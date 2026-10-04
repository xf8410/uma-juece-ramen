# Android 与 SO 恢复候选交接（2026-10-04）

本文件是恢复候选的优先入口。[旧 HANDOFF](../HANDOFF.md) 只保留历史。当前交付包含可构建的本地运行层、重连协议、记录及复盘改进；**没有完整真实盘面、正式 NN 权重或真机验收结论。** 采集端未知字段仍阻止计算，未确认的原生 ABI 仍阻止托管方法 Hook 安装。

恢复分支为 `workbench/ramen-recovery-20261004`，Android 起点是已合入 PR #27 的 `d221cc1c896c035c73a10da3baa085f10e9af8b5`。本页构建记录对应提交前冻结的工作树；分支提交、推送和远端 CI 状态以关联 PR 为准。其他维护者取得分支后，应核对源码锁并自行构建。

提交边界说明：现有 `.gitattributes` 将两份 JSON 锁文件规范化为 LF。Git 中 `engine/source-lock.json` 的字节 SHA256 仍为 `fa0afa40f88f62b3bbbe84aa3c9b9afb825ca205983131975a1724c3771d1f7a`；本次 `docs/handoff/collector-source-lock.json` 显式以 LF 保存，实际字节 SHA256 为 `516641458b39d43304363ccca12fd22c1d4131930709be64d2b4cd367f5921c9`。采集锁新增 `source_revision`，对应 SO 已提交的 CI 修复。已有诊断包中的 APK／manifest／JVM 证据仍对应提交前 Windows 工作树的 CRLF 字节；旧采集锁的 `36a50d0a…`（工作树）及 `4134ded5…`（首次 Git LF 规范化）仅属于该历史冻结材料。CI 或本地从新提交重新构建时必须重新生成 manifest 和产物，不复制旧 manifest，也不放宽字节哈希校验。公共引擎补丁与引擎锁内容未改变。

SO `3.28.2-recovery.2` 修正了旧 `/summary` 对回合语义的错误声明：拉面 `turn/year` 设为 `null`，保留 `raw_total_turn_num`、字段来源及 `raw_field_mapping=unverified`。旧拉面 heuristic 入口返回结构化 `unavailable`，直接调用也拒绝；没有引入新的回合公式或零分建议，非拉面公式保持原样。V2 的 `display_summary.turn_observation` 保留取证信息，但不把原始值导入 `state.baseGame.turn`，也不提高 `ready`。当前 V2 仍缺真实局号、完整阶段与 `state+continuation`，不能驱动真实整局决策。

SO `3.28.2-recovery.3` 进一步修正发布锁边界：Publisher 由唯一采样 worker 持有，共享状态保存不可变 Arc；JSON 转换、深比较、字段检查、深复制和旧大对象最终析构在共享锁外进行，锁内整组交换快照、摘要与状态。进入 `booting` 时撤销当前采样的外发资格；恢复进入 `capturing` 后，在新采样完成前，HTTP、summary 和 push 不会重新暴露 boot 前的旧快照。`current()` 仅保留历史诊断用途。同内容采样仍保留编号、捕获时间和同一 Arc；ACK 仍绑定实际发送的快照。这是确定性宿主回归证实的实现缺口，不是对作者现场故障根因的认定，也没有补齐真实盘面或验证游戏安全采样边界。

**collector 已同步 SO 提交 `2037c5f` 并独立验证；旧 `.3` 本地产物保持冻结。** 新提交相对 `6d0c1bf` 仅增加 CI 中两个锁定 manifest 的依赖预取，共 6 行 workflow，未修改 SO 生产源码或版本。下文 `c400…` 指纹、SO／符号哈希与旧 `.3` ZIP 对应 `6d0c1bf` 所记录的冻结源码树，不是 `2037c5f` 新 CI 构建的身份；新 CI 产物须以对应提交的构建清单另行记录。此前交接包、补丁副本和独立验证目录均保留，Android APK 与公共引擎锁未重新构建或改写。

本候选的来源必须分开核对：

- 公共引擎：`xulai1001/umaai-rs@9e154046a57dd55135257aaacf8f9d9d65cc6e42`，加 [runtime.patch](../engine/runtime.patch)。补丁 SHA256 为 `ca0286e644263525a6268495984070b5e745789c6e508bc024aba10a80e93792`；[source-lock.json](../engine/source-lock.json) 锁定 179 个文件。不要把新增 runtime 当作已包含在上游基线提交中。
- SO：真正的 `xf8410/hlpatch` 恢复工作树以 `fa2c820f4847f4e7b377e846ade958e0be19e98d` 为起点。该仓 `recovery/baseline-source-lock.json` 固定 43 项输入，重放 v3.28.2 的实际生成链。历史原始输入中出现 `721c086`，不等于本候选使用旧 `so-history-backup` 交接仓。
- 基线生成文本的规范 LF SHA256 为 `67f1cb0ef8d0319e4c9f7e3ea026d5938b7f7833d14304594111ac414e6e76c9`。它是生成源码的对应证据，不是现代工具链构建与历史发布 SO 逐字节相同的证明。设备上实际加载的 SO 必须另记 SHA256、Build ID 和对应构建清单。

