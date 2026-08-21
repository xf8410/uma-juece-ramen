//! 同种子、全卡型构成 A/B：本仓库旧策略 vs umaai-rs 最新 RamenPolicy。
//! 默认每种构成、每种策略 10000 局；只输出聚合指标，不保存完整轨迹。

use std::{collections::BTreeMap, env, fs, time::Instant};

use rand::{SeedableRng, rngs::StdRng};
use rayon::prelude::*;
use serde::Deserialize;
use umasim::game::{Game, InheritInfo, Trainer, ramen::RamenGame};
use umasim::gamedata::init_global;
use umasim::trainer::RamenHandwrittenTrainer;

use uma_jni::ramen_strategy::RamenStrategy;

const UMA: u32 = 102601;
const FRIEND: u32 = 303054;
const INHERIT: InheritInfo = InheritInfo {
    blue_count: [12, 0, 0, 0, 6],
    extra_count: [10, 0, 0, 20, 20, 40],
};
const TYPE_NAMES: [&str; 5] = ["速", "耐", "力", "根", "智"];

#[derive(Deserialize, Default)]
#[serde(default, rename_all = "camelCase")]
struct RawCard {
    card_id: u32,
    card_name: String,
    rarity: i32,
    card_type: usize,
    card_value: Vec<RawValue>,
}

#[derive(Deserialize, Default)]
#[serde(default, rename_all = "camelCase")]
struct RawValue {
    bonus: Vec<i32>,
    initial_bonus: Vec<i32>,
    you_qing: f64,
    gan_jing: f64,
    xun_lian: f64,
    initial_ji_ban: f64,
    de_yi_lv: f64,
    sai_hou: f64,
    wiz_vital_bonus: f64,
    fail_rate_drop: f64,
    vital_cost_drop: f64,
}

#[derive(Clone)]
struct Card {
    idrank: u32,
    name: String,
    card_type: usize,
    proxy: f64,
}

#[derive(Clone)]
struct Deck {
    name: String,
    cards: [u32; 6],
}

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
        let pt = game.uma().skill_pt;
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

    fn merge(&mut self, mut other: Aggregate) {
        self.scores.append(&mut other.scores);
        for i in 0..5 { self.status_sum[i] += other.status_sum[i]; }
        self.skill_pt_sum += other.skill_pt_sum;
        self.rmj_all += other.rmj_all;
        self.friend_all += other.friend_all;
        self.target_speed += other.target_speed;
        self.target_wisdom += other.target_wisdom;
        self.target_others += other.target_others;
        self.target_all += other.target_all;
        self.pt_8000 += other.pt_8000;
        self.pt_10000 += other.pt_10000;
        self.failed_runs += other.failed_runs;
    }

    fn summary(&mut self) -> Summary {
        self.scores.sort_unstable();
        let n = self.scores.len();
        let div = n.max(1) as f64;
        Summary {
            n,
            failed: self.failed_runs,
            score_mean: self.scores.iter().map(|&x| x as f64).sum::<f64>() / div,
            score_median: self.scores.get(n / 2).copied().unwrap_or(0),
            score_p10: self.scores.get(n.saturating_sub(1) / 10).copied().unwrap_or(0),
            status: std::array::from_fn(|i| self.status_sum[i] as f64 / div),
            skill_pt: self.skill_pt_sum as f64 / div,
            rmj_all: self.rmj_all as f64 * 100.0 / div,
            friend_all: self.friend_all as f64 * 100.0 / div,
            speed: self.target_speed as f64 * 100.0 / div,
            wisdom: self.target_wisdom as f64 * 100.0 / div,
            others: self.target_others as f64 * 100.0 / div,
            all: self.target_all as f64 * 100.0 / div,
            pt8000: self.pt_8000 as f64 * 100.0 / div,
            pt10000: self.pt_10000 as f64 * 100.0 / div,
        }
    }
}

struct Summary {
    n: usize,
    failed: usize,
    score_mean: f64,
    score_median: i32,
    score_p10: i32,
    status: [f64; 5],
    skill_pt: f64,
    rmj_all: f64,
    friend_all: f64,
    speed: f64,
    wisdom: f64,
    others: f64,
    all: f64,
    pt8000: f64,
    pt10000: f64,
}

fn sum(v: &[i32]) -> f64 { v.iter().map(|&x| x as f64).sum() }

fn load_cards(path: &str) -> anyhow::Result<Vec<Card>> {
    let raw: BTreeMap<String, RawCard> = serde_json::from_str(&fs::read_to_string(path)?)?;
    Ok(raw.into_values().filter_map(|c| {
        if c.rarity != 3 || c.card_type >= 5 || c.card_value.len() < 5 { return None; }
        let v = &c.card_value[4];
        let proxy = v.xun_lian * 3.0 + v.you_qing * 2.0 + v.de_yi_lv * 0.35
            + v.gan_jing * 0.25 + v.initial_ji_ban * 0.45 + v.sai_hou * 1.5
            + v.wiz_vital_bonus * 8.0 + v.fail_rate_drop + v.vital_cost_drop
            + sum(&v.bonus) * 16.0 + sum(&v.initial_bonus) * 0.4;
        Some(Card { idrank: c.card_id * 10 + 4, name: c.card_name, card_type: c.card_type, proxy })
    }).collect())
}

