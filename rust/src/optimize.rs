//! CMA-ES 参数优化器 — 自动搜索最优策略参数
//!
//! 简化版 (μ,λ)-ES + 1/5th 成功规则步长自适应。
//!
//! 用法：
//!   cargo run --release --bin ramen_optimize -- [generations] [pop_size] [games_per_eval]
//!   默认: 10 代, 8 个体, 50 局/评估

use std::env;
use std::time::Instant;

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

/// 参数定义：名称、上下界、初始值、是否整数
struct ParamDef {
    name: &'static str,
    min: f64,
    max: f64,
    initial: f64,
    is_int: bool,
}

/// 14 个可调参数的边界定义
static PARAMS: &[ParamDef] = &[
    ParamDef { name: "head_weight",              min: 0.0,   max: 100.0, initial: 15.0,  is_int: false },
    ParamDef { name: "shining_weight",           min: 0.0,   max: 200.0, initial: 40.0,  is_int: false },
    ParamDef { name: "failure_penalty",          min: 0.0,   max: 20.0,  initial: 2.0,   is_int: false },
    ParamDef { name: "vital_rest_threshold",     min: 10.0,  max: 60.0,  initial: 30.0,  is_int: true  },
    ParamDef { name: "motivation_outing_thresh", min: 1.0,   max: 5.0,   initial: 4.0,   is_int: true  },
    ParamDef { name: "friend_outing_score",      min: 0.0,   max: 200.0, initial: 60.0,  is_int: false },
    ParamDef { name: "special_overflow_thresh",  min: 1.0,   max: 4.0,   initial: 3.0,   is_int: true  },
    ParamDef { name: "feeling_overflow_thresh",  min: 3.0,   max: 15.0,  initial: 8.0,   is_int: true  },
    ParamDef { name: "rmj_urgency_margin",       min: 100.0, max: 500.0, initial: 300.0, is_int: true  },
    ParamDef { name: "no_ramen_base_score",      min: 0.0,   max: 200.0, initial: 100.0, is_int: false },
    ParamDef { name: "eat_ramen_base_score",     min: 0.0,   max: 200.0, initial: 50.0,  is_int: false },
    ParamDef { name: "friend_click_bonus",       min: 0.0,   max: 100.0, initial: 25.0,  is_int: false },
    ParamDef { name: "event_vital_bonus",        min: 0.0,   max: 100.0, initial: 30.0,  is_int: false },
    ParamDef { name: "event_motivation_bonus",   min: 0.0,   max: 100.0, initial: 40.0,  is_int: false },
];

/// 参数向量 → RamenStrategy
fn vec_to_strategy(vec: &[f64]) -> RamenStrategy {
    RamenStrategy {
        head_weight: vec[0],
        shining_weight: vec[1],
        failure_penalty: vec[2],
        vital_rest_threshold: vec[3] as i32,
        motivation_outing_threshold: vec[4] as i32,
        friend_outing_score: vec[5],
        special_overflow_threshold: vec[6] as i32,
        feeling_overflow_threshold: vec[7] as i32,
        rmj_urgency_margin: vec[8] as i32,
        no_ramen_base_score: vec[9],
        eat_ramen_base_score: vec[10],
        friend_click_bonus: vec[11],
        event_vital_bonus: vec[12],
        event_motivation_bonus: vec[13],
    }
}

/// 评估一组参数：跑 n 局，返回均分
fn evaluate(vec: &[f64], n: usize) -> f64 {
    let strategy = vec_to_strategy(vec);
    let results: Vec<i32> = (0..n)
        .into_par_iter()
        .filter_map(|_| {
            let mut game = RamenGame::newgame(TEST_UMA, &TEST_DECK, TEST_INHERIT).ok()?;
            let mut rng = rand::rngs::StdRng::from_os_rng();
            game.run_full_game(&strategy, &mut rng).ok()?;
            Some(game.uma().calc_score())
        })
        .collect();

    if results.is_empty() {
        return 0.0;
    }
    results.iter().sum::<i32>() as f64 / results.len() as f64
}

/// Box-Muller 变换生成标准正态分布随机数
fn sample_normal(rng: &mut rand::rngs::StdRng) -> f64 {
    let u1: f64 = rng.random().max(1e-10);
    let u2: f64 = rng.random();
    (-2.0 * u1.ln()).sqrt() * (2.0 * std::f64::consts::PI * u2).cos()
}

