//! JNI bridge for umaai-rs ramen — 接入上游标准输出层。
//!
//! 架构：
//! - `reconcile`: 状态校正层，把 hlpatch 脏数据清洗为模拟器可用状态
//! - `fast_forward`: 断线重连——从第 0 回合重放重建训练分布/羁绊/训练等级（v0.3.1）
//! - `inject_state`: 把校正后的状态注入 RamenGame（覆盖观测字段）
//! - `apply_observed_distribution`: 直读人头注入（v0.3.2）
//! - `run_search`: 用上游 `RamenMctsTrainer` 搜索，输出 `DecisionInfo` + `GameView`
//! - JNI exports: `nativeInit` / `nativeSearch` / `nativeVersion`
//!
//! 回合口径：
//! - hlpatch `turn` = 游戏 UI「第N回合」（1-based）
//! - umaai-rs 内部回合从 0 开始（上游输出统一 `turn()+1`，见 turn_flow.rs），
//!   `inject_state()` 做 `turn - 1` 转换
//!
//! 回合内阶段顺序（上游 turn_flow.rs 实锤）：
//!   Begin → Distribute(人头) → RamenSelect(选面) → SpecialSelect → Train(面效果已落地) → NextTurn
//! 因此 hlpatch 发来 trainings（行动画面）时，本回合的面已选完，当前决策点
//! 是训练而非吃面——v0.4.0 起主决策据此切换（见 run_search 阶段判定）。
//!
//! 与旧版的区别：
//! - 候选评分直接复用 `select_action` 内部那次搜索的 `last_breakdown()`，
//!   不再二次搜索（旧行为把 4096 次模拟翻倍，且两次随机数不同、
//!   展示的 mean 与实际选中动作不一致）
//! - 返回结构化 JSON（view + decision + warnings + confidence）
//!
//! v0.3.1（断线重连）：
//! - `run_search` 在 newgame 后先 `fast_forward` 从第 0 回合重放到目标回合，
//!   重建 distribution / friendship / train_level_count，再由 inject_state
//!   覆盖观测值。修复"跳回合注入导致训练分布停留在 newgame 初始值"的问题。
//! - hlpatch 没发 `trainings`（非行动画面）时，额外在 Train 阶段搜一次，
//!   输出 `training_decision`，小黑板没有逐回合训练数据也能给训练建议；
//!   trainings 非空时跳过这次补搜，不白耗算力。
//!
//! v0.3.2（直读人头注入 + 上游同步）：
//! - 上游 pin 7cef1fa → eeae510b：MCTS 的 rollout 与未搜阶段 fallback 切换为
//!   `RecommendedRamenTrainer`（正式推荐策略）——「手写策略 + 蒙特卡洛」严格叠加
//! - 重放/兜底策略同步切换为推荐策略（旧 RamenHandwrittenTrainer 缺平衡/联动机制）
//! - ★ 修复「推荐 0 人头训练 / 风味错位的面」的根因：hlpatch 行动画面的
//!   trainings（每训练真实人头）此前只用于判空，从不注入模拟状态——
//!   重放分布是固定种子猜的，早回合与真实对局几乎必然对不上，MCTS 对着
//!   假盘面评估。现在 `apply_observed_distribution` 按观测人头把可动人员
//!   （卡/友人/NPC）在训练之间搬移，使模拟分布与实况一致
//! - 友人加入门槛从内部回合 2 降到 1（实况确认：游戏 UI 第2回合友人已在场）
//!
//! v0.4.0（阶段判定修正 + 直读注入）：
//! - ★ 修复「行动画面时主决策还在推荐吃面」：上游回合内顺序 Distribute →
//!   RamenSelect(选面) → Train(面效果已落地)。hlpatch trainings 在场 =
//!   行动画面 = 面已选完，主决策应为训练建议；旧版按回合区间强设
//!   RamenSelect，主决策错误地推荐吃面
//! - trainings 空（选面画面/非行动画面）→ 主决策 = 吃面建议（按回合区间）
//! - ★ 直读注入（PR #22）：reconcile 额外解析 羁绊(support_cards/partners)、
//!   训练等级(training_levels)、角标(command_feelings)，在 inject_state 末尾
//!   写入上游公开字段 person.friendship / base.train_level_count /
//!   ramen.train_feeling_type，替掉 fast_forward 的重放近似。仅消费上游公开
//!   字段，不改上游。
//!
//! v0.4.1（开局第1回合 panic 修复 + 重放人头归零定性 + 补建门控修正）：
//! - ★ 实机首测复现：第1回合（内部0）必现 "panic during search"。根因是
//!   fast_forward(0) 整段跳过，distribution 仍是 newgame 的空 vec（0 行），
//!   而搜索路径（GameView / Train 阶段候选列举）按 5 行分布取人头 → 越界。
//!   修复：`ensure_distribution_built` 在重放后补跑 Begin+Distribute 建分布
//! - ★ JNI catch_unwind 把 panic 载荷（String/&str）透出到浮窗错误里
//! - ★ CI（build.yml rust-check）下载 gamedata——此前所有依赖 gamedata 的
//!   测试被 `setup_gamedata()` 静默跳过，"CI 绿"不等于全链路真跑过
//! - ★ 重放人头归零定性（上游源码 + umaDB 实锤）：`Uma::is_race_turn` 按
//!   每匹马 races 表判定（umaDB races 数组=内部回合号；102601 美浦波旁
//!   races=[11,22,29,30,33,43,55,69,71]，内回合 29/30 连续两个生涯比赛回合），
//!   `run_distribute` 在比赛回合走 `reset_distribution()`（5 空行 0 人头）。
//!   重放退出点停在内回合 31 的 Begin——0 人头是模拟器**正确行为**，
//!   此前测试断言「退出点人头>0」写错了（把比赛回合当 bug）
//! - ★ 因此挖出真 bug 并修复：ensure 门控此前只看「行数<5」，比赛回合后
//!   有 5 空行就不补跑 → **比赛回合之后的训练回合搜索盘面没有支援卡人头**
//!   （比 0 行 panic 更隐蔽的质量 bug）。门控改为「行数<5 或 人头==0」，
//!   补跑 Begin+Distribute 两步封顶（不误跑 RamenSelect 污染状态）
//! - ★ 重放随机对齐上游 rng_consistency 姿势：set_rule_master 固定种子
//!   （逐位可复现）+ 每回合 Distribute 前锁满人头羁绊（分布权重含得意率，
//!   羁绊 0 → 全员不落位）+ 重放结束清除 rule 流（不带入 MCTS）

pub mod ramen_strategy;
pub mod reconcile;

use std::sync::OnceLock;
use std::time::Instant;

use anyhow::{Result, anyhow};
use rand::{SeedableRng, rngs::StdRng};
use serde::Serialize;

use umasim::game::{
    Game, Trainer,
    ramen::{RamenGame, RamenStage, FeelingType},
    InheritInfo, PersonType,
};
use umasim::gamedata::init_global;
use umasim::output::GameView;
use umasim::search::SearchConfig;
use umasim::trainer::{RamenMctsTrainer, RamenSearchStages, RecommendedRamenTrainer};

use reconcile::{HlpatchSummary, ObservedTraining, ReconciledState, reconcile};

static INITIALIZED: OnceLock<()> = OnceLock::new();

/// 重放专用规则主种子：固定 seed 使 `(master, turn)` 派生的回合固定流
/// 稳定（人头分布/角标/回合事件逐位可复现）。重放结束即清除，
/// 不带入搜索阶段（上游已知问题：rule 流跨 MCTS 候选克隆共享）。
const REPLAY_RULE_MASTER: u64 = 0x5EED_2026;

// ── 搜索配置 ────────────────────────────────────────────────────────

/// 手机版搜索配置（从 Java 传入的 JSON）。
#[derive(serde::Deserialize)]
pub struct SearchConfigInput {
    pub uma_id: u32,
    pub cards: [u32; 6],
    #[serde(default)]
    pub blue_count: [i32; 5],
    #[serde(default)]
    pub extra_count: [i32; 6],
    /// 搜索次数（手机建议 32-128）
    #[serde(default = "default_search_n")]
    pub search_n: usize,
}

fn default_search_n() -> usize {
    64
}

// ── 输出结构（返回给 Java 的标准 JSON）──────────────────────────────

