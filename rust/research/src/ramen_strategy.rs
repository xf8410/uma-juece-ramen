//! 拉面杯手写策略。

use anyhow::Result;
use rand::prelude::StdRng;
use serde::{Deserialize, Serialize};

use umasim::game::{
    FriendOutState, Game, Person, PersonType, Trainer,
    ramen::{Operation, RamenAction, RamenGame, RamenStage, TrainingType},
};
use umasim::gamedata::EventChoice;

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct RamenStrategy {
    pub head_weight: f64,
    pub shining_weight: f64,
    pub failure_penalty: f64,
    pub big_fail_penalty: f64,
    pub jiban_value: f64,
    pub status_soft_cap: f64,
    pub vital_rest_threshold: i32,
    pub motivation_outing_threshold: i32,
    pub friend_outing_score: f64,
    pub outing_motivation_bonus: f64,
    pub special_overflow_threshold: i32,
    pub feeling_overflow_threshold: i32,
    pub rmj_urgency_margin: i32,
    pub no_ramen_base_score: f64,
    pub eat_ramen_base_score: f64,
    pub friend_click_bonus: f64,
    pub event_vital_bonus: f64,
    pub event_motivation_bonus: f64,
}

impl Default for RamenStrategy {
    fn default() -> Self {
        Self {
            head_weight: 15.0,
            shining_weight: 40.0,
            failure_penalty: 150.0,
            big_fail_penalty: 500.0,
            jiban_value: 12.0,
            status_soft_cap: 40.0,
            vital_rest_threshold: 30,
            motivation_outing_threshold: 5,
            friend_outing_score: 60.0,
            outing_motivation_bonus: 200.0,
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
        let scores: Vec<f64> = actions.iter().map(|a| self.score_action(game, a)).collect();
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
                let v = &option[0].value;
                let mut score = 0.0;
                if vital_low && v.vital > 0 {
                    score += self.event_vital_bonus;
                }
                if motivation_low && v.motivation > 0 {
                    score += self.event_motivation_bonus;
                }
                if !vital_low && !motivation_low {
                    if v.status_pt[min_stat_idx] > 0 {
                        score += v.status_pt[min_stat_idx] as f64;
                    }
                    for i in 0..5 {
                        if i != min_stat_idx && v.status_pt[i] > 0 {
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
            RamenStage::SpecialSelect => self.score_special_select(action),
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
            let gap = rmj_targets[year] - pt;
            if gap > 0 && gap <= self.rmj_urgency_margin {
                score += 40.0;
            }
            if gap <= 0 {
                score += 10.0;
            }
        }
        score
    }

    fn score_special_select(&self, action: &RamenAction) -> f64 {
        let targets = action.special_targets.unwrap_or([0, 0, 0]);
        -targets.iter().sum::<i32>() as f64
    }

    fn vital_eval(vital: i32, max_vital: i32) -> f64 {
        let v = vital.clamp(0, max_vital.max(0));
        if v <= 50 {
            2.0 * v as f64
        } else if v <= 70 {
            100.0 + 1.5 * (v - 50) as f64
        } else {
            130.0 + (v - 70) as f64
        }
    }

    fn status_eval(x: f64, reserve: f64) -> f64 {
        if x >= 0.0 {
            0.0
        } else if x > -reserve {
            -x * x / (2.0 * reserve)
        } else {
            x + reserve * 0.5
        }
    }

    fn score_train(&self, game: &RamenGame, action: &RamenAction) -> f64 {
        let vital = game.uma().vital;
        let max_vital = game.uma().max_vital;
        let motivation = game.uma().motivation;
        let is_early = game.base.turn < 2;
        let not_best = motivation < 5;
        let vital_before = Self::vital_eval(vital, max_vital);

        match &action.operation {
            Operation::Train(train_type) => self.score_training(game, *train_type),
            Operation::Rest => {
                let vital_after = (vital + 50).min(max_vital);
                let gain = Self::vital_eval(vital_after, max_vital) - vital_before;
                let mut score = gain * 0.5;
                if is_early && vital > 15 {
                    score *= 0.3;
                }
                score
            }
            Operation::NormalOuting => {
                let deficit = (self.motivation_outing_threshold - motivation).max(0) as f64;
                let mut score = 20.0 + deficit * 25.0;
                if not_best {
                    score += self.outing_motivation_bonus;
                }
                if is_early && not_best {
                    score += 80.0;
                }
                score
            }
            Operation::FriendOuting => {
                if game.friend.out_used.iter().all(|&b| b) {
                    return 0.0;
                }
                if game.ramen.special_feeling >= self.special_overflow_threshold {
                    return self.friend_outing_score * 0.1;
                }
                let mut score = self.friend_outing_score;
                if game.friend.out_used.iter().filter(|&&b| b).count() < 2 {
                    score += 15.0;
                }
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
            Operation::SuperRamenSelect(_) => 50.0,
        }
    }

    fn score_training(&self, game: &RamenGame, train_type: TrainingType) -> f64 {
        let train = train_type as usize;
        let person_indices: Vec<usize> = game
            .distribution()
            .get(train)
            .into_iter()
            .flatten()
            .copied()
            .filter(|&p| p >= 0 && (p as usize) < game.persons().len())
            .map(|p| p as usize)
            .collect();

        let heads = person_indices
            .iter()
            .filter(|&&p| {
                let ty = game.persons()[p].person_type();
                ty != PersonType::Reporter && ty != PersonType::Yayoi
            })
            .count();
        let shining = game.shining_count(train);
        let buffs = game.calc_training_buff(train).unwrap_or_default();
        let fail_rate = game.calc_training_failure_rate(&buffs, train) as f64;
        let value = game.calc_training_value(&buffs, train).unwrap_or_default();
        let remaining_turns = (78 - game.base.turn).max(0) as f64;
        let reserve = (self.status_soft_cap * remaining_turns / 78.0).max(1.0);
        let mut status_gain = 0.0;

        for i in 0..5 {
            let remaining = (game.uma().five_status_limit[i] - game.uma().five_status[i]) as f64 - 45.0;
            let before = Self::status_eval(-remaining, reserve);
            let after = Self::status_eval(value.status_pt[i] as f64 - remaining, reserve);
            status_gain += after - before;
        }

        let mut score = status_gain
            + value.status_pt[5] as f64
            + heads as f64 * self.head_weight
            + shining as f64 * self.shining_weight;

        for &index in &person_indices {
            let person = &game.persons()[index];
            match person.person_type() {
                PersonType::ScenarioCard => {
                    score += match game.friend.out_state {
                        FriendOutState::UnClicked => 150.0,
                        _ if person.friendship() < 60 => 100.0,
                        _ => 40.0,
                    };
                }
                PersonType::Card if person.friendship() < 80 => {
                    let mut gain: f64 = 7.0;
                    if game.uma().flags.aijiao {
                        gain += 2.0;
                    }
                    if person.hint() {
                        gain += 5.0;
                    }
                    gain = gain.min((80 - person.friendship()) as f64);
                    score += gain * self.jiban_value;
                    if person.hint() {
                        score += 8.0;
                    }
                }
                PersonType::Card if person.hint() => {
                    score += 8.0;
                }
                _ => {}
            }
        }

        if game.friend.out_state == FriendOutState::UnClicked {
            let has_friend = person_indices
                .iter()
                .any(|&p| game.persons()[p].person_type() == PersonType::ScenarioCard);
            if has_friend {
                score += self.friend_click_bonus;
            }
        }

        let fail_probability = (fail_rate / 100.0).clamp(0.0, 1.0);
        if fail_probability > 0.0 {
            let big_probability = if fail_rate > 20.0 {
                fail_probability
            } else {
                0.0
            };
            let failed_score = -(
                self.failure_penalty * (1.0 - big_probability)
                    + self.big_fail_penalty * big_probability
            );
            score = score * (1.0 - fail_probability) + failed_score * fail_probability;
        }

        score
    }
}
