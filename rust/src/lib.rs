//! Android JNI transport for the same strict runtime used by the PC client.
//! No approximate history, default deck, Java scores, or cloud routing lives here.
use std::{
    collections::{HashMap, VecDeque},
    fs,
    path::{Path, PathBuf},
    sync::{Mutex, OnceLock},
    time::{Duration, Instant},
};
use anyhow::{Context, Result, anyhow, ensure};
use rayon::{ThreadPool, ThreadPoolBuilder};
use serde::{Deserialize, Serialize};
use serde_json::{Value, json};
use sha2::{Digest, Sha256};
use umaai_runtime::{CancellationToken, RamenSession, RuntimeEvent};

const MAX_SNAPSHOT_BYTES: u64 = 8 * 1024 * 1024;

/// User-visible settings; seed/deadline affect a request, other fields a generation.
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(default, deny_unknown_fields)]
pub struct EngineOptions {
    pub policy: String,
    pub search_n: usize,
    pub threads: usize,
    pub seed: u64,
    pub model_path: Option<String>,
    pub deadline_ms: u64,
    pub config_id: String,
}

impl Default for EngineOptions {
    fn default() -> Self {
        Self { policy: "mcts".into(), search_n: 8192, threads: 4, seed: 61444,
            model_path: None, deadline_ms: 0, config_id: "upstream-aligned-v1".into() }
    }
}

impl EngineOptions {
    /// Validate before constructing pools or loading a model.
    pub fn parse(raw: &str) -> Result<Self> {
        let options: Self = serde_json::from_str(raw).context("invalid engine options")?;
        ensure!(matches!(options.policy.as_str(), "mcts" | "mcts_nn_hint" | "nn"), "unsupported policy");
        ensure!((1..=1_048_576).contains(&options.search_n), "search_n must be 1..1048576");
        ensure!((1..=32).contains(&options.threads), "threads must be 1..32");
        ensure!(!options.config_id.is_empty() && options.config_id.len() <= 256, "invalid config_id");
        ensure!(options.deadline_ms <= 86_400_000, "deadline exceeds one day");
        if options.policy != "mcts" {
            ensure!(options.model_path.as_ref().is_some_and(|x| !x.trim().is_empty()), "selected NN mode requires a model");
        }
        Ok(options)
    }
    fn generation(&self) -> Value {
        json!({"policy":self.policy,"search_n":self.search_n,"threads":self.threads,
            "model_path":self.model_path,"config_id":self.config_id})
    }
}

struct Engine {
    generation: Value,
    data_dir: PathBuf,
    session: RamenSession,
    pool: ThreadPool,
}
static ENGINE: OnceLock<Mutex<Option<Engine>>> = OnceLock::new();

#[derive(Default)]
struct Requests {
    active: HashMap<String, CancellationToken>,
    // Cancellation may arrive before the worker has entered JNI.
    early: VecDeque<String>,
}
static REQUESTS: OnceLock<Mutex<Requests>> = OnceLock::new();

struct RequestGuard(String);
impl Drop for RequestGuard {
    fn drop(&mut self) {
        if let Ok(mut requests) = REQUESTS.get_or_init(Default::default).lock() { requests.active.remove(&self.0); }
    }
}

fn register_request(id: &str, deadline_ms: u64) -> Result<(CancellationToken, RequestGuard)> {
    ensure!(!id.is_empty() && id.len() <= 256 && !id.chars().any(char::is_control), "invalid request_id");
    let mut requests = REQUESTS.get_or_init(Default::default).lock().map_err(|_| anyhow!("request lock poisoned"))?;
    ensure!(!requests.active.contains_key(id), "duplicate request_id");
    let token = if deadline_ms > 0 { CancellationToken::new().with_timeout(Duration::from_millis(deadline_ms)) } else { CancellationToken::new() };
    if let Some(index) = requests.early.iter().position(|x| x == id) {
        requests.early.remove(index); token.cancel();
    }
    requests.active.insert(id.into(), token.clone());
    Ok((token, RequestGuard(id.into())))
}

/// Cancel without taking the session lock held by evaluation.
pub fn cancel_request(id: &str) {
    if id.is_empty() || id.len() > 256 { return; }
    if let Ok(mut requests) = REQUESTS.get_or_init(Default::default).lock() {
        if let Some(token) = requests.active.get(id) { token.cancel(); }
        else if !requests.early.iter().any(|x| x == id) {
            if requests.early.len() == 64 { requests.early.pop_front(); }
            requests.early.push_back(id.into());
        }
    }
}

