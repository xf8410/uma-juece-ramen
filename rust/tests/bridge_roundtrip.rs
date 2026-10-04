//! Host-side native integration. Synthetic state verifies wiring, not game capture.
use std::{fs, path::PathBuf};
use serde_json::{Value, json};
use uma_jni::{cancel_request, evaluate_file, initialize_engine, select_collector_instance, version};
use umaai_runtime::protocol::{GameStatusBase, GameStatusRamen};

fn fixture() -> Value {
    let base = GameStatusBase {
        scenario_id: 14, uma_id: 102601, uma_star: 5, turn: 0,
        vital: 100, max_vital: 100, motivation: 3,
        five_status: [100; 5], five_status_limit: [2000; 5],
        card_id: vec![302424,302894,303044,302924,303024,303054],
        persons: vec![Default::default();6], person_distribution: vec![vec![];5],
        single_mode_chara_id: Some(997), source: Some("command".into()),
        playing_state: 1, ..Default::default()
    };
    json!({"schema_version":1,"run_id":997,"snapshot_id":1,"stage":"train",
        "state":GameStatusRamen { base_game:base,ramen:Default::default() },
        "continuation":{"eat_count":0,"rmj_results":[],"train_level_bonus":0,
            "pending_ramen":null,"pending_special_targets":[0,0,0],"inherit_extra_count":[0,0,0,0,0,0]},
        "event":null,"collector_version":"synthetic-test","game_version":"synthetic-test",
        "ready":true,"capture_coherence":"verified","missing_fields":[],"provenance":{"synthetic":true}})
}

#[test]
fn shared_engine_file_stream_cancel_and_generation() -> anyhow::Result<()> {
    let project = PathBuf::from(env!("CARGO_MANIFEST_DIR")).parent().unwrap().to_owned();
    let data = project.join(".engine-source/gamedata");
    let directory = project.join(".tools/native-roundtrip");
    fs::create_dir_all(&directory)?;
    let snapshot = directory.join("synthetic.json");
    fs::write(&snapshot, serde_json::to_vec(&fixture())?)?;
    let options = json!({"policy":"mcts","search_n":2,"threads":1,"seed":42,"config_id":"native-integration"}).to_string();
    let cwd = std::env::current_dir()?;
    assert!(initialize_engine(&data, &options)?["ok"].as_bool().unwrap_or(false));
    assert_eq!(cwd, std::env::current_dir()?);
    let mut streamed = Vec::new();
    let result = evaluate_file(&snapshot, &options, "roundtrip-1", &mut |event| streamed.push(event))?;
    assert_eq!(result["ok"], true);
    let events = result["evaluation"]["events"].as_array().unwrap();
    assert_eq!(events.len(), streamed.len());
    assert_eq!(streamed.first().unwrap()["event"]["type"], "started");
    assert_eq!(streamed.last().unwrap()["event"]["type"], "completed");
    for (index, event) in streamed.iter().enumerate() {
        assert_eq!(event["event_seq"], index + 1);
        assert_eq!(event["request_id"], "roundtrip-1");
        assert_eq!(event["event"], events[index]);
    }
    assert!(events.iter().any(|event| event["type"] == "decision"));
    assert!(evaluate_file(&snapshot, &options, "duplicate-snapshot", &mut |_| {}).is_err());
    cancel_request("cancel-before-native");
    assert!(evaluate_file(&snapshot, &options, "cancel-before-native", &mut |_| {}).is_err());
    let changed = json!({"search_n":4,"threads":1,"config_id":"new-generation"}).to_string();
    assert!(initialize_engine(&data, &changed).is_err());
    assert!(version()["engine_revision"].as_str().unwrap().contains('+'));
    let mut v2 = fixture(); v2["schema_version"] = json!(2); v2["collector_instance_id"] = json!("collector-a");
    fs::write(&snapshot, serde_json::to_vec(&v2)?)?;
    let mut selected_options: Value = serde_json::from_str(&options)?;
    selected_options["collector_instance_id"] = json!("collector-a");
    assert!(evaluate_file(&snapshot, &options, "no-confirmed-option", &mut |_| {}).is_err());
    assert!(evaluate_file(&snapshot, &selected_options.to_string(), "no-handshake", &mut |_| {}).is_err());
    select_collector_instance("collector-a")?;
    let mut events_a = Vec::new();
    let a = evaluate_file(&snapshot, &selected_options.to_string(), "epoch-a", &mut |event| events_a.push(event))?;
    assert_eq!(a["collector_instance_id"], "collector-a"); assert_eq!(a["schema_version"], 2);
    assert!(events_a.iter().all(|event| event["collector_instance_id"] == "collector-a" && event["schema_version"] == 2));
    v2["collector_instance_id"] = json!("collector-b");
    fs::write(&snapshot, serde_json::to_vec(&v2)?)?;
    assert!(evaluate_file(&snapshot, &selected_options.to_string(), "wrong-confirmed-option", &mut |_| {}).is_err());
    selected_options["collector_instance_id"] = json!("collector-b");
    select_collector_instance("collector-b")?;
    let b = evaluate_file(&snapshot, &selected_options.to_string(), "epoch-b", &mut |_| {})?;
    assert_eq!(a["evaluation"]["snapshot_id"], b["evaluation"]["snapshot_id"]);
    assert!(b["evaluation"]["warnings"].as_array().unwrap().iter().any(|v| v.as_str().is_some_and(|s| s.starts_with("luck_baseline_at_recovery"))));
    assert!(select_collector_instance("collector-a").is_err());
    assert!(evaluate_file(&snapshot, &selected_options.to_string(), "epoch-b-duplicate", &mut |_| {}).is_err());
    println!("Synthetic file→shared runtime→stream: {} events; stale/cancel/config guard verified", events.len());
    Ok(())
}
