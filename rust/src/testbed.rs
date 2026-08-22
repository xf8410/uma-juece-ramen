//! 共享测试台 — 各命令行工具共用的固定测试配置。
//!
//! 同一角色、同一卡组，保证 batch / optimize / benchmark
//! 之间的结果可以直接对比。

use umasim::game::InheritInfo;

/// 测试角色（客服测试用的固定马娘）
pub const TEST_UMA: u32 = 102601;

/// 标准卡组：5 张普通 SSR + 固定友人
pub const TEST_DECK: [u32; 6] = [302424, 302894, 303044, 302924, 303024, 303054];

/// 固定友人卡（卡组第 6 张）
pub const FRIEND: u32 = 303054;

/// 标准继承因子
pub const TEST_INHERIT: InheritInfo = InheritInfo {
    blue_count: [15, 3, 0, 0, 0],
    extra_count: [0, 30, 0, 0, 30, 30],
};
