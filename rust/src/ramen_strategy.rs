//! 拉面杯手写策略 — 阈值/系数版（借鉴 hzyhhzy/UmaAi 通用逻辑）
//!
//! 核心策略：
//! 1. 开局前2回合优先普通外出提升心情到绝好调（1.1x 训练倍率）
//! 2. 体力分段估值：≤50 = 2.0x/点, 50-70 = 1.5x, 70+ = 1.0x
//! 3. 属性软截断：接近上限时训练价值骤降（statusSoftFunction）
//! 4. 羁绊独立价值：每个支援卡羁绊<80 时每点 12 分
//! 5. 失败率分级：>20% 大失败(-500), ≤20% 小失败(-150)
//! 6. 友人卡分层：未点击=150, 羁绊<60=100, ≥60=40
//! 7. 库存满优先吃面

use anyhow::Result;
use rand::prelude::StdRng;
use serde::{Deserialize, Serialize};

use umasim::game::{
    FriendOutState, Game, PersonType, Trainer,
    ramen::{Operation, RamenAction, RamenGame, RamenStage, TrainingType},
};
use umasim::gamedata::EventChoice;

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct RamenStrategy {
    // ── 训练决策 ──
    pub head_weight: f64,
    pub shining_weight: f64,
    pub failure_penalty: f64,
    pub big_fail_penalty: f64,
    pub jiban_value: f64,
    pub status_soft_cap: f64,

    // ── 休息/外出 ──
    pub vital_rest_threshold: i32,
    pub motivation_outing_threshold: i32,
    pub friend_outing_score: f64,
    pub outing_motivation_bonus: f64,

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
        &self, game: &RamenGame, actions: &[RamenAction], _rng: &mut StdRng,
    ) -> Result<usize> {
        if actions.len() <= 1 { return Ok(0); }
        let scores: Vec<f64> = actions.iter().map(|a| self.score_action(game, a)).collect();
        let best = scores.iter().enumerate()
            .max_by(|(_, a), (_, b)| a.partial_cmp(b).unwrap_or(std::cmp::Ordering::Equal))
            .map(|(i, _)| i).unwrap_or(0);
        Ok(best)
    }

    fn select_choice(
        &self, game: &RamenGame, choices: &[Vec<EventChoice>], _rng: &mut StdRng,
    ) -> Result<usize> {
        if choices.is_empty() { return Ok(0); }
        let vital = game.uma().vital;
        let motivation = game.uma().motivation;
        let five_status = &game.uma().five_status;
        let min_stat_idx = (0..5usize).min_by(|&a, &b| five_status[a].cmp(&five_status[b])).unwrap_or(0);
        let vital_low = vital < self.vital_rest_threshold;
        let motivation_low = motivation < self.motivation_outing_threshold;
        let scores: Vec<f64> = choices.iter().map(|option| {
            if option.is_empty() { return 0.0; }
            let choice = &option[0];
            let v = &choice.value;
            let mut score = 0.0;
            if vital_low && v.vital > 0 { score += self.event_vital_bonus; }
            if motivation_low && v.motivation > 0 { score += self.event_motivation_bonus; }
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
            if v.vital > 0 && !vital_low { score += v.vital as f64 * 0.5; }
            if v.motivation > 0 && !motivation_low { score += v.motivation as f64 * 0.5; }
            score
        }).collect();
        let best = scores.iter().enumerate()
            .max_by(|(_, a), (_, b)| a.partial_cmp(b).unwrap_or(std::cmp::Ordering::Equal))
            .map(|(i, _)| i).unwrap_or(0);
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
            } else { 0.0 };
            return self.no_ramen_base_score - overflow_penalty;
        }
        let mut score = self.eat_ramen_base_score;
        if stock_total >= self.feeling_overflow_threshold { score += 30.0; }
        let year = game.current_year() as usize;
        let pt = game.ramen.scenario_pt;
        let rmj_targets = [1500, 3000, 3500];
        if year < rmj_targets.len() {
            let target = rmj_targets[year];
            let gap = target - pt;
            if gap > 0 && gap <= self.rmj_urgency_margin { score += 40.0; }
            if gap <= 0 { score += 10.0; }
        }
        score
    }

    fn score_special_select(&self, _game: &RamenGame, action: &RamenAction) -> f64 {
        let targets = action.special_targets.unwrap_or([0, 0, 0]);
        -targets.iter().sum::<i32>() as f64
    }

    /// 体力分段估值（借鉴 hzyhhzy）
    fn vital_evaluation(vital: i32, max_vital: i32) -> f64 {
        if vital <= 50 { 2.0 * vital as f64 }
        else if vital <= 70 { 100.0 + 1.5 * (vital - 50) as f64 }
        else if vital <= max_vital { 130.0 + 1.0 * (vital - 70) as f64 }
        else { Self::vital_evaluation(max_vital, max_vital) }
    }

    /// 属性软截断（借鉴 hzyhhzy statusSoftFunction）
    fn status_soft(x: f64, reserve: f64) -> f64 {
        let r_inv = 1.0 / (2.0 * reserve);
        if x >= 0.0 { 0.0 }
        else if x > -reserve { -x * x * r_inv }
        else { x + 0.5 * reserve }
    }

    fn score_train(&self, game: &RamenGame, action: &RamenAction) -> f64 {
        let vital = game.uma().vital;
        let max_vital = game.uma().max_vital;
        let motivation = game.uma().motivation;
        let turn = game.base.turn;
        let is_early = turn < 2;
        let not_best = motivation < 5;
        let vital_before = Self::vital_evaluation(vital, max_vital);

        match &action.operation {
            Operation::Train(train_type) => {
                self.score_training(game, *train_type, vital_before, max_vital)
            }
            Operation::Rest => {
                let vital_after = (vital + 50).min(max_vital);
                let gain = Self::vital_evaluation(vital_after, max_vital) - vital_before;
                let mut score = gain * 0.5;
                if is_early && vital > 15 { score *= 0.3; }
                score
            }
            Operation::NormalOuting => {
                let deficit = (self.motivation_outing_threshold - motivation).max(0) as f64;
                let mut score = 20.0 + deficit * 25.0;
                if not_best { score += self.outing_motivation_bonus; }
                if is_early && not_best { score += 80.0; }
                score
            }
            Operation::FriendOuting => {
                let special = game.ramen.special_feeling;
                let all_used = game.friend.out_used.iter().all(|&b| b);
                if all_used { return 0.0; }
                if special >= self.special_overflow_threshold {
                    return self.friend_outing_score * 0.1;
                }
                let mut score = self.friend_outing_score;
                let used = game.friend.out_used.iter().filter(|&&b| b).count();
                if used < 2 { score += 15.0; }
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

    fn score_training(&self, game: &RamenGame, train_type: TrainingType, vital_before: f64, max_vital: i32) -> f64 {
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
        let fail_rate = game.calc_training_failure_rate(&buffs, train) as f64;
        let value = game.calc_training_value(&buffs, train).unwrap_or_default();

        // ── 属性增益 + 软截断 ──
        let five_status = &game.uma().five_status;
        let five_status_limit = &game.uma().five_status_limit;
        let remain_turn = 78 - game.base.turn;
        let reserve = self.status_soft_cap * remain_turn as f64 / 78.0;
        let final_bonus = 45.0;

        let mut status_score = 0.0;
        for sta in 0..5 {
            let remain = (five_status_limit[sta] - five_status[sta]) as f64 - final_bonus;
            let s0 = Self::status_soft(-remain, reserve);
            let s1 = Self::status_soft(value.status_pt[sta] as f64 - remain, reserve);
            status_score += 6.0 * (s1 - s0);
        }
        let pt_score = value.status_pt[5] as f64 * 2.0;

        let mut score = status_score + pt_score
            + heads as f64 * self.head_weight
            + shining as f64 * self.shining_weight;

        // ── 羁绊价值 ──
        for j in 0..5 {
            let pi = match game.distribution().get(train).and_then(|d| d.get(j)) {
                Some(&p) if p >= 0 && (p as usize) < game.persons().len() => p as usize,
                _ => break,
            };
            let person = &game.persons()[pi];
            if person.person_type == PersonType::ScenarioCard {
                match game.friend.out_state {
                    FriendOutState::UnClicked => score += 150.0,
                    _ => {
                        let fr = person.friendship;
                        if fr < 60 { score += 100.0; }
                        else { score += 40.0; }
                    }
                }
            } else if person.person_type == PersonType::Card {
                let fr = person.friendship;
                if fr < 80 {
                    let mut jiban_add = 7.0;
                    if game.uma().flags.aijiao { jiban_add += 2.0; }
                    if person.is_hint { jiban_add += 5.0; }
                    jiban_add = jiban_add.min((80 - fr) as f64);
                    score += jiban_add * self.jiban_value;
                }
                if person.is_hint {
                    score += 8.0;
                }
            }
        }

        // 友人未点击时额外加分
        if game.friend.out_state == FriendOutState::UnClicked {
            let has_friend = game.distribution().get(train)
                .map(|d| d.iter().any(|&p| {
                    p >= 0 && (p as usize) < game.persons().len()
                        && game.persons()[p as usize].person_type == PersonType::ScenarioCard
                }))
                .unwrap_or(false);
            if has_friend { score += self.friend_click_bonus; }
        }

        // ── 失败率分级 ──
        if fail_rate > 0.0 {
            let big_fail_prob = if fail_rate > 20.0 { fail_rate } else { 0.0 };
            let fail_value_avg = 0.01 * big_fail_prob * (-self.big_fail_penalty)
                + (1.0 - 0.01 * big_fail_prob) * (-self.failure_penalty);
            score = 0.01 * fail_rate * fail_value_avg + (1.0 - 0.01 * fail_rate) * score;
        }

        // ── 体力变化 ──
        let vital_after = (game.uma().vital + value.vital).max(0).min(max_vital);
        let vital_change = Self::vital_evaluation(vital_after, max_vital) - vital_before;
        score += vital_change * 0.3;

        score
    }
}
