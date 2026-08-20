# 拉面杯策略调参指南

## 什么是"好"

| 指标 | 含义 | 目标 |
|---|---|---|
| **均分** | N 局评分算术平均 | 越高越好，>5000 算合格 |
| **中位分** | 排序后中间值 | 反映策略稳定性 |
| **P90** | 90% 分位 | 上限潜力 |
| **RMJ 全通率** | 三年 RMJ 全部成功 | >50% 为优秀 |
| **均 PT** | 育成结束总技能点 | 越高越好 |

评分 = 五维属性 + 技能 + hint + 种马继承 + 评赏等综合分，和游戏内 URA 评分一致。

## 怎么跑

### 前置：获取 gamedata

```bash
git clone https://github.com/xulai1001/umaai-rs
cd umaai-rs
# gamedata/ 目录已在仓库中
```

### 跑 1000 局（默认参数）

```bash
# 在 umaai-rs 根目录下执行
cargo run --release \
  --manifest-path /path/to/uma-juece-ramen/rust/Cargo.toml \
  --bin ramen_batch -- 1000
```

### 用自定义参数跑

```bash
RAMEN_STRATEGY='{"vital_rest_threshold":35,"head_weight":20.0,"shining_weight":40.0}' \
cargo run --release \
  --manifest-path /path/to/uma-juece-ramen/rust/Cargo.toml \
  --bin ramen_batch -- 1000
```

### 对比两组参数

```bash
# 默认
cargo run --release --manifest-path .../rust/Cargo.toml --bin ramen_batch -- 1000 2>&1 | tee run-default.txt

# 调参后
RAMEN_STRATEGY='{"vital_rest_threshold":25}' cargo run --release --manifest-path .../rust/Cargo.toml --bin ramen_batch -- 1000 2>&1 | tee run-vital25.txt

# 比较均分
grep "均分" run-default.txt run-vital25.txt
```

## 可调参数

| 参数 | 默认 | 含义 |
|---|---|---|
| `vital_rest_threshold` | 30 | 体力低于此值优先休息 |
| `motivation_outing_threshold` | 4 | 干劲低于此值优先外出 |
| `head_weight` | 15.0 | 每个人头的评分加成 |
| `shining_weight` | 30.0 | 每个发光的评分加成 |
| `failure_penalty` | 2.0 | 失败率百分比惩罚系数 |
| `feeling_overflow_threshold` | 8 | 诀窍库存达此值优先吃面 |
| `rmj_urgency_margin` | 300 | 距 RMJ 目标差此值以内优先吃面 |
| `no_ramen_base_score` | 100.0 | 不吃面的基础分 |
| `eat_ramen_base_score` | 50.0 | 吃面的基础分 |

## 策略决策逻辑

### RamenSelect（吃面决策）
- 库存 ≥ `feeling_overflow_threshold` → 吃面加分（避免 FIFO 溢出丢诀窍）
- 距 RMJ 目标 ≤ `rmj_urgency_margin` → 吃面加分（冲 RMJ）
- 否则保守默认不吃面

### Train（训练决策）
- 体力 < `vital_rest_threshold` → 优先休息
- 干劲 < `motivation_outing_threshold` → 优先外出
- 否则选评分最高的训练：`收益总和 + 人头×head_weight + 发光×shining_weight - 失败率×failure_penalty`

### SpecialSelect（隐藏风味）
- 选用量最少的方案（省资源）

### 事件选项
- 选第一个（简单默认）

## 迭代方法

1. 用默认参数跑 1000 局，记录均分作为基线
2. 改一个参数（比如 `head_weight` 从 15 改到 20）
3. 跑 1000 局，比较均分
4. 均分更高 → 保留；更低 → 回退
5. 每次只改一个参数，直到收敛
6. 收敛后可以尝试组合调参

**核心原则**：不要同时改多个参数，否则无法判断哪个改动有效。

## 给 AI 的提示

你可以自动化这个流程：

```
1. 用默认参数跑 1000 局 → 基线均分 X
2. 尝试参数 A=v1 → 跑 1000 局 → 均分 Y
3. Y > X → 保留 A=v1，基线 = Y；否则回退
4. 对每个参数重复
5. 多轮后收敛到局部最优
```

每次只改一个参数，用 1000 局足够看出差异（均分波动约 ±50）。
