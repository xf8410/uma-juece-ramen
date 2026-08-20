package com.umaai.assistant.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.Toast;
import android.widget.TextView;
import java.io.File;

import androidx.core.app.NotificationCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.umaai.assistant.MainActivity;
import com.umaai.assistant.R;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Iterator;

/**
 * 小黑板风格浮窗服务 v1.24
 * 黑底+彩色文字，显示插件推送的 /summary 数据
 * 支持剧本切换（Spinner选择+广播通知）
 * 支持剧本buff显示（青/绿/桃 / 新剧本适配）
 *
 * 数据来源：插件 v3.10.0+ 主动 POST /summary JSON 到 18766
 */
public class FloatingWindowService extends Service implements HttpDataService.OnDataListener {

    private static final String TAG = "UmaFloat";
    private static final String CHANNEL_ID = "floating_service_channel";
    private static final int NOTIFICATION_ID = 1001;
    public static final String ACTION_DATA = "com.umaai.assistant.ACTION_DATA";
    public static final String EXTRA_DATA = "data";
    public static final String ACTION_SCENARIO = "com.umaai.assistant.ACTION_SCENARIO";
    public static final String ACTION_UPLOAD_DATA = "com.umaai.assistant.ACTION_UPLOAD_DATA";

    // 五维颜色
    private static final int COLOR_SPD = 0xFF4488FF;
    private static final int COLOR_SPD_DIM = 0xFF6688CC;
    private static final int COLOR_STA = 0xFFFF4444;
    private static final int COLOR_STA_DIM = 0xFFCC6666;
    private static final int COLOR_PWR = 0xFFFF8800;
    private static final int COLOR_PWR_DIM = 0xFFCC8844;
    private static final int COLOR_GUT = 0xFFFF66AA;
    private static final int COLOR_GUT_DIM = 0xFFCC6688;
    private static final int COLOR_WIT = 0xFFFFDD00;
    private static final int COLOR_WIT_DIM = 0xFFCCBB44;
    private static final int COLOR_DEFAULT = 0xFF00FF88;

    // 状态颜色
    private static final int COLOR_STATE_SICK = 0xFFFF4444;
    private static final int COLOR_STATE_WARN = 0xFFFFAA00;

    // Buff颜色
    private static final int COLOR_BUFF_AO = 0xFF66AAFF;    // 青
    private static final int COLOR_BUFF_MIDORI = 0xFF66FF88; // 緑
    private static final int COLOR_BUFF_MOMO = 0xFFFF88AA;   // 桃

    private WindowManager windowManager;
    private View floatingView;
    private WindowManager.LayoutParams params;
    private boolean isViewAdded = false;
    private Handler handler = new Handler(Looper.getMainLooper());

    // 顶部
    private TextView tvTurn, tvTotal, tvStamina, tvMotivation;
    // 推荐
    private TextView tvRecommend;
    // 五维
    private TextView tvSpdVal, tvSpdGain, tvStaVal, tvStaGain;
    private TextView tvPwrVal, tvPwrGain, tvGutVal, tvGutGain;
    private TextView tvWitVal, tvWitGain;
    // 训练等级
    private TextView tvSpdLv, tvStaLv, tvPwrLv, tvGutLv, tvWitLv;
    // 状态指示
    private TextView tvState;
    // AI评估详情
    private TextView tvAiDetail;
    // Buff
    private LinearLayout buffContainer;
    private TextView tvBuffAo, tvBuffMidori, tvBuffMomo;
    private TextView tvBuffDetail;
    private View buffSeparator;
    // 队员（育马者杯）
    private View teamContainer;
    private TextView tvTeamLabel, tvTeamMembers, tvTeamRank, tvDreamTraining;
    // 底部
    private TextView tvFacility, tvHookStatus;
    // 剧本标签
    private TextView tvScenarioLabel;
    // 比赛/目标状态栏
    private TextView tvRaceStatus;
    // 拉面杯 Gauge 状态栏
    private TextView tvRamenGauge;
    private TextView tvRamenPlan;
    // turn_config 缓存（从 /log/turn 拉取一次）
    private JSONArray turnConfigCache = null;
    private long turnConfigCacheTime = 0;
    // 生涯目标缓存（从 MDB single_mode_route_race 查）
    private JSONArray routeRaceCache = null;
    private long routeRaceCacheTime = 0;
    // 胜鞍面板
    private View saddlePanel;
    private TextView tvSaddleContent;
    private boolean saddlePanelVisible = false;
    // 抓包面板
    private View sniffPanel;
    private TextView tvSniffContent;
    private boolean sniffPanelVisible = false;
    private boolean sniffEnabled = false;
    private Thread sniffThread;
    private volatile boolean sniffRunning = false;
    // 事件推荐面板
    private View evtPanel;
    private TextView tvEvtContent;
    private boolean evtPanelVisible = false;
    private Thread evtThread;
    private volatile boolean evtRunning = false;
    private int lastEvtChoiceCount = 0;
    private long lastEvtObservationId = 0;
    private int lastEvtStoryId = 0;
    // 支援卡ID→名称缓存
    private java.util.Map<Integer, String> supportCardNameCache = null;
    private java.util.Map<Integer, Integer> supportCardCharaCache = null;
    private java.util.Map<Integer, String> supportCardTypeCache = null;
    private boolean supportNameCachesComplete = false;
    private java.util.Map<Integer, String> npcNameCache = null;
    private java.util.Map<Integer, Integer> ramenPartnerCharaCache = null;
    // Scenario 14 MDB catalogs downloaded by RemoteDataLoader.
    private java.util.Map<Integer, String> ramenRegionNameCache = null;
    private java.util.Map<Integer, JSONObject> ramenEffectRecordCache = null;

    // HTTP服务
    private HttpDataService httpServer;

    // 拖拽
    private int initialX, initialY;
    private float initialTouchX, initialTouchY;
    private boolean isDragging = false;

    // 数据更新时间
    private long lastDataTime = 0;

    // 当前剧本（来自插件推送）
    private String currentScenario = "";
    // 用户选择的剧本（来自Spinner）
    private String selectedScenario = "URA";

    private BroadcastReceiver dataReceiver;
    private BroadcastReceiver scenarioReceiver;

    // 训练评分引擎
    private TrainingEvaluator evaluator = new TrainingEvaluator();
    private DataCollector dataCollector;
    private EndpointDumper endpointDumper;