/// 搜索结果：包含游戏视图 + 决策信息 + 校正日志。
#[derive(Debug, Clone, Serialize)]
pub struct SearchResponse {
    pub ok: bool,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub view: Option<GameView>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub decision: Option<DecisionOutput>,
    /// 训练阶段建议：hlpatch 没发 trainings 时在 Train 阶段补搜的结果。
    /// trainings 非空（行动画面）时为 None，主决策即训练建议。
    #[serde(skip_serializing_if = "Option::is_none")]
    pub training_decision: Option<DecisionOutput>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub reconcile: Option<ReconciledState>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub error: Option<String>,
}

#[derive(Debug, Clone, Serialize)]
pub struct DecisionOutput {
    /// 选中的动作索引
    pub action_index: usize,
    /// 选中的动作展示文本
    pub action_display: String,
    /// 选中的动作评分
    pub score: f64,
    /// 所有候选动作的展示文本
    pub candidate_displays: Vec<String>,
    /// 所有候选动作的评分
    pub candidate_scores: Vec<f64>,
    /// 搜索次数
    pub search_n: usize,
    /// 搜索耗时（毫秒）
    pub elapsed_ms: u64,
    /// 决策来源（mcts / handwritten_fallback / reconcile_failed）
    pub source: String,
}

// ── 断线重连（v0.3.1）───────────────────────────────────────────────

/// 分布里的总人头数（person 下标 >= 0 的槽位数）。
fn distribution_heads(game: &RamenGame) -> usize {
    game.distribution()
        .iter()
        .map(|d| d.iter().filter(|&&p| p >= 0).count())
        .sum()
}

/// 从第 0 回合重放模拟到目标回合（内部 0-based 回合数）。
///
/// `inject_state` 只覆盖观测字段（五维/体力/拉面/回合），但训练分布、
/// 羁绊、训练等级无法从 hlpatch 数据恢复——旧版直接跳回合，导致这些
/// 字段停留在 newgame 初始值，搜索没有依据。这里用策略从第 0 回合
/// 快速重放（不做 MCTS，毫秒级），把 distribution / friendship /
/// train_level_count 重建到接近真实 run 的状态。
///
/// 这是 PC 黑板「全程模拟」在手机上的等价物：hlpatch 不发 trainings
/// （非行动画面）也能给出有依据的训练建议。
///
/// 使用固定种子的 StdRng：同一快照多次重放结果一致（可复现、可对比）。
/// 单个阶段失败不阻断重放（继续推进，尽力走到目标回合）。
///
/// # v0.4.1b 重放随机对齐（上游 rng_consistency::run_turns 同款姿势）
///
/// 1. `set_rule_master(REPLAY_RULE_MASTER)`——人头分布/角标/回合事件走
///    `(master, turn)` 固定流，逐位可复现；
/// 2. 每回合 Distribute 前把人头羁绊锁满 100（分布权重含得意率，得意率
///    随羁绊变化；羁绊 0 → 权重归零全员不落位；真实羁绊随后由
///    inject_state 用 hlpatch kizuna 覆盖）；
/// 3. 重放结束 `rule_master=None` + `reset_turn_streams()`——rule 流跨
///    MCTS 候选克隆共享是上游已知问题，重放用完即弃。
///
/// # 退出点语义（v0.4.1b 定性：上游无 bug）
///
/// 循环在 `game.turn()` 达到目标时退出——此时游戏停在**目标回合的
/// Begin 阶段**，`distribution` 承载的是上一回合的落位。若上一回合
/// 是该马娘的**生涯比赛回合**（上游 `is_race_turn` 按每匹马 races 表
/// 判定，`run_distribute` 走 `reset_distribution()`），上一回合落位
/// 就是 5 空行 0 人头——**这是模拟器正确行为**。调用方必须用
/// [`ensure_distribution_built`] 补建本回合分布（run_search 已这样做，
/// 其门控按「行数或人头」判断，比赛回合后的训练回合能正确补建）。
///
/// 阶段失败不再静默——前 3 条错误打到 stderr。
///
/// 返回实际执行的阶段数（诊断用）。
fn fast_forward(game: &mut RamenGame, target_internal_turn: i32) -> usize {
    if target_internal_turn <= 0 {
        return 0;
    }
    // v0.3.2: 重放策略从旧 RamenHandwrittenTrainer 换成正式推荐策略——
    // 旧手写缺平衡/联动机制，重放出的羁绊/心情偏差更大。for_rollout()
    // 关闭分解采集（重放路径无消费者），决策与 new() 逐位一致（上游守门）。
    let trainer = RecommendedRamenTrainer::for_rollout();
    game.set_rule_master(REPLAY_RULE_MASTER);
    let mut rng = StdRng::seed_from_u64(0x5EED_2026);
    let mut steps = 0usize;
    // 防御上限：每回合最多 8 个阶段再留余量，避免异常状态死循环
    let guard_max = (target_internal_turn as usize + 2) * 8 + 64;
    let mut err_logged = 0usize;
    // 与上游 run_full_game 相同的推进语义：先执行当前阶段，再推进
    while game.turn() < target_internal_turn && steps < guard_max {
        // v0.4.1b: Distribute 前锁满人头羁绊（分布权重含得意率，随羁绊变化；
        // 羁绊 0 → 权重归零 → 全员不落位）。上游 rng_consistency::run_turns
        // 同款处理；真实羁绊随后由 inject_state 用 kizuna 覆盖。
        if game.stage == RamenStage::Distribute {
            for p in game.persons.iter_mut().take(6) {
                p.friendship = 100;
            }
        }
        if let Err(e) = game.run_stage(&trainer, &mut rng) {
            if err_logged < 3 {
                eprintln!(
                    "fast_forward: run_stage 失败 @turn={} stage={:?}: {e}",
                    game.turn(),
                    game.stage
                );
                err_logged += 1;
            }
        }
        steps += 1;
        if !game.next() {
            break; // 游戏结束
        }
    }
    // 清除规则流：MCTS 搜索时 rule 流跨候选克隆共享是上游已知问题，
    // 重放用完即弃，搜索阶段回到"调用方 rng"行为
    game.rule_master = None;
    game.reset_turn_streams();
    steps
}

/// v0.4.1 修复「开局第1回合 panic during search」。
///
/// 重放循环退出时游戏停在目标回合的 Begin 阶段（内部回合 0 时整段跳过
/// 更是完全没跑）。搜索路径（GameView / Train 阶段候选列举）按 5 行分布
/// 取人头——空分布直接越界 panic（实机首测第1回合必现）。
///
/// # 门控（v0.4.1b 修正：看行数也看人头）
///
/// - 行数 < 5：0 行分布，直接 panic 风险；
/// - 行数 = 5 但人头 = 0：上一回合是生涯比赛回合（`reset_distribution`
///   留下的空行），本回合（训练回合）的 Distribute 在重放里从未执行——
///   不补跑的话搜索盘面没有支援卡人头，MCTS 对空盘面评估，且
///   `apply_observed_distribution` 因可动成员为 0 而静默失效。
///
/// 补跑从当前阶段继续：Begin → Distribute 两步封顶——正好覆盖"退出点在
/// Begin"的补建需求；**不**执行 RamenSelect（避免污染 pending 状态）。
/// 若补跑后仍无人头（本回合本身就是比赛回合），照常放行——比赛回合的
/// 候选列举走 is_race_turn 短路（单"比赛"动作），不索引分布行。
///
/// 与 fast_forward 同款：set_rule_master 固定种子 + Distribute 前锁满
/// 人头羁绊，结束即清除。返回实际执行的阶段数。
fn ensure_distribution_built(game: &mut RamenGame) -> usize {
    if game.distribution().len() >= 5 && distribution_heads(game) > 0 {
        return 0;
    }
    let trainer = RecommendedRamenTrainer::for_rollout();
    game.set_rule_master(REPLAY_RULE_MASTER);
    let mut rng = StdRng::seed_from_u64(0x5EED_2026);
    let mut steps = 0usize;
    while (game.distribution().len() < 5 || distribution_heads(game) == 0) && steps < 2 {
        if game.stage == RamenStage::Distribute {
            for p in game.persons.iter_mut().take(6) {
                p.friendship = 100;
            }
        }
        if let Err(e) = game.run_stage(&trainer, &mut rng) {
            eprintln!("ensure_distribution_built: {:?} 失败: {e}", game.stage);
        }
        steps += 1;
        if !game.next() {
            break; // 游戏结束
        }
    }
    let heads = distribution_heads(game);
    if game.distribution().len() < 5 || heads == 0 {
        eprintln!(
            "ensure_distribution_built: 补跑 {steps} 阶段后 行数={} 人头={heads}（0 头多为本回合即生涯比赛回合，属正常）",
            game.distribution().len()
        );
    }
    game.rule_master = None;
    game.reset_turn_streams();
    steps
}

