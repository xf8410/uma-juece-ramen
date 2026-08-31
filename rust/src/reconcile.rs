//! 状态校正层（reconcile_state）
//!
//! hlpatch 推送的 /summary JSON 可能存在以下问题：
//! - 缺少 turn 字段（旧版 hlpatch 只有 month + half），需要推导
//! - acquisition_gauges 是对象数组 [{feeling_id, remaining}]，不是整数数组
//! - sozai 总和可能超过 FEELING_LIMIT(10)
//! - 五维值可能超过剧本上限
//! - 体力可能超过 max_vital
//!
//! 本模块负责把"脏数据"清洗为模拟器可接受的状态，并记录所有校正动作。
//! 核心原则：以游戏规则为准，数据脏了按规则钳制，而不是按数据走。
//!
//! # 回合口径（v0.4.2 重大修正：黑板协议层已减 1）
//!
//! PC 黑板协议（EtherealAO/UmamusumeResponseAnalyzer → xulai1001/UmaAiConnector
//! `GameStatusSend.cs`，手机版 hlpatch 照抄）在发送前做了
//! `turn = chara_info.turn - 1`——**/summary 的 turn 已是 AI 0-based（0..77）**。
//! - 本层对直读 turn **原样透传**（0 = 第1回合，合法）；旧版「1-based」注释作废
//! - 旧版 hlpatch 无 turn 时按 month/half 推导出的值是 UI 1-based，本层 **-1**
//!   转 AI 内部口径
//! - lib.rs `inject_state` 不再做任何减法（此前双重换算，第2回合起全错 1 回合）
//!
//! # 支援卡 id 编码（v0.4.2，黑板协议 GameStatusSend 实锤）
//!
//! 黑板/上游的人头 cardID = `support_card_id * 10 + limit_break_count`
//! （源码注释："突破数+10*卡原来的id，例如神团是30137，满破神团就是301374"）。
//! SO 的 support_card_id 是**短 id**（如 30312），据此编码成 303124 后才能与
//! 上游 `person.card_id` 匹配。**注意：该编码只适用于支援卡，与马娘 id
//! （chara_info.card_id）是两套独立 id 空间，马娘 id 不做变换。**
//!
//! # evaluation[] 映射（v0.4.2，黑板协议实锤）
//!
//! `evaluation_info_array` 的 target_id：102=理事长（persons[6]）、103=记者
//! （persons[7]）、111=凉花（persons[8]）、1-6=支援卡位——理事长/记者的羁绊条
//! 就在这张表里（用户实锤），按 person_type 注入。
//!
//! # command_id → 训练下标（v0.4.2 实测映射修正）
//!
//! SO 实况（用户 URA 回合快照）实证训练 command_id **不连续**：
//! `101=速 105=耐 102=力 103=根 106=智`。旧版按 101-105 连续映射，
//! 把耐当智、把根当力。601-605 是**合宿命令**（level 恒 5），与普通训练
//! 不同槽，不映射（上游合宿期 train_level=5 硬编码，不读 6xx）。
//!
//! # 直读训练观测（v0.3.2）
//!
//! 行动画面的 `trainings` 数组携带每训练的真实人头/彩圈（此前只用于
//! 「是否补搜训练建议」的判空）。重放分布是固定种子近似，早回合几乎必然
//! 与真实人头对不上——MCTS 对着假盘面评估，会推荐人头明显更少的训练、
//! 选风味错位的面。本层把 trainings 解析为 [`ObservedTraining`]，
//! 由 `inject_state` 之后的人头注入（lib.rs `apply_observed_distribution`）
//! 搬移可动人员，使模拟分布与实况一致。
//!
//! # v0.4.0 直读注入（PR #22）
//!
//! 在 v0.3.2 人头注入之外，本层进一步解析三类可直接观测、且重放近似误差大的
//! 状态，由 `inject_state` 之后消费（详见 lib.rs）：
//! - 羁绊：`support_cards[].kizuna` 与 `trainings[].partners[].current_bond`
//!   （后者是训练画面实时累计真值，优先），按编码后 card_id 匹配人头写入
//!   `person.friendship`，替掉 fast_forward 的重放近似。
//! - 训练等级：`training_levels[].{command_id, level}` → `base.train_level_count[idx]`
//!   （公式 train_level = count/4 + 1，故 count = (level-1)*4）。
//! - 角标：`ramen.command_feelings[].{command_id, feeling_id}` →
//!   `ramen.train_feeling_type[5]`（feeling_id 1/2/3 → A/B/C）。
//! 全部按"字段缺失则跳过、保留重放近似"处理，保证旧版 hlpatch 行为不变。

use std::collections::HashMap;

use serde::{Deserialize, Serialize};

use umasim::game::ramen::rules::{FEELING_LIMIT, GAUGE_LIMIT};

// ── 输入结构（hlpatch /summary 的子集）──────────────────────────────

/// hlpatch /summary 的完整反序列化结构。
///
/// 字段全部 `#[serde(default)]`，缺失字段用默认值，由 reconcile 层判断是否可用。
#[derive(Debug, Clone, Deserialize)]
pub struct HlpatchSummary {
    #[serde(default)]
    pub version: String,
    /// hlpatch 直读回合。**v0.4.2：已是 AI 0-based（黑板协议发送前减 1）**，
    /// 0 = 第1回合（合法）；Option 用于区分「字段缺失」和「字段=0」。
    #[serde(default)]
    pub turn: Option<i32>,
    #[serde(default)]
    pub year: i32,
    #[serde(default)]
    pub month: i32,
    #[serde(default)]
    pub half: i32,
    #[serde(default)]
    pub scenario: String,
    #[serde(default)]
    pub chara: Option<HlpatchChara>,
    /// 旧格式兼容：stats 可能直接在顶层
    #[serde(default)]
    pub stats: Option<HlpatchStats>,
    #[serde(default)]
    pub ramen: Option<HlpatchRamen>,
    #[serde(default)]
    pub trainings: Vec<serde_json::Value>,
    /// 支援卡列表（v0.4.0 羁绊注入用）
    #[serde(default)]
    pub support_cards: Vec<HlpatchSupportCard>,
    /// 训练等级列表（v0.4.0 等级注入用）
    #[serde(default)]
    pub training_levels: Vec<HlpatchTrainingLevel>,
    /// 羁绊/evaluation 表（v0.4.2）：102=理事长、103=记者、111=凉花、1-6=卡位
    #[serde(default)]
    pub evaluation: Vec<HlpatchEvaluation>,
}

