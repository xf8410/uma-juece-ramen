//! 拉面杯近期 SSR 卡组基准：全排列版。
//!
//! 五个阶段，全部真实换卡真实模拟，proxy 只用来排候选序：
//!
//! 1. **构成粗筛+力/根逐测**（旧版完整保留）—
//!    枚举类型构成（各0..=3张合计5张）用 proxy 头部卡粗筛；
//!    上游基准逐张换入全部力卡/根卡的传统测试；
//! 2. **卡级坐标搜索** — 头部构成逐槽换卡实测爬坡；
//! 3. **全卡实测筛选** — 爱如往昔之后的每一张速耐力根智卡，
//!    逐一真实换进包含该类型的头部参照卡组模拟，得出每类实测排名；
//! 4. **严格构成全排列** — 友人固定；常规卡恰好 3 种类型
//!    （连友人共 4 种配卡）；每类型取实测前 K 张（含赢家保送），
//!    3-1-1 与 2-2-1 两种分配的全部组合逐一真实模拟；
//! 5. **终验** — 全排列头部 + 搜索赢家 + 构成冠军 + 上游基准，
//!    每种组合十万局定名次。
//!
//! 每套严格 6 张：5 张普通支援卡 + 固定友人 303054。
//! 友人卡与团队卡不进普通卡池（剧本友人已占用友人位）。
//!
//! 用法：
//!   cargo run --release --bin ramen_card_benchmark -- \
//!     [粗筛局数] [搜索构成数] [搜索局数] [全排列每类张数K] [全排列局数] [终验局数]
//!   默认: 300 3 150 5 400 100000
//!
//! K 调大覆盖更多组合：K=6 → 62,100 种；K=7 → 144,060 种（CI 约 5 小时）。
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

/// 每种类型进入枚举/搜索的候选池大小（按 proxy 排序取头部）
const POOL_PER_TYPE: usize = 12;

/// 构成粗筛默认每套局数
const DEFAULT_RUNS: usize = 300;

/// 卡级搜索：取头部构成数、每次试换局数、最大轮数、留任分差
const SEARCH_TOP_K: usize = 3;
const SEARCH_GAMES: usize = 150;
const SEARCH_PASSES: usize = 2;
const SEARCH_IMPROVE_MARGIN: f64 = 20.0;

/// 力/根逐测与全卡筛选的替换槽位（基准第 4 张）
const TEST_SLOT: usize = 3;

/// 全卡实测筛选：每张卡每个参照语境的模拟局数
const SCREEN_GAMES: usize = 500;

/// 严格全排列：每类型默认取实测前 K 张
const DEFAULT_STRICT_K: usize = 5;

/// 严格全排列：每类赢家保送上限
const STRICT_BONUS_CAP: usize = 2;

/// 严格全排列：每种组合模拟局数
const DEFAULT_STRICT_GAMES: usize = 400;

/// 严格全排列入围终验的组合数
const STRICT_FINALISTS: usize = 8;

/// 终验局数（用户要求：每一种跑十万局）
const DEFAULT_FINAL_GAMES: usize = 100_000;

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
/// 运行时 ID = card_id*10+4。友人(5)与团队(6)不进池。
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

/// 每类型全量卡池（不截断，按 proxy 降序）——全卡筛选与力/根逐测用
fn all_by_type(cards: &[Card]) -> [Vec<Card>; 5] {
    std::array::from_fn(|t| {
        let mut v: Vec<Card> = cards.iter().filter(|c| c.card_type == t).cloned().collect();
        v.sort_by(|a, b| b.proxy.total_cmp(&a.proxy));
        v
    })
}

// ── 阶段1：构成枚举 + 力/根逐测 ─────────────────────────

/// 构成标签如 "3速1耐1智+1友"
fn comp_label(c: &[usize; 5]) -> String {
    let mut p = vec![];
    for i in 0..5 {
        if c[i] > 0 {
            p.push(format!("{}{}", c[i], TYPE_LABELS[i]));
        }
    }
    format!("{}+1友", p.join(""))
}

