//! CMA-ES 优化 — 协方差自适应进化策略的简化实现。
//!
//! 每代并行采样 pop_size 个个体，取前半加权更新均值，
//! 按成功率自适应缩放步长 sigma。

use std::time::Instant;

use rand::SeedableRng;
use rand::rngs::StdRng;
use rayon::prelude::*;

use crate::eval::{evaluate, print_results, read_initial_params, write_best_params};
use crate::params::{DIMS, PARAMS, sample_normal};

pub fn cmaes_optimize(generations: usize, pop_size: usize, games_per_eval: usize) {
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

        // 前半精英加权更新均值
        let mu = (pop_size / 2).max(1);
        let weights: Vec<f64> = (0..mu)
            .map(|i| ((mu as f64 + 0.5).ln() - ((i + 1) as f64).ln()).max(0.01))
            .collect();
        let weight_sum: f64 = weights.iter().sum();
        for i in 0..dims {
            let weighted: f64 = (0..mu).map(|j| weights[j] * sorted[j].0[i]).sum();
            mean[i] = weighted / weight_sum;
        }

        // 步长自适应：成功率高扩张，低收缩
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