#[derive(Debug, Clone, Deserialize)]
pub struct HlpatchChara {
    #[serde(default)]
    pub speed: i32,
    #[serde(default)]
    pub stamina: i32,
    #[serde(default)]
    pub power: i32,
    #[serde(default)]
    pub guts: i32,
    #[serde(default)]
    pub wiz: i32,
    #[serde(default)]
    pub vital: i32,
    #[serde(default)]
    pub max_vital: i32,
    /// 干劲：整数 1-5 或字符串 "Best"/"Good"/...
    #[serde(default, deserialize_with = "deserialize_motivation")]
    pub motivation: i32,
    #[serde(default)]
    pub skill_point: i32,
    #[serde(default)]
    pub scenario_id: i32,
    #[serde(default)]
    pub fan_count: i32,
}

/// 旧格式 stats（顶层）
#[derive(Debug, Clone, Deserialize)]
pub struct HlpatchStats {
    #[serde(default)]
    pub speed: i32,
    #[serde(default)]
    pub stamina: i32,
    #[serde(default)]
    pub power: i32,
    #[serde(default)]
    pub guts: i32,
    #[serde(default)]
    pub wiz: i32,
    #[serde(default)]
    pub vital: i32,
    #[serde(default)]
    pub max_vital: i32,
    #[serde(default, deserialize_with = "deserialize_motivation")]
    pub motivation: i32,
    #[serde(default)]
    pub skill_point: i32,
}

#[derive(Debug, Clone, Deserialize)]
pub struct HlpatchRamen {
    #[serde(default)]
    pub checkpoint_pt: i32,
    #[serde(default)]
    pub special_feeling_num: i32,
    /// [麺, スープ, トッピング] 数量
    #[serde(default)]
    pub sozai: Vec<i32>,
    /// 对象数组 [{feeling_id, remaining}] 或整数数组
    #[serde(default)]
    pub acquisition_gauges: serde_json::Value,
    /// 地区 ID（1-based）
    #[serde(default)]
    pub selected_region_ids: Vec<i32>,
    /// 角标 A/B/C（feeling_id 1/2/3），每训练一个（v0.4.0 角标注入用）
    #[serde(default)]
    pub command_feelings: Vec<HlpatchCommandFeeling>,
}

/// hlpatch 支援卡（v0.4.0 羁绊注入用）。
///
/// `support_card_id` 是**短 id**（SO 口径，如 30312）；
/// v0.4.2 起按黑板协议编码 `short*10 + limit_break_count` 后再参与匹配。
#[derive(Debug, Clone, Default, Deserialize)]
pub struct HlpatchSupportCard {
    #[serde(default)]
    pub position: i32,
    #[serde(default)]
    pub support_card_id: Option<u32>,
    #[serde(default)]
    pub limit_break_count: i32,
    /// 该卡当前羁绊（累计真值）
    #[serde(default)]
    pub kizuna: i32,
    #[serde(default)]
    pub rental_type: i32,
}

/// hlpatch 训练等级（v0.4.0 等级注入用）。
#[derive(Debug, Clone, Default, Deserialize)]
pub struct HlpatchTrainingLevel {
    #[serde(default)]
    pub command_id: i64,
    /// 训练等级 1..=5
    #[serde(default)]
    pub level: i32,
}

/// hlpatch 角标（v0.4.0 角标注入用）。
#[derive(Debug, Clone, Default, Deserialize)]
pub struct HlpatchCommandFeeling {
    #[serde(default)]
    pub command_id: i64,
    /// 1=A / 2=B / 3=C
    #[serde(default)]
    pub feeling_id: i32,
}

/// hlpatch evaluation 表条目（v0.4.2 理事长/记者羁绊注入用）。
///
/// 黑板协议映射：target_id 102=理事长、103=记者、111=凉花、1-6=支援卡位；
/// 其余（1022/1058/1060/1076/1077/1120…）为剧本伙伴 NPC。
#[derive(Debug, Clone, Default, Deserialize)]
pub struct HlpatchEvaluation {
    #[serde(default)]
    pub target_id: i64,
    #[serde(default)]
    pub evaluation: i32,
    #[serde(default)]
    pub current_bond: i32,
}

// ── 校正后的状态 ────────────────────────────────────────────────────

/// 校正结果：包含清洗后的状态 + 校正日志 + 置信度
#[derive(Debug, Clone, Serialize)]
pub struct ReconciledState {
    /// AI 内部回合（0-based；直读透传，推导值已 -1）
    pub turn: i32,
    /// 回合来源
    pub turn_source: String,
    /// 校正后的五维
    pub stats: ReconciledStats,
    /// 校正后的拉面状态
    pub ramen: ReconciledRamen,
    /// 所有校正动作的描述
    pub warnings: Vec<String>,
    /// 整体置信度
    pub confidence: Confidence,
    /// 直读训练观测（v0.3.2 人头注入用；非行动画面为空）
    pub observed_trainings: Vec<ObservedTraining>,
    /// 支援卡羁绊注入：[(编码后 card_id, bond)]，已按 current_bond 优先合并
    /// 并按黑板协议编码 short*10+突破（v0.4.2）
    pub support_bonds: Vec<(u32, i32)>,
    /// 训练等级注入：[(train_idx, level)]，level∈1..=5（v0.4.0）
    pub training_levels: Vec<(usize, i32)>,
    /// 角标注入：[(train_idx, feeling_id)]，feeling_id∈1..=3（v0.4.0）
    pub feeling_types: Vec<(usize, i32)>,
    /// 理事长/记者羁绊注入：[(target_id, bond)]，102=理事长、103=记者（v0.4.2）
    pub evaluation_bonds: Vec<(i64, i32)>,
}

/// hlpatch 行动画面 `trainings[]` 的观测条目（v0.3.2 人头注入用）。
#[derive(Debug, Clone, Serialize)]
pub struct ObservedTraining {
    /// 训练下标 0..5（速/耐/力/根/智）
    pub train_index: usize,
    /// 该训练人头数（hlpatch `heads`，按训练界面人头数口径）
    pub heads: i32,
    /// 彩圈数（仅供参考；注入不使用——彩圈由卡的落位与效果推导）
    pub shining: i32,
}

#[derive(Debug, Clone, Serialize)]
pub struct ReconciledStats {
    pub speed: i32,
    pub stamina: i32,
    pub power: i32,
    pub guts: i32,
    pub wiz: i32,
    pub vital: i32,
    pub max_vital: i32,
    pub motivation: i32,
    pub skill_point: i32,
}

#[derive(Debug, Clone, Serialize)]
pub struct ReconciledRamen {
    pub scenario_pt: i32,
    pub feeling_stock: [i32; 3],
    /// 诀窍槽（近似值，0..=GAUGE_LIMIT）
    pub feeling_slot: [i32; 3],
    pub feeling_slot_source: String,
    pub special_feeling: i32,
    /// 地区下标（0-based）
    pub selected_region_indices: [usize; 3],
    pub has_region_data: bool,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize)]
pub enum Confidence {
    /// 所有字段都是直接观测值，回合可确定
    High,
    /// 部分字段经过推导/近似
    Medium,
    /// 回合无法确定或多个关键字段异常
    Low,
    /// 数据完全不可用
    Reject,
}