    // 兜底轮询：5秒没收到push就主动去18765拉数据
    private static final long POLL_THRESHOLD_MS = 5000;
    private static final long POLL_INTERVAL_MS = 2000;
    private volatile boolean pollRunning = false;
    private Thread pollThread;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "onCreate");
        createNotificationChannel();
        startForeground(NOTIFICATION_ID, createNotification());

        // 读取用户选择的剧本
        SharedPreferences prefs = getSharedPreferences(MainActivity.PREFS_NAME, MODE_PRIVATE);
        selectedScenario = prefs.getString(MainActivity.KEY_SCENARIO, "URA");
        dataCollector = new DataCollector(this);
        endpointDumper = new EndpointDumper(this);

        handler.postDelayed(this::createFloatingView, 300);
        registerDataReceiver();
        registerScenarioReceiver();
        startHttpServer();
        startFallbackPoll();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && intent.hasExtra(EXTRA_DATA)) {
            handleData(intent.getStringExtra(EXTRA_DATA));
        }
        if (intent != null && ACTION_UPLOAD_DATA.equals(intent.getAction())) {
            finalizeCurrentSessionFromUser();
        }
        // 从MainActivity传入剧本选择
        if (intent != null && intent.hasExtra("scenario")) {
            selectedScenario = intent.getStringExtra("scenario");
            Log.d(TAG, "Scenario from intent: " + selectedScenario);
            updateScenarioLabel();
        }
        return START_STICKY;
    }

    // ======== HttpDataService.OnDataListener ========
    @Override
    public void onDataReceived(String data) {
        Log.d(TAG, "HTTP data received: " + data);
        handleData(data);
    }

    // ======== 数据处理核心 ========
    private void handleData(String data) {
        if (data == null || data.isEmpty()) return;

        try {
            JSONObject json = new JSONObject(data);

            // ★ /summary format from plugin push (v3.10.0+)
            if (json.has("version") && json.has("stats") && json.has("trainings")) {
                updateFromSummary(json, data);
                return;
            }

            // ★ v1.24: /choices event data from plugin (v3.24.1+)
            if (json.has("choices") && (json.has("story_id") || json.has("chara_id"))) {
                if (dataCollector != null) {
                    dataCollector.onEventData(json);
                }
                return;
            }

            // Legacy: just show as text
            final String text = data;
            handler.post(() -> {
                if (tvRecommend != null) {
                    tvRecommend.setTextColor(COLOR_DEFAULT);
                    tvRecommend.setText(text);
                }
            });
        } catch (JSONException e) {
            handler.post(() -> {
                if (tvRecommend != null) {
                    tvRecommend.setTextColor(COLOR_DEFAULT);
                    tvRecommend.setText(data);
                }
            });
        }
    }

    /**
     * 解析插件 /summary 格式JSON并更新浮窗
     */
    private void updateFromSummary(JSONObject json, String rawJson) {
        handler.post(() -> {
            try {
                JSONObject stats = json.getJSONObject("stats");
                JSONArray trainings = json.getJSONArray("trainings");
                JSONArray buffs = json.optJSONArray("buffs");
                // ★ v3.18.6: Ramen active_effects fallback — if buffs has no Ramen data,
                // convert ramen.active_effects into buffs format
                if (buffs == null || buffs.length() == 0) {
                    JSONObject ramenObj = json.optJSONObject("ramen");
                    if (ramenObj != null) {
                        JSONArray aeArr = ramenObj.optJSONArray("active_effects");
                        if (aeArr != null && aeArr.length() > 0) {
                            buffs = new JSONArray();
                            for (int aei = 0; aei < aeArr.length(); aei++) {
                                JSONObject ae = aeArr.optJSONObject(aei);
                                if (ae == null) continue;
                                int cat = ae.optInt("category", -1);
                                int eid = ae.optInt("id", 0);
                                int val = ae.optInt("value", 0);
                                String catName;
                                switch (cat) {
                                    case 1: catName = "地区效果"; break;
                                    case 2: catName = "吃面效果"; break;
                                    case 4: catName = "特殊效果"; break;
                                    default: catName = "Cat" + cat; break;
                                }
                                JSONObject buffItem = new JSONObject();
                                buffItem.put("name", catName + "#" + eid);
                                buffItem.put("EffectCategory", cat);
                                buffItem.put("EffectId", eid);
                                buffItem.put("EffectValue", val);
                                buffItem.put("desc", "");
                                buffItem.put("type", "Ramen");
                                buffs.put(buffItem);
                            }
                            // Also add UrafEffect from ramen if available
                            // (future: ramen.uraf_type/uraf_state fields)
                        }
                    }
                } else {
                    // Check if buffs has any Ramen type entry
                    boolean hasRamenInBuffs = false;
                    for (int bi = 0; bi < buffs.length(); bi++) {
                        if ("Ramen".equals(buffs.optJSONObject(bi).optString("type",""))) {
                            hasRamenInBuffs = true; break;
                        }
                    }
                    if (!hasRamenInBuffs) {
                        JSONObject ramenObj = json.optJSONObject("ramen");
                        if (ramenObj != null) {
                            JSONArray aeArr = ramenObj.optJSONArray("active_effects");
                            if (aeArr != null && aeArr.length() > 0) {
                                // Prepend Ramen buffs before existing buffs
                                JSONArray newBuffs = new JSONArray();
                                for (int aei = 0; aei < aeArr.length(); aei++) {
                                    JSONObject ae = aeArr.optJSONObject(aei);
                                    if (ae == null) continue;
                                    int cat = ae.optInt("category", -1);
                                    String catName;
                                    switch (cat) {
                                        case 1: catName = "地区效果"; break;
                                        case 2: catName = "吃面效果"; break;
                                        case 4: catName = "特殊效果"; break;
                                        default: catName = "Cat" + cat; break;
                                    }
                                    JSONObject buffItem = new JSONObject();
                                    int aeId = ae.optInt("id", 0);
                                    int aeVal = ae.optInt("value", 0);
                                    buffItem.put("name", catName + "#" + aeId);
                                    buffItem.put("EffectCategory", cat);
                                    buffItem.put("EffectId", aeId);
                                    buffItem.put("EffectValue", aeVal);
                                    buffItem.put("desc", "");
                                    buffItem.put("type", "Ramen");
                                    newBuffs.put(buffItem);
                                }
                                for (int bi = 0; bi < buffs.length(); bi++) {
                                    newBuffs.put(buffs.optJSONObject(bi));
                                }
                                buffs = newBuffs;
                            }
                        }
                    }
                }
                lastDataTime = System.currentTimeMillis();

                // 整局训练数据只走DataCollector的持久上传队列，避免重复逐回合上传。
                if (dataCollector != null) {
                    String action = dataCollector.onSummaryData(json);
                    int turnCount = dataCollector.getTurnCount();
                    if (tvHookStatus != null) {
                        tvHookStatus.setText("自动上传：开　已记录：" + turnCount + "回合");
                        tvHookStatus.setTextColor(0xFF00FF88);
                    }
                }

                // 记录当前剧本（来自插件推送）
                currentScenario = json.optString("scenario", "");

                // 回合信息
                int month = json.optInt("month", -1);
                int half = json.optInt("half", -1);
                if (month > 0) {
                    tvTurn.setText(month + "月" + (half == 1 ? "前" : "後"));
                } else {
                    tvTurn.setText(currentScenario.isEmpty() ? "---" : currentScenario);
                }

                // ★ 更新剧本标签
                updateScenarioLabel();

                // 総合 + Pt
                int total = stats.getInt("speed") + stats.getInt("stamina")
                        + stats.getInt("power") + stats.getInt("guts") + stats.getInt("wiz");
                int pt = stats.getInt("skill_point");
                tvTotal.setText("总" + total + " 技能点" + pt);

                // 体力
                int vital = stats.getInt("vital");
                int maxVital = stats.getInt("max_vital");
                tvStamina.setText("体" + vital + "/" + maxVital);
                if (vital <= 30) {
                    tvStamina.setTextColor(0xFFFF4444);
                } else if (vital <= 50) {
                    tvStamina.setTextColor(0xFFFFAA00);
                } else {
                    tvStamina.setTextColor(0xFF66CCFF);
                }

                // 干劲
                String mot = stats.getString("motivation");
                // ★ 状态显示（生病等）— 用 chara_effect_ids 判断
                org.json.JSONArray effectIds = json.optJSONArray("chara_effect_ids");
                boolean hasBadCondition = false;
                if (effectIds != null && effectIds.length() > 0) {
                    for (int ei = 0; ei < effectIds.length(); ei++) {
                        int eid = effectIds.optInt(ei, 0);
                        if (eid >= 1 && eid <= 6) { hasBadCondition = true; break; }
                    }
                }
                String stateStr = hasBadCondition ? " ⚠病" : "";
                tvMotivation.setText(mot + stateStr);
                if (hasBadCondition) {
                    tvMotivation.setTextColor(COLOR_STATE_SICK);
                } else if (mot.contains("Best") || mot.contains("Good")) {
                    tvMotivation.setTextColor(0xFFFFCC00);
                } else if (mot.contains("Normal")) {
                    tvMotivation.setTextColor(0xFFAAAAAA);
                } else {
                    tvMotivation.setTextColor(0xFFFF4444);
                }

                // 五维属性 + 该属性的训练增益
                updateStatFromSummary(tvSpdVal, tvSpdGain, stats, trainings, json,
                        "speed", "Speed", COLOR_SPD, COLOR_SPD_DIM);
                updateStatFromSummary(tvStaVal, tvStaGain, stats, trainings, json,
                        "stamina", "Stamina", COLOR_STA, COLOR_STA_DIM);
                updateStatFromSummary(tvPwrVal, tvPwrGain, stats, trainings, json,
                        "power", "Power", COLOR_PWR, COLOR_PWR_DIM);
                updateStatFromSummary(tvGutVal, tvGutGain, stats, trainings, json,
                        "guts", "Guts", COLOR_GUT, COLOR_GUT_DIM);
                updateStatFromSummary(tvWitVal, tvWitGain, stats, trainings, json,
                        "wiz", "Wiz", COLOR_WIT, COLOR_WIT_DIM);

                // ★ 训练等级显示
                JSONArray trainLevels = json.optJSONArray("training_levels");
                updateTrainingLevels(trainLevels);

                // ★ v1.24: 比赛回合检测 — 默认不是比赛，减轻误判
                // 只有当完全没有训练数据或month<=0时才判定为比赛中/加载中
                boolean isRaceTurn = false;
                if (month <= 0) {
                    // 加载/过渡画面
                    isRaceTurn = true;
                } else if (trainings == null || trainings.length() == 0) {
                    // month>0但无训练数据 → 可能是比赛回合
                    isRaceTurn = true;
                } else {
                    // 有训练数据 → 检查是否全为Unknown（数据未就绪）
                    boolean anyValid = false;
                    for (int ti = 0; ti < trainings.length(); ti++) {
                        JSONObject tr = trainings.optJSONObject(ti);
                        if (tr == null) continue;
                        String tName = tr.optString("name", "");
                        if (!"Unknown".equals(tName) && !tName.isEmpty()) {
                            anyValid = true;
                            break;
                        }
                    }
                    isRaceTurn = !anyValid;
                }

                if (isRaceTurn) {
                    tvRecommend.setText("▶ 比賽中");
                    tvRecommend.setTextColor(0xFF4FC3F7);
                    if (tvAiDetail != null) tvAiDetail.setVisibility(View.GONE);
                } else {
                // 推荐训练 — 优先使用插件AI评估
                JSONObject aiObj = json.optJSONObject("ai");
                if (aiObj != null) {
                    updateAiRecommendation(aiObj);
                } else {
                    // 回退到App端评分引擎（传入剧本信息）
                    if (tvAiDetail != null) tvAiDetail.setVisibility(View.GONE);
                    evaluator.setScenario(selectedScenario);
                    TrainingEvaluator.EvalResult evalResult = evaluator.evaluate(json);
                    if ("rest".equals(evalResult.bestType)) {
                        tvRecommend.setText("▶ 休息 (" + evalResult.bestDetail + ")");
                        tvRecommend.setTextColor(0xFFFF4444);
                    } else if (!"unknown".equals(evalResult.bestType) && !"error".equals(evalResult.bestType)) {
                        tvRecommend.setText("▶ " + evalResult.bestDetail);
                        tvRecommend.setTextColor(evalResult.bestColor);
                    } else {
                        tvRecommend.setText("▶ " + evalResult.bestDetail);
                        tvRecommend.setTextColor(COLOR_DEFAULT);
                    }
                }
                }

                // ★ 每个剧本的 Buff 数据结构不同，必须按实际剧本分流。
                updateBuffs(buffs, currentScenario);

                // ★ 比赛回合检测（改进版）+ 生涯目标粉丝数
                int fan = stats.optInt("fan", -1);
                updateRaceStatus(json, month, half, fan, isRaceTurn);

                // ★ 拉面杯 Gauge 状态栏
                if ("Ramen".equals(currentScenario)) {
                    updateRamenInfo(json);
                } else {
                    if (tvRamenGauge != null) tvRamenGauge.setVisibility(View.GONE);
                    if (tvRamenPlan != null) tvRamenPlan.setVisibility(View.GONE);
                }

                // ★ 队员显示（育马者杯）
                updateTeamMembers(json);

                // 状态
                if (tvHookStatus != null) {
                    tvHookStatus.setText("自动上传：开");
                    tvHookStatus.setTextColor(0xFF00FF88);
                }

            } catch (JSONException e) {
                Log.w(TAG, "Summary parse error: " + e.getMessage());
            }
        });
    }

    /**
     * 更新剧本标签显示
     * 优先用用户选择的剧本，插件推送的作为辅助显示
     */
    private void updateScenarioLabel() {
        if (tvScenarioLabel == null) return;
        String label = scenarioIdToLabel(selectedScenario);
        tvScenarioLabel.setText(label);
        tvScenarioLabel.setVisibility(View.VISIBLE);
    }

    /** 剧本ID → 显示名 */
    private String scenarioIdToLabel(String id) {
        if (id == null) return "";
        switch (id) {
            case "URA": return "URA总决赛";
            case "Aoharu": return "青春杯";
            case "Climax": return "巅峰杯";
            case "GrandDrive": return "偶像杯";
            case "GrandMasters": return "女神杯";
            case "LArc": return "凯旋门杯";
            case "UAF": return "UAF运动会";
            case "Harvest": return "种田杯";
            case "Mecha": return "赛博杯";
            case "Legends": return "传奇杯";
            case "DesertIsland": return "无人岛杯";
            case "HotSpring": return "温泉杯";
            case "Dreams": return "育马者杯";
            case "Ramen": return "拉面杯";
            default: return id;
        }
    }

    /**
     * 解析插件AI评估结果并更新浮窗
     */
    private void updateAiRecommendation(JSONObject ai) {
        try {
            int score = ai.optInt("score", 0);
            int totalStats = ai.optInt("total_stats", 0);
            int skillEval = ai.optInt("skill_eval", 0);
            int skillCount = ai.optInt("skill_count", 0);
            String best = ai.optString("best", "");
            double bestV = ai.optDouble("best_v", 0);
            JSONObject train = ai.optJSONObject("train");
            double restV = ai.optDouble("rest", 0);
            double outgoingV = ai.optDouble("outgoing", 0);

            String bestLabel = aiActionLabel(best);
            if ("Rest".equals(best)) {
                tvRecommend.setText("▶ 助手：休息　评分" + score);
                tvRecommend.setTextColor(0xFFFF4444);
            } else if ("Outgoing".equals(best)) {
                tvRecommend.setText("▶ 助手：外出　评分" + score);
                tvRecommend.setTextColor(0xFF66CCFF);
            } else {
                tvRecommend.setText("▶ 助手：" + bestLabel + "　评分" + score);
                tvRecommend.setTextColor(aiActionColor(best));
            }

            if (tvAiDetail != null) {
                StringBuilder sb = new StringBuilder();
                String[] trainKeys = {"Speed", "Stamina", "Power", "Guts", "Wisdom"};
                String[] trainLabels = {"速", "耐", "力", "根", "智"};
                if (train != null) {
                    for (int i = 0; i < trainKeys.length; i++) {
                        double v = train.optDouble(trainKeys[i], 0);
                        if (i > 0) sb.append(" ");
                        sb.append(trainLabels[i]).append(fmtAiVal(v));
                    }
                }
                sb.append(" | 休").append(fmtAiVal(restV));
                sb.append(" 外").append(fmtAiVal(outgoingV));
                if (totalStats > 0) {
                    sb.append(" 五維").append(totalStats);
                }
                if (skillEval > 0) {
                    sb.append(" 技能").append(skillEval);
                }
                tvAiDetail.setText(sb.toString());
                tvAiDetail.setVisibility(View.VISIBLE);
            }

        } catch (Exception e) {
            Log.w(TAG, "AI parse error: " + e.getMessage());
            tvRecommend.setText("▶ 助手解析错误");
            tvRecommend.setTextColor(0xFFFF4444);
            if (tvAiDetail != null) tvAiDetail.setVisibility(View.GONE);
        }
    }

    private String aiActionLabel(String action) {
        switch (action) {
            case "Speed": return "速";
            case "Stamina": return "耐";
            case "Power": return "力";
            case "Guts": return "根";
            case "Wisdom": return "智";
            case "Rest": return "休息";
            case "Outgoing": return "外出";
            default: return action;
        }
    }

    private int aiActionColor(String action) {
        switch (action) {
            case "Speed": return COLOR_SPD;
            case "Stamina": return COLOR_STA;
            case "Power": return COLOR_PWR;
            case "Guts": return COLOR_GUT;
            case "Wisdom": return COLOR_WIT;
            case "Rest": return 0xFFFF4444;
            case "Outgoing": return 0xFF66CCFF;
            default: return COLOR_DEFAULT;
        }
    }

    private String fmtAiVal(double v) {
        if (Math.abs(v) < 0.05) return "0";
        if (v == Math.floor(v)) {
            return (v >= 0 ? "+" : "") + (int) v;
        }
        return (v >= 0 ? "+" : "") + String.format("%.1f", v);
    }

    // ======== 育马者杯队员显示 ========

    /** 等级数值→标签映射 (0=G, 1=F, 2=E, 3=D, 4=C, 5=B, 6=A, 7=S, 8=SS, 9=UG, 10=UF, 11=UA, 12=US) */
    private static final String[] BREEDERS_LEVEL_LABELS = {
        "G","F","E","D","C","B","A","S","SS","UG","UF","UA","US"
    };

    /** 等级数值→训练加成比例 */
    private static final double[] BREEDERS_LEVEL_BONUS = {
        0.10, 0.12, 0.14, 0.16, 0.18, 0.20, 0.22, 0.24, 0.26, 0.28, 0.30, 0.30, 0.30
    };

    private static String breedersLevelLabel(int level) {
        if (level >= 0 && level < BREEDERS_LEVEL_LABELS.length) return BREEDERS_LEVEL_LABELS[level];
        return "?";
    }

    /**
     * 更新队员显示区域（育马者杯专用）
     * 数据来自插件的 team_members 字段:
     *   "team_members": [{"chara_id":X, "level":3, "dream_gauge":2, "is_burst":false}, ...]
     *   "team_rank": 3, "dream_left": 2
     */
    /**
     * 比赛回合检测 + 生涯目标粉丝数状态栏
     *
     * race_entry_type (single_mode_turn 表):
     *   0 = 非比赛（训练回合）
     *   1 = 有比赛可选（非强制）
     *
     * 生涯目标 (single_mode_route_race 表):
     *   target_type=1 = 生涯目标（必须完成，否则育成结束）
     *   condition_type=3 = 粉丝数条件
     *   condition_value_1 = 需要的粉丝数
     *   turn = 检查回合（从育成开始算，1-78）
     */
    private void updateRaceStatus(JSONObject json, int month, int half, int fan, boolean fallbackRaceTurn) {
        if (tvRaceStatus == null) return;

        StringBuilder sb = new StringBuilder();
        int color = 0xFF888888;

        // 粉丝数
        if (fan >= 0) {
            sb.append("粉丝").append(fan);
        }

        // 比赛回合判断 — 用 turn_config
        // race_entry_type: 0=非比赛, 1=有比赛可选
        boolean isRaceTurn = false;
        if (month > 0 && half > 0) {
            JSONArray turnConfig = getTurnConfig();
            if (turnConfig != null) {
                for (int i = 0; i < turnConfig.length(); i++) {
                    JSONObject tc = turnConfig.optJSONObject(i);
                    if (tc == null) continue;
                    int tcMonth = tc.optInt("month", 0);
                    int tcHalf = tc.optInt("half", 0);
                    if (tcMonth == month && tcHalf == half) {
                        int raceEntry = tc.optInt("race_entry", 0);
                        if (raceEntry == 1) {
                            isRaceTurn = true;
                        }
                        break;
                    }
                }
            } else {
                isRaceTurn = fallbackRaceTurn;
            }
        }

        if (isRaceTurn) {
            if (sb.length() > 0) sb.append(" ");
            sb.append("○有比赛");
            color = 0xFF4FC3F7;
        }

        // 生涯目标粉丝数警告 — 查 MDB single_mode_route_race
        // condition_type=3 → 粉丝数条件, condition_value_1=需要的粉丝数
        // turn=检查回合(从游戏开始算), target_type=1=生涯目标
        if (fan >= 0 && month > 0) {
            // ★ v1.25: 用插件权威回合数（_totalTurnNum），旧 (month-1)*2+half 在
            // 拉面杯第一年特殊日历（4/5/6月双份）和第2/3年会错位 → 识别不到目标比赛
            int jsonTurn = json.optInt("turn", -1);
            int currentTurn = jsonTurn > 0 ? jsonTurn : (month - 1) * 2 + half;
            JSONArray routeRaces = getRouteRaceTargets();
            if (routeRaces != null) {
                int nearestFanTarget = 0;
                int nearestTurn = 0;
                for (int i = 0; i < routeRaces.length(); i++) {
                    JSONObject rr = routeRaces.optJSONObject(i);
                    if (rr == null) continue;
                    int targetType = rr.optInt("target_type", 0);
                    int condType = rr.optInt("condition_type", 0);
                    int turn = rr.optInt("turn", 0);
                    int condValue = rr.optInt("condition_value_1", 0);
                    // 只看生涯目标 + 粉丝条件 + 还没过去的回合
                    if (targetType == 1 && condType == 3 && condValue > 0 && turn >= currentTurn) {
                        if (nearestFanTarget == 0 || turn < nearestTurn) {
                            nearestFanTarget = condValue;
                            nearestTurn = turn;
                        }
                    }
                }
                if (nearestFanTarget > 0 && fan < nearestFanTarget) {
                    int turnsLeft = nearestTurn - currentTurn;
                    if (sb.length() > 0) sb.append(" ");
                    sb.append("⚠目标粉丝<").append(nearestFanTarget);
                    if (turnsLeft > 0) {
                        sb.append(" (剩").append(turnsLeft).append("回合)");
                    }
                    color = 0xFFFF6600;
                }
            }
        }

        if (sb.length() > 0) {
            tvRaceStatus.setText(sb.toString());
            tvRaceStatus.setTextColor(color);
            tvRaceStatus.setVisibility(View.VISIBLE);
        } else {
            tvRaceStatus.setVisibility(View.GONE);
        }
    }

    private JSONArray getRouteRaceTargets() {
        if (routeRaceCache != null && System.currentTimeMillis() - routeRaceCacheTime < 600000) {
            return routeRaceCache;
        }
        new Thread(() -> {
            try {
                String sql = "SELECT race_set_id, target_type, turn, condition_type, condition_value_1" +
                        " FROM single_mode_route_race" +
                        " WHERE target_type=1 AND condition_type=3 AND condition_value_1>0" +
                        " ORDER BY turn";
                String encoded = java.net.URLEncoder.encode(sql, "UTF-8");
                String data = httpGet("http://127.0.0.1:18765/mdb/raw?sql=" + encoded);
                if (data == null || data.isEmpty()) return;
                JSONObject json = new JSONObject(data);
                JSONArray rows = json.optJSONArray("rows");
                if (rows != null) {
                    // 转成对象数组方便使用
                    JSONArray targetArr = new JSONArray();
                    for (int i = 0; i < rows.length(); i++) {
                        JSONArray row = rows.optJSONArray(i);
                        if (row == null || row.length() < 5) continue;
                        JSONObject obj = new JSONObject();
                        obj.put("race_set_id", row.optInt(0));
                        obj.put("target_type", row.optInt(1));
                        obj.put("turn", row.optInt(2));
                        obj.put("condition_type", row.optInt(3));
                        obj.put("condition_value_1", row.optInt(4));
                        targetArr.put(obj);
                    }
                    routeRaceCache = targetArr;
                    routeRaceCacheTime = System.currentTimeMillis();
                    Log.d(TAG, "Route race targets cached: " + targetArr.length() + " entries");
                }
            } catch (Exception e) {
                Log.e(TAG, "getRouteRaceTargets error: " + e.getMessage());
            }
        }).start();
        return routeRaceCache;
    }

    private JSONArray getTurnConfig() {
        // 缓存 10 分钟
        if (turnConfigCache != null && System.currentTimeMillis() - turnConfigCacheTime < 600000) {
            return turnConfigCache;
        }
        // 异步拉取 /log/turn
        new Thread(() -> {
            String data = httpGet("http://127.0.0.1:18765/log/turn");
            if (data == null || data.isEmpty()) return;
            try {
                JSONObject json = new JSONObject(data);
                JSONArray tc = json.optJSONArray("turn_config");
                if (tc != null) {
                    turnConfigCache = tc;
                    turnConfigCacheTime = System.currentTimeMillis();
                }
            } catch (Exception e) {
                Log.e(TAG, "getTurnConfig error: " + e.getMessage());
            }
        }).start();
        return turnConfigCache;
    }

    private void updateRamenInfo(JSONObject json) {
        JSONObject ramen = json.optJSONObject("ramen");
        if (ramen == null) {
            if (tvRamenGauge != null) tvRamenGauge.setVisibility(View.GONE);
            if (tvRamenPlan != null) tvRamenPlan.setVisibility(View.GONE);
            return;
        }

        StringBuilder info = new StringBuilder();

        // ★ 盛況度等级 (moriagari_level) — MDB check_point_pt_effect 真实11档
        // 0-249=0, 250/500/1000/1500/2000/2500/3000/3500/4000/5000 → Lv1~10
        final int[] MORIAGARI_TIERS = {250, 500, 1000, 1500, 2000, 2500, 3000, 3500, 4000, 5000};
        int moriagari = ramen.optInt("moriagari_level", -1);
        int cppt = ramen.optInt("checkpoint_pt", -1);
        if (moriagari >= 0 || cppt >= 0) {
            if (cppt >= 0) {
                int lv = 0;
                for (int t : MORIAGARI_TIERS) if (cppt >= t) lv++;
                moriagari = lv;
            } else if (moriagari > 10) {
                moriagari = 10;
            }
            // 进度条: Lv0~10
            StringBuilder bar = new StringBuilder();
            for (int i = 0; i < 10; i++) {
                bar.append(i < moriagari ? "■" : "□");
            }
            info.append("热").append(bar).append(" Lv").append(moriagari);
            if (cppt >= 0) {
                info.append(" ").append(cppt);
                if (moriagari < 10) {
                    info.append("/").append(MORIAGARI_TIERS[moriagari]);
                }
                // RMJ目标: 第1/2/3年 1500/3000/3500(大成功5000)
                int turn = json.optInt("turn", -1);
                int year = turn < 0 ? -1 : (turn < 24 ? 1 : (turn < 48 ? 2 : 3));
                if (year > 0) {
                    int target = year == 1 ? 1500 : (year == 2 ? 3000 : 3500);
                    info.append(cppt >= target ? " RMJ✓" : " RMJ差" + (target - cppt));
                    // RMJ倒计时: turn 24/48/72行动结算后
                    int rmjTurn = year == 1 ? 24 : (year == 2 ? 48 : 72);
                    if (turn >= 0 && turn <= rmjTurn) {
                        info.append(" 余").append(rmjTurn - turn).append("T");
                    }
                }
            }
            info.append("\n");
        }

        // ★ 隠し味 + 素材槽 + 已选地区（MDB/截图确认字段）
        int kakushimi = ramen.optInt("special_feeling_num", -1);
        JSONArray sozai = ramen.optJSONArray("sozai");
        JSONArray gauges = ramen.optJSONArray("acquisition_gauges");
        if (kakushimi >= 0 || sozai != null) {
            info.append("材");
            if (sozai != null && sozai.length() >= 3) {
                info.append(" 麺").append(sozai.optInt(0))
                    .append(" 汤").append(sozai.optInt(1))
                    .append(" 配").append(sozai.optInt(2));
            }
            if (gauges != null && gauges.length() >= 3) {
                info.append(" (").append(gauges.optInt(0))
                    .append("/").append(gauges.optInt(1))
                    .append("/").append(gauges.optInt(2)).append(")");
            }
            if (kakushimi >= 0) {
                info.append(" 秘").append(kakushimi).append("/4");
            }
            JSONArray regions = ramen.optJSONArray("selected_region_ids");
            if (regions != null && regions.length() > 0) {
                info.append(" 区");
                for (int i = 0; i < regions.length(); i++) {
                    info.append(i == 0 ? "" : "+").append(regions.optInt(i));
                }
            }
            info.append("\n");
        }

        String resourcePlan = RamenResourcePlanner.buildSummary(
                ramen,
                RemoteDataLoader.getCachedData(this, RemoteDataLoader.KEY_RAMEN_RESOURCES),
                RemoteDataLoader.getCachedData(this, RemoteDataLoader.KEY_RAMEN_GAUGES),
                RemoteDataLoader.getCachedData(this, RemoteDataLoader.KEY_RAMEN_REGIONS));
        String checkpointPlan = RamenCheckpointPlanner.buildSummary(
                json,
                RemoteDataLoader.getCachedData(this, RemoteDataLoader.KEY_RAMEN_CHECKPOINTS),
                RemoteDataLoader.getCachedData(this, RemoteDataLoader.KEY_RAMEN_ACTIONS));
        StringBuilder planInfo = new StringBuilder();
        if (!checkpointPlan.isEmpty()) planInfo.append(checkpointPlan);
        if (!resourcePlan.isEmpty()) {
            if (planInfo.length() > 0) planInfo.append("\n");
            planInfo.append(resourcePlan);
        }

        // Keep the compact current-state line above training columns. Long planning
        // details live in their own lower-priority block so they cannot crowd it.
        if (tvRamenGauge != null && info.length() > 0) {
            tvRamenGauge.setText(info.toString().trim());
            tvRamenGauge.setVisibility(View.VISIBLE);
        } else if (tvRamenGauge != null) {
            tvRamenGauge.setVisibility(View.GONE);
        }
        // ★ v1.26: 吃面时机建议 + 地区选择推荐（用户反馈：只告诉能不能吃，不告诉什么时候吃；选地区不推荐）
        String tastingAdvice = buildTastingAdvice(json, ramen);
        if (!tastingAdvice.isEmpty()) planInfo.insert(0, tastingAdvice + "\n");
        String regionAdvice = buildRegionAdvice(json, ramen);
        if (!regionAdvice.isEmpty()) planInfo.insert(0, regionAdvice + "\n");

        if (tvRamenPlan != null && planInfo.length() > 0) {
            tvRamenPlan.setText(planInfo.toString().trim());
            tvRamenPlan.setVisibility(View.VISIBLE);
        } else if (tvRamenPlan != null) {
            tvRamenPlan.setVisibility(View.GONE);
        }

        // 地区必须按 Region ID 显示；第三阶段同名地区仍是独立效果。
        StringBuilder buffInfo = new StringBuilder();
        JSONArray regionIds = ramen.optJSONArray("selected_region_ids");
        if (regionIds != null && regionIds.length() > 0) {
            ensureRamenCatalogs();
            buffInfo.append("已选地区:");
            for (int i = 0; i < regionIds.length(); i++) {
                if (i > 0) buffInfo.append("/");
                int id = regionIds.optInt(i, 0);
                String name = ramenRegionNameCache.get(id);
                buffInfo.append(name == null ? "编号" + id : name + "#" + id);
            }
        }

        // ★ v1.33.1: 候选地区池（插件 MDB 推导值，非运行时原生读数）。
        // selectable_region_ids_source=unknown 时明确显示“尚待验证”，不猜。
        JSONArray derivedIds = ramen.optJSONArray("selectable_region_ids_derived");
        if (derivedIds != null && derivedIds.length() > 0) {
            ensureRamenCatalogs();
            if (buffInfo.length() > 0) buffInfo.append("\n");
            buffInfo.append("候选地区(推导):");
            for (int i = 0; i < derivedIds.length(); i++) {
                if (i > 0) buffInfo.append("/");
                int id = derivedIds.optInt(i, 0);
                String name = ramenRegionNameCache.get(id);
                buffInfo.append(name == null ? "编号" + id : name + "#" + id);
            }
        } else if ("unknown".equals(ramen.optString("selectable_region_ids_source", ""))) {
            if (buffInfo.length() > 0) buffInfo.append("\n");
            buffInfo.append("候选地区:尚待验证");
        }

        if (tvBuffDetail != null && buffInfo.length() > 0) {
            tvBuffDetail.setText(buffInfo.toString().trim());
            tvBuffDetail.setVisibility(View.VISIBLE);
        }
    }

    private void updateTeamMembers(JSONObject json) {
        String scenario = json.optString("scenario", "");
        if (!"Dreams".equals(scenario)) {
            if (teamContainer != null) teamContainer.setVisibility(View.GONE);
            return;
        }

        // team_data is a nested object: {"team_members":[...], "team_rank":N, "dream_training_left":N}
        JSONObject teamData = json.optJSONObject("team_data");
        JSONArray members = teamData != null ? teamData.optJSONArray("team_members") : null;
        if (members == null || members.length() == 0) {
            if (teamContainer != null) teamContainer.setVisibility(View.GONE);
            return;
        }

        if (teamContainer != null) teamContainer.setVisibility(View.VISIBLE);

        // 构建队员字符串: "D●2 E●1 G★3"
        // ★=魂爆可(梦想槽满), ●=梦想槽进度
        StringBuilder sb = new StringBuilder();
        int minLevel = Integer.MAX_VALUE;
        int burstReadyCount = 0;
        for (int i = 0; i < members.length(); i++) {
            try {
                JSONObject m = members.getJSONObject(i);
                int level = m.optInt("level", 0);
                int gauge = m.optInt("dream_gauge", 0);
                // 兼容旧字段名is_burst和新字段名burst_ready
                boolean burstReady = m.optBoolean("burst_ready", false) || m.optBoolean("is_burst", false);
                if (level < minLevel) minLevel = level;
                if (burstReady) burstReadyCount++;
                if (i > 0) sb.append(" ");
                sb.append(breedersLevelLabel(level));
                if (burstReady) {
                    sb.append("★");  // 魂爆可用（梦想槽满）
                } else if (gauge > 0) {
                    for (int g = 0; g < gauge; g++) sb.append("●");
                }
            } catch (JSONException e) { /* skip */ }
        }

        // 魂爆可次数提示
        if (burstReadyCount > 0) {
            sb.append(" 爆×").append(burstReadyCount);
        }

        if (tvTeamMembers != null) tvTeamMembers.setText(sb.toString());

        // 队伍等级 = 最低队员等级
        if (tvTeamRank != null && minLevel < Integer.MAX_VALUE) {
            tvTeamRank.setText("队伍" + breedersLevelLabel(minLevel));
        }

        // 梦想训练剩余次数
        // 优先从插件读取，如果插件读不到则根据month/half计算
        int dreamLeft = teamData != null ? teamData.optInt("dream_left", teamData.optInt("dream_training_left", -1)) : -1;
        if (dreamLeft < 0) {
            dreamLeft = calculateDreamTrainingLeft(json);
        }
        if (tvDreamTraining != null) {
            if (dreamLeft >= 0) {
                tvDreamTraining.setText("梦想" + dreamLeft + "次");
            } else {
                tvDreamTraining.setText("梦想?");
            }
        }
    }

    /**
     * 根据month/half计算梦想训练剩余次数（兜底逻辑）
     * 规则：每半年2次，第三年夏季集训4次
     * 半年=2个half，即每个half给1次梦想训练
     * 第三年夏训(month=7,half=1,2)额外给4次
     */
    private int calculateDreamTrainingLeft(JSONObject json) {
        int month = json.optInt("month", -1);
        int half = json.optInt("half", -1);
        if (month < 1 || half < 1) return -1;

        // 简化模型：假设每次half给1次梦想训练（每半年2次=2个half）
        // 第三年夏训( month=7 )额外多2次（原本2次变成4次）
        // 这是近似计算，实际可能因为已使用而不同
        int totalHalf = (month - 1) * 2 + half;
        // 每个half给1次，但需要判断当前half还没用完
        // 无法精确计算已使用次数，返回-1让用户知道需要插件数据
        return -1;
    }

    /**
     * 更新Buff显示区域
     */
    private void clearBuffViews() {
        if (tvBuffAo != null) tvBuffAo.setText("");
        if (tvBuffMidori != null) tvBuffMidori.setText("");
        if (tvBuffMomo != null) tvBuffMomo.setText("");
        if (tvBuffDetail != null) {
            tvBuffDetail.setText("");
            tvBuffDetail.setVisibility(View.GONE);
        }
    }

    private void hideBuffViews() {
        clearBuffViews();
        if (buffContainer != null) buffContainer.setVisibility(View.GONE);
        if (buffSeparator != null) buffSeparator.setVisibility(View.GONE);
    }

    /** Buff 展示严格按剧本分流：Dreams 才使用青/緑/桃，Ramen 只显示拉面效果。 */
    private void updateBuffs(JSONArray buffs, String scenario) {
        clearBuffViews();
        if (buffs == null || buffs.length() == 0) {
            hideBuffViews();
            return;
        }

        final String requiredType;
        if ("Dreams".equals(scenario)) {
            requiredType = "Breeders";
        } else if ("Ramen".equals(scenario)) {
            requiredType = "Ramen";
        } else {
            hideBuffViews();
            return;
        }

        try {
            JSONArray scenarioBuffs = new JSONArray();
            for (int i = 0; i < buffs.length(); i++) {
                JSONObject buff = buffs.optJSONObject(i);
                if (buff != null && requiredType.equals(buff.optString("type", ""))) {
                    scenarioBuffs.put(buff);
                }
            }
            if (scenarioBuffs.length() == 0) {
                hideBuffViews();
                return;
            }

            if (buffContainer != null) buffContainer.setVisibility(View.VISIBLE);
            if (buffSeparator != null) buffSeparator.setVisibility(View.VISIBLE);
            if ("Dreams".equals(scenario)) {
                updateBreedersBuffs(scenarioBuffs);
            } else {
                updateRamenBuffs(scenarioBuffs);
            }
        } catch (JSONException e) {
            hideBuffViews();
            Log.w(TAG, "Buff parse error: " + e.getMessage());
        }
    }

    private void updateBreedersBuffs(JSONArray buffs) throws JSONException {
        String aoLevel = "-", aoDesc = "";
        String midoriLevel = "-", midoriDesc = "";
        String momoLevel = "-", momoDesc = "";

        for (int i = 0; i < buffs.length(); i++) {
            JSONObject b = buffs.getJSONObject(i);
            String name = b.getString("name");
            int level = b.getInt("level");
            String desc = b.optString("desc", "");

            if ("青".equals(name)) {
                aoLevel = "等级" + level;
                aoDesc = desc;
            } else if ("緑".equals(name)) {
                midoriLevel = "等级" + level;
                midoriDesc = desc;
            } else if ("桃".equals(name)) {
                momoLevel = "等级" + level;
                momoDesc = desc;
            }
        }

        if (tvBuffAo != null) {
            tvBuffAo.setText("青" + aoLevel);
            tvBuffAo.setTextColor(COLOR_BUFF_AO);
        }
        if (tvBuffMidori != null) {
            tvBuffMidori.setText("緑" + midoriLevel);
            tvBuffMidori.setTextColor(COLOR_BUFF_MIDORI);
        }
        if (tvBuffMomo != null) {
            tvBuffMomo.setText("桃" + momoLevel);
            tvBuffMomo.setTextColor(COLOR_BUFF_MOMO);
        }

        if (tvBuffDetail != null) {
            StringBuilder detail = new StringBuilder();
            if (!aoDesc.isEmpty()) detail.append("青:").append(aoDesc).append(" ");
            if (!midoriDesc.isEmpty()) detail.append("緑:").append(midoriDesc).append(" ");
            if (!momoDesc.isEmpty()) detail.append("桃:").append(momoDesc);
            if (detail.length() > 0) {
                tvBuffDetail.setText(detail.toString().trim());
                tvBuffDetail.setVisibility(View.VISIBLE);
            } else {
                tvBuffDetail.setVisibility(View.GONE);
            }
        }
    }


    /** Load Scenario 14 catalogs. Failure keeps raw IDs visible instead of inventing semantics. */
    private void ensureRamenCatalogs() {
        if (ramenEffectRecordCache != null && !ramenEffectRecordCache.isEmpty()) return;
        ramenRegionNameCache = new java.util.HashMap<>();
        ramenEffectRecordCache = new java.util.HashMap<>();
        try {
            String raw = RemoteDataLoader.getCachedData(this, RemoteDataLoader.KEY_RAMEN_REGIONS);
            if (raw == null) return;
            JSONArray regions = new JSONObject(raw).optJSONArray("regions");
            if (regions == null) return;
            for (int i = 0; i < regions.length(); i++) {
                JSONObject region = regions.optJSONObject(i);
                if (region == null) continue;
                int id = region.optInt("region_id", 0);
                if (id <= 0) continue;
                ramenRegionNameCache.put(id, chineseRegionName(region.optString("name_ja", "地区" + id)));
                JSONArray effects = region.optJSONArray("effects");
                if (effects == null) continue;
                for (int j = 0; j < effects.length(); j++) {
                    JSONObject effect = effects.optJSONObject(j);
                    if (effect == null) continue;
                    int effectRecordId = effect.optInt("id", 0);
                    if (effectRecordId > 0) {
                        effect.put("region_name_ja", ramenRegionNameCache.get(id));
                        ramenEffectRecordCache.put(effectRecordId, effect);
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Ramen region catalog parse failed: " + e.getMessage());
        }
    }


    private static String chineseRegionName(String name) {
        return name.replace("館", "馆").replace("島", "岛")
                .replace("東", "东").replace("倉", "仓");
    }

    private String stripRamenMarkup(String text) {
        if (text == null) return "";
        return text.replaceAll("<[^>]+>", "")
                .replace("\\n", " ")
                .replace("\n", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private int ramenCategory(JSONObject effect, String rawName) {
        int category = effect.optInt("EffectCategory", -1);
        if (category >= 0) return category;
        // Compatibility: older hlpatch mislabeled category 1 as 試食会 and category 2 as 地域.
        if (rawName.startsWith("地区效果") || rawName.startsWith("试食会") || rawName.startsWith("試食会")) return 1;
        if (rawName.startsWith("吃面效果") || rawName.startsWith("地域") || rawName.startsWith("地区")) return 2;
        if (rawName.startsWith("特殊效果") || rawName.startsWith("隐味") || rawName.startsWith("隠味")) return 4;
        return -1;
    }

    /** Category 2 is the current-turn ramen action effect, not the RMJ checkpoint reward. */
    private String resolveRamenActionEffect(int effectId, int value) {
        String label;
        String unit = "";
        switch (effectId) {
            case 1: case 4: case 9: label = "训练效果"; unit = "%"; break;
            case 2: case 6: case 11: label = "失败率降低"; unit = "%"; break;
            case 3: label = "羁绊"; break;
            case 5: case 10: label = "友情加成"; unit = "%"; break;
            case 7: case 12: label = "属性单次上限"; break;
            case 8: case 13: label = "技能点单次上限"; break;
            case 14: return "全编成支援卡提示事件发生";
            case 15: return "所选训练全部提示效果发动";
            default: return "吃面效果(编号" + effectId + ",值" + value + ")";
        }
        return label + (value >= 0 ? "+" + value + unit : "");
    }

    /** ActiveEffect category 1 EffectId is single_mode_14_region_effect.id, not region_id. */
    private String resolveRamenRegionEffect(int effectRecordId, int value) {
        ensureRamenCatalogs();
        JSONObject effect = ramenEffectRecordCache.get(effectRecordId);
        if (effect == null) {
            return "地区效果(编号" + effectRecordId + ",值" + value + ")";
        }
        String name = effect.optString("region_name_ja", "地区");
        String template = stripRamenMarkup(effect.optString("display_template", ""));
        if (template.isEmpty()) return name + "(效果编号" + effectRecordId + ",值" + value + ")";
        template = template.replace("{0}", String.valueOf(value));
        return name + ":" + template;
    }

    private String resolveRamenEffect(JSONObject effect) {
        String rawName = effect.optString("name", "");
        // ★ v1.26: 过滤空效果槽（类别-1/编号0/值0占位条目，用户反馈"未解析效果"噪音）
        if (rawName.isEmpty() && effect.optInt("EffectId", 0) == 0 && effect.optInt("EffectValue", 0) == 0) {
            return "";
        }
        int effectId = effect.optInt("EffectId", 0);
        int value = effect.optInt("EffectValue", 0);
        int category = ramenCategory(effect, rawName);
        if (category == 1) return resolveRamenRegionEffect(effectId, value);
        if (category == 2) return resolveRamenActionEffect(effectId, value);
        if (category == 4) return "特殊效果(编号" + effectId + ",值" + value + ")";
        return "未解析效果(类别" + category + ",编号" + effectId + ",值" + value + ")";
    }

    // [MDB region_feeling] 地区配方 麺/汤/配（id 1-10，11-20同1-10）
    private static final int[][] RAMEN_RECIPES = {
        null,
        {2,2,1},{1,2,2},{3,1,1},{2,3,0},{1,1,3},   // 1-5 札幌/函館/新潟/福島/東京
        {2,0,3},{3,2,0},{0,3,2},{2,1,2},{1,3,1}     // 6-10 中山/中京/京都/阪神/小倉
    };
    // [MDB region_effect] 地区对应训练类型（0速1耐2力3根4智）
    private static final int[][] RAMEN_REGION_ATTRS = {
        null,
        {0},{1},{2},{3},{4},
        {0,2,4},{2,3},{1,3},{1,2},{4}
    };

    /** 能否吃面：已选地区配方 vs 素材+隠し味(≤2) [MDB+截图] */
    private boolean canEatRamen(JSONObject ramen) {
        JSONArray selected = ramen.optJSONArray("selected_region_ids");
        JSONArray sozai = ramen.optJSONArray("sozai");
        if (selected == null || selected.length() == 0 || sozai == null || sozai.length() < 3) return false;
        int kakushimi = ramen.optInt("special_feeling_num", 0);
        for (int r = 0; r < selected.length(); r++) {
            int rid = selected.optInt(r);
            int base = rid > 10 ? rid - 10 : rid;
            if (base < 1 || base > 10) continue;
            int[] recipe = RAMEN_RECIPES[base];
            int shortage = 0;
            for (int i = 0; i < 3; i++) shortage += Math.max(0, recipe[i] - sozai.optInt(i));
            if (shortage <= Math.min(2, kakushimi)) return true;
        }
        return false;
    }

    /** ★ v1.26 吃面时机建议（机制: 当回合buff[截图]/槽满FIFO溢出[MDB]/RMJ指标[MDB]） */
    private String buildTastingAdvice(JSONObject json, JSONObject ramen) {
        int turn = json.optInt("turn", -1);
        if (turn <= 0 || !canEatRamen(ramen)) return "";
        int year = turn <= 24 ? 1 : (turn <= 48 ? 2 : 3);
        int target = year == 1 ? 1500 : (year == 2 ? 3000 : 3500);
        int rmjTurn = year == 1 ? 24 : (year == 2 ? 48 : 72);
        int cppt = ramen.optInt("checkpoint_pt", -1);
        JSONArray sozai = ramen.optJSONArray("sozai");
        int sozaiTotal = 0;
        if (sozai != null) for (int i = 0; i < sozai.length(); i++) sozaiTotal += sozai.optInt(i);
        if (sozaiTotal >= 10) return "🍜建议吃：槽满10，不吃会FIFO溢出";
        if (cppt >= 0 && rmjTurn - turn <= 3 && cppt < target)
            return "🍜紧急：RMJ剩" + (rmjTurn - turn) + "回合，pt差" + (target - cppt) + "，能吃就吃";
        JSONArray trainings = json.optJSONArray("trainings");
        if (trainings != null) {
            for (int i = 0; i < trainings.length(); i++) {
                JSONObject tr = trainings.optJSONObject(i);
                if (tr == null) continue;
                if (tr.optInt("shining", 0) == 1 && tr.optInt("heads", 0) >= 2)
                    return "🍜好时机：彩圈多人头，当回合buff收益最大";
                if (tr.optInt("failure_rate", 0) >= 15 && tr.optInt("heads", 0) >= 2)
                    return "🍜好时机：高失败率训练，吃面降失败率";
            }
        }
        if (cppt >= target) return "🍜可留：pt已达标" + target + "，材料留给彩圈大回合";
        return "🍜可吃可留：等彩圈/多人头回合吃更值";
    }

    /** ★ v1.26 地区选择推荐：按卡组类型计数选匹配地区（选择回合 turn 3/25/49） */
    private String buildRegionAdvice(JSONObject json, JSONObject ramen) {
        int turn = json.optInt("turn", -1);
        if (turn != 3 && turn != 25 && turn != 49) return "";
        JSONArray selected = ramen.optJSONArray("selected_region_ids");
        if (selected != null && selected.length() > 0) return "";
        ensureNameCaches();
        int[] typeCount = new int[5];
        JSONArray sc = json.optJSONArray("support_cards");
        if (sc != null) {
            for (int i = 0; i < sc.length(); i++) {
                JSONObject c = sc.optJSONObject(i);
                if (c == null) continue;
                int cardId = c.optInt("support_card_id", 0);
                String tp = supportCardTypeCache != null ? supportCardTypeCache.get(cardId) : null;
                String lb = tp != null ? supportCardDataTypeLabel(tp) : "";
                int idx = "速耐力根智".indexOf(lb.isEmpty() ? ' ' : lb.charAt(0));
                if (idx >= 0) typeCount[idx]++;
            }
        }
        // 地区打分：匹配类型卡数求和（无卡组数据时退化为人气推荐）
        String[] names = {"","札幌","函館","新潟","福島","東京","中山","中京","京都","阪神","小倉"};
        int year = turn <= 24 ? 1 : (turn <= 48 ? 2 : 3);
        int[][] pool = year == 1 ? new int[][]{{1},{2},{3},{4},{5}}
                : year == 2 ? new int[][]{{6},{7},{8},{9},{10}}
                : new int[][]{{1},{2},{3},{4},{5},{6},{7},{8},{9},{10}};
        java.util.List<int[]> scored = new java.util.ArrayList<>();
        for (int[] p : pool) {
            int rid = p[0];
            int score = 0;
            for (int a : RAMEN_REGION_ATTRS[rid]) score += typeCount[a] * 10;
            scored.add(new int[]{rid, score});
        }
        scored.sort((a, b) -> b[1] - a[1]);
        StringBuilder sb = new StringBuilder("🗺地区推荐：");
        for (int i = 0; i < Math.min(3, scored.size()); i++) {
            if (i > 0) sb.append("+");
            sb.append(names[scored.get(i)[0]]);
        }
        sb.append("（按卡组类型）");
        return sb.toString();
    }

    /**
     * 加载支援卡ID→角色名和 NPC ID→名称的缓存
     * 数据来源: uma_support_cards.json (RemoteDataLoader 缓存) + uma_names.json
     */
    /**
     * 加载支援卡ID→真实角色名/类型及角色、NPC名称。
     * support_card_data.json 是完整卡库；uma_support_cards.json 仅作为新卡名称补丁。
     */
    // 只转换 uma_support_cards.json 中此具体 support_card_id 的 type。
    // 不读取当前训练 command_id 或伙伴位置。
    private static String supportCardDataTypeLabel(String type) {
        if ("Speed".equals(type)) return "速";
        if ("Stamina".equals(type)) return "耐";
        if ("Power".equals(type)) return "力";
        if ("Guts".equals(type)) return "根";
        if ("Wit".equals(type) || "Wisdom".equals(type)) return "智";
        if ("Friend".equals(type)) return "友";
        if ("Group".equals(type)) return "团";
        return "";
    }


    private void ensureNameCaches() {
        // 首次浮窗刷新可能早于 RemoteDataLoader 下载完成。不能把只有新卡补丁的
        // 临时结果永久当成完整缓存，否则旧卡会一直回退成“支援卡+ID”。
        if (supportNameCachesComplete) return;
        supportCardNameCache = new java.util.HashMap<>();
        supportCardCharaCache = new java.util.HashMap<>();
        supportCardTypeCache = new java.util.HashMap<>();
        npcNameCache = new java.util.HashMap<>();
        ramenPartnerCharaCache = new java.util.HashMap<>();
        try {
            String namesContent = RemoteDataLoader.getCachedData(this, RemoteDataLoader.KEY_NAMES);
            if (namesContent != null) {
                JSONObject namesJson = new JSONObject(namesContent);
                JSONArray rows = namesJson.optJSONArray("rows");
                if (rows == null) rows = namesJson.optJSONArray("data");
                if (rows != null) {
                    for (int i = 0; i < rows.length(); i++) {
                        JSONObject row = rows.optJSONObject(i);
                        if (row == null) continue;
                        int id = row.optInt("id", 0);
                        String name = row.optString("name", "");
                        String nickname = row.optString("nickname", "");
                        if (id > 0 && (!nickname.isEmpty() || !name.isEmpty())) {
                            npcNameCache.put(id, !nickname.isEmpty() ? nickname : name);
                        }
                    }
                }
            }

            String uniqueCharaContent = RemoteDataLoader.getCachedData(
                    this, RemoteDataLoader.KEY_UNIQUE_CHARA);
            if (uniqueCharaContent != null) {
                JSONArray rows = new JSONObject(uniqueCharaContent).optJSONArray("rows");
                if (rows != null) {
                    for (int i = 0; i < rows.length(); i++) {
                        JSONObject row = rows.optJSONObject(i);
                        if (row == null || row.optInt("scenario_id", 0) != 14) continue;
                        int partnerId = row.optInt("partner_id", 0);
                        int charaId = row.optInt("chara_id", 0);
                        if (partnerId > 0 && charaId > 0) ramenPartnerCharaCache.put(partnerId, charaId);
                    }
                }
            }

            String fullCardContent = RemoteDataLoader.getCachedData(
                    this, RemoteDataLoader.KEY_SUPPORT_CARD_DATA);
            if (fullCardContent != null) {
                JSONArray rows = new JSONObject(fullCardContent).optJSONArray("rows");
                if (rows != null) {
                    for (int i = 0; i < rows.length(); i++) {
                        JSONObject row = rows.optJSONObject(i);
                        if (row == null) continue;
                        int cardId = row.optInt("id", 0);
                        int charaId = row.optInt("chara_id", 0);
                        int cardType = row.optInt("support_card_type", 0);
                        if (cardId <= 0) continue;
                        if (charaId > 0) {
                            supportCardCharaCache.put(cardId, charaId);
                            String name = npcNameCache.get(charaId);
                            if (name != null && !name.isEmpty()) supportCardNameCache.put(cardId, name);
                        }
                        // UI 卡属性共有七种：五种训练卡由 command_id 决定，
                        // support_card_type 仅用于友人/团队，不能把普通卡统称为“支援”。
                        String shortType = "?";
                        if (cardType == 2) shortType = "友";
                        else if (cardType == 3) shortType = "团";
                        else if (cardType == 1) {
                            switch (row.optInt("command_id", 0)) {
                                case 101: shortType = "速"; break;
                                case 102: shortType = "力"; break;
                                case 105: shortType = "耐"; break;
                                case 103: shortType = "根"; break;
                                case 106: shortType = "智"; break;
                                default: break; // command_id=0 等特殊卡不猜类型
                            }
                        }
                        supportCardTypeCache.put(cardId, shortType);
                    }
                }
            }

            String patchContent = RemoteDataLoader.getCachedData(
                    this, RemoteDataLoader.KEY_SUPPORT_CARDS);
            if (patchContent != null) {
                JSONObject patch = new JSONObject(patchContent);
                JSONArray keys = patch.names();
                if (keys != null) {
                    for (int i = 0; i < keys.length(); i++) {
                        String key = keys.optString(i);
                        if ("_meta".equals(key)) continue;
                        JSONObject card = patch.optJSONObject(key);
                        if (card == null) continue;
                        int cardId = card.optInt("cardId", 0);
                        String chara = card.optString("chara", "");
                        String patchType = supportCardDataTypeLabel(card.optString("type", ""));
                        // 补丁卡表的 chara 是日文名；不能覆盖完整卡表经
                        // chara_id → uma_names.nickname 得到的中文名称。
                        if (cardId > 0 && !chara.isEmpty()
                                && !supportCardNameCache.containsKey(cardId)) {
                            supportCardNameCache.put(cardId, chara);
                        }
                        // 类型与同一具体 cardId 绑定，可覆盖完整卡表的
                        // 通用回退值，但绝不来自当前训练。
                        if (cardId > 0 && !patchType.isEmpty()) {
                            supportCardTypeCache.put(cardId, patchType);
                        }
                    }
                }
            }
            Log.d(TAG, "Name caches: cards=" + supportCardNameCache.size()
                    + " links=" + supportCardCharaCache.size()
                    + " types=" + supportCardTypeCache.size()
                    + " names=" + npcNameCache.size()
                    + " ramenPartners=" + ramenPartnerCharaCache.size());
            supportNameCachesComplete = namesContent != null && fullCardContent != null
                    && uniqueCharaContent != null;
            if (!supportNameCachesComplete) {
                Log.w(TAG, "Support name caches incomplete; retry on next summary");
            }
        } catch (Exception e) {
            supportNameCachesComplete = false;
            Log.e(TAG, "ensureNameCaches error: " + e.getMessage());
        }
    }

    private void updateRamenBuffs(JSONArray buffs) throws JSONException {
        StringBuilder effectStr = new StringBuilder();
        String urafType = "";
        String urafState = "";

        for (int i = 0; i < buffs.length(); i++) {
            JSONObject b = buffs.getJSONObject(i);
            String name = b.optString("name", "");
            String type = b.optString("type", "");

            if ("Ramen".equals(type)) {
                if (name.startsWith("裏風:") || name.startsWith("里风:")) {
                    urafType = name;
                    urafState = b.optString("state", "");
                } else {
                    String resolved = resolveRamenEffect(b);
                    if (!resolved.isEmpty()) {
                        if (effectStr.length() > 0) effectStr.append(" ");
                        effectStr.append(resolved);
                    }
                }
            }
        }

        if (tvBuffAo != null) {
            tvBuffAo.setText(effectStr.length() > 0 ? effectStr.toString() : "");
            tvBuffAo.setTextColor(0xFFFFCC00);
        }
        if (tvBuffMidori != null) {
            tvBuffMidori.setText(urafType.isEmpty() ? "" : urafType);
            tvBuffMidori.setTextColor(0xFFCC88FF);
        }
        if (tvBuffMomo != null) {
            tvBuffMomo.setText(urafState.isEmpty() ? "" : "裏風:" + urafState);
            tvBuffMomo.setTextColor(urafState.equals("有効") ? 0xFF00FF88 : 0xFF888888);
        }
        if (tvBuffDetail != null) {
            tvBuffDetail.setVisibility(View.GONE);
        }
    }
    private void updateGenericBuffs(JSONArray buffs) throws JSONException {
        StringBuilder goodBuffs = new StringBuilder();
        StringBuilder badBuffs = new StringBuilder();
        StringBuilder otherBuffs = new StringBuilder();
        StringBuilder detail = new StringBuilder();

        for (int i = 0; i < buffs.length(); i++) {
            JSONObject b = buffs.getJSONObject(i);
            String name = b.getString("name");
            int level = b.optInt("level", 0);
            String type = b.optString("type", "");
            String desc = b.optString("desc", "");

            String label = level > 0 ? name + "等级" + level : name;

            if ("Good".equals(type)) {
                if (goodBuffs.length() > 0) goodBuffs.append(" ");
                goodBuffs.append(label);
            } else if ("Bad".equals(type)) {
                if (badBuffs.length() > 0) badBuffs.append(" ");
                badBuffs.append(label);
            } else {
                if (otherBuffs.length() > 0) otherBuffs.append(" ");
                otherBuffs.append(label);
            }

            if (!desc.isEmpty() && !desc.equals(name)) {
                if (detail.length() > 0) detail.append(" ");
                detail.append(name).append(":").append(desc);
            }
        }

        if (tvBuffAo != null) {
            if (goodBuffs.length() > 0) {
                tvBuffAo.setText(goodBuffs.toString());
                tvBuffAo.setTextColor(COLOR_BUFF_AO);
            } else {
                tvBuffAo.setText("");
            }
        }
        if (tvBuffMidori != null) {
            if (otherBuffs.length() > 0) {
                tvBuffMidori.setText(otherBuffs.toString());
                tvBuffMidori.setTextColor(COLOR_BUFF_MIDORI);
            } else {
                tvBuffMidori.setText("");
            }
        }
        if (tvBuffMomo != null) {
            if (badBuffs.length() > 0) {
                tvBuffMomo.setText(badBuffs.toString());
                tvBuffMomo.setTextColor(COLOR_BUFF_MOMO);
            } else {
                tvBuffMomo.setText("");
            }
        }

        if (tvBuffDetail != null) {
            if (detail.length() > 0) {
                tvBuffDetail.setText(detail.toString());
                tvBuffDetail.setVisibility(View.VISIBLE);
            } else {
                tvBuffDetail.setVisibility(View.GONE);
            }
        }
    }

    private static final int[] CMD_ID_MAP = {101, 102, 105, 103, 106};

    private void updateStatFromSummary(TextView tvVal, TextView tvGain,
                                        JSONObject stats, JSONArray trainings,
                                        JSONObject fullJson,
                                        String statKey, String gainKey,
                                        int brightColor, int dimColor) throws JSONException {
        int current = stats.getInt(statKey);

        int totalGain = 0;
        for (int i = 0; i < trainings.length(); i++) {
            JSONObject tr = trainings.getJSONObject(i);
            JSONObject gains = tr.optJSONObject("gains");
            if (gains != null && gains.has(gainKey)) {
                totalGain += gains.getInt(gainKey);
            }
        }

        tvVal.setText(String.valueOf(current));

        int cmdId = -1;
        int ramenCmdId = -1;
        if ("Speed".equals(gainKey)) { cmdId = 101; ramenCmdId = 601; }
        else if ("Stamina".equals(gainKey)) { cmdId = 105; ramenCmdId = 602; }
        else if ("Power".equals(gainKey)) { cmdId = 102; ramenCmdId = 603; }
        else if ("Guts".equals(gainKey)) { cmdId = 103; ramenCmdId = 604; }
        else if ("Wiz".equals(gainKey)) { cmdId = 106; ramenCmdId = 605; }

        int failureRate = -1;
        int heads = -1;
        int shining = -1;
        int supportCardHeads = 0;
        int npcHeads = 0;
        JSONArray partners = null;
        if (cmdId > 0) {
            for (int i = 0; i < trainings.length(); i++) {
                JSONObject tr = trainings.getJSONObject(i);
                int trCmdId = tr.optInt("command_id", -1);
                if (trCmdId == cmdId || trCmdId == ramenCmdId) {
                    failureRate = tr.optInt("failure_rate", -1);
                    heads = tr.optInt("heads", -1);
                    shining = tr.optInt("shining", -1);
                    partners = tr.optJSONArray("partners");
                    break;
                }
            }
        }

        // 三层参与者显示：携带支援卡、固定羁绊NPC、剧本伙伴。
        // 只有支援卡与固定NPC显示羁绊；剧本伙伴只显示名称。
        ensureNameCaches();
        StringBuilder partnerStr = new StringBuilder();
        if (partners != null) {
            // 建 position → support_card_id 映射
            java.util.Map<Integer, Integer> positionToCardId = new java.util.HashMap<>();
            JSONArray supportCards = fullJson.optJSONArray("support_cards");
            if (supportCards != null) {
                for (int si = 0; si < supportCards.length(); si++) {
                    JSONObject sc = supportCards.optJSONObject(si);
                    if (sc == null) continue;
                    int pos = sc.optInt("position", 0);
                    int scId = sc.optInt("support_card_id", 0);
                    if (pos > 0 && scId > 0) positionToCardId.put(pos, scId);
                }
            }

            for (int pi = 0; pi < partners.length(); pi++) {
                JSONObject p = partners.optJSONObject(pi);
                if (p == null) continue;
                int partnerId = p.optInt("partner_id", 0);
                if (partnerId <= 0) continue;
                int supportPosition = p.isNull("support_position")
                        ? 0 : p.optInt("support_position", 0);
                int supportCardId = p.isNull("support_card_id")
                        ? 0 : p.optInt("support_card_id", 0);
                // 兼容旧 SO，但只使用明确的 support_position，绝不按数字范围分类。
                if (supportCardId <= 0 && supportPosition > 0) {
                    supportCardId = positionToCardId.getOrDefault(supportPosition, 0);
                }
                int bond = p.isNull("current_bond") ? -1 : p.optInt("current_bond", -1);
                boolean shiningKnown = p.has("is_shining") && !p.isNull("is_shining");
                boolean isShining = shiningKnown && p.optBoolean("is_shining", false);
                boolean isHint = p.optBoolean("is_tips_event", false);

                String pName;
                String displayType = "";
                boolean isSupportCard = supportCardId > 0;
                // Scenario 14 has three distinct participant layers:
                // equipped support cards; fixed bond NPCs (director/reporter);
                // and scenario partners whose gauges are not support-card bonds.
                int mappedCharaId = ramenPartnerCharaCache.getOrDefault(partnerId, 0);
                // Prefer the stable chara IDs; keep partner 102/103 only as a
                // Scenario 14 compatibility fallback for older plugin payloads.
                boolean isDirector = mappedCharaId == 9002
                        || ("Ramen".equals(currentScenario) && partnerId == 102);
                boolean isReporter = mappedCharaId == 9003
                        || ("Ramen".equals(currentScenario) && partnerId == 103);
                boolean isFixedBondNpc = !isSupportCard && (isDirector || isReporter);
                if (isSupportCard) {
                    pName = supportCardNameCache.getOrDefault(supportCardId, "");
                    if (pName.isEmpty()) {
                        int charaId = supportCardCharaCache.getOrDefault(supportCardId, 0);
                        if (charaId > 0) pName = npcNameCache.getOrDefault(charaId, "");
                    }
                    if (pName.isEmpty()) pName = "支援卡" + supportCardId;
                    displayType = supportCardTypeCache.getOrDefault(supportCardId, "?");
                } else {
                    int charaId = mappedCharaId;
                    if (charaId == 0 && npcNameCache.containsKey(partnerId)) charaId = partnerId;
                    pName = charaId > 0 ? npcNameCache.getOrDefault(charaId, "") : "";
                    if (isDirector) pName = "理";
                    else if (isReporter && pName.isEmpty()) pName = "记者";
                    if (pName.isEmpty()) {
                        String soName = p.optString("name", "");
                        if (!soName.isEmpty() && !soName.startsWith("伙伴")
                                && !"NPC".equals(soName)) pName = soName;
                        else pName = "伙伴" + partnerId;
                    }
                }

                StringBuilder one = new StringBuilder();
                if (!displayType.isEmpty()) one.append("[").append(displayType).append("]");
                one.append(pName);
                if (isSupportCard || isFixedBondNpc) one.append(" 羁绊").append(bond >= 0 ? bond : "?");
                if (isSupportCard && isShining) one.append(" 彩圈");
                else if (isSupportCard && !shiningKnown) one.append(" 彩圈?");
                if (isSupportCard && isHint) one.append(" 提示");
                if (partnerStr.length() > 0) partnerStr.append("\n");
                partnerStr.append(one);
            }
        }

        StringBuilder gainText = new StringBuilder();
        if (totalGain > 0) {
            gainText.append("+").append(totalGain);
        }
        if (failureRate > 0) {
            gainText.append(" ⚠").append(failureRate).append("%");
        }
        // ★ 伙伴名称显示（照 PC 版小黑板）
        if (partnerStr.length() > 0) {
            gainText.append("\n").append(partnerStr);
        }
        // ★ 彩圈数量（只显示 >0 的值，-1/null 不显示）
        if (shining > 0) {
            gainText.append(" ★").append(shining);
        }

        if (gainText.length() > 0) {
            tvGain.setText(gainText.toString());
            if (failureRate > 30) {
                tvGain.setTextColor(0xFFFF4444);
            } else if (failureRate > 10) {
                tvGain.setTextColor(0xFFFFAA00);
            } else if (failureRate > 0) {
                tvGain.setTextColor(0xFFCCCC44);
            } else {
                tvGain.setTextColor(brightColor);
            }
        } else {
            tvGain.setText("");
            tvGain.setTextColor(dimColor);
        }
    }

    private void updateTrainingLevels(JSONArray trainingLevels) {
        java.util.Map<Integer, Integer> cmdMap = new java.util.HashMap<>();
        cmdMap.put(101, 0); cmdMap.put(102, 2); cmdMap.put(103, 3);
        cmdMap.put(105, 1); cmdMap.put(106, 4);
        // Ramen scenario: 601-605 map to same positions
        cmdMap.put(601, 0); cmdMap.put(602, 1); cmdMap.put(603, 2);
        cmdMap.put(604, 3); cmdMap.put(605, 4);

        int[] levels = {1, 1, 1, 1, 1};
        TextView[] lvViews = {tvSpdLv, tvStaLv, tvPwrLv, tvGutLv, tvWitLv};

        if (trainingLevels != null) {
            try {
                for (int i = 0; i < trainingLevels.length(); i++) {
                    JSONObject tl = trainingLevels.getJSONObject(i);
                    int cmdId = tl.optInt("command_id", 0);
                    int lv = tl.optInt("level", 1);
                    Integer idx = cmdMap.get(cmdId);
                    if (idx != null && idx < levels.length) {
                        levels[idx] = lv;
                    }
                }
            } catch (JSONException e) {
                Log.w(TAG, "trainingLevels parse error: " + e.getMessage());
            }
        }

        for (int i = 0; i < lvViews.length; i++) {
            if (lvViews[i] != null) {
                if (levels[i] > 1) {
                    lvViews[i].setText("等级" + levels[i]);
                    lvViews[i].setVisibility(View.VISIBLE);
                } else {
                    lvViews[i].setVisibility(View.GONE);
                }
            }
        }
    }

    // ======== HTTP服务器 ========
    private void startHttpServer() {
        try {
            httpServer = new HttpDataService(this);
            httpServer.startServer();
            Log.d(TAG, "HTTP server started on port " + HttpDataService.PORT);
            updateNotification("本地通信：" + HttpDataService.PORT + " | " + scenarioIdToLabel(selectedScenario) + " | 等待插件推送");
        } catch (Exception e) {
            Log.e(TAG, "HTTP server start failed: " + e.getMessage(), e);
        }
    }

    private void stopHttpServer() {
        if (httpServer != null) {
            httpServer.stopServer();
            httpServer = null;
        }
    }

    // ======== 兜底轮询 ========
    private void startFallbackPoll() {
        pollRunning = true;
        pollThread = new Thread(() -> {
            try { Thread.sleep(POLL_THRESHOLD_MS); } catch (InterruptedException e) { return; }
            while (pollRunning) {
                long elapsed = System.currentTimeMillis() - lastDataTime;
                if (elapsed > POLL_THRESHOLD_MS) {
                    String data = httpGet("http://127.0.0.1:18765/summary");
                    if (data != null && !data.isEmpty() && !data.contains("\"error\"")) {
                        Log.d(TAG, "Fallback poll got data");
                        handleData(data);
                    }
                }
                try { Thread.sleep(POLL_INTERVAL_MS); } catch (InterruptedException e) { break; }
            }
        });
        pollThread.setDaemon(true);
        pollThread.setName("FallbackPoll");
        pollThread.start();
    }

    private void stopFallbackPoll() {
        pollRunning = false;
        if (pollThread != null) pollThread.interrupt();
    }

    private String httpGet(String urlStr) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(urlStr);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(1500);
            conn.setReadTimeout(1500);
            int code = conn.getResponseCode();
            if (code == 200) {
                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(conn.getInputStream(), "UTF-8"));
                final int maxChars = urlStr.contains("/summary") ? 131072 : 32768;
                StringBuilder sb = new StringBuilder(Math.min(maxChars, 8192));
                char[] chunk = new char[2048];
                int n;
                while ((n = reader.read(chunk)) >= 0) {
                    if (sb.length() + n > maxChars) {
                        Log.w(TAG, "HTTP response exceeds safe limit: " + urlStr);
                        reader.close();
                        return null;
                    }
                    sb.append(chunk, 0, n);
                }
                reader.close();
                return sb.toString();
            }
            return null;
        } catch (Exception e) {
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    // ======== 胜鞍分析 ========
    private void fetchSaddleAnalysis() {
        new Thread(() -> {
            String data = httpGet("http://127.0.0.1:18765/saddle-analysis");
            if (data == null || data.isEmpty()) {
                handler.post(() -> {
                    if (tvSaddleContent != null) {
                        tvSaddleContent.setText("无法连接插件");
                        tvSaddleContent.setTextColor(0xFFFF6666);
                    }
                });
                return;
            }
            try {
                JSONObject json = new JSONObject(data);
                if (json.has("error")) {
                    handler.post(() -> {
                        if (tvSaddleContent != null) {
                            tvSaddleContent.setText("未在育成中");
                            tvSaddleContent.setTextColor(0xFF888888);
                        }
                    });
                    return;
                }
                final String display = renderSaddleAnalysis(json);
                handler.post(() -> {
                    if (tvSaddleContent != null) {
                        tvSaddleContent.setText(display);
                        tvSaddleContent.setTextColor(0xFFCCAAFF);
                    }
                });
            } catch (Exception e) {
                handler.post(() -> {
                    if (tvSaddleContent != null) {
                        tvSaddleContent.setText("解析失败: " + e.getMessage());
                        tvSaddleContent.setTextColor(0xFFFF6666);
                    }
                });
            }
        }).start();
    }

    private String renderSaddleAnalysis(JSONObject json) throws JSONException {
        StringBuilder sb = new StringBuilder();
        int totalRaces = json.optInt("total_races", 0);
        int winCount = json.optInt("win_count", 0);
        int saddleCount = json.optInt("saddle_count", 0);

        sb.append("出走").append(totalRaces).append(" 勝").append(winCount)
          .append(" 一级赛胜鞍").append(saddleCount).append("\n");

        // 当前马胜鞍
        JSONArray winSaddles = json.optJSONArray("win_saddles");
        if (winSaddles != null && winSaddles.length() > 0) {
            int relationBonusCount = 0;
            int totalPoints = 0;
            StringBuilder saddleStr = new StringBuilder();
            for (int i = 0; i < winSaddles.length(); i++) {
                JSONObject s = winSaddles.optJSONObject(i);
                if (s == null) continue;
                String name = s.optString("name", "?");
                boolean isRelBonus = s.optBoolean("is_relation_bonus", false);
                int point = s.optInt("relation_point", 0);
                if (isRelBonus) {
                    relationBonusCount++;
                    totalPoints += point;
                    if (saddleStr.length() > 0) saddleStr.append(" ");
                    saddleStr.append(name).append("(").append(point).append(")");
                }
            }
            sb.append("相性加成: ").append(relationBonusCount).append("种 ")
              .append(totalPoints).append("pt\n");
            if (saddleStr.length() > 0) {
                sb.append(saddleStr).append("\n");
            }
        } else {
            sb.append("无G1胜鞍\n");
        }

        // 亲马胜鞍
        JSONArray parentSaddles = json.optJSONArray("parent_saddles");
        if (parentSaddles != null && parentSaddles.length() > 0) {
            // 收集当前马胜鞍名集合
            java.util.Set<String> mySaddleNames = new java.util.HashSet<>();
            if (winSaddles != null) {
                for (int i = 0; i < winSaddles.length(); i++) {
                    JSONObject s = winSaddles.optJSONObject(i);
                    if (s != null) mySaddleNames.add(s.optString("name", ""));
                }
            }
            for (int p = 0; p < parentSaddles.length(); p++) {
                JSONObject parent = parentSaddles.optJSONObject(p);
                if (parent == null) continue;
                String label = parent.optString("label", "p" + p);
                int charaId = parent.optInt("chara_id", 0);
                int pCount = parent.optInt("saddle_count", 0);
                JSONArray pSaddles = parent.optJSONArray("saddles");
                sb.append(label).append("(").append(charaId).append("): ");
                if (pCount == 0 || pSaddles == null || pSaddles.length() == 0) {
                    sb.append("无\n");
                    continue;
                }
                int overlap = 0;
                StringBuilder pStr = new StringBuilder();
                for (int i = 0; i < pSaddles.length(); i++) {
                    JSONObject ps = pSaddles.optJSONObject(i);
                    if (ps == null) continue;
                    String name = ps.optString("name", "?");
                    if (mySaddleNames.contains(name)) {
                        overlap++;
                        if (pStr.length() > 0) pStr.append(" ");
                        pStr.append("★").append(name);
                    }
                }
                sb.append(pCount).append("种");
                if (overlap > 0) {
                    sb.append(" 一致").append(overlap).append("种\n");
                    sb.append(pStr).append("\n");
                } else {
                    sb.append("\n");
                }
            }
        }

        // MDB relation_groups 点数表（只显示有点的）
        JSONArray relGroups = json.optJSONArray("relation_groups");
        if (relGroups != null && relGroups.length() > 0) {
            StringBuilder rgStr = new StringBuilder();
            for (int i = 0; i < relGroups.length(); i++) {
                JSONObject rg = relGroups.optJSONObject(i);
                if (rg == null) continue;
                int point = rg.optInt("point", 0);
                if (point > 0) {
                    int type = rg.optInt("type", 0);
                    if (rgStr.length() > 0) rgStr.append(",");
                    rgStr.append(type).append(":").append(point);
                }
            }
            if (rgStr.length() > 0) {
                sb.append("数据库相性表：").append(rgStr);
            }
        }

        return sb.toString().trim();
    }

    // ======== 抓包 (EventLogger) ========

    private void toggleSniff(boolean enable) {
        sniffEnabled = enable;
        new Thread(() -> {
            String url = "http://127.0.0.1:18765/api/sniff/toggle";
            // POST with empty body — hlpatch just needs the path hit
            try {
                java.net.URL u = new java.net.URL(url);
                HttpURLConnection conn = (HttpURLConnection) u.openConnection();
                conn.setRequestMethod("POST");
                conn.setDoOutput(true);
                conn.setConnectTimeout(3000);
                conn.setReadTimeout(3000);
                conn.getOutputStream().write(enable ? b("1") : b("0"));
                String resp = readAll(conn.getInputStream());
                conn.disconnect();
                Log.d(TAG, "sniff toggle: " + resp);
            } catch (Exception e) {
                Log.e(TAG, "sniff toggle failed: " + e.getMessage());
            }
        }).start();
    }

    private byte[] b(String s) { return s.getBytes(java.nio.charset.StandardCharsets.UTF_8); }

    private String readAll(java.io.InputStream is) throws Exception {
        java.io.BufferedReader r = new java.io.BufferedReader(
                new java.io.InputStreamReader(is, "UTF-8"));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = r.readLine()) != null) sb.append(line);
        return sb.toString();
    }

    private void startSniffPolling() {
        sniffRunning = true;
        sniffThread = new Thread(() -> {
            // 等一下让 toggle 生效
            try { Thread.sleep(500); } catch (InterruptedException e) { return; }
            while (sniffRunning && sniffPanelVisible) {
                try {
                    String data = httpGet("http://127.0.0.1:18765/api/sniff");
                    if (data != null && !data.isEmpty()) {
                        final String display = renderSniffData(data);
                        handler.post(() -> {
                            if (tvSniffContent != null && sniffPanelVisible) {
                                tvSniffContent.setText(display);
                            }
                        });
                    }
                } catch (Exception e) {
                    Log.e(TAG, "sniff poll error: " + e.getMessage());
                }
                try { Thread.sleep(1500); } catch (InterruptedException e) { break; }
            }
        });
        sniffThread.setDaemon(true);
        sniffThread.setName("SniffPoll");
        sniffThread.start();
    }

    private void stopSniffPolling() {
        sniffRunning = false;
        if (sniffThread != null) sniffThread.interrupt();
    }

    private String renderSniffData(String jsonStr) {
        try {
            JSONObject json = new JSONObject(jsonStr);
            boolean enabled = json.optBoolean("enabled", false);
            if (!enabled) return "抓包未启用";

            JSONArray reqs = json.optJSONArray("requests");
            JSONArray resps = json.optJSONArray("responses");

            StringBuilder sb = new StringBuilder();
            int reqCount = (reqs != null) ? reqs.length() : 0;
            int respCount = (resps != null) ? resps.length() : 0;
            sb.append("抓包：请求").append(reqCount).append("条/响应").append(respCount)
              .append("条\n");

            // 显示最近的请求（最多5条）
            if (reqs != null && reqCount > 0) {
                int start = Math.max(0, reqCount - 5);
                for (int i = reqCount - 1; i >= start; i--) {
                    JSONObject req = reqs.optJSONObject(i);
                    if (req == null) continue;
                    String url = req.optString("url", "?");
                    // 提取 API 路径
                    String apiName = extractApiName(url);
                    int size = req.optInt("size", 0);
                    String hex = req.optString("hex", "");
                    String text = req.optString("text", "");

                    sb.append("→").append(apiName).append(" [").append(size).append("B]\n");

                    // 尝试 MessagePack 解码
                    String decoded = tryDecodeMsgPack(hex);
                    if (decoded != null) {
                        // 截断过长的解码结果
                        if (decoded.length() > 200) {
                            decoded = decoded.substring(0, 200) + "...";
                        }
                        sb.append(decoded).append("\n");
                    } else if (text != null && !text.isEmpty() && text.length() < 200) {
                        sb.append("  文本：").append(text.substring(0, Math.min(text.length(), 100)));
                        sb.append("\n");
                    }
                }
            }

            // 显示最近的响应（最多3条）
            if (resps != null && respCount > 0) {
                int start = Math.max(0, respCount - 3);
                for (int i = respCount - 1; i >= start; i--) {
                    JSONObject resp = resps.optJSONObject(i);
                    if (resp == null) continue;
                    int id = resp.optInt("id", 0);
                    int size = resp.optInt("size", 0);
                    String hex = resp.optString("hex", "");

                    sb.append("←#").append(id).append(" [").append(size).append("B]\n");

                    String decoded = tryDecodeMsgPack(hex);
                    if (decoded != null) {
                        if (decoded.length() > 200) {
                            decoded = decoded.substring(0, 200) + "...";
                        }
                        sb.append(decoded).append("\n");
                    }
                }
            }

            if (reqCount == 0 && respCount == 0) {
                sb.append("等待通信...");
            }

            return sb.toString().trim();
        } catch (Exception e) {
            return "解析错误: " + e.getMessage();
        }
    }

    private String extractApiName(String url) {
        if (url == null || url.isEmpty()) return "?";
        // 提取最后一段路径
        int idx = url.lastIndexOf('/');
        if (idx >= 0 && idx < url.length() - 1) {
            String name = url.substring(idx + 1);
            // 去掉 query string
            int qIdx = name.indexOf('?');
            if (qIdx >= 0) name = name.substring(0, qIdx);
            return name;
        }
        return url.length() > 30 ? url.substring(url.length() - 30) : url;
    }

    private String tryDecodeMsgPack(String hex) {
        if (hex == null || hex.isEmpty()) return null;
        try {
            // hex → bytes
            int len = hex.length() / 2;
            if (len < 2) return null;
            byte[] bytes = new byte[len];
            for (int i = 0; i < len; i++) {
                bytes[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
            }
            // 如果是 JSON 文本（以 { 开头），直接返回
            if (bytes[0] == '{' || bytes[0] == '[') {
                return new String(bytes, 0, Math.min(bytes.length, 200),
                        java.nio.charset.StandardCharsets.UTF_8);
            }
            // MessagePack 解码
            Object decoded = MsgPackDecoder.decode(bytes);
            if (decoded == null) return null;
            String formatted = MsgPackDecoder.formatValue(decoded, 0);
            if (formatted.length() < 2) return null;
            return "  " + formatted;
        } catch (Exception e) {
            return null;
        }
    }

    // ======== 事件推荐 ========

    private void startEvtPolling() {
        evtRunning = true;
        evtThread = new Thread(() -> {
            // Do not clear SO state here: completed observations must survive UI reopen.
            lastEvtChoiceCount = 0;
            lastEvtStoryId = 0;

            while (evtRunning && evtPanelVisible) {
                try {
                    String data = httpGet("http://127.0.0.1:18765/api/event/choices");
                    if (data != null && !data.isEmpty()) processEvtData(data);
                    String obs = httpGet("http://127.0.0.1:18765/api/event/observations?after_id="
                            + lastEvtObservationId);
                    if (obs != null && !obs.isEmpty()) processEvtObservations(obs);
                } catch (Exception e) {
                    Log.e(TAG, "evt poll error: " + e.getMessage());
                }
                try { Thread.sleep(500); } catch (InterruptedException e) { break; }
            }
        });
        evtThread.setDaemon(true);
        evtThread.setName("EvtPoll");
        evtThread.start();
    }

    private void stopEvtPolling() {
        evtRunning = false;
        if (evtThread != null) evtThread.interrupt();
    }

    private void processEvtObservations(String jsonStr) {
        try {
            JSONObject root = new JSONObject(jsonStr);
            JSONArray observations = root.optJSONArray("observations");
            if (observations == null) return;
            for (int i = 0; i < observations.length(); i++) {
                JSONObject observation = observations.optJSONObject(i);
                if (observation == null) continue;
                long id = observation.optLong("observation_id", 0);
                if (id <= lastEvtObservationId) continue;
                dataCollector.onEventObservation(observation);
                lastEvtObservationId = id;
            }
            if (lastEvtObservationId > 0) {
                final long shownId = lastEvtObservationId;
                handler.post(() -> {
                    if (tvEvtContent != null && evtPanelVisible) {
                        tvEvtContent.setText("事件结果已安全记录 #" + shownId + "（结果标签待确认）");
                        tvEvtContent.setTextColor(0xFF00FFAA);
                    }
                });
            }
        } catch (Exception e) {
            Log.e(TAG, "event observation parse error: " + e.getMessage());
        }
    }

    private void processEvtData(String jsonStr) {
        try {
            JSONObject json = new JSONObject(jsonStr);
            int storyId = json.optInt("story_id", 0);
            int selectedIdx = json.optInt("selected_idx", -1);
            JSONArray choices = json.optJSONArray("choices");
            int choiceCount = (choices != null) ? choices.length() : 0;

            // 没有选项 → 无事件
            if (choiceCount == 0) {
                if (lastEvtChoiceCount > 0) {
                    // 事件刚结束，清空
                    lastEvtChoiceCount = 0;
                    lastEvtStoryId = 0;
                    handler.post(() -> {
                        if (tvEvtContent != null) {
                            tvEvtContent.setText("监控中...");
                            tvEvtContent.setTextColor(0xFF00FFAA);
                        }
                    });
                }
                return;
            }

            // 玩家已选择 → 显示已选
            if (selectedIdx >= 0 && selectedIdx < choiceCount) {
                final int sel = selectedIdx;
                final int total = choiceCount;
                handler.post(() -> {
                    if (tvEvtContent != null) {
                        tvEvtContent.setText("已选: 第" + (sel + 1) + "个 / " + total + "选");
                        tvEvtContent.setTextColor(0xFF888888);
                    }
                });
                // SO v3.24.74 keeps completed observations independently.
                // Never clear them automatically; consume by observation_id instead.
                return;
            }

            // 新事件或选项数变化 → 重新评分
            if (choiceCount != lastEvtChoiceCount || storyId != lastEvtStoryId) {
                lastEvtChoiceCount = choiceCount;
                lastEvtStoryId = storyId;

                // 先显示"检测到事件"
                final int cnt = choiceCount;
                handler.post(() -> {
                    if (tvEvtContent != null) {
                        tvEvtContent.setText("检测到事件 " + cnt + "选 评分中...");
                        tvEvtContent.setTextColor(0xFFFFFFAA);
                    }
                });

                // 收集 gain_ids
                StringBuilder gainIds = new StringBuilder();
                for (int i = 0; i < choiceCount; i++) {
                    JSONObject c = choices.optJSONObject(i);
                    if (c == null) continue;
                    int gid = c.optInt("gain_id", 0);
                    if (gid > 0) {
                        if (gainIds.length() > 0) gainIds.append(",");
                        gainIds.append(gid);
                    }
                }

                // 查 MDB 获取各选项的 reward 数值
                int bestIdx = -1;
                double bestScore = Double.NEGATIVE_INFINITY;
                String bestDetail = "";

                if (gainIds.length() > 0) {
                    // 查 event_choice_reward_gain_param 获取实际数值
                    String sql = "SELECT display_id, effect_value0, effect_value1, effect_value2" +
                            " FROM event_choice_reward_gain_param" +
                            " WHERE display_id IN (" + gainIds + ")";
                    String mdbResult = httpGet("http://127.0.0.1:18765/mdb/raw?sql=" +
                            java.net.URLEncoder.encode(sql, "UTF-8"));

                    if (mdbResult != null && !mdbResult.isEmpty()) {
                        try {
                            JSONObject mdb = new JSONObject(mdbResult);
                            JSONArray rows = mdb.optJSONArray("rows");
                            // 建 display_id → total_value 映射
                            java.util.Map<Integer, Integer> gainMap = new java.util.HashMap<>();
                            java.util.Map<Integer, String> gainDetail = new java.util.HashMap<>();
                            if (rows != null) {
                                for (int r = 0; r < rows.length(); r++) {
                                    JSONArray row = rows.optJSONArray(r);
                                    if (row == null || row.length() < 4) continue;
                                    int displayId = row.optInt(0);
                                    int v0 = row.optInt(1);
                                    int v1 = row.optInt(2);
                                    int v2 = row.optInt(3);
                                    int total = v0 + v1 + v2;
                                    gainMap.put(displayId, total);
                                    StringBuilder detail = new StringBuilder();
                                    if (v0 != 0) detail.append(v0 > 0 ? "+" : "").append(v0).append(" ");
                                    if (v1 != 0) detail.append(v1 > 0 ? "+" : "").append(v1).append(" ");
                                    if (v2 != 0) detail.append(v2 > 0 ? "+" : "").append(v2);
                                    gainDetail.put(displayId, detail.toString().trim());
                                }
                            }

                            // 评分每个选项
                            for (int i = 0; i < choiceCount; i++) {
                                JSONObject c = choices.optJSONObject(i);
                                if (c == null) continue;
                                int gid = c.optInt("gain_id", 0);
                                Integer total = gainMap.get(gid);
                                if (total != null) {
                                    double score = total;
                                    if (score > bestScore) {
                                        bestScore = score;
                                        bestIdx = i;
                                        bestDetail = gainDetail.getOrDefault(gid, "");
                                    }
                                }
                            }
                        } catch (Exception e) {
                            Log.e(TAG, "MDB parse error: " + e.getMessage());
                        }
                    }
                }

                // 显示推荐结果
                final int recommend = bestIdx;
                final double score = bestScore;
                final String detail = bestDetail;
                final int total = choiceCount;
                handler.post(() -> {
                    if (tvEvtContent != null && evtPanelVisible) {
                        if (recommend >= 0) {
                            tvEvtContent.setText("推荐选第" + (recommend + 1) + "个 (" + total + "选)");
                            tvEvtContent.setTextColor(0xFF00FFAA);
                        } else {
                            // MDB 查不到 → 用简单启发式
                            tvEvtContent.setText("事件 " + total + "选 (数据不足)");
                            tvEvtContent.setTextColor(0xFFAAAAAA);
                        }
                    }
                });
            }
        } catch (Exception e) {
            Log.e(TAG, "evt process error: " + e.getMessage());
        }
    }

    // ======== 通知栏 ========
    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "悬浮窗服务", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("保持悬浮窗运行");
            channel.setShowBadge(false);
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }

    private Notification createNotification() {
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("赛马娘助手")
                .setContentText("本地通信：18766 | " + scenarioIdToLabel(selectedScenario) + " | 等待插件推送")
                .setSmallIcon(android.R.drawable.ic_menu_agenda)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true)
                .build();
    }

    private void updateNotification(String text) {
        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("赛马娘助手")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_menu_agenda)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true)
                .build();
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) {
            nm.notify(NOTIFICATION_ID, notification);
        }
    }

    // ======== 浮窗 ========
    private void createFloatingView() {
        try {
            windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
            if (windowManager == null) {
                Log.e(TAG, "WindowManager is null");
                return;
            }

            floatingView = LayoutInflater.from(this).inflate(R.layout.floating_window, null);

            // 绑定视图
            tvTurn = floatingView.findViewById(R.id.tv_turn);
            tvTotal = floatingView.findViewById(R.id.tv_total);
            tvStamina = floatingView.findViewById(R.id.tv_stamina);
            tvMotivation = floatingView.findViewById(R.id.tv_motivation);
            tvRecommend = floatingView.findViewById(R.id.tv_recommend);
            tvSpdVal = floatingView.findViewById(R.id.tv_spd_val);
            tvSpdGain = floatingView.findViewById(R.id.tv_spd_gain);
            tvStaVal = floatingView.findViewById(R.id.tv_sta_val);
            tvStaGain = floatingView.findViewById(R.id.tv_sta_gain);
            tvPwrVal = floatingView.findViewById(R.id.tv_pwr_val);
            tvPwrGain = floatingView.findViewById(R.id.tv_pwr_gain);
            tvGutVal = floatingView.findViewById(R.id.tv_gut_val);
            tvGutGain = floatingView.findViewById(R.id.tv_gut_gain);
            tvWitVal = floatingView.findViewById(R.id.tv_wit_val);
            tvWitGain = floatingView.findViewById(R.id.tv_wit_gain);
            tvFacility = floatingView.findViewById(R.id.tv_facility);
            tvHookStatus = floatingView.findViewById(R.id.tv_hook_status);
            // 剧本标签
            tvScenarioLabel = floatingView.findViewById(R.id.tv_scenario_label);

            // 训练等级视图
            tvSpdLv = floatingView.findViewById(R.id.tv_spd_lv);
            tvStaLv = floatingView.findViewById(R.id.tv_sta_lv);
            tvPwrLv = floatingView.findViewById(R.id.tv_pwr_lv);
            tvGutLv = floatingView.findViewById(R.id.tv_gut_lv);
            tvWitLv = floatingView.findViewById(R.id.tv_wit_lv);
            // 状态指示
            tvState = floatingView.findViewById(R.id.tv_state);
            // AI评估详情
            tvAiDetail = floatingView.findViewById(R.id.tv_ai_detail);
            // Buff视图
            buffContainer = floatingView.findViewById(R.id.buff_container);
            tvBuffAo = floatingView.findViewById(R.id.tv_buff_ao);
            tvBuffMidori = floatingView.findViewById(R.id.tv_buff_midori);
            tvBuffMomo = floatingView.findViewById(R.id.tv_buff_momo);
            tvBuffDetail = floatingView.findViewById(R.id.tv_buff_detail);
            buffSeparator = floatingView.findViewById(R.id.buff_separator);
            // 队员视图（育马者杯）
            teamContainer = floatingView.findViewById(R.id.team_container);
            tvTeamLabel = floatingView.findViewById(R.id.tv_team_label);
            tvTeamMembers = floatingView.findViewById(R.id.tv_team_members);
            tvTeamRank = floatingView.findViewById(R.id.tv_team_rank);
            tvDreamTraining = floatingView.findViewById(R.id.tv_dream_training);
            // 胜鞍面板
            saddlePanel = floatingView.findViewById(R.id.saddle_panel);
            tvSaddleContent = floatingView.findViewById(R.id.tv_saddle_content);
            // 抓包面板
            sniffPanel = floatingView.findViewById(R.id.sniff_panel);
            tvSniffContent = floatingView.findViewById(R.id.tv_sniff_content);
            // 事件推荐面板
            evtPanel = floatingView.findViewById(R.id.evt_panel);
            tvEvtContent = floatingView.findViewById(R.id.tv_evt_content);
            // 比赛/目标状态栏 + 拉面杯 Gauge
            tvRaceStatus = floatingView.findViewById(R.id.tv_race_status);
            tvRamenGauge = floatingView.findViewById(R.id.tv_ramen_gauge);
            tvRamenPlan = floatingView.findViewById(R.id.tv_ramen_plan);

            // ★ 初始化剧本标签
            updateScenarioLabel();

            int layoutFlag;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                layoutFlag = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
            } else {
                layoutFlag = WindowManager.LayoutParams.TYPE_PHONE;
            }

            params = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    layoutFlag,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT);
            params.gravity = Gravity.TOP | Gravity.START;
            params.x = 20;
            params.y = 100;

            setupDrag();
            windowManager.addView(floatingView, params);
            isViewAdded = true;
            Log.d(TAG, "Floating view added");

            // ★ v1.26: 折叠/展开面板（用户反馈占地方）
            final View contentContainer = floatingView.findViewById(R.id.content_container);
            final TextView btnCollapse = floatingView.findViewById(R.id.btn_collapse);
            if (btnCollapse != null && contentContainer != null) {
                btnCollapse.setOnClickListener(v -> {
                    boolean show = contentContainer.getVisibility() != View.VISIBLE;
                    contentContainer.setVisibility(show ? View.VISIBLE : View.GONE);
                    btnCollapse.setText(show ? "▼" : "▶");
                });
            }

            View btnClose = floatingView.findViewById(R.id.btn_close);
            // 拉面按钮：合并三个端点后只产生一次GitHub提交。
            TextView btnLog = floatingView.findViewById(R.id.btn_debug_log);
            if (btnLog != null) {
                btnLog.setOnClickListener(v -> {
                    if (endpointDumper == null) return;
                    final TextView btn = (TextView) v;
                    final int origColor = btn.getCurrentTextColor();
                    btn.setText("···");
                    btn.setTextColor(0xFF00FF88);
                    endpointDumper.dumpRamenSnapshot(currentScenario,
                            (ok, fail, fails) -> handler.post(() -> {
                                btn.setText("拉面");
                                btn.setTextColor(origColor);
                                if (tvHookStatus != null) {
                                    tvHookStatus.setText(fail == 0
                                            ? "拉面快照上传成功"
                                            : "拉面快照失败:" + fails.trim());
                                    tvHookStatus.setTextColor(
                                            fail == 0 ? 0xFF00FF88 : 0xFFFF4444);
                                }
                            }));
                });
            }

            // DUMP按钮：一键抓取所有端点数据上传GitHub
            TextView btnDump = floatingView.findViewById(R.id.btn_dump);
            if (btnDump != null) {
                btnDump.setOnClickListener(v -> {
                    if (endpointDumper != null) {
                        // ★ v3.18.8: dumping中再点=取消
                        if (endpointDumper.isDumping()) {
                            endpointDumper.cancel();
                            final TextView btn = (TextView) v;
                            btn.setText("完整调试");
                            return;
                        }
                        // ★ 视觉反馈：按钮变色+改字
                        final TextView btn = (TextView) v;
                        final int origColor = btn.getCurrentTextColor();
                        btn.setText("···");
                        btn.setTextColor(0xFF00FF88);
                        endpointDumper.dumpAll(currentScenario, new EndpointDumper.DumpCallback() {
                            @Override
                            public void onDumpComplete(int ok, int fail, String fails) {
                                handler.post(() -> {
                                    btn.setText("完整调试");
                                    btn.setTextColor(origColor);
                                });
                            }
                        });
                    }
                });
            }
            if (btnClose != null) {
                btnClose.setOnClickListener(v -> {
                    Log.d(TAG, "Close button clicked");
                    stopSelf();
                });
            }

            // ★ 胜鞍分析按钮
            TextView btnSaddle = floatingView.findViewById(R.id.btn_saddle);
            if (btnSaddle != null) {
                btnSaddle.setOnClickListener(v -> {
                    saddlePanelVisible = !saddlePanelVisible;
                    if (saddlePanelVisible) {
                        saddlePanel.setVisibility(View.VISIBLE);
                        tvSaddleContent.setText("加载中...");
                        fetchSaddleAnalysis();
                    } else {
                        saddlePanel.setVisibility(View.GONE);
                    }
                });
            }

            // ★ 抓包按钮
            TextView btnSniff = floatingView.findViewById(R.id.btn_sniff);
            if (btnSniff != null) {
                btnSniff.setOnClickListener(v -> {
                    sniffPanelVisible = !sniffPanelVisible;
                    if (sniffPanelVisible) {
                        sniffPanel.setVisibility(View.VISIBLE);
                        if (!sniffEnabled) {
                            toggleSniff(true);
                        }
                        startSniffPolling();
                    } else {
                        sniffPanel.setVisibility(View.GONE);
                        stopSniffPolling();
                    }
                });
            }

            // ★ 事件推荐按钮
            TextView btnEvt = floatingView.findViewById(R.id.btn_evt);
            if (btnEvt != null) {
                btnEvt.setOnClickListener(v -> {
                    evtPanelVisible = !evtPanelVisible;
                    if (evtPanelVisible) {
                        evtPanel.setVisibility(View.VISIBLE);
                        tvEvtContent.setText("监控中...");
                        startEvtPolling();
                    } else {
                        evtPanel.setVisibility(View.GONE);
                        stopEvtPolling();
                    }
                });
            }

        } catch (Exception e) {
            Log.e(TAG, "createFloatingView failed: " + e.getMessage(), e);
            handler.postDelayed(() -> {
                try {
                    if (!isViewAdded && floatingView != null && floatingView.getParent() == null) {
                        windowManager.addView(floatingView, params);
                        isViewAdded = true;
                    }
                } catch (Exception ex) {
                    Log.e(TAG, "Retry also failed: " + ex.getMessage(), ex);
                }
            }, 2000);
        }
    }

    private void setupDrag() {
        if (floatingView == null) return;

        floatingView.setOnTouchListener((v, event) -> {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    initialX = params.x;
                    initialY = params.y;
                    initialTouchX = event.getRawX();
                    initialTouchY = event.getRawY();
                    isDragging = false;
                    return true;

                case MotionEvent.ACTION_MOVE:
                    float dx = event.getRawX() - initialTouchX;
                    float dy = event.getRawY() - initialTouchY;
                    if (Math.abs(dx) > 5 || Math.abs(dy) > 5) {
                        isDragging = true;
                        params.x = initialX + (int) dx;
                        params.y = initialY + (int) dy;
                        if (windowManager != null && floatingView != null) {
                            windowManager.updateViewLayout(floatingView, params);
                        }
                    }
                    return true;

                case MotionEvent.ACTION_UP:
                    if (!isDragging) {
                        v.performClick();
                    }
                    return true;
            }
            return false;
        });
    }

    private void registerDataReceiver() {
        dataReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String data = intent.getStringExtra(EXTRA_DATA);
                handleData(data);
            }
        };
        LocalBroadcastManager.getInstance(this).registerReceiver(
                dataReceiver, new IntentFilter(ACTION_DATA));
    }

    /** 注册剧本切换广播接收器 */
    private void registerScenarioReceiver() {
        scenarioReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();
                if (ACTION_SCENARIO.equals(action)) {
                    String scenario = intent.getStringExtra("scenario");
                    if (scenario != null) {
                        selectedScenario = scenario;
                        Log.d(TAG, "Scenario changed via broadcast: " + scenario);
                        updateScenarioLabel();
                        updateNotification("本地通信：" + HttpDataService.PORT + " | " + scenarioIdToLabel(selectedScenario) + " | 等待插件推送");
                    }
                } else if (ACTION_UPLOAD_DATA.equals(action)) {
                    finalizeCurrentSessionFromUser();
                }
            }
        };
        LocalBroadcastManager.getInstance(this).registerReceiver(
                scenarioReceiver, new IntentFilter(ACTION_SCENARIO));
        LocalBroadcastManager.getInstance(this).registerReceiver(
                scenarioReceiver, new IntentFilter(ACTION_UPLOAD_DATA));
    }

    private void finalizeCurrentSessionFromUser() {
        if (dataCollector == null) {
            Toast.makeText(this, "采集器尚未就绪", Toast.LENGTH_SHORT).show();
            return;
        }
        int finalized = dataCollector.finalizeAndUpload();
        if (finalized > 0) {
            Log.d(TAG, "Manual upload requested, observations/turns: " + finalized);
            Toast.makeText(this, "已封存" + finalized + "条回合记录，已加入上传队列",
                    Toast.LENGTH_LONG).show();
        } else {
            Toast.makeText(this, "当前没有可封存的育成数据", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        stopFallbackPoll();
        stopSniffPolling();
        stopEvtPolling();
        if (sniffEnabled) {
            toggleSniff(false);
        }
        stopHttpServer();
        if (floatingView != null && isViewAdded) {
            try {
                windowManager.removeView(floatingView);
            } catch (Exception e) {
                Log.w(TAG, "removeView failed: " + e.getMessage());
            }
        }
        if (dataReceiver != null) {
            try {
                LocalBroadcastManager.getInstance(this).unregisterReceiver(dataReceiver);
            } catch (Exception e) {
                Log.w(TAG, "unregisterReceiver failed: " + e.getMessage());
            }
        }
        if (scenarioReceiver != null) {
            try {
                LocalBroadcastManager.getInstance(this).unregisterReceiver(scenarioReceiver);
            } catch (Exception e) {
                Log.w(TAG, "unregister scenarioReceiver failed: " + e.getMessage());
            }
        }
        handler.removeCallbacksAndMessages(null);
    }
}

