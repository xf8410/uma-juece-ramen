//! JNI bridge + batch runner for umaai-rs ramen.
//!
//! - `nativeInit` / `nativeSearch` / `nativeVersion`: JNI exports (jni-support feature)
//! - `ramen_strategy`: handwritten strategy module (always available)
//! - `run_flat_search`: flat Monte Carlo search core (always available)

pub mod ramen_strategy;

use std::sync::OnceLock;
use std::time::Instant;

use anyhow::{Result, anyhow};
use rand::{SeedableRng, rngs::StdRng};
use rayon::prelude::*;
use serde::{Deserialize, Serialize};

use umasim::game::{
    Game, Trainer,
    ramen::{RamenGame, RamenStage},
    InheritInfo, PersonType,
};
use umasim::gamedata::init_global;

static INITIALIZED: OnceLock<()> = OnceLock::new();

// ── Input/output structs ────────────────────────────────────────────

#[derive(Deserialize)]
pub struct RuntimeState {
    pub turn: i32,
    #[serde(default)]
    pub stats: Option<RuntimeStats>,
    #[serde(default)]
    pub ramen: Option<RuntimeRamen>,
    #[serde(default)]
    pub trainings: Vec<serde_json::Value>,
}

#[derive(Deserialize)]
pub struct RuntimeStats {
    #[serde(default)] pub speed: i32,
    #[serde(default)] pub stamina: i32,
    #[serde(default)] pub power: i32,
    #[serde(default)] pub guts: i32,
    #[serde(default)] pub wiz: i32,
    #[serde(default)] pub skill_point: i32,
    #[serde(default)] pub vital: i32,
    #[serde(default)] pub max_vital: i32,
    /// 干劲：hlpatch 可能返回整数或字符串 "Best"/"Good"/"Normal"/"Bad"/"Worst"
    /// 上游约定：1=绝不调, 2=不调, 3=普通, 4=好调, 5=绝好调（越大越好）
    #[serde(default, deserialize_with = "deserialize_motivation")]
    pub motivation: i32,
}

#[derive(Deserialize)]
pub struct RuntimeRamen {
    #[serde(default)] pub checkpoint_pt: i32,
    #[serde(default)] pub sozai: Vec<i32>,
    #[serde(default)] pub special_feeling_num: i32,
    #[serde(default)] pub selected_region_ids: Vec<i32>,
    #[serde(default)] pub acquisition_gauges: Vec<i32>,
}

