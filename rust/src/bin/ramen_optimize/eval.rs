//! 评估与策略文件 IO — 把参数向量跑成分数，把最优参数存取为 JSON，
//! 并汇总打印优化结果。

use std::time::Duration;

use rand::SeedableRng;
use rand::rngs::StdRng;
use rayon::prelude::*;

use uma_jni::ramen_strategy::RamenStrategy;
use uma_jni::testbed::{TEST_DECK, TEST_INHERIT, TEST_UMA};
use umasim::game::ramen::RamenGame;

use crate::params::{PARAMS, clamp_vec, strategy_to_vec, vec_to_strategy};

/// 用固定测试台跑 n 局取平均分
pub fn evaluate(vec: &[f64], n: usize) -> f64 {
    let strategy = vec_to_strategy(vec);
    let results: Vec<i32> = (0..n)
        .into_par_iter()
        .filter_map(|_| {
            let mut game = RamenGame::newgame(TEST_UMA, &TEST_DECK, TEST_INHERIT).ok()?;
            let mut rng = StdRng::from_os_rng();
            game.run_full_game(&strategy, &mut rng).ok()?;
            Some(game.uma().calc_score())
        })
        .collect();
    if results.is_empty() { return 0.0; }
    results.iter().sum::<i32>() as f64 / results.len() as f64
}

/// 从 STRATEGY_IN 指向的 JSON 读上次结果作为起点；没有则用默认参数。
pub fn read_initial_params() -> Vec<f64> {
    let path = std::env::var("STRATEGY_IN")
        .unwrap_or_else(|_| "strategy_optimized.json".to_string());
    match std::fs::read_to_string(&path) {
        Ok(json) => {
            match serde_json::from_str::<RamenStrategy>(&json) {
                Ok(strategy) => {
                    let mut p = strategy_to_vec(&strategy);
                    clamp_vec(&mut p);
                    println!("从 {} 加载上次优化结果作为起点", path);
                    p
                }
                Err(_) => {
                    println!("{} 解析失败，用默认参数", path);
                    PARAMS.iter().map(|p| p.initial).collect()
                }
            }
        }
        Err(_) => {
            println!("无上次优化结果（{}），用默认参数", path);
            PARAMS.iter().map(|p| p.initial).collect()
        }
    }
}

/// 最优参数写入 STRATEGY_OUT 指向的文件
pub fn write_best_params(params: &[f64]) {
    let strategy = vec_to_strategy(params);
    let json = serde_json::to_string_pretty(&strategy).unwrap_or_default();
    let path = std::env::var("STRATEGY_OUT")
        .unwrap_or_else(|_| "strategy_optimized.json".to_string());
    match std::fs::write(&path, json) {
        Ok(_) => println!("最优策略已写入: {}", path),
        Err(e) => println!("策略写入失败 ({}): {}", path, e),
    }
}

/// 汇总打印优化结果：基线对比、最优参数 JSON、逐参数变化
pub fn print_results(baseline: f64, best: f64, best_params: &[f64], elapsed: Duration, mode: &str, total_games: usize) {
    println!("════════════════════════════════════════");
    println!("优化完成 ({})", mode);
    println!("  基线均分:  {:.0}", baseline);
    println!("  最优均分:  {:.0}", best);
    println!("  提升:      {:+.0} ({:+.1}%)", best - baseline, (best - baseline) / baseline * 100.0);
    println!("  总局数:    {}", total_games);
    println!("  耗时:      {:.1}分", elapsed.as_secs_f64() / 60.0);
    println!();

    let best_strategy = vec_to_strategy(best_params);
    let best_json_compact = serde_json::to_string(&best_strategy).unwrap_or_default();
    println!("最优参数 (RAMEN_STRATEGY):");
    println!("{}", best_json_compact);
    println!();
    println!("参数变化:");
    println!("  {:<28} {:>10} → {:>10}  Δ", "参数", "起点", "优化");
    for (i, p) in PARAMS.iter().enumerate() {
        let old = p.initial;
        let new = best_params[i];
        let delta = new - old;
        let marker = if delta.abs() > 0.01 { "*" } else { "" };
        if p.is_int {
            println!("  {:<28} {:>10.0} → {:>10.0}  Δ{:+.0} {}", p.name, old, new, delta, marker);
        } else {
            println!("  {:<28} {:>10.1} → {:>10.1}  Δ{:+.1} {}", p.name, old, new, delta, marker);
        }
    }
    println!("════════════════════════════════════════");
}
