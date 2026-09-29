# 公共引擎与采集契约

## 生产基线

生产 JNI 已切换到 `xulai1001/umaai-rs` 公共运行层。完整基线提交、增量补丁 SHA256 和导出文件 SHA256 以 `engine/source-lock.json` 为唯一来源。

当前新增公共层尚未发布为远端提交，因此 CI 从固定基线应用 `engine/runtime.patch`，校验每个文件后重建 `.engine-source`。不得依赖动态 master，不得只改一个 revision 而遗漏数据或补丁。

历史 fork `53227d4`、GA 覆盖与近似重放保存在 `rust/research`，不进入生产 APK 的决策路径。旧分数和旧“零 API 破坏”结论不适用于当前版本。

## 输入契约

唯一生产输入为 `RamenSnapshotV1`：

- `schema_version=1`；真实 `run_id`、单调 `snapshot_id`、明确 `stage`。
- `state.baseGame`、`state.ramen` 与公共协议一致；内部回合为 0–77。
- `continuation` 提供吃面次数、真实 RMJ 结果、训练等级加成、待执行面与隐藏风味目标、继承附加值。
- 事件阶段需要真实选项及效果。缺失不能用零值、固定卡组、模拟过程或空数组掩盖。
- 采集版本、游戏版本、时间、完整性与来源一并保留。
- `ready=false`、`capture_coherence=unverified` 或非空 `missing_fields` 禁止搜索。

`GET /api/ai/ramen/v1/snapshot` 与推送复用同一份缓存。旧 `/summary` 只展示；其回合映射、人员 ID、槽剩余值等有未确认语义，不可直接当作完整 PC 状态。

## 输出与配置

复用 `DecisionInfo`、`GameView` 与结构化动作，停止解析评分文本。每条事件关联局、快照、请求、配置和引擎版本。JNI 的流事件与最终 events 数组使用相同 `event_seq`，用于幂等记录。

默认 MCTS 8192，其他策略配置从锁定的 `default_config.toml` 读取。用户明确更改预算才切换。生产策略不注入旧 GA；fallback 与 rollout 使用同一正式配置。

模型必须携带旁车、适用卡组及兼容信息，并验证真实 ONNX 图。没有兼容模型时不能启用 NN。

## 更新流程

1. 修改公共引擎并完成 PC/公共层相关回归。
2. 显式 capture 产生新补丁和锁文件，审阅全部差异。
3. 重新构建 JNI 和 APK，检查 `.so`、ELF/ZIP 16 KB 对齐与版本清单。
4. 执行同输入、同种子、同预算、同并行度的两端对照。
5. 完成真机阶段、长局和升级验收后再发布。

真实采集、正式模型和设备验证的未完成项见 [DEVELOPMENT.md](DEVELOPMENT.md)。
