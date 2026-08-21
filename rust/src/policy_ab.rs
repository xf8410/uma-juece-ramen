//! 同种子 A/B：本仓库旧策略 vs umaai-rs 最新 RamenPolicy。
//! 默认 100 局；只输出聚合指标，不保存完整轨迹。

use std::{env, time::Instant};

use rand::{SeedableRng, rngs::StdRng};
use umasim::game::{Game, InheritInfo, Trainer, ramen::RamenGame};
use umasim::gamedata::init_global;
use umasim::trainer::RamenHandwrittenTrainer;

use uma_jni::ramen_strategy::RamenStrategy;

const UMA: u32 = 102601;
const DECK: [u32; 6] = [302424, 302894, 303044, 302924, 303024, 303054];
const INHERIT: InheritInfo = InheritInfo {
    blue_count: [12, 0, 0, 0, 6],
    extra_count: [10, 0, 0, 20, 20, 40],
};

#[derive(Default)]
struct Aggregate {
    scores: Vec<i32>,
    status_sum: [i64; 5],
    skill_pt_sum: i64,
    rmj_all: usize,
    friend_all: usize,
    target_speed: usize,
    target_wisdom: usize,
    target_others: usize,
    target_all: usize,
    pt_8000: usize,
    pt_10000: usize,
    failed_runs: usize,
}

impl Aggregate {
    fn add(&mut self, game: &RamenGame) {
        self.scores.push(game.uma().calc_score());
        for i in 0..5 {
            self.status_sum[i] += game.uma().five_status[i] as i64;
        }
        let pt = game.uma().total_pt();
        self.skill_pt_sum += pt as i64;
        self.rmj_all += usize::from(game.ramen.rmj_results.iter().take(3).all(|&x| x));
        self.friend_all += usize::from(game.friend.out_used.iter().all(|&x| x));
        self.target_speed += usize::from(game.uma().five_status[0] >= 2200);
        self.target_wisdom += usize::from(game.uma().five_status[4] >= 1800);
        self.target_others += usize::from((1..=3).all(|i| game.uma().five_status[i] >= 1400));
        self.target_all += usize::from(
            game.uma().five_status[0] >= 2200
                && game.uma().five_status[4] >= 1800
                && (1..=3).all(|i| game.uma().five_status[i] >= 1400),
        );
        self.pt_8000 += usize::from(pt >= 8000);
        self.pt_10000 += usize::from(pt >= 10000);
    }

    fn print(&mut self, name: &str, requested: usize, elapsed: f64) {
        self.scores.sort_unstable();
        let n = self.scores.len();
        let mean = if n == 0 { 0.0 } else { self.scores.iter().map(|&x| x as f64).sum::<f64>() / n as f64 };
        let median = self.scores.get(n / 2).copied().unwrap_or(0);
        let p10 = self.scores.get(n.saturating_sub(1) / 10).copied().unwrap_or(0);
        let pct = |x: usize| if n == 0 { 0.0 } else { x as f64 * 100.0 / n as f64 };
        let status: Vec<f64> = self.status_sum.iter().map(|&x| if n == 0 { 0.0 } else { x as f64 / n as f64 }).collect();
        println!("## {name}");
        println!("- 完成/异常：{n}/{}，运行耗时：{elapsed:.2}s", requested, self.failed_runs);
        println!("- 评分：均值 {mean:.0}，中位 {median}，P10 {p10}");
        println!("- 五维均值：速 {:.0} / 耐 {:.0} / 力 {:.0} / 根 {:.0} / 智 {:.0}", status[0], status[1], status[2], status[3], status[4]);
        println!("- 训练技能PT均值：{:.0}；≥8000：{:.1}%；≥10000：{:.1}%", if n == 0 { 0.0 } else { self.skill_pt_sum as f64 / n as f64 }, pct(self.pt_8000), pct(self.pt_10000));
        println!("- 速≥2200：{:.1}%；智≥1800：{:.1}%；耐力根均≥1400：{:.1}%；全部KPI：{:.1}%", pct(self.target_speed), pct(self.target_wisdom), pct(self.target_others), pct(self.target_all));
        println!("- 三年RMJ全通：{:.1}%；友人五次外出完成：{:.1}%\n", pct(self.rmj_all), pct(self.friend_all));
    }
}

fn run<T: Trainer<RamenGame>>(trainer: &T, runs: usize, seed: u64) -> Aggregate {
    let mut out = Aggregate::default();
    let started = Instant::now();
    for i in 0..runs {
        let s = seed + i as u64;
        let mut decision_rng = StdRng::seed_from_u64(s);
        let rule_rng = StdRng::seed_from_u64(s ^ 0x9E37_79B9_7F4A_7C15);
        match RamenGame::newgame(UMA, &DECK, INHERIT) {
            Ok(mut game) => {
                game.set_internal_rng(rule_rng);
                if game.run_full_game(trainer, &mut decision_rng).is_ok() {
                    out.add(&game);
                } else {
                    out.failed_runs += 1;
                }
            }
            Err(_) => out.failed_runs += 1,
        }
    }
    eprintln!("{} 局完成，{:.2}s", out.scores.len(), started.elapsed().as_secs_f64());
    out
}

fn main() -> anyhow::Result<()> {
    let args: Vec<String> = env::args().collect();
    let runs = args.get(1).and_then(|x| x.parse().ok()).unwrap_or(100);
    let seed = args.get(2).and_then(|x| x.parse().ok()).unwrap_or(42);
    init_global()?;

    println!("# 拉面杯手写策略同种子 A/B\n\n- 局数：每种策略 {runs}\n- 基础种子：{seed}\n- 使用同一马娘、卡组、因子与逐局种子\n- A：本仓库原手写策略；B：umaai-rs 最新 RamenPolicy\n");

    let start_a = Instant::now();
    let mut a = run(&RamenStrategy::default(), runs, seed);
    let elapsed_a = start_a.elapsed().as_secs_f64();
    let start_b = Instant::now();
    let mut b = run(&RamenHandwrittenTrainer::new(), runs, seed);
    let elapsed_b = start_b.elapsed().as_secs_f64();
    a.print("A：原手写策略", runs, elapsed_a);
    b.print("B：上游最新手写策略", runs, elapsed_b);
    Ok(())
}