[collector.patch](handoff/collector.patch) 和 [collector-source-lock.json](handoff/collector-source-lock.json) 直接从 Git 提交树 `fa2c820f4847f4e7b377e846ade958e0be19e98d..2037c5f35d032eab76015536d2e9596f5aca7e6a` 生成，包含 102 个受影响文件。补丁 SHA256 为 `795f98d8520be125810be823beddc58f6c52b8b52a4238a89d2d44429a5ab0c2`；schema 1 锁文件的 `source_revision` 固定为 `2037c5f35d032eab76015536d2e9596f5aca7e6a`，逐项记录原始基线 Git blob 哈希和规范 LF 的前后文件哈希。现有应用工具兼容此新增元信息。相对上一份冻结 bundle，只有 `.github/workflows/recovery-candidate.yml` 的 after hash 改变；生成过程只读取提交树，未修改 SO 真实 index、旧 mirror 或已有产物。

独立验证使用本地无 hardlink clone，明确启用普通 Windows `core.autocrlf=true`，检出 `fa2c820f4847f4e7b377e846ade958e0be19e98d` 后直接运行现有应用工具。`--check` 通过，`--apply` 后 102 个文件哈希全部匹配；没有私下预规范化检出目录。验证 clone 和日志保留在本地 ignored cache：`.tools/collector-bundle-ci2037c5f-verified.log` 与 `.tools/collector-commit-2037c5f-20261004T062350930893Z/verification.json`，不属于已经推送的证明。使用其他来源 bundle 时仍应核对锁文件；旧 `so-history-backup/721c086` bundle 不能代替本候选。

从 Android 仓根目录应用到一个干净、独立的 hlpatch clone：

```powershell
git clone --no-checkout https://github.com/xf8410/hlpatch.git ../hlpatch-recovery-handoff
git -C ../hlpatch-recovery-handoff checkout --detach fa2c820f4847f4e7b377e846ade958e0be19e98d
python scripts/apply_collector_handoff.py --checkout ../hlpatch-recovery-handoff --check
python scripts/apply_collector_handoff.py --checkout ../hlpatch-recovery-handoff --apply
```

不要在日常脏工作区应用。该工具不提交或推送，且拒绝错误基线、已有改动和哈希不符。随后按采集仓的恢复构建说明操作。旧本地 SO `3.28.2-recovery.3`（`6d0c1bf` 冻结树）的构建输入指纹为 `c4001711dd8fba47aefd1f999aaf62cbf769c822afea286b8d56903751aa15a5`，候选 SO SHA256 为 `f4de9af7745c625acce0a15ee2395403c9c84d25f99b3b27b62b835677f8b5cc`，Build ID 为 `9f02c12b3a649322cf5f393c38b4449497711801`。部署前仍须对照同目录的构建清单、符号文件和实际装载文件，不能只看版本字符串。

新版流程先读取 `/api/ai/ramen/capabilities`，再确认 `collector_instance_id` 并读取 `/api/ai/ramen/v2/snapshot`。V2 的身份为 `(run_id, collector_instance_id, snapshot_id)`：游戏进程重启产生新实例；对于已获得完整真实局标识的 V2 输入，继续同一育成时必须保留真实局号。**当前 legacy 适配的 `run_id` 仍为 null，不能据此宣称已实现真实局号采集。** 只有完成新实例握手，才能接受从 1 开始的序号。旧实例回调不能重新激活。V1 仍兼容原序号语义，不把低序号猜成重启。

SO 只有一个采样 worker，HTTP 和推送只读缓存。相同盘面再次采样保留原快照序号及捕获时间，最近采样时间单独记录。推送只有在收到 HTTP 202、`status=received`、明确 `computed=false` 且实例/局号/快照号全部匹配时，才记录运输成功；不完整盘面的 `run_id:null` 可以被收件，但不能因此开始搜索。连接、读写和 ACK 大小都有边界，失败后同一个待发快照仍可重试。

