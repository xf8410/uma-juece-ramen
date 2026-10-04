# 历史交接报告：Android 拉面杯客户端（2026-09-29）

> **恢复候选请先读 [docs/RECOVERY_HANDOFF.md](docs/RECOVERY_HANDOFF.md)。** 下文保留 2026-09-29 交接时的分支、远端 CI、旧 mirror 补丁和构建哈希，仅供追溯，不是 2026-10-04 恢复候选的安装或补丁指引。新候选基于 Android `d221cc1`、真正的 `hlpatch@fa2c820`、独立 SnapshotV2 和 179 文件公共引擎锁。**同路径 collector bundle 已更换为经过独立应用验证的 hlpatch 恢复候选；不能按下文的 `so-history-backup@721c086` 命令应用。** 新补丁基线、摘要及操作步骤以恢复交接文档为准。当前同路径 bundle 已同步 SO 已提交的 CI 修复 `2037c5f35d032eab76015536d2e9596f5aca7e6a`：102 个文件，补丁 SHA256 `795f98d8520be125810be823beddc58f6c52b8b52a4238a89d2d44429a5ab0c2`，已通过独立 Windows clone 的检查、应用和文件哈希验证。该提交只比 `6d0c1bf` 增加 6 行 CI 依赖预取。旧 `.3` SO／ZIP／manifest 仍来自 `6d0c1bf` 冻结树；新 CI 构建不能沿用其 `c400…` 指纹。旧包与日志全部保留，公共引擎锁未改。

**2026-10-04 更新：** SO `3.28.2-recovery.2` 修正了旧 `/summary` 对回合语义的错误声明：拉面 `turn/year` 设为 `null`，保留 `raw_total_turn_num`、字段来源及 `raw_field_mapping=unverified`。旧拉面 heuristic 入口返回结构化 `unavailable`，直接调用也拒绝；没有引入新的回合公式或零分建议，非拉面公式保持原样。V2 的 `display_summary.turn_observation` 保留取证信息，但不把原始值导入 `state.baseGame.turn`，也不提高 `ready`。当前 V2 仍缺真实局号、完整阶段与 `state+continuation`，不能驱动真实整局决策。

SO `3.28.2-recovery.3` 进一步修正发布锁边界：Publisher 由唯一采样 worker 持有，共享状态保存不可变 Arc；JSON 转换、深比较、字段检查、深复制和旧大对象最终析构在共享锁外进行，锁内整组交换快照、摘要与状态。进入 `booting` 时撤销当前采样的外发资格；恢复进入 `capturing` 后，在新采样完成前，HTTP、summary 和 push 不会重新暴露 boot 前的旧快照。`current()` 仅保留历史诊断用途。同内容采样仍保留编号、捕获时间和同一 Arc；ACK 仍绑定实际发送的快照。这是确定性宿主回归证实的实现缺口，不是对作者现场故障根因的认定，也没有补齐真实盘面或验证游戏安全采样边界。

SO `.3` 聚焦 Release 验证为 SO bridge 13 项、portable observation 13 项通过。三项新增 bridge 回归在旧源码上均失败；线程本地分配/释放探针确认转换、读取深复制和两个指定旧缓冲区最终释放时可取得共享锁，通道阻塞测试确认 boot 恢复窗口不外发旧状态。新增 portable 回归确认相同发布复用 Arc、旧读者快照不变及迟到 ticket 拒绝。使用合成数据和宿主线程，没有运行游戏 Hook；未用耗时阈值代替锁边界证据。 `.3` 本轮统一检查为 14 项基础设施、19 个宿主测试入口及 13 项 portable 测试通过；bridge 内层 13 项不与包装入口重复相加。`.3` ARM64 完整 Release 构建通过，保留 107 项警告，不代表原生安全性验收。

