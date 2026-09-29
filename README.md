# uma-juece-ramen

Android 本地拉面杯决策辅助，使用与 PC umaai-rs 共用的 Rust 运行层。玩家根据建议操作游戏。

**当前为开发版本，尚未完成真实对局验收。** 新采集协议会拒绝缺失关键状态的输入；现有旧 SO 摘要不足以驱动完整决策。正式 NN 权重和目标设备验证也尚未完成。详见 [开发与验收记录](DEVELOPMENT.md)。

仓库作者接手请先读 [交接报告](HANDOFF.md)，其中包含跨仓补丁、复现命令、真实采集缺口和真机验收清单。

## 架构

采集端输出版本化快照 → Android 接入 → 独立 `:engine` 进程 → JNI → `umaai_runtime` → 浮窗、局记录和本地复盘。

- 只支持拉面杯，计算在手机完成。
- 生产策略来自正式上游，支持 MCTS、MCTS 加 NN 参考和 NN 模式；NN 需要兼容模型。
- 完整状态经严格校验后进入引擎，不使用模拟历史或固定卡组填补观测。
- 请求支持取消及过期结果保护；原生失败不回退到独立 Java 评分。
- 保留完整快照和阶段事件，导出标准局包，本地生成复盘报告。
- 历史 GA 和优化实验位于 [rust/research](rust/research/README.md)，不参与正式决策。

## 构建

版本固定在 `toolchains.json`。使用项目提供的 Gradle Wrapper；需要 JDK 17、对应 Android SDK/NDK、Rust 与 cargo-ndk。

```powershell
python scripts/prepare_engine.py --source <umaai-rs-checkout> --package-data
python scripts/build_android.py --source <umaai-rs-checkout>
```

公共引擎尚未作为新提交发布，因此构建使用 `engine/source-lock.json` 中的固定上游提交与 `engine/runtime.patch` 重建。代码、数据、配置和原生库必须匹配，缺少原生库或校验失败会阻止打包。

开发 APK 使用 `com.umaai.assistant.dev`，可与旧正式包并存。正式包仍为 `com.umaai.assistant`，升级需要同签名和经过验证的数据迁移。

## 状态来源

- 新协议：`GET http://127.0.0.1:18765/api/ai/ramen/v1/snapshot`，或向应用本机 `18766/data` 推送同一快照。
- 旧 `/summary` 仅供展示，不作为完整搜索输入。
- `ready:false`、关键字段缺失或采集一致性未确认时显示原因，不给出伪造的建议。

历史来源见 [SOURCE_SNAPSHOT.md](SOURCE_SNAPSHOT.md)。引擎版本与协议约束见 [UPSTREAM.md](UPSTREAM.md)。