impl ReconciledState {
    /// 是否可以继续注入模拟器并搜索
    pub fn is_searchable(&self) -> bool {
        matches!(self.confidence, Confidence::High | Confidence::Medium)
    }
}

// ── 核心校正逻辑 ────────────────────────────────────────────────────

/// 把 hlpatch 原始 JSON 校正为模拟器可用的状态。
///
/// # 校正步骤
///
/// 1. 场景校验：必须是 Ramen
/// 2. 回合推导：直读透传（已 0-based），month+half 推导后 -1
/// 3. 五维校验：clamp 到 [0, 剧本上限]
/// 4. 体力校验：clamp 到 [0, max_vital]
/// 5. sozai 校验：每项 [0, FEELING_LIMIT]，总和 ≤ FEELING_LIMIT
/// 6. acquisition_gauges 解析：对象数组 → [i32; 3]
/// 7. 地区 ID 校验：1-based → 0-based
/// 8. 直读训练观测解析（v0.3.2）
/// 9. v0.4.0 注入数据解析：羁绊 / 训练等级 / 角标
/// 10. v0.4.2 evaluation 解析：理事长/记者羁绊
/// 11. 置信度评估
pub fn reconcile(raw: &HlpatchSummary) -> Result<ReconciledState, String> {
    let mut warnings = Vec::new();

    // ① 场景校验
    let scenario = raw.scenario.as_str();
    if !scenario.is_empty() && scenario != "Ramen" {
        return Err(format!("scenario 不是 Ramen: {scenario}"));
    }

    // ② 提取 stats（chara 优先，兼容旧格式 stats）
    let (stats_raw, scenario_id) = if let Some(chara) = &raw.chara {
        (
            HlpatchStats {
                speed: chara.speed,
                stamina: chara.stamina,
                power: chara.power,
                guts: chara.guts,
                wiz: chara.wiz,
                vital: chara.vital,
                max_vital: chara.max_vital,
                motivation: chara.motivation,
                skill_point: chara.skill_point,
            },
            chara.scenario_id,
        )
    } else if let Some(stats) = &raw.stats {
        (stats.clone(), 14)
    } else {
        return Err("缺少 chara 和 stats 字段".into());
    };

    // scenario_id 校验
    if scenario_id != 0 && scenario_id != 14 {
        return Err(format!("scenario_id 不是 14(Ramen): {scenario_id}"));
    }

    // ③ 回合推导
    let (turn, turn_source, turn_confidence) = derive_turn(raw, &stats_raw, &mut warnings)?;

    // ④ 五维校验
    let stats = validate_stats(&stats_raw, &mut warnings);

    // ⑤ 拉面状态校正
    let ramen = if let Some(ramen_raw) = &raw.ramen {
        validate_ramen(ramen_raw, &mut warnings)
    } else {
        warnings.push("缺少 ramen 对象，拉面状态置零".into());
        ReconciledRamen {
            scenario_pt: 0,
            feeling_stock: [0, 0, 0],
            feeling_slot: [0, 0, 0],
            feeling_slot_source: "missing".into(),
            special_feeling: 0,
            selected_region_indices: [0, 0, 0],
            has_region_data: false,
        }
    };

    // ⑤b 直读训练观测（v0.3.2）：解析失败静默跳过（人头注入不生效而已，
    //     不值得为它压低置信度/刷警告）
    let observed_trainings = parse_observed_trainings(&raw.trainings);

    // ⑤c v0.4.0 注入数据解析：羁绊 / 训练等级 / 角标
    let support_bonds = parse_support_bonds(&raw.support_cards, &raw.trainings);
    let training_levels = parse_training_levels(&raw.training_levels);
    let feeling_types = parse_command_feelings(
        raw.ramen
            .as_ref()
            .map(|r| r.command_feelings.as_slice())
            .unwrap_or(&[]),
    );

    // ⑤d v0.4.2 evaluation 解析：理事长(102)/记者(103) 羁绊
    let evaluation_bonds = parse_evaluation_bonds(&raw.evaluation);

    // ⑥ 置信度评估
    let warning_count = warnings.len();
    let confidence = if turn_confidence == TurnConfidence::Reject {
        Confidence::Reject
    } else if warning_count == 0 && turn_confidence == TurnConfidence::Direct {
        Confidence::High
    } else if warning_count <= 3 {
        Confidence::Medium
    } else {
        Confidence::Low
    };

    Ok(ReconciledState {
        turn,
        turn_source,
        stats,
        ramen,
        warnings,
        confidence,
        observed_trainings,
        support_bonds,
        training_levels,
        feeling_types,
        evaluation_bonds,
    })
}

// ── 直读训练观测解析 ────────────────────────────────────────────────

/// 从 hlpatch `trainings[]` 原始 JSON 提取观测人头。
///
/// 训练下标优先用 `command_id`（实测映射 101=速 105=耐 102=力 103=根 106=智），
/// 否则按 `name` 匹配（Speed/Stamina/Power/Guts/Wiz，兼容中文单字）。
/// 无法定位训练的条目跳过（外出/休息 301/390/401/701/801 等天然跳过）。
fn parse_observed_trainings(raw: &[serde_json::Value]) -> Vec<ObservedTraining> {
    let mut out = Vec::new();
    for item in raw {
        let train_index = item
            .get("command_id")
            .and_then(|v| v.as_i64())
            .and_then(command_id_to_train_idx)
            .or_else(|| {
                item.get("name")
                    .and_then(|v| v.as_str())
                    .and_then(|name| match name.trim() {
                        "Speed" | "速" => Some(0),
                        "Stamina" | "耐" => Some(1),
                        "Power" | "力" => Some(2),
                        "Guts" | "根" => Some(3),
                        "Wiz" | "Wisdom" | "智" => Some(4),
                        _ => None,
                    })
            });
        let Some(train_index) = train_index else { continue };
        let heads = item.get("heads").and_then(|v| v.as_i64()).unwrap_or(0);
        let shining = item.get("shining").and_then(|v| v.as_i64()).unwrap_or(0);
        out.push(ObservedTraining {
            train_index,
            heads: heads.clamp(0, 12) as i32,
            shining: shining.clamp(0, 12) as i32,
        });
    }
    out
}

/// hlpatch command_id → 上游训练下标（0=速 1=耐 2=力 3=根 4=智）。
///
/// # v0.4.2 实测映射（SO 实况 + 黑板 GameStatusSend 佐证，**不连续！**）
///
/// - `101=速`、`105=耐`、`102=力`、`103=根`、`106=智`
/// - `104` 实况未出现，语义未知 → None
/// - `601..=605` 是**合宿命令**（training_levels 里恒 level5），与普通训练
///   不同槽，**不映射**——上游合宿期 train_level=5 硬编码，不读 6xx；
///   旧版把 601-605 映射到 0-4 会把合宿值错写进普通训练等级
/// - `301/390/401/701/801`（外出/休息）→ None
pub fn command_id_to_train_idx(cmd: i64) -> Option<usize> {
    match cmd {
        101 => Some(0), // 速 Speed
        105 => Some(1), // 耐 Stamina
        102 => Some(2), // 力 Power
        103 => Some(3), // 根 Guts
        106 => Some(4), // 智 Wiz
        _ => None,
    }
}

