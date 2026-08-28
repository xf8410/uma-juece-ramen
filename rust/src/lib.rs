//! JNI bridge for umaai-rs ramen — 接入上游标准输出层。
//!
//! 架构：
//! - `reconcile`: 状态校正层，把 hlpatch 脏数据清洗为模拟器可用状态
//! - `inject_state`: 把校正后的状态注入 RamenGame
//! - `run_search`: 用上游 `RamenMctsTrainer` 搜索，输出 `DecisionInfo` + `GameView`
//! - JNI exports: `nativeInit` / `nativeSearch` / `nativeVersion`
//!
//! 回合口径：
//! - hlpatch `turn` = 游戏 UI「第N回合」（1-based）
//! - umaai-rs 内部回合从 0 开始，`inject_state()` 做 `turn - 1` 转换
//!
//! 与旧版的区别：
//! - 候选评分直接复用 `select_action` 内部那次搜索的 `last_breakdown()`，
//!   不再二次搜索（旧行为把 4096 次模拟翻倍，且两次随机数不同、
//!   展示的 mean 与实际选中动作不一致）
//! - 返回结构化 JSON（view + decision + warnings + confidence）

pub mod ramen_strategy;
pub mod reconcile;

use std::sync::OnceLock;
use std::time::Instant;

use anyhow::{Result, anyhow};
use rand::{SeedableRng, rngs::StdRng};
use serde::Serialize;

use umasim::game::{
    Game, Trainer,
    ramen::{RamenGame, RamenStage},
    InheritInfo, PersonType,
};
use umasim::gamedata::init_global;
use umasim::output::GameView;
use umasim::search::SearchConfig;
use umasim::trainer::{RamenHandwrittenTrainer, RamenMctsTrainer, RamenSearchStages};

use reconcile::{HlpatchSummary, ReconciledState, reconcile};

static INITIALIZED: OnceLock<()> = OnceLock::new();

// ── 搜索配置 ────────────────────────────────────────────────────────

/// 手机版搜索配置（从 Java 传入的 JSON）。
#[derive(serde::Deserialize)]
pub struct SearchConfigInput {
    pub uma_id: u32,
    pub cards: [u32; 6],
    #[serde(default)]
    pub blue_count: [i32; 5],
    #[serde(default)]
    pub extra_count: [i32; 6],
    /// 搜索次数（手机建议 32-128）
    #[serde(default = "default_search_n")]
    pub search_n: usize,
}

fn default_search_n() -> usize {
    64
}

// ── 输出结构（返回给 Java 的标准 JSON）──────────────────────────────

/// 搜索结果：包含游戏视图 + 决策信息 + 校正日志。
#[derive(Debug, Clone, Serialize)]
pub struct SearchResponse {
    pub ok: bool,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub view: Option<GameView>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub decision: Option<DecisionOutput>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub reconcile: Option<ReconciledState>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub error: Option<String>,
}

#[derive(Debug, Clone, Serialize)]
pub struct DecisionOutput {
    /// 选中的动作索引
    pub action_index: usize,
    /// 选中的动作展示文本
    pub action_display: String,
    /// 选中动作的评分
    pub score: f64,
    /// 所有候选动作的展示文本
    pub candidate_displays: Vec<String>,
    /// 所有候选动作的评分
    pub candidate_scores: Vec<f64>,
    /// 搜索次数
    pub search_n: usize,
    /// 搜索耗时（毫秒）
    pub elapsed_ms: u64,
    /// 决策来源（mcts / handwritten_fallback / reconcile_failed）
    pub source: String,
}

// ── 状态注入 ────────────────────────────────────────────────────────

