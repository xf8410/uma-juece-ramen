package com.umaai.assistant.service;

import org.json.JSONObject;
import org.junit.Test;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * RamenDecisionLogger 的 JVM 单测：纯 Java 实现，无 Android 依赖。
 * 覆盖：回合行去重、summary+decision 结构、outcome 三收尾路径（新局开/结算 fans/flush）、
 * fans 双键输出、decision 缺省不落行、readLog 空态。
 */
public class RamenDecisionLoggerTest {

    private static File tempDir() {
        // 每个用例独立临时目录；init() 会把日志指到 dir/decision_log.jsonl
        return new File(System.getProperty("java.io.tmpdir"),
                "ramen-dl-test-" + System.nanoTime());
    }

    private static List<String> logLines() throws Exception {
        String all = RamenDecisionLogger.readLog();
        List<String> out = new ArrayList<>();
        for (String l : all.split("\n")) {
            if (!l.trim().isEmpty()) out.add(l);
        }
        return out;
    }

    private static JSONObject summary(int turn, int speed, int checkpointPt) throws Exception {
        JSONObject chara = new JSONObject();
        chara.put("speed", speed);
        chara.put("stamina", 800);
        chara.put("power", 700);
        chara.put("guts", 600);
        chara.put("wiz", 900);
        chara.put("vital", 80);
        chara.put("skill_point", 120);
        JSONObject s = new JSONObject();
        s.put("turn", turn);
        s.put("month", 6);
        s.put("half", 1);
        s.put("chara", chara);
        JSONObject ramen = new JSONObject();
        ramen.put("checkpoint_pt", checkpointPt);
        ramen.put("sozai", new int[]{2, 2, 1});
        s.put("ramen", ramen);
        return s;
    }

    private static JSONObject decision() throws Exception {
        return new JSONObject("{\"action_index\":1,\"action_display\":\"吃面/函馆-耐\"," +
                "\"score\":66972.3,\"search_n\":4096,\"elapsed_ms\":12697," +
                "\"candidate_displays\":[\"不吃面\",\"吃面/函馆-耐\"]," +
                "\"candidate_scores\":[65973,66972]}");
    }

    @Test public void turnLoggedOncePerKeyWithSummaryAndDecision() throws Exception {
        RamenDecisionLogger.init(tempDir(), 102601, new int[]{1, 2});
        JSONObject s = summary(1, 120, 0);
        RamenDecisionLogger.onSummary(s);
        String key = "1:6:1:80:絕好:0:2:2:1"; // 与 FloatingWindowService.searchKey 同构
        RamenDecisionLogger.onDecision(s, decision(), key);
        // 轮询/搜索完成回调会重复渲染同一 summary —— 按 key 去重
        RamenDecisionLogger.onDecision(s, decision(), key);
        RamenDecisionLogger.awaitIdle();

        List<String> lines = logLines();
        assertEquals(1, lines.size());
        JSONObject line = new JSONObject(lines.get(0));
        assertEquals("turn", line.getString("type"));
        assertEquals(1, line.getInt("turn"));
        assertTrue(line.getString("run").startsWith("r"));
        assertEquals(key, line.getString("key"));
        assertEquals(66972.3, line.getJSONObject("decision").getDouble("score"), 1e-9);
        assertEquals(4096, line.getJSONObject("decision").getInt("search_n"));
        assertEquals(120, line.getJSONObject("summary").getJSONObject("chara").getInt("speed"));
    }

    @Test public void outcomeWrittenWhenNextRunStarts() throws Exception {
        RamenDecisionLogger.init(tempDir(), 102601, new int[]{1, 2});
        RamenDecisionLogger.onSummary(summary(1, 100, 0));
        RamenDecisionLogger.onSummary(summary(77, 1200, 4600)); // 终盘最后一条
        RamenDecisionLogger.onSummary(summary(1, 90, 0));       // 新一局开始 → 上一局收尾
        RamenDecisionLogger.awaitIdle();

        List<String> lines = logLines();
        assertEquals(1, lines.size());
        JSONObject line = new JSONObject(lines.get(0));
        assertEquals("outcome", line.getString("type"));
        JSONObject fin = line.getJSONObject("final");
        assertEquals(77, fin.getInt("turn"));
        assertEquals(1200, fin.getInt("speed"));
        assertEquals(4600, fin.getInt("checkpoint_pt"));
        assertEquals(800, fin.getInt("stamina"));
        // config 回显搜索配置
        assertEquals(102601, line.getJSONObject("config").getInt("uma_id"));
        assertEquals(2, line.getJSONObject("config").getJSONArray("cards").length());

        // 新 run 已开启；仅 onSummary 不产生 turn 行，也不重复 outcome
        RamenDecisionLogger.onSummary(summary(2, 95, 10));
        RamenDecisionLogger.awaitIdle();
        assertEquals(1, logLines().size());
    }

    @Test public void outcomeOnFansSignatureAtFinalTurn() throws Exception {
        RamenDecisionLogger.init(tempDir(), 102601, new int[]{1, 2});
        JSONObject s = summary(77, 1300, 4700);
        s.getJSONObject("chara").put("fans", 71234); // 结算画面签名
        RamenDecisionLogger.onSummary(s);
        RamenDecisionLogger.awaitIdle();

        List<String> lines = logLines();
        assertEquals(1, lines.size());
        JSONObject fin = new JSONObject(lines.get(0)).getJSONObject("final");
        assertEquals(71234, fin.getLong("fans"));
        assertEquals(71234, fin.getLong("fan_count")); // 别名双键
    }

    @Test public void flushWritesOutcomeOnce() throws Exception {
        RamenDecisionLogger.init(tempDir(), 102601, new int[]{1, 2});
        RamenDecisionLogger.onSummary(summary(40, 1100, 3200));
        RamenDecisionLogger.flush();
        RamenDecisionLogger.awaitIdle();
        List<String> lines = logLines();
        assertEquals(1, lines.size());
        assertEquals("outcome", new JSONObject(lines.get(0)).getString("type"));

        // 幂等：再 flush 不产生第二条 outcome
        RamenDecisionLogger.flush();
        RamenDecisionLogger.awaitIdle();
        assertEquals(1, logLines().size());
    }

    @Test public void noLineWithoutDecisionAndEmptyReadWhenFresh() throws Exception {
        RamenDecisionLogger.init(tempDir(), 102601, new int[]{1, 2});
        assertEquals("", RamenDecisionLogger.readLog());

        RamenDecisionLogger.onSummary(summary(5, 200, 100));
        RamenDecisionLogger.onDecision(summary(5, 200, 100), null, "k"); // 无 decision 不落行
        RamenDecisionLogger.awaitIdle();
        assertEquals(0, logLines().size());

        // run 尚未开启时 decision 也能自动开 run
        RamenDecisionLogger.onDecision(summary(5, 200, 100), decision(), "k2");
        RamenDecisionLogger.awaitIdle();
        assertEquals(1, logLines().size());
    }
}
