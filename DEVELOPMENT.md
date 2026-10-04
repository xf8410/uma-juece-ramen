# Android 拉面杯开发与验收记录

本文件记录 2026-10-04 恢复候选的实际状态。作者交接优先阅读 [RECOVERY_HANDOFF.md](docs/RECOVERY_HANDOFF.md)；[HANDOFF.md](HANDOFF.md) 保留的是 2026-09-29 历史交接。`已实现` 不代表真实设备验收完成，合成快照测试不代表采集字段已确认。

## 基线与范围

- Android 恢复分支：`workbench/ramen-recovery-20261004`，基线为已合入 PR #27 的 `d221cc1c896c035c73a10da3baa085f10e9af8b5`。
- 公共引擎基线：`xulai1001/umaai-rs@9e154046a57dd55135257aaacf8f9d9d65cc6e42`。
- 公共引擎恢复补丁：`ca0286e644263525a6268495984070b5e745789c6e508bc024aba10a80e93792`，锁定 179 个文件。
- 采集恢复工作树：真正的 `xf8410/hlpatch@fa2c820f4847f4e7b377e846ade958e0be19e98d`。v3.28.2 生成链的 43 项输入由该仓 `recovery/baseline-source-lock.json` 锁定；锁内历史输入修订与恢复工作树基线是不同概念，不能替换成旧 mirror 交接补丁。
- 拉面杯、手机本地计算、人工操作游戏。生产策略以正式上游为准。
- 原来的 GA、近似重放和优化器移至 `rust/research`，保留原锁定依赖，不编入正式 JNI。
- 引擎新增代码尚未发布为上游提交，因此使用 `engine/source-lock.json` 锁定基线、可审查补丁及每个文件哈希。不得假称基线提交已经包含新运行层。

## 实现结构

`采集端单采样缓存 → SnapshotV2 / 已确认采集实例 → Android 接入/私有文件 → :engine Service → JNI → umaai_runtime → 结构化事件 → 浮窗与局包`

- `umaai_runtime` 提供统一协议、严格导入、会话、策略、确定性种子与取消。
- JNI 仅转换文件和事件；原生回调通过消息通道回到原 JNI 线程，随后由 Java 投递主线程。
- 一个计算任务和一个最新待处理任务；请求、快照、局、配置与引擎世代共同约束结果提交。
- 引擎全局数据与配置按进程加载一次；设置变更重启引擎。复盘复用同目录数据，不修改 cwd。
- `umaai_review` 可分析标准 ZIP 或局目录，并嵌入模板生成本地事实报告。
- V2 用 `(run_id, collector_instance_id, snapshot_id)` 绑定输入和结果。先读 capabilities，再显式确认实例；旧实例不能回流。相同盘面重复采样保留编号和首次捕获时间，诊断采样时间单独更新。V1 保留原序号规则。
- 局包使用独立持久化 `archive_seq`。源序号跳号、采集重启或中途接管产生独立运气基线；当前完整状态仍可计算。取消行不创建基线，同快照可记录不同请求/配置。原生复盘要求每个历史配置都有版本证据，不能只用最新版本代替旧记录。

恢复候选的启动路径不再启动历史后台自更新，也不自动上传或删除旧日志，不读取历史 SQLCipher flags 安装 Hook。原标记与日志保留，防止旧设置在启动后替换正在验收的候选 SO 或改变 Hook 状态；这些限制不代表未知 ABI 已得到验证。

SO `3.28.2-recovery.2` 修正了旧 `/summary` 对回合语义的错误声明：拉面 `turn/year` 设为 `null`，保留 `raw_total_turn_num`、字段来源及 `raw_field_mapping=unverified`。旧拉面 heuristic 入口返回结构化 `unavailable`，直接调用也拒绝；没有引入新的回合公式或零分建议，非拉面公式保持原样。V2 的 `display_summary.turn_observation` 保留取证信息，但不把原始值导入 `state.baseGame.turn`，也不提高 `ready`。当前 V2 仍缺真实局号、完整阶段与 `state+continuation`，不能驱动真实整局决策。

SO `3.28.2-recovery.3` 进一步修正发布锁边界：Publisher 由唯一采样 worker 持有，共享状态保存不可变 Arc；JSON 转换、深比较、字段检查、深复制和旧大对象最终析构在共享锁外进行，锁内整组交换快照、摘要与状态。进入 `booting` 时撤销当前采样的外发资格；恢复进入 `capturing` 后，在新采样完成前，HTTP、summary 和 push 不会重新暴露 boot 前的旧快照。`current()` 仅保留历史诊断用途。同内容采样仍保留编号、捕获时间和同一 Arc；ACK 仍绑定实际发送的快照。这是确定性宿主回归证实的实现缺口，不是对作者现场故障根因的认定，也没有补齐真实盘面或验证游戏安全采样边界。

