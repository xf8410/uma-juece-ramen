# Ramen upstream lock

## Pinned revision

- Repository: https://github.com/xulai1001/umaai-rs
- Branch observed: `master`
- Commit: `ead54762fedc25cafdf2759846d4396a6333aa40`
- Scenario data blob: `gamedata/scenario_ramen.json@cbd96ce172fa7c9578cb6f79859aab907a51c7c9`
- State model blob: `crates/umasim/src/game/ramen/state.rs@f64a2187209b011d8addc0ed314ba6dcaba52057`

All Android constants in `RamenUpstreamData` must cite this revision. Updating this file and the constants/tests belongs in one PR.

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