/// 解析支援卡羁绊（kizuna）与训练伙伴精确羁绊（current_bond），
/// 合并为 `编码后 card_id → bond`。`current_bond` 优先（训练画面实时累计真值）。
///
/// # v0.4.2 编码（黑板协议 GameStatusSend 实锤）
///
/// 人头 cardID = `support_card_id * 10 + limit_break_count`（短 id ×10 + 突破数，
/// "神团 30137 满破 301374"）。SO 的 short id 据此编码后才能与上游
/// `person.card_id` 匹配——旧版直接用短 id 匹配**全部 miss**。
fn parse_support_bonds(
    support_cards: &[HlpatchSupportCard],
    trainings: &[serde_json::Value],
) -> Vec<(u32, i32)> {
    let mut map: HashMap<u32, i32> = HashMap::new();
    // 短 id → 突破数（供 partners[] 里的短 id 编码用）
    let mut limit_by_id: HashMap<u32, i32> = HashMap::new();

    // 1) 支援卡静态羁绊（kizuna）：仅当尚无更精确的 current_bond 时记录
    for sc in support_cards {
        if let Some(id) = sc.support_card_id {
            let lb = sc.limit_break_count.clamp(0, 5);
            limit_by_id.entry(id).or_insert(lb);
            let encoded = encode_card_id(id, lb);
            map.entry(encoded).or_insert_with(|| sc.kizuna.clamp(0, 100));
        }
    }

    // 2) 训练伙伴精确羁绊（current_bond）覆盖（partners[] 里是短 id，编码后覆盖）
    for tr in trainings {
        if let Some(partners) = tr.get("partners").and_then(|p| p.as_array()) {
            for p in partners {
                let Some(short_id) = p
                    .get("support_card_id")
                    .and_then(|v| v.as_u64())
                    .map(|v| v as u32)
                else {
                    continue;
                };
                let bond = p
                    .get("current_bond")
                    .and_then(|v| v.as_i64())
                    .unwrap_or(0) as i32;
                let lb = limit_by_id
                    .get(&short_id)
                    .copied()
                    .unwrap_or(0);
                let encoded = encode_card_id(short_id, lb);
                map.insert(encoded, bond.clamp(0, 100));
            }
        }
    }

    map.into_iter().collect()
}

/// 黑板协议支援卡编码：`short_id * 10 + limit_break_count`。
/// short_id 5 位（≤ 99999），编码后 6 位，u32 安全。
pub fn encode_card_id(short_id: u32, limit_break: i32) -> u32 {
    short_id
        .saturating_mul(10)
        .saturating_add(limit_break.max(0) as u32)
}

/// 解析 evaluation 表为 (target_id, bond)，只保留 102(理事长)/103(记者)。
/// `current_bond` 优先，缺失时用 `evaluation`。
fn parse_evaluation_bonds(raw: &[HlpatchEvaluation]) -> Vec<(i64, i32)> {
    let mut out = Vec::new();
    for e in raw {
        if e.target_id != 102 && e.target_id != 103 {
            continue;
        }
        let bond = if e.current_bond > 0 {
            e.current_bond
        } else {
            e.evaluation
        };
        out.push((e.target_id, bond.clamp(0, 100)));
    }
    out
}

/// 解析训练等级（training_levels[]）为 (train_idx, level)。
/// 601-605（合宿）与未知 command_id 由 [`command_id_to_train_idx`] 返回 None 天然跳过。
fn parse_training_levels(raw: &[HlpatchTrainingLevel]) -> Vec<(usize, i32)> {
    let mut out = Vec::new();
    for tl in raw {
        if let Some(idx) = command_id_to_train_idx(tl.command_id) {
            out.push((idx, tl.level.clamp(1, 5)));
        }
    }
    out
}

/// 解析角标（command_feelings[]）为 (train_idx, feeling_id)。
fn parse_command_feelings(raw: &[HlpatchCommandFeeling]) -> Vec<(usize, i32)> {
    let mut out = Vec::new();
    for cf in raw {
        if let Some(idx) = command_id_to_train_idx(cf.command_id) {
            out.push((idx, cf.feeling_id.clamp(0, 3)));
        }
    }
    out
}

// ── 回合推导 ────────────────────────────────────────────────────────

#[derive(Debug, Clone, Copy, PartialEq)]
enum TurnConfidence {
    /// turn 字段直接可用
    Direct,
    /// 从 month+half+year 推导
    Derived,
    /// 从属性总量估算年份后推导
    Estimated,
    /// 无法确定
    Reject,
}

/// 回合推导（v0.4.2 修正：直读透传，推导值 -1）。
///
/// - hlpatch `turn` 已是 AI 0-based（黑板协议 GameStatusSend 发送前减 1），
///   `0..=77` 合法、`0` = 第1回合——**原样透传，不再改写**；
/// - month/half/年份推导出的是 UI 1-based 值，**-1** 转 AI 内部口径。
fn derive_turn(
    raw: &HlpatchSummary,
    stats: &HlpatchStats,
    warnings: &mut Vec<String>,
) -> Result<(i32, String, TurnConfidence), String> {
    // 优先用 turn 字段（hlpatch 直读，AI 0-based）
    if let Some(t) = raw.turn {
        if (0..=77).contains(&t) {
            return Ok((t, "direct".into(), TurnConfidence::Direct));
        }
        warnings.push(format!("turn={t} 越界(0-77)，回退 month/half 推导"));
    }

    // 从 year + month + half 推导（UI 1-based → -1 转 AI 内部）
    if raw.year > 0 && raw.month >= 1 && raw.month <= 12 && raw.half >= 1 && raw.half <= 2 {
        let ui_turn = (raw.year - 1) * 24 + (raw.month - 1) * 2 + raw.half;
        if ui_turn >= 1 && ui_turn <= 78 {
            let turn = ui_turn - 1;
            warnings.push(format!(
                "turn 从 year={} month={} half={} 推导: UI 第{}回合 → AI 内部 {}",
                raw.year, raw.month, raw.half, ui_turn, turn
            ));
            return Ok((turn, "year+month+half".into(), TurnConfidence::Derived));
        }
    }

    // 从 month + half + 属性总量估算年份
    if raw.month >= 1 && raw.month <= 12 && raw.half >= 1 && raw.half <= 2 {
        let total = stats.speed + stats.stamina + stats.power + stats.guts + stats.wiz;
        let year = estimate_year_from_stats(total);
        let ui_turn = (year - 1) * 24 + (raw.month - 1) * 2 + raw.half;
        let turn = ui_turn - 1;
        warnings.push(format!(
            "turn 从 month={} half={} + 属性总量{}估算年份{}: UI 第{}回合 → AI 内部 {}（近似值）",
            raw.month, raw.half, total, year, ui_turn, turn
        ));
        return Ok((turn, "month+half+stats_estimate".into(), TurnConfidence::Estimated));
    }

    // 完全无法确定
    Err(format!(
        "无法确定回合：turn={}, year={}, month={}, half={}",
        raw.turn.map(|t| t.to_string()).unwrap_or_else(|| "缺失".into()),
        raw.year, raw.month, raw.half
    ))
}

