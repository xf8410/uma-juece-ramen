package com.umaai.assistant.service;
import com.umaai.assistant.BuildConfig;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 赛马娘育成数据收集器
 *
 * v2.4 修复清单（2026-07-17）：
 *   1. 换局不再由异步上传结果驱动：finalizeAndUpload 同步「冻结→startNewSession→
 *      入持久化队列」，网络线程永远不触碰当前 session（修跨局混合/数据丢失）
 *   2. startNewSession 改为 synchronized，消除网络线程 turns.clear() 并发
 *   3. 上传队列持久化到 filesDir/pending_uploads/（原子写），单 worker 线程循环，
 *      失败退避重试；final 上传失败不再丢整局；App 重启自动续传
 *   4. 整局状态持久化从 SharedPreferences 改为状态文件（原子写），
 *      SharedPreferences 只存小状态，修写放大/XML 损坏风险
 *   5. TurnSnapshot.fromJson 恢复全部字段（trainings/action_source/confidence/
 *      captured_at/scenario/支援卡等），重启后不再用残缺数据覆盖完整上传
 *   6. turn < prevTurn 显式分支 + 告警，不再静默接受回合回退
 *
 * v2.0 修复清单（2026-07-14）：
 *   1. parseSnapshot 对 /summary error JSON 做防御，不再抛异常
 *   2. detectAction 在 gains 全0时不再跳过，改用 command_id 直接映射
 *   3. 上传改为单线程串行队列，不再每回合开新线程（v2.4 起改为持久队列+单worker）
 *   4. 上传时冻结 session 快照，不再读取可变的 sessionId/scenario/turns
 *   5. startNewSession 不再被 finalizeAndUpload 间接重复调用（v2.4 起改同步切局）
 *   6. 恢复时持久化 turns/prevSnapshot/scenario 的 JSON 序列化（v2.4 起改状态文件）
 *   7. 文件名使用 session_id，与内部 session_id 保持一致
 *   8. shining/heads 保留 -1 未知语义（v2.3 起），不洗成 0
 *   9. 对 summary 错误、回合跳跃和数据断档做显式标记
 */
public class DataCollector {

    private static final String TAG = "DataCollector";
    private static final String PREFS_NAME = "uma_data_collector";
    private static final String KEY_SESSION_ID = "session_id";
    private static final String KEY_TURN_COUNT = "turn_count";
    private static final String KEY_SCENARIO = "scenario";
    private static final String KEY_TURNS_JSON = "turns_json";
    private static final String KEY_PREV_SNAPSHOT = "prev_snapshot";
    // 已消费的 SO training-hook 序号。必须持久化，避免 App 重启后复用旧动作。
    private static final String KEY_LAST_CONSUMED_ACTION_SEQUENCE = "last_consumed_action_sequence";
    private static final String KEY_LAST_CONSUMED_RNG_SEQUENCE = "last_consumed_rng_sequence";

    // GitHub upload config
    private static final String GITHUB_REPO = "xf8410/uma-data";
    private static final String GITHUB_BRANCH = "main";
    private static final String GITHUB_PATH = "training_sessions";
    private static final String GITHUB_TOKEN = BuildConfig.GITHUB_TOKEN;

    // 行动类型
    public static final String ACTION_SPEED = "Speed";
    public static final String ACTION_STAMINA = "Stamina";
    public static final String ACTION_POWER = "Power";
    public static final String ACTION_GUTS = "Guts";
    public static final String ACTION_WISDOM = "Wisdom";
    public static final String ACTION_REST = "Rest";
    public static final String ACTION_OUTING = "Outing";
    public static final String ACTION_UNKNOWN = "Unknown";
    public static final String ACTION_ERROR = "Error"; // ★ v2.0: summary 错误标记

    private Context context;
    private String sessionId;
    private String scenario;
    private List<TurnSnapshot> turns = new ArrayList<>();
    private final List<String> eventObservations = new ArrayList<>();
    private static final int MAX_EVENT_OBSERVATIONS = 64;
    private long lastEventObservationId = 0;
    private TurnSnapshot prevSnapshot = null;

    // ★ v2.4: 持久化上传队列 — 待上传文件先落盘，单 worker 线程消费，
    //         网络结果只决定文件删不删，永远不改当前 session 状态
    private final LinkedBlockingQueue<String> uploadQueue = new LinkedBlockingQueue<>();
    private final Set<String> queuedUploads = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean uploadWorkerRunning = new AtomicBoolean(false);
    private volatile Thread uploadWorker = null;
    private String lastUploadedSha = null; // 缓存上次 GET 的 SHA，避免每次都 GET
    private String lastUploadedPath = null; // SHA 缓存按远端路径 keyed，跨文件不复用

    // 上传回调
    public interface UploadCallback {
        void onUploaded(boolean success, String message);
    }

    public DataCollector(Context context) {
        this.context = context;
        restoreSession();
        // ★ v2.4: App 重启后续传上次未完成的 pending 上传
        enqueuePendingFromDisk();
    }

    // ========================================================================
    // 会话管理
    // ========================================================================

    private void restoreSession() {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        sessionId = prefs.getString(KEY_SESSION_ID, null);
        int savedTurnCount = prefs.getInt(KEY_TURN_COUNT, 0);
        scenario = prefs.getString(KEY_SCENARIO, null);
        lastConsumedActionSequence = prefs.getLong(KEY_LAST_CONSUMED_ACTION_SEQUENCE, 0);
        lastConsumedRngSequence = prefs.getLong(KEY_LAST_CONSUMED_RNG_SEQUENCE, 0);

        // ★ v2.4: 优先从状态文件恢复（完整字段）；文件不存在时回退旧 SharedPreferences
        // 整局 JSON（字段残缺，仅用于一次性迁移），迁移后下次 persistState 会写状态文件。
        String stateJson = readFileQuietly(getStateFile());
        boolean restoredFromFile = false;
        if (stateJson != null && !stateJson.isEmpty()) {
            try {
                JSONObject st = new JSONObject(stateJson);
                sessionId = st.optString("session_id", sessionId);
                scenario = st.optString("scenario", scenario);
                lastConsumedActionSequence = st.optLong("last_consumed_action_sequence",
                        lastConsumedActionSequence);
                lastConsumedRngSequence = st.optLong("last_consumed_rng_sequence", lastConsumedRngSequence);
                JSONArray arr = st.optJSONArray("turns");
                if (arr != null) {
                    for (int i = 0; i < arr.length(); i++) {
                        turns.add(TurnSnapshot.fromJson(arr.getJSONObject(i)));
                    }
                }
                JSONObject prev = st.optJSONObject("prev_snapshot");
                if (prev != null) prevSnapshot = TurnSnapshot.fromJson(prev);
                restoredFromFile = true;
                Log.d(TAG, "Restored session from state file: " + sessionId
                    + " turns: " + turns.size());
            } catch (JSONException e) {
                Log.e(TAG, "Restore state file failed: " + e.getMessage());
                turns.clear();
                prevSnapshot = null;
            }
        }

        if (!restoredFromFile && sessionId != null && savedTurnCount > 0) {
            // 旧路径：SharedPreferences 整局 JSON（fromJson 现在能恢复全部字段）
            String turnsJson = prefs.getString(KEY_TURNS_JSON, null);
            if (turnsJson != null) {
                try {
                    JSONArray arr = new JSONArray(turnsJson);
                    for (int i = 0; i < arr.length(); i++) {
                        turns.add(TurnSnapshot.fromJson(arr.getJSONObject(i)));
                    }
                    Log.d(TAG, "Migrated " + turns.size() + " turns from SharedPreferences");
                } catch (JSONException e) {
                    Log.e(TAG, "Restore turns failed: " + e.getMessage());
                }
            }
            String prevJson = prefs.getString(KEY_PREV_SNAPSHOT, null);
            if (prevJson != null) {
                try {
                    prevSnapshot = TurnSnapshot.fromJson(new JSONObject(prevJson));
                } catch (JSONException e) {
                    Log.e(TAG, "Restore prevSnapshot failed: " + e.getMessage());
                }
            }
            // 迁移完成后清掉旧的大字段，SharedPreferences 只留小状态
            prefs.edit().remove(KEY_TURNS_JSON).remove(KEY_PREV_SNAPSHOT).apply();
        }

        if (sessionId == null) {
            startNewSession();
        } else {
            Log.d(TAG, "Restored session: " + sessionId + " turns: " + turns.size()
                + " scenario: " + scenario);
        }
    }

    /** 开始新育成局（synchronized：可能被 UI/网络以外线程调用） */
    public synchronized void startNewSession() {
        startNewSessionLocked();
        persistState();
    }

