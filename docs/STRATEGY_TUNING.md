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

## 剩下的你自己想办法
