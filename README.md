# Uma Juece Ramen

拉面杯专用 Android 育成辅助浮窗，基于 `xf8410/uma-juece` 的通信与悬浮窗思路重新建立。

## 边界

- 只接受并显示 `scenario = Ramen`。
- 监听 `127.0.0.1:18766` 接收 hlpatch 推送；无推送时轮询 `127.0.0.1:18765/summary`。
- 使用与通用版相同的 `applicationId`：`com.umaai.assistant`，安装时覆盖通用版。
- 拉面杯规则、数据和状态机以 [`xulai1001/umaai-rs`](https://github.com/xulai1001/umaai-rs) 为上游依据。
- 当前 Java 推荐仅为运行时显示和保守兜底，后续再接入上游 Rust 搜索/决策内核。

## 当前功能

- 五维、体力、干劲和技能点显示；
- 五项训练最终收益、失败率显示；
- RMJ Pt、诀窍库存、槽、隐藏风味、地区显示；
- 普通吃面可行性和保守时机提示；
- 非拉面剧本输入明确拒绝，不套用错误策略。

## 构建

```bash
./gradlew assembleDebug
```

要求 Android SDK 34、JDK 17、minSdk 26。

## 来源

见 [`SOURCE_SNAPSHOT.md`](SOURCE_SNAPSHOT.md)。
