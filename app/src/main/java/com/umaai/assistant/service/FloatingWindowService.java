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

/**
 * 拉面杯浮窗服务（v0.3.1+）。
 *
 * 通信架构：
 * - hlpatch so 推送 JSON → HttpDataService(:18766) 或轮询(:18765/summary)
 * - JSON 透传给 UmaNativeBridge.search() → Rust reconcile + 重放重建 + MCTS 搜索
 * - 返回结构化 JSON（view + decision + training_decision + reconcile）→ 渲染浮窗
 *
 * 显示内容（对齐 PC 黑板，EtherealAO 版）：
 * - 主建议 + 搜索规模（建议：吃面/函馆-耐（mean 66972 · 4096次/12.7s））
 * - 候选差值（决策理由：#0 不吃面 -999 ｜ #2 吃面/东京-智 -731）
 * - 训练建议（训练建议：速度训练（mean X · 64次/…ms），来自 Rust Train 阶段补搜）
 * - 训练明细行（速: 速46 力14 27pt 体力-25 失败10% 头3光2）
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
    private static final int NOTIFICATION_ID = 1401;
    private static final long STALE_MS = 5000;
    private static final int DEFAULT_UMA_ID = 102601;
    private static final int[] DEFAULT_CARDS = {302424, 302894, 303044, 302924, 303024, 303054};

    private final Handler main = new Handler(Looper.getMainLooper());
    private WindowManager windowManager;
    private View panel;
    private TextView turnView, recommendView, statusView, ramenView, trainingsView, sourceView;
    private HttpDataService server;
    private volatile boolean polling, searchRunning;
    private volatile long lastDataAt;
    private volatile String lastSearchKey = "";
    private volatile JSONObject lastSearchResult, pendingSummary;
    private Thread searchThread;

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        startForeground(NOTIFICATION_ID, notification("等待拉面杯数据"));
        createPanel();
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
        if (panel != null && windowManager != null) windowManager.removeView(panel);
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
            return;
        }

        // 渲染基础状态
        renderBasicState(s, charaOrStats, source);

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
            return;
        }

        JSONObject result = lastSearchResult;
        if (result != null && result.optBoolean("ok", false)) {
            JSONObject decision = result.optJSONObject("decision");
            if (decision != null) {
                StringBuilder b = new StringBuilder(RamenBoardText.decisionLine(decision));

                // 候选差值（PC 黑板「决策理由」：其余候选相对选中的 mean 差）
                String deltas = RamenBoardText.candidateDeltas(decision, 3);
                if (!deltas.isEmpty()) b.append('\n').append(deltas);

                // 训练建议：hlpatch 没发 trainings（非行动画面）时由 Rust 在
                // Train 阶段补搜返回；trainings 非空时不显示（黑板已有真实明细）
                JSONObject td = result.optJSONObject("training_decision");
                if (td != null) {
                    // decisionLine 输出「建议：X（…）」，前拼「训练」→「训练建议：X（…）」
                    b.append("\n训练").append(RamenBoardText.decisionLine(td));
                }

                // 校正警告数量
                JSONObject reconcile = result.optJSONObject("reconcile");
                JSONArray warnings = reconcile == null ? null : reconcile.optJSONArray("warnings");
                if (warnings != null && warnings.length() > 0) {
                    b.append(" ⚠").append(warnings.length());
                }

                recommendView.setText(b.toString());
                return;
            }
        }

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
        recommendView = panel.findViewById(R.id.tv_recommend);
        statusView = panel.findViewById(R.id.tv_status);
        ramenView = panel.findViewById(R.id.tv_ramen);
        trainingsView = panel.findViewById(R.id.tv_trainings);
        sourceView = panel.findViewById(R.id.tv_source);
        windowManager.addView(panel, p);
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