/// 构成名如 "构成 3速1耐1智+1友"
fn composition_name(c: &[usize; 5]) -> String {
    format!("构成 {}", comp_label(c))
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

/// 全部待测卡组：上游基准 + 枚举构成 + 力/根逐卡替换（旧版测试，保留）
fn build_decks(pools: &[Vec<Card>; 5], all: &[Vec<Card>; 5]) -> Vec<Deck> {
    let mut decks = vec![Deck {
        name: "上游推荐基准：3速1耐1智+1友".into(),
        cards: BASELINE,
        types: BASELINE_TYPES,
    }];

    // 力/根逐卡替换：基准第 4 张换成每张近期力卡/根卡（真实换卡）
    for t in [2usize, 3usize] {
        for c in all[t].iter() {
            if BASELINE.contains(&c.id) {
                continue;
            }
            let mut cards = BASELINE;
            cards[TEST_SLOT] = c.id;
            let mut types = BASELINE_TYPES;
            types[TEST_SLOT] = t;
            decks.push(Deck {
                name: format!("基准+{}", c.name),
                cards,
                types,
            });
        }
    }

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

/// 轻量评估：只算均分（搜索/筛选/全排列用）
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

// ── 阶段3：全卡实测筛选 ─────────────────────────────────

/// 爱如往昔之后的每一张卡真实换进头部参照卡组模拟，得出每类实测排名。
/// 参照卡组：阶段1 均分最高的 2 套包含该类型的卡组。
/// 卡已在参照卡组里时直接用该卡组实测均分（它就是那张卡的语境成绩）。
fn screen_cards(
    all: &[Vec<Card>; 5],
    rows: &[(Deck, ResultRow)],
    strategy: &RamenStrategy,
    games: usize,
) -> BTreeMap<usize, Vec<(Card, f64)>> {
    let mut out = BTreeMap::new();
    for t in 0..5 {
        let refs: Vec<(&Deck, &ResultRow)> = rows
            .iter()
            .filter(|(d, _)| d.types[..5].contains(&t))
            .take(2)
            .map(|(d, r)| (d, r))
            .collect();
        let mut scored: Vec<(Card, f64)> = vec![];
        for c in &all[t] {
            eprintln!("[筛选] {}槽 {}", TYPE_LABELS[t], c.name);
            let s = if refs.is_empty() {
                0.0
            } else {
                let acc: f64 = refs
                    .iter()
                    .map(|(rd, rr)| {
                        if rd.cards.contains(&c.id) {
                            rr.mean
                        } else {
                            let slot = rd.types[..5].iter().position(|&x| x == t).unwrap();
                            let mut d = rd.cards;
                            d[slot] = c.id;
                            score_of(&d, strategy, games)
                        }
                    })
                    .sum();
                acc / refs.len() as f64
            };
            scored.push((c.clone(), s));
        }
        scored.sort_by(|a, b| b.1.total_cmp(&a.1));
        out.insert(t, scored);
    }
    out
}

// ── 阶段4：严格构成全排列 ───────────────────────────────

/// 组合枚举：从 pool 里取 k 个的所有组合（保持池顺序）
fn combinations(pool: &[u32], k: usize) -> Vec<Vec<u32>> {
    let n = pool.len();
    let mut out = vec![];
    if k == 0 || k > n {
        return out;
    }
    let mut idx: Vec<usize> = (0..k).collect();
    'outer: loop {
        out.push(idx.iter().map(|&i| pool[i]).collect());
        // 从右往左找可递增位
        let mut i = k;
        loop {
            if i == 0 {
                break 'outer;
            }
            i -= 1;
            if idx[i] < n - (k - i) {
                idx[i] += 1;
                for j in i + 1..k {
                    idx[j] = idx[j - 1] + 1;
                }
                break;
            }
        }
    }
    out
}

/// 从槽位类型表算构成标签
fn comp_of_types(types: &[usize; 6]) -> String {
    let mut counts = [0usize; 5];
    for &t in &types[..5] {
        counts[t] += 1;
    }
    comp_label(&counts)
}

/// 严格构成全排列：常规卡恰好 3 种类型（连友人共 4 种配卡）。
/// 每类型取 strict_pools[t]，枚举 3-1-1 与 2-2-1 两种分配的全部组合，
/// 每种组合真实模拟 games 局，返回按分降序的 (卡组, 分) 列表。
fn strict_enumerate(
    strict_pools: &[Vec<u32>; 5],
    strategy: &RamenStrategy,
    games: usize,
) -> Vec<(Deck, f64)> {
    let mut seen: HashSet<Vec<u32>> = HashSet::new();
    let mut out: Vec<(Deck, f64)> = vec![];
    let mut tried = 0usize;

    // 10 个三元类型组
    let mut sets: Vec<[usize; 3]> = vec![];
    for a in 0..5 {
        for b in a + 1..5 {
            for c in b + 1..5 {
                sets.push([a, b, c]);
            }
        }
    }

    for set in &sets {
        // 分配一：某类出 3 张（3-1-1）
        for &big in set {
            let others: Vec<usize> = set.iter().copied().filter(|&x| x != big).collect();
            for triple in combinations(&strict_pools[big], 3) {
                for &x in &strict_pools[others[0]] {
                    for &y in &strict_pools[others[1]] {
                        let mut ids = triple.clone();
                        ids.push(x);
                        ids.push(y);
                        let mut key = ids.clone();
                        key.sort();
                        if !seen.insert(key) {
                            continue;
                        }
                        let types = [big, big, big, others[0], others[1], 5];
                        ids.push(FRIEND);
                        let cards: [u32; 6] = ids.try_into().unwrap();
                        tried += 1;
                        if tried % 2000 == 0 {
                            eprintln!("  全排列进度：{} 种组合", tried);
                        }
                        let s = score_of(&cards, strategy, games);
                        out.push((
                            Deck {
                                name: comp_of_types(&types),
                                cards,
                                types,
                            },
                            s,
                        ));
                    }
                }
            }
        }
        // 分配二：某类出 1 张（2-2-1）
        for &single in set {
            let pairs: Vec<usize> = set.iter().copied().filter(|&x| x != single).collect();
            for pa in combinations(&strict_pools[pairs[0]], 2) {
                for pb in combinations(&strict_pools[pairs[1]], 2) {
                    for &s0 in &strict_pools[single] {
                        let mut ids = pa.clone();
                        ids.extend_from_slice(&pb);
                        ids.push(s0);
                        let mut key = ids.clone();
                        key.sort();
                        if !seen.insert(key) {
                            continue;
                        }
                        let types = [pairs[0], pairs[0], pairs[1], pairs[1], single, 5];
                        ids.push(FRIEND);
                        let cards: [u32; 6] = ids.try_into().unwrap();
                        tried += 1;
                        if tried % 2000 == 0 {
                            eprintln!("  全排列进度：{} 种组合", tried);
                        }
                        let s = score_of(&cards, strategy, games);
                        out.push((
                            Deck {
                                name: comp_of_types(&types),
                                cards,
                                types,
                            },
                            s,
                        ));
                    }
                }
            }
        }
    }

    eprintln!("  全排列完成：共 {} 种组合", tried);
    out.sort_by(|a, b| b.1.total_cmp(&a.1));
    out
}

// ── 入口 ────────────────────────────────────────────────

fn card_label(id: u32, names: &BTreeMap<u32, String>) -> String {
    match names.get(&id) {
        Some(n) => format!("{} {}", id, n),
        None if id == FRIEND => format!("{} [友]固定友人", id),
        None => format!("{}", id),
    }
}

fn print_row_table(rows: &[&ResultRow]) {
    println!("|排名|卡组|均分|中位|P90|最高|均PT|RMJ全通|成功/失败|\n|---:|---|---:|---:|---:|---:|---:|---:|---:|");
    for (i, r) in rows.iter().enumerate() {
        println!(
            "|{}|{}|{:.0}|{}|{}|{}|{:.0}|{:.1}%|{}/{}|",
            i + 1, r.name, r.mean, r.median, r.p90, r.max, r.pt_mean, r.rmj_all, r.ok, r.failed
        );
    }
}

fn main() -> anyhow::Result<()> {
    let args: Vec<_> = env::args().collect();
    let n: usize = args.get(1).and_then(|x| x.parse().ok()).unwrap_or(DEFAULT_RUNS);
    let top_k: usize = args.get(2).and_then(|x| x.parse().ok()).unwrap_or(SEARCH_TOP_K);
    let search_games: usize =
        args.get(3).and_then(|x| x.parse().ok()).unwrap_or(SEARCH_GAMES);
    let strict_k: usize =
        args.get(4).and_then(|x| x.parse().ok()).unwrap_or(DEFAULT_STRICT_K);
    let strict_games: usize =
        args.get(5).and_then(|x| x.parse().ok()).unwrap_or(DEFAULT_STRICT_GAMES);
    let final_games: usize =
        args.get(6).and_then(|x| x.parse().ok()).unwrap_or(DEFAULT_FINAL_GAMES);

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
    let all = all_by_type(&cards);
    let decks = build_decks(&pools, &all);
    let strategy: RamenStrategy = env::var("RAMEN_STRATEGY")
        .ok()
        .and_then(|s| serde_json::from_str(&s).ok())
        .unwrap_or_default();

    println!(
        "# 拉面杯卡组基准：全排列版\n\n\
         - 每套严格6张：5张普通卡 + 固定友人 {}\n\
         - 卡池：爱如往昔后满破SSR共{}张（友人/团队不进池），每类 {:?}\n\
         - 阶段1：构成粗筛+力/根逐测，{} 局/套，共{}套\n\
         - 阶段2：头部{}种构成逐槽换卡实测搜索，{} 局/次试换\n\
         - 阶段3：全卡实测筛选（每张卡真实换进头部参照卡组，{} 局/语境）\n\
         - 阶段4：严格构成全排列（恰好3种常规类型+友人=4种配卡），\n\
         \x20\x20\x20\x20\x20\x20\x20\x20每类实测前{}张（含赢家保送），每种 {} 局\n\
         - 阶段5：终验 {} 局/种（全排列前{} + 搜索赢家 + 构成冠军 + 上游基准）\n",
        FRIEND,
        cards.len(),
        all.iter().map(|v| v.len()).collect::<Vec<_>>(),
        n,
        decks.len(),
        top_k,
        search_games,
        SCREEN_GAMES,
        strict_k,
        strict_games,
        final_games,
        STRICT_FINALISTS,
    );

    let start = Instant::now();

    // ── 阶段1：构成粗筛 + 力/根逐测 ──
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
        "\n## 阶段1：构成粗筛+力/根逐测（{}局/套，{:.0}秒）\n",
        n,
        start.elapsed().as_secs_f64()
    );
    let row_refs: Vec<&ResultRow> = rows.iter().map(|(_, r)| r).collect();
    print_row_table(&row_refs);

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

    // ── 阶段3：全卡实测筛选 ──
    println!(
        "\n## 阶段3：全卡实测筛选（每张卡真实换进头部参照卡组，{}局/语境）\n",
        SCREEN_GAMES
    );
    let screened = screen_cards(&all, &rows, &strategy, SCREEN_GAMES);
    for t in 0..5 {
        println!("### {}槽实测排名（前8）\n", TYPE_LABELS[t]);
        println!("|排名|卡|实测分|");
        for (i, (c, s)) in screened[&t].iter().take(8).enumerate() {
            println!("|{}|{}|{:.0}|", i + 1, c.name, s);
        }
        println!();
    }

    // ── 阶段4：严格构成全排列 ──
    // 每类入池 = 搜索赢家/头部卡组保送（至多2张） + 实测排名补足到 K
    let mut strict_pools: [Vec<u32>; 5] = std::array::from_fn(|_| vec![]);
    for t in 0..5 {
        let mut chosen: Vec<u32> = vec![];
        let sources: Vec<&Deck> = searched
            .iter()
            .chain(rows.iter().take(2).map(|(d, _)| d))
            .collect();
        'src: for d in sources {
            for slot in 0..5 {
                if d.types[slot] == t && !chosen.contains(&d.cards[slot]) {
                    chosen.push(d.cards[slot]);
                    if chosen.len() >= STRICT_BONUS_CAP {
                        break 'src;
                    }
                }
            }
        }
        for (c, _) in screened[&t].iter() {
            if chosen.len() >= strict_k {
                break;
            }
            if !chosen.contains(&c.id) {
                chosen.push(c.id);
            }
        }
        strict_pools[t] = chosen;
    }

    println!(
        "\n## 阶段4：严格构成全排列（恰好3种常规类型+友人=4种配卡）\n"
    );
    println!("各类型入池卡：");
    for t in 0..5 {
        let list = strict_pools[t]
            .iter()
            .map(|id| {
                let score = screened[&t]
                    .iter()
                    .find(|(c, _)| c.id == *id)
                    .map(|(_, s)| format!("{:.0}", s))
                    .unwrap_or_else(|| "保送".into());
                format!("{}({})", card_label(*id, &names), score)
            })
            .collect::<Vec<_>>()
            .join("、");
        println!("- {}：{}", TYPE_LABELS[t], list);
    }
    println!();

    let strict_results = strict_enumerate(&strict_pools, &strategy, strict_games);
    println!(
        "\n### 全排列结果（{}局/种，共{}种，头部20）\n",
        strict_games,
        strict_results.len()
    );
    println!("|排名|构成|实测分|六张卡|");
    println!("|---:|---|---:|---|");
    for (i, (d, s)) in strict_results.iter().take(20).enumerate() {
        let list = d
            .cards
            .iter()
            .map(|id| card_label(*id, &names))
            .collect::<Vec<_>>()
            .join(" / ");
        println!("|{}|{}|{:.0}|{}|", i + 1, d.name, s, list);
    }

    // ── 阶段5：终验（十万局） ──
    println!("\n## 阶段5：终验（{}局/种）\n", final_games);
    let mut finalists: Vec<Deck> = vec![];
    let mut seen_final: HashSet<Vec<u32>> = HashSet::new();
    let push_final = |d: Deck, seen: &mut HashSet<Vec<u32>>, out: &mut Vec<Deck>| {
        let mut key = d.cards[..5].to_vec();
        key.sort();
        if seen.insert(key) {
            out.push(d);
        }
    };
    for (i, (d, _)) in strict_results.iter().take(STRICT_FINALISTS).enumerate() {
        let mut d = d.clone();
        d.name = format!("全排列#{} {}", i + 1, d.name);
        push_final(d, &mut seen_final, &mut finalists);
    }
    for d in searched {
        let mut d = d;
        d.name = format!("搜索·{}", d.name);
        push_final(d, &mut seen_final, &mut finalists);
    }
    if let Some((champion, _)) = rows.first() {
        let mut d = champion.clone();
        d.name = format!("粗筛冠军·{}", d.name);
        push_final(d, &mut seen_final, &mut finalists);
    }
    push_final(
        Deck {
            name: "上游推荐基准：3速1耐1智+1友".into(),
            cards: BASELINE,
            types: BASELINE_TYPES,
        },
        &mut seen_final,
        &mut finalists,
    );

    let mut final_rows: Vec<ResultRow> = finalists
        .iter()
        .map(|d| {
            eprintln!("[终验] {} {:?}", d.name, d.cards);
            simulate(d, final_games, &strategy)
        })
        .collect();
    final_rows.sort_by(|a, b| b.mean.total_cmp(&a.mean));

    let final_refs: Vec<&ResultRow> = final_rows.iter().collect();
    print_row_table(&final_refs);

    println!(
        "\n总耗时：{:.1}秒（约{:.0}分钟）\n\n## 终验卡组明细\n",
        start.elapsed().as_secs_f64(),
        start.elapsed().as_secs_f64() / 60.0
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
