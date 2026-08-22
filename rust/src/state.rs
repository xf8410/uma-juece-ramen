//! 运行时状态模型 — 从 hlpatch JSON 快照反序列化。
//!
//! 字段与上游 `RamenGame` 内部状态一一对应，
//! 由 [`crate::inject::inject_state`] 写入游戏实例。

use serde::Deserialize;

#[derive(Deserialize)]
pub struct RuntimeState {
    pub turn: i32,
    #[serde(default)]
    pub stats: Option<RuntimeStats>,
    #[serde(default)]
    pub ramen: Option<RuntimeRamen>,
    #[serde(default)]
    pub trainings: Vec<serde_json::Value>,
}

#[derive(Deserialize)]
pub struct RuntimeStats {
    #[serde(default)] pub speed: i32,
    #[serde(default)] pub stamina: i32,
    #[serde(default)] pub power: i32,
    #[serde(default)] pub guts: i32,
    #[serde(default)] pub wiz: i32,
    #[serde(default)] pub skill_point: i32,
    #[serde(default)] pub vital: i32,
    #[serde(default)] pub max_vital: i32,
    /// 干劲：hlpatch 可能返回整数或字符串 "Best"/"Good"/"Normal"/"Bad"/"Worst"
    /// 上游约定：1=绝不调, 2=不调, 3=普通, 4=好调, 5=绝好调（越大越好）
    #[serde(default, deserialize_with = "deserialize_motivation")]
    pub motivation: i32,
}

#[derive(Deserialize)]
pub struct RuntimeRamen {
    #[serde(default)] pub checkpoint_pt: i32,
    #[serde(default)] pub sozai: Vec<i32>,
    #[serde(default)] pub special_feeling_num: i32,
    #[serde(default)] pub selected_region_ids: Vec<i32>,
    #[serde(default)] pub acquisition_gauges: Vec<i32>,
}

/// 接受整数或字符串，映射为上游约定：1=绝不调 ~ 5=绝好调（越大越好）
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
                "worst" | "絶不調" | "絶不" | "绝不调" | "绝不" => 1,
                _ => s.parse::<i32>().unwrap_or(3),
            };
            Ok(val)
        }
        _ => Ok(3),
    }
}
