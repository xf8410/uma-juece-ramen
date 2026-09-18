//! GA 最优基因组覆盖层装载。
//!
//! Phase 1 接线（本提交）：
//! - `RamenMctsTrainer::fallback`（未搜阶段 / 事件选项 / 隐藏风味 / 单候选）
//!   与手写兜底路径换用覆盖层策略——`fallback` 是 pub 字段，无需改上游；
//! - MCTS rollout 仍走 `FlatSearchGame` 默认 rollout 策略（该注入点属
//!   Phase 2，需上游 FlatSearch 侧改造）。
//!
//! 两个通道：
//! - `best_genome_ga_freeze.toml` = best_genome Some 值 ∪ GA 当时 preset 基线
//!   （batch_v5/preset_baseline.toml），即 GA 评估时的完整参数面（默认启用）；
//! - `best_genome.toml` = 纯 Some 子集覆盖层（None=保留当前 preset），供 A/B 对照。
//!
//! 覆盖层格式与 `umasim::trainer::local_ramen_trainer::parse_override_toml`
//! （genetic_optimizer `override_to_toml` 输出的 best_genome.toml 同格式）严格
//! 一致；未知键 / 类型不匹配直接 Err → 回退当前 preset，绝不带病上岗。
//! 零差异保证：全 None 覆盖层与 `new()` 逐位一致（上游 GA 守门测试）。

use std::sync::OnceLock;

use umasim::trainer::local_ramen_trainer::parse_override_toml;
use umasim::trainer::{ParamOverride, RecommendedRamenTrainer};

/// 纯 GA 覆盖层（Some 子集；None=保留当前 preset）——A/B 对照用。
pub const BEST_GENOME_TOML: &str = include_str!("../genome/best_genome.toml");
/// GA 冻结配置（genome ∪ 当时 preset 基线）——默认启用通道。
pub const GA_FREEZE_TOML: &str = include_str!("../genome/best_genome_ga_freeze.toml");
/// 当前启用通道名（nativeVersion 暴露，供浮窗/日志观测）。
pub const SOURCE_NAME: &str = "ga_freeze_v5";

static OVERRIDE: OnceLock<Option<ParamOverride>> = OnceLock::new();

/// 解析覆盖层 TOML（公开给 batch_genome 做 A/B/C 对照）。
pub fn parse(toml: &str) -> Result<ParamOverride, String> {
    parse_override_toml(toml)
}

/// 当前启用的覆盖层（解析失败 → None → 一切回退当前 preset）。
pub fn override_set() -> Option<&'static ParamOverride> {
    OVERRIDE
        .get_or_init(|| match parse(GA_FREEZE_TOML) {
            Ok(ov) => {
                eprintln!("genome: {SOURCE_NAME} 覆盖层装载成功");
                Some(ov)
            }
            Err(e) => {
                eprintln!("genome: {SOURCE_NAME} 解析失败，回退当前 preset: {e}");
                None
            }
        })
        .as_ref()
}

/// 覆盖层是否生效（nativeVersion 观测点）。
pub fn enabled() -> bool {
    override_set().is_some()
}

/// 推荐/兜底策略统一入口：覆盖层生效时返回覆盖版，否则原样 new()。
pub fn recommended_trainer() -> RecommendedRamenTrainer {
    match override_set() {
        Some(ov) => RecommendedRamenTrainer::with_overrides(ov),
        None => RecommendedRamenTrainer::new(),
    }
}