/// Compile-time provenance; data packaging uses the identical revision string.
pub fn version() -> Value {
    let lock: Value = serde_json::from_str(include_str!("../../engine/source-lock.json")).unwrap_or(Value::Null);
    let base = lock["base_revision"].as_str().unwrap_or("unknown");
    let patch = lock["patch_sha256"].as_str().unwrap_or("unknown");
    let revision = option_env!("UMAAI_ENGINE_REVISION").map(String::from)
        .unwrap_or_else(|| format!("{base}+{}", &patch[..patch.len().min(12)]));
    json!({"ok":true,"bridge_version":env!("CARGO_PKG_VERSION"),"schema_version":1,
        "engine_revision":revision,"onnx":cfg!(feature="onnx"),"strict_snapshots":true,
        "policies":if cfg!(feature="onnx") { vec!["mcts","mcts_nn_hint","nn"] } else { vec!["mcts"] }})
}

/// Initialize exactly one data/config generation. Changes require :engine restart.
pub fn initialize_engine(data_dir: &Path, raw_options: &str) -> Result<Value> {
    let options = EngineOptions::parse(raw_options)?;
    let data_dir = data_dir.canonicalize().context("gamedata directory missing")?;
    ensure!(data_dir.is_dir(), "gamedata path is not a directory");
    let generation = options.generation();
    let mut engine = ENGINE.get_or_init(Default::default).lock().map_err(|_| anyhow!("engine lock poisoned"))?;
    if let Some(existing) = engine.as_ref() {
        ensure!(existing.generation == generation && existing.data_dir == data_dir,
            "config_generation_changed: restart engine process");
        return Ok(json!({"ok":true,"version":version(),"config_id":options.config_id}));
    }
    let mut config = umaai_runtime::load_config(&data_dir, None)?;
    config.scenario = "ramen".into();
    config.trainer = "mcts".into();
    config.mcts.search_n = options.search_n;
    config.collector.threads = options.threads;
    config.ramen_trainer_policy = serde_json::from_value(json!(options.policy))?;
    config.ramen_nn_model_path = options.model_path.clone();
    if let Some(model) = options.model_path.as_ref().filter(|_| options.policy != "mcts") {
        ensure!(Path::new(model).is_file(), "model file missing");
        ensure!(Path::new(&format!("{model}.json")).is_file(), "model sidecar missing");
    }
    umaai_runtime::initialize(&data_dir, &config)?;
    let session = RamenSession::new(config)?;
    let pool = ThreadPoolBuilder::new().num_threads(options.threads).thread_name(|n| format!("ramen-search-{n}")).build()?;
    *engine = Some(Engine { generation, data_dir, session, pool });
    Ok(json!({"ok":true,"version":version(),"config_id":options.config_id}))
}

/// Evaluate one private snapshot file and stream each result before completion.
pub fn evaluate_file(path: &Path, raw_options: &str, request_id: &str, sink: &mut dyn FnMut(Value)) -> Result<Value> {
    let options = EngineOptions::parse(raw_options)?;
    let (token, _guard) = register_request(request_id, options.deadline_ms)?;
    token.check()?;
    let metadata = fs::metadata(path).context("snapshot not found")?;
    ensure!(metadata.is_file() && metadata.len() <= MAX_SNAPSHOT_BYTES, "snapshot exceeds 8 MiB or is not a file");
    let raw = fs::read_to_string(path).context("snapshot is not UTF-8")?;
    // Parse before acquiring/initializing the simulator; incomplete input never searches.
    let input = umaai_runtime::RamenSnapshotV1::parse(&raw)?;
    let mut guard = ENGINE.get_or_init(Default::default).lock().map_err(|_| anyhow!("engine lock poisoned"))?;
    let engine = guard.as_mut().ok_or_else(|| anyhow!("engine_not_initialized"))?;
    ensure!(engine.generation == options.generation(), "config_generation_changed: restart engine process");
    let started = Instant::now();
    // A scoped worker owns evaluation. JNI callbacks stay on the calling thread
    // and receive events immediately, including before a subsequent search.
    let Engine { session, pool, .. } = engine;
    let evaluation = std::thread::scope(|scope| -> Result<_> {
        let (sender, receiver) = std::sync::mpsc::channel();
        let worker_token = token.clone();
        let seed = options.seed;
        let worker = scope.spawn(move || pool.install(|| {
            session.evaluate_json_with_sink(&raw, seed, worker_token, &mut |event: &RuntimeEvent| {
                let payload = serde_json::to_value(event).unwrap_or_else(|_| json!({"type":"failed","error":"event_serialization"}));
                let _ = sender.send(payload);
            })
        }));
        for (index, event) in receiver.into_iter().enumerate() {
            sink(json!({"request_id":request_id,"run_id":input.run_id,"snapshot_id":input.snapshot_id,
                "config_id":options.config_id,"event_seq":index + 1,"event":event}));
        }
        worker.join().map_err(|_| anyhow!("evaluation worker panicked"))?
    })?;
    token.check()?;
    Ok(json!({"ok":true,"request_id":request_id,"config_id":options.config_id,
        "engine_revision":version()["engine_revision"],"elapsed_ms":started.elapsed().as_millis(),
        "evaluation":evaluation}))
}

