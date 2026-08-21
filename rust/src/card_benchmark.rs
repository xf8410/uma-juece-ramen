//! GitHub 云端拉面杯卡组基准。
//! 仿 UmaAi TestCardsSingle：固定马娘、因子、策略和友人卡，只改变卡组构成，
//! 每套卡跑相同局数并按均分排序。候选卡从当前 cardDB 的满破 SSR 中按基础训练参数预筛。

use std::{collections::{BTreeMap, HashSet}, env, fs, time::Instant};

use rand::SeedableRng;
use rayon::prelude::*;
use serde::Deserialize;
use uma_jni::ramen_strategy::RamenStrategy;
use umasim::{game::{Game, InheritInfo, ramen::RamenGame}, gamedata::init_global};

const TEST_UMA: u32 = 102601;
const FRIEND: u32 = 303054;
const BASELINE: [u32; 6] = [302424, 302894, 303044, 302924, 303024, FRIEND];
const INHERIT: InheritInfo = InheritInfo {
    blue_count: [12, 0, 0, 0, 6],
    extra_count: [10, 0, 0, 20, 20, 40],
};

#[derive(Clone, Debug)]
struct Card { id: u32, name: String, card_type: usize, proxy: f64 }

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
    bonus: Vec<i32>, initial_bonus: Vec<i32>, hint_bonus: Vec<i32>,
    you_qing: f64, gan_jing: f64, xun_lian: f64, initial_ji_ban: f64,
    hint_prob_increase: f64, de_yi_lv: f64, sai_hou: f64, wiz_vital_bonus: f64,
    fail_rate_drop: f64, vital_cost_drop: f64,
}

#[derive(Clone)]
struct Deck { name: String, cards: [u32; 6] }

struct ResultRow {
    name: String, cards: [u32; 6], ok: usize, failed: usize,
    mean: f64, median: i32, p90: i32, max: i32, pt_mean: f64,
    rmj_all: f64, elapsed: f64,
}

fn sum(v: &[i32]) -> f64 { v.iter().map(|&x| x as f64).sum() }

fn load_cards(path: &str) -> anyhow::Result<Vec<Card>> {
    let text = fs::read_to_string(path)?;
    let raw: BTreeMap<String, RawCard> = serde_json::from_str(&text)?;
    let mut cards = Vec::new();
    for c in raw.into_values() {
        if c.rarity != 3 || c.card_type >= 5 || c.card_value.len() < 5 { continue; }
        let v = &c.card_value[4];
        // 只做候选预筛，不当作最终卡强度；最终排名完全来自完整对局均分。
        let proxy = v.xun_lian * 3.0 + v.you_qing * 2.0 + v.de_yi_lv * 0.35
            + v.gan_jing * 0.25 + v.initial_ji_ban * 0.45 + v.sai_hou * 1.5
            + v.wiz_vital_bonus * 8.0 + v.fail_rate_drop + v.vital_cost_drop
            + sum(&v.bonus) * 16.0 + sum(&v.initial_bonus) * 0.4
            + sum(&v.hint_bonus) * 0.15 + v.hint_prob_increase * 0.1;
        cards.push(Card {
            id: c.card_id * 10 + 4,
            name: c.card_name,
            card_type: c.card_type,
            proxy,
        });
    }
    Ok(cards)
}

fn top_by_type(cards: &[Card], card_type: usize, n: usize) -> Vec<Card> {
    let mut v: Vec<Card> = cards.iter().filter(|c| c.card_type == card_type).cloned().collect();
    v.sort_by(|a, b| b.proxy.total_cmp(&a.proxy));
    v.truncate(n);
    v
}

fn choose(pool: &[Card], count: usize, used: &mut HashSet<u32>) -> Vec<u32> {
    pool.iter().filter_map(|c| {
        if used.insert(c.id) { Some(c.id) } else { None }
    }).take(count).collect()
}

fn archetype(name: &str, counts: [usize; 5], pools: &[Vec<Card>; 5]) -> Option<Deck> {
    let mut used = HashSet::new();
    let mut ids = Vec::new();
    for t in 0..5 { ids.extend(choose(&pools[t], counts[t], &mut used)); }
    if ids.len() != 5 { return None; }
    ids.push(FRIEND);
    Some(Deck { name: name.to_string(), cards: ids.try_into().ok()? })
}

