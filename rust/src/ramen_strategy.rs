//! 拉面杯手写策略 — 最简阈值/系数版
//!
//! 所有参数都是 struct 字段，可外部调整后重新跑批量模拟。
//! 目标：提供一个"不傻"的基线策略，让 AI 或人可以迭代调参。

use anyhow::Result;
use rand::prelude::StdRng;
use serde::{Deserialize, Serialize};

use umasim::game::{
    FriendOutState, Game, PersonType, Trainer,
    ramen::{Operation, RamenAction, RamenGame, RamenStage, TrainingType},
};
use umasim::gamedata::EventChoice;

/// 可调参数集
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct RamenStrategy {
    // ── 训练决策 ──
    /// 每个人头的评分权重
    pub head_weight: f64,
    /// 每个发光的评分权重（发光训练比普通训练多填诀窍槽→多材料→多吃面→多RMJ PT）
    pub shining_weight: f64,
    /// 失败率惩罚系数
    pub failure_penalty: f64,

    // ── 休息/外出 ──
    /// 体力低于此值时优先休息
    pub vital_rest_threshold: i32,
    /// 干劲低于此值时优先普通外出（回干劲，不给材料）
    pub motivation_outing_threshold: i32,
    /// 友人外出的基础分（给万能材料×2 + 友人事件，与普通外出完全不同）
    pub friend_outing_score: f64,

    // ── 万能材料管理 ──
    /// 万能材料达到此值时不再友人外出（避免溢出）
    pub special_overflow_threshold: i32,

    // ── 吃面决策 ──
    /// 诀窍库存总数达到此值时优先吃面（避免 FIFO 溢出）
    pub feeling_overflow_threshold: i32,
    /// 距离 RMJ 目标差此值以内时优先吃面
    pub rmj_urgency_margin: i32,
    /// 不吃面时的基础分
    pub no_ramen_base_score: f64,
    /// 吃面时的基础分
    pub eat_ramen_base_score: f64,

    // ── 友人点击 ──
    /// 友人未点击时，有友人的训练加分（友人必须先点一次才能解锁外出）
    pub friend_click_bonus: f64,

    // ── 事件选项 ──
    /// 事件选项给体力的加分
    pub event_vital_bonus: f64,
    /// 事件选项给干劲的加分
    pub event_motivation_bonus: f64,
}

impl Default for RamenStrategy {
    fn default() -> Self {
        Self {
            head_weight: 15.0,
            shining_weight: 40.0,
            failure_penalty: 2.0,
            vital_rest_threshold: 30,
            motivation_outing_threshold: 4,
            friend_outing_score: 60.0,
            special_overflow_threshold: 3,
            feeling_overflow_threshold: 8,
            rmj_urgency_margin: 300,
            no_ramen_base_score: 100.0,
            eat_ramen_base_score: 50.0,
            friend_click_bonus: 25.0,
            event_vital_bonus: 30.0,
            event_motivation_bonus: 40.0,
        }
    }
}

impl Trainer<RamenGame> for RamenStrategy {
    fn select_action(
        &self,
        game: &RamenGame,
        actions: &[RamenAction],
        _rng: &mut StdRng,
    ) -> Result<usize> {
        if actions.len() <= 1 {
            return Ok(0);
        }

        let scores: Vec<f64> = actions
            .iter()
            .map(|a| self.score_action(game, a))
            .collect();

        let best = scores
            .iter()
            .enumerate()
            .max_by(|(_, a), (_, b)| a.partial_cmp(b).unwrap_or(std::cmp::Ordering::Equal))
            .map(|(i, _)| i)
            .unwrap_or(0);

        Ok(best)
    }

    /// 事件选项：优先选有体力和干劲的；都不缺就选最缺的属性
    fn select_choice(
        &self,
        game: &RamenGame,
        choices: &[Vec<EventChoice>],
        _rng: &mut StdRng,
    ) -> Result<usize> {
        if choices.is_empty() {
            return Ok(0);
        }

        let vital = game.uma().vital;
        let motivation = game.uma().motivation;
        let five_status = &game.uma().five_status;

        // 找当前最低属性（0=速 1=耐 2=力 3=根 4=智）
        let min_stat_idx = (0..5usize)
            .min_by(|&a, &b| five_status[a].cmp(&five_status[b]))
            .unwrap_or(0);

        let vital_low = vital < self.vital_rest_threshold;
        let motivation_low = motivation < self.motivation_outing_threshold;

        let scores: Vec<f64> = choices
            .iter()
            .map(|option| {
                if option.is_empty() {
                    return 0.0;
                }

                // 取第一个分支做代表（简化：不按概率加权）
                let choice = &option[0];
                let v = &choice.value;

                let mut score = 0.0;

                // 体力或干劲低时，优先选给体力/干劲的
                if vital_low && v.vital > 0 {
                    score += self.event_vital_bonus;
                }
                if motivation_low && v.motivation > 0 {
                    score += self.event_motivation_bonus;
                }

                // 体力和干劲都不缺时，优先选给最缺属性的
                if !vital_low && !motivation_low {
                    // 给最缺属性的加分
                    if v.status_pt.len() > min_stat_idx && v.status_pt[min_stat_idx] > 0 {
                        score += v.status_pt[min_stat_idx] as f64;
                    }
                    // 也给其他属性一点分（不是 0 就有价值）
                    for i in 0..5 {
                        if i != min_stat_idx && v.status_pt.len() > i && v.status_pt[i] > 0 {
                            score += v.status_pt[i] as f64 * 0.3;
                        }
                    }
                }

                // 无论什么情况，体力/干劲都有基础价值
                if v.vital > 0 && !vital_low {
                    score += v.vital as f64 * 0.5;
                }
                if v.motivation > 0 && !motivation_low {
                    score += v.motivation as f64 * 0.5;
                }

                score
            })
            .collect();

        let best = scores
            .iter()
            .enumerate()
            .max_by(|(_, a), (_, b)| a.partial_cmp(b).unwrap_or(std::cmp::Ordering::Equal))
            .map(|(i, _)| i)
            .unwrap_or(0);

        Ok(best)
    }
}