    /** 调用方必须已持有 this 锁 */
    private void startNewSessionLocked() {
        sessionId = UUID.randomUUID().toString().substring(0, 8);
        scenario = null;
        turns.clear();
        prevSnapshot = null;
        Log.d(TAG, "New session: " + sessionId);
    }

    /** ★ v2.4: 持久化状态 — 小状态进 SharedPreferences，整局 JSON 写状态文件（原子写） */
    private void persistState() {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit()
            .putString(KEY_SESSION_ID, sessionId)
            .putInt(KEY_TURN_COUNT, turns.size())
            .putString(KEY_SCENARIO, scenario)
            .putLong(KEY_LAST_CONSUMED_ACTION_SEQUENCE, lastConsumedActionSequence)
            .putLong(KEY_LAST_CONSUMED_RNG_SEQUENCE, lastConsumedRngSequence)
            .apply();

        // 整局 JSON 写状态文件：先写临时文件再 rename，崩溃也不会留下半截文件
        try {
            JSONObject st = new JSONObject();
            st.put("session_id", sessionId);
            st.put("scenario", scenario);
            st.put("last_consumed_action_sequence", lastConsumedActionSequence);
            st.put("last_consumed_rng_sequence", lastConsumedRngSequence);
            JSONArray arr = new JSONArray();
            for (TurnSnapshot t : turns) {
                arr.put(t.toJson());
            }
            st.put("turns", arr);
            if (prevSnapshot != null) {
                st.put("prev_snapshot", prevSnapshot.toJson());
            }
            File tmp = new File(getStateFile().getAbsolutePath() + ".tmp");
            writeFile(tmp, st.toString());
            if (!tmp.renameTo(getStateFile())) {
                Log.w(TAG, "persistState rename failed");
            } else {
                LocalBackupManager.backupCurrentAsync(context, getStateFile());
            }
        } catch (Exception e) {
            Log.e(TAG, "Persist state failed: " + e.getMessage());
        }
    }

    private File getStateFile() {
        return new File(context.getFilesDir(), "uma_collector_state.json");
    }

    private File getPendingDir() {
        return new File(context.getFilesDir(), "pending_uploads");
    }

    private static void writeFile(File f, String content) throws Exception {
        File parent = f.getParentFile();
        if (parent != null) parent.mkdirs();
        FileOutputStream fos = new FileOutputStream(f);
        try {
            fos.write(content.getBytes(StandardCharsets.UTF_8));
            fos.getFD().sync();
        } finally {
            fos.close();
        }
    }

    private static String readFileQuietly(File f) {
        if (f == null || !f.exists()) return null;
        try {
            FileInputStream fis = new FileInputStream(f);
            try {
                StringBuilder sb = new StringBuilder();
                BufferedReader reader = new BufferedReader(
                    new InputStreamReader(fis, StandardCharsets.UTF_8));
                String line;
                while ((line = reader.readLine()) != null) sb.append(line);
                return sb.toString();
            } finally {
                fis.close();
            }
        } catch (Exception e) {
            Log.e(TAG, "Read file failed: " + f.getName() + " — " + e.getMessage());
            return null;
        }
    }

    /** 获取当前 session 已记录的不同回合数（同回合可有多个 observation）。 */
    public synchronized int getTurnCount() {
        int count = 0;
        int last = Integer.MIN_VALUE;
        for (TurnSnapshot t : turns) {
            if (t.turn != last) {
                count++;
                last = t.turn;
            }
        }
        if (prevSnapshot != null && prevSnapshot.turn != last) count++;
        return count;
    }

    public synchronized int getObservationCount() {
        return turns.size() + (prevSnapshot != null && !turns.contains(prevSnapshot) ? 1 : 0);
    }

    public String getSessionId() {
        return sessionId;
    }

    // ========================================================================
    // 数据采集
    // ========================================================================

    /**
     * 处理一次 /summary 数据推送
     * @return 检测到的行动类型（如果是新回合）
     */
    // ★ v2.3: 去重 — 同一 turn 的相同快照不重复处理
    private int lastProcessedTurn = -1;
    private String lastSnapshotHash = "";
    // last_action 是 SO 的“最近一次训练 hook”缓存；只可消费一次。
    private long lastConsumedActionSequence = 0;
    private long lastConsumedRngSequence = 0;

    public synchronized String onSummaryData(JSONObject json) {
        // ★ v2.0: 防御 — 检查是否是 error JSON
        if (json == null) return ACTION_ERROR;
        if (json.has("error") || json.has("sigsegv_recovered") || json.has("panic_caught")) {
            Log.w(TAG, "Received error summary, recording gap (not overwriting action)");
            // ★ v2.2: 不覆盖 prevSnapshot.actionTaken — 错误不是玩家动作
            // 只记录数据断档事件
            if (prevSnapshot != null) {
                // 保留上一个真实动作标签不变
                Log.d(TAG, "Summary gap at turn after " + prevSnapshot.turn);
            }
            return ACTION_ERROR;
        }
        if (!json.has("stats")) {
            Log.w(TAG, "Summary missing stats field, skipping");
            return ACTION_ERROR;
        }

        try {
            TurnSnapshot snapshot = parseSnapshot(json);
            if (snapshot == null) return ACTION_ERROR;

            // 同回合也可能发生吃面、RMJ Pt、素材、效果或训练人头变化。
            String snapHash = buildSnapshotHash(snapshot);
            if (snapshot.turn == lastProcessedTurn && snapHash.equals(lastSnapshotHash)) {
                Log.d(TAG, "Duplicate summary for turn " + snapshot.turn + ", skipping");
                return ACTION_UNKNOWN;
            }
            lastProcessedTurn = snapshot.turn;
            lastSnapshotHash = snapHash;

            String detectedAction = ACTION_UNKNOWN;

            // ★ v2.2: 新育成检测 — 不能只靠 turn<=1，还要检查 chara_id 变化
            // 跨年时 month=1 half=1 会导致旧逻辑误判为新育成
            boolean isNewSession = false;
            if (prevSnapshot != null) {
                // 条件1: turn 从大跳到小（且不是同 year 的正常流动）
                if (snapshot.turn <= 1 && prevSnapshot.turn > 1) {
                    // 可能是跨年（year 2→3 month 1）或真正新育成
                    // 如果 chara_id 变了 → 确定是新育成
                    if (snapshot.charaId > 0 && prevSnapshot.charaId > 0
                            && snapshot.charaId != prevSnapshot.charaId) {
                        isNewSession = true;
                    } else if (snapshot.charaId > 0 && prevSnapshot.charaId > 0
                            && snapshot.charaId == prevSnapshot.charaId) {
                        // 同角色 → 可能是跨年误判，不结束 session
                        Log.d(TAG, "Turn reset but same chara_id=" + snapshot.charaId
                            + " — likely year transition, not new session");
                    } else {
                        // chara_id 未知 → 保守判断，用 scenario 变化
                        if (snapshot.scenario != null && prevSnapshot.scenario != null
                                && !snapshot.scenario.equals(prevSnapshot.scenario)) {
                            isNewSession = true;
                        }
                        // 否则不结束 session
                    }
                }
            }

            if (isNewSession) {
                // ★ v2.4: finalizeAndUpload 同步完成「冻结旧局→startNewSession→入队」，
                // 返回时 prevSnapshot 已为 null，下面的基线分支接管新局第一帧。
                Log.d(TAG, "Session " + sessionId + " ended (new session detected), finalizing...");
                finalizeAndUpload();
            }

            // 新局/App 重启后的第一帧：建立基线，丢弃 SO 缓存的旧训练动作
            if (prevSnapshot == null) {
                // 建立本局基线时丢弃 SO 已存在的最近训练动作。该动作可能属于
                // App 启动前或上一局；没有前后快照不能安全归属，宁可走 Unknown。
                JSONObject baselineAction = json.optJSONObject("last_action");
                if (baselineAction != null) {
                    long baselineSeq = baselineAction.optLong("sequence", 0);
                    if (baselineSeq > lastConsumedActionSequence) {
                        lastConsumedActionSequence = baselineSeq;
                        Log.d(TAG, "Baseline consumed training_hook seq=" + baselineSeq);
                    }
                }
                prevSnapshot = snapshot;
                if (snapshot.scenario != null && !snapshot.scenario.isEmpty()) {
                    scenario = snapshot.scenario;
                }
                persistState();
                return detectedAction;
            }

            // 检测玩家行动
            if (snapshot.turn > prevSnapshot.turn) {
                // ★ v2.2: 检测回合跳跃（非连续）— 不伪造全0回合，只记录 gap
                int turnDelta = snapshot.turn - prevSnapshot.turn;
                if (turnDelta > 1) {
                    Log.w(TAG, "Turn jump: " + prevSnapshot.turn + " → " + snapshot.turn
                        + " (gap=" + (turnDelta - 1) + "), recording gap (not faking turns)");
                }

                // ★ v2.3: 优先使用 SO hook 捕获的真实动作
                JSONObject lastAction = json.optJSONObject("last_action");
                if (lastAction != null) {
                    String soAction = lastAction.optString("action", "");
                    int soCmdId = lastAction.optInt("raw_command_id", -1);
                    int soNormId = lastAction.optInt("normalized_command_id", -1);
                    long soSeq = lastAction.optLong("sequence", 0);
                    // last_action 是“最近一次训练 hook”缓存，且 sub_id 尚未证实等于
                    // CommandId。因此 sequence 只能用于去重，不能作为训练动作的真值。
                    if (soSeq > lastConsumedActionSequence) {
                        lastConsumedActionSequence = soSeq;
                        Log.d(TAG, "Consumed fresh unverified training_hook seq=" + soSeq
                                + " for turn " + prevSnapshot.turn + " → " + snapshot.turn
                                + "; falling back to inference (raw sub_id=" + soCmdId + ")");
                    } else if (soSeq > 0 && soSeq < lastConsumedActionSequence) {
                        // SO/插件重启后 sequence 会从较小值重新开始。丢弃这条无法安全
                        // 归属的记录，并重置水位；下一条递增记录才允许作为“新鲜”观察值。
                        lastConsumedActionSequence = soSeq;
                        Log.w(TAG, "training_hook sequence reset to " + soSeq
                                + "; dropped current unverified action");
                    } else if (soSeq > 0) {
                        Log.d(TAG, "Ignoring consumed training_hook action seq=" + soSeq
                                + " for turn " + prevSnapshot.turn + " → " + snapshot.turn);
                    }
                }

                // 回退到推断
                detectedAction = detectAction(prevSnapshot, snapshot);
                Log.d(TAG, "Turn " + prevSnapshot.turn + " → " + snapshot.turn
                    + " action: " + detectedAction + " (source=inference)");


                prevSnapshot.actionTaken = detectedAction;
                prevSnapshot.actionSource = "stat_delta_inference";
                prevSnapshot.actionConfidence = 0.4;
                turns.add(prevSnapshot);

                // ★ v2.4: checkpoint 入持久化上传队列（先落盘再排队，崩溃不丢）
                checkpointUploadLocked();
            } else if (snapshot.turn == prevSnapshot.turn) {
                // 同回合实质变化也要留档，例如吃面前后 RMJ/素材/效果/人头变化。
                snapshot.actionTaken = prevSnapshot.actionTaken;
                snapshot.actionSource = prevSnapshot.actionSource;
                snapshot.actionConfidence = prevSnapshot.actionConfidence;
                snapshot.sameTurnTransitionRaw = buildSameTurnTransition(prevSnapshot, snapshot);
                prevSnapshot.observationKind = "same_turn_before_change";
                turns.add(prevSnapshot);
                prevSnapshot = snapshot;
                if (snapshot.scenario != null && !snapshot.scenario.isEmpty()) scenario = snapshot.scenario;
                persistState();
                checkpointUploadLocked();
                return detectedAction;
            } else {
                // ★ v2.4: 回合回退（SO读取抖动等）— 不记录、不伪造，显式告警并更新基准
                Log.w(TAG, "Turn regression: " + prevSnapshot.turn + " → " + snapshot.turn
                    + " — updating baseline only (not recorded)");
            }

            // 更新scenario
            if (snapshot.scenario != null && !snapshot.scenario.isEmpty()) {
                scenario = snapshot.scenario;
            }

            prevSnapshot = snapshot;
            persistState();

            return detectedAction;

        } catch (Exception e) {
            Log.e(TAG, "onSummaryData error: " + e.getMessage());
            return ACTION_ERROR;
        }
    }