原 8 + 1 项 JNI 测试是直接 Rust 宿主调用。新增真实 JVM → JNI 验证：Windows x64、Temurin JDK 17.0.16+8，直接编译未修改的 `UmaNativeBridge.java`，再由 `java -Xcheck:jni` 加载该类和匹配当前 `9e15404+ca0286e64426` 引擎的宿主 DLL。13 项断言通过，收到 `started/decision/completed` 共 3 次真实 Java 回调；覆盖中文及空格路径、初始化、实例确认、缺失/不完整输入拒绝、提前取消、合成 MCTS 搜索与复盘版本拒绝。未出现 `-Xcheck:jni` 警告或 fatal 诊断。证据位于本地 ignored `.tools/jvm-jni-smoke-20261004/` 的 `README.txt`、`commands.jsonl`、`smoke.log`、`result.json`、`provenance.json`；此目录不代表已进入 Git 或已分发。仅使用合成状态和 MCTS（`onnx=false`），不代表 Android ART、ARM64、Binder、浮窗或真机验收。原 Java 类、引擎锁和 APK 哈希均未变化。

仍需完成实际采集代码，而非只补配置：完整盘面生产与桥接、真实局号及续养字段来源、经过验证的游戏线程/生命周期安全观察边界均未完成。worker 的 IL2CPP attach 和自身读锁不证明盘面原子性。`verified_profiles()` 为空、typed native thunk 尚未实现，授权接线仍未提供实际 `BuildIdentity`；取得目标游戏、Unity、Hachimi 身份和原生 ABI 证据后，还需实现 thunk 与 identity plumbing，并由作者在设备上验证原调用及宿主 Hook 共存。

以下继续保留历史交接正文。

交接日期：2026-09-29。分支：`workbench/android-runtime-handoff-20260929`。

