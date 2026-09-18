//! 基因组 A/B/C 批量模拟 — 同卷面同种子配对，比较三种策略配置的整局评分。
//!
//! A = preset ：当前正式推荐策略（9 旋钮 GA 定稿档，`RecommendedRamenTrainer::new()`）
//! B = genome ：best_genome.toml 纯覆盖层（None=保留当前 preset）
//! C = freeze ：GA 冻结配置（best_genome ∪ GA 当时 preset 基线，GA 评估时的完整参数面，
//!              与浮窗 fallback 启用的通道一致）
//!
//! 卷面与 ramen_batch 完全一致（TEST_UMA/TEST_DECK/TEST_INHERIT），逐局同种子
//! ⇒ 三臂配对（CRN 思想），配对差值即配置净效应，消除单局运气方差。
//!
//! 用法（同 ramen_batch，需要 gamedata）：
//!   cargo run --release --manifest-path rust/Cargo.toml --bin ramen_batch_genome -- 100

use std::env;
use std::time::Instant;

use rand::SeedableRng;
use rand::rngs::StdRng;
use rayon::prelude::*;

use uma_jni::genome;
use umasim::game::{Game, InheritInfo, ramen::RamenGame};
use umasim::gamedata::init_global;
use umasim::trainer::RecommendedRamenTrainer;

const TEST_UMA: u32 = 102601;
const TEST_DECK: [u32; 6] = [302424, 302894, 303044, 302924, 303024, 303054];
const TEST_INHERIT: InheritInfo = InheritInfo {
    blue_count: [15, 3, 0, 0, 0],
    extra_count: [0, 30, 0, 0, 30, 30],
};

/// 局 i 的配对种子（三臂同序同种子）。
fn seed_of(i: usize) -> u64 {
    0x5EED_0000 + i as u64
}

fn run_one(trainer: &RecommendedRamenTrainer, seed: u64) -> Option<(i32, i32)> {
    let mut game = RamenGame::newgame(TEST_UMA, &TEST_DECK, TEST_INHERIT).ok()?;
    let mut rng = StdRng::seed_from_u64(seed);
    game.run_full_game(trainer, &mut rng).ok()?;
    Some((game.uma().calc_score(), game.uma().total_pt()))
}

fn summarize(name: &str, scores: &[i32], pts: &[i32]) {
    let mut s: Vec<i32> = scores.to_vec();
    s.sort_unstable();
    let ok = s.len();
    if ok == 0 {
        println!("{name}: 无样本");
        return;
    }
    let mean = s.iter().map(|&x| x as i64).sum::<i64>() as f64 / ok as f64;
    let median = s[ok / 2];
    let (min, max) = (s[0], s[ok - 1]);
    let p90 = s[(ok * 9 / 10).min(ok - 1)];
    let pt_mean = pts.iter().map(|&x| x as i64).sum::<i64>() as f64 / ok as f64;
    println!("{name}: 均分={mean:.0} 中位={median} min={min} max={max} P90={p90} 均PT={pt_mean:.0}");
}

fn main() {
    let args: Vec<String> = env::args().collect();
    let n: usize = args.get(1).and_then(|s| s.parse().ok()).unwrap_or(100);

    println!("初始化 gamedata...");
    if let Err(e) = init_global() {
        eprintln!("init_global 失败: {e}");
        eprintln!("请从 umaai-rs 根目录运行，或设置 UMAI_DATA_DIR=/path/to/gamedata");
        std::process::exit(1);
    }

    // 三臂构造（解析失败的臂跳过，不阻塞其余对照）
    let mut arms: Vec<(&str, RecommendedRamenTrainer)> =
        vec![("A_preset", RecommendedRamenTrainer::new())];
    match genome::parse(genome::BEST_GENOME_TOML) {
        Ok(ov) => arms.push(("B_genome", RecommendedRamenTrainer::with_overrides(&ov))),
        Err(e) => eprintln!("B_genome 覆盖层解析失败，跳过该臂: {e}"),
    }
    match genome::parse(genome::GA_FREEZE_TOML) {
        Ok(ov) => arms.push(("C_freeze", RecommendedRamenTrainer::with_overrides(&ov))),
        Err(e) => eprintln!("C_freeze 冻结配置解析失败，跳过该臂: {e}"),
    }

    println!("卷面: uma={TEST_UMA} deck={TEST_DECK:?} N={n} 局（同种子三臂配对）\n");

    let start = Instant::now();
    let mut all: Vec<(&str, Vec<(i32, i32)>)> = Vec::new();
    for (name, trainer) in &arms {
        let results: Vec<(i32, i32)> = (0..n)
            .into_par_iter()
            .filter_map(|i| run_one(trainer, seed_of(i)))
            .collect();
        println!("臂 {name}: 成功 {}/{} 局", results.len(), n);
        all.push((*name, results));
    }

    println!("\n════════════════════════════════════════");
    let mut base: Option<Vec<i32>> = None;
    for (name, results) in &all {
        let (scores, pts): (Vec<i32>, Vec<i32>) = results.iter().copied().unzip();
        summarize(name, &scores, &pts);
        if *name == "A_preset" {
            base = Some(scores);
        }
    }

    // 配对差值（与 A 臂逐局对齐）
    if let Some(base) = &base {
        println!();
        for (name, results) in &all {
            if *name == "A_preset" {
                continue;
            }
            let deltas: Vec<i64> = results
                .iter()
                .zip(base.iter())
                .map(|((b, _), a)| (*b - *a) as i64)
                .collect();
            let cnt = deltas.len();
            if cnt == 0 {
                continue;
            }
            let mean = deltas.iter().sum::<i64>() as f64 / cnt as f64;
            let wins = deltas.iter().filter(|&&d| d > 0).count();
            let losses = deltas.iter().filter(|&&d| d < 0).count();
            println!(
                "配对 {name} − A_preset: 均差={mean:+.0}（{cnt} 对，胜 {wins} / 平 {} / 负 {losses}）",
                cnt - wins - losses
            );
        }
    }
    println!("\n耗时: {:.2}s", start.elapsed().as_secs_f64());
    println!("════════════════════════════════════════");
}