    /**
     * ★ v1.24: 处理插件推送的事件选择数据
     */
    public synchronized void onEventObservation(JSONObject observation) {
        if (observation == null) return;
        long id = observation.optLong("observation_id", 0);
        if (id <= 0 || id <= lastEventObservationId) return;
        // Keep runtime labels separate: result_label remains unknown without direct UI evidence.
        if (eventObservations.size() >= MAX_EVENT_OBSERVATIONS) eventObservations.remove(0);
        eventObservations.add(observation.toString());
        lastEventObservationId = id;
        persistState();
        checkpointUploadLocked();
    }

    public void onEventData(JSONObject json) {
        try {
            int storyId = json.optInt("story_id", 0);
            int charaId = json.optInt("chara_id", 0);
            if (storyId > 0 || charaId > 0) {
                Log.d(TAG, "Event data: story_id=" + storyId + " chara_id=" + charaId);
                if (prevSnapshot != null) {
                    prevSnapshot.storyId = storyId;
                    prevSnapshot.charaId = charaId;
                }
                if (!turns.isEmpty()) {
                    TurnSnapshot last = turns.get(turns.size() - 1);
                    if (last.storyId == 0) last.storyId = storyId;
                    if (last.charaId == 0) last.charaId = charaId;
                }
                persistState();
            }
        } catch (Exception e) {
            Log.e(TAG, "onEventData error: " + e.getMessage());
        }
    }

    /**
     * 从 /summary JSON 解析回合快照
     */
    private TurnSnapshot parseSnapshot(JSONObject json) {
        try {
            TurnSnapshot s = new TurnSnapshot();
            JSONObject stats = json.getJSONObject("stats");

            s.month = json.optInt("month", 1);
            s.half = json.optInt("half", 1);
            // ★ v2.2: 累计 turn 计算 — 如果 SO 提供了 "turn" 字段直接用
            int soTurn = json.optInt("turn", 0);
            if (soTurn > 0) {
                s.turn = soTurn;
            } else {
                // ★ v2.2: 从 year + month + half 计算
                // 赛马娘育成：year 1 从 4 月开始，每年 12 个月 × 2 half = 24 turn
                // year=1 month=4 half=1 → turn 1
                // year=2 month=1 half=1 → turn 19
                // year=3 month=1 half=1 → turn 43
                int year = json.optInt("year", 0);
                if (year > 0) {
                    s.turn = (year - 1) * 24 + (s.month - 1) * 2 + s.half;
                } else {
                    // 无 year 字段 — 旧逻辑兜底，但标记可能跨年错误
                    s.turn = (s.month - 1) * 2 + s.half;
                }
            }
            s.scenario = json.optString("scenario", "");
            s.charaId = json.optInt("chara_id", 0);
            s.storyId = json.optInt("story_id", 0);

            s.speed = stats.optInt("speed", 0);
            s.stamina = stats.optInt("stamina", 0);
            s.power = stats.optInt("power", 0);
            s.guts = stats.optInt("guts", 0);
            s.wisdom = stats.optInt("wiz", 0);
            s.skillPt = stats.optInt("skill_point", 0);
            s.vital = stats.optInt("vital", 50);
            s.maxVital = stats.optInt("max_vital", 100);
            s.fan = stats.optInt("fan", 0);
            String motStr = stats.optString("motivation", "Normal");
            switch (motStr) {
                case "Best":  s.motivation = 5; break;
                case "Good":  s.motivation = 4; break;
                case "Normal": s.motivation = 3; break;
                case "Bad":   s.motivation = 2; break;
                case "Worst": s.motivation = 1; break;
                default:      s.motivation = 3; break;
            }


            // 训练选项
            JSONArray trainings = json.optJSONArray("trainings");
            if (trainings != null) {
                s.trainings = new TrainingOption[trainings.length()];
                for (int i = 0; i < trainings.length(); i++) {
                    JSONObject tr = trainings.getJSONObject(i);
                    TrainingOption opt = new TrainingOption();
                    opt.name = tr.optString("name", "");
                    opt.commandId = tr.optInt("command_id", 0);
                    opt.isEnabled = tr.optInt("is_enable", 1) != 0;

                    JSONObject gains = tr.optJSONObject("gains");
                    if (gains != null) {
                        opt.gainSpeed = gains.optInt("Speed", 0);
                        opt.gainStamina = gains.optInt("Stamina", 0);
                        opt.gainPower = gains.optInt("Power", 0);
                        opt.gainGuts = gains.optInt("Guts", 0);
                        opt.gainWisdom = gains.optInt("Wiz", 0);
                        opt.gainSkillPt = gains.optInt("SkillPt", 0);
                        opt.vitalCost = -gains.optInt("HP", 0);
                    }

                    opt.failureRate = tr.optInt("failure_rate", 0);
                    JSONArray partnerIds = tr.optJSONArray("partner_ids");
                    if (partnerIds != null) opt.partnerIdsRaw = partnerIds.toString();
                    JSONArray partners = tr.optJSONArray("partners");
                    if (partners != null) opt.partnersRaw = partners.toString();
                    // ★ v2.0: 统一 shining/heads 的 -1/null/0 → 0
                    // ★ v2.3: shining/heads — 保留 -1（未知），不洗成 0
                    int sh = tr.optInt("shining", -1);
                    opt.shining = sh; // -1=unknown, 0=none, >0=count
                    int hd = tr.optInt("heads", -1);
                    opt.heads = hd; // -1=unknown, 0=none, >0=count
                    s.trainings[i] = opt;
                }
            }

            // Buff
            JSONArray buffs = json.optJSONArray("buffs");
            if (buffs != null) {
                s.buffsRaw = buffs.toString();
            }

            // chara_effect_ids (疾病)
            JSONArray effectIds = json.optJSONArray("chara_effect_ids");
            if (effectIds != null) {
                s.hasBadCondition = false;
                for (int i = 0; i < effectIds.length(); i++) {
                    int eid = effectIds.optInt(i, 0);
                    if (eid >= 1 && eid <= 6) {
                        s.hasBadCondition = true;
                        break;
                    }
                }
                s.charaEffectIds = effectIds.toString();
            }

            // 训练等级
            JSONArray trainLevels = json.optJSONArray("training_levels");
            if (trainLevels != null) {
                s.trainingLevelsRaw = trainLevels.toString();
            }

            // 羁绊
            JSONArray evaluation = json.optJSONArray("evaluation");
            if (evaluation != null) {
                s.evaluationRaw = evaluation.toString();
            }

            // AI推荐
            JSONObject ai = json.optJSONObject("ai");
            if (ai != null) {
                s.aiBest = ai.optString("best", "");
                s.aiScore = ai.optInt("score", 0);
                s.skillEval = ai.optInt("skill_eval", 0);
                s.skillCount = ai.optInt("skill_count", 0);
            }

            // Ramen
            JSONObject ramen = json.optJSONObject("ramen");
            if (ramen != null) {
                s.ramenRaw = ramen.toString();
                JSONArray gaugeGains = ramen.optJSONArray("gauge_gains");
                if (gaugeGains != null && gaugeGains.length() > 0) {
                    s.gaugeGainsRaw = gaugeGains.toString();
                }
            }

            // Support cards
            JSONArray supportCards = json.optJSONArray("support_cards");
            if (supportCards != null && supportCards.length() > 0) {
                s.supportCardsRaw = supportCards.toString();
            }

            // Skills
            JSONObject skills = json.optJSONObject("skills");
            if (skills != null) {
                s.skillsRaw = skills.toString();
            }

            return s;
        } catch (JSONException e) {
            Log.e(TAG, "parseSnapshot error: " + e.getMessage());
            return null;
        }
    }

