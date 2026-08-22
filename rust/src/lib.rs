//! uma-jni — umaai-rs 拉面杯的 Android/命令行桥接库。
//!
//! 模块划分：
//! - [`state`]        运行时状态模型（hlpatch JSON 快照）
//! - [`inject`]       状态注入（快照 → RamenGame）
//! - [`search`]       平面蒙特卡洛搜索核心
//! - [`ramen_strategy`] 手写策略
//! - [`testbed`]      各工具共享的固定测试配置
//! - `jni_exports`    JNI 边界（仅 jni-support feature，Android 入口）

pub mod ramen_strategy;
pub mod state;
pub mod inject;
pub mod search;
pub mod testbed;

#[cfg(feature = "jni-support")]
mod jni_exports;

pub use inject::inject_state;
pub use search::{ActionScore, SearchConfig, SearchResult, run_flat_search};
pub use state::{RuntimeRamen, RuntimeState, RuntimeStats};