/// 把校正后的状态注入 RamenGame。
///
/// 回合转换：`state.turn` 是 hlpatch 直读的游戏 UI 回合（1-based），
/// 上游模拟器内部回合从 0 开始，这里做 `-1`。
///
/// 注意：这是"部分重建"，不是完美快照。以下字段无法从 hlpatch 数据恢复：
/// - friendship（友情等级）：估算为 80（接近满级）
/// - train_level_count（训练等级）：估算为 0
/// - feeling_queue（诀窍获得顺序队列）：置空
/// - yearly_* 观测字段：置默认值
pub fn inject_state(game: &mut RamenGame, state: &ReconciledState) -> Result<()> {
    let internal_turn = (state.turn - 1).max(0);
    game.base.turn = internal_turn;

    // 五维 + 体力 + 干劲 + 技能点
    game.base.uma.five_status[0] = state.stats.speed;
    game.base.uma.five_status[1] = state.stats.stamina;
    game.base.uma.five_status[2] = state.stats.power;
    game.base.uma.five_status[3] = state.stats.guts;
    game.base.uma.five_status[4] = state.stats.wiz;
    game.base.uma.skill_pt = state.stats.skill_point;
    game.base.uma.vital = state.stats.vital;
    game.base.uma.max_vital = state.stats.max_vital;
    game.base.uma.motivation = state.stats.motivation;

    // 拉面状态
    game.ramen.scenario_pt = state.ramen.scenario_pt;
    game.ramen.feeling_stock = state.ramen.feeling_stock;
    game.ramen.feeling_slot = state.ramen.feeling_slot;
    game.ramen.special_feeling = state.ramen.special_feeling;

    if state.ramen.has_region_data {
        game.ramen.selected_regions = state.ramen.selected_region_indices;
    }

    // 人头注入：根据回合添加友人/NPC/记者
    if internal_turn >= 2 {
        let has_scenario = game
            .persons
            .iter()
            .any(|p| p.person_type == PersonType::ScenarioCard);
        if !has_scenario {
            game.add_friend_and_npcs()?;
        }
    }
    if internal_turn >= 12 {
        let has_reporter = game
            .persons
            .iter()
            .any(|p| p.person_type == PersonType::Reporter);
        if !has_reporter {
            game.add_reporter();
        }
    }

    // 阶段判定
    // turn 2-71: RamenSelect 阶段（吃面决策）
    // turn 72-77: SuperRamenSelect 阶段（超级拉面）
    // 其他: Train 阶段
    if internal_turn >= 2 && internal_turn <= 71 {
        game.stage = RamenStage::RamenSelect;
    } else if internal_turn >= 72 && internal_turn <= 77 {
        game.stage = RamenStage::SuperRamenSelect;
    } else {
        game.stage = RamenStage::Train;
    }

    Ok(())
}

// ── 搜索核心 ────────────────────────────────────────────────────────

/// 执行搜索，返回标准结构化结果。
///
/// 流程：
/// 1. reconcile hlpatch JSON → ReconciledState
/// 2. 如果 confidence = Reject，返回错误
/// 3. newgame + inject_state
/// 4. 用 RamenMctsTrainer 搜索（Train + RamenSelect 阶段）
/// 5. 输出 GameView + DecisionOutput
pub fn run_search(
    summary_json: &str,
    config: &SearchConfigInput,
) -> SearchResponse {
    let start = Instant::now();

    // ① 校正状态
    let raw: HlpatchSummary = match serde_json::from_str(summary_json) {
        Ok(r) => r,
        Err(e) => {
            log::warn!("JSON 解析失败: {e}");
            return SearchResponse {
                ok: false,
                view: None,
                decision: None,
                reconcile: None,
                error: Some(format!("JSON 解析失败: {e}")),
            }
        }
    };

    let reconciled = match reconcile(&raw) {
        Ok(s) => s,
        Err(e) => {
            log::warn!("状态校正失败: {e}");
            return SearchResponse {
                ok: false,
                view: None,
                decision: None,
                reconcile: None,
                error: Some(format!("状态校正失败: {e}")),
            }
        }
    };

    log::info!(
        "reconcile: turn={}, confidence={:?}, warnings={}",
        reconciled.turn,
        reconciled.confidence,
        reconciled.warnings.len()
    );
    for w in &reconciled.warnings {
        log::info!("  warning: {w}");
    }

    if !reconciled.is_searchable() {
        let confidence = reconciled.confidence.clone();
        log::warn!("置信度过低({confidence:?})，跳过搜索");
        return SearchResponse {
            ok: false,
            view: None,
            decision: None,
            reconcile: Some(reconciled),
            error: Some(format!(
                "置信度过低({confidence:?})，跳过搜索"
            )),
        };
    }

    // ② 初始化游戏 + 注入状态
    let inherit = InheritInfo {
        blue_count: config.blue_count,
        extra_count: config.extra_count,
        ..Default::default()
    };

    let mut game = match RamenGame::newgame(config.uma_id, &config.cards, inherit) {
        Ok(g) => g,
        Err(e) => {
            return SearchResponse {
                ok: false,
                view: None,
                decision: None,
                reconcile: Some(reconciled),
                error: Some(format!("newgame 失败: {e}")),
            }
        }
    };

    if let Err(e) = inject_state(&mut game, &reconciled) {
        return SearchResponse {
            ok: false,
            view: None,
            decision: None,
            reconcile: Some(reconciled),
            error: Some(format!("inject_state 失败: {e}")),
        };
    }

    // ③ 获取 GameView
    let view = game.view();

    // ④ 搜索
    let decision = match run_mcts_search(&mut game, config.search_n) {
        Ok(d) => d,
        Err(e) => {
            // 搜索失败，尝试手写策略兜底
            match run_handwritten_fallback(&mut game) {
                Ok(mut d) => {
                    d.source = format!("handwritten_fallback(mcts_failed: {e})");
                    d
                }
                Err(e2) => {
                    return SearchResponse {
                        ok: false,
                        view: Some(view),
                        decision: None,
                        reconcile: Some(reconciled),
                        error: Some(format!(
                            "搜索失败: {e}；手写兜底也失败: {e2}"
                        )),
                    }
                }
            }
        }
    };

    let elapsed_ms = start.elapsed().as_millis() as u64;

    log::info!(
        "搜索完成: action={}, score={:.0}, n={}, elapsed={}ms, source={}",
        decision.action_display,
        decision.score,
        config.search_n,
        elapsed_ms,
        decision.source
    );

    SearchResponse {
        ok: true,
        view: Some(view),
        decision: Some(DecisionOutput {
            action_index: decision.action_index,
            action_display: decision.action_display,
            score: decision.score,
            candidate_displays: decision.candidate_displays,
            candidate_scores: decision.candidate_scores,
            search_n: config.search_n,
            elapsed_ms,
            source: decision.source,
        }),
        reconcile: Some(reconciled),
        error: None,
    }
}