## 当前硬性阻塞

1. **真实完整快照尚未获得。** 旧采集摘要不能证明局标识、阶段、所有人员/继承信息和原子捕获。新采集端如实返回 `ready:false` 和 `missing_fields`，不会填零或模拟历史。本轮不能宣称完成 P2 或真实整局辅助。
2. **开发方没有测试设备，真机验收交由仓库作者。** 尚缺目标手机、Android/游戏版本、实际装载 SO 及真机完整对局样本。执行步骤及回传要求见 [恢复交接](docs/RECOVERY_HANDOFF.md)。不能用桌面编译或 Windows JVM/JNI 验证替代 Android 真机 JNI 加载、浮窗生命周期与性能验收。
3. **正式 NN 权重未取得。** 支持模型导入和契约检查不等于模型质量验收；仍需匹配版本的正式权重、旁车、适用卡组及分发条件。
4. **正式签名和发布未执行。** 开发包使用 `.dev` 后缀。不得卸载旧应用来绕过签名或数据迁移问题。
5. **原生 ABI 尚未获得目标游戏证据。** 采集端 `verified_profiles()` 当前为空；涉及托管方法的 Hook 默认拒绝安装并返回 `unsupported_native_abi`。新候选是诊断和恢复机制交付，不代表完整嗅探已恢复。仍需完成实际采集代码，而非只补配置：完整盘面生产与桥接、真实局号及续养字段来源、经过验证的游戏线程/生命周期安全观察边界均未完成。worker 的 IL2CPP attach 和自身读锁不证明盘面原子性。`verified_profiles()` 为空、typed native thunk 尚未实现，授权接线仍未提供实际 `BuildIdentity`；取得目标游戏、Unity、Hachimi 身份和原生 ABI 证据后，还需实现 thunk 与 identity plumbing，并由作者在设备上验证原调用及宿主 Hook 共存。

**SO `.3` 构建与 collector bundle 已同步并独立验证。** 以下补丁及配套产物包含锁边界和 boot 屏障修复；此前 `.2` 包、补丁副本与验证目录保留为历史，不与 `.3` 混用。

采集配套补丁已按真正的 `hlpatch@fa2c820f4847f4e7b377e846ade958e0be19e98d` 重新生成：102 个受影响文件，补丁 SHA256 `49a1f16b9cc5f6fd1f874fb121bda8e62e11c984be43122559ea525a7b0b1b52`。在启用 `core.autocrlf=true` 的独立 Windows clone 中通过现有工具的 `--check`、`--apply` 及逐文件哈希验证，未改 SO 真实 index。恢复构建直接使用受检源码，不在构建阶段运行旧累计生成器；不能用旧 mirror 的 `721c086` 应用命令安装这个新 bundle。最终候选 SO 的哈希及来源以配套构建清单为准。

## 恢复候选的已执行验证

- 公共 runtime：31 项通过，2 项缺真实采集样本的测试显式忽略；含同回合序号跳号后继续计算、独立运气基线和新会话重算不误报。
- review：54 项通过；JNI 的 8 项单元测试、1 项合成快照流式集成是直接 Rust 宿主调用证据，当时没有启动 JVM。
- SO `.3` 聚焦 Release 验证为 SO bridge 13 项、portable observation 13 项通过。三项新增 bridge 回归在旧源码上均失败；线程本地分配/释放探针确认转换、读取深复制和两个指定旧缓冲区最终释放时可取得共享锁，通道阻塞测试确认 boot 恢复窗口不外发旧状态。新增 portable 回归确认相同发布复用 Arc、旧读者快照不变及迟到 ticket 拒绝。使用合成数据和宿主线程，没有运行游戏 Hook；未用耗时阈值代替锁边界证据。
- SO `.3` 的 `run_checks` 通过 14 项基础设施测试、19 个生产函数／模块宿主测试入口和 13 项 portable 测试，包含 summary/heuristic、锁边界和 boot 屏障回归。bridge 的 13 项内层测试已包含在对应宿主入口中，不重复相加。SO `.3` ARM64 完整 Release 构建通过；保留 107 项编译警告，限制见配套构建日志，不能当作原生安全性验收。
- 严格 ARM64 JNI 与 APK 构建通过，1 个 ELF、8 份数据及 ELF/ZIP 16 KB 对齐和源码对应关系校验通过。最终清理 App 构建输出后重新运行严格构建，Java 单元测试实际执行 78 项、0 失败。此前增量构建复用缓存的记录属于中间验证。
- 最终本地开发 APK 为 28,513,923 字节，SHA256：`94ec61fea906751831d3dd5d3c724b66f5ecb42cb4d666b91e5f35dbd9d8a121`。这是当前未提交恢复工作树的产物；不是远端发布或作者已经安装的证明。