Android 保留每次实例的原始 envelope，标准状态文件与 CSV 使用独立持久化 `archive_seq`，避免跨进程源序号重用导致覆盖。当前完整状态可以继续计算；同实例同局的来源序号跳号、跨实例恢复和中途接管会标记历史缺口或独立运气基线。新会话重算同一快照不凭空制造“上一序号”。复盘允许同一快照的多个请求及配置，取消/失败行不建立运气基线；每段曲线只使用自己的决策行。

原生复盘逐项检查 `config_history` 中的引擎、数据、策略和模型版本证据，并检查决策使用的配置是否登记。历史版本不匹配或缺少证据时，明确拒绝完整复盘；不能只拿最新配置代表整局。旧 PC 包仍可由公共复盘库按兼容规则读取，但这不等于它通过 Android 原生版本一致性校验。

最终启动路径已关闭历史后台自更新、旧日志自动上传／删除及历史 SQLCipher flags 触发的 Hook 安装。旧标记和原始日志仍保留；它们不会再通过本插件的这些启动入口，在验收过程中替换候选 SO 或额外安装 Hook。这项修复不启用任何尚未验证的 ABI profile。

本轮实际验证如下：

- 公共 runtime：31 通过、2 忽略。忽略的是 `test_into_game_restores_rmj_state` 和 `test_turn_import_v2_full_samples`，需要未提供的真实采集样本；显式运行且缺样本时会失败。
- review：54 通过，包括多实例目录/ZIP 一致性、同快照多配置、取消行、V1 序号 0、同回合源序号跳号及缺历史处理。
- 直接 Rust JNI 宿主：8 单元测试及 1 合成快照集成通过，当时未启动 JVM。历史版本和同快照复盘错误先由失败测试复现，再验证修复。
- SO `.3` 聚焦 Release 验证为 SO bridge 13 项、portable observation 13 项通过。三项新增 bridge 回归在旧源码上均失败；线程本地分配/释放探针确认转换、读取深复制和两个指定旧缓冲区最终释放时可取得共享锁，通道阻塞测试确认 boot 恢复窗口不外发旧状态。新增 portable 回归确认相同发布复用 Arc、旧读者快照不变及迟到 ticket 拒绝。使用合成数据和宿主线程，没有运行游戏 Hook；未用耗时阈值代替锁边界证据。
- SO `.3` 的 `run_checks` 通过 14 项构建基础设施测试、19 个生产函数／模块宿主测试入口及 13 项 portable 测试；内层 bridge 测试为 13 项，不与包装入口相加计算覆盖率。包含真实 summary formatter、heuristic 拒绝、锁边界和 boot 屏障回归。`.3` ARM64 完整 Release 构建通过，仍保留 107 项编译警告；限制以配套构建日志为准，不构成原生安全性验收。
- 严格 ARM64 JNI 编译、APK 打包、1 个 ELF、8 份游戏数据、ELF/ZIP 16 KB 对齐及源码对应关系校验通过。最终 clean 构建执行 41 个任务，Java 单元测试实际重新执行 78 项、0 失败。此前 `UP-TO-DATE` 是中间构建记录。

新增真实 JVM → JNI 验证：Windows x64、Temurin JDK 17.0.16+8，直接编译未修改的 `UmaNativeBridge.java`，再由 `java -Xcheck:jni` 加载该类和匹配当前 `9e15404+ca0286e64426` 引擎的宿主 DLL。13 项断言通过，收到 `started/decision/completed` 共 3 次真实 Java 回调；覆盖中文及空格路径、初始化、实例确认、缺失/不完整输入拒绝、提前取消、合成 MCTS 搜索与复盘版本拒绝。未出现 `-Xcheck:jni` 警告或 fatal 诊断。证据位于本地 ignored `.tools/jvm-jni-smoke-20261004/` 的 `README.txt`、`commands.jsonl`、`smoke.log`、`result.json`、`provenance.json`；此目录不代表已进入 Git 或已分发。仅使用合成状态和 MCTS（`onnx=false`），不代表 Android ART、ARM64、Binder、浮窗或真机验收。原 Java 类、引擎锁和 APK 哈希均未变化。

最终开发产物位于 `app/build/outputs/apk/debug/app-debug.apk`，大小 28,513,923 字节，SHA256 为 `94ec61fea906751831d3dd5d3c724b66f5ecb42cb4d666b91e5f35dbd9d8a121`。包内 ARM64 `libuma_jni.so` SHA256 为 `7a80874d640f1e8a118edb3436e813256c7beb5a4cf1546c85524c1962533062`；引擎锁文件 SHA256 为 `50281bfc1558cd49d3d43f055558a5486e3c6ee4b08aa2f900913942ff1657e1`。clean 重打包清除了旧增量包中未被 ZIP 条目引用的空洞，898 个条目的解压字节保持一致；旧 `f25b2a84…` 包仅保留为中间历史。所列 APK 来自提交前冻结工作树；后续源码或锁改变后必须重新构建并重新记录摘要，不能沿用旧 APK 的证明。

