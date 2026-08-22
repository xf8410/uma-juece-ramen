//! 状态注入 — 把 hlpatch 运行时快照写入 `RamenGame`。
//!
//! 覆盖五维/体力/干劲/拉面材料/区域/盛況度，
//! 并按回合数补齐友人、NPC 和记者。

use anyhow::Result;

use umasim::game::{
    ramen::{RamenGame, RamenStage},
    PersonType,
};

use crate::state::RuntimeState;

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
