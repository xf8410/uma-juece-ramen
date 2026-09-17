# Ramen upstream lock

## Pinned revision

- Repository: https://github.com/xf8410/umaai-rs（fork，含 GA 注入通道修复；上游同步 f363386 + 修复 acb7735/3b4e940）
- Branch observed: `master`
- Commit: `53227d4b2c2c45fe441491a9df9c13949d773a7e`（2026-09-17，fork master = 上游 8 commit 合并 875dd2c + bench_base 冲突修复）
- Scenario data blob: 以 fork 仓库 `gamedata/scenario_ramen.json` 当前版本为准
- State model blob: 以 fork 仓库 `crates/umasim/src/game/ramen/state.rs` 当前版本为准

### 2026-09-17 sync notes（f11fdf4 → 53227d4）
- fork master 合并上游 8 commit（8e9f7a5..04c739c）+ 合并冲突修复：
  - 6376dd7 ga_lab 最优策略合并：通解卡组 + 9 旋钮参数组合档 + bench_base --deck + 基线重抓
  - 70550cd 智力豁免白名单 + 已满位 PT 定价实验 token（trd/trdsh/trds）
  - d9374e8 合宿训练诀窍全 MAX 填充修复（模拟器行为变化，历史基准失效）
  - 968489f MCTS pt_favor_rate 定档 2.0 + 运气分改真实评分
  - 合并遗留修复 53227d4：bench_base 重复 deck 字段/parse_deck_override 删除
- API 兼容性：Trainer trait / RamenMctsTrainer / RecommendedRamenTrainer / RamenSearchStages 预期零破坏，cargo check 与 CI Build Ramen Android 双重验证。
### 2026-09-16 sync notes（eeae510b → 3b4e940）

- 依赖源从上游切换到 fork（xf8410/umaai-rs）：fork = 上游 9-15 master 同步 + ParamOverride GA
  注入通道回植 + region_pt_weight 基因删除，CI 编译绿、四档 bench 5升2降（power_wisdom +1754）。
- jueceramen 侧 **API 零破坏**：Trainer trait / RamenMctsTrainer / RecommendedRamenTrainer /
  RamenSearchStages / GameView / rules 常量全部健在，cargo check 无 error。
- 行为对拍（同 RamenStrategy 同卡组各 300 局）：均分 57389→57431（+42，噪声内）、
  RMJ 全通 100%→100%、最高分 63474→66475（上游规则修正抬升上限）。
- 上游新增 policy_schema 参数包体系：后续可让浮窗直接加载 GA 最优基因（batch_v5/best_genome.toml）。
- 上游新增 NN 管线（convert754 / ramen_nn / onnx feature，规格 754 in / 234 out）：
  浮窗接 NN 推理待 NN 权重定稿后另起 PR。

All Android constants in `RamenUpstreamData` must cite this revision. Updating this file and the constants/tests belongs in one PR.

## 2026-08-29 sync notes（7cef1fa → eeae510b）

- `RamenMctsTrainer` 的 rollout 与未搜阶段 fallback 已切换为
  `RecommendedRamenTrainer`（正式推荐策略）——「手写策略 + 蒙特卡洛」由
  上游结构保证：门控全关时与纯推荐策略逐位一致（上游守门测试钉死）。
- rollout 提速约 -29% CPU（diag 输出改运行时门控）。
- 五维上限剧本化（[3100,2400,2200,2200,2400]）上游已生效；
  下方 newgame 2800 clamp 的旧记录按当时 rev 保留备查。
- 险胜决策理由输出（output/reason）默认 NoopSink，安卓侧未接。

## 直读人头注入口径（本仓库约定）

- hlpatch `trainings[].heads` 按「该训练界面人头数」理解（含卡/友人/NPC，
  不含理事长/记者——两者位置注入时保持不动）。
- 注入方式：按观测 heads 在训练之间**搬移可动人员**（多退少补），
  不重建行结构；总人数与观测不一致时按比例缩放并出 warning。
- `partner_ids` 语义未定，不使用（UPSTREAM 规则：未知映射保持未知）。
- 彩圈（shining）由卡的落位与效果推导，无法直接注入，保持重放近似。

## Confirmed model details

- Upstream simulator turns are zero-based.
- Year boundaries are internal turns `0..23`, `24..47`, `48..71`.
- RMJ settlement turns are internal `23`, `47`, `71`; hlpatch/UI external equivalents are normally `24`, `48`, `72`.
- Super Ramen is internal turns `72..77`; UI external equivalents are normally `73..78`.
- Feeling stock has three counters, total shared capacity 10, and FIFO overflow order.
- Each feeling slot completes at 7.
- Special feeling capacity is 4; at most 2 substitutions may be used for one normal ramen.
- RMJ success thresholds are `1500`, `3000`, `3500`; `5000` is the final great-success threshold.
- Per-year base scenario Pt gains are `300`, `400`, `500`, with per-bowl deltas `30`, `40`, `50` and an annual stacking cap of five bowls.
- Scenario data declares five-stat limits `[3100,2400,2200,2200,2400]`, while current `RamenGame::newgame` clamps every entry to `2800`. The Android app records both facts and does not invent a third cap.

## State mapping target

| Upstream `RamenState` | hlpatch summary candidate |
|---|---|
| `feeling_stock` | `ramen.sozai` |
| `feeling_slot` | `ramen.acquisition_gauges` (verify whether value or remaining count) |
| `feeling_queue` | `ramen.feeling_info` |
| `special_feeling` | `ramen.special_feeling_num` |
| `selected_regions` | `ramen.selected_region_ids` (verify zero/one-based ID conversion) |
| `scenario_pt` | `ramen.checkpoint_pt` |
| `current_ramen` | runtime active/selected ramen field; not yet mapped |
| `super_ramen` | not yet mapped |
| `eat_count` | not yet mapped |
| `train_feeling_type` | command gauge vectors/markers; not yet mapped |

Unknown mappings must remain unknown; UI heuristics must not be fed into the future Rust state importer as facts.
