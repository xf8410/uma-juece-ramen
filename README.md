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
