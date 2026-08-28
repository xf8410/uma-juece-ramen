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
 * 拉面杯浮窗服务（v0.2.0+）。
 *
 * 通信架构：
 * - hlpatch so 推送 JSON → HttpDataService(:18766) 或轮询(:18765/summary)
 * - JSON 透传给 UmaNativeBridge.search() → Rust reconcile + MCTS 搜索
 * - 返回结构化 JSON（view + decision + reconcile）→ 渲染浮窗
 *
 * hlpatch v3.27.22 JSON 格式：
 * - chara 对象（speed/stamina/power/guts/wiz/vital/max_vital/motivation/skill_point/scenario_id）
 * - month(1-12) + half(1-2)，无 turn 字段（Rust 侧 reconcile 推导）
 * - 可选 ramen 对象（sozai/feeling/acquisition_gauges/checkpoint_pt）
 */
public final class FloatingWindowService extends Service implements HttpDataService.OnDataListener {
    private static final String TAG = "RamenFloat";
    private static final String CHANNEL = "ramen_overlay";
    private static final int NOTIFICATION_ID = 1401;
    private static final long STALE_MS = 5000;
    private static final int DEFAULT_UMA_ID = 102601;
    private static final int[] DEFAULT_CARDS = {302424, 302894, 303044, 302924, 303024, 303054};

    private final Handler main = new Handler(Looper.getMainLooper());
    private final TrainingEvaluator evaluator = new TrainingEvaluator();
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
        // 回合显示：优先用 Rust 返回的 view.turn，否则从 month/half 显示
        int turn = s.optInt("turn", -1);
        int month = s.optInt("month", -1);
        int half = s.optInt("half", -1);

        String turnText;
        if (turn > 0) {
            turnText = "第" + turn + "回合";
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

        // 训练数据（如果有）
        trainingsView.setText(renderTrainings(s.optJSONArray("trainings")));

        // 来源 + AI 状态
        String aiStatus = UmaNativeBridge.isAvailable() ? "AI就绪" : "安全兜底";
        sourceView.setText(source + " · " + aiStatus);

        // 通知栏
        NotificationManager m = getSystemService(NotificationManager.class);
        if (m != null) m.notify(NOTIFICATION_ID, notification("拉面杯 " + turnText));
    }

    private void renderSearchResult(JSONObject s) {
        // 安全兜底
        String fallback = evaluator.recommend(s);

        if (searchRunning) {
            recommendView.setText(fallback + " · 模拟搜索中...");
            return;
        }

        if (lastSearchResult != null) {
            // Rust 返回的结构化 JSON
            boolean ok = lastSearchResult.optBoolean("ok", false);
            if (ok) {
                JSONObject decision = lastSearchResult.optJSONObject("decision");
                if (decision != null) {
                    String action = decision.optString("action_display", "?");
                    int searchN = decision.optInt("search_n", 0);
                    long elapsed = decision.optLong("elapsed_ms", 0);
                    String source = decision.optString("source", "mcts");

                    // 显示校正警告（如果有）
                    JSONObject reconcile = lastSearchResult.optJSONObject("reconcile");
                    String warningText = "";
                    if (reconcile != null) {
                        JSONArray warnings = reconcile.optJSONArray("warnings");
                        if (warnings != null && warnings.length() > 0) {
                            warningText = " ⚠" + warnings.length() + "警告";
                        }
                    }

                    recommendView.setText(
                        "模拟建议：" + action +
                        (searchN > 0 ? "（" + searchN + "次/" + elapsed + "ms）" : "") +
                        warningText
                    );
                    return;
                }
            } else {
                String error = lastSearchResult.optString("error", "");
                if (!error.isEmpty()) {
                    recommendView.setText("搜索失败：" + error + "\n兜底：" + fallback);
                    return;
                }
            }
        }

        // 无搜索结果，用兜底
        recommendView.setText(fallback);
    }

    // ── 搜索触发 ──────────────────────────────────────────────────────

    /**
     * 搜索去重键：用 month+half+vital+motivation+sozai 组合。
     * 不再用 turn（hlpatch 不发）和 sozai 字符串（格式不稳定）。
     */
    static String searchKey(JSONObject s) {
        JSONObject chara = s.optJSONObject("chara");
        JSONObject stats = s.optJSONObject("stats");
        JSONObject c = chara != null ? chara : stats;
        JSONObject r = s.optJSONObject("ramen");

        return s.optInt("month") + ":" + s.optInt("half") + ":" +
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
        recommendView.setText(evaluator.recommend(s) + " · 模拟搜索中...");

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

    // ── 训练数据渲染 ──────────────────────────────────────────────────

    private String renderTrainings(JSONArray a) {
        if (a == null) return "训练数据：无";
        StringBuilder o = new StringBuilder();
        for (int i = 0; i < a.length(); i++) {
            JSONObject t = a.optJSONObject(i);
            if (t == null) continue;
            JSONObject g = t.optJSONObject("gains");
            if (o.length() > 0) o.append("  |  ");
            o.append(translate(t.optString("name", "?"))).append(':');
            if (g != null) {
                gain(o, "速", g.optInt("Speed"));
                gain(o, "耐", g.optInt("Stamina"));
                gain(o, "力", g.optInt("Power"));
                gain(o, "根", g.optInt("Guts"));
                gain(o, "智", g.optInt("Wiz"));
                gain(o, "Pt", g.optInt("SkillPt"));
                int hp = g.optInt("HP");
                if (hp != 0) o.append(" HP").append(hp > 0 ? "+" : "").append(hp);
            }
            int f = t.optInt("failure_rate");
            if (f > 0) o.append(" 失败").append(f).append('%');
            int h = t.optInt("heads");
            if (h > 0) o.append(" 头").append(h);
        }
        return o.toString();
    }

    private static void gain(StringBuilder o, String n, int v) {
        if (v != 0) o.append(' ').append(n).append(v > 0 ? "+" : "").append(v);
    }

    private static String translate(String n) {
        switch (n.toLowerCase()) {
            case "speed": return "速";
            case "stamina": return "耐";
            case "power": return "力";
            case "guts": return "根";
            case "wisdom":
            case "wiz": return "智";
            case "rest": return "休息";
            case "outgoing":
            case "outing": return "外出";
            default: return n;
        }
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