/// Offline review uses the same Rust analysis and embedded templates as PC.
pub fn review_run(input: &Path, output: &Path) -> Result<Value> {
    let engine = ENGINE.get_or_init(Default::default).lock().map_err(|_| anyhow!("engine lock poisoned"))?;
    let engine = engine.as_ref().ok_or_else(|| anyhow!("engine_not_initialized"))?;
    ensure!(input.is_dir(), "native_review_requires_run_directory: import ZIP through the record manager");
    let metadata: Value = serde_json::from_str(&fs::read_to_string(input.join("meta.json"))
        .context("review_version_metadata_missing")?)?;
    let data_manifest = fs::read(engine.data_dir.join("manifest.json")).context("active_data_manifest_missing")?;
    let actual_version = version();
    validate_review_versions(&metadata, &data_manifest, actual_version["engine_revision"].as_str().unwrap_or("unknown"))?;
    let report = umaai_review::analyze_pack(input, output, &engine.data_dir)?;
    Ok(json!({"ok":true,"report_path":report,"digest_path":output.join("digest.json")}))
}

/// Historical decisions must not be reinterpreted with a silently different model.
fn validate_review_versions(metadata: &Value, manifest_bytes: &[u8], engine_revision: &str) -> Result<()> {
    let recorded_engine = metadata["engine_revision"].as_str().context("review_engine_version_missing")?;
    let recorded_data = metadata["data_version"].as_str().context("review_data_version_missing")?;
    let manifest: Value = serde_json::from_slice(manifest_bytes).context("invalid active data manifest")?;
    ensure!(recorded_engine == engine_revision && manifest["engine_revision"].as_str() == Some(engine_revision),
        "review_engine_version_mismatch: open this record with its matching engine version");
    let actual_data = sha256_hex(manifest_bytes);
    ensure!(recorded_data == actual_data,
        "review_data_version_mismatch: historical data is preserved; select the matching app/data version");
    Ok(())
}

fn sha256_hex(bytes: &[u8]) -> String {
    Sha256::digest(bytes).iter().map(|byte| format!("{byte:02x}")).collect()
}

#[cfg(feature = "jni-support")]
fn response(result: Result<Value>) -> String {
    match result {
        Ok(v) => v.to_string(),
        Err(e) => {
            let detail = format!("{e:#}");
            let status = if detail.contains("request_cancelled") { "cancelled" } else { "failed" };
            json!({"ok":false,"status":status,"error":detail}).to_string()
        }
    }
}

#[cfg(feature = "jni-support")]
mod native {
    use super::*;
    use jni::{JNIEnv, objects::{JClass, JObject, JString, JValue}, sys::jstring};
    use std::panic::{AssertUnwindSafe, catch_unwind};

    fn string(env: &mut JNIEnv, value: &JString) -> Result<String> { Ok(env.get_string(value)?.into()) }
    fn output(env: &JNIEnv, value: String) -> jstring {
        env.new_string(value).map(JString::into_raw).unwrap_or(std::ptr::null_mut())
    }
    fn guarded(f: impl FnOnce() -> Result<Value>) -> String {
        match catch_unwind(AssertUnwindSafe(f)) {
            Ok(result) => response(result),
            Err(_) => json!({"ok":false,"error":"native_panic: restart engine process"}).to_string(),
        }
    }

