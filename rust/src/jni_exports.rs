//! JNI 边界 — Android 侧唯一入口。
//!
//! 导出符号固定为 `Java_com_umaai_assistant_service_UmaNativeBridge_*`，
//! 移动或重命名本模块不影响符号名（#[no_mangle] 保证）。

use std::sync::OnceLock;

use anyhow::anyhow;
use jni::objects::{JClass, JString};
use jni::sys::jstring;
use jni::JNIEnv;
use rand::{SeedableRng, rngs::StdRng};

use umasim::game::{InheritInfo, ramen::RamenGame};
use umasim::gamedata::init_global;

use crate::inject::inject_state;
use crate::search::{SearchConfig, SearchResult, run_flat_search};
use crate::state::RuntimeState;

static INITIALIZED: OnceLock<()> = OnceLock::new();

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
        Ok(Err(e)) => SearchResult::error(e.to_string()),
        Err(_) => SearchResult::error("panic during search".to_string()),
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