交付仓库：[Vinzelles/uma-juece-ramen](https://github.com/Vinzelles/uma-juece-ramen/tree/workbench/android-runtime-handoff-20260929)。原仓库 `xf8410/uma-juece-ramen` 对当前账号仅开放读取，直接推送返回 403，因此使用同源 fork 交付。作者可从该分支取回改动。

**当前交付是可构建、已做桌面回归的开发版本。真实完整采集尚未接通，也没有真机验收结论。** 本次开发方没有测试设备，后续由仓库作者在目标游戏环境验证。

最重要的未完成项是采集端：`from_legacy_summary` 仍明确输出 `run_id=null`、`stage=unknown`、`capture_coherence=unverified` 和空 `continuation`。安装 APK 或替换为本次编译的 SO，都不会自动补齐这些字段。接手后应先实现可靠的完整快照，再验证真实推荐。

## 1. 本分支交付内容

- Android：独立 `:engine` 进程、JNI、最新任务调度、取消和过期结果保护、浮窗、设置、模型管理、局记录与本地复盘入口。
- 公共引擎：`engine/runtime.patch` 和 `engine/source-lock.json`。从固定上游提交重建 `umaai_runtime`、PC 接线和复盘库，不需要开发方的本地工作区。
- 采集端：`docs/handoff/collector.patch`、`docs/handoff/collector-source-lock.json` 和 `scripts/apply_collector_handoff.py`。包括观察协议模块、新端点、推送接线、测试及字段缺口说明。
- 构建：官方 Gradle Wrapper、固定工具版本、严格原生/数据校验、ELF 与 APK ZIP 的 16 KB 对齐检查，以及开发 APK 的 CI 产物步骤。
- 文档：[开发状态](DEVELOPMENT.md)、[实现阶段验证记录](VERIFICATION.md)、[引擎契约](UPSTREAM.md)、[构建说明](scripts/README.md)。

基线固定为：

- Android：`xf8410/uma-juece-ramen@ccbf9786f314ad09f91bd14282ee09c8f90a284e`。
- 引擎：`xulai1001/umaai-rs@9e154046a57dd55135257aaacf8f9d9d65cc6e42`，增量补丁 SHA256 为 `0efac668e6c25fd6f9fabf507e9a7123702566705b2d8b51084689b2a205732a`。
- 采集端：`xf8410/so-history-backup@721c086220ea081523229d88623de5abddd97eaa`。补丁摘要和逐文件校验值以随附锁文件为准。

此次只推送 Android 交接分支。没有直接推送另外两个仓库、替换作者的 SO、合并主线或发布正式版本。公共引擎和采集改动以补丁交付，作者可先检查，再迁入各自维护分支。

原 GA、近似重放、独立评分与旧云端文件保留在研究代码中，正式决策不调用它们。缺状态时拒绝搜索，不能通过恢复这些兜底让验收“通过”。

## 2. 当前证据及其边界

以下是实现阶段已执行的本地检查；交接后的远端 CI 状态需在本分支 Actions 中另行确认。

- 公共运行层：29 项通过，2 项真实快照测试明确忽略。
- 复盘：48 项通过，包括目录/ZIP 一致性、中断局标记、真实继承参数和本地报告。
- ONNX 装配与 PC 共用编排：额外 4 + 6 项通过。使用合成模型，验证接口、动作和随机流；不能据此判断正式模型质量。
- JNI：6 项单元测试和 1 项文件到流式决策的集成测试通过，输入为合成状态。
- 采集观察模块：7 项通过，验证缺失与生产者否决信息不会被抹掉，以及去重和字段转换。
- Android：63 项单元测试通过；Lint 0 错误、41 条警告。
- 原生与打包：ARM64 JNI（包含 ONNX 后端）和采集 SO 均编译成功；开发 APK 签名、原生库及 ELF/ZIP 16 KB 对齐检查通过。

以上不覆盖游戏内存布局、Hook 生效、快照原子性、真实全阶段推荐、Binder 真机行为、耗时、PSS、温升、10 局或两小时稳定性。x86_64 构建入口存在，本轮没有模拟器运行证据。正式 NN 模型未随仓库交付。

`VERIFICATION.md` 中的 APK 路径和 SHA 是实现阶段的历史证据。`dist/`、`.tools/`、构建日志和本机 SDK 不进入 Git；作者应从本分支重新构建，或下载本分支成功 CI 的 `ramen-arm64-dev-verified` artifact。不能用一个本地路径当作作者已经取得了安装包。

本次交接提交前另行复核：16 项 Python 测试通过；引擎锁定的 178 个文件与受检源码一致；采集补丁在独立基线检出中完成检查、应用和 10 个文件的哈希核对。`python scripts/build_android.py --skip-prepare` 再次成功，原生构建、数据及 ELF/ZIP 16 KB 校验通过；Java 测试任务复用未改变输入的 Gradle 缓存。此次本地 APK 的 SHA256 为 `75a3875fb95dcba5c4ba6457047e575c0a315dce011130b62391587f702cd43a`，它仍是提交前开发产物，分支 CI 会生成带实际提交标识的新产物。

创建 PR 前复查发现，[首次远端 CI](https://github.com/Vinzelles/uma-juece-ramen/actions/runs/36573785405) 在 SDK 初始化阶段失败：`sdkmanager` 找不到旧 `tools` 包，后续项目测试均未执行。工作流已显式只安装 `platform-tools`，固定版本 SDK/NDK 的安装与全部测试步骤保持启用；修正后的结果以分支最新 Actions 为准，不能把首次失败记录算作构建通过。

## 3. 作者先完成可复现构建

从一个独立目录检出交接分支：

```powershell
git clone --branch workbench/android-runtime-handoff-20260929 https://github.com/Vinzelles/uma-juece-ramen.git
cd uma-juece-ramen
python scripts/prepare_engine.py --package-data
```

普通构建不运行 `--capture`。该选项用于维护者主动更新锁定源码，不能用来掩盖哈希不符。

使用 `toolchains.json` 指定的版本：Rust 1.97.1、JDK 17.0.16+8、Gradle 8.11.1、AGP 8.9.2、cargo-ndk 4.1.2、Android SDK 34、Build-Tools 35.0.0、NDK 28.2.13676358。Windows 还需 Visual Studio C++ Build Tools；以下宿主 Cargo 命令应在已初始化 MSVC 的 Developer PowerShell 中执行，避免 Git 的同名 `link.exe` 抢占链接器。

```powershell
$env:JAVA_HOME='<JDK17目录>'
$env:ANDROID_HOME='<Android SDK目录>'
cargo install cargo-ndk --version 4.1.2 --locked
python -m unittest discover -s scripts/tests -v
cargo test --release --locked --manifest-path .engine-source/Cargo.toml -p umaai_runtime -p umaai_review
cargo test --release --locked --manifest-path rust/Cargo.toml
python scripts/build_android.py --skip-prepare
```

预期输出：`app/build/outputs/apk/debug/app-debug.apk`，包名 `com.umaai.assistant.dev`，版本 `0.5.0-dev-debug`。它可与旧正式包并存。首次运行按界面授予浮窗和通知权限。

该分支匹配 `workbench/*` 的构建 workflow。新 fork 可能尚未启用 Actions，需先在仓库 Actions 页面确认并启用，再手动触发构建；作者也可将分支迁入原仓库运行。CI 成功只代表该 workflow 覆盖的构建与测试通过，不代表真机验收通过。自动发版和自动策略提交已退出生产流水线。

## 4. 取得采集端改动

不要在日常使用的脏工作区直接应用补丁。先按锁定基线建立独立检出：

```powershell
git clone https://github.com/xf8410/so-history-backup.git ../so-history-backup-handoff
git -C ../so-history-backup-handoff switch -c workbench/ramen-snapshot-v1 721c086220ea081523229d88623de5abddd97eaa
python scripts/apply_collector_handoff.py --checkout ../so-history-backup-handoff --check
python scripts/apply_collector_handoff.py --checkout ../so-history-backup-handoff --apply
cargo test --release --locked --manifest-path ../so-history-backup-handoff/ramen_observation/Cargo.toml
```

补丁工具核对基线、工作区、补丁 SHA 和文件哈希，不会替作者提交。应用后查看采集仓的 `docs/RAMEN_RUNTIME_SNAPSHOT_V1.md`，再迁移到实际维护版本。

采集仓原有 `build-ura.yml` 会运行历史源码生成器。此次直接编译受检 checkout，没有证明这些生成器会保留新端点和新依赖。作者需在最终生成源码中检查新端点、观察模块及推送接线，记录源码提交与 SO 的 SHA256 对应关系。

## 5. 第一优先级：补齐真实快照

先记录手机型号、Android/游戏版本、Hachimi 版本、实际 SO 的版本/哈希/来源提交。保留现用 SO、配置和恢复方法，在测试环境确认新 SO 后再进入实际对局。

按缺失清单逐项核对，字段必须有来源、单位、编号、有效阶段和现场样本：

1. 真实育成局 ID、内部回合和明确阶段；不得用角色 ID 代替局 ID，不得只按回合范围猜阶段。
2. 当前角色、卡组和突破、继承、五维上限、人员身份/羁绊/分布、训练等级、友人进度与比赛历史。
3. 诀窍库存的获得顺序、槽值、地区、活跃效果、当前面、隐藏风味和超级拉面。
4. `continuation` 中的吃面次数、实际 RMJ 结果、训练等级加成、pending 面与风味目标、继承附加值。
5. 事件标识、选项及完整效果/概率；未知结果不能赋一个看似完整的默认值。

关键约束：

- 内部回合为 0–77；五维采用未折半真实值。
- `run_id` 与 `baseGame.single_mode_chara_id` 一致；同局快照序号递增。
- 同一快照中的字段属于同一状态版本。两次 HTTP 响应相同不等于已证明原子捕获。
- V1 顶层需要完整 envelope；非事件的 `event` 也须明确为 null。
- 只有真实验证通过后才能声明 `capture_coherence=verified`。非空 `missing_fields`、`ready=false` 或未验证的一致性会阻止搜索。

补齐后，从本机 `18765` 的 `/api/ai/ramen/v1/snapshot` 读取，或推送同一缓存快照到应用 `18766/data`。推送与轮询不能各自产生不同的快照身份。

第一道验收门：选一个普通训练盘面，完整采集、导入和合法候选检查均通过，手机显示当前盘面的建议。未过这一门，不进入整局质量测试。

## 6. 同一真实快照的 PC/Android 对照

保存完整 V1 envelope，例如命名为 `snapshot-v1.json`。局包顶层的裸 `baseGame/ramen` 文件不等于完整 envelope，应使用 `envelopes/` 内的输入。

```powershell
cargo run --release --locked --manifest-path .engine-source/Cargo.toml -p umaai_runtime --bin ramen_runtime -- .engine-source/gamedata snapshot-v1.json 61444 4
```

最后两项为随机种子和线程数。**当前 CLI 没有预算、策略或覆盖配置参数**，只读取数据目录中的 `default_config.toml`。当前锁定默认预算为 **12288**，Android 默认是 **8192**，直接使用两端默认值不能称为同参对照。

最少改动的对照方式：手机设为 `mcts`、预算 12288、4 线程，使用默认种子 61444；按请求的 `config_id` 核对局包 `meta.json` 中 `config_history[config_id].options`，不要只读最后一次设置。若需对照 8192 或其他策略，在独立数据副本配置并记录修改后的配置哈希，不修改锁定原目录，也不能继续声称配置未变。

比较阶段、结构化动作、候选顺序、候选评分/样本数和决策来源。`request_id`、耗时等宿主字段不参与比较。跨平台浮点误差单独记录，动作不同必须定位原因。

CLI 每次创建新会话，只处理一个快照。不能直接拿中途快照的累计运气值与 Android 持续会话比较；完整运气对照需从同一起点按相同顺序向持续会话重放。当前 CLI 没有多快照序列参数。PC 文件监听入口使用随机种子，也不能替代上述确定性入口。

第二道验收门：同一真实快照在 PC 与 ARM64 上得到一致动作和候选，评分差异有明确解释；同时确认 UI 不显示过期结果。

## 7. 真机验证顺序和回传内容

先覆盖一次完整育成，再做长时验证。逐阶段检查开局、吃/不吃、隐藏风味、跨年地区、友人和普通事件、比赛、RMJ、超级拉面与终局。随后验证重复/乱序输入、快速连续操作、断连恢复、进程退出、配置切换、初始化失败、横竖屏与暂停恢复。

回传证据至少包含：

- APK、引擎、数据、模型、采集器和游戏版本；实际卡组、策略、预算、种子、线程数。
- 失败前后的完整 envelope、请求标识、阶段及局包；必要时补界面截图/录屏和相关日志。
- PC 对照输出、候选差异和实际采用的配置，明确是否从同一起点重放。
- 耗时分布、峰值内存、取消响应和崩溃/ANR 记录。

这些是对局证据，不需要账号令牌、Cookie 或完整认证请求。提交公开 issue 前检查附件，保留用于复现的字段并去除与决策无关的敏感信息。

性能验收目标仍是目标：状态更新 ≤200 ms；取消释放 ≤1 秒；8192 预算普通 MCTS P95 ≤20 秒、地区 ≤30 秒；热 NN P95 ≤1 秒；应用加引擎 PSS ≤1 GB。至少 10 局和连续 2 小时，并覆盖 ARM64 的 4 KB/16 KB 环境。不能用降低预算或关闭功能掩盖未达标。

## 8. 其他未完成项与接收结论

- NN：取得匹配的正式模型、旁车、适用卡组及分发条件，再做真实推理一致性和手机性能测试；先不影响 MCTS 的采集验证。
- 复盘：同一真实完整局和中断局在 PC/手机生成相同事实；换引擎或数据版本后的旧局应明确拒绝错误版本，而不是静默重算。
- 两项忽略测试：补 `logs/SendGameStatusPlugin/game7075_turn{13,23_2,23_4,24_2,24_3}.json` 和 `logs/GameStatusSend_Ramen/game6204_turn*.json`，再显式运行相应 ignored 测试。缺样本失败不能改成通过。
- 发布：完成正式签名、升级迁移和回退验证后再讨论正式版。本分支不代表已达到发布条件。

作者回传结论可按以下格式填写：

```text
验证分支/提交：
设备、系统、游戏、Hachimi、SO 版本：
APK/引擎/数据/模型指纹：
完整快照字段核验：通过 / 未通过（缺项及证据）
单快照 PC/ARM64 对照：通过 / 未通过（参数及差异）
完整育成与阶段覆盖：
断连、取消、崩溃恢复、配置切换：
局包与复盘对照：
性能、10 局及 2 小时结果：
阻塞问题和复现附件：
是否具备进入下一验收阶段的条件：
```

当前建议接收为开发交接分支，下一阶段聚焦“真实采集 → 同参决策 → 手机展示”这一条完整链路。
