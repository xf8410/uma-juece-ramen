//! 拉面杯近期 SSR 卡组基准：构成粗筛 + 卡级搜索 + 终验证。
//!
//! 三阶段：
//! 1. **构成粗筛** — 系统枚举五种类型构成（各 0..=3 张、合计 5 张），
//!    每种构成先用 proxy 头部卡跑粗样本，筛出头部构成；
//! 2. **卡级坐标搜索** — 对头部构成逐槽换卡实测：同一类型候选池里
//!    每张卡轮流换进对应槽位，实际模拟评分，分高者留任。
//!    proxy 只是启发式排序，最终以实测均分为准；
//! 3. **终验证** — 搜索赢家 + 构成冠军 + 上游基准，大样本重跑定名次。
//!
//! 每套严格 6 张：5 张普通支援卡 + 固定友人 303054。
//!
//! 用法：
//!   cargo run --release --bin ramen_card_benchmark -- [局数] [top_k] [搜索局数] [终验局数]
//!   默认: 300 3 150 1000（总模拟约 8~10 万局）
//!
//! 环境变量：
//!   MIN_CARD_ID     卡池下界（默认 30215 = 爱如往昔）
//!   CARD_DB         cardDB.json 路径（默认 gamedata/cardDB.json）
//!   RAMEN_STRATEGY  策略 JSON（内联字符串，缺省用默认策略）

use std::collections::{BTreeMap, HashSet};
use std::env;
use std::fs;
use std::time::Instant;

use rand::SeedableRng;
use rayon::prelude::*;
use serde::Deserialize;

use uma_jni::ramen_strategy::RamenStrategy;
use uma_jni::testbed::{FRIEND, TEST_UMA};
use umasim::game::{Game, InheritInfo, ramen::RamenGame};
use umasim::gamedata::init_global;

/// 卡池下界默认值：爱如往昔（cardDB 中 30215 = [速]爱如往昔 SSR）
const DEFAULT_MIN_CARD_ID: u32 = 30215;

/// 每种类型进入枚举/搜索的卡池大小（按 proxy 排序取头部）
const POOL_PER_TYPE: usize = 12;

/// 构成粗筛默认每套局数
const DEFAULT_RUNS: usize = 300;

/// 卡级搜索：取头部构成数、每次试换局数、最大轮数、留任分差
const SEARCH_TOP_K: usize = 3;
const SEARCH_GAMES: usize = 150;
const SEARCH_PASSES: usize = 2;
const SEARCH_IMPROVE_MARGIN: f64 = 20.0;

/// 终验证默认局数
const DEFAULT_FINAL_GAMES: usize = 1000;

const TYPE_LABELS: [&str; 5] = ["速", "耐", "力", "根", "智"];

/// 上游推荐基准卡组：3速1耐1智 + 友
const BASELINE: [u32; 6] = [302424, 302894, 303044, 302924, 303024, FRIEND];
/// BASELINE 各槽类型（速/智/耐/速/速/友）
const BASELINE_TYPES: [usize; 6] = [0, 4, 1, 0, 0, 5];

/// 基准用继承因子（与 testbed 标准继承不同，专用于本基准）
const INHERIT: InheritInfo = InheritInfo {
    blue_count: [12, 0, 0, 0, 6],
    extra_count: [10, 0, 0, 20, 20, 40],
};

// ── 数据结构 ─────────────────────────────────────────────

#[derive(Clone, Debug)]
struct Card {
    id: u32,
    name: String,
    card_type: usize,
    proxy: f64,
}

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
    hint_bonus: Vec<i32>,
    you_qing: f64,
    gan_jing: f64,
    xun_lian: f64,
    initial_ji_ban: f64,
    hint_prob_increase: f64,
    de_yi_lv: f64,
    sai_hou: f64,
    wiz_vital_bonus: f64,
    fail_rate_drop: f64,
    vital_cost_drop: f64,
}

/// 一套待测卡组（含每个槽位的类型，第 6 槽固定友人）
#[derive(Clone)]
struct Deck {
    name: String,
    cards: [u32; 6],
    types: [usize; 6],
}