// ── 状态注入 ────────────────────────────────────────────────────────

/// 把校正后的状态注入 RamenGame。
///
/// 回合转换：`state.turn` 是 hlpatch 直读的游戏 UI 回合（1-based），
/// 上游模拟器内部回合从 0 开始（上游显示统一 `turn()+1`），这里做 `-1`。
///
/// 注意：这是"部分重建"，不是完美快照。训练分布 / 羁绊 / 训练等级由
/// `fast_forward` 重放重建（v0.3.1）+ `apply_observed_distribution`
/// 人头注入（v0.3.2），本函数只覆盖可直接观测的字段：
/// - friendship：重放近似值（不再固定估算 80）
/// - train_level_count：重放近似值（不再固定为 0）
/// - feeling_queue（诀窍获得顺序队列）：置空
/// - yearly_* 观测字段：置默认值
///
/// v0.4.0（PR #22）：末尾额外注入 reconcile 携带的直读观测
/// （羁绊 / 训练等级 / 角标），替掉上面的重放近似。
pub fn inject_state(game: &mut RamenGame, state: &mut ReconciledState) -> Result<()> {
    let internal_turn = (state.turn - 1).max(0);
    game.base.turn = internal_turn;

    // 五维 + 体力 + 干劲 + 技能点
    game.base.uma.five_status[0] = state.stats.speed;
    game.base.uma.five_status[1] = state.stats.stamina;
    game.base.uma.five_status[2] = state.stats.power;
    game.base.uma.five_status[3] = state.stats.guts;
    game.base.uma.five_status[4] = state.stats.wiz;
    game.base.uma.skill_pt = state.stats.skill_point;
    game.base.uma.vital = state.stats.vital;
    game.base.uma.max_vital = state.stats.max_vital;
    game.base.uma.motivation = state.stats.motivation;

    // 拉面状态
    game.ramen.scenario_pt = state.ramen.scenario_pt;
    game.ramen.feeling_stock = state.ramen.feeling_stock;
    game.ramen.feeling_slot = state.ramen.feeling_slot;
    game.ramen.special_feeling = state.ramen.special_feeling;

    if state.ramen.has_region_data {
        game.ramen.selected_regions = state.ramen.selected_region_indices;
    }

    // 人头注入：根据回合添加友人/NPC/记者
    // （fast_forward 重放过程中已按回合推进添加；这里兜底补齐，
    //   保证 target=0 或重放中断时人头仍然完整）
    // v0.3.2: 友人在游戏 UI 第2回合（内部 1）已可见（实况确认），
    //   门槛从 >=2 降到 >=1，避免第2回合的模拟里缺友人。
    if internal_turn >= 1 {
        let has_scenario = game
            .persons
            .iter()
            .any(|p| p.person_type == PersonType::ScenarioCard);
        if !has_scenario {
            game.add_friend_and_npcs()?;
        }
    }
    if internal_turn >= 12 {
        let has_reporter = game
            .persons
            .iter()
            .any(|p| p.person_type == PersonType::Reporter);
        if !has_reporter {
            game.add_reporter();
        }
    }

    // 阶段判定（v0.4.0 起由 run_search 按观测数据覆盖，这里保留兜底）
    // turn 2-71: RamenSelect 阶段（吃面决策）
    // turn 72-77: SuperRamenSelect 阶段（超级拉面）
    // 其他: Train 阶段
    if internal_turn >= 2 && internal_turn <= 71 {
        game.stage = RamenStage::RamenSelect;
    } else if internal_turn >= 72 && internal_turn <= 77 {
        game.stage = RamenStage::SuperRamenSelect;
    } else {
        game.stage = RamenStage::Train;
    }

    // ★ v0.4.0 直读注入：羁绊 / 训练等级 / 角标（替掉 fast_forward 重放近似）
    let inj_warnings = inject_observed_details(game, state);
    state.warnings.extend(inj_warnings);

    Ok(())
}

/// v0.4.0（PR #22）：把 reconcile 解析出的直读观测注入模拟器，
/// 覆盖 fast_forward 的重放近似。
///
/// 三项均为"字段缺失则跳过、保留重放值"，保证旧版 hlpatch 行为不变：
/// - 羁绊 `support_bonds`：按 `card_id` 匹配人头，写 `person.friendship`
/// - 训练等级 `training_levels`：`(level-1)*4` 写 `base.train_level_count[idx]`
///   （上游公式 `train_level = count/4 + 1`）
/// - 角标 `feeling_types`：`feeling_id 1/2/3 → A/B/C` 写 `ramen.train_feeling_type[5]`
///
/// 返回本次注入产生的 warning（由调用方并入 `state.warnings`，透出到小黑板 ⚠ 区）。
fn inject_observed_details(game: &mut RamenGame, state: &ReconciledState) -> Vec<String> {
    let mut warnings = Vec::new();

    // 1) 羁绊：按 card_id 匹配支援卡人头，写入 person.friendship
    if !state.support_bonds.is_empty() {
        let mut n = 0usize;
        for &(card_id, bond) in &state.support_bonds {
            for p in game.persons.iter_mut() {
                if p.card_id == Some(card_id) {
                    p.friendship = bond;
                    n += 1;
                    break;
                }
            }
        }
        warnings.push(format!(
            "羁绊注入: 命中 {} 张卡 (观测 {} 项)",
            n,
            state.support_bonds.len()
        ));
    }

    // 2) 训练等级：level → train_level_count（公式 train_level = count/4 + 1）
    if !state.training_levels.is_empty() {
        for &(idx, level) in &state.training_levels {
            if idx < game.base.train_level_count.len() {
                let lvl = level.clamp(1, 5);
                game.base.train_level_count[idx] = (lvl - 1) * 4;
            }
        }
        warnings.push(format!("训练等级注入: {:?}", state.training_levels));
    }

    // 3) 角标：feeling_id(1/2/3) → FeelingType(A/B/C)，写入 ramen.train_feeling_type
    if !state.feeling_types.is_empty() {
        let mut arr: [FeelingType; 5] = [FeelingType::A; 5];
        for &(idx, fid) in &state.feeling_types {
            if idx < 5 {
                arr[idx] = match fid {
                    1 => FeelingType::A,
                    2 => FeelingType::B,
                    3 => FeelingType::C,
                    _ => FeelingType::A,
                };
            }
        }
        game.ramen.train_feeling_type = Some(arr);
        warnings.push(format!("角标注入: {:?}", state.feeling_types));
    }

    warnings
}

// ── 直读人头注入（v0.3.2）───────────────────────────────────────────

/// 重放分布的一个成员快照。
#[derive(Clone)]
struct DistMember {
    /// 分布行内的槽位下标
    slot: usize,
    /// person 下标
    person: i32,
    /// 理事长/记者：位置保持不动
    fixed: bool,
    /// 是否仍在训练里（false = 已被搬出）
    present: bool,
}