    private String buildSnapshotHash(TurnSnapshot s) {
        StringBuilder b = new StringBuilder();
        b.append(s.turn).append('|').append(s.speed).append('|').append(s.stamina)
            .append('|').append(s.power).append('|').append(s.guts).append('|')
            .append(s.wisdom).append('|').append(s.skillPt).append('|').append(s.vital)
            .append('|').append(s.maxVital).append('|').append(s.motivation)
            .append('|').append(s.ramenRaw == null ? "" : s.ramenRaw)
            .append('|').append(s.buffsRaw == null ? "" : s.buffsRaw);
        if (s.trainings != null) for (TrainingOption t : s.trainings) {
            b.append('|').append(t.commandId).append(':').append(t.isEnabled)
                .append(':').append(t.gainSpeed).append(':').append(t.gainStamina)
                .append(':').append(t.gainPower).append(':').append(t.gainGuts)
                .append(':').append(t.gainWisdom).append(':').append(t.gainSkillPt)
                .append(':').append(t.vitalCost).append(':').append(t.failureRate)
                .append(':').append(t.shining).append(':').append(t.heads)
                .append(':').append(t.partnerIdsRaw == null ? "" : t.partnerIdsRaw)
                .append(':').append(t.partnersRaw == null ? "" : t.partnersRaw);
        }
        return Integer.toHexString(b.toString().hashCode());
    }

    /** 仅记录直接观察到的前后值；不把相关变化命名为吃面因果。 */
    private String buildSameTurnTransition(TurnSnapshot before, TurnSnapshot after) {
        try {
            JSONObject event = new JSONObject();
            event.put("type", "observed_same_turn_change");
            event.put("turn", after.turn);
            event.put("captured_at", after.capturedAt);
            if (before.ramenRaw != null && after.ramenRaw != null) {
                JSONObject rb = new JSONObject(before.ramenRaw), ra = new JSONObject(after.ramenRaw);
                JSONObject ramen = new JSONObject();
                String[] keys = {"checkpoint_pt", "moriagari_level", "special_feeling_num",
                    "recommend_type", "sozai", "selected_region_ids", "active_effects", "feeling_info"};
                for (String key : keys) copyObservedChange(ramen, key, rb, ra);
                if (ramen.length() > 0) event.put("ramen", ramen);
            }
            JSONArray hb = trainingCounts(before), ha = trainingCounts(after);
            if (!hb.toString().equals(ha.toString())) {
                JSONObject tr = new JSONObject(); tr.put("before", hb); tr.put("after", ha);
                event.put("training_counts", tr);
            }
            return event.length() > 3 ? event.toString() : null;
        } catch (Exception e) {
            Log.w(TAG, "Build same-turn transition failed: " + e.getMessage());
            return null;
        }
    }

    private void copyObservedChange(JSONObject out, String key, JSONObject before,
            JSONObject after) throws JSONException {
        Object b = before.opt(key), a = after.opt(key);
        String bs = b == null ? "null" : b.toString(), as = a == null ? "null" : a.toString();
        if (!bs.equals(as)) {
            JSONObject change = new JSONObject();
            change.put("before", b == null ? JSONObject.NULL : b);
            change.put("after", a == null ? JSONObject.NULL : a);
            if (b instanceof Number && a instanceof Number)
                change.put("delta", ((Number) a).longValue() - ((Number) b).longValue());
            out.put(key, change);
        }
    }

    private JSONArray trainingCounts(TurnSnapshot s) throws JSONException {
        JSONArray arr = new JSONArray();
        if (s.trainings != null) for (TrainingOption t : s.trainings) {
            JSONObject o = new JSONObject();
            o.put("command_id", t.commandId); o.put("name", t.name);
            o.put("heads", t.heads); o.put("shining", t.shining);
            if (t.partnerIdsRaw != null) o.put("partner_ids", new JSONArray(t.partnerIdsRaw));
            if (t.partnersRaw != null) o.put("partners", new JSONArray(t.partnersRaw));
            arr.put(o);
        }
        return arr;
    }

    // ========================================================================
    // 行动检测
    // ========================================================================

    /**
     * ★ v2.1: 通过 training_levels 变化检测动作（弱辅助信号）
     * ★ v2.3: 降级为辅助 — 设施等级不是每次训练都升级
     * training_levels 格式: [{"command_id":101,"level":3}, ...]
     * 只有恰好一个普通训练等级+1时才作为辅助证据
     * 多个变化或非普通训练变化时返回 null
     */
    private String detectActionByTrainingLevels(TurnSnapshot prev, TurnSnapshot curr) {
        if (prev.trainingLevelsRaw == null || curr.trainingLevelsRaw == null) return null;
        try {
            JSONArray prevLevels = new JSONArray(prev.trainingLevelsRaw);
            JSONArray currLevels = new JSONArray(curr.trainingLevelsRaw);
            // ★ v2.3: 不要求长度相同，按 command_id 建 Map

            java.util.Map<Integer, Integer> prevMap = new java.util.HashMap<>();
            java.util.Map<Integer, Integer> currMap = new java.util.HashMap<>();
            for (int i = 0; i < prevLevels.length(); i++) {
                JSONObject pl = prevLevels.optJSONObject(i);
                if (pl != null) {
                    int cid = pl.optInt("command_id", 0);
                    if (cid > 0) prevMap.put(cid, pl.optInt("level", 0));
                }
            }
            for (int i = 0; i < currLevels.length(); i++) {
                JSONObject cl = currLevels.optJSONObject(i);
                if (cl != null) {
                    int cid = cl.optInt("command_id", 0);
                    if (cid > 0) currMap.put(cid, cl.optInt("level", 0));
                }
            }

            // 找等级变化（只看普通训练 101/102/103/105/106）
            int changedCount = 0;
            String changedAction = null;
            int[] validCmdIds = {101, 102, 103, 105, 106};
            for (int cmdId : validCmdIds) {
                int prevLv = prevMap.getOrDefault(cmdId, 0);
                int currLv = currMap.getOrDefault(cmdId, 0);
                if (currLv > prevLv && currLv - prevLv == 1) {
                    changedCount++;
                    switch (cmdId) {
                        case 101: changedAction = ACTION_SPEED; break;
                        case 105: changedAction = ACTION_STAMINA; break;
                        case 103: changedAction = ACTION_GUTS; break;
                        case 102: changedAction = ACTION_POWER; break;
                        case 106: changedAction = ACTION_WISDOM; break;
                    }
                }
            }
            // ★ v2.3: 恰好一个变化才作为辅助，多个变化返回 null
            if (changedCount == 1) return changedAction;
        } catch (Exception e) {
            Log.w(TAG, "detectActionByTrainingLevels error: " + e.getMessage());
        }
        return null;
    }

