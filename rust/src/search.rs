//! 平面蒙特卡洛搜索核心 — 枚举当前回合全部动作，各自并行模拟 N 局取均分。

use std::time::Instant;

use anyhow::{Result, anyhow};
use rand::{SeedableRng, rngs::StdRng};
use rayon::prelude::*;
use serde::{Deserialize, Serialize};

use umasim::game::{Game, ramen::{RamenGame, RamenStage}};

use crate::ramen_strategy;

#[derive(Deserialize)]
pub struct SearchConfig {
    pub uma_id: u32,
    pub cards: [u32; 6],
    #[serde(default)]
    pub blue_count: [i32; 5],
    #[serde(default)]
    pub extra_count: [i32; 6],
    #[serde(default = "default_search_n")]
    pub search_n: usize,
    #[serde(default)]
    pub strategy: ramen_strategy::RamenStrategy,
}

fn default_search_n() -> usize { 32 }

#[derive(Serialize)]
pub struct SearchResult {
    pub ok: bool,
    pub action: String,
    pub action_display: String,
    pub score_mean: f64,
    pub search_n: usize,
    pub elapsed_ms: u64,
    pub all_actions: Vec<ActionScore>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub error: Option<String>,
}

impl SearchResult {
    pub fn error(message: String) -> Self {
        Self {
            ok: false,
            action: String::new(),
            action_display: String::new(),
            score_mean: 0.0,
            search_n: 0,
            elapsed_ms: 0,
            all_actions: vec![],
            error: Some(message),
        }
    }
}

#[derive(Serialize)]
pub struct ActionScore {
    pub action: String,
    pub action_display: String,
    pub score_mean: f64,
    pub count: usize,
}

pub fn run_flat_search(
    game: &RamenGame,
    search_n: usize,
    strategy: &ramen_strategy::RamenStrategy,
) -> Result<SearchResult> {
    let start = Instant::now();

    let candidates: Vec<(String, String, Box<dyn Fn(&mut RamenGame, &mut StdRng) -> Result<()> + Send + Sync>)>;

    if game.stage == RamenStage::RamenSelect {
        let actions = game.list_combined_ramen_select_actions();
        if actions.is_empty() {
            return Err(anyhow!("No available actions at RamenSelect stage"));
        }
        candidates = actions
            .iter()
            .map(|a| {
                let display = format!("{}", a);
                let ramen = a.ramen;
                let targets = a.special_targets.unwrap_or([0, 0, 0]);
                let desc = format!("{:?}", a);
                (
                    desc,
                    display,
                    Box::new(move |g: &mut RamenGame, _rng: &mut StdRng| {
                        g.apply_combined_ramen_decision(ramen, targets)?;
                        while g.next() {
                            if g.stage == RamenStage::Train { break; }
                        }
                        Ok(())
                    })
                        as Box<dyn Fn(&mut RamenGame, &mut StdRng) -> Result<()> + Send + Sync>,
                )
            })
            .collect();
    } else {
        let actions = game.list_actions()?;
        if actions.is_empty() {
            return Err(anyhow!("No available actions"));
        }
        candidates = actions
            .iter()
            .map(|a| {
                let display = format!("{}", a);
                let desc = format!("{:?}", a);
                let action_clone = a.clone();
                (
                    desc,
                    display,
                    Box::new(move |g: &mut RamenGame, rng: &mut StdRng| {
                        g.apply_action(&action_clone, rng)?;
                        Ok(())
                    })
                        as Box<dyn Fn(&mut RamenGame, &mut StdRng) -> Result<()> + Send + Sync>,
                )
            })
            .collect();
    }

    let results: Vec<(usize, f64, usize)> = candidates
        .par_iter()
        .enumerate()
        .map(|(i, (_, _, apply_fn))| {
            let mut scores: Vec<f64> = Vec::with_capacity(search_n);
            for _ in 0..search_n {
                let mut sim_game = game.clone();
                let mut rng = StdRng::from_os_rng();

                if apply_fn(&mut sim_game, &mut rng).is_err() {
                    continue;
                }

                let _ = sim_game.run_full_game(strategy, &mut rng);

                let score = sim_game.uma().calc_score() as f64;
                scores.push(score);
            }

            let count = scores.len();
            let mean = if count > 0 {
                scores.iter().sum::<f64>() / count as f64
            } else {
                0.0
            };
            (i, mean, count)
        })
        .collect();

    let best = results
        .iter()
        .max_by(|a, b| a.1.partial_cmp(&b.1).unwrap_or(std::cmp::Ordering::Equal))
        .copied();

    let all_actions: Vec<ActionScore> = results
        .iter()
        .map(|(idx, mean, count)| ActionScore {
            action: candidates[*idx].0.clone(),
            action_display: candidates[*idx].1.clone(),
            score_mean: *mean,
            count: *count,
        })
        .collect();

    let best_idx = best.map(|b| b.0).unwrap_or(0);
    let best_mean = best.map(|b| b.1).unwrap_or(0.0);

    Ok(SearchResult {
        ok: true,
        action: candidates[best_idx].0.clone(),
        action_display: candidates[best_idx].1.clone(),
        score_mean: best_mean,
        search_n,
        elapsed_ms: start.elapsed().as_millis() as u64,
        all_actions,
        error: None,
    })
}