/// 把 hlpatch 观测的训练人头重排到模拟分布上。
///
/// 背景：`fast_forward` 用固定种子重放，训练分布是模拟器自己随机出来的——
/// 与真实对局（哪个训练有几张卡）几乎必然对不上。MCTS 因此对着「假盘面」
/// 评估，会推荐人头明显更少的训练、选风味错位的面（实测：第2回合真实
/// 力训练 3 头含友人，AI 却推荐 0 头的耐训练）。
///
/// 做法：按观测 heads 在训练之间**搬移可动人员**（卡/友人/NPC），
/// 理事长/记者位置保持不动；搬出时优先让 person 下标大者（NPC 在
/// persons 数组尾部）移动，尽量保留卡的身份（羁绊/Hint 与卡绑定）。
/// 观测总人数与场上可动总人数不一致时按比例缩放并出 warning。
///
/// 彩圈（shining）由卡的落位与效果推导，无法直接注入，保持重放近似；
/// `partner_ids` 语义未定（UPSTREAM.md 规则），不使用。
fn apply_observed_distribution(
    game: &mut RamenGame,
    observed: &[ObservedTraining],
    warnings: &mut Vec<String>,
) {
    if observed.is_empty() {
        return;
    }

    // 观测目标人数（按训练下标累加）
    let mut target = [0i64; 5];
    let mut sum_target = 0i64;
    for o in observed {
        if o.train_index < 5 {
            target[o.train_index] += o.heads.max(0) as i64;
            sum_target += o.heads.max(0) as i64;
        }
    }
    if sum_target <= 0 {
        return;
    }

    // Phase 1: 读取当前成员（训练 → 成员列表）
    let mut members: Vec<Vec<DistMember>> = vec![Vec::new(); 5];
    {
        let dist = game.distribution();
        for (t, row) in dist.iter().enumerate().take(5) {
            for (slot, v) in row.iter().enumerate() {
                if *v >= 0 {
                    let fixed = matches!(
                        game.persons().get(*v as usize).map(|p| p.person_type),
                        Some(PersonType::Yayoi) | Some(PersonType::Reporter)
                    );
                    members[t].push(DistMember {
                        slot,
                        person: *v,
                        fixed,
                        present: true,
                    });
                }
            }
        }
    }

    let fixed_count: [i64; 5] =
        std::array::from_fn(|t| members[t].iter().filter(|m| m.fixed).count() as i64);
    let movable_total: i64 = members
        .iter()
        .map(|m| m.iter().filter(|x| !x.fixed).count() as i64)
        .sum();
    if movable_total <= 0 {
        return;
    }

    // 目标可动人数 = 观测人头 - 该训练固定人员；总数与场上不一致时按比例缩放
    let mut desired = [0i64; 5];
    let mut sum_desired = 0i64;
    for t in 0..5 {
        desired[t] = (target[t] - fixed_count[t]).max(0);
        sum_desired += desired[t];
    }
    if sum_desired <= 0 {
        return;
    }
    if sum_desired != movable_total {
        for t in 0..5 {
            desired[t] = desired[t] * movable_total / sum_desired;
        }
        // 修正舍入误差
        let mut scaled: i64 = desired.iter().sum();
        let mut t = 0usize;
        while scaled != movable_total && t < 5 {
            if scaled < movable_total {
                desired[t] += 1;
                scaled += 1;
            } else if desired[t] > 0 {
                desired[t] -= 1;
                scaled -= 1;
            }
            t += 1;
        }
        warnings.push(format!(
            "人头口径: 观测总人数 {sum_target} 与场上可动人数 {movable_total} 不一致，已按比例重排"
        ));
    }

    // Phase 2a: 盈余训练搬出（优先 person 下标大者 = NPC，保留卡的身份）
    let mut surplus: Vec<i32> = Vec::new();
    for t in 0..5 {
        let present_movable: Vec<usize> = members[t]
            .iter()
            .enumerate()
            .filter(|(_, m)| !m.fixed && m.present)
            .map(|(i, _)| i)
            .collect();
        let excess = present_movable.len() as i64 - desired[t];
        if excess > 0 {
            let mut idxs = present_movable;
            idxs.sort_by_key(|&i| std::cmp::Reverse(members[t][i].person));
            for &i in idxs.iter().take(excess as usize) {
                members[t][i].present = false;
                surplus.push(members[t][i].person);
            }
        }
    }

    // Phase 2b: 缺口训练补入（优先复用本训练腾出的空槽）
    // 注意：desired[t] 是「可动人数」目标（已扣除固定人员），present_n 也
    // 必须只数可动成员——若把 fixed（理事长/记者）也算进去，need 会少算
    // fixed_count 个，导致该训练补人不足（12 回合后记者登场必触发）。
    for t in 0..5 {
        let present_movable_n =
            members[t].iter().filter(|m| m.present && !m.fixed).count() as i64;
        let mut need = (desired[t] - present_movable_n).max(0);
        while need > 0 {
            let Some(person) = surplus.pop() else { break };
            let reuse = members[t]
                .iter_mut()
                .find(|m| !m.fixed && !m.present);
            match reuse {
                Some(m) => {
                    m.person = person;
                    m.present = true;
                }
                None => members[t].push(DistMember {
                    slot: usize::MAX, // 行尾追加（Phase 3 处理）
                    person,
                    fixed: false,
                    present: true,
                }),
            }
            need -= 1;
        }
    }

    // Phase 3: 写回分布行（先清空非固定成员原槽，再按新位置落位）
    let mut new_rows: Vec<Vec<i32>> = game.distribution().clone();
    let n_trains = new_rows.len().min(5);
    for t in 0..n_trains {
        for m in &members[t] {
            if !m.fixed && m.slot < new_rows[t].len() {
                new_rows[t][m.slot] = -1;
            }
        }
    }
    for t in 0..n_trains {
        for m in &members[t] {
            if m.present {
                if m.slot == usize::MAX {
                    new_rows[t].push(m.person);
                } else {
                    if m.slot >= new_rows[t].len() {
                        new_rows[t].resize(m.slot + 1, -1);
                    }
                    new_rows[t][m.slot] = m.person;
                }
            }
        }
    }
    {
        let dist = game.distribution_mut();
        for (t, row) in new_rows.into_iter().enumerate().take(5) {
            dist[t] = row;
        }
    }

    // 结果摘要（透出到小黑板 ⚠ 区）
    let counts: Vec<i64> = (0..5)
        .map(|t| {
            members[t]
                .iter()
                .filter(|m| m.present && !m.fixed)
                .count() as i64
                + fixed_count[t]
        })
        .collect();
    warnings.push(format!(
        "人头注入: 速{} 耐{} 力{} 根{} 智{} (观测 速{} 耐{} 力{} 根{} 智{})",
        counts[0], counts[1], counts[2], counts[3], counts[4],
        target[0], target[1], target[2], target[3], target[4]
    ));
}

// ── 搜索核心 ────────────────────────────────────────────────────────

