//! 贝叶斯优化 — 线性代理模型 + UCB 采集函数。
//!
//! 初始随机采样后，每轮：
//! 1. 对全部已采样点拟合线性模型（岭回归风格的正规方程）；
//! 2. 并行生成候选点（一半全空间均匀、一半围绕当前最优扰动）；
//! 3. 按 UCB（预测 + κ×不确定性）选下一个评估点。

use std::time::Instant;

use rand::Rng;
use rand::SeedableRng;
use rand::rngs::StdRng;
use rayon::prelude::*;

use crate::eval::{evaluate, print_results, read_initial_params, write_best_params};
use crate::params::{DIMS, denormalize, normalize, sample_normal, sample_random};

// ── 线性代数 ─────────────────────────────────────────────

/// 高斯消元解线性方程组 Ax=b（原地修改 a 和 b）
fn solve_linear_system(a: &mut [Vec<f64>], b: &mut [f64]) -> Vec<f64> {
    let n = b.len();
    for col in 0..n {
        let pivot = (col..n).max_by(|&i, &j| a[i][col].abs().partial_cmp(&a[j][col].abs()).unwrap_or(std::cmp::Ordering::Equal)).unwrap_or(col);
        a.swap(col, pivot);
        b.swap(col, pivot);
        let d = a[col][col];
        if d.abs() < 1e-12 { continue; }
        for j in col..n { a[col][j] /= d; }
        b[col] /= d;
        for r in 0..n {
            if r != col && a[r][col].abs() > 1e-12 {
                let f = a[r][col];
                for j in col..n { a[r][j] -= f * a[col][j]; }
                b[r] -= f * b[col];
            }
        }
    }
    b.to_vec()
}

/// 最小二乘拟合线性模型（含截距）
fn fit_linear(X: &[Vec<f64>], y: &[f64]) -> Vec<f64> {
    let d = X.first().map(|x| x.len()).unwrap_or(0);
    let n = d + 1;
    let mut a = vec![vec![0.0; n]; n];
    let mut b = vec![0.0; n];
    for (x, &yv) in X.iter().zip(y.iter()) {
        let mut xe = x.clone();
        xe.push(1.0);
        for i in 0..n {
            for j in 0..n { a[i][j] += xe[i] * xe[j]; }
            b[i] += xe[i] * yv;
        }
    }
    for i in 0..n { a[i][i] += 1e-6; }
    solve_linear_system(&mut a, &mut b)
}

/// 线性预测（w 最后一维是截距）
fn predict_linear(w: &[f64], x: &[f64]) -> f64 {
    w.iter().zip(x.iter()).map(|(wi, xi)| wi * xi).sum::<f64>() + w.last().unwrap_or(&0.0)
}

/// 候选点到训练点的最小距离（不确定性代理）
fn uncertainty(x: &[f64], train: &[Vec<f64>]) -> f64 {
    train.iter().map(|t| {
        let d2: f64 = x.iter().zip(t.iter()).map(|(a, b)| (a - b) * (a - b)).sum();
        d2.sqrt()
    }).fold(f64::MAX, f64::min)
}

/// UCB 采集函数：预测值 + κ × 不确定性
fn ucb(w: &[f64], x: &[f64], train: &[Vec<f64>], kappa: f64) -> f64 {
    predict_linear(w, x) + kappa * uncertainty(x, train)
}

// ── 优化主循环 ───────────────────────────────────────────

pub fn bayesian_optimize(n_init: usize, n_iter: usize, games_per_eval: usize) {
    let mut samples: Vec<(Vec<f64>, f64)> = Vec::new();

    let init_start = Instant::now();
    let start_params = read_initial_params();
    let start_score = evaluate(&start_params, games_per_eval);
    samples.push((start_params, start_score));
    println!("  起点: {:.0}", start_score);

    let remaining = n_init.saturating_sub(1);
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

    let baseline_score = samples[0].1;
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
