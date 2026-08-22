//! 参数定义与向量互转 — 优化器的参数空间。
//!
//! 18 维参数与 `RamenStrategy` 字段一一对应，
//! `PARAMS` 定义每一维的取值范围、初值和整数约束。

use rand::Rng;

use uma_jni::ramen_strategy::RamenStrategy;

pub struct ParamDef {
    pub name: &'static str,
    pub min: f64,
    pub max: f64,
    pub initial: f64,
    pub is_int: bool,
}

pub static PARAMS: &[ParamDef] = &[
    // ── 训练决策 ──
    ParamDef { name: "head_weight",              min: 0.0,   max: 100.0, initial: 15.0,  is_int: false },
    ParamDef { name: "shining_weight",           min: 0.0,   max: 200.0, initial: 40.0,  is_int: false },
    ParamDef { name: "failure_penalty",          min: 10.0,  max: 500.0, initial: 150.0, is_int: false },
    ParamDef { name: "big_fail_penalty",         min: 100.0, max: 2000.0, initial: 500.0, is_int: false },
    ParamDef { name: "jiban_value",              min: 0.0,   max: 100.0, initial: 12.0,  is_int: false },
    ParamDef { name: "status_soft_cap",          min: 10.0,  max: 100.0, initial: 40.0,  is_int: false },
    // ── 休息/外出 ──
    ParamDef { name: "vital_rest_threshold",     min: 10.0,  max: 60.0, initial: 30.0,  is_int: true  },
    ParamDef { name: "motivation_outing_thresh", min: 3.0,   max: 5.0,   initial: 5.0,   is_int: true  },
    ParamDef { name: "friend_outing_score",      min: 0.0,   max: 200.0, initial: 60.0,  is_int: false },
    ParamDef { name: "outing_motivation_bonus",  min: 0.0,   max: 400.0, initial: 200.0, is_int: false },
    // ── 万能材料 ──
    ParamDef { name: "special_overflow_thresh",  min: 1.0,   max: 4.0,   initial: 3.0,   is_int: true  },
    // ── 吃面 ──
    ParamDef { name: "feeling_overflow_thresh",  min: 3.0,   max: 15.0,  initial: 8.0,   is_int: true  },
    ParamDef { name: "rmj_urgency_margin",       min: 100.0, max: 500.0, initial: 300.0, is_int: true  },
    ParamDef { name: "no_ramen_base_score",      min: 0.0,   max: 200.0, initial: 100.0, is_int: false },
    ParamDef { name: "eat_ramen_base_score",     min: 0.0,   max: 200.0, initial: 50.0,  is_int: false },
    // ── 友人 ──
    ParamDef { name: "friend_click_bonus",       min: 0.0,   max: 100.0, initial: 25.0,  is_int: false },
    // ── 事件 ──
    ParamDef { name: "event_vital_bonus",        min: 0.0,   max: 100.0, initial: 30.0,  is_int: false },
    ParamDef { name: "event_motivation_bonus",   min: 0.0,   max: 100.0, initial: 40.0,  is_int: false },
];

pub const DIMS: usize = 18;

/// 参数向量 → 策略结构体（顺序与 PARAMS 一致）
pub fn vec_to_strategy(vec: &[f64]) -> RamenStrategy {
    RamenStrategy {
        head_weight: vec[0],
        shining_weight: vec[1],
        failure_penalty: vec[2],
        big_fail_penalty: vec[3],
        jiban_value: vec[4],
        status_soft_cap: vec[5],
        vital_rest_threshold: vec[6] as i32,
        motivation_outing_threshold: vec[7] as i32,
        friend_outing_score: vec[8],
        outing_motivation_bonus: vec[9],
        special_overflow_threshold: vec[10] as i32,
        feeling_overflow_threshold: vec[11] as i32,
        rmj_urgency_margin: vec[12] as i32,
        no_ramen_base_score: vec[13],
        eat_ramen_base_score: vec[14],
        friend_click_bonus: vec[15],
        event_vital_bonus: vec[16],
        event_motivation_bonus: vec[17],
    }
}

/// 策略结构体 → 参数向量
pub fn strategy_to_vec(s: &RamenStrategy) -> Vec<f64> {
    vec![
        s.head_weight, s.shining_weight, s.failure_penalty, s.big_fail_penalty,
        s.jiban_value, s.status_soft_cap,
        s.vital_rest_threshold as f64, s.motivation_outing_threshold as f64,
        s.friend_outing_score, s.outing_motivation_bonus,
        s.special_overflow_threshold as f64, s.feeling_overflow_threshold as f64,
        s.rmj_urgency_margin as f64,
        s.no_ramen_base_score, s.eat_ramen_base_score,
        s.friend_click_bonus, s.event_vital_bonus, s.event_motivation_bonus,
    ]
}

/// 逐维夹到 [min, max] 并对整数维取整
pub fn clamp_vec(vec: &mut [f64]) {
    for i in 0..DIMS {
        if vec[i] < PARAMS[i].min { vec[i] = PARAMS[i].min; }
        if vec[i] > PARAMS[i].max { vec[i] = PARAMS[i].max; }
        if PARAMS[i].is_int { vec[i] = vec[i].round(); }
    }
}

/// Box-Muller 正态采样
pub fn sample_normal(rng: &mut rand::rngs::StdRng) -> f64 {
    let u1: f64 = rng.random::<f64>().max(1e-10);
    let u2: f64 = rng.random::<f64>();
    (-2.0 * u1.ln()).sqrt() * (2.0 * std::f64::consts::PI * u2).cos()
}

/// 全空间均匀随机采样（整数维取整）
pub fn sample_random(rng: &mut rand::rngs::StdRng) -> Vec<f64> {
    let mut vec = vec![0.0; DIMS];
    for i in 0..DIMS {
        vec[i] = PARAMS[i].min + rng.random::<f64>() * (PARAMS[i].max - PARAMS[i].min);
        if PARAMS[i].is_int { vec[i] = vec[i].round(); }
    }
    vec
}

/// 归一化到 [0,1]^DIMS（贝叶斯优化用）
pub fn normalize(vec: &[f64]) -> Vec<f64> {
    vec.iter().enumerate()
        .map(|(i, &v)| {
            let range = PARAMS[i].max - PARAMS[i].min;
            ((v - PARAMS[i].min) / range).clamp(0.0, 1.0)
        })
        .collect()
}

/// 反归一化（整数维取整）
pub fn denormalize(nvec: &[f64]) -> Vec<f64> {
    let mut vec = vec![0.0; DIMS];
    for i in 0..DIMS {
        let range = PARAMS[i].max - PARAMS[i].min;
        vec[i] = PARAMS[i].min + nvec[i].clamp(0.0, 1.0) * range;
        if PARAMS[i].is_int { vec[i] = vec[i].round(); }
    }
    vec
}