/// 用上游 RamenMctsTrainer 搜索。
///
/// 阶段门控：只搜 Train + RamenSelect（手机性能有限，省略 SpecialSelect 等）。
///
/// 候选评分来自 `trainer.last_breakdown()`——那是 `select_action` 内部
/// 那次搜索缓存的统计（`#i 动作 n=N mean=M sd=S pt=P | ...`）。
/// 旧版在这里又跑了一遍 `trainer.search.search()`：4096 次模拟直接翻倍，
/// 且两次随机流不同，展示的 mean 和实际选中动作对不上。
fn run_mcts_search(game: &mut RamenGame, search_n: usize) -> Result<DecisionOutput> {
    let config = SearchConfig::default().with_search_n(search_n);
    let trainer = RamenMctsTrainer::new(config).with_stages(RamenSearchStages {
        train: true,
        ramen_select: true,
        special_select: false,
        region_select: false,
        super_ramen_select: false,
    });

    let mut rng = StdRng::from_os_rng();

    // 获取候选动作列表（RamenSelect 用合并候选：吃面×targets + 不吃面）
    let actions: Vec<_> = if game.stage == RamenStage::RamenSelect {
        game.list_combined_ramen_select_actions()
    } else {
        game.list_actions()?
    };

    if actions.is_empty() {
        return Err(anyhow!("没有可用候选动作"));
    }

    // 执行搜索
    let action_index = trainer.select_action(game, &actions, &mut rng)?;

    // 收集所有候选的展示文本
    let candidate_displays: Vec<String> = actions
        .iter()
        .map(|a| format!("{a}"))
        .collect();

    // 候选评分：复用 select_action 内部搜索的 breakdown，不再二次搜索
    let candidate_scores =
        parse_breakdown_means(trainer.last_breakdown().as_deref(), actions.len());

    let score = candidate_scores.get(action_index).copied().unwrap_or(0.0);
    let action_display = candidate_displays
        .get(action_index)
        .cloned()
        .unwrap_or_default();

    Ok(DecisionOutput {
        action_index,
        action_display,
        score,
        candidate_displays,
        candidate_scores,
        search_n,
        elapsed_ms: 0, // 由上层填充
        source: "mcts".into(),
    })
}

