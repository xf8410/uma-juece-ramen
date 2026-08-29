# 拉面杯策略调参指南

## 什么是"好"

| 指标 | 含义 | 目标 |
|---|---|---|
| **均分** | N 局评分算术平均 | 越高越好，>5000 算合格 |
| **中位分** | 排序后中间值 | 反映策略稳定性 |
| **P90** | 90% 分位 | 上限潜力 |
| **RMJ 全通率** | 三年 RMJ 全部成功 | >50% 为优秀 |
| **均 PT** | 育成结束总技能点 | 越高越好 |

## 关键机制（AI 必须理解）

1. **RMJ PT = 吃面获取**。不吃面就没有 PT，没有 PT 就过不了 RMJ。
2. **吃面需要诀窍材料**。材料来自训练填满诀窍槽（满 7 清零→+1 诀窍）。
3. **闪彩圈训练比普通训练多填槽**（三种各+2）。所以发光不只是当回合属性高，还会多产材料→多吃面→多 PT。
4. **比赛也给材料但少**。不如训练。
5. **万能材料（隐藏风味）上限 4**。来源：地区选择后给 2 个；友人外出每次给 2 个（5 次共 10 个）。需避免溢出。
6. **友人外出 ≠ 普通外出**。友人外出给万能材料+友人事件；普通外出只回干劲。评分必须分开。
7. **友人必须先点一次**（在训练中点击友人卡）才会概率触发外出解锁事件。未点击前友人外不可用。
8. **开局第 2 回合选地区时送初始诀窍**（新友人每种 2 个）和万能材料。

## 怎么跑

### 前置：获取 gamedata

```bash
git clone https://github.com/xulai1001/umaai-rs
cd umaai-rs
```

### 跑 1000 局（默认参数）

```bash
cargo run --release \
  --manifest-path /path/to/uma-juece-ramen/rust/Cargo.toml \
  --bin ramen_batch -- 1000
```

### 用自定义参数跑

```bash
RAMEN_STRATEGY='{"shining_weight":50.0,"friend_outing_score":70.0}' \
cargo run --release \
  --manifest-path /path/to/uma-juece-ramen/rust/Cargo.toml \
  --bin ramen_batch -- 1000
```

### 对比两组参数

```bash
cargo run --release --manifest-path .../rust/Cargo.toml --bin ramen_batch -- 1000 2>&1 | tee run-a.txt
RAMEN_STRATEGY='{"shining_weight":50.0}' cargo run --release --manifest-path .../rust/Cargo.toml --bin ramen_batch -- 1000 2>&1 | tee run-b.txt
grep "均分" run-a.txt run-b.txt
```

## 可调参数

| 参数 | 默认 | 含义 |
|---|---|---|
| `head_weight` | 15.0 | 每个人头评分加成 |
| `shining_weight` | 40.0 | 每个发光评分加成（含材料链价值） |
| `failure_penalty` | 2.0 | 失败率百分比惩罚 |
| `vital_rest_threshold` | 30 | 体力低于此值优先休息 |
| `motivation_outing_threshold` | 4 | 干劲低于此值优先普通外出 |
| `friend_outing_score` | 60.0 | 友人外出的基础分（给万能材料×2，与普通外出不同） |
| `special_overflow_threshold` | 3 | 万能材料达此值不再友人外出 |
| `feeling_overflow_threshold` | 8 | 诀窍库存达此值优先吃面 |
| `rmj_urgency_margin` | 300 | 距 RMJ 目标差此值以内优先吃面 |
| `no_ramen_base_score` | 100.0 | 不吃面基础分 |
| `eat_ramen_base_score` | 50.0 | 吃面基础分 |
| `friend_click_bonus` | 25.0 | 友人未点击时有友人的训练加分 |

## 真实对局数据收集（decision_log.jsonl，v0.3.3+）

APK 在手机上自动记录真实对局：**每回合一行 turn（hlpatch summary 原样 + Rust decision），
局末一行 outcome（最终五维 + fans/fan_count + RMJ Pt + 回合）**，追加写
`getFilesDir()/decision_log.jsonl`（超 4MB 轮转 `.1`，只留一代）。

### 行格式

```jsonc
// turn 行（每回合最多一条，按 searchKey 去重）
{"type":"turn","run":"r20260829_012233","ts":…,"turn":12,"key":"12:…",
 "summary":{…hlpatch 推送 JSON 原样，含 chara/ramen/trainings…},
 "decision":{…Rust DecisionOutput：action_index/action_display/score/
             search_n/elapsed_ms/candidate_displays/candidate_scores…}}

// outcome 行（每局恰好一条：下一局 turn<=1 出现 / 终盘 fans 签名 / 服务退出，先到先写）
{"type":"outcome","run":"r…","ts":…,
 "final":{"turn":77,"speed":…,"stamina":…,"power":…,"guts":…,"wiz":…,
          "vital":…,"skill_point":…,"fans":…,"fan_count":…,"checkpoint_pt":…},
 "config":{"uma_id":102601,"cards":[302424,302894,303044,302924,303024,303054]}}
```

### 从手机拉日志

```bash
adb forward tcp:18766 tcp:18766
curl http://127.0.0.1:18766/decision_log > decision_log.jsonl
```

### 喂 optimize.rs 的路线（设计目标）

- **回放校准**：turn 行的 `summary` 可原样喂 Rust reconcile 重放重建，
  对照 `decision` 复现真实决策链 → 找出模拟器与真实局分歧的回合类型。
- **真实结果回归**：outcome 行的最终五维/粉丝是 `calc_score()` 之外的
  真实回归目标（真实局 objective），用于校验/修正 `rust/strategy_optimized.json`
  的模拟分与真实分差距（bias 校正），而不是直接替换 CMA-ES 目标。
- **后续步骤**：攒够 10+ 局后，在 rust 侧加 `ramen_calibrate` 二进制读
  decision_log.jsonl，输出「模拟 vs 真实」逐回合偏差报告；偏差稳定后
  再考虑把真实 outcome 作为 optimize.rs 的先验/验证集。

### 注意

- 日志只在本机（app 私有目录），不上传任何服务器；写盘失败静默。
- 手写兜底/解析失败（评分全 0）的回合不产 turn 行。
- 中途安装/启动时从当前回合起记录，outcome 的 `final.turn` < 77 表示该局未跑完。
