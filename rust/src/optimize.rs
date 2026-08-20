//! 参数优化器 — CMA-ES + 贝叶斯优化
//!
//! 用法：
//!   cargo run --release --bin ramen_optimize -- cmaes [generations] [pop_size] [games_per_eval]
//!   cargo run --release --bin ramen_optimize -- bayes [n_init] [n_iter] [games_per_eval]
//!   默认: cmaes 10 8 50
//!
//! 迭代：如果 STRATEGY_IN 指向的 strategy_optimized.json 存在，以上次结果为起点。
//! 输出：最优参数写入 STRATEGY_OUT 指向的文件。

use std::env;
use std::time::Instant;

use rand::prelude::*;
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

struct ParamDef {
    name: &'static str,
    min: f64,
    max: f64,
    initial: f64,
    is_int: bool,
}

static PARAMS: &[ParamDef] = &[
    ParamDef { name: "head_weight",              min: 0.0,   max: 100.0, initial: 15.0,  is_int: false },
    ParamDef { name: "shining_weight",           min: 0.0,   max: 200.0, initial: 40.0,  is_int: false },
    ParamDef { name: "failure_penalty",          min: 0.0,   max: 20.0,  initial: 2.0,   is_int: false },
    ParamDef { name: "vital_rest_threshold",     min: 10.0,  max: 60.0,  initial: 30.0,  is_int: true  },
    ParamDef { name: "motivation_outing_thresh", min: 1.0,   max: 5.0,   initial: 4.0,   is_int: true  },
    ParamDef { name: "friend_outing_score",      min: 0.0,   max: 200.0, initial: 60.0,  is_int: false },
    ParamDef { name: "special_overflow_thresh",  min: 1.0,   max: 4.0,   initial: 3.0,   is_int: true  },
    ParamDef { name: "feeling_overflow_thresh",  min: 3.0,   max: 15.0,  initial: 8.0,   is_int: true  },
    ParamDef { name: "rmj_urgency_margin",        min: 100.0, max: 500.0, initial: 300.0, is_int: true  },
    ParamDef { name: "no_ramen_base_score",      min: 0.0,   max: 200.0, initial: 100.0, is_int: false },
    ParamDef { name: "eat_ramen_base_score",     min: 0.0,   max: 200.0, initial: 50.0,  is_int: false },
    ParamDef { name: "friend_click_bonus",       min: 0.0,   max: 100.0, initial: 25.0,  is_int: false },
    ParamDef { name: "event_vital_bonus",        min: 0.0,   max: 100.0, initial: 30.0,  is_int: false },
    ParamDef { name: "event_motivation_bonus",   min: 0.0,   max: 100.0, initial: 40.0,  is_int: false },
];

const DIMS: usize = 14;

// ── 共用工具 ──────────────────────────────────────────────

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

fn strategy_to_vec(s: &RamenStrategy) -> Vec<f64> {
    vec![
        s.head_weight, s.shining_weight, s.failure_penalty,
        s.vital_rest_threshold as f64, s.motivation_outing_threshold as f64,
        s.friend_outing_score, s.special_overflow_threshold as f64,
        s.feeling_overflow_threshold as f64, s.rmj_urgency_margin as f64,
        s.no_ramen_base_score, s.eat_ramen_base_score, s.friend_click_bonus,
        s.event_vital_bonus, s.event_motivation_bonus,
    ]
}

fn clamp_vec(vec: &mut [f64]) {
    for i in 0..DIMS {
        if vec[i] < PARAMS[i].min { vec[i] = PARAMS[i].min; }
        if vec[i] > PARAMS[i].max { vec[i] = PARAMS[i].max; }
        if PARAMS[i].is_int { vec[i] = vec[i].round(); }
    }
}