/// 从 `RamenMctsTrainer::last_breakdown()` 文本解析每个候选的 mean 分。
///
/// 行格式（" | " 分隔）：`#0 不吃面 n=4096 mean=65973 sd=1234 pt=5678`
/// 解析失败的候选保持 0.0（上层展示时按"无评分"处理）。
fn parse_breakdown_means(breakdown: Option<&str>, expected_len: usize) -> Vec<f64> {
    let mut scores = vec![0.0; expected_len];
    let Some(text) = breakdown else {
        return scores;
    };
    for part in text.split(" | ") {
        let part = part.trim();
        let Some(rest) = part.strip_prefix('#') else {
            continue;
        };
        let Some((idx_str, tail)) = rest.split_once(' ') else {
            continue;
        };
        let Ok(idx) = idx_str.trim().parse::<usize>() else {
            continue;
        };
        if idx >= expected_len {
            continue;
        }
        for seg in tail.split_whitespace() {
            if let Some(v) = seg.strip_prefix("mean=") {
                if let Ok(m) = v.parse::<f64>() {
                    scores[idx] = m;
                }
            }
        }
    }
    scores
}

/// 手写策略兜底（搜索失败时使用）。
fn run_handwritten_fallback(game: &mut RamenGame) -> Result<DecisionOutput> {
    let trainer = RamenHandwrittenTrainer::new();
    let mut rng = StdRng::from_os_rng();

    let actions: Vec<_> = if game.stage == RamenStage::RamenSelect {
        game.list_combined_ramen_select_actions()
    } else {
        game.list_actions()?
    };

    if actions.is_empty() {
        return Err(anyhow!("没有可用候选动作"));
    }

    let action_index = trainer.select_action(game, &actions, &mut rng)?;

    let candidate_displays: Vec<String> = actions
        .iter()
        .map(|a| format!("{a}"))
        .collect();
    let candidate_scores = parse_breakdown_means(trainer.last_breakdown().as_deref(), actions.len());

    Ok(DecisionOutput {
        action_index,
        action_display: candidate_displays
            .get(action_index)
            .cloned()
            .unwrap_or_default(),
        score: candidate_scores.get(action_index).copied().unwrap_or(0.0),
        candidate_displays,
        candidate_scores,
        search_n: 0,
        elapsed_ms: 0,
        source: "handwritten".into(),
    })
}

// ── JNI exports ─────────────────────────────────────────────────────

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

        // 初始化 Android logger，把上游 log::info!/warn!/error! 转发到 Logcat
        android_logger::init_once(
            android_logger::Config::default()
                .with_tag("uma_jni")
                .with_max_level(log::LevelFilter::Info),
        );
        log::info!("nativeInit: data_dir={dir}");

        let result = if INITIALIZED.get().is_some() {
            r#""{"ok":true,"already_initialized":true}"#.to_string()
        } else {
            // 上游 init_global() 通过相对路径 "gamedata/xxx.json" 加载数据，
            // 需要先 set_current_dir 到 data_dir（Java 侧已把 assets 复制到这里）
            if let Err(e) = std::env::set_current_dir(&dir) {
                let msg = format!("set_current_dir({dir}) 失败: {e}");
                log::error!("{msg}");
                format!(r#"{{"ok":false,"error":"{}"}}"#, msg.replace('"', "'"))
            } else {
                match init_global() {
                    Ok(()) => {
                        log::info!("init_global 成功，gamedata 已从 {dir}/gamedata/ 加载");
                        let _ = INITIALIZED.set(());
                        r#"{"ok":true}"#.to_string()
                    }
                    Err(e) => {
                        log::error!("init_global 失败: {e}");
                        format!(r#"{{"ok":false,"error":"{}"}}"#, e.to_string().replace('"', "'"))
                    }
                }
            }
        };

        jstring_from_str(&mut env, &result)
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

        let config: SearchConfigInput = match serde_json::from_str(&config_str) {
            Ok(c) => c,
            Err(e) => {
                let resp = SearchResponse {
                    ok: false,
                    view: None,
                    decision: None,
                    reconcile: None,
                    error: Some(format!("config 解析失败: {e}")),
                };
                let json = serde_json::to_string(&resp).unwrap_or_else(|_| r#"{"ok":false}"#.into());
                return jstring_from_str(&mut env, &json);
            }
        };

        let response = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
            run_search(&state_str, &config)
        }))
        .map_err(|_| SearchResponse {
            ok: false,
            view: None,
            decision: None,
            reconcile: None,
            error: Some("panic during search".into()),
        })
        .unwrap_or_else(|err| err);

        let json = serde_json::to_string(&response)
            .unwrap_or_else(|_| r#"{"ok":false}"#.to_string());
        jstring_from_str(&mut env, &json)
    }

    #[no_mangle]
    pub extern "system" fn Java_com_umaai_assistant_service_UmaNativeBridge_nativeVersion(
        mut env: JNIEnv,
        _class: JClass,
    ) -> jstring {
        let v = serde_json::json!({
            "version": "0.3.0",
            "upstream": "xulai1001/umaai-rs",
            "upstream_commit": "7cef1fa",
            "search": "ramen_mcts_trainer",
            "stages": ["train", "ramen_select"],
            "reconcile": true,
            "turn_convention": "hlpatch UI 1-based -> AI internal 0-based (turn-1)",
            "candidate_scores": "last_breakdown_reuse"
        })
        .to_string();
        jstring_from_str(&mut env, &v)
    }
}

