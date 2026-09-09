<div align="center">

# 🐎 uma-juece-ramen

**拉面杯专用决策浮窗：Rust 核心 + Android 壳**

![仓库](https://img.shields.io/badge/仓库-xf8410-8B5CF6?style=flat-square) ![分支](https://img.shields.io/badge/分支-34-10B981?style=flat-square) ![版本](https://img.shields.io/badge/版本-1-F59E0B?style=flat-square) ![CI](https://img.shields.io/badge/CI-6-3B82F6?style=flat-square)

</div>

---
> 📌 **一句话定位**：拉面杯专用决策浮窗：Rust 核心 + Android 壳

## 🧭 项目定位

<b>uma-juece-ramen</b> 是拉面杯（scenario 14）专用决策浮窗：基于 uma-juece 的浮窗壳 + 模块化 Rust 决策核心，机制与数据对齐 xulai1001/umaai-rs。内置五阶段卡组基准（粗筛→坐标搜索→全卡实测→严格全排列→十万局终验）与分片矩阵基准（10 片并行 96.7 万组合），K=5 冠军卡组 2速1力2智+1友 70,264 分（超上游基准 25.7%）。

## ✨ 核心功能
- 拉面杯实时决策浮窗（Rust 核心已模块化）- 五阶段卡组基准 + 10 片并行 96.7 万组合分片矩阵- K=5 冠军卡组 70,264 分（超上游基准 25.7%）- hlpatch SO AI 兜底（v3.27.22）- 牌谱决策日志 v1、黑板图表（board charts）

## 🌿 分支导览（共 34 个分支全览）

<details open>
<summary><b>点击收起/展开全部分支用途说明</b></summary>

| 分支 | 用途说明 |
|---|---|
| `main` | 主干：浮窗 + Rust 核心 |
| `workbench/modularize-rust-core` | Rust 核心模块化线 |
| `workbench/accuracy-v040` | v0.4.0 精度线 |
| `workbench/board-charts / board-vertical-candidates` | 黑板图表/竖排候选展示线 |
| `workbench/card-filter-* / cloud-card-benchmark / power-guts-benchmark` | 卡组筛选/云端与力量根性基准线 |
| `workbench/port-pc-ramen-planners / test-upstream-compositions / upstream-*` | PC 规划器移植/上游组合测试/上游同步线 |
| `workbench/so-ai-fallback-v32722 / strict-arm-native-build / strict-runtime-state-adapter` | SO AI 兜底/严格 ARM 原生构建/严格运行时适配线 |
| `workbench/ramen-http-api / ramen-upload / decision-log-v1` | HTTP API/上传/决策日志线 |
| `workbench/其余 fix/hotfix/reconnect/protocol 线` | 重连回放、协议对齐、发布修复等历史修复线 |

</details>

## 🏷️ 版本历史

v0.4.0 拉面杯决策浮窗一个正式版。

完整版本列表 ➡️ [Releases 页](../../releases)

## ⚙️ CI 流水线（共 6 条）

| 流水线 | 用途说明 |
|---|---|
| Build Ramen Android | 主构建流水线 |
| Card Benchmark Matrix / Ramen Card Benchmark / Root-card four-type A-B 100k | 卡组基准矩阵与 10 万局 A/B 终验 |
| Ramen Policy A-B | 策略 A/B 对比 |
| Strict ARM Native Build | 严格 ARM 原生构建 |
| Release APK | 发版流水线 |
| Import uma-juece source snapshot | 从 uma-juece 导入源码快照 |


---

## 📜 历史介绍存档

> 以下为仓库原有介绍，**内容未删改**，仅移入存档区（新版介绍以本页上方为准）。

<details>
<summary><b>点击展开原 README</b></summary>

# Uma Juece Ramen

拉面杯专用 Android 育成辅助浮窗，基于 `xf8410/uma-juece` 的通信与悬浮窗思路重新建立。

## 边界

- 只接受并显示 `scenario = Ramen`。
- 监听 `127.0.0.1:18766` 接收 hlpatch 推送；无推送时轮询 `127.0.0.1:18765/summary`。
- 使用与通用版相同的 `applicationId`：`com.umaai.assistant`，安装时覆盖通用版。
- 拉面杯规则、数据和状态机以 [`xulai1001/umaai-rs`](https://github.com/xulai1001/umaai-rs) 为上游依据。

## 上游搜索集成（v0.2.0+）

- **Rust JNI 桥接**：`rust/` 目录含 `uma-jni` crate，依赖上游 `umasim`（git 依赖，`--no-default-features` 排除 CLI 依赖）。
- **扁平蒙特卡洛搜索**：对每个可用动作（吃面+训练组合）运行 N 次模拟，走到终局，取均分最高的动作。
- **Java 侧**：`UmaNativeBridge.java` 加载 `libuma_jni.so`，从 assets 复制 gamedata 到内部存储后初始化。
- **后台线程**：搜索在独立线程运行，不阻塞浮窗 UI。回合变化时自动触发。
- **兜底降级**：原生库不可用时自动回退到 `TrainingEvaluator`（运行时收益+人头/发光权重评分）。
- **gamedata**：CI 从上游仓库下载 `constants.json`、`cardDB.json`、`umaDB.json`、`text_data_dict.json`、`events.json`、`scenario_ramen.json` 等，打包到 APK assets。
- **交叉编译**：CI 使用 `cargo-ndk` 交叉编译 `aarch64-linux-android` 和 `x86_64-linux-android` 目标。

### 当前限制

- 牌组配置硬编码为上游测试牌组（uma_id=102601, cards=[302424,302894,303044,302924,303024,303054]），后续加设置页。
- 状态注入为部分重建（stats/vital/ramen 状态从 hlpatch 注入，friendship/train_level 估算），非完美快照。

## 回合口径（v0.3.0+）

- hlpatch 的 `turn` 与游戏 UI「第N回合」一致（**1-based**），v3.27.17+ 对拉面杯直读下发。
- 上游模拟器 umaai-rs 内部回合**从 0 开始**（0..=77），`inject_state()` 做 `turn - 1` 转换。
- 浮窗回合行同时显示两个口径：`第31回合 直读(AI:30)`，便于现场核对是否差一。
- 旧版 hlpatch 无 turn 字段时，Rust 侧回退 month/half + 属性总量估算（带 ⚠ 警告计数）。

## 小黑板显示（v0.3.0+，对齐 PC 黑板）

- 拉面建议与训练建议**并列显示**，不再互相覆盖：
  ```text
  建议：吃面/函馆-耐（mean 66972 · 4096次/12.7s）
  #0 不吃面 -999 ｜ #2 吃面/东京-智 -731 ｜ #3 吃面/中山-速力智 -42
  训练兜底：耐×5（期望收益评分）
  ```
- 候选差值（决策理由）来自 Rust `last_breakdown`，其余候选相对选中动作的 mean 差。
- 训练明细行（PC 黑板「训练:」风格）：`速: 速+46 力+14 27pt 体力-25 失败10% 头3光2`。
- 候选评分复用 `select_action` 内部那次搜索的 breakdown，**不再二次搜索**
  （旧版 4096 次模拟直接翻倍，且两次随机流不同、展示均值与实际选中不一致）。

## 当前功能

- 五维、体力、干劲和技能点显示；
- 五项训练最终收益、失败率、人头数、发光数显示；
- RMJ Pt、诀窍库存、槽、隐藏风味、地区显示；
- 普通吃面可行性和保守时机提示；
- **上游搜索推荐**（均分/N次/耗时）+ 候选差值 + 运行时兜底评分；
- 非拉面剧本输入明确拒绝，不套用错误策略。

## 构建

```bash
./gradlew assembleDebug
```

要求 Android SDK 34、JDK 17、minSdk 26。

CI 自动交叉编译 Rust JNI 库和下载 gamedata，无需本地安装 Rust/NDK。

## 来源

见 [`SOURCE_SNAPSHOT.md`](SOURCE_SNAPSHOT.md)。


</details>