/// 一套卡组的模拟统计
struct ResultRow {
    name: String,
    cards: [u32; 6],
    ok: usize,
    failed: usize,
    mean: f64,
    median: i32,
    p90: i32,
    max: i32,
    pt_mean: f64,
    rmj_all: f64,
}

// ── 卡池加载 ─────────────────────────────────────────────

fn sum(v: &[i32]) -> f64 {
    v.iter().map(|&x| x as f64).sum()
}

/// 加权卡强度：满破(card_value[4])各项加权和，仅用于排序候选池，
/// 不作为最终强度依据（最终以模拟实测为准）。
fn proxy_of(v: &RawValue) -> f64 {
    v.xun_lian * 3.0
        + v.you_qing * 2.0
        + v.de_yi_lv * 0.35
        + v.gan_jing * 0.25
        + v.initial_ji_ban * 0.45
        + v.sai_hou * 1.5
        + v.wiz_vital_bonus * 8.0
        + v.fail_rate_drop
        + v.vital_cost_drop
        + sum(&v.bonus) * 16.0
        + sum(&v.initial_bonus) * 0.4
        + sum(&v.hint_bonus) * 0.15
        + v.hint_prob_increase * 0.1
}

/// 读取 cardDB.json，只保留满破 SSR（rarity==3、card_type<5、card_id>=min_id），
/// 运行时 ID = card_id*10+4。
fn load_cards(path: &str, min_id: u32) -> anyhow::Result<Vec<Card>> {
    let raw: BTreeMap<String, RawCard> = serde_json::from_str(&fs::read_to_string(path)?)?;
    Ok(raw
        .into_values()
        .filter_map(|c| {
            if c.rarity != 3 || c.card_type >= 5 || c.card_value.len() < 5 || c.card_id < min_id {
                return None;
            }
            let v = &c.card_value[4];
            Some(Card {
                id: c.card_id * 10 + 4,
                name: c.card_name,
                card_type: c.card_type,
                proxy: proxy_of(v),
            })
        })
        .collect())
}

/// 每类型 proxy 前 n 名的候选池（已按 proxy 降序）
fn build_pools(cards: &[Card]) -> [Vec<Card>; 5] {
    std::array::from_fn(|t| {
        let mut v: Vec<Card> = cards.iter().filter(|c| c.card_type == t).cloned().collect();
        v.sort_by(|a, b| b.proxy.total_cmp(&a.proxy));
        v.truncate(POOL_PER_TYPE);
        v
    })
}

// ── 阶段1：构成枚举 ──────────────────────────────────────

/// 构成名如 "构成 3速1耐1智+1友"
fn composition_name(c: &[usize; 5]) -> String {
    let mut p = vec![];
    for i in 0..5 {
        if c[i] > 0 {
            p.push(format!("{}{}", c[i], TYPE_LABELS[i]));
        }
    }
    format!("构成 {}+1友", p.join(""))
}

/// 从各类型候选池头部取 counts[t] 张组成卡组；池不足则放弃该构成
fn archetype(counts: &[usize; 5], pools: &[Vec<Card>; 5]) -> Option<Deck> {
    if counts.iter().sum::<usize>() != 5 {
        return None;
    }
    let mut ids = Vec::with_capacity(6);
    let mut types = Vec::with_capacity(6);
    for t in 0..5 {
        if pools[t].len() < counts[t] {
            return None;
        }
        for c in pools[t].iter().take(counts[t]) {
            ids.push(c.id);
            types.push(t);
        }
    }
    ids.push(FRIEND);
    types.push(5);
    Some(Deck {
        name: composition_name(counts),
        cards: ids.try_into().ok()?,
        types: types.try_into().ok()?,
    })
}