#[derive(Deserialize)]
pub struct SearchConfig {
    pub uma_id: u32,
    pub cards: [u32; 6],
    #[serde(default)] pub blue_count: [i32; 5],
    #[serde(default)] pub extra_count: [i32; 6],
    #[serde(default = "default_search_n")] pub search_n: usize,
    #[serde(default)] pub strategy: ramen_strategy::RamenStrategy,
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

#[derive(Serialize)]
pub struct ActionScore {
    pub action: String,
    pub action_display: String,
    pub score_mean: f64,
    pub count: usize,
}

// ── Custom deserializer for motivation ─────────────────────────────

/// 接受整数或字符串，映射为上游约定：1=绝不调 ~ 5=绝好调（越大越好）
fn deserialize_motivation<'de, D: serde::Deserializer<'de>>(d: D) -> Result<i32, D::Error> {
    use serde::de::Error;
    let v: serde_json::Value = Deserialize::deserialize(d)?;
    match v {
        serde_json::Value::Number(n) => Ok(n.as_i64().unwrap_or(3) as i32),
        serde_json::Value::String(s) => {
            let lower = s.to_lowercase();
            let val = match lower.as_str() {
                "best" | "perfect" | "絶好調" | "絶好" | "绝好调" | "绝好" => 5,
                "good" | "好調" | "好" | "好调" => 4,
                "normal" | "普通" => 3,
                "bad" | "不調" | "不" | "不调" => 2,
                "worst" | "絶不調" | "絶不" | "绝不调" | "绝不" => 1,
                _ => s.parse::<i32>().unwrap_or(3),
            };
            Ok(val)
        }
        _ => Ok(3),
    }
}

// ── State injection ──────────────────────────────────────────────────

pub fn inject_state(game: &mut RamenGame, state: &RuntimeState) -> Result<()> {
    let internal_turn = (state.turn - 1).max(0);
    game.base.turn = internal_turn;

    if let Some(stats) = &state.stats {
        game.base.uma.five_status[0] = stats.speed;
        game.base.uma.five_status[1] = stats.stamina;
        game.base.uma.five_status[2] = stats.power;
        game.base.uma.five_status[3] = stats.guts;
        game.base.uma.five_status[4] = stats.wiz;
        game.base.uma.skill_pt = stats.skill_point;
        game.base.uma.vital = stats.vital;
        game.base.uma.max_vital = stats.max_vital.max(stats.vital);
        game.base.uma.motivation = stats.motivation;
    }

    if let Some(ramen) = &state.ramen {
        game.ramen.scenario_pt = ramen.checkpoint_pt;
        if ramen.sozai.len() >= 3 {
            game.ramen.feeling_stock = [ramen.sozai[0], ramen.sozai[1], ramen.sozai[2]];
        }
        game.ramen.special_feeling = ramen.special_feeling_num;
        if ramen.selected_region_ids.len() >= 3 {
            game.ramen.selected_regions = [
                (ramen.selected_region_ids[0] as usize).saturating_sub(1),
                (ramen.selected_region_ids[1] as usize).saturating_sub(1),
                (ramen.selected_region_ids[2] as usize).saturating_sub(1),
            ];
        }
        if ramen.acquisition_gauges.len() >= 3 {
            game.ramen.feeling_slot = [
                ramen.acquisition_gauges[0],
                ramen.acquisition_gauges[1],
                ramen.acquisition_gauges[2],
            ];
        }
    }

    if internal_turn >= 2 {
        let has_scenario = game.persons.iter().any(|p| p.person_type == PersonType::ScenarioCard);
        if !has_scenario {
            game.add_friend_and_npcs()?;
        }
    }
    if internal_turn >= 12 {
        let has_reporter = game.persons.iter().any(|p| p.person_type == PersonType::Reporter);
        if !has_reporter {
            game.add_reporter();
        }
    }

    if internal_turn >= 2 && internal_turn <= 71 {
        game.stage = RamenStage::RamenSelect;
    } else {
        game.stage = RamenStage::Train;
    }

    Ok(())
}

// ── Flat Monte Carlo search ─────────────────────────────────────────

pub fn run_flat_search(game: &RamenGame, search_n: usize, strategy: &ramen_strategy::RamenStrategy) -> Result<SearchResult> {
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

                if let Err(_) = apply_fn(&mut sim_game, &mut rng) {
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

// ── JNI exports (only with jni-support feature) ─────────────────────

#[cfg(feature = "jni-support")]
mod jni_exports {
    use super::*;
    use jni::objects::{JClass, JString};
    use jni::sys::jstring;
    use jni::JNIEnv;

    fn jstring_from_str(env: &mut JNIEnv, s: &str) -> jstring {
        let jstr = env.new_string(s).expect("JString alloc");
        jstr.into_raw()
    }

    fn string_from_jstring<'a>(env: &'a mut JNIEnv, js: JString<'a>) -> String {
        env.get_string(&js)
            .expect("JString extract")
            .to_str()
            .expect("UTF-8")
            .to_string()
    }

    #[no_mangle]
    pub extern "system" fn Java_com_umaai_assistant_service_UmaNativeBridge_nativeInit(
        mut env: JNIEnv,
        _class: JClass,
        data_dir: JString,
    ) -> jstring {
        let dir = string_from_jstring(&mut env, data_dir);

        INITIALIZED.get_or_init(|| {
            let _ = std::env::set_current_dir(&dir);
            let gamedata_dir = std::path::Path::new(&dir).join("gamedata");
            if gamedata_dir.exists() {
                std::env::set_var("UMAI_DATA_DIR", &gamedata_dir);
            }
            if let Err(e) = init_global() {
                log::error!("init_global failed: {e}");
            }
        });

        let msg = serde_json::json!({"ok": true, "data_dir": dir}).to_string();
        jstring_from_str(&mut env, &msg)
    }

    #[no_mangle]
    pub extern "system" fn Java_com_umaai_assistant_service_UmaNativeBridge_nativeSearch(
        mut env: JNIEnv,
        _class: JClass,
        state_json: JString,
        config_json: JString,
    ) -> jstring {
        let state_str = string_from_jstring(&mut env, state_json);
        let config_str = string_from_jstring(&mut env, config_json);

        let result = std::panic::catch_unwind(|| {
            let state: RuntimeState = serde_json::from_str(&state_str)
                .map_err(|e| anyhow!("parse state failed: {e}"))?;
            let config: SearchConfig = serde_json::from_str(&config_str)
                .map_err(|e| anyhow!("parse config failed: {e}"))?;

            let inherit = InheritInfo {
                blue_count: config.blue_count,
                extra_count: config.extra_count,
            };

            let mut game = RamenGame::newgame(config.uma_id, &config.cards, inherit)
                .map_err(|e| anyhow!("newgame failed: {e}"))?;

            inject_state(&mut game, &state)
                .map_err(|e| anyhow!("inject_state failed: {e}"))?;

            let mut rng = StdRng::from_os_rng();
            game.distribute_all(&mut rng).ok();
            game.distribute_hint(&mut rng).ok();

            run_flat_search(&game, config.search_n, &config.strategy)
        });

        let search_result = match result {
            Ok(Ok(r)) => r,
            Ok(Err(e)) => SearchResult {
                ok: false, action: String::new(), action_display: String::new(),
                score_mean: 0.0, search_n: 0, elapsed_ms: 0, all_actions: vec![],
                error: Some(e.to_string()),
            },
            Err(_) => SearchResult {
                ok: false, action: String::new(), action_display: String::new(),
                score_mean: 0.0, search_n: 0, elapsed_ms: 0, all_actions: vec![],
                error: Some("panic during search".to_string()),
            },
        };

        let json = serde_json::to_string(&search_result).unwrap_or_else(|_| r#"{"ok":false}"#.to_string());
        jstring_from_str(&mut env, &json)
    }

    #[no_mangle]
    pub extern "system" fn Java_com_umaai_assistant_service_UmaNativeBridge_nativeVersion(
        mut env: JNIEnv,
        _class: JClass,
    ) -> jstring {
        let v = serde_json::json!({
            "version": "0.1.0",
            "upstream": "xulai1001/umaai-rs",
            "search": "flat_monte_carlo",
            "trainer": "handwritten"
        }).to_string();
        jstring_from_str(&mut env, &v)
    }
}