fn build_decks(cards: &[Card]) -> Vec<Deck> {
    let pools: [Vec<Card>; 5] = std::array::from_fn(|t| top_by_type(cards, t, 8));
    let mut decks = vec![Deck { name: "上游推荐基准(3速1耐1智)".into(), cards: BASELINE }];
    let specs = [
        ("自动Top 3速1耐1智", [3,1,0,0,1]),
        ("自动Top 2速1耐1力1智", [2,1,1,0,1]),
        ("自动Top 2速2耐1智", [2,2,0,0,1]),
        ("自动Top 2速1耐2智", [2,1,0,0,2]),
        ("自动Top 2速1力1根1智", [2,0,1,1,1]),
        ("自动Top 五维各1", [1,1,1,1,1]),
    ];
    for (name, counts) in specs {
        if let Some(d) = archetype(name, counts, &pools) { decks.push(d); }
    }
    // 参考 UmaAi 的单卡替换法：在上游基准中逐槽换成该类型预筛 Top 卡。
    for slot in 0..5 {
        let base = cards.iter().find(|c| c.id == BASELINE[slot]);
        let Some(base) = base else { continue; };
        for candidate in pools[base.card_type].iter().take(5) {
            if BASELINE.contains(&candidate.id) { continue; }
            let mut deck = BASELINE;
            deck[slot] = candidate.id;
            decks.push(Deck {
                name: format!("单换槽{} {}", slot + 1, candidate.name), cards: deck,
            });
        }
    }
    let mut seen = HashSet::new();
    decks.retain(|d| seen.insert(d.cards));
    decks
}

fn simulate(deck: &Deck, n: usize, strategy: &RamenStrategy) -> ResultRow {
    let start = Instant::now();
    let results: Vec<(i32, i32, bool)> = (0..n).into_par_iter().filter_map(|_| {
        let mut game = RamenGame::newgame(TEST_UMA, &deck.cards, INHERIT).ok()?;
        let mut rng = rand::rngs::StdRng::from_os_rng();
        game.run_full_game(strategy, &mut rng).ok()?;
        Some((game.uma().calc_score(), game.uma().total_pt(),
              game.ramen.rmj_results.iter().take(3).all(|&x| x)))
    }).collect();
    let ok = results.len();
    let mut scores: Vec<i32> = results.iter().map(|x| x.0).collect();
    scores.sort_unstable();
    let mean = if ok == 0 { 0.0 } else { scores.iter().map(|&x| x as f64).sum::<f64>() / ok as f64 };
    let pt_mean = if ok == 0 { 0.0 } else { results.iter().map(|x| x.1 as f64).sum::<f64>() / ok as f64 };
    let rmj_all = if ok == 0 { 0.0 } else { results.iter().filter(|x| x.2).count() as f64 * 100.0 / ok as f64 };
    ResultRow {
        name: deck.name.clone(), cards: deck.cards, ok, failed: n - ok, mean,
        median: scores.get(ok / 2).copied().unwrap_or(0),
        p90: scores.get((ok * 9 / 10).min(ok.saturating_sub(1))).copied().unwrap_or(0),
        max: scores.last().copied().unwrap_or(0), pt_mean, rmj_all,
        elapsed: start.elapsed().as_secs_f64(),
    }
}

fn main() -> anyhow::Result<()> {
    let args: Vec<String> = env::args().collect();
    let n = args.get(1).and_then(|x| x.parse().ok()).unwrap_or(300usize);
    let card_db = env::var("CARD_DB").unwrap_or_else(|_| "gamedata/cardDB.json".into());
    init_global()?;
    let cards = load_cards(&card_db)?;
    let names: BTreeMap<u32, String> = cards.iter().map(|c| (c.id, c.name.clone())).collect();
    let decks = build_decks(&cards);
    let strategy = env::var("RAMEN_STRATEGY")
        .ok().and_then(|s| serde_json::from_str(&s).ok()).unwrap_or_default();

    println!("# 拉面杯主流卡组云端模拟\n");
    println!("- 方法：固定马娘 {}、因子、手写策略和满破友人 {}，每套独立模拟 {} 局", TEST_UMA, FRIEND, n);
    println!("- 对照：上游推荐卡组；另测常见编成，以及仿 UmaAi TestCardsSingle 的同类型单卡替换");
    println!("- 候选：当前 cardDB 满破 SSR 按基础训练参数预筛；最终按完整对局均分排名\n");
    println!("共 {} 套卡组，开始模拟...", decks.len());

    let mut rows: Vec<ResultRow> = decks.iter().map(|d| {
        eprintln!("模拟 {} {:?}", d.name, d.cards);
        simulate(d, n, &strategy)
    }).collect();
    rows.sort_by(|a, b| b.mean.total_cmp(&a.mean));

    println!("\n|排名|卡组|均分|中位|P90|最高|均PT|RMJ全通|成功/失败|耗时|\n|---:|---|---:|---:|---:|---:|---:|---:|---:|---:|");
    for (i, r) in rows.iter().enumerate() {
        println!("|{}|{}|{:.0}|{}|{}|{}|{:.0}|{:.1}%|{}/{}|{:.1}s|",
                 i+1, r.name, r.mean, r.median, r.p90, r.max, r.pt_mean,
                 r.rmj_all, r.ok, r.failed, r.elapsed);
    }
    println!("\n## 卡组明细\n");
    for (i, r) in rows.iter().enumerate() {
        let detail = r.cards.iter().map(|id| format!("{} {}", id,
            names.get(id).cloned().unwrap_or_else(|| if *id == FRIEND { "[友]拉面杯友人".into() } else { "?".into() })))
            .collect::<Vec<_>>().join(" / ");
        println!("{}. **{}**：{}", i+1, r.name, detail);
    }
    Ok(())
}
