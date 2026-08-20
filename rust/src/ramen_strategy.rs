//! 拉面杯手写策略 — 阈值/系数版
//!
//! 核心策略：
//! 1. 开局前2回合优先普通外出提升心情到绝好调（1.1x 训练倍率）
//! 2. 体力低于阈值优先休息
//! 3. 训练选总增益最高（含人头/发光加权）
//! 4. 库存满了优先吃面
//! 5. 友人未点击时优先有友人的训练

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
    pub head_weight: f64,
    pub shining_weight: f64,
    pub failure_penalty: f64,

    // ── 休息/外出 ──
    pub vital_rest_threshold: i32,
    pub motivation_outing_threshold: i32,
    pub friend_outing_score: f64,

    // ── 万能材料管理 ──
    pub special_overflow_threshold: i32,

    // ── 吃面决策 ──
    pub feeling_overflow_threshold: i32,
    pub rmj_urgency_margin: i32,
    pub no_ramen_base_score: f64,
    pub eat_ramen_base_score: f64,

    // ── 友人点击 ──
    pub friend_click_bonus: f64,

    // ── 事件选项 ──
    pub event_vital_bonus: f64,
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

                let choice = &option[0];
                let v = &choice.value;

                let mut score = 0.0;

                if vital_low && v.vital > 0 {
                    score += self.event_vital_bonus;
                }
                if motivation_low && v.motivation > 0 {
                    score += self.event_motivation_bonus;
                }

                if !vital_low && !motivation_low {
                    if v.status_pt.len() > min_stat_idx && v.status_pt[min_stat_idx] > 0 {
                        score += v.status_pt[min_stat_idx] as f64;
                    }
                    for i in 0..5 {
                        if i != min_stat_idx && v.status_pt.len() > i && v.status_pt[i] > 0 {
                            score += v.status_pt[i] as f64 * 0.3;
                        }
                    }
                }

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

    fn score_special_select(&self, _game: &RamenGame, action: &RamenAction) -> f64 {
        let targets = action.special_targets.unwrap_or([0, 0, 0]);
        let used: i32 = targets.iter().sum();
        -used as f64
    }

    /// Train 阶段：评估训练/休息/外出/比赛
    fn score_train(&self, game: &RamenGame, action: &RamenAction) -> f64 {
        let vital = game.uma().vital;
        let motivation = game.uma().motivation;
        let turn = game.base.turn;
        let is_early = turn < 2; // 开局前2回合
        let not_best = motivation < 4; // 未到绝好调

        match &action.operation {
            Operation::Train(train_type) => {
                self.score_training(game, *train_type)
            }
            Operation::Rest => {
                let deficit = (self.vital_rest_threshold - vital).max(0) as f64;
                let mut score = 50.0 + deficit * 3.0;
                // 开局前2回合：除非体力极低，否则不休息
                if is_early && vital > 15 {
                    score *= 0.3;
                }
                score
            }
            // 普通外出：提升干劲
            Operation::NormalOuting => {
                let deficit = (self.motivation_outing_threshold - motivation).max(0) as f64;
                let mut score = 20.0 + deficit * 25.0;
                // ★ 开局优先：未到绝好调时大幅加分
                if is_early && not_best {
                    score += 80.0;
                }
                score
            }
            // 友人外出：万能材料 + 友人事件
            Operation::FriendOuting => {
                let special = game.ramen.special_feeling;
                let all_used = game.friend.out_used.iter().all(|&b| b);

                if all_used {
                    return 0.0;
                }
                if special >= self.special_overflow_threshold {
                    return self.friend_outing_score * 0.1;
                }

                let mut score = self.friend_outing_score;
                let used = game.friend.out_used.iter().filter(|&&b| b).count();
                if used < 2 {
                    score += 15.0;
                }
                // 开局也可以友人外出（如果需要点友人）
                if is_early && game.friend.out_state == FriendOutState::UnClicked {
                    score += 30.0;
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

    fn score_training(&self, game: &RamenGame, train_type: TrainingType) -> f64 {
        let train = train_type as usize;

        let heads = game.distribution().get(train)
            .map(|d| d.iter().filter(|&&p| {
                p >= 0 && (p as usize) < game.persons().len()
                    && game.persons()[p as usize].person_type != PersonType::Reporter
                    && game.persons()[p as usize].person_type != PersonType::Yayoi
            }).count())
            .unwrap_or(0);

        let shining = game.shining_count(train);

        let buffs = game.calc_training_buff(train).unwrap_or_default();
        let fail_rate = game.calc_training_failure_rate(&buffs, train);

        let value = game.calc_training_value(&buffs, train).unwrap_or_default();
        let total_gain: i32 = value.status_pt.iter().sum();

        let mut score = total_gain as f64
            + heads as f64 * self.head_weight
            + shining as f64 * self.shining_weight
            - fail_rate as f64 * self.failure_penalty;

        // 友人未点击时，有友人的训练加分
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
