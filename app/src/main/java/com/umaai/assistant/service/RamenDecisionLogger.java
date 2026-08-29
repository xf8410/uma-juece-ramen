package com.umaai.assistant.service;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 决策日志（decision_log.jsonl）— 真实对局数据收集，喂 rust/src/optimize.rs 调参。
 *
 * 每回合（hlpatch summary + Rust decision）追加一行 JSONL，单线程后台写盘：
 * <pre>
 * {"type":"turn","run":"r20260829_012233","ts":1787966553000,"turn":12,"key":"12:…",
 *  "summary":{…hlpatch 推送的 JSON 原样…},"decision":{…Rust DecisionOutput…}}
 * </pre>
 * 局末补一条 outcome（最终五维 + fans/fan_count + RMJ Pt + 回合）：
 * <pre>
 * {"type":"outcome","run":"r…","ts":…,
 *  "final":{"turn":77,"speed":…,"stamina":…,"power":…,"guts":…,"wiz":…,
 *           "vital":…,"skill_point":…,"fans":…,"checkpoint_pt":…},
 *  "config":{"uma_id":102601,"cards":[302424,…]}}
 * </pre>
 *
 * 收尾时机（每局恰好一条 outcome，先到先写，幂等）：
 * - 下一局 turn<=1 出现 → 用上一局「最后一条 summary」收尾（结算画面若可见即最终值）
 * - 终盘（turn>=77）后 summary 出现 fans/fan_count 字段（结算画面签名）→ 立即收尾
 * - 服务 onDestroy（flush）
 *
 * 设计目标：离线工具按 run 分组 —— summary 喂 Rust reconcile 重放重建、decision
 * 对照模拟器策略分、outcome 作回归目标，校准 rust/strategy_optimized.json。
 * （fans 键名对齐协议 chara_info.fans；fan_count 为兼容别名，两键同值输出）
 *
 * 文件位置：getFilesDir()/decision_log.jsonl；超 4MB 轮转为 .1（只留一代）。
 * 拉取：HttpDataService GET /decision_log（adb forward tcp:18766 后 curl）。
 *
 * 纯 Java（无 android.* 依赖），可 JVM 单测；写盘失败静默（不影响浮窗）。
 */
public final class RamenDecisionLogger {
    private static final long MAX_BYTES = 4L * 1024 * 1024;
    /** 拉面杯最后一回合：3 年 × 24 回合 + 超级拉面 5 回合（协议 CONFIRMED） */
    private static final int FINAL_TURN = 77;