    #[unsafe(no_mangle)]
    pub extern "system" fn Java_com_umaai_assistant_service_UmaNativeBridge_nativeInit(
        mut env: JNIEnv, _class: JClass, data: JString, options: JString,
    ) -> jstring {
        let value = guarded(|| initialize_engine(Path::new(&string(&mut env, &data)?), &string(&mut env, &options)?));
        output(&env, value)
    }
    #[unsafe(no_mangle)]
    pub extern "system" fn Java_com_umaai_assistant_service_UmaNativeBridge_nativeEvaluate(
        mut env: JNIEnv, _class: JClass, snapshot: JString, options: JString, request: JString, listener: JObject,
    ) -> jstring {
        let value = guarded(|| {
            let snapshot = string(&mut env, &snapshot)?;
            let options = string(&mut env, &options)?;
            let request = string(&mut env, &request)?;
            evaluate_file(Path::new(&snapshot), &options, &request, &mut |event| {
                if listener.is_null() { return; }
                let callback: jni::errors::Result<()> = env.with_local_frame(8, |env| {
                    let text = JObject::from(env.new_string(event.to_string())?);
                    env.call_method(&listener, "onNativeEvent", "(Ljava/lang/String;)V", &[JValue::Object(&text)])?;
                    Ok(())
                });
                if callback.is_err() {
                    let _ = env.exception_clear();
                    cancel_request(&request);
                }
            })
        });
        output(&env, value)
    }
    #[unsafe(no_mangle)]
    pub extern "system" fn Java_com_umaai_assistant_service_UmaNativeBridge_nativeCancel(
        mut env: JNIEnv, _class: JClass, request: JString,
    ) {
        if let Ok(id) = string(&mut env, &request) { cancel_request(&id); }
    }
    #[unsafe(no_mangle)]
    pub extern "system" fn Java_com_umaai_assistant_service_UmaNativeBridge_nativeVersion(
        env: JNIEnv, _class: JClass,
    ) -> jstring { output(&env, version().to_string()) }
    #[unsafe(no_mangle)]
    pub extern "system" fn Java_com_umaai_assistant_service_UmaNativeBridge_nativeReview(
        mut env: JNIEnv, _class: JClass, input: JString, out: JString,
    ) -> jstring {
        let value = guarded(|| review_run(Path::new(&string(&mut env, &input)?), Path::new(&string(&mut env, &out)?)));
        output(&env, value)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn no_silent_budget_or_policy_fallback() {
        assert!(EngineOptions::parse(r#"{"search_n":0}"#).is_err());
        assert!(EngineOptions::parse(r#"{"policy":"nn"}"#).is_err());
        assert!(EngineOptions::parse(r#"{"policy":"ga"}"#).is_err());
        assert_eq!(EngineOptions::parse("{}").unwrap().search_n, 8192);
    }
    #[test]
    fn early_cancel_survives_registration() {
        cancel_request("early-cancel-test");
        let (token, _guard) = register_request("early-cancel-test", 0).unwrap();
        assert!(token.is_cancelled());
    }
    #[test]
    fn cancellation_does_not_wait_for_engine_lock() {
        let _engine_lock = ENGINE.get_or_init(Default::default).lock().unwrap();
        let (token, _guard) = register_request("lock-test", 0).unwrap();
        cancel_request("lock-test");
        assert!(token.is_cancelled());
    }
    #[test]
    fn seed_does_not_change_configuration_generation() {
        let a = EngineOptions::default(); let mut b = a.clone(); b.seed += 1;
        assert_eq!(a.generation(), b.generation());
        b.search_n /= 2; assert_ne!(a.generation(), b.generation());
    }
    #[test]
    fn cancelled_guard_releases_request_id() {
        let (token, guard) = register_request("guard-test", 0).unwrap();
        assert!(register_request("guard-test", 0).is_err());
        token.cancel(); drop(guard);
        let (fresh, _guard) = register_request("guard-test", 0).unwrap();
        assert!(!fresh.is_cancelled());
    }
    #[test]
    fn historical_review_requires_matching_code_and_data() {
        let manifest = br#"{"engine_revision":"engine-a","files":{}}"#;
        let meta = json!({"engine_revision":"engine-a","data_version":sha256_hex(manifest)});
        assert!(validate_review_versions(&meta, manifest, "engine-a").is_ok());
        assert!(validate_review_versions(&meta, manifest, "engine-b").is_err());
        let changed = br#"{"engine_revision":"engine-a","files":{"a":"changed"}}"#;
        assert!(validate_review_versions(&meta, changed, "engine-a").is_err());
        assert!(validate_review_versions(&json!({}), manifest, "engine-a").is_err());
    }
}
