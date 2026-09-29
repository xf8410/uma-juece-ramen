# 构建与引擎版本

正式构建使用 `toolchains.json`、`rust-toolchain.toml` 和 Gradle Wrapper 固定工具版本。JDK、Android SDK/NDK 路径通过进程环境传入，不修改系统配置。

## 引擎来源

源码白名单也包含 `testsupport/`，确保重建后的 ONNX 合成模型测试能读取其辅助代码。

`engine/source-lock.json` 记录完整上游提交、`engine/runtime.patch` 的 SHA256 及白名单内每个文件的 SHA256。它明确区分上游原始提交和本项目尚未提交到上游的公共运行层修改。

维护者在修改完成并通过上游测试后运行：

```powershell
python scripts/prepare_engine.py --capture --source ../../umaai-rs --package-data
```

此命令只读上游仓库，生成可审查的 patch 和 lock；不暂存、不提交上游源码。请将二者一起审查。它只导出 Cargo 清单、锁文件、crates 源码和 gamedata，不复制 `.git`、缓存、模型、日志或凭据文件。换行统一为 LF，以保持 Windows 与 CI 一致。

普通构建不执行 `--capture`。从锁定上游加 patch 重建到忽略的 `.engine-source`，逐文件验证后才可构建。可指定本地上游路径以离线重建；不指定时由脚本获取锁定提交。

## 构建命令

```powershell
$env:JAVA_HOME='path/to/jdk-17.0.16+8'
$env:ANDROID_HOME='path/to/android-sdk'
$env:ANDROID_NDK_HOME="$env:ANDROID_HOME/ndk/28.2.13676358"
# 若 cargo-ndk 安装在项目局部，将其 bin 路径加入当前进程 PATH。
cargo install cargo-ndk --version 4.1.2 --locked
python scripts/build_android.py --source ../../umaai-rs
```

SDK 必须安装 `platforms;android-34`、`build-tools;35.0.0`、`ndk;28.2.13676358`。脚本运行固定版本的 ARM64 JNI 编译、Java 测试、Debug APK 构建及产物校验。使用 `--native-only` 可仅准备 Gradle 的原生和数据输入。

Windows 还需要 Visual Studio C++ Build Tools，以编译 Rust 的宿主 build script。脚本用 `vswhere` 找到 MSVC，仅在构建子进程中初始化环境，避免 Git 自带同名 `link.exe` 抢占链接器。Linux CI 无此要求。

```powershell
python -m unittest discover -s scripts/tests -v
python scripts/verify_artifact.py --inputs
python scripts/verify_artifact.py --apk app/build/outputs/apk/debug/app-debug.apk
```

模拟器使用 `python scripts/build_android.py --source ../../umaai-rs --abi x86_64`。脚本自动将 `-PtestAbi=x86_64` 传给 Gradle，并按 x86_64 校验原生库和 APK；手工校验也需加 `--abi x86_64`。默认命令始终要求 ARM64，不能把仅含模拟器库的 APK 当作手机产物交付。

Gradle 的 `preBuild` 拒绝缺失原生库、数据校验失败、源码与上次原生构建不同等情况。源码变更后重新运行构建脚本，不手工复制旧 SO 绕过校验。Android `rust/Cargo.lock` 和引擎 `Cargo.lock` 必须同步更新并保留；正式命令使用 `--locked`。

## 产物契约

- Debug 应用包名为 `com.umaai.assistant.dev`；正式包名保持 `com.umaai.assistant`。
- `assets/gamedata/manifest.json` 包含 `schema_version`、`engine_revision`、`config_version` 和 `files` 哈希映射，供安装器验证数据。
- `assets/build-manifest.json` 记录 Android 提交与源码指纹、引擎基线与补丁、数据清单、配置、工具链、协议和原生库哈希。
- 尚未取得正式 NN 模型时，模型字段为 `null`，不能宣称已打包模型。
- 校验器检查所有 SO 的架构和 ELF LOAD 对齐，另检查 APK 中未压缩 SO 的 ZIP 偏移 16 KB 对齐，核对 APK 内数据和原生库字节与构建输入一致。
- 主 CI 的编译、测试和产物检查任一失败都会失败。研究优化器、自动策略提交、旧源码覆盖和自动发布已退出正式流水线。发布候选 workflow 只生成已验证的开发产物；签名、真机验收及正式发布仍是独立交付条件。

工具链依据：[AGP 8.9 兼容矩阵](https://developer.android.com/build/releases/agp-8-9-0-release-notes)、[Android 16 KB 页面要求](https://developer.android.com/guide/practices/page-sizes)、[Gradle 8.11.1 分发](https://services.gradle.org/distributions/)、[cargo-ndk 版本](https://github.com/bbqsrc/cargo-ndk/releases/tag/v4.1.2)。

构建验证不能替代 Android 真机的 4 KB/16 KB 启动、运行时页面假设、整局稳定性和性能验收。