新增真实 JVM → JNI 验证：Windows x64、Temurin JDK 17.0.16+8，直接编译未修改的 `UmaNativeBridge.java`，再由 `java -Xcheck:jni` 加载该类和匹配当前 `9e15404+ca0286e64426` 引擎的宿主 DLL。13 项断言通过，收到 `started/decision/completed` 共 3 次真实 Java 回调；覆盖中文及空格路径、初始化、实例确认、缺失/不完整输入拒绝、提前取消、合成 MCTS 搜索与复盘版本拒绝。未出现 `-Xcheck:jni` 警告或 fatal 诊断。证据位于本地 ignored `.tools/jvm-jni-smoke-20261004/` 的 `README.txt`、`commands.jsonl`、`smoke.log`、`result.json`、`provenance.json`；此目录不代表已进入 Git 或已分发。仅使用合成状态和 MCTS（`onnx=false`），不代表 Android ART、ARM64、Binder、浮窗或真机验收。原 Java 类、引擎锁和 APK 哈希均未变化。

## 阶段清单

- P0：固定工具链、Wrapper、源/数据锁、严格 CI、16 KB 与 APK 产物验证已实现；具体构建证据见本轮验证记录。
- P1：公共运行层、PC 复用入口、JNI 及确定性对照入口已实现；真实 PC/ARM64 同快照对照待真机。
- P2：新端点、同缓存推送、已知字段转换和缺失报告已实现；完整实时采集与语义验收阻塞。
- P3：公共层补显式事件、超级拉面和阶段处理；全部真实阶段覆盖仍依赖真实快照。
- P4：独立引擎、取消、过期保护、异常恢复已实现；设备生命周期验收待执行。
- P5：连接、浮窗、设置、局记录入口已实现；视觉与设备操作验收待执行。
- P6：标准局包、本地复盘、报告展示与导出已接入；真实完整/中断局及跨端 digest 对照待执行。
- P7：NN 三模式与模型契约、版本数据管理已接入；正式模型、10 局、2 小时稳定性与发布验收未完成。

## 复现与检查

工具版本见 `toolchains.json`。设置当前命令进程的 `JAVA_HOME`、`ANDROID_HOME`，无需修改系统配置。

```powershell
# 从锁定基线和补丁重建引擎，并校验全部文件。
python scripts/prepare_engine.py --source <umaai-rs-checkout> --package-data
# 严格 ARM64 原生构建与 APK 检查。
python scripts/build_android.py --source <umaai-rs-checkout>
# 直接 Rust 宿主测试；不启动 JVM，不能作为真实设备验证。
cargo test --release --locked --manifest-path rust/Cargo.toml --no-default-features
# Python 构建检查回归。
python -m unittest discover -s scripts/tests -v
```

仅在明确升级公共引擎源码时使用 capture；不能在正式构建中自动吸收任意工作树改动：

```powershell
python scripts/prepare_engine.py --capture --source <umaai-rs-checkout> --package-data
```

capture 后必须审阅补丁与锁文件，再重新运行原生构建、Java 测试和 APK 验证。源码在编译期间改变时，构建脚本拒绝把产物标记为匹配版本。

恢复协议改造后的 `assets/build-manifest.json` 使用 `protocol_version: 2` 与 `supported_snapshot_schema_versions: [1, 2]`，分别记录首选快照协议和兼容协议。`schema_version: 1` 仅表示产物清单自身的格式，不能用来判断快照是否支持 V2。严格打包检查会拒绝缺少或错误的协议声明。运行时先读取采集端 `/api/ai/ramen/capabilities`，再按已确认的采集进程获取 `/api/ai/ramen/v2/snapshot`；V1 保留原来的序号语义，不允许把进程重启后的 V2 序号归零伪装成 V1。

## 真机验收条件

固定设备及游戏/采集/引擎/数据/模型版本，记录同一真实局面、种子、预算和并行度。比较阶段、合法动作、候选顺序、动作与评分容差。覆盖开局、跨年、事件、超级拉面、断连、切局、取消、进程退出、旧包升级和损坏数据。

性能目标仍为目标而非实测：普通 MCTS P95 ≤20 秒、地区 ≤30 秒、热 NN ≤1 秒；新状态显示 ≤200 ms、取消释放 ≤1 秒、应用加引擎 PSS ≤1 GB。须完成 10 局和连续 2 小时真机测试，并验证 ARM64 4 KB/16 KB 环境。

无法满足时记录原因并修复，不能静默降低预算或隐藏未验证状态。