/// 从五维总量粗估年份。
///
/// 这是经验值，不是精确公式：
/// - 第1年：总属性通常 0-1500
/// - 第2年：总属性通常 1500-3500
/// - 第3年：总属性通常 3500+
fn estimate_year_from_stats(total: i32) -> i32 {
    if total < 1500 {
        1
    } else if total < 3500 {
        2
    } else {
        3
    }
}

// ── 五维校验 ────────────────────────────────────────────────────────

fn validate_stats(raw: &HlpatchStats, warnings: &mut Vec<String>) -> ReconciledStats {
    // 上游已移除 2800 硬钳制，改用剧本化上限。
    // 但 hlpatch 读的是游戏内存真实值，不会超上限，这里只做防御性 clamp。
    const STATUS_HARD_CAP: i32 = 3500;

    let max_vital = raw.max_vital.max(raw.vital).max(100);
    if raw.max_vital > 0 && raw.max_vital < max_vital {
        warnings.push(format!(
            "max_vital={} 小于 vital={}，修正为 {}",
            raw.max_vital, raw.vital, max_vital
        ));
    }

    let vital = if raw.vital < 0 {
        warnings.push(format!("vital={} 为负，钳制为 0", raw.vital));
        0
    } else if raw.vital > max_vital {
        warnings.push(format!(
            "vital={} 超过 max_vital={}，钳制",
            raw.vital, max_vital
        ));
        max_vital
    } else {
        raw.vital
    };

    let motivation = if !(1..=5).contains(&raw.motivation) {
        warnings.push(format!(
            "motivation={} 不在 1-5 范围，钳制为 3(普通)",
            raw.motivation
        ));
        3
    } else {
        raw.motivation
    };

    ReconciledStats {
        speed: clamp_status(raw.speed, "speed", STATUS_HARD_CAP, warnings),
        stamina: clamp_status(raw.stamina, "stamina", STATUS_HARD_CAP, warnings),
        power: clamp_status(raw.power, "power", STATUS_HARD_CAP, warnings),
        guts: clamp_status(raw.guts, "guts", STATUS_HARD_CAP, warnings),
        wiz: clamp_status(raw.wiz, "wiz", STATUS_HARD_CAP, warnings),
        vital,
        max_vital,
        motivation,
        skill_point: raw.skill_point.max(0),
    }
}

/// 防御性钳制五维值到合法范围
fn clamp_status(v: i32, name: &str, hard_cap: i32, warnings: &mut Vec<String>) -> i32 {
    if v < 0 {
        warnings.push(format!("{name}={v} 为负，钳制为 0"));
        0
    } else if v > hard_cap {
        warnings.push(format!("{name}={v} 超过硬上限{hard_cap}，钳制"));
        hard_cap
    } else {
        v
    }
}

// ── 拉面状态校验 ────────────────────────────────────────────────────

fn validate_ramen(raw: &HlpatchRamen, warnings: &mut Vec<String>) -> ReconciledRamen {
    // sozai → feeling_stock
    let feeling_stock = validate_feeling_stock(&raw.sozai, warnings);

    // acquisition_gauges → feeling_slot
    let (feeling_slot, feeling_slot_source) =
        parse_acquisition_gauges(&raw.acquisition_gauges, warnings);

    // special_feeling
    let special_feeling = if raw.special_feeling_num < 0 {
        warnings.push(format!(
            "special_feeling_num={} 为负，钳制为 0",
            raw.special_feeling_num
        ));
        0
    } else if raw.special_feeling_num > 4 {
        warnings.push(format!(
            "special_feeling_num={} 超过上限4，钳制",
            raw.special_feeling_num
        ));
        4
    } else {
        raw.special_feeling_num
    };

    // selected_region_ids → 0-based indices
    let (selected_region_indices, has_region_data) =
        validate_region_ids(&raw.selected_region_ids, warnings);

    ReconciledRamen {
        scenario_pt: raw.checkpoint_pt.max(0),
        feeling_stock,
        feeling_slot,
        feeling_slot_source,
        special_feeling,
        selected_region_indices,
        has_region_data,
    }
}

fn validate_feeling_stock(sozai: &[i32], warnings: &mut Vec<String>) -> [i32; 3] {
    if sozai.is_empty() {
        return [0, 0, 0];
    }

    let mut stock = [0i32; 3];
    for i in 0..3.min(sozai.len()) {
        let v = sozai[i];
        if v < 0 {
            warnings.push(format!("sozai[{i}]={} 为负，钳制为 0", v));
            stock[i] = 0;
        } else if v > FEELING_LIMIT {
            warnings.push(format!(
                "sozai[{i}]={} 超过 FEELING_LIMIT={}，钳制",
                v, FEELING_LIMIT
            ));
            stock[i] = FEELING_LIMIT;
        } else {
            stock[i] = v;
        }
    }

    if sozai.len() < 3 {
        warnings.push(format!("sozai 长度{}不足3，补零", sozai.len()));
    }

    // 校验总和
    let total: i32 = stock.iter().sum();
    if total > FEELING_LIMIT {
        warnings.push(format!(
            "sozai 总和{}超过 FEELING_LIMIT={}，按比例缩放",
            total, FEELING_LIMIT
        ));
        let scale = FEELING_LIMIT as f64 / total as f64;
        for i in 0..3 {
            stock[i] = (stock[i] as f64 * scale).round() as i32;
        }
        // 修正舍入误差
        let new_total: i32 = stock.iter().sum();
        if new_total < FEELING_LIMIT {
            // 找最大的那项补差
            let max_idx = stock
                .iter()
                .enumerate()
                .max_by_key(|(_, &v)| v)
                .map(|(i, _)| i)
                .unwrap_or(0);
            stock[max_idx] += FEELING_LIMIT - new_total;
        }
    }

    stock
}

