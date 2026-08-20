# Uma Juece — 赛马娘育成辅助浮窗

赛马娘育成实时辅助 Android 应用，以悬浮窗形式展示属性、训练增益、Buff 状态与 AI 决策推荐，搭配 [Hachimi URA Plugin](https://github.com/xf8410/hlpatch) 使用。

## 功能亮点

- **悬浮窗实时显示**：速度/耐力/力量/毅力/贤、体力/干劲一目了然，无需切出游戏。
- **训练增益对比**：各训练项目的属性增量与体力消耗并排展示，训练等级提升实时更新。
- **Buff 三色分类**：Good 效果（青）/ Bad 效果（桃）/ 其他效果（緑），清晰直观。
- **生病自动检测**：基于 CharaEffectId 判定不良状态，准确识别夜鷹/怠け/肌荒れ等病态。
- **AI 决策推荐**：综合属性增益、体力、干劲、Buff 与生病状态，推荐最优训练选择。
- **双重数据源**：支持插件主动推送（低延迟）+ 兜底轮询（5 秒自动拉取），确保数据不中断。
- **拉面杯面板**：盛況度 11 档进度条（MDB 真实阈值）、RMJ 目标差距与回合倒计时、隠し味 x/4、麺/汤/配素材与进度条、已选地区展示。

## 数据来源

| 来源 | 端口 | 方式 | 说明 |
|------|------|------|------|
| Hachimi URA Plugin | 18765 | 推送 + 拉取 | 插件主动推送数据到 App，App 同时兜底轮询 `/summary` |

## 目录结构

- `app/src/main/java/com/umaai/assistant/service/FloatingWindowService.java`：悬浮窗核心逻辑，属性/Buff/推荐渲染。
- `app/src/main/java/com/umaai/assistant/service/TrainingEvaluator.java`：AI 决策引擎，综合评估训练优先级。
- `app/src/main/java/com/umaai/assistant/MainActivity.java`：主界面，启停控制与权限管理。
- `.github/workflows/`：GitHub Actions 自动编译 APK。

## 环境要求

- Android 8.0（API 26）及以上。
- 悬浮窗权限（SYSTEM_ALERT_WINDOW）。
- 已安装 Hachimi URA Plugin 并在运行中。

## 安装

1. 下载最新 APK 安装包。
2. 安装后授予悬浮窗权限。
3. 启动 App，点击"开始"开启浮窗服务。
4. 进入游戏，浮窗自动显示育成数据。

## 使用说明

1. 确保 Hachimi URA 插件已加载并运行（浏览器访问 `18765/status` 确认）。
2. 打开 Uma Juece，授予悬浮窗权限后点击"开始"。
3. 进入育成界面，浮窗自动刷新数据。
4. 点击浮窗可展开/收起详细面板。

## 技术栈

- **语言**：Java（Android SDK 34）
- **构建**：Gradle + GitHub Actions
- **UI**：Android 悬浮窗（WindowManager）
- **通信**：HTTP Client（OkHttp），JSON 解析（org.json）
- **兼容**：minSdk 26，targetSdk 34

## 故障排查

- **浮窗不显示**：检查是否授予悬浮窗权限，Android 设置 → 应用 → 特殊权限 → 悬浮窗。
- **数据不刷新**：确认插件 `/status` 返回 `game_initialized: true`；App 内置 5 秒兜底轮询，推送断连时自动切拉取模式。
- **Buff 为空**：确认插件版本 ≥ 3.14.2，App 版本 ≥ 1.11。
- **属性显示 0**：确认插件版本 ≥ 3.14.1，Int32 读取已修复。

## 许可证

本项目仅供学习研究使用，请勿用于商业用途。

## 当前版本

- App v1.33.2：三地区规划器改为库存消耗模拟（连做/单做分列），消费插件推导候选池（需 hlpatch ≥ v3.24.32）
- 配套插件：hlpatch v3.24.32（候选池仅在选择回合输出，HTTP 仅绑定 127.0.0.1）