fn main() {
    let args: Vec<String> = env::args().collect();
    let generations: usize = args.get(1).and_then(|s| s.parse().ok()).unwrap_or(10);
    let pop_size: usize = args.get(2).and_then(|s| s.parse().ok()).unwrap_or(8);
    let games_per_eval: usize = args.get(3).and_then(|s| s.parse().ok()).unwrap_or(50);

    println!("初始化 gamedata...");
    if let Err(e) = init_global() {
        eprintln!("init_global 失败: {e}");
        std::process::exit(1);
    }

    let dims = PARAMS.len();
    let lower: Vec<f64> = PARAMS.iter().map(|p| p.min).collect();
    let upper: Vec<f64> = PARAMS.iter().map(|p| p.max).collect();
    let is_int: Vec<bool> = PARAMS.iter().map(|p| p.is_int).collect();

    // 初始均值 = 默认参数
    let mut mean: Vec<f64> = PARAMS.iter().map(|p| p.initial).collect();
    // 初始步长 = 15% 参数范围
    let mut sigma: Vec<f64> = PARAMS.iter().map(|p| (p.max - p.min) * 0.15).collect();

    // 评估基线
    println!("评估基线 ({} 局)...", games_per_eval);
    let baseline_score = evaluate(&mean, games_per_eval);
    println!("基线均分: {:.0}\n", baseline_score);

    let mut best_score = baseline_score;
    let mut best_params = mean.clone();

    let start = Instant::now();

    for gen in 0..generations {
        let parent_score = best_score;

        // 采样 λ 个 offspring
        let offspring: Vec<(Vec<f64>, f64)> = (0..pop_size)
            .into_par_iter()
            .map(|_| {
                let mut rng = rand::rngs::StdRng::from_os_rng();
                let mut vec = vec![0.0; dims];
                for i in 0..dims {
                    vec[i] = mean[i] + sigma[i] * sample_normal(&mut rng);
                    if vec[i] < lower[i] { vec[i] = lower[i]; }
                    if vec[i] > upper[i] { vec[i] = upper[i]; }
                    if is_int[i] { vec[i] = vec[i].round(); }
                }
                let score = evaluate(&vec, games_per_eval);
                (vec, score)
            })
            .collect();

        // 按分数降序排列
        let mut sorted = offspring;
        sorted.sort_by(|a, b| b.1.partial_cmp(&a.1).unwrap_or(std::cmp::Ordering::Equal));

        // 统计超过 parent 的比例（1/5th 成功规则）
        let improvements = sorted.iter().filter(|(_, s)| *s > parent_score).count();
        let success_rate = improvements as f64 / pop_size as f64;

        // 精英选择：更新全局最优
        if sorted[0].1 > best_score {
            best_score = sorted[0].1;
            best_params = sorted[0].0.clone();
        }

        // 重组：加权平均 top μ
        let mu = (pop_size / 2).max(1);
        let weights: Vec<f64> = (0..mu)
            .map(|i| ((mu as f64 + 0.5).ln() - ((i + 1) as f64).ln()).max(0.01))
            .collect();
        let weight_sum: f64 = weights.iter().sum();
        for i in 0..dims {
            let weighted: f64 = (0..mu).map(|j| weights[j] * sorted[j].0[i]).sum();
            mean[i] = weighted / weight_sum;
        }

        // 步长自适应：成功率 > 20% 增大，否则缩小
        let factor = if success_rate > 0.2 { 1.15 } else { 0.85 };
        for i in 0..dims {
            sigma[i] *= factor;
            sigma[i] = sigma[i].max((upper[i] - lower[i]) * 0.01);
        }

        let delta = best_score - baseline_score;
        println!(
            "Gen {}/{}: best={:.0} (Δ{:+.0}) sr={:.0}% σ×{:.2}",
            gen + 1, generations, best_score, delta, success_rate * 100.0, factor
        );
    }

    let elapsed = start.elapsed();

    // 输出结果
    let best_strategy = vec_to_strategy(&best_params);
    let best_json_compact = serde_json::to_string(&best_strategy).unwrap();
    let best_json_pretty = serde_json::to_string_pretty(&best_strategy).unwrap();

    println!();
    println!("════════════════════════════════════════");
    println!("CMA-ES 优化完成");
    println!("  代数={}  个体={}  局/评估={}", generations, pop_size, games_per_eval);
    println!("  耗时={:.1}s ({:.0} 局/秒)",
             elapsed.as_secs_f64(),
             (pop_size * games_per_eval * generations + games_per_eval) as f64 / elapsed.as_secs_f64());
    println!();
    println!("基线均分: {:.0}", baseline_score);
    println!("最优均分: {:.0}", best_score);
    println!("提升: {:+.0} ({:+.1}%)",
             best_score - baseline_score,
             (best_score - baseline_score) / baseline_score * 100.0);
    println!();
    println!("最优参数 (可直接用于 RAMEN_STRATEGY):");
    println!("{}", best_json_compact);
    println!();
    println!("最优参数 (格式化):");
    println!("{}", best_json_pretty);

    // 参数变化对比
    println!();
    println!("参数变化:");
    println!("  {:<28} {:>10} → {:>10}  Δ", "参数", "默认", "优化");
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