    private static final Object LOCK = new Object();
    private static final ExecutorService IO = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "RamenDecisionLog");
        t.setDaemon(true);
        return t;
    });

    private static File logFile;
    private static int umaId;
    private static int[] cards = new int[0];
    private static String runId;
    private static boolean outcomeWritten;
    private static String lastTurnKey;
    private static JSONObject lastSummary;

    private RamenDecisionLogger() {}

    /** 服务启动时初始化；重复调用会重置 run 生命周期（视为新服务会话）。 */
    public static void init(File filesDir, int configUmaId, int[] configCards) {
        synchronized (LOCK) {
            logFile = filesDir == null ? null : new File(filesDir, "decision_log.jsonl");
            umaId = configUmaId;
            cards = configCards == null ? new int[0] : configCards.clone();
            runId = null;
            outcomeWritten = false;
            lastTurnKey = null;
            lastSummary = null;
        }
    }

    /**
     * 每条拉面杯 summary 进来时调用（render 的 isRamen 分支）。
     * 只做 run 生命周期管理：开局/中途入局/终盘收尾，本身不写文件。
     */
    public static void onSummary(JSONObject summary) {
        if (summary == null) return;
        try {
            int turn = summary.optInt("turn", -1);
            synchronized (LOCK) {
                if (turn == 1 || turn == 0) {
                    // 新的一局开始（hlpatch 1-based 直读；0 视作同义首回合）
                    // → 上一局用「最后一条 summary」收尾，再开新 run
                    flushOutcomeLocked();
                    startRunLocked();
                } else if (runId == null) {
                    // 浮窗中途启动（装好时已在局中）：从当前回合起记录
                    startRunLocked();
                }
                lastSummary = summary;
                if (!outcomeWritten && runId != null && turn >= FINAL_TURN && hasFans(summary)) {
                    // 结算画面签名（fans/fan_count 出现）→ 立即收尾
                    flushOutcomeLocked();
                }
            }
        } catch (Exception ignored) { }
    }

    /**
     * 搜索成功拿到 decision 时调用（renderSearchResult）。
     * 同一 summary 重复渲染（轮询/搜索完成回调）按 key 去重，一回合一行。
     */
    public static void onDecision(JSONObject summary, JSONObject decision, String dedupKey) {
        if (decision == null || dedupKey == null) return;
        try {
            synchronized (LOCK) {
                if (dedupKey.equals(lastTurnKey)) return;
                if (runId == null) startRunLocked();
                lastTurnKey = dedupKey;

                JSONObject line = new JSONObject();
                line.put("type", "turn");
                line.put("run", runId);
                line.put("ts", System.currentTimeMillis());
                if (summary != null) {
                    line.put("turn", summary.optInt("turn", -1));
                    line.put("summary", summary);
                }
                line.put("key", dedupKey);
                line.put("decision", decision);
                enqueueLocked(line);
            }
        } catch (Exception ignored) { }
    }

    /** 服务销毁时收尾当前 run（幂等，重复调用不产生第二条 outcome）。 */
    public static void flush() {
        synchronized (LOCK) {
            flushOutcomeLocked();
        }
    }

    /** HttpDataService GET /decision_log：返回日志全文（无文件/为空返回空串）。 */
    public static String readLog() {
        synchronized (LOCK) {
            if (logFile == null || !logFile.exists()) return "";
            try (BufferedReader r = new BufferedReader(new FileReader(logFile))) {
                StringBuilder b = new StringBuilder();
                char[] buf = new char[8192];
                int n;
                while ((n = r.read(buf)) != -1) b.append(buf, 0, n);
                return b.toString();
            } catch (IOException e) {
                return "";
            }
        }
    }

    // ── 内部 ──────────────────────────────────────────────────────────

    private static void startRunLocked() {
        String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        runId = "r" + stamp;
        outcomeWritten = false;
        lastTurnKey = null;
    }

    private static void flushOutcomeLocked() {
        if (runId == null || outcomeWritten || lastSummary == null) return;
        outcomeWritten = true;
        try {
            JSONObject line = new JSONObject();
            line.put("type", "outcome");
            line.put("run", runId);
            line.put("ts", System.currentTimeMillis());
            line.put("final", finalState(lastSummary));
            JSONObject config = new JSONObject();
            config.put("uma_id", umaId);
            JSONArray arr = new JSONArray();
            for (int c : cards) arr.put(c);
            config.put("cards", arr);
            line.put("config", config);
            enqueueLocked(line);
        } catch (Exception ignored) { }
    }

    /** 最终状态投影：五维 + 体力 + 技能点 + fans/fan_count + RMJ Pt + 回合。 */
    private static JSONObject finalState(JSONObject summary) throws Exception {
        JSONObject out = new JSONObject();
        JSONObject chara = summary.optJSONObject("chara");
        if (chara == null) chara = summary.optJSONObject("stats");
        if (chara == null) chara = new JSONObject();
        out.put("turn", summary.optInt("turn", -1));
        out.put("speed", chara.optInt("speed"));
        out.put("stamina", chara.optInt("stamina"));
        out.put("power", chara.optInt("power"));
        out.put("guts", chara.optInt("guts"));
        out.put("wiz", chara.optInt("wiz"));
        if (chara.has("vital")) out.put("vital", chara.optInt("vital"));
        if (chara.has("max_vital")) out.put("max_vital", chara.optInt("max_vital"));
        if (chara.has("skill_point")) out.put("skill_point", chara.optInt("skill_point"));
        // 粉丝数：chara_info.fans（协议键名），兼容 fan_count 别名；两键同值输出
        long fans = -1;
        if (chara.has("fans")) fans = chara.optLong("fans", -1);
        else if (chara.has("fan_count")) fans = chara.optLong("fan_count", -1);
        else if (summary.has("fans")) fans = summary.optLong("fans", -1);
        else if (summary.has("fan_count")) fans = summary.optLong("fan_count", -1);
        if (fans >= 0) {
            out.put("fans", fans);
            out.put("fan_count", fans);
        }
        JSONObject ramen = summary.optJSONObject("ramen");
        if (ramen != null && ramen.has("checkpoint_pt")) {
            out.put("checkpoint_pt", ramen.optInt("checkpoint_pt", -1));
        }
        return out;
    }

    private static boolean hasFans(JSONObject summary) {
        JSONObject chara = summary.optJSONObject("chara");
        if (chara == null) chara = summary.optJSONObject("stats");
        return (chara != null && (chara.has("fans") || chara.has("fan_count")))
                || summary.has("fans") || summary.has("fan_count");
    }

    private static void enqueueLocked(JSONObject line) {
        final String text = line.toString();
        IO.execute(() -> appendLine(text));
    }

    private static void appendLine(String text) {
        synchronized (LOCK) {
            if (logFile == null) return;
            try {
                if (logFile.exists() && logFile.length() > MAX_BYTES) {
                    File old = new File(logFile.getParentFile(), logFile.getName() + ".1");
                    if (old.exists()) old.delete();
                    logFile.renameTo(old);
                }
                try (PrintWriter w = new PrintWriter(new FileWriter(logFile, true))) {
                    w.println(text);
                }
            } catch (IOException ignored) { }
        }
    }

    // ── 测试辅助（包私有） ────────────────────────────────────────────

    /** 等待后台写盘队列清空（单线程队列尾部放哨兵任务）。 */
    static void awaitIdle() {
        try {
            IO.submit(() -> { }).get(10, TimeUnit.SECONDS);
        } catch (Exception ignored) { }
    }
}
