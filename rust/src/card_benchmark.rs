//! 拉面杯近期 SSR 云端卡组基准。
//!
//! 每套严格 6 张：5 张普通支援卡 + 固定友人 303054。
//! 系统枚举普通卡的类型构成（五种类型各 0..=3 张、合计 5 张），
//! 另对近期力卡、根卡逐张替换测试。
//!
//! 用法：
//!   cargo run --release --bin ramen_card_benchmark -- [局数]
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

/// 被替换测试的卡位（0-based，基准里第 4 张）
const TEST_SLOT: usize = 3;

/// 卡池下界默认值：爱如往昔（cardDB 中 30215 = [速]爱如往昔 SSR）
const DEFAULT_MIN_CARD_ID: u32 = 30215;

/// 每种类型进入构成枚举的卡池大小
const POOL_PER_TYPE: usize = 10;

/// 默认每套模拟局数
const DEFAULT_RUNS: usize = 300;

const TYPE_LABELS: [&str; 5] = ["速", "耐", "力", "根", "智"];

/// 上游推荐基准卡组
const BASELINE: [u32; 6] = [302424, 302894, 303044, 302924, 303024, FRIEND];

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

/// 一套待测卡组
#[derive(Clone)]
struct Deck {
    name: String,
    cards: [u32; 6],
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

/// 加权卡强度：满破(card_value[4])各项加权和，用于构成枚举排序。
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

/// 某类型 proxy 前 n 名的卡池（已按 proxy 降序）
fn top_by_type(cards: &[Card], t: usize, n: usize) -> Vec<Card> {
    let mut v: Vec<Card> = cards.iter().filter(|c| c.card_type == t).cloned().collect();
    v.sort_by(|a, b| b.proxy.total_cmp(&a.proxy));
    v.truncate(n);
    v
}

// ── 卡组构造 ─────────────────────────────────────────────

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

/// 从各类型卡池头部取 counts[t] 张组成卡组；卡池不足则放弃该构成
fn archetype(counts: &[usize; 5], pools: &[Vec<Card>; 5]) -> Option<Deck> {
    if counts.iter().sum::<usize>() != 5 {
        return None;
    }
    let mut ids = Vec::with_capacity(6);
    for t in 0..5 {
        if pools[t].len() < counts[t] {
            return None;
        }
        ids.extend(pools[t].iter().take(counts[t]).map(|c| c.id));
    }
    if ids.len() != 5 {
        return None;
    }
    ids.push(FRIEND);
    Some(Deck { name: composition_name(counts), cards: ids.try_into().ok()? })
}

/// 全部待测卡组：基准 + 枚举构成 + 力/根卡逐测，最后去重校验
fn build_decks(cards: &[Card]) -> Vec<Deck> {
    let pools: [Vec<Card>; 5] = std::array::from_fn(|t| top_by_type(cards, t, POOL_PER_TYPE));

    let mut decks = vec![Deck { name: "上游推荐基准：3速1耐1智+1友".into(), cards: BASELINE }];

    // 五重循环枚举构成：各类型 0..=3 张、合计 5 张
    for speed in 0..=3 {
        for stamina in 0..=3 {
            for power in 0..=3 {
                for guts in 0..=3 {
                    for wiz in 0..=3 {
                        let counts = [speed, stamina, power, guts, wiz];
                        if counts.iter().sum::<usize>() == 5 {
                            if let Some(d) = archetype(&counts, &pools) {
                                decks.push(d);
                            }
                        }
                    }
                }
            }
        }
    }

    // 力(2)/根(3)卡逐测：每张替换 TEST_SLOT 槽位
    for kind in [2usize, 3] {
        let mut candidates: Vec<_> =
            cards.iter().filter(|c| c.card_type == kind).cloned().collect();
        candidates.sort_by(|a, b| b.proxy.total_cmp(&a.proxy));
        for c in candidates {
            let mut d = BASELINE;
            d[TEST_SLOT] = c.id;
            decks.push(Deck {
                name: format!("{}卡逐测：{}（替换1速度槽）", TYPE_LABELS[kind], c.name),
                cards: d,
            });
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

// ── 入口 ────────────────────────────────────────────────

fn main() -> anyhow::Result<()> {
    let args: Vec<_> = env::args().collect();
    let n: usize = args.get(1).and_then(|x| x.parse().ok()).unwrap_or(DEFAULT_RUNS);
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
    let decks = build_decks(&cards);
    let strategy: RamenStrategy = env::var("RAMEN_STRATEGY")
        .ok()
        .and_then(|s| serde_json::from_str(&s).ok())
        .unwrap_or_default();

    println!(
        "# 拉面杯类型构成 + 力根逐卡模拟\n\n\
         - 每套严格6张：5张普通卡 + 固定友人 {}\n\
         - 系统枚举五种类型合计5张、每种最多3张，允许某些类型为0\n\
         - 包含3智1力1耐+1友；另逐张测试近期力卡、根卡\n\
         - 仅近期满破SSR（card_id >= {}），每套{}局，共{}套\n",
        FRIEND, min_id, n, decks.len()
    );

    let start = Instant::now();
    let mut rows: Vec<_> = decks
        .iter()
        .map(|d| {
            eprintln!("模拟 {} {:?}", d.name, d.cards);
            simulate(d, n, &strategy)
        })
        .collect();
    rows.sort_by(|a, b| b.mean.total_cmp(&a.mean));

    println!("|排名|卡组|均分|中位|P90|最高|均PT|RMJ全通|成功/失败|\n|---:|---|---:|---:|---:|---:|---:|---:|---:|");
    for (i, r) in rows.iter().enumerate() {
        println!(
            "|{}|{}|{:.0}|{}|{}|{}|{:.0}|{:.1}%|{}/{}|",
            i + 1, r.name, r.mean, r.median, r.p90, r.max, r.pt_mean, r.rmj_all, r.ok, r.failed
        );
    }

    println!("\n总耗时：{:.1}秒\n\n## 六张卡明细\n", start.elapsed().as_secs_f64());
    for (i, r) in rows.iter().enumerate() {
        let d = r
            .cards
            .iter()
            .map(|id| {
                format!(
                    "{} {}",
                    id,
                    names
                        .get(id)
                        .cloned()
                        .unwrap_or_else(|| if *id == FRIEND { "[友]固定友人".into() } else { "?".into() })
                )
            })
            .collect::<Vec<_>>()
            .join(" / ");
        println!("{}. **{}**：{}", i + 1, r.name, d);
    }

    Ok(())
}
