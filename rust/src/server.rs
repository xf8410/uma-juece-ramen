//! 云端搜索服务器 — 手机发状态，云端跑蒙特卡洛，返回推荐
//!
//! POST /search  { "state": {...}, "config": {...} }  → SearchResult
//! GET  /health  → {"ok":true}
//!
//! 环境变量：
//!   PORT=8080  监听端口
//!   STRATEGY_IN=strategy_optimized.json  优化策略文件路径

use std::io::Read;
use std::net::SocketAddr;

use rand::{SeedableRng, rngs::StdRng};
use serde::Deserialize;

use uma_jni::ramen_strategy::RamenStrategy;
use uma_jni::{inject_state, run_flat_search, SearchConfig, SearchResult, RuntimeState};
use umasim::game::{Game, InheritInfo, ramen::RamenGame};
use umasim::gamedata::init_global;

#[derive(Deserialize)]
struct SearchPayload {
    state: serde_json::Value,
    config: serde_json::Value,
}

fn load_strategy() -> RamenStrategy {
    let path = std::env::var("STRATEGY_IN").unwrap_or_else(|_| "strategy_optimized.json".to_string());
    match std::fs::read_to_string(&path) {
        Ok(json) => {
            match serde_json::from_str::<RamenStrategy>(&json) {
                Ok(s) => { println!("策略已加载: {}", path); s }
                Err(_) => { println!("策略解析失败，用默认"); RamenStrategy::default() }
            }
        }
        Err(_) => { println!("无策略文件，用默认"); RamenStrategy::default() }
    }
}

fn handle_search(body: &str, strategy: &RamenStrategy) -> String {
    let payload: SearchPayload = match serde_json::from_str(body) {
        Ok(p) => p,
        Err(e) => return serde_json::to_string(&SearchResult {
            ok: false, action: String::new(), action_display: String::new(),
            score_mean: 0.0, search_n: 0, elapsed_ms: 0, all_actions: vec![],
            error: Some(format!("parse payload: {e}")),
        }).unwrap_or_default(),
    };

    let state_json = payload.state.to_string();
    let config_json = payload.config.to_string();

    let result = std::panic::catch_unwind(|| {
        let state: RuntimeState = serde_json::from_str(&state_json)
            .map_err(|e| anyhow::anyhow!("parse state: {e}"))?;
        let config: SearchConfig = serde_json::from_str(&config_json)
            .map_err(|e| anyhow::anyhow!("parse config: {e}"))?;

        let inherit = InheritInfo {
            blue_count: config.blue_count,
            extra_count: config.extra_count,
        };

        let mut game = RamenGame::newgame(config.uma_id, &config.cards, inherit)
            .map_err(|e| anyhow::anyhow!("newgame: {e}"))?;

        inject_state(&mut game, &state)?;

        let mut rng = StdRng::from_os_rng();
        game.distribute_all(&mut rng).ok();
        game.distribute_hint(&mut rng).ok();

        run_flat_search(&game, config.search_n, strategy)
    });

    let search_result = match result {
        Ok(Ok(r)) => r,
        Ok(Err(e)) => SearchResult {
            ok: false, action: String::new(), action_display: String::new(),
            score_mean: 0.0, search_n: 0, elapsed_ms: 0, all_actions: vec![],
            error: Some(e.to_string()),
        },
        Err(_) => SearchResult {
            ok: false, action: String::new(), action_display: String::new(),
            score_mean: 0.0, search_n: 0, elapsed_ms: 0, all_actions: vec![],
            error: Some("panic during search".to_string()),
        },
    };

    serde_json::to_string(&search_result).unwrap_or_else(|_| r#"{"ok":false}"#.to_string())
}

fn main() {
    println!("初始化 gamedata...");
    if let Err(e) = init_global() {
        eprintln!("init_global 失败: {e}");
        std::process::exit(1);
    }

    let strategy = load_strategy();
    let port: u16 = std::env::var("PORT")
        .ok()
        .and_then(|s| s.parse().ok())
        .unwrap_or(8080);
    let addr: SocketAddr = format!("0.0.0.0:{port}").parse().unwrap();

    let listener = std::net::TcpListener::bind(addr).expect("bind failed");
    println!("搜索服务已启动: http://{}", addr);

    for stream in listener.incoming() {
        let stream = match stream { Ok(s) => s, Err(_) => continue };
        let strategy = strategy.clone();
        std::thread::spawn(move || {
            let mut buf = [0u8; 65536];
            let n = match stream.peek(&mut buf) { Ok(n) => n, Err(_) => return };
            let req = String::from_utf8_lossy(&buf[..n]);

            let (status, body) = if req.contains("POST /search") {
                // Extract JSON body from HTTP request
                let body_start = req.find("\r\n\r\n").map(|i| i + 4).unwrap_or(0);
                let json_body = &req[body_start..];
                let result = handle_search(json_body, &strategy);
                ("200 OK", result)
            } else if req.contains("GET /health") {
                ("200 OK", r#"{"ok":true}"#.to_string())
            } else if req.contains("OPTIONS") {
                ("204 No Content", String::new())
            } else {
                ("404 Not Found", r#"{"error":"not found"}"#.to_string())
            };

            let response = format!(
                "HTTP/1.1 {}\r\nContent-Type: application/json\r\nAccess-Control-Allow-Origin: *\r\nAccess-Control-Allow-Methods: POST, GET, OPTIONS\r\nAccess-Control-Allow-Headers: Content-Type\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{}",
                status, body.len(), body
            );
            use std::io::Write;
            let mut stream = stream;
            let _ = stream.write_all(response.as_bytes());
            let _ = stream.flush();
        });
    }
}