/// 全部待测卡组：上游基准 + 枚举构成（去重校验）
fn build_decks(pools: &[Vec<Card>; 5]) -> Vec<Deck> {
    let mut decks = vec![Deck {
        name: "上游推荐基准：3速1耐1智+1友".into(),
        cards: BASELINE,
        types: BASELINE_TYPES,
    }];

    // 五重循环枚举构成：各类型 0..=3 张、合计 5 张
    for speed in 0..=3 {
        for stamina in 0..=3 {
            for power in 0..=3 {
                for guts in 0..=3 {
                    for wiz in 0..=3 {
                        let counts = [speed, stamina, power, guts, wiz];
                        if counts.iter().sum::<usize>() == 5 {
                            if let Some(d) = archetype(&counts, pools) {
                                decks.push(d);
                            }
                        }
                    }
                }
            }
        }
    }

    // 校验第 6 张必须是友人、前 5 张不含友人、卡组不重复
    let mut seen = HashSet::new();
    decks.retain(|d| {
        d.cards[5] == FRIEND
            && d.cards[..5].iter().all(|id| *id != FRIEND)
            && seen.insert(d.cards)
    });
    decks
}

// ── 模拟 ────────────────────────────────────────────────

/// 跑 n 局，返回统计。每局独立熵源；RMJ 取前 3 场全部通过为全通。
fn simulate(deck: &Deck, n: usize, strategy: &RamenStrategy) -> ResultRow {
    assert_eq!(deck.cards[5], FRIEND);

    let results: Vec<(i32, i32, bool)> = (0..n)
        .into_par_iter()
        .filter_map(|_| {
            let mut g = RamenGame::newgame(TEST_UMA, &deck.cards, INHERIT).ok()?;
            let mut rng = rand::rngs::StdRng::from_os_rng();
            g.run_full_game(strategy, &mut rng).ok()?;
            Some((
                g.uma().calc_score(),
                g.uma().total_pt(),
                g.ramen.rmj_results.iter().take(3).all(|&x| x),
            ))
        })
        .collect();

    let ok = results.len();
    let mut s: Vec<_> = results.iter().map(|x| x.0).collect();
    s.sort_unstable();

    let mean = if ok == 0 { 0.0 } else { s.iter().map(|&x| x as f64).sum::<f64>() / ok as f64 };
    let pt_mean =
        if ok == 0 { 0.0 } else { results.iter().map(|x| x.1 as f64).sum::<f64>() / ok as f64 };
    let rmj_all =
        if ok == 0 { 0.0 } else { results.iter().filter(|x| x.2).count() as f64 * 100.0 / ok as f64 };

    ResultRow {
        name: deck.name.clone(),
        cards: deck.cards,
        ok,
        failed: n - ok,
        mean,
        median: s.get(ok / 2).copied().unwrap_or(0),
        p90: s.get((ok * 9 / 10).min(ok.saturating_sub(1))).copied().unwrap_or(0),
        max: s.last().copied().unwrap_or(0),
        pt_mean,
        rmj_all,
    }
}

/// 轻量评估：只算均分（卡级搜索用）
fn score_of(cards: &[u32; 6], strategy: &RamenStrategy, n: usize) -> f64 {
    let results: Vec<i32> = (0..n)
        .into_par_iter()
        .filter_map(|_| {
            let mut g = RamenGame::newgame(TEST_UMA, cards, INHERIT).ok()?;
            let mut rng = rand::rngs::StdRng::from_os_rng();
            g.run_full_game(strategy, &mut rng).ok()?;
            Some(g.uma().calc_score())
        })
        .collect();
    if results.is_empty() {
        0.0
    } else {
        results.iter().map(|&x| x as f64).sum::<f64>() / results.len() as f64
    }
}

// ── 阶段2：卡级坐标搜索 ─────────────────────────────────

/// 一次换卡记录
struct Swap {
    slot: usize,
    from_id: u32,
    to_id: u32,
    gain: f64,
}