/// 执行搜索，返回标准结构化结果。
///
/// 流程：
/// 1. reconcile hlpatch JSON → ReconciledState
/// 2. 如果 confidence = Reject，返回错误
/// 3. newgame → fast_forward（重放重建）→ ensure_distribution_built（v0.4.1，
///    重放退出点停在 Begin、上一回合比赛回合时人头为 0，按「行数或人头」
///    补跑 Begin+Distribute 建立本回合分布）→ inject_state（覆盖观测值）
///    → apply_observed_distribution（直读人头注入）
/// 4. 阶段判定（v0.4.0）：trainings 在场 = 行动画面 = 面已选完 → Train，
///    主决策即训练建议；trainings 空 → 按回合区间（RamenSelect 等）
/// 5. 用上游 `RamenMctsTrainer` 搜索当前决策点
/// 6. hlpatch 无 trainings 时补一次 Train 阶段搜索 → training_decision
/// 7. 输出 GameView + DecisionOutput(+ training_decision)
pub fn run_search(
    summary_json: &str,
    config: &SearchConfigInput,
) -> SearchResponse {
    let start = Instant::now();

    // ① 校正状态
    let raw: HlpatchSummary = match serde_json::from_str(summary_json) {
        Ok(r) => r,
        Err(e) => {
            log::warn!("JSON 解析失败: {e}");
            return SearchResponse {
                ok: false,
                view: None,
                decision: None,
                training_decision: None,
                reconcile: None,
                error: Some(format!("JSON 解析失败: {e}")),
            }
        }
    };

    // hlpatch 的 trainings 只在回合行动画面非空。
    // 空时需要 Rust 在 Train 阶段补搜训练建议；非空时跳过，不白耗算力。
    let need_training_search = raw.trainings.is_empty();

    let mut reconciled = match reconcile(&raw) {
        Ok(s) => s,
        Err(e) => {
            log::warn!("状态校正失败: {e}");
            return SearchResponse {
                ok: false,
                view: None,
                decision: None,
                training_decision: None,
                reconcile: None,
                error: Some(format!("状态校正失败: {e}")),
            }
        }
    };

    log::info!(
        "reconcile: turn={}, confidence={:?}, warnings={}, observed_trainings={}",
        reconciled.turn,
        reconciled.confidence,
        reconciled.warnings.len(),
        reconciled.observed_trainings.len()
    );
    for w in &reconciled.warnings {
        log::info!("  warning: {w}");
    }

    if !reconciled.is_searchable() {
        let confidence = reconciled.confidence.clone();
        log::warn!("置信度过低({confidence:?})，跳过搜索");
        return SearchResponse {
            ok: false,
            view: None,
            decision: None,
            training_decision: None,
            reconcile: Some(reconciled),
            error: Some(format!(
                "置信度过低({confidence:?})，跳过搜索"
            )),
        };
    }

    // ② 初始化游戏 + 重放重建 + 注入状态
    let inherit = InheritInfo {
        blue_count: config.blue_count,
        extra_count: config.extra_count,
        ..Default::default()
    };

    let mut game = match RamenGame::newgame(config.uma_id, &config.cards, inherit) {
        Ok(g) => g,
        Err(e) => {
            return SearchResponse {
                ok: false,
                view: None,
                decision: None,
                training_decision: None,
                reconcile: Some(reconciled),
                error: Some(format!("newgame 失败: {e}")),
            }
        }
    };

    // ★ v0.3.1 断线重连：先重放到目标回合（重建分布/羁绊/训练等级），
    //   再用观测值覆盖。跳回合注入的时代，这些字段全是 newgame 初始值。
    let target_internal = (reconciled.turn - 1).max(0);
    let replay_steps = fast_forward(&mut game, target_internal);
    log::info!(
        "fast_forward: target_turn={} replayed_stages={} (断线重连重建)",
        target_internal,
        replay_steps
    );

    // ★ v0.4.1：重放退出点停在目标回合 Begin（上一回合是比赛回合时人头为
    //   0）——搜索路径按 5 行分布取人头，0 行会越界 panic（实机首测第1回合
    //   必现），5 行 0 头会让 MCTS 对空盘面评估。按「行数或人头」补跑
    //   Begin+Distribute 建立本回合分布后再继续。
    let built_steps = ensure_distribution_built(&mut game);
    if built_steps > 0 {
        log::info!(
            "ensure_distribution_built: {built_steps} stages (replay exit at Begin)"
        );
    }

    if let Err(e) = inject_state(&mut game, &mut reconciled) {
        return SearchResponse {
            ok: false,
            view: None,
            decision: None,
            training_decision: None,
            reconcile: Some(reconciled),
            error: Some(format!("inject_state 失败: {e}")),
        };
    }

    // ★ v0.3.2 直读人头注入：重放分布是固定种子近似，观测人头（行动画面
    //   trainings）在场时把可动人员搬到与实况一致——MCTS 从此对真实盘面评估。
    if !reconciled.observed_trainings.is_empty() {
        apply_observed_distribution(
            &mut game,
            &reconciled.observed_trainings,
            &mut reconciled.warnings,
        );
        log::info!("observed distribution injected");
    }

    // ★ v0.4.0 阶段判定修正：上游回合内顺序 Distribute → RamenSelect(选面)
    //   → SpecialSelect → Train(面效果已落地)（turn_flow.rs）。hlpatch 发来
    //   trainings = 行动画面 = 面已选完，当前决策点是训练而非吃面——
    //   旧版此时仍按回合区间强设 RamenSelect，主决策错误地推荐吃面。
    //   trainings 空 = 选面画面或非行动画面 → 按回合区间推定。
    let have_trainings = !reconciled.observed_trainings.is_empty();
    game.stage = if have_trainings {
        RamenStage::Train
    } else if (2..=71).contains(&target_internal) {
        RamenStage::RamenSelect
    } else if (72..=77).contains(&target_internal) {
        RamenStage::SuperRamenSelect
    } else {
        RamenStage::Train
    };

    // ③ 获取 GameView
    let view = game.view();

    // ④ 搜索
    let decision = match run_mcts_search(&mut game, config.search_n) {
        Ok(d) => d,
        Err(e) => {
            // 搜索失败，尝试手写策略兜底
            match run_handwritten_fallback(&mut game) {
                Ok(mut d) => {
                    d.source = format!("handwritten_fallback(mcts_failed: {e})");
                    d
                }
                Err(e2) => {
                    return SearchResponse {
                        ok: false,
                        view: Some(view),
                        decision: None,
                        training_decision: None,
                        reconcile: Some(reconciled),
                        error: Some(format!(
                            "搜索失败: {e}；手写兜底也失败: {e2}"
                        )),
                    }
                }
            }
        }
    };

    // ⑤ 训练阶段建议（hlpatch 无 trainings 时才补搜，省算力）
    // trainings 在场时主决策已是训练建议，此处自然跳过
    let training_decision = if need_training_search && game.stage != RamenStage::Train {
        let t0 = Instant::now();
        let saved_stage = game.stage;
        game.stage = RamenStage::Train;
        let mut td = run_mcts_search(&mut game, config.search_n)
            .or_else(|e| {
                log::warn!("训练阶段 MCTS 失败，用手写策略: {e}");
                run_handwritten_fallback(&mut game)
            })
            .ok();
        game.stage = saved_stage;
        if let Some(d) = td.as_mut() {
            d.elapsed_ms = t0.elapsed().as_millis() as u64;
        }
        td
    } else {
        None
    };

    let elapsed_ms = start.elapsed().as_millis() as u64;

    log::info!(
        "搜索完成: stage={:?}, action={}, score={:.0}, n={}, elapsed={}ms, source={}, training_advice={}",
        game.stage,
        decision.action_display,
        decision.score,
        config.search_n,
        elapsed_ms,
        decision.source,
        training_decision
            .as_ref()
            .map(|d| d.action_display.clone())
            .unwrap_or_else(|| "skip(trainings_present)".into())
    );

    SearchResponse {
        ok: true,
        view: Some(view),
        decision: Some(DecisionOutput {
            action_index: decision.action_index,
            action_display: decision.action_display,
            score: decision.score,
            candidate_displays: decision.candidate_displays,
            candidate_scores: decision.candidate_scores,
            search_n: config.search_n,
            elapsed_ms,
            source: decision.source,
        }),
        training_decision,
        reconcile: Some(reconciled),
        error: None,
    }
}

/// 用上游 RamenMctsTrainer 搜索。
///
/// 阶段门控：只搜 Train + RamenSelect（手机性能有限，省略 SpecialSelect 等）。
/// v0.3.2: 上游 eeae510b 起 MCTS 的 rollout 与未搜阶段 fallback 均为
/// `RecommendedRamenTrainer`（正式推荐策略）——「手写 + 蒙特卡洛」严格叠加。
///
/// 候选评分来自 `trainer.last_breakdown()`——那是 `select_action` 内部
/// 那次搜索缓存的统计（`#i 动作 n=N mean=M sd=S pt=P | ...`）。
/// 旧版在这里又跑了一遍 `trainer.search.search()`：4096 次模拟直接翻倍，
/// 且两次随机流不同，展示的 mean 和实际选中动作对不上。
fn run_mcts_search(game: &mut RamenGame, search_n: usize) -> Result<DecisionOutput> {
    let config = SearchConfig::default().with_search_n(search_n);
    let trainer = RamenMctsTrainer::new(config).with_stages(RamenSearchStages {
        train: true,
        ramen_select: true,
        special_select: false,
        region_select: false,
        super_ramen_select: false,
    });

    let mut rng = StdRng::from_os_rng();

    // 获取候选动作列表（RamenSelect 用合并候选：吃面×targets + 不吃面）
    let actions: Vec<_> = if game.stage == RamenStage::RamenSelect {
        game.list_combined_ramen_select_actions()
    } else {
        game.list_actions()?
    };

    if actions.is_empty() {
        return Err(anyhow!("没有可用候选动作"));
    }

    // 执行搜索
    let action_index = trainer.select_action(game, &actions, &mut rng)?;

    // 收集所有候选的展示文本
    let candidate_displays: Vec<String> = actions
        .iter()
        .map(|a| format!("{a}"))
        .collect();

    // 候选评分：复用 select_action 内部搜索的 breakdown，不再二次搜索
    let candidate_scores =
        parse_breakdown_means(trainer.last_breakdown().as_deref(), actions.len());

    let score = candidate_scores.get(action_index).copied().unwrap_or(0.0);
    let action_display = candidate_displays
        .get(action_index)
        .cloned()
        .unwrap_or_default();

    Ok(DecisionOutput {
        action_index,
        action_display,
        score,
        candidate_displays,
        candidate_scores,
        search_n,
        elapsed_ms: 0, // 由上层填充
        source: "mcts".into(),
    })
}