/// 解析 acquisition_gauges。
///
/// hlpatch 推送格式为对象数组：
/// ```json
/// [{"feeling_id": 1, "remaining": 3}, {"feeling_id": 2, "remaining": 1}, ...]
/// ```
///
/// `remaining` 是"还剩几回合获得诀窍"（倒数），而模拟器的 `feeling_slot` 是
/// "累积点数"（0→GAUGE_LIMIT 累加）。两者语义不同，只能近似转换：
/// `feeling_slot ≈ GAUGE_LIMIT - remaining`（粗估，标记为近似值）。
///
/// 也兼容旧格式整数数组 `[3, 1, 0]`。
fn parse_acquisition_gauges(
    raw: &serde_json::Value,
    warnings: &mut Vec<String>,
) -> ([i32; 3], String) {
    let mut slot = [0i32; 3];

    // 空值
    if raw.is_null() {
        return (slot, "missing".into());
    }

    // 整数数组格式（旧兼容）
    if let Some(arr) = raw.as_array() {
        if arr.iter().all(|v| v.is_i64() || v.is_u64()) {
            for i in 0..3.min(arr.len()) {
                let v = arr[i].as_i64().unwrap_or(0) as i32;
                slot[i] = v.clamp(0, GAUGE_LIMIT);
            }
            if arr.len() < 3 {
                warnings.push(format!(
                    "acquisition_gauges(整数数组)长度{}不足3",
                    arr.len()
                ));
            }
            return (slot, "int_array".into());
        }

        // 对象数组格式（hlpatch v3.27.x）
        let mut parsed_count = 0;
        for elem in arr {
            // 尝试 {feeling_id, remaining} 格式
            if let (Some(feeling_id), Some(remaining)) = (
                elem.get("feeling_id").and_then(|v| v.as_i64()),
                elem.get("remaining").and_then(|v| v.as_i64()),
            ) {
                let idx = (feeling_id - 1) as usize;
                if idx < 3 {
                    // 近似转换：remaining(倒数回合) → feeling_slot(累积点数)
                    // remaining=0 表示即将获得诀窍 → slot ≈ GAUGE_LIMIT
                    // remaining 越大表示离获得越远 → slot 越小
                    let estimated = GAUGE_LIMIT - remaining.clamp(0, GAUGE_LIMIT as i64) as i32;
                    slot[idx] = estimated.clamp(0, GAUGE_LIMIT - 1);
                    parsed_count += 1;
                }
            }
        }

        if parsed_count > 0 {
            if parsed_count < 3 {
                warnings.push(format!(
                    "acquisition_gauges 只解析出{}项（期望3）",
                    parsed_count
                ));
            }
            warnings.push("feeling_slot 从 remaining 近似转换（语义不完全匹配）".into());
            return (slot, "object_array_approximated".into());
        }
    }

    warnings.push(format!("acquisition_gauges 格式无法解析: {}", raw));
    (slot, "parse_failed".into())
}

fn validate_region_ids(
    ids: &[i32],
    warnings: &mut Vec<String>,
) -> ([usize; 3], bool) {
    let mut indices = [0usize; 3];

    if ids.is_empty() {
        return (indices, false);
    }

    for i in 0..3.min(ids.len()) {
        let id = ids[i];
        if id < 1 || id > 20 {
            warnings.push(format!("selected_region_ids[{i}]={} 越界(1-20)，置0", id));
            indices[i] = 0;
        } else {
            // 1-based → 0-based，年3地区复用年1配方（取模映射）
            indices[i] = ((id - 1) as usize) % 10;
        }
    }

    if ids.len() < 3 {
        warnings.push(format!(
            "selected_region_ids 长度{}不足3",
            ids.len()
        ));
    }

    (indices, true)
}

// ── motivation 反序列化（接受整数或字符串）─────────────────────────

fn deserialize_motivation<'de, D: serde::Deserializer<'de>>(d: D) -> Result<i32, D::Error> {
    let v: serde_json::Value = Deserialize::deserialize(d)?;
    match v {
        serde_json::Value::Number(n) => Ok(n.as_i64().unwrap_or(3) as i32),
        serde_json::Value::String(s) => {
            let lower = s.to_lowercase();
            let val = match lower.as_str() {
                "best" | "perfect" | "絶好調" | "絶好" | "绝好调" | "绝好" => 5,
                "good" | "好調" | "好" | "好调" => 4,
                "normal" | "普通" => 3,
                "bad" | "不調" | "不" | "不调" => 2,
                "worst" | "絶不調" | "绝不" | "绝不调" => 1,
                _ => s.parse::<i32>().unwrap_or(3),
            };
            Ok(val)
        }
        _ => Ok(3),
    }
}

// ── 单元测试 ────────────────────────────────────────────────────────

#[cfg(test)]
mod tests {
    use super::*;

    /// 用户提供的真实样本（month=4, half=1, 无 turn 字段）：
    /// UI 推导 (2-1)*24+3*2+1=31（1-based）→ v0.4.2 起 -1 = AI 内部 30
    #[test]
    fn test_reconcile_real_sample() {
        let json = r#"{
            "ok": true,
            "chara": {
                "speed": 1200, "stamina": 301, "power": 437, "guts": 362, "wiz": 280,
                "vital": 60, "max_vital": 108, "motivation": 5,
                "skill_point": 1492, "scenario_id": 14, "fan_count": 32425,
                "chara_effect_ids": [40, 42, 43], "scenario_progress": 0
            },
            "month": 4, "half": 1, "playing_state": 1, "is_playing": true,
            "scenario_obj": "0x7eaabd43e0",
            "via": "WorkDataManager->get_SingleMode->get_Character->getters"
        }"#;

        let raw: HlpatchSummary = serde_json::from_str(json).unwrap();
        let result = reconcile(&raw);

        assert!(result.is_ok(), "reconcile 应该成功: {:?}", result.err());
        let state = result.unwrap();

