package com.umaai.assistant.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;
import androidx.core.app.NotificationCompat;
import com.umaai.assistant.R;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;

/**
 * 拉面杯浮窗服务（v0.3.5+）。
 *
 * 通信架构：
 * - hlpatch so 推送 JSON → HttpDataService(:18766) 或轮询(:18765/summary)
 * - JSON 透传给 UmaNativeBridge.search() → Rust reconcile + 重放重建 + MCTS 搜索
 * - 返回结构化 JSON（view + decision + training_decision + reconcile）→ 渲染浮窗
 * - 决策日志：每回合 summary+decision 一行、局末 outcome 一行追加到
 *   decision_log.jsonl（RamenDecisionLogger，GET /decision_log 可拉取），
 *   目标是喂 rust/src/optimize.rs 用真实对局校准 strategy_optimized.json
 *
 * 显示内容（对齐 PC 黑板，EtherealAO 版）：
 * - 主建议 + 搜索规模（建议：吃面/函馆-耐（mean 66972 · 4096次/12.7s））
 * - 候选条形图（BoardChartsView：每候选一行 标签+比例条+相对差值，选中项绿色，
 *   喂 decision 的 candidate_displays/candidate_scores/action_index）
 * - 训练建议（训练建议：速度训练（mean X · 64次/…ms），来自 Rust Train 阶段补搜）
 * - 训练明细行（速: 速46 力14 27pt 体力-25 失败10% 头3光2）
 * - 运气行（v0.3.4：运:总±X 回±Y，见下）
 *
 * v0.3.5 变更：
 * - 紧凑模式开关：手机屏不够放全部信息——面板右上角新增一枚可点小按钮
 *   （独立悬浮窗，主面板保持不可触碰不挡游戏）。「简」= 收起状态/训练明细/
 *   条形图/运气/⚠警告/训练建议，只留回合行+主建议行；「详」= 全部展开。
 *   状态持久化（SharedPreferences），重开服务保持
 * - 运气/百分比格式改 Locale.US，避免个别系统区域设置产出本地化数字
 *
 * v0.3.4 变更：
 * - ⚠ 校正警告显示原文（最多2条/每条40字），不再是干巴巴的条数——
 *   用户反馈看不懂 ⚠1 是什么；同时 Rust v0.3.2 的「人头注入」摘要
 *   也会出现在警告里，可直接核对注入是否生效
 * - 运气追踪：mean 是「模拟到育成结束的期望总分」——第一回合的 mean
 *   记为本局基准；运气:总 = 当前 mean − 基准（整局相对开局的漂移）；
 *   运气:回 = 当前 mean − 上一回合 mean（本回合的增益/波动）。
 *   中途接入（错过第1回合）时以最早观测为近似基准
 *
 * v0.3.3 变更：
 * - 接入 RamenDecisionLogger（数据收集）：日志写盘在后台单线程，不影响渲染
 *
 * v0.3.2 变更：
 * - 候选差值从文字行（「#2 吃面/东京-智 -731」）改为 tv_turn 下方的候选条形图
 *   （BoardChartsView，Canvas 绘制，无候选/评分全 0 时自动隐藏不占空间）
 *
 * v0.3.1 变更：
 * - 删除 Java 端「训练兜底」（TrainingEvaluator）：小黑板已有 hlpatch 真实训练
 *   明细，Java 端再算一遍纯属浪费算力，且质量远低于模拟器搜索
 * - hlpatch 没发 trainings（非行动画面）时，Rust 在 Train 阶段补搜并返回
 *   training_decision，浮窗显示「训练建议：…」；trainings 非空时不显示该行
 * - Rust 侧 v0.3.1 起先重放重建（断线重连）再搜索，非行动画面也能给出
 *   有依据的吃面/训练建议
 *
 * 回合口径：
 * - hlpatch 的 turn 与游戏 UI「第N回合」一致（1-based），直读时标注「直读」
 * - AI（umaai-rs）内部回合从 0 开始，浮窗同时显示 AI 内部值便于核对
 * - 旧版 hlpatch 无 turn 字段时回退 month/half 显示，Rust 侧再推导
 *
 * hlpatch v3.27.22 JSON 格式：
 * - chara 对象（speed/stamina/power/guts/wiz/vital/max_vital/motivation/skill_point/scenario_id）
 * - month(1-12) + half(1-2)，v3.27.17+ 补发 turn
 * - 可选 ramen 对象（sozai/feeling/acquisition_gauges/checkpoint_pt）
 * - 顶层 trainings（五项训练收益/失败率/人头/发光，仅行动画面非空）
 */