/// 对一套卡组做坐标上升搜索：
/// 逐槽尝试同类型候选池里的其他卡，实测均分超过现任（含留任分差）才换。
/// 每轮扫完全部 5 槽，无改进即停。
/// 返回 (最优卡组, 起点分, 最优分, 换卡记录)；起点分与最优分同为 games 局口径。
fn coordinate_search(
    start: &Deck,
    pools: &[Vec<Card>; 5],
    strategy: &RamenStrategy,
    games: usize,
    max_passes: usize,
) -> (Deck, f64, f64, Vec<Swap>) {
    let mut current = start.clone();
    // 起点用相同局数重测，保证搜索内比较口径一致
    let mut current_score = score_of(&current.cards, strategy, games);
    let initial_score = current_score;
    let mut swaps: Vec<Swap> = vec![];
    let mut evals = 1usize;

    for pass in 0..max_passes {
        let mut improved = false;
        for slot in 0..5 {
            let t = current.types[slot];
            for cand in pools[t].iter() {
                if cand.id == current.cards[slot] || current.cards.contains(&cand.id) {
                    continue;
                }
                let mut trial_cards = current.cards;
                trial_cards[slot] = cand.id;
                let s = score_of(&trial_cards, strategy, games);
                evals += 1;
                if s > current_score + SEARCH_IMPROVE_MARGIN {
                    swaps.push(Swap {
                        slot,
                        from_id: current.cards[slot],
                        to_id: cand.id,
                        gain: s - current_score,
                    });
                    current.cards = trial_cards;
                    current_score = s;
                    improved = true;
                }
            }
        }
        eprintln!(
            "  搜索 pass {}: 均分 {:.0}（累计 {} 次试换）",
            pass + 1,
            current_score,
            evals
        );
        if !improved {
            break;
        }
    }

    (current, initial_score, current_score, swaps)
}

// ── 入口 ────────────────────────────────────────────────

fn card_label(id: u32, names: &BTreeMap<u32, String>) -> String {
    match names.get(&id) {
        Some(n) => format!("{} {}", id, n),
        None if id == FRIEND => format!("{} [友]固定友人", id),
        None => format!("{}", id),
    }
}

