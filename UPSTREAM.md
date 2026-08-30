# Ramen upstream lock

## Pinned revision

- Repository: https://github.com/xulai1001/umaai-rs
- Branch observed: `master`
- Commit: `eeae510b57ee9d29a475645a05c191e6ef5a6e72`（2026-08-28）
- Scenario data blob: `gamedata/scenario_ramen.json@cbd96ce172fa7c9578cb6f79859aab907a51c7c9`
- State model blob: `crates/umasim/src/game/ramen/state.rs@f64a2187209b011d8addc0ed314ba6dcaba52057`

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