impl RamenStrategy {
    fn score_action(&self, game: &RamenGame, action: &RamenAction) -> f64 {
        match game.stage {
            RamenStage::RamenSelect => self.score_ramen_select(game, action),
            RamenStage::SpecialSelect => self.score_special_select(game, action),
            RamenStage::Train => self.score_train(game, action),
            _ => 0.0,
        }
    }

    /// RamenSelect：评估吃哪碗面（含不吃）
    fn score_ramen_select(&self, game: &RamenGame, action: &RamenAction) -> f64 {
        let stock_total: i32 = game.ramen.feeling_stock.iter().sum();

        if action.ramen.is_none() {
            let overflow_penalty = if stock_total >= self.feeling_overflow_threshold {
                (stock_total - self.feeling_overflow_threshold) as f64 * 20.0
            } else {
                0.0
            };
            return self.no_ramen_base_score - overflow_penalty;
        }

        let mut score = self.eat_ramen_base_score;

        if stock_total >= self.feeling_overflow_threshold {
            score += 30.0;
        }

        let year = game.current_year() as usize;
        let pt = game.ramen.scenario_pt;
        let rmj_targets = [1500, 3000, 3500];
        if year < rmj_targets.len() {
            let target = rmj_targets[year];
            let gap = target - pt;
            if gap > 0 && gap <= self.rmj_urgency_margin {
                score += 40.0;
            }
            if gap <= 0 {
                score += 10.0;
            }
        }

        score
    }

    /// SpecialSelect：选最少隐藏风味用量
    fn score_special_select(&self, _game: &RamenGame, action: &RamenAction) -> f64 {
        let targets = action.special_targets.unwrap_or([0, 0, 0]);
        let used: i32 = targets.iter().sum();
        -used as f64
    }

    /// Train：评估训练/休息/外出/比赛等
    fn score_train(&self, game: &RamenGame, action: &RamenAction) -> f64 {
        let vital = game.uma().vital;
        let motivation = game.uma().motivation;

        match &action.operation {
            Operation::Train(train_type) => {
                self.score_training(game, *train_type)
            }
            Operation::Rest => {
                let deficit = (self.vital_rest_threshold - vital).max(0) as f64;
                50.0 + deficit * 3.0
            }
            // 普通外出：回干劲，不给材料，与友人外出完全不同
            Operation::NormalOuting => {
                let deficit = (self.motivation_outing_threshold - motivation).max(0) as f64;
                20.0 + deficit * 25.0
            }
            // 友人外出：给万能材料×2 + 友人事件，与普通外出完全不同
            Operation::FriendOuting => {
                let special = game.ramen.special_feeling;
                let all_used = game.friend.out_used.iter().all(|&b| b);

                if all_used {
                    return 0.0;
                }

                // 万能材料快满了 → 友人外出会溢出 → 大幅扣分
                if special >= self.special_overflow_threshold {
                    return self.friend_outing_score * 0.1;
                }

                let mut score = self.friend_outing_score;
                let used = game.friend.out_used.iter().filter(|&&b| b).count();
                if used < 2 {
                    score += 15.0;
                }
                score
            }
            Operation::Race => 10.0,
            Operation::Clinic => {
                if game.uma().flags.ill { 80.0 } else { 0.0 }
            }
            Operation::RegionSelect(_) => 50.0,
            Operation::StageOnly => 0.0,
        }
    }

    /// 计算某个训练类型的评分
    fn score_training(&self, game: &RamenGame, train_type: TrainingType) -> f64 {
        let train = train_type as usize;

        // 人头数（排除理事长和记者）
        let heads = game.distribution().get(train)
            .map(|d| d.iter().filter(|&&p| {
                p >= 0 && (p as usize) < game.persons().len()
                    && game.persons()[p as usize].person_type != PersonType::Reporter
                    && game.persons()[p as usize].person_type != PersonType::Yayoi
            }).count())
            .unwrap_or(0);

        // 发光数（闪彩圈训练比普通训练多填诀窍槽→多材料→多吃面→多RMJ PT）
        let shining = game.shining_count(train);

        // 失败率
        let buffs = game.calc_training_buff(train).unwrap_or_default();
        let fail_rate = game.calc_training_failure_rate(&buffs, train);

        // 训练值（含拉面buff）
        let value = game.calc_training_value(&buffs, train).unwrap_or_default();
        let total_gain: i32 = value.status_pt.iter().sum();

        let mut score = total_gain as f64
            + heads as f64 * self.head_weight
            + shining as f64 * self.shining_weight
            - fail_rate as f64 * self.failure_penalty;

        // 友人点击加成：友人未点击时，有友人的训练加分
        if game.friend.out_state == FriendOutState::UnClicked {
            let has_friend = game.distribution().get(train)
                .map(|d| d.iter().any(|&p| {
                    p >= 0 && (p as usize) < game.persons().len()
                        && game.persons()[p as usize].person_type == PersonType::ScenarioCard
                }))
                .unwrap_or(false);
            if has_friend {
                score += self.friend_click_bonus;
            }
        }

        score
    }
}