旧本地 `.3`（`6d0c1bf` 冻结树）配套 stripped SO 为 5,143,320 字节；符号 SO 为 54,866,616 字节，SHA256 为 `abf788ff3d1d03121bd57246f2636e0c9552c4cbc41da4f2fb800bcfcd5f6bc6`。二者的 Build ID 都是 `9f02c12b3a649322cf5f393c38b4449497711801`，必须配套保留，不能使用此前中间候选的符号解析当前崩溃。

复现时使用 [toolchains.json](../toolchains.json) 的固定版本：Rust 1.97.1、cargo-ndk 4.1.2、JDK 17.0.16+8、NDK 28.2.13676358、Gradle 8.11.1、AGP 8.9.2、SDK 34、Build-Tools 35.0.0。Windows 宿主测试需要正确的 MSVC 子进程环境。仅在当前命令进程设置 JDK/SDK 和已安装 cargo-ndk 的 PATH，不修改系统配置。

```powershell
python scripts/prepare_engine.py --source <umaai-rs检出目录> --package-data
cargo test --release --locked --manifest-path .engine-source/Cargo.toml -p umaai_runtime -p umaai_review
cargo test --release --locked --manifest-path rust/Cargo.toml --no-default-features --features jni-support
python scripts/build_android.py --skip-prepare
python scripts/verify_artifact.py --apk app/build/outputs/apk/debug/app-debug.apk --abi arm64-v8a
```

正常复现不运行 `--capture`。它会改变交付的源码锁，仅供维护者主动更新受检源码。发生版本或哈希不符时先确认来源，不通过重新 capture 掩盖差异。

同快照 PC 对照可用 `.engine-source` 的 CLI，V2 最后一个参数必须是此前确认的采集实例：

```powershell
cargo run --release --locked --manifest-path .engine-source/Cargo.toml -p umaai_runtime --bin ramen_runtime -- <同版gamedata目录> <完整V2快照.json> 61444 4 <已确认实例>
```

比较前统一引擎、数据、模型、策略、预算、种子和线程数。CLI 从该数据目录的 `default_config.toml` 读预算；PC 默认 12288 与 Android 默认 8192 不相同，不能直接比较默认结果。单份快照的新会话也不能代表已经运行半局的累计运气基线。先比较阶段、合法动作、结构化决策和候选分，再解释历史差异。

设备验收由仓库作者完成。以下链接假设两个仓库并排检出为 `uma-juece-ramen/` 与 `hlpatch/`；隔离 worktree 布局不同时，应在对应 SO 检出目录打开同名文件。它们不是已经推送的远端链接：

- [SO 设备验证步骤](../../hlpatch/docs/RECOVERY_DEVICE_VALIDATION.md)：实际安装组合、冷启动、App 后启动、同局三次游戏重启、原始文件分段导出、采样/ACK/崩溃证据及回退。
- [SO 原生 ABI 验收](../../hlpatch/docs/RECOVERY_ABI.md)：交付时应确认它与候选源码一致。当前 `verified_profiles()` 是空集合，托管方法 Hook 默认报 `unsupported_native_abi`，没有开关绕过。方法名或托管参数个数不能单独证明原生隐藏参数正确。

仍需完成实际采集代码，而非只补配置：完整盘面生产与桥接、真实局号及续养字段来源、经过验证的游戏线程/生命周期安全观察边界均未完成。worker 的 IL2CPP attach 和自身读锁不证明盘面原子性。`verified_profiles()` 为空、typed native thunk 尚未实现，授权接线仍未提供实际 `BuildIdentity`；取得目标游戏、Unity、Hachimi 身份和原生 ABI 证据后，还需实现 thunk 与 identity plumbing，并由作者在设备上验证原调用及宿主 Hook 共存。

在 ABI 证据补齐前，本候选不能称为“完整嗅探恢复”。在完整真实 `state+continuation`、阶段和一致性证据补齐前，也不能称为“真实整局 AI 已接通”。NN 正式权重、Android 真机 JNI 加载、性能、PSS、温升、10 局和两小时稳定性均待设备验收；Windows JVM/JNI 验证不能替代这些条件。保留原 APK/SO 和局记录，不卸载旧应用或删除用户数据来绕过签名与迁移问题。