fn main() -> anyhow::Result<()> {
    let args: Vec<_> = env::args().collect();
    let n: usize = args.get(1).and_then(|x| x.parse().ok()).unwrap_or(DEFAULT_RUNS);
    let top_k: usize = args.get(2).and_then(|x| x.parse().ok()).unwrap_or(SEARCH_TOP_K);
    let search_games: usize =
        args.get(3).and_then(|x| x.parse().ok()).unwrap_or(SEARCH_GAMES);
    let final_games: usize =
        args.get(4).and_then(|x| x.parse().ok()).unwrap_or(DEFAULT_FINAL_GAMES);

    let min_id: u32 = env::var("MIN_CARD_ID")
        .ok()
        .and_then(|x| x.parse().ok())
        .unwrap_or(DEFAULT_MIN_CARD_ID);

    init_global()?;
    let cards = load_cards(
        &env::var("CARD_DB").unwrap_or_else(|_| "gamedata/cardDB.json".into()),
        min_id,
    )?;
    let names: BTreeMap<u32, String> = cards.iter().map(|c| (c.id, c.name.clone())).collect();
    let pools = build_pools(&cards);
    let decks = build_decks(&pools);
    let strategy: RamenStrategy = env::var("RAMEN_STRATEGY")
        .ok()
        .and_then(|s| serde_json::from_str(&s).ok())
        .unwrap_or_default();

    println!(
        "# 拉面杯卡组基准：构成粗筛 + 卡级搜索 + 终验证\n\n\
         - 每套严格6张：5张普通卡 + 固定友人 {}\n\
         - 阶段1：枚举类型构成（各0..=3张合计5张），proxy头部卡粗筛 {} 局/套，共{}套\n\
         - 阶段2：头部{}种构成逐槽换卡实测搜索，{} 局/次试换\n\
         - 阶段3：搜索赢家+构成冠军+上游基准，终验证 {} 局\n\
         - 仅近期满破SSR（card_id >= {}）\n",
        FRIEND, n, decks.len(), top_k, search_games, final_games, min_id
    );

    // ── 阶段1：构成粗筛 ──
    let start = Instant::now();
    let mut rows: Vec<(Deck, ResultRow)> = decks
        .iter()
        .map(|d| {
            eprintln!("[粗筛] {} {:?}", d.name, d.cards);
            let r = simulate(d, n, &strategy);
            (d.clone(), r)
        })
        .collect();
    rows.sort_by(|a, b| b.1.mean.total_cmp(&a.1.mean));

    println!(
        "\n## 阶段1：构成粗筛（proxy选卡，{}局/套，{:.0}秒）\n",
        n,
        start.elapsed().as_secs_f64()
    );
    println!("|排名|卡组|均分|中位|P90|最高|均PT|RMJ全通|成功/失败|\n|---:|---|---:|---:|---:|---:|---:|---:|---:|");
    for (i, (_, r)) in rows.iter().enumerate() {
        println!(
            "|{}|{}|{:.0}|{}|{}|{}|{:.0}|{:.1}%|{}/{}|",
            i + 1, r.name, r.mean, r.median, r.p90, r.max, r.pt_mean, r.rmj_all, r.ok, r.failed
        );
    }

    // ── 阶段2：卡级坐标搜索 ──
    println!(
        "\n## 阶段2：卡级搜索（头部{}构成，逐槽换卡{}局实测）\n",
        top_k, search_games
    );
    let mut searched: Vec<Deck> = vec![];
    for (deck, row) in rows.iter().take(top_k) {
        eprintln!("[搜索] {}", deck.name);
        let (best, init_score, best_score, swaps) =
            coordinate_search(deck, &pools, &strategy, search_games, SEARCH_PASSES);
        println!(
            "**{}**：{}局口径 {:.0} → 换卡后 {:.0}（{:+.0}）；粗筛分 {:.0}，共换 {} 张",
            deck.name,
            search_games,
            init_score,
            best_score,
            best_score - init_score,
            row.mean,
            swaps.len()
        );
        for s in &swaps {
            println!(
                "  - {}槽：{} → {}（{:+.0}）",
                TYPE_LABELS[deck.types[s.slot]],
                card_label(s.from_id, &names),
                card_label(s.to_id, &names),
                s.gain
            );
        }
        println!();
        searched.push(best);
    }

    // ── 阶段3：终验证 ──
    println!("## 阶段3：终验证（{}局）\n", final_games);
    let mut finalists: Vec<Deck> = searched;
    // 构成冠军（若不在搜索结果里）与上游基准也一并终验
    if let Some((champion, _)) = rows.first() {
        finalists.push(champion.clone());
    }
    finalists.push(Deck {
        name: "上游推荐基准：3速1耐1智+1友".into(),
        cards: BASELINE,
        types: BASELINE_TYPES,
    });
    let mut seen = HashSet::new();
    finalists.retain(|d| seen.insert(d.cards));

    let mut final_rows: Vec<ResultRow> = finalists
        .iter()
        .map(|d| {
            eprintln!("[终验] {} {:?}", d.name, d.cards);
            simulate(d, final_games, &strategy)
        })
        .collect();
    final_rows.sort_by(|a, b| b.mean.total_cmp(&a.mean));

    println!("|排名|卡组|均分|中位|P90|最高|均PT|RMJ全通|成功/失败|\n|---:|---|---:|---:|---:|---:|---:|---:|---:|");
    for (i, r) in final_rows.iter().enumerate() {
        println!(
            "|{}|{}|{:.0}|{}|{}|{}|{:.0}|{:.1}%|{}/{}|",
            i + 1, r.name, r.mean, r.median, r.p90, r.max, r.pt_mean, r.rmj_all, r.ok, r.failed
        );
    }

    println!(
        "\n总耗时：{:.1}秒\n\n## 终验卡组明细\n",
        start.elapsed().as_secs_f64()
    );
    for (i, r) in final_rows.iter().enumerate() {
        let d = r
            .cards
            .iter()
            .map(|id| card_label(*id, &names))
            .collect::<Vec<_>>()
            .join(" / ");
        println!("{}. **{}**：{}", i + 1, r.name, d);
    }

    Ok(())
}
