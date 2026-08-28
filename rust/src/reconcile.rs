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
//! # 回合口径（重要）
//!
//! - hlpatch 的 `turn` 与游戏 UI「第N回合」一致（1-based，第1回合=1）。
//!   hlpatch v3.27.17+ 对拉面杯直接发 turn（raw_total_turn_num 校正），
//!   本层直读，置信度 High，不再做年份估算。
//! - 上游模拟器 umaai-rs 的内部回合从 0 开始（0..=77），
//!   `inject_state()` 负责 `turn - 1` 转换，本层保持 1-based 口径。
//! - hlpatch 若发 `turn: 0`，按「第1回合」处理（内部回合 0，两种口径一致）。

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
    /// hlpatch 直读回合（1-based，与游戏 UI「第N回合」一致）。
    /// Option 用于区分「字段缺失」和「字段=0」：缺失才走 month/half 推导。
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
}

// ── 校正后的状态 ────────────────────────────────────────────────────

/// 校正结果：包含清洗后的状态 + 校正日志 + 置信度
#[derive(Debug, Clone, Serialize)]
pub struct ReconciledState {
    /// 推导出的回合（1-based，与 hlpatch UI 一致）
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
/// 2. 回合推导：优先用 turn 字段（1-based 直读），否则从 month+half+stats 推导
/// 3. 五维校验：clamp 到 [0, 剧本上限]
/// 4. 体力校验：clamp 到 [0, max_vital]
/// 5. sozai 校验：每项 [0, FEELING_LIMIT]，总和 ≤ FEELING_LIMIT
/// 6. acquisition_gauges 解析：对象数组 → [i32; 3]
/// 7. 地区 ID 校验：1-based → 0-based
/// 8. 置信度评估
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
    })
}

// ── 回合推导 ────────────────────────────────────────────────────────

#[derive(Debug, Clone, Copy, PartialEq)]
enum TurnConfidence {
    /// turn 字段直接可用
    Direct,
    /// 从 month+half+year 推导
    Derived,
    /// 从属性总量粗估年份后推导
    Estimated,
    /// 无法确定
    Reject,
}

fn derive_turn(
    raw: &HlpatchSummary,
    stats: &HlpatchStats,
    warnings: &mut Vec<String>,
) -> Result<(i32, String, TurnConfidence), String> {
    // 优先用 turn 字段（hlpatch 直读，1-based，与游戏 UI「第N回合」一致）。
    // AI（umaai-rs）内部回合从 0 开始，inject_state() 做 -1 转换。
    if let Some(t) = raw.turn {
        if (0..=78).contains(&t) {
            // turn=0 视为第1回合（内部回合 0，两种口径一致）
            let turn = if t == 0 { 1 } else { t };
            return Ok((turn, "direct".into(), TurnConfidence::Direct));
        }
        warnings.push(format!("turn={t} 越界(0-78)，回退 month/half 推导"));
    }

    // 从 year + month + half 推导
    if raw.year > 0 && raw.month >= 1 && raw.month <= 12 && raw.half >= 1 && raw.half <= 2 {
        let turn = (raw.year - 1) * 24 + (raw.month - 1) * 2 + raw.half;
        if turn >= 1 && turn <= 78 {
            warnings.push(format!(
                "turn 从 year={} month={} half={} 推导为 {}",
                raw.year, raw.month, raw.half, turn
            ));
            return Ok((turn, "year+month+half".into(), TurnConfidence::Derived));
        }
    }

    // 从 month + half + 属性总量估算年份
    if raw.month >= 1 && raw.month <= 12 && raw.half >= 1 && raw.half <= 2 {
        let total = stats.speed + stats.stamina + stats.power + stats.guts + stats.wiz;
        let year = estimate_year_from_stats(total);
        let turn = (year - 1) * 24 + (raw.month - 1) * 2 + raw.half;
        warnings.push(format!(
            "turn 从 month={} half={} + 属性总量{}估算年份{}推导为 {}（近似值）",
            raw.month, raw.half, total, year, turn
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

    /// 用户提供的真实样本（month=4, half=1, 无 turn 字段）
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

        // 没有 ramen 对象，但 chara 数据完整，应该能推导出回合
        assert!(result.is_ok(), "reconcile 应该成功: {:?}", result.err());
        let state = result.unwrap();

        // 总属性 = 1200+301+437+362+280 = 2580，应该估算为第2年
        // turn = (2-1)*24 + (4-1)*2 + 1 = 24 + 6 + 1 = 31
        assert_eq!(state.turn, 31, "从 month=4 half=1 + 属性2580 推导回合");
        assert_eq!(state.confidence, Confidence::Medium);
        assert!(
            state
                .warnings
                .iter()
                .any(|w| w.contains("估算年份")),
            "应该有年份估算的 warning"
        );
        assert_eq!(state.stats.speed, 1200);
        assert_eq!(state.stats.motivation, 5);
        assert!(!state.ramen.has_region_data);
    }

    /// hlpatch 直读 turn：1-based（与游戏 UI「第N回合」一致），无 warning、High 置信。
    /// AI 内部回合由 inject_state 做 -1 转换（31 → 0-based 30）。
    #[test]
    fn test_direct_turn_from_hlpatch_is_ui_one_based() {
        let json = r#"{
            "scenario": "Ramen",
            "turn": 31,
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

        assert_eq!(state.turn, 31, "直读第31回合（与游戏 UI 一致）");
        assert_eq!(state.turn_source, "direct");
        assert_eq!(state.confidence, Confidence::High, "直读且无校正 → High");
        assert!(state.warnings.is_empty(), "直读回合不应产生估算 warning");
    }

    /// turn=0 按第1回合处理（内部回合 0，两种口径一致）；
    /// turn 缺失时才走 month/half 推导。
    #[test]
    fn test_turn_zero_is_first_turn_and_missing_turn_falls_back() {
        let zero: HlpatchSummary =
            serde_json::from_str(r#"{"scenario":"Ramen","turn":0,"chara":{"scenario_id":14,"speed":100}}"#)
                .unwrap();
        let s0 = reconcile(&zero).unwrap();
        assert_eq!(s0.turn, 1, "turn=0 → 第1回合");
        assert_eq!(s0.turn_source, "direct");

        let missing: HlpatchSummary =
            serde_json::from_str(r#"{"scenario":"Ramen","chara":{"scenario_id":14,"speed":100}}"#)
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

        // turn 直接可用
        assert_eq!(state.turn, 10);
        assert_eq!(state.turn_source, "direct");
        assert_eq!(state.confidence, Confidence::Medium); // 有 feeling_slot 近似 warning

        // sozai
        assert_eq!(state.ramen.feeling_stock, [3, 2, 1]);

        // acquisition_gauges 近似转换
        // remaining=2 → slot=7-2=5
        // remaining=0 → slot=7-0=7，但 clamp 到 6
        // remaining=5 → slot=7-5=2
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
}