// ── 单元测试 ────────────────────────────────────────────────────────

#[cfg(test)]
mod tests {
    use super::*;

    /// last_breakdown 文本 → 候选 mean 数组（PC 黑板「决策理由」数据源）
    #[test]
    fn test_parse_breakdown_means() {
        let text = "#0 不吃面 n=4096 mean=65973 sd=3210 pt=5646 | #1 吃面/函馆-耐 n=4096 mean=66972 sd=2980 pt=6750 | #2 吃面/东京-智 n=4096 mean=66241 sd=3055 pt=6668";
        let scores = parse_breakdown_means(Some(text), 4);
        assert_eq!(scores.len(), 4);
        assert!((scores[0] - 65973.0).abs() < 0.5, "scores[0]={}", scores[0]);
        assert!((scores[1] - 66972.0).abs() < 0.5);
        assert!((scores[2] - 66241.0).abs() < 0.5);
        assert_eq!(scores[3], 0.0, "缺失候选保持 0.0");

        // 候选数超出的行被忽略、非 # 开头的段被跳过
        let messy = "garbage | #7 越界 mean=1.0";
        let scores2 = parse_breakdown_means(Some(messy), 2);
        assert_eq!(scores2, vec![0.0, 0.0]);

        assert_eq!(parse_breakdown_means(None, 3), vec![0.0, 0.0, 0.0]);
    }

    #[test]
    fn test_run_search_with_real_sample() {
        // 初始化 gamedata（需要 gamedata/ 目录在工作目录下）
        let workspace_root = std::env::current_dir()
            .ok()
            .and_then(|d| d.parent().map(|p| p.to_path_buf()))
            .unwrap_or_else(|| std::path::PathBuf::from(".."));

        let gamedata_dir = workspace_root.join("gamedata");
        if !gamedata_dir.exists() {
            eprintln!("跳过测试：gamedata 目录不存在");
            return;
        }

        // 上游 init_global() 用相对路径 "gamedata/xxx.json" 加载，
        // 需要先 set_current_dir 到 workspace_root
        let _ = std::env::set_current_dir(&workspace_root);
        let _ = init_global();

        // hlpatch 直读回合样本：turn=31（UI 第31回合）→ AI 内部 30
        let json = r#"{
            "chara": {
                "speed": 1200, "stamina": 301, "power": 437, "guts": 362, "wiz": 280,
                "vital": 60, "max_vital": 108, "motivation": 5,
                "skill_point": 1492, "scenario_id": 14
            },
            "turn": 31
        }"#;

        let config = SearchConfigInput {
            uma_id: 102601,
            cards: [302424, 302894, 303044, 302924, 303024, 303054],
            blue_count: [15, 3, 0, 0, 0],
            extra_count: [0, 30, 0, 0, 30, 30],
            search_n: 8, // 测试用小搜索次数
        };

        let response = run_search(json, &config);

        // 直读回合无估算 warning
        let reconciled = response.reconcile.as_ref().unwrap();
        assert_eq!(reconciled.turn, 31);
        assert_eq!(reconciled.turn_source, "direct");

        // 搜索可能成功也可能失败（取决于 gamedata 是否完整）
        if response.ok {
            assert!(response.view.is_some());
            assert!(response.decision.is_some());
            let decision = response.decision.as_ref().unwrap();
            assert!(!decision.candidate_displays.is_empty());
            eprintln!("搜索成功: {}", decision.action_display);
        } else {
            eprintln!("搜索失败（预期，gamedata 可能不完整）: {:?}", response.error);
        }
    }
}