fn build_deck(cards: &[Card], counts: [usize; 5]) -> anyhow::Result<Deck> {
    let mut ids = Vec::with_capacity(6);
    for (t, &count) in counts.iter().enumerate() {
        let mut pool: Vec<&Card> = cards.iter().filter(|c| c.card_type == t).collect();
        pool.sort_by(|a, b| b.proxy.total_cmp(&a.proxy));
        if pool.len() < count { anyhow::bail!("类型 {} 的候选卡不足 {} 张", TYPE_NAMES[t], count); }
        ids.extend(pool.into_iter().take(count).map(|c| c.idrank));
    }
    ids.push(FRIEND);
    let cards: [u32; 6] = ids.try_into().map_err(|_| anyhow::anyhow!("卡组必须为6张"))?;
    let name = counts.iter().enumerate().filter(|(_, n)| **n > 0)
        .map(|(i, n)| format!("{}{}", n, TYPE_NAMES[i])).collect::<Vec<_>>().join("");
    Ok(Deck { name: format!("{name}+1友"), cards })
}

fn all_composition_decks(cards: &[Card]) -> anyhow::Result<Vec<Deck>> {
    let mut decks = Vec::new();
    // 与之前测卡一致：五种普通卡数量各为0..3，合计固定5张，再加固定友人。
    for speed in 0..=3 {
        for stamina in 0..=3 {
            for power in 0..=3 {
                for guts in 0..=3 {
                    for wisdom in 0..=3 {
                        let counts = [speed, stamina, power, guts, wisdom];
                        if counts.iter().sum::<usize>() == 5 {
                            decks.push(build_deck(cards, counts)?);
                        }
                    }
                }
            }
        }
    }
    // 数学上应为101种，防止以后无意缩减覆盖范围。
    anyhow::ensure!(decks.len() == 101, "全构成数量应为101，实际为{}", decks.len());
    Ok(decks)
}

fn deck_detail(deck: &Deck, cards: &[Card]) -> String {
    deck.cards.iter().map(|id| {
        if *id == FRIEND { return format!("{id} 固定友人"); }
        let name = cards.iter().find(|c| c.idrank == *id).map(|c| c.name.as_str()).unwrap_or("?");
        format!("{id} {name}")
    }).collect::<Vec<_>>().join(" / ")
}

fn run<T: Trainer<RamenGame> + Sync>(trainer: &T, deck: &Deck, runs: usize, seed: u64) -> Aggregate {
    (0..runs).into_par_iter().map(|i| {
        let mut out = Aggregate::default();
        let s = seed + i as u64;
        let mut decision_rng = StdRng::seed_from_u64(s);
        let rule_rng = StdRng::seed_from_u64(s ^ 0x9E37_79B9_7F4A_7C15);
        match RamenGame::newgame(UMA, &deck.cards, INHERIT) {
            Ok(mut game) => {
                game.set_internal_rng(rule_rng);
                if game.run_full_game(trainer, &mut decision_rng).is_ok() { out.add(&game); }
                else { out.failed_runs = 1; }
            }
            Err(_) => out.failed_runs = 1,
        }
        out
    }).reduce(Aggregate::default, |mut a, b| { a.merge(b); a })
}

fn print_row(deck: &str, strategy: &str, s: &Summary) {
    println!(
        "|{deck}|{strategy}|{}/{}|{:.0}|{}|{}|{:.0}/{:.0}/{:.0}/{:.0}/{:.0}|{:.0}|{:.1}%|{:.1}%|{:.1}%|{:.1}%|{:.1}%|",
        s.n, s.failed, s.score_mean, s.score_median, s.score_p10,
        s.status[0], s.status[1], s.status[2], s.status[3], s.status[4], s.skill_pt,
        s.pt8000, s.all, s.rmj_all, s.friend_all, s.speed.min(s.wisdom).min(s.others),
    );
}

fn main() -> anyhow::Result<()> {
    let args: Vec<String> = env::args().collect();
    let runs = args.get(1).and_then(|x| x.parse().ok()).unwrap_or(10000);
    let seed = args.get(2).and_then(|x| x.parse().ok()).unwrap_or(42);
    init_global()?;
    let cards = load_cards(&env::var("CARD_DB").unwrap_or_else(|_| "gamedata/cardDB.json".into()))?;
    let decks = all_composition_decks(&cards)?;

    println!("# 拉面杯全101种卡型构成手写策略同种子 A/B\n");
    println!("- 每种构成、每种策略：{runs}局；构成数：{}；总计划局数：{}", decks.len(), decks.len() * runs * 2);
    println!("- 普通卡速/耐/力/根/智各0..3张且合计5张，再加固定友人");
    println!("- A：本仓库原手写策略；B：umaai-rs 最新 RamenPolicy");
    println!("- 两策略在每种构成使用同一批逐局种子；训练技能PT严格读取 `uma.skill_pt`。\n");
    println!("|卡组|策略|完成/异常|均分|中位|P10|五维均值 速/耐/力/根/智|技能PT均值|PT≥8000|全部属性KPI|RMJ全通|友人走完|属性分项最低达成率|");
    println!("|---|---|---:|---:|---:|---:|---|---:|---:|---:|---:|---:|---:|");

    let old = RamenStrategy::default();
    let new = RamenHandwrittenTrainer::new();
    let started = Instant::now();
    for (i, deck) in decks.iter().enumerate() {
        eprintln!("[{}/{}] {} A/B", i + 1, decks.len(), deck.name);
        let mut a = run(&old, deck, runs, seed);
        let mut b = run(&new, deck, runs, seed);
        print_row(&deck.name, "A 原策略", &a.summary());
        print_row(&deck.name, "B 上游新策略", &b.summary());
    }
    println!("\n总耗时：{:.2}s\n", started.elapsed().as_secs_f64());
    println!("## 卡组明细\n");
    for deck in &decks { println!("- **{}**：{}", deck.name, deck_detail(deck, &cards)); }
    println!("\n> 代表卡按 cardDB 满破面板代理值选取，仅用于为每种卡型构成选卡；最终比较依据是完整育成结果。固有未实现的卡仍需单独审计。\n");
    Ok(())
}