fn evaluate(vec: &[f64], n: usize) -> f64 {
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

fn sample_normal(rng: &mut StdRng) -> f64 {
    let u1: f64 = rng.random::<f64>().max(1e-10);
    let u2: f64 = rng.random::<f64>();
    (-2.0 * u1.ln()).sqrt() * (2.0 * std::f64::consts::PI * u2).cos()
}

fn sample_random(rng: &mut StdRng) -> Vec<f64> {
    let mut vec = vec![0.0; DIMS];
    for i in 0..DIMS {
        vec[i] = PARAMS[i].min + rng.random::<f64>() * (PARAMS[i].max - PARAMS[i].min);
        if PARAMS[i].is_int { vec[i] = vec[i].round(); }
    }
    vec
}

fn normalize(vec: &[f64]) -> Vec<f64> {
    vec.iter().enumerate()
        .map(|(i, &v)| {
            let range = PARAMS[i].max - PARAMS[i].min;
            ((v - PARAMS[i].min) / range).clamp(0.0, 1.0)
        })
        .collect()
}

fn denormalize(nvec: &[f64]) -> Vec<f64> {
    let mut vec = vec![0.0; DIMS];
    for i in 0..DIMS {
        let range = PARAMS[i].max - PARAMS[i].min;
        vec[i] = PARAMS[i].min + nvec[i].clamp(0.0, 1.0) * range;
        if PARAMS[i].is_int { vec[i] = vec[i].round(); }
    }
    vec
}

/// 读取上次优化结果作为起点，没有则用默认参数
fn read_initial_params() -> Vec<f64> {
    let path = std::env::var("STRATEGY_IN")
        .unwrap_or_else(|_| "strategy_optimized.json".to_string());
    match std::fs::read_to_string(&path) {
        Ok(json) => {
            match serde_json::from_str::<RamenStrategy>(&json) {
                Ok(strategy) => {
                    let v = strategy_to_vec(&strategy);
                    let mut p = v.clone();
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

/// 写入最优参数到文件，供下次迭代和 APK 打包使用
fn write_best_params(params: &[f64]) {
    let strategy = vec_to_strategy(params);
    let json = serde_json::to_string_pretty(&strategy).unwrap_or_default();
    let path = std::env::var("STRATEGY_OUT")
        .unwrap_or_else(|_| "strategy_optimized.json".to_string());
    match std::fs::write(&path, json) {
        Ok(_) => println!("最优策略已写入: {}", path),
        Err(e) => println!("策略写入失败 ({}): {}", path, e),
    }
}

fn print_results(baseline: f64, best: f64, best_params: &[f64], elapsed: std::time::Duration, mode: &str, total_games: usize) {
    let best_strategy = vec_to_strategy(best_params);
    let best_json_compact = serde_json::to_string(&best_strategy).unwrap();
    let best_json_pretty = serde_json::to_string_pretty(&best_strategy).unwrap();

    println!();
    println!("════════════════════════════════════════");
    println!("{} 优化完成", mode);
    println!("  耗时={:.1}s ({:.0} 局/秒)", elapsed.as_secs_f64(),
             total_games as f64 / elapsed.as_secs_f64());
    println!();
    println!("基线均分: {:.0}", baseline);
    println!("最优均分: {:.0}", best);
    println!("提升: {:+.0} ({:+.1}%)", best - baseline, (best - baseline) / baseline * 100.0);
    println!();
    println!("最优参数 (RAMEN_STRATEGY):");
    println!("{}", best_json_compact);
    println!();
    println!("最优参数 (格式化):");
    println!("{}", best_json_pretty);
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

// ── CMA-ES ────────────────────────────────────────────────

fn cmaes_optimize(generations: usize, pop_size: usize, games_per_eval: usize) {
    let dims = DIMS;
    let lower: Vec<f64> = PARAMS.iter().map(|p| p.min).collect();
    let upper: Vec<f64> = PARAMS.iter().map(|p| p.max).collect();
    let is_int: Vec<bool> = PARAMS.iter().map(|p| p.is_int).collect();

    let init_params = read_initial_params();
    let mut mean: Vec<f64> = init_params.clone();
    let mut sigma: Vec<f64> = PARAMS.iter().map(|p| (p.max - p.min) * 0.15).collect();

    println!("评估基线 ({} 局)...", games_per_eval);
    let baseline_score = evaluate(&mean, games_per_eval);
    println!("基线均分: {:.0}\n", baseline_score);

    let mut best_score = baseline_score;
    let mut best_params = mean.clone();
    let start = Instant::now();

    for gen in 0..generations {
        let parent_score = best_score;

        let offspring: Vec<(Vec<f64>, f64)> = (0..pop_size)
            .into_par_iter()
            .map(|_| {
                let mut rng = StdRng::from_os_rng();
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

        let mut sorted = offspring;
        sorted.sort_by(|a, b| b.1.partial_cmp(&a.1).unwrap_or(std::cmp::Ordering::Equal));

        let improvements = sorted.iter().filter(|(_, s)| *s > parent_score).count();
        let success_rate = improvements as f64 / pop_size as f64;

        if sorted[0].1 > best_score {
            best_score = sorted[0].1;
            best_params = sorted[0].0.clone();
        }

        let mu = (pop_size / 2).max(1);
        let weights: Vec<f64> = (0..mu)
            .map(|i| ((mu as f64 + 0.5).ln() - ((i + 1) as f64).ln()).max(0.01))
            .collect();
        let weight_sum: f64 = weights.iter().sum();
        for i in 0..dims {
            let weighted: f64 = (0..mu).map(|j| weights[j] * sorted[j].0[i]).sum();
            mean[i] = weighted / weight_sum;
        }

        let factor = if success_rate > 0.2 { 1.15 } else { 0.85 };
        for i in 0..dims {
            sigma[i] *= factor;
            sigma[i] = sigma[i].max((upper[i] - lower[i]) * 0.01);
        }

        let delta = best_score - baseline_score;
        println!("Gen {}/{}: best={:.0} (Δ{:+.0}) sr={:.0}% σ×{:.2}",
            gen + 1, generations, best_score, delta, success_rate * 100.0, factor);
    }

    let elapsed = start.elapsed();
    let total = pop_size * games_per_eval * generations + games_per_eval;
    print_results(baseline_score, best_score, &best_params, elapsed, "CMA-ES", total);
    write_best_params(&best_params);
}

// ── 贝叶斯优化 ────────────────────────────────────────────

fn solve_linear_system(a: &mut [Vec<f64>], b: &mut [f64]) -> Vec<f64> {
    let n = b.len();
    for k in 0..n {
        let mut max_row = k;
        let mut max_val = a[k][k].abs();
        for i in (k + 1)..n {
            if a[i][k].abs() > max_val { max_val = a[i][k].abs(); max_row = i; }
        }
        if max_row != k { a.swap(k, max_row); b.swap(k, max_row); }
        for i in (k + 1)..n {
            let factor = a[i][k] / a[k][k].max(1e-12);
            for j in k..n { a[i][j] -= factor * a[k][j]; }
            b[i] -= factor * b[k];
        }
    }
    let mut x = vec![0.0; n];
    for i in (0..n).rev() {
        x[i] = b[i];
        for j in (i + 1)..n { x[i] -= a[i][j] * x[j]; }
        x[i] /= a[i][i].max(1e-12);
    }
    x
}

fn fit_linear(X: &[Vec<f64>], y: &[f64]) -> Vec<f64> {
    let n = X.len();
    let m = DIMS + 1;
    let mut a = vec![vec![0.0; m]; m];
    let mut b = vec![0.0; m];
    for i in 0..n {
        let row: Vec<f64> = std::iter::once(1.0).chain(X[i].iter().copied()).collect();
        for j in 0..m {
            for k in 0..m { a[j][k] += row[j] * row[k]; }
            b[j] += row[j] * y[i];
        }
    }
    for i in 0..m { a[i][i] += 1.0; }
    solve_linear_system(&mut a, &mut b)
}

fn predict_linear(w: &[f64], x: &[f64]) -> f64 {
    let mut y = w[0];
    for i in 0..DIMS { y += w[i + 1] * x[i]; }
    y
}

fn uncertainty(x: &[f64], train: &[Vec<f64>]) -> f64 {
    let mut min_d2 = f64::MAX;
    for xi in train {
        let d2: f64 = x.iter().zip(xi.iter()).map(|(a, b)| (a - b).powi(2)).sum();
        if d2 < min_d2 { min_d2 = d2; }
    }
    min_d2.sqrt()
}

fn ucb(w: &[f64], x: &[f64], train: &[Vec<f64>], kappa: f64) -> f64 {
    let mean = predict_linear(w, x);
    let sigma = uncertainty(x, train);
    mean + kappa * sigma
}

fn bayesian_optimize(n_init: usize, n_iter: usize, games_per_eval: usize) {
    let init_params = read_initial_params();

    println!("评估基线 ({} 局)...", games_per_eval);
    let baseline_score = evaluate(&init_params, games_per_eval);
    println!("基线均分: {:.0}\n", baseline_score);

    let mut samples: Vec<(Vec<f64>, f64)> = Vec::new();

    println!("初始采样 ({} 个点)...", n_init);
    samples.push((init_params.clone(), baseline_score));
    let remaining = n_init.saturating_sub(1);
    let init_start = Instant::now();
    let init_results: Vec<(Vec<f64>, f64)> = (0..remaining)
        .into_par_iter()
        .map(|_| {
            let mut rng = StdRng::from_os_rng();
            let vec = sample_random(&mut rng);
            let score = evaluate(&vec, games_per_eval);
            (vec, score)
        })
        .collect();
    for r in init_results { samples.push(r); }
    println!("  初始采样完成 ({:.1}s)\n", init_start.elapsed().as_secs_f64());

    let mut best_score = samples.iter().map(|(_, s)| *s).fold(f64::MIN, f64::max);
    let mut best_params = samples.iter().max_by(|a, b| a.1.partial_cmp(&b.1).unwrap()).unwrap().0.clone();

    let start = Instant::now();

    for iter in 0..n_iter {
        let X: Vec<Vec<f64>> = samples.iter().map(|(v, _)| normalize(v)).collect();
        let y: Vec<f64> = samples.iter().map(|(_, s)| *s).collect();
        let w = fit_linear(&X, &y);

        let residuals: Vec<f64> = X.iter().zip(y.iter())
            .map(|(x, &yv)| yv - predict_linear(&w, x))
            .collect();
        let resid_std = (residuals.iter().map(|r| r * r).sum::<f64>() / residuals.len().max(1) as f64).sqrt();
        let kappa = resid_std * 2.0;

        let n_candidates = 2000;
        let train_norm: Vec<Vec<f64>> = X.clone();

        let candidates: Vec<(Vec<f64>, f64)> = (0..n_candidates)
            .into_par_iter()
            .map(|_| {
                let mut rng = StdRng::from_os_rng();
                let mut nvec = if rng.random::<f64>() < 0.5 {
                    (0..DIMS).map(|_| rng.random::<f64>()).collect::<Vec<_>>()
                } else {
                    let best_norm = normalize(&best_params);
                    let scale = 0.15;
                    (0..DIMS).map(|i| {
                        let v = best_norm[i] + sample_normal(&mut rng) * scale;
                        v.clamp(0.0, 1.0)
                    }).collect::<Vec<_>>()
                };
                let acq = ucb(&w, &nvec, &train_norm, kappa);
                (nvec, acq)
            })
            .collect();

        let (best_nvec, _) = candidates.iter()
            .max_by(|a, b| a.1.partial_cmp(&b.1).unwrap_or(std::cmp::Ordering::Equal))
            .unwrap();

        let new_vec = denormalize(best_nvec);
        let new_score = evaluate(&new_vec, games_per_eval);
        samples.push((new_vec.clone(), new_score));

        if new_score > best_score {
            best_score = new_score;
            best_params = new_vec;
        }

        let delta = best_score - baseline_score;
        let n_evals = n_init + iter + 1;
        println!("BO {}/{}: best={:.0} (Δ{:+.0}) new={:.0} κ={:.0} evals={}",
            iter + 1, n_iter, best_score, delta, new_score, kappa, n_evals);
    }

    let elapsed = start.elapsed();
    let total = (n_init + n_iter) * games_per_eval;
    print_results(baseline_score, best_score, &best_params, elapsed, "贝叶斯优化", total);
    write_best_params(&best_params);
}

// ── main ──────────────────────────────────────────────────

fn main() {
    let args: Vec<String> = env::args().collect();
    let mode = args.get(1).map(|s| s.as_str()).unwrap_or("cmaes");

    println!("初始化 gamedata...");
    if let Err(e) = init_global() {
        eprintln!("init_global 失败: {e}");
        std::process::exit(1);
    }

    match mode {
        "bayes" | "bo" => {
            let n_init = args.get(2).and_then(|s| s.parse().ok()).unwrap_or(10);
            let n_iter = args.get(3).and_then(|s| s.parse().ok()).unwrap_or(20);
            let games = args.get(4).and_then(|s| s.parse().ok()).unwrap_or(50);
            println!("贝叶斯优化: 初始采样={} 迭代={} 局/评估={}\n", n_init, n_iter, games);
            bayesian_optimize(n_init, n_iter, games);
        }
        _ => {
            let gens = args.get(1).and_then(|s| s.parse().ok()).unwrap_or(10);
            let pop = args.get(2).and_then(|s| s.parse().ok()).unwrap_or(8);
            let games = args.get(3).and_then(|s| s.parse().ok()).unwrap_or(50);
            println!("CMA-ES: 代数={} 个体={} 局/评估={}\n", gens, pop, games);
            cmaes_optimize(gens, pop, games);
        }
    }
}