public final class FloatingWindowService extends Service implements HttpDataService.OnDataListener {
    private static final String TAG = "RamenFloat";
    private static final String CHANNEL = "ramen_overlay";
    private static final String PREFS = "ramen_overlay";
    private static final String PREF_COMPACT = "compact";
    private static final int NOTIFICATION_ID = 1401;
    private static final long STALE_MS = 5000;
    private static final int DEFAULT_UMA_ID = 102601;
    private static final int[] DEFAULT_CARDS = {302424, 302894, 303044, 302924, 303024, 303054};

    private final Handler main = new Handler(Looper.getMainLooper());
    private WindowManager windowManager;
    private View panel;
    private TextView turnView, recommendView, statusView, ramenView, trainingsView, sourceView;
    private BoardChartsView chartsView;
    private HttpDataService server;
    private volatile boolean polling, searchRunning;
    private volatile long lastDataAt;
    private volatile String lastSearchKey = "";
    private volatile JSONObject lastSearchResult, pendingSummary, lastSummary;
    private Thread searchThread;

    // 紧凑模式（v0.3.5）：true = 只显示回合行+主建议行
    private volatile boolean compactMode;
    private TextView toggleBtn;

    // 运气追踪（v0.3.4）：
    // - firstMean：第一回合（或新一局最早观测）的 mean，= 总运气基准
    // - prevMean / prevLuckTurn：上一条新决策的 mean 与回合，= 当回合运气基准
    // - lastLuckKey：去重（同回合重复渲染不重复计算）
    private volatile double firstMean = Double.NaN;
    private volatile double prevMean = Double.NaN;
    private volatile int prevLuckTurn = -1;
    private volatile String lastLuckKey = "";

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        startForeground(NOTIFICATION_ID, notification("等待拉面杯数据"));
        compactMode = getSharedPreferences(PREFS, MODE_PRIVATE)
                .getBoolean(PREF_COMPACT, false);
        createPanel();
        createToggleButton();
        // 决策日志：真实对局数据收集（outcome 的 config 回显本服务固定搜索配置）
        RamenDecisionLogger.init(getFilesDir(), DEFAULT_UMA_ID, DEFAULT_CARDS);
        try {
            server = new HttpDataService(this);
            server.startServer();
        } catch (Exception e) {
            Log.e(TAG, "HTTP server start failed", e);
            stopSelf();
            return;
        }
        startPolling();
        initNativeSearch();
    }

    @Override
    public IBinder onBind(Intent i) { return null; }

    @Override
    public int onStartCommand(Intent i, int f, int id) { return START_STICKY; }

    @Override
    public void onDestroy() {
        polling = false;
        if (server != null) server.stopServer();
        if (windowManager != null) {
            if (panel != null) windowManager.removeView(panel);
            if (toggleBtn != null) windowManager.removeView(toggleBtn);
        }
        RamenDecisionLogger.flush(); // 收尾当前 run（幂等）
        super.onDestroy();
    }

    @Override
    public void onDataReceived(String data) { consume(data, "实时"); }

    // ── 数据接收 ──────────────────────────────────────────────────────

    private void consume(String data, String source) {
        if (data == null || data.isEmpty()) return;
        try {
            JSONObject s = new JSONObject(data);
            // hlpatch v3.27.22: 有 chara 对象或 stats 对象都接受
            if (!s.has("chara") && !s.has("stats")) return;
            lastDataAt = System.currentTimeMillis();
            lastSummary = s;
            main.post(() -> render(s, source));
        } catch (Exception ignored) { }
    }

    // ── 渲染 ──────────────────────────────────────────────────────────

    private void render(JSONObject s, String source) {
        // 判断是否拉面杯场景
        JSONObject chara = s.optJSONObject("chara");
        JSONObject stats = s.optJSONObject("stats");
        JSONObject charaOrStats = chara != null ? chara : stats;

        if (charaOrStats == null) return;

        int scenarioId = charaOrStats.optInt("scenario_id", -1);
        String scenario = s.optString("scenario", "");
        // scenario_id=14 是拉面杯；或者 scenario 字段为 "Ramen"
        boolean isRamen = scenarioId == 14 || "Ramen".equals(scenario);

        if (!isRamen) {
            turnView.setText("非拉面杯");
            recommendView.setText("此版本仅支持拉面杯");
            statusView.setText("");
            ramenView.setText("");
            trainingsView.setText("");
            chartsView.clear();
            return;
        }

        // 渲染基础状态
        renderBasicState(s, charaOrStats, source);

        // 决策日志 run 生命周期（开局/中途入局/终盘收尾），先于搜索结果处理
        RamenDecisionLogger.onSummary(s);

        // 渲染搜索结果（如果有）
        renderSearchResult(s);

        // 触发搜索（如果回合变化且 native 可用）
        String key = searchKey(s);
        if (!key.equals(lastSearchKey) && !searchRunning && UmaNativeBridge.isAvailable()) {
            triggerSearch(s, key);
        }
    }

    private void renderBasicState(JSONObject s, JSONObject chara, String source) {
        // 回合显示：
        // - hlpatch 直读 turn（游戏 UI 第N回合，1-based）→ 标「直读」并附 AI 内部值（0-based）
        // - 旧版 hlpatch 无 turn → 显示 month/half，Rust 侧推导
        int turn = s.has("turn") ? s.optInt("turn", -1) : -1;
        int month = s.optInt("month", -1);
        int half = s.optInt("half", -1);

        String turnText;
        if (turn > 0) {
            turnText = "第" + turn + "回合 直读(AI:" + (turn - 1) + ")";
        } else if (turn == 0) {
            turnText = "第1回合 直读(AI:0)";
        } else if (month > 0 && half > 0) {
            turnText = month + "月" + (half == 1 ? "前" : "后");
        } else {
            turnText = "拉面杯";
        }

        // 拉面杯状态行
        JSONObject ramen = s.optJSONObject("ramen");
        String ramenText = "";
        if (ramen != null) {
            StringBuilder rb = new StringBuilder();
            int pt = ramen.optInt("checkpoint_pt", -1);
            if (pt >= 0) rb.append("RMJ Pt:").append(pt).append("  ");
            JSONArray sozai = ramen.optJSONArray("sozai");
            if (sozai != null && sozai.length() >= 3) {
                rb.append("诀窍:").append(sozai.optInt(0)).append("/")
                  .append(sozai.optInt(1)).append("/")
                  .append(sozai.optInt(2));
            }
            int special = ramen.optInt("special_feeling_num", -1);
            if (special >= 0) rb.append("  隐藏:").append(special);
            ramenText = rb.toString();
        }

        turnView.setText(turnText + (ramenText.isEmpty() ? "" : " · " + ramenText));

        // 五维 + 体力 + 干劲
        int spd = chara.optInt("speed");
        int sta = chara.optInt("stamina");
        int pow = chara.optInt("power");
        int gut = chara.optInt("guts");
        int wiz = chara.optInt("wiz");
        int total = spd + sta + pow + gut + wiz;
        int vital = chara.optInt("vital", -1);
        int maxVital = chara.optInt("max_vital", -1);
        int skillPt = chara.optInt("skill_point", -1);
        String mot = chara.optString("motivation", "?");

        statusView.setText(
            "速" + spd + " 耐" + sta + " 力" + pow + " 根" + gut + " 智" + wiz +
            "  总" + total +
            (skillPt >= 0 ? " Pt" + skillPt : "") +
            (vital >= 0 ? "\n体力" + vital + "/" + (maxVital > 0 ? maxVital : "?") : "") +
            " 干劲" + mot
        );

        // 训练数据（hlpatch /summary 顶层 trainings，仅行动画面非空）
        JSONArray trainings = s.optJSONArray("trainings");
        String detail = RamenBoardText.trainingLines(trainings);
        trainingsView.setText(detail.isEmpty() ? "训练数据：无" : detail);

        // 紧凑模式（v0.3.5）：状态行/训练明细收起，只留回合行+主建议行+来源行
        statusView.setVisibility(compactMode ? View.GONE : View.VISIBLE);
        trainingsView.setVisibility(compactMode ? View.GONE : View.VISIBLE);

        // 来源 + AI 状态
        String aiStatus = UmaNativeBridge.isAvailable() ? "AI就绪" : "安全兜底";
        sourceView.setText(source + " · " + aiStatus);

        // 通知栏
        NotificationManager m = getSystemService(NotificationManager.class);
        if (m != null) m.notify(NOTIFICATION_ID, notification("拉面杯 " + turnText));
    }

    private void renderSearchResult(JSONObject s) {
        if (searchRunning) {
            recommendView.setText("模拟搜索中…");
            chartsView.clear();
            return;
        }

        JSONObject result = lastSearchResult;
        if (result != null && result.optBoolean("ok", false)) {
            JSONObject decision = result.optJSONObject("decision");
            if (decision != null) {
                StringBuilder b = new StringBuilder(RamenBoardText.decisionLine(decision));

                // 运气追踪（v0.3.4）：
                // - mean（decision.score）= 模拟到育成结束的期望总分
                // - 总运气 = 当前 mean − 第一回合 mean（整局相对开局的漂移）
                // - 当回合运气 = 当前 mean − 上一回合 mean（本回合的增益/波动）
                // 新一局判定：回合回退（turn 变小）；第1回合强制重设基准；
                // 中途接入（错过第1回合）以最早观测为近似基准
                double mean = decision.optDouble("score", 0.0);
                int turnNow = s.has("turn") ? s.optInt("turn", -1) : -1;
                String key = searchKey(s);
                if (mean > 0 && turnNow > 0 && !key.equals(lastLuckKey)) {
                    lastLuckKey = key;
                    if (turnNow == 1 || prevLuckTurn < 0 || turnNow < prevLuckTurn) {
                        firstMean = mean;
                    }
                    StringBuilder luck = new StringBuilder();
                    if (!Double.isNaN(firstMean)) {
                        luck.append(" 总").append(String.format(Locale.US, "%+.0f", mean - firstMean));
                    }
                    if (!Double.isNaN(prevMean) && turnNow == prevLuckTurn + 1) {
                        luck.append(" 回").append(String.format(Locale.US, "%+.0f", mean - prevMean));
                    }
                    if (luck.length() > 0) {
                        b.append("\n运气").append(luck);
                    }
                    prevMean = mean;
                    prevLuckTurn = turnNow;
                }

                // 候选差值（PC 黑板「决策理由」图形版）：喂 BoardChartsView 画横条，
                // 标签走 RamenBoardText.translate 中文化，选中项绿色；
                // 紧凑模式不画条形图（省空间）
                if (compactMode) {
                    chartsView.clear();
                } else {
                    chartsView.setCandidates(
                            decision.optJSONArray("candidate_displays"),
                            decision.optJSONArray("candidate_scores"),
                            decision.optInt("action_index", 0));
                }

                // 决策日志：本回合 summary+decision 一行（按 searchKey 去重，一回合一行）
                RamenDecisionLogger.onDecision(s, decision, searchKey(s));

                // 训练建议：hlpatch 没发 trainings（非行动画面）时由 Rust 在
                // Train 阶段补搜返回；trainings 非空时不显示（黑板已有真实明细）
                if (!compactMode) {
                    JSONObject td = result.optJSONObject("training_decision");
                    if (td != null) {
                        // decisionLine 输出「建议：X（…）」，前拼「训练」→「训练建议：X（…）」
                        b.append("\n训练").append(RamenBoardText.decisionLine(td));
                    }
                }

                // 校正警告（v0.3.4）：显示原文而不是干巴巴的条数——用户反馈
                // 看不懂 ⚠1 是什么。常见为良性近似（feeling_slot 从 remaining
                // 近似转换）；Rust v0.3.2 的「人头注入」摘要也在这里，可直接
                // 核对注入是否生效。紧凑模式收起。
                if (!compactMode) {
                    JSONObject reconcile = result.optJSONObject("reconcile");
                    JSONArray warnings = reconcile == null ? null : reconcile.optJSONArray("warnings");
                    if (warnings != null && warnings.length() > 0) {
                        StringBuilder wb = new StringBuilder("\n⚠");
                        int show = Math.min(warnings.length(), 2);
                        for (int i = 0; i < show; i++) {
                            if (i > 0) wb.append("；");
                            String w = warnings.optString(i);
                            if (w.length() > 40) w = w.substring(0, 40) + "…";
                            wb.append(w);
                        }
                        if (warnings.length() > 2) {
                            wb.append(" 等").append(warnings.length()).append("条");
                        }
                        b.append(wb);
                    }
                }

                recommendView.setText(b.toString());
                return;
            }
        }

        chartsView.clear();

        if (result != null && !result.optBoolean("ok", false)) {
            String error = result.optString("error", "");
            if (!error.isEmpty()) {
                recommendView.setText("搜索失败：" + error);
                return;
            }
        }

        // 无搜索结果
        recommendView.setText("等待搜索…");
    }

    // ── 搜索触发 ──────────────────────────────────────────────────────

    /**
     * 搜索去重键：直读 turn（若有）+ month/half + vital + motivation + sozai。
     * 不再依赖 sozai 字符串（格式不稳定）以外的推断值。
     */
    static String searchKey(JSONObject s) {
        JSONObject chara = s.optJSONObject("chara");
        JSONObject stats = s.optJSONObject("stats");
        JSONObject c = chara != null ? chara : stats;
        JSONObject r = s.optJSONObject("ramen");

        int turn = s.has("turn") ? s.optInt("turn", -1) : -1;
        return turn + ":" + s.optInt("month") + ":" + s.optInt("half") + ":" +
               (c == null ? "" : c.optInt("vital") + ":" + c.optString("motivation")) + ":" +
               (r == null ? "" : r.optInt("checkpoint_pt") + ":" + r.optString("sozai"));
    }

    private void initNativeSearch() {
        new Thread(() -> {
            if (UmaNativeBridge.init(this)) {
                main.post(() -> {
                    if (sourceView != null) {
                        sourceView.setText(sourceView.getText().toString().replace("安全兜底", "AI就绪"));
                    }
                });
            }
        }, "NativeInit").start();
    }

    private void triggerSearch(JSONObject s, String key) {
        searchRunning = true;
        lastSearchKey = key;
        pendingSummary = s;
        recommendView.setText("模拟搜索中...");

        if (searchThread != null && searchThread.isAlive()) return;

        searchThread = new Thread(() -> {
            JSONObject snap = pendingSummary;
            JSONObject result = snap == null ? null :
                UmaNativeBridge.search(snap, DEFAULT_UMA_ID, DEFAULT_CARDS, 0);
            lastSearchResult = result;
            searchRunning = false;
            main.post(() -> {
                if (snap != null) render(snap, "搜索完成");
            });
        }, "NativeSearch");
        searchThread.setDaemon(true);
        searchThread.start();
    }

    // ── 浮窗 + 通信 ───────────────────────────────────────────────────

    private void createPanel() {
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        panel = LayoutInflater.from(this).inflate(R.layout.floating_window, null);
        int type = Build.VERSION.SDK_INT >= 26 ?
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY :
            WindowManager.LayoutParams.TYPE_PHONE;
        int flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE |
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN;
        WindowManager.LayoutParams p = new WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type, flags, PixelFormat.TRANSLUCENT);
        p.gravity = Gravity.TOP | Gravity.START;
        p.x = 0;
        p.y = 120;
        turnView = panel.findViewById(R.id.tv_turn);
        chartsView = panel.findViewById(R.id.chart_candidates);
        recommendView = panel.findViewById(R.id.tv_recommend);
        statusView = panel.findViewById(R.id.tv_status);
        ramenView = panel.findViewById(R.id.tv_ramen);
        trainingsView = panel.findViewById(R.id.tv_trainings);
        sourceView = panel.findViewById(R.id.tv_source);
        windowManager.addView(panel, p);
    }

    /**
     * 紧凑模式开关（v0.3.5）。
     *
     * 主面板带 FLAG_NOT_TOUCHABLE（不挡游戏），无法直接放按钮——所以用一枚
     * 独立的小悬浮窗（仅 FLAG_NOT_FOCUSABLE，可点）贴在面板右上角。
     * 「简」= 收起详情；「详」= 展开详情。状态持久化。
     */
    private void createToggleButton() {
        toggleBtn = new TextView(this);
        toggleBtn.setText(compactMode ? "详" : "简");
        toggleBtn.setTextSize(11);
        toggleBtn.setTextColor(0xFF202020);
        toggleBtn.setBackgroundColor(0xCCEEEEEE);
        toggleBtn.setPadding(dp(7), dp(2), dp(7), dp(2));
        int type = Build.VERSION.SDK_INT >= 26 ?
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY :
            WindowManager.LayoutParams.TYPE_PHONE;
        WindowManager.LayoutParams p = new WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT);
        p.gravity = Gravity.TOP | Gravity.END;
        p.x = dp(6);
        p.y = dp(122);
        toggleBtn.setOnClickListener(v -> {
            compactMode = !compactMode;
            getSharedPreferences(PREFS, MODE_PRIVATE)
                    .edit().putBoolean(PREF_COMPACT, compactMode).apply();
            toggleBtn.setText(compactMode ? "详" : "简");
            JSONObject snap = lastSummary;
            if (snap != null) {
                main.post(() -> render(snap, "视图切换"));
            }
        });
        windowManager.addView(toggleBtn, p);
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private void startPolling() {
        polling = true;
        Thread t = new Thread(() -> {
            while (polling) {
                if (System.currentTimeMillis() - lastDataAt > STALE_MS) {
                    String b = get("http://127.0.0.1:18765/summary");
                    if (b != null) consume(b, "轮询");
                }
                try {
                    Thread.sleep(2000);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }, "RamenPoll");
        t.setDaemon(true);
        t.start();
    }

    private static String get(String a) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(a).openConnection();
            c.setConnectTimeout(1500);
            c.setReadTimeout(2000);
            if (c.getResponseCode() != 200) return null;
            BufferedReader r = new BufferedReader(
                new InputStreamReader(c.getInputStream(), "UTF-8"));
            StringBuilder b = new StringBuilder();
            String l;
            while ((l = r.readLine()) != null) b.append(l);
            r.close();
            return b.toString();
        } catch (Exception e) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager m = getSystemService(NotificationManager.class);
            if (m != null) {
                m.createNotificationChannel(new NotificationChannel(
                    CHANNEL, "拉面杯浮窗", NotificationManager.IMPORTANCE_LOW));
            }
        }
    }

    private Notification notification(String t) {
        return new NotificationCompat.Builder(this, CHANNEL)
            .setContentTitle("拉面杯决策浮窗")
            .setContentText(t)
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setOngoing(true)
            .build();
    }
}