    /**
     * 对比前后两个快照，检测玩家采取的行动
     * ★ v2.0: 优先用 command_id 直接映射，gains 全0时不跳过
     */
    private String detectAction(TurnSnapshot prev, TurnSnapshot curr) {
        if (prev.trainings == null) return ACTION_UNKNOWN;

        int dSpeed = curr.speed - prev.speed;
        int dStamina = curr.stamina - prev.stamina;
        int dPower = curr.power - prev.power;
        int dGuts = curr.guts - prev.guts;
        int dWisdom = curr.wisdom - prev.wisdom;
        int dVital = curr.vital - prev.vital;
        int dMotivation = curr.motivation - prev.motivation;

        // 1. 外出：干劲上升
        if (dMotivation > 0 && dVital > -30) {
            return ACTION_OUTING;
        }

        // 2. 休息：体力上升且属性无变化
        if (dVital > 25 && Math.abs(dSpeed) + Math.abs(dStamina) + Math.abs(dPower)
                + Math.abs(dGuts) + Math.abs(dWisdom) < 10) {
            return ACTION_REST;
        }

        // ★ v2.1: 优先用 training_levels 变化检测
        // 训练后等级+1的那个就是选中的训练
        String levelAction = detectActionByTrainingLevels(prev, curr);
        if (levelAction != null) return levelAction;

        // ★ v2.0: 优先用 command_id 映射（如果 prev 快照有训练数据）
        // 找属性变化最大的方向，然后匹配有对应 command_id 的训练
        int[] deltas = {dSpeed, dStamina, dGuts, dPower, dWisdom};
        String[] actions = {ACTION_SPEED, ACTION_STAMINA, ACTION_GUTS, ACTION_POWER, ACTION_WISDOM};
        // ★ 2026-07-17 修正：command_id 与 deltas 同序（速/耐/根/力/智）— 游戏内 105=耐力、102=力量
        int[] commandIds = {101, 105, 103, 102, 106}; // 无 104

        // 找最大变化方向
        int maxIdx = 0;
        for (int i = 1; i < 5; i++) {
            if (deltas[i] > deltas[maxIdx]) maxIdx = i;
        }

        // 如果最大变化 > 5，且对应的训练选项存在且启用，直接匹配
        if (deltas[maxIdx] > 5) {
            // 检查是否有对应 command_id 的训练选项
            for (TrainingOption opt : prev.trainings) {
                if (!opt.isEnabled) continue;
                if (opt.commandId == commandIds[maxIdx]) {
                    return actions[maxIdx];
                }
            }
        }

        // 3. 余弦匹配（gains 非0时才用）
        String bestMatch = ACTION_UNKNOWN;
        double bestScore = -1;

        for (TrainingOption opt : prev.trainings) {
            if (!opt.isEnabled) continue;

            double dot = opt.gainSpeed * dSpeed
                       + opt.gainStamina * dStamina
                       + opt.gainPower * dPower
                       + opt.gainGuts * dGuts
                       + opt.gainWisdom * dWisdom;

            double predMag = Math.sqrt(opt.gainSpeed * opt.gainSpeed
                                     + opt.gainStamina * opt.gainStamina
                                     + opt.gainPower * opt.gainPower
                                     + opt.gainGuts * opt.gainGuts
                                     + opt.gainWisdom * opt.gainWisdom);

            double actualMag = Math.sqrt(dSpeed * dSpeed + dStamina * dStamina
                                       + dPower * dPower + dGuts * dGuts
                                       + dWisdom * dWisdom);

            // ★ v2.0: predMag < 1 时不跳过，而是用 command_id 兜底
            if (predMag < 1 || actualMag < 1) {
                // gains 全0，用 command_id 直接映射
                if (opt.commandId > 0 && deltas[maxIdx] > 5) {
                    if (opt.commandId == commandIds[maxIdx]) {
                        return actions[maxIdx];
                    }
                }
                continue;
            }

            double cosine = dot / (predMag * actualMag);
            if (cosine > bestScore) {
                bestScore = cosine;
                bestMatch = trainingNameToAction(opt.name);
            }
        }

        if (bestScore > 0.5) {
            return bestMatch;
        }

        // 4. 简单启发式
        int maxDelta = Math.max(Math.max(dSpeed, dStamina),
                       Math.max(Math.max(dPower, dGuts), dWisdom));
        if (maxDelta > 5) {
            if (dSpeed == maxDelta) return ACTION_SPEED;
            if (dStamina == maxDelta) return ACTION_STAMINA;
            if (dPower == maxDelta) return ACTION_POWER;
            if (dGuts == maxDelta) return ACTION_GUTS;
            if (dWisdom == maxDelta) return ACTION_WISDOM;
        }

        // 5. 体力恢复
        if (dVital > 15) {
            return ACTION_REST;
        }

        return ACTION_UNKNOWN;
    }

    /** 训练名 → 行动标识 */
    private String trainingNameToAction(String name) {
        if (name == null) return ACTION_UNKNOWN;
        switch (name.toLowerCase()) {
            case "speed": return ACTION_SPEED;
            case "stamina": return ACTION_STAMINA;
            case "power": return ACTION_POWER;
            case "guts": return ACTION_GUTS;
            case "wisdom": return ACTION_WISDOM;
            default: return ACTION_UNKNOWN;
        }
    }

    // ========================================================================
    // 数据导出 & 上传
    // ========================================================================

    /**
     * 育成结束：同步「冻结旧局 → 开新局 → 旧局入持久化上传队列」。
     * 网络结果永远不触碰当前 session（v2.4 修复跨局混合/数据丢失）。
     * @return 本次 finalize 的回合数（0 = 没有数据）
     */
    public synchronized int finalizeAndUpload() {
        if (turns.isEmpty() && prevSnapshot == null) {
            Log.d(TAG, "No turns to upload");
            return 0;
        }

        // 把最后一回合也加进去
        if (prevSnapshot != null && !turns.contains(prevSnapshot)) {
            prevSnapshot.actionTaken = ACTION_UNKNOWN;
            turns.add(prevSnapshot);
        }

        // 1. 冻结（持锁内读取 live turns 是安全的；返回的字符串与可变状态脱钩）
        String payload;
        int finalizedTurns;
        String finalizedId = sessionId;
        try {
            JSONObject sessionData = buildSessionJson(true, sessionId, scenario, null);
            if (sessionData == null) return 0;
            payload = sessionData.toString();
            finalizedTurns = turns.size();
        } catch (Exception e) {
            Log.e(TAG, "Freeze final snapshot failed: " + e.getMessage());
            return 0;
        }

        // 2. 同步开新局 — 此后所有新 summary 都归属新 session
        startNewSessionLocked();
        persistState();

        // 3. 旧局落盘 + 入队（失败会退避重试，App 重启也会续传）
        enqueueUpload(finalizedId, payload);
        Log.d(TAG, "Session " + finalizedId + " finalized (" + finalizedTurns
            + " turns), queued for upload; new session: " + sessionId);
        return finalizedTurns;
    }

    /**
     * ★ v2.4: 每回合 checkpoint — 冻结当前 session 并入持久化队列。
     * 文件名固定为 sessionId.json，新的 checkpoint 覆盖旧的，队列自然去重。
     * 调用方必须已持有 this 锁（onSummaryData 是 synchronized）。
     */
    private void checkpointUploadLocked() {
        try {
            JSONObject sessionData = buildSessionJson(false, sessionId, scenario, prevSnapshot);
            if (sessionData == null) return;
            enqueueUpload(sessionId, sessionData.toString());
        } catch (Exception e) {
            Log.e(TAG, "Checkpoint freeze failed: " + e.getMessage());
        }
    }

    // ========================================================================
    // ★ v2.4: 持久化上传队列 — payload 先原子落盘，单 worker 线程消费
    // ========================================================================

