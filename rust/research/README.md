# 历史研究代码

这里保存迁移前的完整 Rust 搜索、GA 和离线优化代码及其锁定依赖。它是独立 Cargo workspace，仍锁定原来的 fork。生产 JNI 不编译、调用或读取这些策略。

正式产品使用 `rust/src/lib.rs` → `umaai_runtime`，只接受完整真实状态。历史 `fast_forward`、固定卡组和评分文本解析不能作为生产兜底重新接回。

旧实验可在准备匹配版本的数据后，以 `cargo run --release --manifest-path rust/research/Cargo.toml --bin ramen_batch` 等入口复现。历史分数不代表当前手机策略性能。

`legacy-cloud/` 保存已退出产品的 Docker 文件。其旧服务器接口未完成，不是可部署服务；该目录仅用于历史追溯。
