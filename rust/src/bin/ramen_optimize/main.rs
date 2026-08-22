//! 参数优化器 — CMA-ES + 贝叶斯优化。
//!
//! 用法：
//!   cargo run --release --bin ramen_optimize -- cmaes [generations] [pop_size] [games_per_eval]
//!   cargo run --release --bin ramen_optimize -- bayes [n_init] [n_iter] [games_per_eval]
//!   默认: cmaes 10 8 50
//!
//! 迭代：如果 STRATEGY_IN 指向的 strategy_optimized.json 存在，以上次结果为起点。
//! 输出：最优参数写入 STRATEGY_OUT 指向的文件。
//!
//! 模块划分：
//! - `params`  参数空间定义与向量互转
//! - `eval`    评估函数、策略文件 IO、结果打印
//! - `cmaes`   CMA-ES 优化器
//! - `bayes`   贝叶斯优化器

mod bayes;
mod cmaes;
mod eval;
mod params;

use std::env;

use umasim::gamedata::init_global;

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
            bayes::bayesian_optimize(n_init, n_iter, games);
        }
        _ => {
            let gens = args.get(1).and_then(|s| s.parse().ok()).unwrap_or(10);
            let pop = args.get(2).and_then(|s| s.parse().ok()).unwrap_or(8);
            let games = args.get(3).and_then(|s| s.parse().ok()).unwrap_or(50);
            println!("CMA-ES: 代数={} 个体={} 局/评估={}\n", gens, pop, games);
            cmaes::cmaes_optimize(gens, pop, games);
        }
    }
}