    /** 把冻结的 session JSON 原子写入 pending 目录并排队（幂等，按 sessionId 去重） */
    private void enqueueUpload(String sessId, String payload) {
        try {
            File dir = getPendingDir();
            File tmp = new File(dir, sessId + ".tmp");
            writeFile(tmp, payload);
            File dst = new File(dir, sessId + ".json");
            if (!tmp.renameTo(dst)) {
                Log.e(TAG, "enqueueUpload rename failed: " + sessId);
                return;
            }
            LocalBackupManager.backupSessionAsync(context, sessId, dst);
        } catch (Exception e) {
            Log.e(TAG, "enqueueUpload write failed: " + sessId + " — " + e.getMessage());
            return;
        }
        if (queuedUploads.add(sessId)) {
            uploadQueue.offer(sessId);
            ensureUploadWorker();
        }
    }

    /** App 启动时把 pending 目录里未传完的文件重新排队 */
    private void enqueuePendingFromDisk() {
        File dir = getPendingDir();
        File[] files = dir.listFiles();
        if (files == null) return;
        int n = 0;
        for (File f : files) {
            String name = f.getName();
            if (!name.endsWith(".json")) continue;
            String sessId = name.substring(0, name.length() - 5);
            if (queuedUploads.add(sessId)) {
                uploadQueue.offer(sessId);
                n++;
            }
        }
        if (n > 0) {
            Log.d(TAG, "Re-queued " + n + " pending uploads from disk");
            ensureUploadWorker();
        }
    }

    /** 启动上传 worker（synchronized：与 worker 退出路径互斥，防止退出竞态丢任务） */
    private synchronized void ensureUploadWorker() {
        if (uploadWorker != null && uploadWorker.isAlive()) return;
        uploadWorkerRunning.set(true);
        Thread t = new Thread(this::uploadWorkerLoop);
        t.setDaemon(true);
        t.setName("UploadWorker");
        uploadWorker = t;
        t.start();
    }

    private static final long UPLOAD_RETRY_BACKOFF_MS = 30_000;