        // 总属性 = 2580 → 估算第2年；UI 第31回合 → AI 内部 30
        assert_eq!(state.turn, 30, "推导 UI 31 → AI 内部 30");
        assert_eq!(state.confidence, Confidence::Medium);
        assert!(
            state
                .warnings
                .iter()
                .any(|w| w.contains("AI 内部")),
            "应该有口径换算的 warning"
        );
        assert_eq!(state.stats.speed, 1200);
        assert_eq!(state.stats.motivation, 5);
        assert!(!state.ramen.has_region_data);
        assert!(state.observed_trainings.is_empty(), "无 trainings → 观测为空");
        assert!(state.support_bonds.is_empty());
        assert!(state.training_levels.is_empty());
        assert!(state.feeling_types.is_empty());
        assert!(state.evaluation_bonds.is_empty());
    }

    /// ★ v0.4.2：直读 turn 已是 AI 0-based（黑板协议发送前减 1），原样透传
    #[test]
    fn test_direct_turn_is_ai_zero_based_passthrough() {
        let json = r#"{
            "scenario": "Ramen",
            "turn": 30,
            "chara": {
                "speed": 1200, "stamina": 301, "power": 437, "guts": 362, "wiz": 280,
                "vital": 60, "max_vital": 108, "motivation": 5,
                "skill_point": 1492, "scenario_id": 14
            },
            "ramen": {
                "checkpoint_pt": 450,
                "special_feeling_num": 2,
                "sozai": [3, 2, 1],
                "acquisition_gauges": [3, 5, 1]
            }
        }"#;

        let raw: HlpatchSummary = serde_json::from_str(json).unwrap();
        let state = reconcile(&raw).unwrap();

        assert_eq!(state.turn, 30, "直读 AI 内部 30，透传不改写（UI 第31回合）");
        assert_eq!(state.turn_source, "direct");
        assert_eq!(state.confidence, Confidence::High, "直读且无校正 → High");
        assert!(state.warnings.is_empty(), "直读回合不应产生估算 warning");
    }

    /// v0.3.2：trainings 解析为观测人头。
    /// v0.4.2 数据对齐实测映射（101=速 105=耐 102=力 103=根 106=智）
    #[test]
    fn test_observed_trainings_parsed() {
        let json = r#"{
            "scenario": "Ramen",
            "turn": 10,
            "chara": {"scenario_id": 14, "speed": 100, "vital": 50, "max_vital": 100},
            "trainings": [
                {"name": "Speed", "command_id": 101, "heads": 2, "shining": 1},
                {"name": "Stamina", "command_id": 105, "heads": 3, "shining": 0},
                {"name": "Power", "command_id": 102, "heads": 5, "shining": 0},
                {"name": "Guts", "command_id": 103, "heads": 3, "shining": 0},
                {"name": "Wiz", "command_id": 106, "heads": 4, "shining": 2}
            ]
        }"#;
        let raw: HlpatchSummary = serde_json::from_str(json).unwrap();
        let state = reconcile(&raw).unwrap();
        assert_eq!(state.observed_trainings.len(), 5, "五条观测全部解析");
        // 每条都落到正确的训练槽（v0.4.2 之前 105→4/102→1/103→2 全错位）
        let by_idx: std::collections::HashMap<usize, i32> = state
            .observed_trainings
            .iter()
            .map(|o| (o.train_index, o.heads))
            .collect();
        assert_eq!(by_idx.get(&0), Some(&2), "速 101→0");
        assert_eq!(by_idx.get(&1), Some(&3), "耐 105→1（旧版错映射到 4）");
        assert_eq!(by_idx.get(&2), Some(&5), "力 102→2（旧版错映射到 1）");
        assert_eq!(by_idx.get(&3), Some(&3), "根 103→3（旧版错映射到 2）");
        assert_eq!(by_idx.get(&4), Some(&4), "智 106→4（旧版 106 无映射被丢弃）");
    }

    /// turn=0 = 第1回合（AI 内部 0，合法直读）；
    /// turn 缺失时才走 month/half 推导。
    #[test]
    fn test_turn_zero_is_first_turn_and_missing_turn_falls_back() {
        let zero: HlpatchSummary =
            serde_json::from_str(r#"{"scenario":"Ramen","turn":0,"chara":{"scenario_id":14,"speed":100}}"#)
                .unwrap();
        let s0 = reconcile(&zero).unwrap();
        assert_eq!(s0.turn, 0, "turn=0 = 第1回合（AI 内部 0），透传");
        assert_eq!(s0.turn_source, "direct");

        let missing: HlpatchSummary =
            serde_json::from_str(r#"{\"scenario\":\"Ramen\",\"chara\":{\"scenario_id\":14,\"speed\":100}}"#.replace("\\\"", "\"").as_str())
                .unwrap();
        assert_eq!(missing.turn, None);
        // 无 turn 且无 month/half → 无法确定
        assert!(reconcile(&missing).is_err());
    }

    #[test]
    fn test_reconcile_with_ramen_data() {
        let json = r#"{
            "scenario": "Ramen",
            "turn": 10,
            "chara": {
                "speed": 500, "stamina": 400, "power": 300, "guts": 200, "wiz": 100,
                "vital": 80, "max_vital": 100, "motivation": 4,
                "skill_point": 500, "scenario_id": 14
            },
            "ramen": {
                "checkpoint_pt": 450,
                "special_feeling_num": 2,
                "sozai": [3, 2, 1],
                "acquisition_gauges": [
                    {"feeling_id": 1, "remaining": 2},
                    {"feeling_id": 2, "remaining": 0},
                    {"feeling_id": 3, "remaining": 5}
                ],
                "selected_region_ids": [1, 4, 7]
            }
        }"#;

        let raw: HlpatchSummary = serde_json::from_str(json).unwrap();
        let state = reconcile(&raw).unwrap();

        // 直读透传（AI 内部 10）
        assert_eq!(state.turn, 10);
        assert_eq!(state.turn_source, "direct");
        assert_eq!(state.confidence, Confidence::Medium); // 有 feeling_slot 近似 warning

        // sozai
        assert_eq!(state.ramen.feeling_stock, [3, 2, 1]);

        // acquisition_gauges 近似转换
        assert_eq!(state.ramen.feeling_slot, [5, 6, 2]);
        assert_eq!(state.ramen.feeling_slot_source, "object_array_approximated");

        // 地区 ID
        assert_eq!(state.ramen.selected_region_indices, [0, 3, 6]);
        assert!(state.ramen.has_region_data);
    }

    #[test]
    fn test_reconcile_rejects_non_ramen() {
        let json = r#"{"scenario": "URA", "chara": {"scenario_id": 1}}"#;
        let raw: HlpatchSummary = serde_json::from_str(json).unwrap();
        assert!(reconcile(&raw).is_err());
    }

    #[test]
    fn test_reconcile_rejects_no_turn_info() {
        let json = r#"{"scenario": "Ramen", "chara": {"scenario_id": 14, "speed": 100}}"#;
        let raw: HlpatchSummary = serde_json::from_str(json).unwrap();
        assert!(reconcile(&raw).is_err());
    }

    #[test]
    fn test_sozai_overflow_scaled() {
        let json = r#"{
            "turn": 10,
            "chara": {"scenario_id": 14, "speed": 100, "max_vital": 100, "vital": 50},
            "ramen": {"sozai": [8, 8, 8]}
        }"#;
        let raw: HlpatchSummary = serde_json::from_str(json).unwrap();
        let state = reconcile(&raw).unwrap();

        // 总和 24 > 10，应该缩放到总和 10
        let total: i32 = state.ramen.feeling_stock.iter().sum();
        assert_eq!(total, FEELING_LIMIT, "sozai 总和应缩放到 FEELING_LIMIT");
    }

    #[test]
    fn test_acquisition_gauges_int_array_compat() {
        let json = r#"{
            "turn": 10,
            "chara": {"scenario_id": 14, "speed": 100, "max_vital": 100, "vital": 50},
            "ramen": {"acquisition_gauges": [3, 5, 1]}
        }"#;
        let raw: HlpatchSummary = serde_json::from_str(json).unwrap();
        let state = reconcile(&raw).unwrap();

        assert_eq!(state.ramen.feeling_slot, [3, 5, 1]);
        assert_eq!(state.ramen.feeling_slot_source, "int_array");
    }

    // ── v0.4.0/v0.4.2 注入数据解析测试 ──────────────────────────────

    /// ★ v0.4.2 实测映射：101=速 105=耐 102=力 103=根 106=智；
    /// 104 未知、601-605 合宿 → None
    #[test]
    fn test_command_id_to_train_idx() {
        assert_eq!(command_id_to_train_idx(101), Some(0), "速");
        assert_eq!(command_id_to_train_idx(105), Some(1), "耐（旧版错映射 4）");
        assert_eq!(command_id_to_train_idx(102), Some(2), "力（旧版错映射 1）");
        assert_eq!(command_id_to_train_idx(103), Some(3), "根（旧版错映射 2）");
        assert_eq!(command_id_to_train_idx(106), Some(4), "智（旧版直接丢弃）");
        assert_eq!(command_id_to_train_idx(104), None, "未知语义");
        assert_eq!(command_id_to_train_idx(601), None, "合宿命令不映射（旧版错映射 0）");
        assert_eq!(command_id_to_train_idx(605), None, "合宿命令不映射（旧版错映射 4）");
        assert_eq!(command_id_to_train_idx(301), None, "外出");
        assert_eq!(command_id_to_train_idx(390), None, "休息");
        assert_eq!(command_id_to_train_idx(999), None);
    }

    /// ★ v0.4.2 编码：cardId = short*10 + limit_break（黑板协议实锤）
    #[test]
    fn test_encode_card_id() {
        assert_eq!(encode_card_id(30137, 4), 301374, "神团满破 301374");
        assert_eq!(encode_card_id(30312, 4), 303124);
        assert_eq!(encode_card_id(30305, 0), 303050, "友人卡 0 突破");
        assert_eq!(encode_card_id(30242, 4), 302424);
    }

    /// v0.4.2：kizuna/current_bond 合并 + 编码后键
    #[test]
    fn test_parse_support_bonds_kizuna_and_current_bond() {
        let json = r#"{
            "turn": 10,
            "chara": {"scenario_id": 14, "speed": 100},
            "support_cards": [
                {"support_card_id": 30242, "limit_break_count": 4, "kizuna": 70},
                {"support_card_id": 30289, "limit_break_count": 4, "kizuna": 40}
            ],
            "trainings": [
                {"command_id": 101, "heads": 2, "partners": [
                    {"support_card_id": 30242, "current_bond": 85},
                    {"support_card_id": 30289, "current_bond": 55}
                ]}
            ]
        }"#;
        let raw: HlpatchSummary = serde_json::from_str(json).unwrap();
        let state = reconcile(&raw).unwrap();
        // current_bond 优先于 kizuna：30242(4突)→302424:85，30289(4突)→302894:55
        assert_eq!(state.support_bonds.len(), 2);
        let mut map: std::collections::HashMap<u32, i32> =
            state.support_bonds.iter().cloned().collect();
        assert_eq!(map.get(&302424), Some(&85), "短id 30242 编码为 302424");
        assert_eq!(map.get(&302894), Some(&55), "短id 30289 编码为 302894");
    }

    /// v0.4.2：601-605 合宿等级不再写进普通训练槽
    #[test]
    fn test_parse_training_levels_and_feelings() {
        let json = r#"{
            "turn": 10,
            "chara": {"scenario_id": 14, "speed": 100},
            "training_levels": [
                {"command_id": 101, "level": 3},
                {"command_id": 103, "level": 5},
                {"command_id": 601, "level": 5},
                {"command_id": 605, "level": 5}
            ],
            "ramen": {
                "command_feelings": [
                    {"command_id": 102, "feeling_id": 1},
                    {"command_id": 105, "feeling_id": 2},
                    {"command_id": 601, "feeling_id": 3}
                ]
            }
        }"#;
        let raw: HlpatchSummary = serde_json::from_str(json).unwrap();
        let state = reconcile(&raw).unwrap();
        // training_levels: 101→0(L3), 103→3(L5)；601/605 合宿被丢弃
        let tl: std::collections::HashMap<usize, i32> =
            state.training_levels.iter().cloned().collect();
        assert_eq!(tl.len(), 2, "合宿 601/605 不应出现");
        assert_eq!(tl.get(&0), Some(&3));
        assert_eq!(tl.get(&3), Some(&5), "根 103→槽 3（旧版错映射 2）");
        // feeling_types: 102→2(A), 105→1(B)；601 合宿被丢弃
        let ft: std::collections::HashMap<usize, i32> =
            state.feeling_types.iter().cloned().collect();
        assert_eq!(ft.len(), 2, "合宿角标不应出现");
        assert_eq!(ft.get(&2), Some(&1), "力 102→槽 2");
        assert_eq!(ft.get(&1), Some(&2), "耐 105→槽 1");
    }

    /// v0.4.2：evaluation 表解析出理事长(102)/记者(103)羁绊
    #[test]
    fn test_parse_evaluation_bonds() {
        let json = r#"{
            "turn": 20,
            "chara": {"scenario_id": 14, "speed": 100},
            "evaluation": [
                {"target_id": 1, "partner_id": 1, "evaluation": 100, "current_bond": 100},
                {"target_id": 102, "partner_id": 102, "evaluation": 100, "current_bond": 100},
                {"target_id": 103, "partner_id": 103, "evaluation": 80, "current_bond": 42},
                {"target_id": 104, "partner_id": 104, "evaluation": 0, "current_bond": 0},
                {"target_id": 1120, "partner_id": 1120, "evaluation": 0, "current_bond": 0}
            ]
        }"#;
        let raw: HlpatchSummary = serde_json::from_str(json).unwrap();
        let state = reconcile(&raw).unwrap();
        assert_eq!(
            state.evaluation_bonds,
            vec![(102, 100), (103, 42)],
            "只保留 102=理事长/103=记者；103 取 current_bond=42"
        );
    }

    #[test]
    fn test_injection_data_absent_is_empty() {
        // 旧版 hlpatch 样本（无 support_cards / training_levels / command_feelings / evaluation）
        let json = r#"{
            "scenario": "Ramen",
            "turn": 10,
            "chara": {"scenario_id": 14, "speed": 100, "vital": 50, "max_vital": 100}
        }"#;
        let raw: HlpatchSummary = serde_json::from_str(json).unwrap();
        let state = reconcile(&raw).unwrap();
        assert!(state.support_bonds.is_empty());
        assert!(state.training_levels.is_empty());
        assert!(state.feeling_types.is_empty());
        assert!(state.evaluation_bonds.is_empty());
    }
}