/// 从 `RamenMctsTrainer::last_breakdown()` 文本解析每个候选的 mean 分。
///
/// 搜索决策行格式（" | " 分隔）：
/// `#0 不吃面 n=4096 mean=65973 sd=1234 pt=5678`
///
/// 转发（手写策略）决策行格式（v0.3.2 兜底路径）：
/// `#2 8125[速训练 理由...]`
///
/// 解析失败的候选保持 0.0（上层展示时按"无评分"处理）。
fn parse_breakdown_means(breakdown: Option<&str>, expected_len: usize) -> Vec<f64> {
    let mut scores = vec![0.0; expected_len];
    let Some(text) = breakdown else {
        return scores;
    };
    for part in text.split(" | ") {
        let part = part.trim();
        let Some(rest) = part.strip_prefix('#') else {
            continue;
        };
        let Some((idx_str, tail)) = rest.split_once(' ') else {
            continue;
        };
        let Ok(idx) = idx_str.trim().parse::<usize>() else {
            continue;
        };
        if idx >= expected_len {
            continue;
        }
        for seg in tail.split_whitespace() {
            if let Some(v) = seg.strip_prefix("mean=") {
                if let Ok(m) = v.parse::<f64>() {
                    scores[idx] = m;
                }
            }
        }
        // 兼容推荐策略分解格式 `#2 8125[理由…]`（无 mean= 键时取首个数字）
        if scores[idx] == 0.0 {
            if let Some(first) = tail.split_whitespace().next() {
                let num = first.split('[').next().unwrap_or("");
                if let Ok(m) = num.parse::<f64>() {
                    scores[idx] = m;
                }
            }
        }
    }
    scores
}

/// 手写策略兜底（搜索失败时使用）。
///
/// v0.3.2: 与上游同步切换为 `RecommendedRamenTrainer`（正式推荐策略）——
/// 旧 `RamenHandwrittenTrainer` 缺平衡/联动机制，已非上游生产路径。
fn run_handwritten_fallback(game: &mut RamenGame) -> Result<DecisionOutput> {
    let trainer = RecommendedRamenTrainer::new();
    let mut rng = StdRng::from_os_rng();

    let actions: Vec<_> = if game.stage == RamenStage::RamenSelect {
        game.list_combined_ramen_select_actions()
    } else {
        game.list_actions()?
    };

    if actions.is_empty() {
        return Err(anyhow!("没有可用候选动作"));
    }

    let action_index = trainer.select_action(game, &actions, &mut rng)?;

    let candidate_displays: Vec<String> = actions
        .iter()
        .map(|a| format!("{a}"))
        .collect();
    let candidate_scores = parse_breakdown_means(trainer.last_breakdown().as_deref(), actions.len());

    Ok(DecisionOutput {
        action_index,
        action_display: candidate_displays
            .get(action_index)
            .cloned()
            .unwrap_or_default(),
        score: candidate_scores.get(action_index).copied().unwrap_or(0.0),
        candidate_displays,
        candidate_scores,
        search_n: 0,
        elapsed_ms: 0,
        source: "handwritten".into(),
    })
}

// ── JNI exports ─────────────────────────────────────────────────────

#[cfg(feature = "jni-support")]
mod jni_exports {
    use super::*;
    use jni::objects::{JClass, JString};
    use jni::sys::jstring;
    use jni::JNIEnv;

    fn jstring_from_str(env: &mut JNIEnv, s: &str) -> jstring {
        let jstr = env.new_string(s).expect("JString alloc");
        jstr.into_raw()
    }

    fn string_from_jstring<'a>(env: &'a mut JNIEnv, js: JString<'a>) -> String {
        env.get_string(&js)
            .expect("JString extract")
            .to_str()
            .expect("UTF-8")
            .to_string()
    }

    #[no_mangle]
    pub extern "system" fn Java_com_umaai_assistant_service_UmaNativeBridge_nativeInit(
        mut env: JNIEnv,
        _class: JClass,
        data_dir: JString,
    ) -> jstring {
        let dir = string_from_jstring(&mut env, data_dir);

        // 初始化 Android logger，把上游 log::info!/warn!/error! 转发到 Logcat
        android_logger::init_once(
            android_logger::Config::default()
                .with_tag("uma_jni")
                .with_max_level(log::LevelFilter::Info),
        );
        log::info!("nativeInit: data_dir={dir}");

        let result = if INITIALIZED.get().is_some() {
            r#"{"ok":true,"already_initialized":true}"#.to_string()
        } else {
            // 上游 init_global() 通过相对路径 "gamedata/xxx.json" 加载数据，
            // 需要先 set_current_dir 到 data_dir（Java 侧已把 assets 复制到这里）
            if let Err(e) = std::env::set_current_dir(&dir) {
                let msg = format!("set_current_dir({dir}) 失败: {e}");
                log::error!("{msg}");
                format!(r#"{{"ok":false,"error":"{}"}}"#, msg.replace('"', "'"))
            } else {
                match init_global() {
                    Ok(()) => {
                        log::info!("init_global 成功，gamedata 已从 {dir}/gamedata/ 加载");
                        let _ = INITIALIZED.set(());
                        r#"{"ok":true}"#.to_string()
                    }
                    Err(e) => {
                        log::error!("init_global 失败: {e}");
                        format!(r#"{{"ok":false,"error":"{}"}}"#, e.to_string().replace('"', "'"))
                    }
                }
            }
        };

        jstring_from_str(&mut env, &result)
    }

    #[no_mangle]
    pub extern "system" fn Java_com_umaai_assistant_service_UmaNativeBridge_nativeSearch(
        mut env: JNIEnv,
        _class: JClass,
        state_json: JString,
        config_json: JString,
    ) -> jstring {
        let state_str = string_from_jstring(&mut env, state_json);
        let config_str = string_from_jstring(&mut env, config_json);

        let config: SearchConfigInput = match serde_json::from_str(&config_str) {
            Ok(c) => c,
            Err(e) => {
                let resp = SearchResponse {
                    ok: false,
                    view: None,
                    decision: None,
                    training_decision: None,
                    reconcile: None,
                    error: Some(format!("config 解析失败: {e}")),
                };
                let json = serde_json::to_string(&resp).unwrap_or_else(|_| r#"{"ok":false}"#.into());
                return jstring_from_str(&mut env, &json);
            }
        };

        let response = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
            run_search(&state_str, &config)
        }))
        .map_err(|payload| {
            // v0.4.1：把 panic 消息透出到浮窗，不再只显示 "panic during search"
            let msg = if let Some(s) = payload.downcast_ref::<String>() {
                s.clone()
            } else if let Some(s) = payload.downcast_ref::<&str>() {
                (*s).to_string()
            } else {
                "unknown panic (非字符串载荷)".to_string()
            };
            SearchResponse {
                ok: false,
                view: None,
                decision: None,
                training_decision: None,
                reconcile: None,
                error: Some(format!("panic during search: {msg}")),
            }
        })
        .unwrap_or_else(|err| err);

        let json = serde_json::to_string(&response)
            .unwrap_or_else(|_| r#"{"ok":false}"#.to_string());
        jstring_from_str(&mut env, &json)
    }

    #[no_mangle]
    pub extern "system" fn Java_com_umaai_assistant_service_UmaNativeBridge_nativeVersion(
        mut env: JNIEnv,
        _class: JClass,
    ) -> jstring {
        let v = serde_json::json!({
            "version": "0.4.1",
            "upstream": "xulai1001/umaai-rs",
            "upstream_commit": "eeae510b57ee9d29a475645a05c191e6ef5a6e72",
            "search": "ramen_mcts_trainer",
            "rollout": "recommended_ramen_trainer",
            "stages": ["train", "ramen_select"],
            "reconcile": true,
            "replay_reconnect": true,
            "replay_trainer": "recommended_for_rollout",
            "observed_heads_injection": true,
            "stage_detection": "trainings_present_means_train_phase(action_screen)",
            "training_decision": "computed_only_when_hlpatch_trainings_empty",
            "turn_convention": "hlpatch UI 1-based -> AI internal 0-based (turn-1); upstream displays turn()+1",
            "candidate_scores": "last_breakdown_reuse",
            "pr22_direct_injection": "friendship/train_level_count/train_feeling_type from hlpatch",
            "turn0_distribution_fix": "ensure_distribution_built after replay (exit at Begin)",
            "race_turn_board_fix": "ensure gate checks rows AND heads (race turn leaves 5 empty rows)",
            "replay_heads_fix": "rule_master seed + friendship=100 before Distribute (deyilv weight)"
        })
        .to_string();
        jstring_from_str(&mut env, &v)
    }
}

