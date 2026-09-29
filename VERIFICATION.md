# 本轮实现验证记录

日期：2026-09-29。范围：Android 项目、umaai-rs 公共运行层、采集端观察协议。本文件保留实现阶段的历史证据，当时尚未提交或推送。后续交接分支说明见 [HANDOFF.md](HANDOFF.md)；没有部署 SO、安装 APK 或操作真实游戏。

## 开发产物

- APK：`dist/uma-juece-ramen-0.5.0-dev-715a837f0bc7-arm64.apk`
- 包名：`com.umaai.assistant.dev`；versionCode 8；versionName `0.5.0-dev-debug`。
- 大小：28,430,036 字节。
- SHA256：`94f137d04788f71a73504a6933a4b9a2a46dc3df671e69e3bb4f29743f4d8a67`。
- 引擎：`9e154046a57dd55135257aaacf8f9d9d65cc6e42+0efac668e6c2`。
- Android 源码指纹：`715a837f0bc7b5213dc5dd8e7e5f683f0628e991fc50d83f73b66fb9a75f0de6`。
- `dist/build-manifest.json`、`dist/source-lock.json`、`dist/SHA256SUMS` 保存配套来源。

产物为开发版，不代表完整方案已经验收。当前旧采集器缺关键真实状态，会显示缺项并拒绝搜索。

## 已通过的检查

1. **公共层 CI 原命令**：`cargo test --release --locked -p umaai_runtime -p umaai_review`，运行层 29 passed / 2 ignored，复盘 48 passed，其余 bin/doc 测试通过。主代理重新运行确认退出码 0。
2. **ONNX 与 PC 回归**：额外 4 项装配/错误路径、6 项 PC 共用编排与合成模型测试通过。NN hint 与 MCTS 在两个固定种子整局中的终局分、五维、地区一致。这里使用合成测试网络，不证明正式权重质量。
3. **JNI 宿主测试**：`cargo test --release --locked --manifest-path rust/Cargo.toml --no-default-features`，6 项请求/取消/版本测试和 1 项文件→会话→流式结果集成测试通过。集成测试明确使用合成状态。
4. **采集转换**：`cargo test --release --locked --manifest-path ramen_observation/Cargo.toml`，7 项通过，含生产者否决保留、缺失字段、身份/回合不推测、序号去重和错误输入。
5. **Android Java/XML**：63 项单元测试通过，无失败或跳过；Lint 0 错误、41 条警告。警告主要为既有资源、中文硬编码、locale 和样式提示，未隐藏错误。
6. **构建契约**：9 项 Python 测试通过，覆盖源快照重建、未跟踪源码与已有改动保护、错误 ABI、截断 ELF、4 KB ELF/ZIP 拒绝、16 KB 正例和显式模拟器 ABI。
7. **最终完整构建**：`python scripts/build_android.py --skip-prepare` 退出码 0，包含默认 ONNX 的 ARM64 Release JNI、Java 测试和 Debug APK 打包，未绕过 `verifyPackagingInputs`。
8. **最终产物复核**：`verify_artifact.py --inputs --apk ...` 验证原生库、8 份数据、源码/引擎/数据清单，以及 ELF 和 APK ZIP 的 16 KB 对齐；`apksigner verify --verbose` 验证 v2 签名成功；`aapt2` 确认 `.dev` 包名和 ARM64 架构。
9. **采集 SO**：对受检 checkout 直接执行 ARM64 Release `cargo ndk ... --locked --lib` 成功；最终 SO 4,698,344 字节，ELF/ARM64/16 KB 检查通过。未执行旧生成器，未部署。编译仍有 141 条既有大文件相关警告。

日志在 `.tools/logs/`，Android 测试和 Lint 报告在 `app/build/reports/`。源码导出文件哈希已与实际上游工作树比对一致。

## 审查中修复的问题

- 原生内部仍按 cwd 读取配置，导致嵌入调用失败：改为显式已初始化配置，文件到引擎集成测试通过。
- 复盘错误地用新版本数据重算旧局：JNI 在分析前核对引擎版本和数据清单 SHA，未知或不匹配时拒绝。
- 中断局末份快照被当作终局：显式完整性与终局证据控制评分标记。
- 复盘继承基线读取当前默认因子：V1 使用本局 `runtime_continuation`，缺失不回填。
- 局包漏运气字段、同一流事件重复落盘、配置变化后模型来源未更新：补齐 CSV、事件序号去重和不可变配置历史。
- INIT 崩溃无限重连：独立初始化重试预算，连续两次后停止自动重试。
- 不完整新局清空屏障后接受迟到旧局：独立保留采集水位与退役局集合。
- 新配置先发布后登记导致空版本污染：IO 队列先完成真实版本登记再发布，未登记代不能写局包，增加交错顺序回归。
- 整库测试争用全局配置：仅隔离测试进程，生产严格配置世代检查不放宽。

## 未完成的验收

- 两项依赖真实 PC 快照目录的测试明确标为 ignored；显式运行时因缺目录退出 101，不算通过。
- 没有取得可验证的完整真实 Android 快照，P2 字段语义、原子捕获和真实全阶段对照未完成。
- 正式 NN 权重、旁车、适用卡组与模型质量未验收。APK 中没有冒充正式模型的合成权重。
- `adb devices -l` 最后检查没有在线设备。未验证 JNI 真机加载、浮窗操作、1 秒取消、MCTS/NN 耗时、PSS、温升、10 局或 2 小时稳定性。
- x86_64 构建入口已支持，但本轮没有构建或运行模拟器 APK。
- 没有宣称远端 GitHub Actions 已运行通过。采集端历史生成流水线、正式签名、升级迁移及发布回退仍需后续验收。

后续工作和硬性准入条件见 [DEVELOPMENT.md](DEVELOPMENT.md)。