    private void uploadWorkerLoop() {
        while (uploadWorkerRunning.get()) {
            String sessId;
            try {
                sessId = uploadQueue.poll(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                return;
            }
            if (sessId == null) {
                // 队列空了：再扫一次磁盘（覆盖「上次失败遗留」），真空则退出线程
                enqueuePendingFromDisk();
                synchronized (this) {
                    if (uploadQueue.peek() == null) {
                        uploadWorker = null;
                        return;
                    }
                }
                continue;
            }

            File f = new File(getPendingDir(), sessId + ".json");
            if (!f.exists()) {
                // 已被更新/删除（例如同 session 的更新 checkpoint 覆盖后又成功）
                queuedUploads.remove(sessId);
                continue;
            }

            boolean ok = uploadPendingFile(f);
            if (ok) {
                if (!f.delete()) {
                    Log.w(TAG, "Pending file delete failed: " + f.getName());
                }
                queuedUploads.remove(sessId);
            } else {
                // 失败：退避后重试（文件保留，queuedUploads 保留，重启也会续传）
                Log.w(TAG, "Upload failed for " + sessId + ", retry in "
                    + (UPLOAD_RETRY_BACKOFF_MS / 1000) + "s");
                try {
                    Thread.sleep(UPLOAD_RETRY_BACKOFF_MS);
                } catch (InterruptedException e) {
                    return;
                }
                uploadQueue.offer(sessId);
            }
        }
    }

    /** 读取 pending 文件并上传。返回 true = 成功（可删文件） */
    private boolean uploadPendingFile(File f) {
        String content = readFileQuietly(f);
        if (content == null || content.isEmpty()) {
            Log.e(TAG, "Pending file unreadable, dropping: " + f.getName());
            return true; // 读不出内容，重试无意义，丢弃避免死循环
        }
        try {
            JSONObject session = new JSONObject(content);
            String sessId = session.optString("session_id",
                f.getName().replace(".json", ""));
            String scen = session.optString("scenario", "Unknown");
            boolean isFinal = session.optBoolean("is_final", false);
            int turnCount = session.optInt("turn_count", 0);
            return uploadToGitHub(content, sessId, scen, turnCount, isFinal);
        } catch (JSONException e) {
            Log.e(TAG, "Pending file corrupt, dropping: " + f.getName());
            return true; // 坏文件，重试无意义
        }
    }

    /**
     * 构建整局数据JSON
     * ★ v2.4: 调用方必须已持有 this 锁（onSummaryData/finalizeAndUpload 均 synchronized），
     * 此时读取 live turns 安全；返回的 JSONObject 与可变状态脱钩，可安全传给后台线程。
     */
    private JSONObject buildSessionJson(boolean isFinal, String sessId,
            String scen, TurnSnapshot prevSnap) {
        try {
            JSONObject session = new JSONObject();
            session.put("session_id", sessId);
            session.put("schema_version", 4);
            session.put("rng_observation_valid", false);
            session.put("rng_invalid_reason", "offset_0x198_is_ObscuredInt_not_u32x4");
            session.put("app_version", "v2.4");
            session.put("app_commit", BuildConfig.VERSION_NAME);
            session.put("scenario", scen != null ? scen : "Unknown");
            session.put("turn_count", turns.size());
            session.put("timestamp", System.currentTimeMillis());
            session.put("started_at", turns.isEmpty() ? System.currentTimeMillis() : turns.get(0).capturedAt);
            session.put("is_final", isFinal);
            session.put("valid", true);
            session.put("mapping_version", "v2.4_102power_105stamina");

            JSONArray eventObsArr = new JSONArray();
            for (String raw : eventObservations) {
                try { eventObsArr.put(new JSONObject(raw)); } catch (JSONException ignored) {}
            }
            session.put("event_observations", eventObsArr);

            // 回合数据
            JSONArray turnsArr = new JSONArray();
            for (TurnSnapshot t : turns) {
                turnsArr.put(t.toJson());
            }
            // 非final时，把当前进行中的回合也加进去
            if (!isFinal && prevSnap != null) {
                boolean inList = false;
                for (int i = 0; i < turns.size(); i++) {
                    if (turns.get(i) == prevSnap) { inList = true; break; }
                }
                if (!inList) {
                    turnsArr.put(prevSnap.toJson());
                }
            }
            session.put("turns", turnsArr);
            int distinctTurnCount = 0;
            int lastTurnNumber = Integer.MIN_VALUE;
            for (int i = 0; i < turnsArr.length(); i++) {
                int turnNumber = turnsArr.getJSONObject(i).optInt("turn", 0);
                if (turnNumber != lastTurnNumber) {
                    distinctTurnCount++;
                    lastTurnNumber = turnNumber;
                }
            }
            session.put("turn_count", distinctTurnCount);
            session.put("observation_count", turnsArr.length());
            session.put("record_kind", "observations_with_same_turn_changes");

            // 最终属性
            TurnSnapshot last = prevSnap;
            if (last == null && !turns.isEmpty()) last = turns.get(turns.size() - 1);
            if (last != null) {
                JSONObject finalStats = new JSONObject();
                finalStats.put("speed", last.speed);
                finalStats.put("stamina", last.stamina);
                finalStats.put("power", last.power);
                finalStats.put("guts", last.guts);
                finalStats.put("wisdom", last.wisdom);
                finalStats.put("total", last.speed + last.stamina + last.power + last.guts + last.wisdom);
                finalStats.put("skill_pt", last.skillPt);
                session.put("final_stats", finalStats);
            }

            // ★ v2.3: 数据质量验证器
            JSONObject validation = validateSession();
            session.put("validation", validation);

            return session;
        } catch (JSONException e) {
            Log.e(TAG, "buildSessionJson error: " + e.getMessage());
            return null;
        }
    }

    /**
     * ★ v2.3: 数据质量验证器 — 上传前校验 session 数据完整性
     */
    private JSONObject validateSession() {
        JSONObject v = new JSONObject();
        try {
            boolean valid = true;
            JSONArray errors = new JSONArray();
            JSONArray warnings = new JSONArray();

            // 1. turns 单调递增
            int prevTurn = 0;
            int gapCount = 0;
            int unknownActionCount = 0;
            int zeroGainCount = 0;
            int totalTurns = turns.size();
            for (int i = 0; i < turns.size(); i++) {
                TurnSnapshot t = turns.get(i);
                // schema v4 允许同一回合保存多个客观 observation；只禁止回退。
                if (t.turn < prevTurn) {
                    errors.put("Turn regressed at index " + i + ": " + t.turn + " < " + prevTurn);
                    valid = false;
                }
                if (t.turn > prevTurn + 1 && prevTurn > 0) {
                    gapCount++;
                }
                if (t.turn > prevTurn) prevTurn = t.turn;
                if ("Unknown".equals(t.actionTaken)) unknownActionCount++;
                if (t.trainings != null) {
                    boolean allZero = true;
                    for (TrainingOption opt : t.trainings) {
                        if (opt.gainSpeed + opt.gainStamina + opt.gainPower
                                + opt.gainGuts + opt.gainWisdom > 0) {
                            allZero = false;
                            break;
                        }
                    }
                    if (allZero) zeroGainCount++;
                }
            }

            // 2. 文件名 == session_id (由调用方保证)

            // 3. 无全0伪快照
            for (int i = 0; i < turns.size(); i++) {
                TurnSnapshot t = turns.get(i);
                if (t.speed == 0 && t.stamina == 0 && t.power == 0
                        && t.guts == 0 && t.wisdom == 0 && t.vital == 0) {
                    errors.put("All-zero snapshot at turn " + t.turn);
                    valid = false;
                }
            }

            // 4. action_source 存在
            for (int i = 0; i < turns.size(); i++) {
                TurnSnapshot t = turns.get(i);
                if (t.actionSource == null || t.actionSource.isEmpty()) {
                    warnings.put("Missing action_source at turn " + t.turn);
                }
            }

            v.put("valid", valid);
            v.put("errors", errors);
            v.put("warnings", warnings);
            v.put("gap_count", gapCount);
            v.put("observation_count", totalTurns);
            int distinctTurns = 0;
            int lastDistinctTurn = Integer.MIN_VALUE;
            for (TurnSnapshot t : turns) {
                if (t.turn != lastDistinctTurn) {
                    distinctTurns++;
                    lastDistinctTurn = t.turn;
                }
            }
            v.put("distinct_turn_count", distinctTurns);
            v.put("unknown_action_ratio", totalTurns > 0 ? (double) unknownActionCount / totalTurns : 0.0);
            v.put("zero_gain_ratio", totalTurns > 0 ? (double) zeroGainCount / totalTurns : 0.0);
        } catch (JSONException e) {
            Log.e(TAG, "validateSession error: " + e.getMessage());
        }
        return v;
    }

    /**
     * ★ v2.4: 返回 true=上传成功。失败由调用方（worker）退避重试；
     * 本方法绝不修改当前 session 状态（不再调用 startNewSession）。
     */
    private boolean uploadToGitHub(String jsonContent, String sessId,
            String scen, int turnCount, boolean isFinal) {
        try {
            String scenarioDir = (scen != null && !scen.isEmpty()) ? scen : "Unknown";
            // ★ v2.0: 文件名使用冻结的 session_id，与内部一致
            String filename = sessId + ".json";
            String path = GITHUB_PATH + "/" + scenarioDir + "/" + filename;
            String apiUrl = "https://api.github.com/repos/" + GITHUB_REPO + "/contents/" + path;

            // ★ v2.4: SHA 缓存按远端路径 keyed — 换一个文件就必须重新 GET，
            // 否则拿别的文件的 SHA 会稳定 409（isFinal 也强制 GET 复核）
            if (!apiUrl.equals(lastUploadedPath)) {
                lastUploadedSha = null;
            }
            String sha = lastUploadedSha;
            if (sha == null || isFinal) {
                try {
                    URL getUrl = new URL(apiUrl);
                    HttpURLConnection getConn = (HttpURLConnection) getUrl.openConnection();
                    getConn.setRequestMethod("GET");
                    getConn.setRequestProperty("Authorization", "token " + GITHUB_TOKEN);
                    getConn.setRequestProperty("Accept", "application/vnd.github.v3+json");
                    getConn.setConnectTimeout(10000);
                    getConn.setReadTimeout(10000);
                    if (getConn.getResponseCode() == 200) {
                        BufferedReader reader = new BufferedReader(
                            new InputStreamReader(getConn.getInputStream(), "UTF-8"));
                        StringBuilder sb = new StringBuilder();
                        String line;
                        while ((line = reader.readLine()) != null) sb.append(line);
                        reader.close();
                        JSONObject existing = new JSONObject(sb.toString());
                        sha = existing.optString("sha", null);
                        lastUploadedSha = sha;
                    }
                    getConn.disconnect();
                } catch (Exception e) {
                    // 文件不存在
                }
            }

            String encoded = Base64.encodeToString(
                jsonContent.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);

            JSONObject body = new JSONObject();
            body.put("message", (isFinal ? "Finalize" : "Update") + " training session " + sessId
                + " (" + turnCount + " turns, " + scenarioDir + ")");
            body.put("content", encoded);
            body.put("branch", GITHUB_BRANCH);
            if (sha != null) {
                body.put("sha", sha);
            }

            URL url = new URL(apiUrl);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("PUT");
            conn.setRequestProperty("Authorization", "token " + GITHUB_TOKEN);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Accept", "application/vnd.github.v3+json");
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(30000);
            conn.setDoOutput(true);

            OutputStream os = conn.getOutputStream();
            os.write(body.toString().getBytes(StandardCharsets.UTF_8));
            os.flush();
            os.close();

            int code = conn.getResponseCode();
            if (code == 200 || code == 201) {
                // ★ v2.0: 从响应中提取新 SHA 缓存
                try {
                    BufferedReader reader = new BufferedReader(
                        new InputStreamReader(conn.getInputStream(), "UTF-8"));
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) sb.append(line);
                    reader.close();
                    JSONObject resp = new JSONObject(sb.toString());
                    JSONObject content = resp.optJSONObject("content");
                    if (content != null) {
                        lastUploadedSha = content.optString("sha", lastUploadedSha);
                    }
                    lastUploadedPath = apiUrl;
                } catch (Exception ignored) {}

                Log.d(TAG, "Upload success: " + filename + " (isFinal=" + isFinal + ")");
                conn.disconnect();
                return true;
            } else {
                BufferedReader reader = new BufferedReader(
                    new InputStreamReader(conn.getErrorStream(), "UTF-8"));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) sb.append(line);
                reader.close();
                Log.e(TAG, "Upload failed (" + code + "): " + sb.toString());
                // 409 Conflict：SHA 失配，清缓存让下次重试重新 GET
                if (code == 409) {
                    lastUploadedSha = null;
                    Log.w(TAG, "409 conflict, cleared SHA cache");
                }
            }
            conn.disconnect();

        } catch (Exception e) {
            Log.e(TAG, "Upload error: " + e.getMessage());
        }
        return false;
    }

    // ========================================================================
    // 数据类
    // ========================================================================

    /** 回合快照 */
    private static class TurnSnapshot {
        int month, half, turn;
        String scenario;
        int charaId;
        int speed, stamina, power, guts, wisdom, skillPt;
        int vital, maxVital;
        int motivation;
        int fan;
        boolean hasBadCondition;
        String charaEffectIds;
        String buffsRaw;
        String trainingLevelsRaw;
        String evaluationRaw;
        String gaugeGainsRaw;
        String supportCardsRaw;
        String skillsRaw;
        String ramenRaw;
        String sameTurnTransitionRaw;
        String observationKind = "turn_snapshot";
        TrainingOption[] trainings;
        String aiBest;
        int aiScore;
        int skillEval;
        int skillCount;
        String rngStateRaw;
        String rngTransitionRaw;
        long rngSequence;

        String actionTaken = "Unknown";
        String actionSource = "unknown";
        double actionConfidence = 0.0;
        int actionCommandId = -1;
        int actionRawCommandId = -1;
        int storyId = 0;
        long capturedAt = System.currentTimeMillis();

        JSONObject toJson() throws JSONException {
            JSONObject o = new JSONObject();
            o.put("turn", turn);
            o.put("month", month);
            o.put("half", half);
            if (scenario != null && !scenario.isEmpty()) o.put("scenario", scenario);
            if (charaId > 0) o.put("chara_id", charaId);
            if (storyId > 0) o.put("story_id", storyId);

            JSONObject stats = new JSONObject();
            stats.put("speed", speed);
            stats.put("stamina", stamina);
            stats.put("power", power);
            stats.put("guts", guts);
            stats.put("wisdom", wisdom);
            stats.put("skill_pt", skillPt);
            stats.put("vital", vital);
            stats.put("max_vital", maxVital);
            stats.put("motivation", motivation);
            stats.put("fan", fan);
            stats.put("has_bad_condition", hasBadCondition);
            o.put("stats", stats);
            o.put("rng_observation_valid", false);
            o.put("rng_invalid_reason", "offset_0x198_is_ObscuredInt_not_u32x4");

            if (trainings != null) {
                JSONArray trArr = new JSONArray();
                for (TrainingOption opt : trainings) {
                    JSONObject tr = new JSONObject();
                    tr.put("name", opt.name);
                    tr.put("command_id", opt.commandId);
                    tr.put("is_enable", opt.isEnabled);
                    JSONObject g = new JSONObject();
                    g.put("Speed", opt.gainSpeed);
                    g.put("Stamina", opt.gainStamina);
                    g.put("Power", opt.gainPower);
                    g.put("Guts", opt.gainGuts);
                    g.put("Wisdom", opt.gainWisdom);
                    g.put("SkillPt", opt.gainSkillPt);
                    g.put("HP", -opt.vitalCost);
                    tr.put("gains", g);
                    tr.put("failure_rate", opt.failureRate);
                    tr.put("shining", opt.shining);
                    tr.put("heads", opt.heads);
                    if (opt.partnerIdsRaw != null)
                        tr.put("partner_ids", new JSONArray(opt.partnerIdsRaw));
                    if (opt.partnersRaw != null)
                        tr.put("partners", new JSONArray(opt.partnersRaw));
                    trArr.put(tr);
                }
                o.put("trainings", trArr);
            }

            if (charaId > 0) o.put("chara_id", charaId);
            if (storyId > 0) o.put("story_id", storyId);

            o.put("observation_kind", observationKind);
            if (sameTurnTransitionRaw != null && !sameTurnTransitionRaw.isEmpty())
                o.put("observed_transition", new JSONObject(sameTurnTransitionRaw));

            // ★ v2.3: 动作来源元数据
            o.put("action_taken", actionTaken);
            o.put("action_source", actionSource);
            o.put("action_confidence", actionConfidence);
            o.put("captured_at", capturedAt);
            if (actionCommandId >= 0) o.put("action_command_id", actionCommandId);
            if (actionRawCommandId >= 0) o.put("action_raw_command_id", actionRawCommandId);

            if (aiBest != null && !aiBest.isEmpty()) {
                JSONObject ai = new JSONObject();
                ai.put("best", aiBest);
                ai.put("score", aiScore);
                ai.put("skill_eval", skillEval);
                ai.put("skill_count", skillCount);
                o.put("ai_recommend", ai);
            }

            if (gaugeGainsRaw != null && !gaugeGainsRaw.isEmpty()) {
                o.put("gauge_gains", new JSONArray(gaugeGainsRaw));
            }
            if (supportCardsRaw != null && !supportCardsRaw.isEmpty()) {
                o.put("support_cards", new JSONArray(supportCardsRaw));
            }
            if (skillsRaw != null && !skillsRaw.isEmpty()) {
                o.put("skills", new JSONObject(skillsRaw));
            }
            if (ramenRaw != null && !ramenRaw.isEmpty()) {
                o.put("ramen", new JSONObject(ramenRaw));
            }
            if (trainingLevelsRaw != null && !trainingLevelsRaw.isEmpty()) {
                o.put("training_levels", new JSONArray(trainingLevelsRaw));
            }
            if (buffsRaw != null && !buffsRaw.isEmpty()) {
                o.put("buffs", new JSONArray(buffsRaw));
            }
            if (charaEffectIds != null && !charaEffectIds.isEmpty()) {
                o.put("chara_effect_ids", new JSONArray(charaEffectIds));
            }
            if (evaluationRaw != null && !evaluationRaw.isEmpty()) {
                o.put("evaluation", new JSONArray(evaluationRaw));
            }

            return o;
        }

        /** ★ v2.4: 全字段恢复 — 重启后不再用残缺数据覆盖完整上传 */
        static TurnSnapshot fromJson(JSONObject o) throws JSONException {
            TurnSnapshot s = new TurnSnapshot();
            s.turn = o.optInt("turn", 0);
            s.month = o.optInt("month", 0);
            s.half = o.optInt("half", 0);
            s.scenario = o.optString("scenario", "");
            s.charaId = o.optInt("chara_id", 0);
            s.storyId = o.optInt("story_id", 0);
            s.observationKind = o.optString("observation_kind", "turn_snapshot");
            JSONObject observedTransition = o.optJSONObject("observed_transition");
            if (observedTransition != null) s.sameTurnTransitionRaw = observedTransition.toString();
            s.actionTaken = o.optString("action_taken", "Unknown");

            JSONObject stats = o.optJSONObject("stats");
            if (stats != null) {
                s.speed = stats.optInt("speed", 0);
                s.stamina = stats.optInt("stamina", 0);
                s.power = stats.optInt("power", 0);
                s.guts = stats.optInt("guts", 0);
                s.wisdom = stats.optInt("wisdom", 0);
                s.skillPt = stats.optInt("skill_pt", 0);
                s.vital = stats.optInt("vital", 0);
                s.maxVital = stats.optInt("max_vital", 0);
                s.motivation = stats.optInt("motivation", 3);
                s.fan = stats.optInt("fan", 0);
                s.hasBadCondition = stats.optBoolean("has_bad_condition", false);
            }

            JSONObject rngState = o.optJSONObject("rng_state");
            if (rngState != null) s.rngStateRaw = rngState.toString();
            JSONObject rngTransition = o.optJSONObject("rng_transition");
            if (rngTransition != null) {
                s.rngTransitionRaw = rngTransition.toString();
                s.rngSequence = rngTransition.optLong("sequence", 0);
            }

            // 动作元数据
            s.actionSource = o.optString("action_source", "unknown");
            s.actionConfidence = o.optDouble("action_confidence", 0.0);
            s.actionCommandId = o.optInt("action_command_id", -1);
            s.actionRawCommandId = o.optInt("action_raw_command_id", -1);
            s.capturedAt = o.optLong("captured_at", System.currentTimeMillis());

            // 训练选项（shining/heads 的 -1 未知语义原样保留）
            JSONArray trArr = o.optJSONArray("trainings");
            if (trArr != null) {
                s.trainings = new TrainingOption[trArr.length()];
                for (int i = 0; i < trArr.length(); i++) {
                    JSONObject tr = trArr.optJSONObject(i);
                    TrainingOption opt = new TrainingOption();
                    if (tr == null) { s.trainings[i] = opt; continue; }
                    opt.name = tr.optString("name", "");
                    opt.commandId = tr.optInt("command_id", 0);
                    Object ie = tr.opt("is_enable");
                    opt.isEnabled = (ie instanceof Boolean) ? (Boolean) ie
                        : tr.optInt("is_enable", 1) != 0;
                    JSONObject g = tr.optJSONObject("gains");
                    if (g != null) {
                        opt.gainSpeed = g.optInt("Speed", 0);
                        opt.gainStamina = g.optInt("Stamina", 0);
                        opt.gainPower = g.optInt("Power", 0);
                        opt.gainGuts = g.optInt("Guts", 0);
                        opt.gainWisdom = g.optInt("Wisdom", 0);
                        opt.gainSkillPt = g.optInt("SkillPt", 0);
                        opt.vitalCost = -g.optInt("HP", 0);
                    }
                    opt.failureRate = tr.optInt("failure_rate", 0);
                    opt.shining = tr.optInt("shining", -1);
                    opt.heads = tr.optInt("heads", -1);
                    JSONArray partnerIds = tr.optJSONArray("partner_ids");
                    if (partnerIds != null) opt.partnerIdsRaw = partnerIds.toString();
                    JSONArray partners = tr.optJSONArray("partners");
                    if (partners != null) opt.partnersRaw = partners.toString();
                    s.trainings[i] = opt;
                }
            }

            // AI 推荐
            JSONObject ai = o.optJSONObject("ai_recommend");
            if (ai != null) {
                s.aiBest = ai.optString("best", null);
                s.aiScore = ai.optInt("score", 0);
                s.skillEval = ai.optInt("skill_eval", 0);
                s.skillCount = ai.optInt("skill_count", 0);
            }

            // 原始 JSON 透传字段（转回字符串保存）
            JSONArray gauge = o.optJSONArray("gauge_gains");
            if (gauge != null) s.gaugeGainsRaw = gauge.toString();
            JSONArray sc = o.optJSONArray("support_cards");
            if (sc != null) s.supportCardsRaw = sc.toString();
            JSONObject skills = o.optJSONObject("skills");
            if (skills != null) s.skillsRaw = skills.toString();
            JSONObject ramen = o.optJSONObject("ramen");
            if (ramen != null) s.ramenRaw = ramen.toString();
            JSONArray tl = o.optJSONArray("training_levels");
            if (tl != null) s.trainingLevelsRaw = tl.toString();
            JSONArray buffs = o.optJSONArray("buffs");
            if (buffs != null) s.buffsRaw = buffs.toString();
            JSONArray effectIds = o.optJSONArray("chara_effect_ids");
            if (effectIds != null) s.charaEffectIds = effectIds.toString();
            JSONArray eval = o.optJSONArray("evaluation");
            if (eval != null) s.evaluationRaw = eval.toString();

            return s;
        }
    }

    /** 训练选项 */
    private static class TrainingOption {
        String name;
        int commandId;
        boolean isEnabled;
        int gainSpeed, gainStamina, gainPower, gainGuts, gainWisdom;
        int gainSkillPt;
        int vitalCost;
        int failureRate;
        int shining;
        int heads;
        String partnerIdsRaw;
        String partnersRaw;
    }
}