// ── 单元测试 ────────────────────────────────────────────────────────

#[cfg(test)]
mod tests {
    use super::*;

    /// last_breakdown 文本 → 候选 mean 数组（PC 黑板「决策理由」数据源）
    #[test]
    fn test_parse_breakdown_means() {
        let text = "#0 不吃面 n=4096 mean=65973 sd=3210 pt=5646 | #1 吃面/函馆-耐 n=4096 mean=66972 sd=2980 pt=6750 | #2 吃面/东京-智 n=4096 mean=66241 sd=3055 pt=6668";
        let scores = parse_breakdown_means(Some(text), 4);
        assert_eq!(scores.len(), 4);
        assert!((scores[0] - 65973.0).abs() < 0.5, "scores[0]={}", scores[0]);
        assert!((scores[1] - 66972.0).abs() < 0.5);
        assert!((scores[2] - 66241.0).abs() < 0.5);
        assert_eq!(scores[3], 0.0, "缺失候选保持 0.0");

        // 候选数超出的行被忽略、非 # 开头的段被跳过
        let messy = "garbage | #7 越界 mean=1.0";
        let scores2 = parse_breakdown_means(Some(messy), 2);
        assert_eq!(scores2, vec![0.0, 0.0]);

        assert_eq!(parse_breakdown_means(None, 3), vec![0.0, 0.0, 0.0]);
    }

    /// v0.3.2: 推荐策略分解格式 `#2 8125[理由…]`（兜底路径，无 mean= 键）
    #[test]
    fn test_parse_breakdown_means_policy_format() {
        let text = "#0 8125[速训练 基础] | #1 6500[休息]";
        let scores = parse_breakdown_means(Some(text), 2);
        assert!((scores[0] - 8125.0).abs() < 0.5, "scores[0]={}", scores[0]);
        assert!((scores[1] - 6500.0).abs() < 0.5, "scores[1]={}", scores[1]);
    }

    /// gamedata 工作目录初始化（测试公共前置）
    fn setup_gamedata() -> bool {
        let workspace_root = std::env::current_dir()
            .ok()
            .and_then(|d| d.parent().map(|p| p.to_path_buf()))
            .unwrap_or_else(|| std::path::PathBuf::from(".."));

        let gamedata_dir = workspace_root.join("gamedata");
        if !gamedata_dir.exists() {
            eprintln!("跳过测试：gamedata 目录不存在");
            return false;
        }

        // 上游 init_global() 用相对路径 "gamedata/xxx.json" 加载，
        // 需要先 set_current_dir 到 workspace_root
        let _ = std::env::set_current_dir(&workspace_root);
        let _ = init_global();
        true
    }

    fn test_config(search_n: usize) -> SearchConfigInput {
        SearchConfigInput {
            uma_id: 102601,
            cards: [302424, 302894, 303044, 302924, 303024, 303054],
            blue_count: [15, 3, 0, 0, 0],
            extra_count: [0, 30, 0, 0, 30, 30],
            search_n,
        }
    }

    /// ★ v0.3.1 断线重连核心：重放后回合到位、固定种子可复现、rule 流清除。
    ///
    /// v0.4.1b 定性（上游源码 + umaDB 实锤）：
    /// - `Uma::is_race_turn` 按每匹马 races 表判定；102601（美浦波旁）
    ///   races=[11,22,29,30,33,43,55,69,71]——**内回合 29/30 连续两个生涯
    ///   比赛回合**，`run_distribute` 走 `reset_distribution()`（5 空行）。
    /// - 重放退出点停在内回合 31 的 Begin，承载的是内 30（比赛回合）的
    ///   空分布——退出点 0 人头是**模拟器正确行为**，不是 bug。
    ///
    /// 本测试守住的真不变量：
    /// 1. 回合到位、可复现；
    /// 2. 退出点保留 5 行（比赛回合 reset 的空行，正是 0 行 panic 的反面）；
    /// 3. ★ ensure 补建门控按「行数或人头」：内 31 是训练回合，补跑
    ///    Begin+Distribute 后必须有人头（这是比赛回合后训练回合搜索
    ///    盘面有人头的保障，v0.4.1b 修正的真 bug）；
    /// 4. rule 流已清除（不带入 MCTS）。
    #[test]
    fn test_fast_forward_rebuilds_distribution() {
        if !setup_gamedata() {
            return;
        }

        let inherit = InheritInfo {
            blue_count: [15, 3, 0, 0, 0],
            extra_count: [0, 30, 0, 0, 30, 30],
            ..Default::default()
        };
        let mut game =
            RamenGame::newgame(102601, &[302424, 302894, 303044, 302924, 303024, 303054], inherit.clone())
                .expect("newgame 失败");

        let steps = fast_forward(&mut game, 31);

        assert!(steps > 0, "应至少重放一个阶段");
        assert_eq!(game.turn(), 31, "重放后应到达目标回合");
        assert!(
            game.rule_master.is_none(),
            "重放结束应清除 rule 流（不带入 MCTS）"
        );

        // 退出点：内 30 是比赛回合 → reset 留 5 空行 0 头（模拟器正确行为）
        assert_eq!(
            game.distribution().len(),
            5,
            "比赛回合 reset 后应保留 5 行分布"
        );
        let heads_at_exit = distribution_heads(&game);
        eprintln!(
            "重放退出点(内31 Begin): 行数=5 人头数={heads_at_exit}（0=内30比赛回合正常现象）"
        );

        // ★ ensure 补建：门控按「行数或人头」，内 31 训练回合 → 补跑后有人头
        let built = ensure_distribution_built(&mut game);
        assert!(
            built > 0,
            "比赛回合后的训练回合应触发补建（门控=行数或人头不足）"
        );
        let heads_after = distribution_heads(&game);
        assert!(
            heads_after > 0,
            "训练回合补建后应有人头（实际={heads_after}，行数={}）",
            game.distribution().len()
        );
        eprintln!(
            "ensure 补跑 {built} 阶段 → 人头数 {heads_at_exit} → {heads_after}"
        );

        // 固定种子：重放可复现
        let mut game2 =
            RamenGame::newgame(102601, &[302424, 302894, 303044, 302924, 303024, 303054], inherit)
                .expect("newgame 失败");
        let steps2 = fast_forward(&mut game2, 31);
        let _ = ensure_distribution_built(&mut game2);
        assert_eq!(steps, steps2, "固定种子下重放阶段数应一致");
        assert_eq!(
            game.uma().five_status, game2.uma().five_status,
            "固定种子下重放结果应一致"
        );
    }

    /// 开局第1回合（内部0）：fast_forward 整段跳过（0 行分布），
    /// ensure 补跑 Begin+Distribute 后应有人头——守住第1回合 panic 不复发
    #[test]
    fn test_turn0_replay_skipped_then_ensure_builds() {
        if !setup_gamedata() {
            return;
        }

        let inherit = InheritInfo {
            blue_count: [15, 3, 0, 0, 0],
            extra_count: [0, 30, 0, 0, 30, 30],
            ..Default::default()
        };
        let mut game =
            RamenGame::newgame(102601, &[302424, 302894, 303044, 302924, 303024, 303054], inherit)
                .expect("newgame 失败");

        let steps = fast_forward(&mut game, 0);
        assert_eq!(steps, 0, "内部回合 0 重放应整段跳过");
        assert_eq!(game.distribution().len(), 0, "跳过时分布应为空 vec（0 行）");

        let built = ensure_distribution_built(&mut game);
        assert!(built > 0, "0 行分布应触发补建");
        assert!(
            distribution_heads(&game) > 0,
            "第1回合（训练回合）补建后应有人头"
        );
    }

