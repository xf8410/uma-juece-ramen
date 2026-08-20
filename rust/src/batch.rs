//! 批量模拟器 — 运行 N 局拉面杯，输出分数统计
//!
//! 用法：
//!   cd <umaai-rs 根目录>  （需要 gamedata/ 在当前目录）
//!   cargo run --release --manifest-path <path-to-rust>/Cargo.toml --bin ramen_batch -- 1000
//!
//! 或设置 UMAI_DATA_DIR 环境变量指向 gamedata/ 目录。
//!
//! 可通过环境变量传 JSON 覆盖策略参数：
//!   RAMEN_STRATEGY='{"vital_rest_threshold":35,"head_weight":20.0}' cargo run --release --bin ramen_batch -- 1000

use std::env;
use std::time::Instant;

use rand::SeedableRng;
use rayon::prelude::*;

use uma_jni::ramen_strategy::RamenStrategy;
use umasim::game::{Game, InheritInfo, ramen::RamenGame};
use umasim::gamedata::init_global;

const TEST_UMA: u32 = 102601;
const TEST_DECK: [u32; 6] = [302424, 302894, 303044, 302924, 303024, 303054];
const TEST_INHERIT: InheritInfo = InheritInfo {
    blue_count: [15, 3, 0, 0, 0],
    extra_count: [0, 30, 0, 0, 30, 30],
};

struct SimResult {
    score: i32,
    pt: i32,
    rmj_success: [bool; 3],
    final_turn: i32,
}

fn run_one(strategy: &RamenStrategy) -> Option<SimResult> {
    let mut game = RamenGame::newgame(TEST_UMA, &TEST_DECK, TEST_INHERIT).ok()?;
    let mut rng = rand::rngs::StdRng::from_os_rng();
    game.run_full_game(strategy, &mut rng).ok()?;

    let score = game.uma().calc_score();
    let pt = game.uma().total_pt();
    let rmj = game.ramen.rmj_results.clone();
    let final_turn = game.turn();

    Some(SimResult {
        score,
        pt,
        rmj_success: [
            rmj.get(0).copied().unwrap_or(false),
            rmj.get(1).copied().unwrap_or(false),
            rmj.get(2).copied().unwrap_or(false),
        ],
        final_turn,
    })
}

fn main() {
    let args: Vec<String> = env::args().collect();
    let n: usize = args
        .get(1)
        .and_then(|s| s.parse().ok())
        .unwrap_or(100);

    // 初始化 gamedata
    println!("初始化 gamedata...");
    if let Err(e) = init_global() {
        eprintln!("init_global 失败: {e}");
        eprintln!("请从 umaai-rs 根目录运行，或设置 UMAI_DATA_DIR=/path/to/gamedata");
        std::process::exit(1);
    }

    // 从环境变量加载策略覆盖
    let mut strategy = RamenStrategy::default();
    if let Ok(json) = env::var("RAMEN_STRATEGY") {
        if let Ok(override_strategy) = serde_json::from_str::<RamenStrategy>(&json) {
            strategy = override_strategy;
            println!("策略覆盖: {strategy:?}");
        }
    }
    println!("策略参数: {strategy:#?}");
    println!("开始模拟 {} 局...\n", n);

    let start = Instant::now();

    let results: Vec<SimResult> = (0..n)
        .into_par_iter()
        .filter_map(|_| run_one(&strategy))
        .collect();

    let elapsed = start.elapsed();
    let ok = results.len();
    let fail = n - ok;

    if ok == 0 {
        eprintln!("全部模拟失败！");
        std::process::exit(1);
    }

    // 分数统计
    let mut scores: Vec<i32> = results.iter().map(|r| r.score).collect();
    scores.sort_by(|a, b| a.cmp(b));

    let sum: i64 = scores.iter().map(|&s| s as i64).sum();
    let mean = sum as f64 / ok as f64;
    let max = scores.last().unwrap();
    let min = scores.first().unwrap();
    let median = scores[ok / 2];
    let p25 = scores[ok / 4];
    let p75 = scores[ok * 3 / 4];
    let p90 = scores[(ok * 9 / 10).min(ok - 1)];

    // PT 统计
    let pts: Vec<i32> = results.iter().map(|r| r.pt).collect();
    let pt_mean = pts.iter().sum::<i32>() as f64 / ok as f64;
    let pt_max = pts.iter().max().unwrap();

    // RMJ 成功率
    let rmj1 = results.iter().filter(|r| r.rmj_success[0]).count();
    let rmj2 = results.iter().filter(|r| r.rmj_success[1]).count();
    let rmj3 = results.iter().filter(|r| r.rmj_success[2]).count();
    let rmj_all = results.iter().filter(|r| r.rmj_success.iter().all(|&b| b)).count();

    // 分数分布（按 500 分一档）
    let mut dist: std::collections::BTreeMap<i32, usize> = std::collections::BTreeMap::new();
    for &s in &scores {
        let bucket = (s / 500) * 500;
        *dist.entry(bucket).or_insert(0) += 1;
    }

    println!("════════════════════════════════════════");
    println!("模拟结果 ({} 局, 成功 {} 局, 失败 {} 局)", n, ok, fail);
    println!("════════════════════════════════════════");
    println!("评分统计:");
    println!("  均分:  {:.0}", mean);
    println!("  中位:  {}", median);
    println!("  最高:  {}", max);
    println!("  最低:  {}", min);
    println!("  P25:   {}", p25);
    println!("  P75:   {}", p75);
    println!("  P90:   {}", p90);
    println!();
    println!("PT 统计:");
    println!("  均PT:  {:.0}", pt_mean);
    println!("  最高:  {}", pt_max);
    println!();
    println!("RMJ 成功率:");
    println!("  年1:   {:.1}% ({}/{})", rmj1 as f64 / ok as f64 * 100.0, rmj1, ok);
    println!("  年2:   {:.1}% ({}/{})", rmj2 as f64 / ok as f64 * 100.0, rmj2, ok);
    println!("  年3:   {:.1}% ({}/{})", rmj3 as f64 / ok as f64 * 100.0, rmj3, ok);
    println!("  全通:  {:.1}% ({}/{})", rmj_all as f64 / ok as f64 * 100.0, rmj_all, ok);
    println!();
    println!("分数分布:");
    for (bucket, count) in &dist {
        let bar = "█".repeat((*count * 40 / ok).max(1));
        println!("  {:>6}: {:>4} {:>5.1}% {}", bucket, count, *count as f64 / ok as f64 * 100.0, bar);
    }
    println!();
    println!("耗时:  {:.2}s ({:.1} 局/秒)", elapsed.as_secs_f64(), ok as f64 / elapsed.as_secs_f64());
    println!("════════════════════════════════════════");
}