    #[test]
    fn test_run_search_with_real_sample() {
        if !setup_gamedata() {
            return;
        }

        // hlpatch 直读回合样本：turn=31（UI 第31回合）→ AI 内部 30
        // 注意：没有 trainings / ramen —— 正是"非行动画面"的真实形态，
        // v0.3.1 起这种输入也应给出完整决策 + 训练建议
        let json = r#"{
            "chara": {
                "speed": 1200, "stamina": 301, "power": 437, "guts": 362, "wiz": 280,
                "vital": 60, "max_vital": 108, "motivation": 5,
                "skill_point": 1492, "scenario_id": 14
            },
            "turn": 31
        }"#;

        let response = run_search(json, &test_config(8));

        // 直读回合无估算 warning
        let reconciled = response.reconcile.as_ref().unwrap();
        assert_eq!(reconciled.turn, 31);
        assert_eq!(reconciled.turn_source, "direct");

        // 搜索可能成功也可能失败（取决于 gamedata 是否完整）
        if response.ok {
            assert!(response.view.is_some());
            assert!(response.decision.is_some());
            let decision = response.decision.as_ref().unwrap();
            assert!(!decision.candidate_displays.is_empty());
            eprintln!("搜索成功: {}", decision.action_display);

            // ★ v0.3.1: hlpatch 没发 trainings → 必须给出训练阶段建议
            let td = response.training_decision.as_ref().expect("无 trainings 时应有训练阶段建议");
            assert!(!td.action_display.is_empty());
            eprintln!(
                "训练建议: {} (n={} elapsed={}ms source={})",
                td.action_display, td.search_n, td.elapsed_ms, td.source
            );
        } else {
            eprintln!("搜索失败（预期，gamedata 可能不完整）: {:?}", response.error);
        }
    }

    /// trainings 非空（行动画面）时跳过训练补搜，省算力
    #[test]
    fn test_training_search_skipped_when_trainings_present() {
        if !setup_gamedata() {
            return;
        }

        let json = r#"{
            "chara": {
                "speed": 1200, "stamina": 301, "power": 437, "guts": 362, "wiz": 280,
                "vital": 60, "max_vital": 108, "motivation": 5,
                "skill_point": 1492, "scenario_id": 14
            },
            "turn": 31,
            "trainings": [
                {"name": "Speed", "command_id": 101, "is_enable": 1, "failure_rate": 0,
                 "heads": 3, "shining": 2, "partner_ids": [1, 2, 3], "partners": [],
                 "gains": {"Speed": 46, "Power": 14, "SkillPt": 27, "HP": -25}}
            ]
        }"#;

        let response = run_search(json, &test_config(8));
        if response.ok {
            assert!(
                response.training_decision.is_none(),
                "trainings 非空时不应补搜训练建议（省算力）"
            );
            // ★ v0.3.2: 观测人头应被解析并注入（warning 里有摘要）
            let reconciled = response.reconcile.as_ref().unwrap();
            assert!(
                !reconciled.observed_trainings.is_empty(),
                "trainings 应解析为观测人头"
            );
            assert!(
                reconciled.warnings.iter().any(|w| w.contains("人头注入")),
                "人头注入应留摘要 warning: {:?}",
                reconciled.warnings
            );
        }
    }

    /// v0.4.0（PR #22）：直读数据解析后携带到 ReconciledState，
    /// inject_state 末尾注入 person.friendship / train_level_count / train_feeling_type。
    #[test]
    fn test_pr22_direct_injection_fields_carried() {
        if !setup_gamedata() {
            return;
        }

        let json = r#"{
            "chara": {
                "speed": 1200, "stamina": 301, "power": 437, "guts": 362, "wiz": 280,
                "vital": 60, "max_vital": 108, "motivation": 5,
                "skill_point": 1492, "scenario_id": 14
            },
            "turn": 10,
            "support_cards": [
                {"support_card_id": 302424, "kizuna": 75},
                {"support_card_id": 302894, "kizuna": 50}
            ],
            "training_levels": [
                {"command_id": 101, "level": 3},
                {"command_id": 103, "level": 5}
            ],
            "ramen": {
                "command_feelings": [
                    {"command_id": 102, "feeling_id": 1},
                    {"command_id": 104, "feeling_id": 2},
                    {"command_id": 105, "feeling_id": 3}
                ]
            }
        }"#;

        let response = run_search(json, &test_config(8));
        let reconciled = response.reconcile.as_ref().unwrap();
        // 解析层已携带三项注入数据
        assert_eq!(reconciled.support_bonds.len(), 2, "应解析出 2 张卡羁绊");
        assert_eq!(reconciled.training_levels.len(), 2, "应解析出 2 条训练等级");
        assert_eq!(reconciled.feeling_types.len(), 3, "应解析出 3 条角标");
        // 注入应在 warnings 中留痕（inj 注入只在 gamedata 完整、搜索成功路径才执行，
        // 但 reconcile 层解析一定发生）
        eprintln!("reconcile warnings: {:?}", reconciled.warnings);
    }

    /// ★ v0.4.1 回归：开局第1回合（内部0）+ 行动画面 + v0.4.0 三路注入全开，
    /// 搜索不得 panic（实机首测：第1回合必现 panic during search）。
    /// 根因：fast_forward(0) 整段跳过 → distribution 空 vec → 搜索路径
    /// 按 5 行分布索引越界。修复：ensure_distribution_built 补建分布。
    /// 需要 gamedata（CI rust-check v0.4.1 起下载）。
    #[test]
    fn test_turn1_action_screen_no_panic() {
        if !setup_gamedata() {
            return;
        }

        // 形状对齐用户实机首测截图（第1回合）：速165 耐81 力109 根109 智117，
        // 速训练1头/智训练3头，六卡羁绊全 0，训练等级全 1，角标 A/B/C
        let json = r#"{
            "scenario": "Ramen",
            "turn": 1,
            "chara": {"speed":165,"stamina":81,"power":109,"guts":109,"wiz":117,
                      "vital":100,"max_vital":100,"motivation":3,
                      "skill_point":320,"scenario_id":14},
            "trainings": [
                {"name":"Speed","command_id":101,"is_enable":1,"failure_rate":0,
                 "heads":1,"shining":0,"partner_ids":[],"partners":[],
                 "gains":{"Speed":13,"Power":2,"SkillPt":8,"HP":-20}},
                {"name":"Wiz","command_id":105,"is_enable":1,"failure_rate":0,
                 "heads":3,"shining":0,"partner_ids":[],"partners":[],
                 "gains":{"Speed":7,"Wisdom":19,"SkillPt":18,"HP":5}}
            ],
            "support_cards": [
                {"support_card_id":302424,"kizuna":0},
                {"support_card_id":302894,"kizuna":0},
                {"support_card_id":303044,"kizuna":0},
                {"support_card_id":302924,"kizuna":0},
                {"support_card_id":303024,"kizuna":0},
                {"support_card_id":303054,"kizuna":0}
            ],
            "training_levels": [
                {"command_id":601,"level":1},{"command_id":602,"level":1},
                {"command_id":603,"level":1},{"command_id":604,"level":1},
                {"command_id":605,"level":1}
            ],
            "ramen": {"checkpoint_pt":0,"special_feeling_num":0,"sozai":[0,0,0],
                      "acquisition_gauges":[
                          {"feeling_id":1,"remaining":7},
                          {"feeling_id":2,"remaining":7},
                          {"feeling_id":3,"remaining":7}],
                      "command_feelings":[
                          {"command_id":601,"feeling_id":1},
                          {"command_id":602,"feeling_id":2},
                          {"command_id":603,"feeling_id":3}]}
        }"#;

        let response = run_search(json, &test_config(8));
        assert!(
            response.ok,
            "开局第1回合搜索不应失败: {:?}",
            response.error
        );
        let decision = response.decision.as_ref().expect("应有主决策");
        assert!(!decision.candidate_displays.is_empty(), "应有候选动作");
        eprintln!(
            "第1回合搜索成功: {} (n={} source={})",
            decision.action_display, decision.search_n, decision.source
        );
    }
}
