package com.umaai.assistant.service;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

/**
 * TrainingsGate 单元测试（v0.3.6 bug1/bug2 调度逻辑，纯 JVM）。
 */
public class TrainingsGateTest {

    private static JSONObject summary(String turn, String trainingsJson) {
        String json = "{\"scenario\":\"Ramen\",\"turn\":" + turn + "," +
                "\"chara\":{\"scenario_id\":14,\"speed\":100,\"vital\":50,\"motivation\":3}," +
                "\"ramen\":{\"checkpoint_pt\":100,\"sozai\":\"3/2/1\"}" +
                (trainingsJson == null ? "" : ",\"trainings\":" + trainingsJson) + "}";
        return new JSONObject(json);
    }

    private static final String TRAININGS = "[{\"name\":\"Speed\",\"command_id\":101,\"heads\":3,\"shining\":2}," +
            "{\"name\":\"Power\",\"command_id\":103,\"heads\":1,\"shining\":0}]";

    // ── searchKey：trainings 指纹（bug1 核心） ─────────────────────────

    @Test public void searchKeyDiffersWithoutVsWithTrainings() {
        JSONObject noT = summary("31", null);
        JSONObject withT = summary("31", TRAININGS);
        // 同一回合：无人头推送与有人头推送必须是两个 key（人头到达 → 补搜）
        assertFalse(TrainingsGate.searchKey(noT).equals(TrainingsGate.searchKey(withT)));
        assertTrue(TrainingsGate.searchKey(noT).endsWith(":noT"));
        // 有人头 key 含 人数/彩圈指纹：3+1=4 头，2+0=2 光
        String k = TrainingsGate.searchKey(withT);
        assertTrue(k, k.endsWith(":4h2s"));
    }

    @Test public void searchKeyDiffersWhenHeadsChange() {
        // 同回合人头变化（如技能/事件改变站位）→ 重搜
        String t1 = "[{\"name\":\"Speed\",\"command_id\":101,\"heads\":3,\"shining\":2}]";
        String t2 = "[{\"name\":\"Speed\",\"command_id\":101,\"heads\":5,\"shining\":2}]";
        assertFalse(TrainingsGate.searchKey(summary("31", t1))
                .equals(TrainingsGate.searchKey(summary("31", t2))));
    }

    @Test public void searchKeyStableForIdenticalPushes() {
        // 同内容重复推送 → 同 key（不重复搜索）
        assertEquals(TrainingsGate.searchKey(summary("31", TRAININGS)),
                     TrainingsGate.searchKey(summary("31", TRAININGS)));
    }

    // ── 状态机：等人头 → 放行 / 人头到达 ─────────────────────────────

    @Test public void firstPushWithoutTrainingsWaits() {
        TrainingsGate gate = new TrainingsGate();
        String key = TrainingsGate.searchKey(summary("31", null));
        TrainingsGate.Decision d = gate.onNewSummary(key, TrainingsGate.Obs.fromSummary(summary("31", null)), 1000);
        assertTrue(d.isWait());
        assertEquals(1000 + TrainingsGate.TRAININGS_WAIT_MS, d.waitDeadline);
    }

    @Test public void repeatedPushWithoutTrainingsSkips() {
        TrainingsGate gate = new TrainingsGate();
        String key = TrainingsGate.searchKey(summary("31", null));
        gate.onNewSummary(key, TrainingsGate.Obs.fromSummary(summary("31", null)), 1000);
        // 等待期间的重复推送 → SKIP（不重复起线程）
        assertTrue(gate.onNewSummary(key, TrainingsGate.Obs.fromSummary(summary("31", null)), 1500).isSkip());
    }

    @Test public void releaseWithoutTrainingsOnlyOnce() {
        TrainingsGate gate = new TrainingsGate();
        String key = TrainingsGate.searchKey(summary("31", null));
        gate.onNewSummary(key, TrainingsGate.Obs.fromSummary(summary("31", null)), 1000);
        assertTrue(gate.releaseWithoutTrainings(key)); // 超时放行（⚠ 无人头）
        assertFalse(gate.releaseWithoutTrainings(key)); // 只放行一次
        assertTrue(gate.releasedWithoutTrainings(key));
    }

    @Test public void trainingsArrivingUpgradesToRunNow() {
        TrainingsGate gate = new TrainingsGate();
        String noT = TrainingsGate.searchKey(summary("31", null));
        gate.onNewSummary(noT, TrainingsGate.Obs.fromSummary(summary("31", null)), 1000);
        gate.onTrainingsArrived();
        // 人头到了：有人头推送 → RUN_NOW(true)
        String withT = TrainingsGate.searchKey(summary("31", TRAININGS));
        TrainingsGate.Decision d = gate.onNewSummary(withT, TrainingsGate.Obs.fromSummary(summary("31", TRAININGS)), 2000);
        assertTrue(d.isRunNow());
        assertTrue(d.hasHeads);
    }

    @Test public void pushWithTrainingsRunsImmediately() {
        TrainingsGate gate = new TrainingsGate();
        String key = TrainingsGate.searchKey(summary("31", TRAININGS));
        TrainingsGate.Decision d = gate.onNewSummary(key, TrainingsGate.Obs.fromSummary(summary("31", TRAININGS)), 1000);
        assertTrue(d.isRunNow());
        assertTrue(d.hasHeads);
    }

    // ── 失败重试（bug2） ─────────────────────────────────────────────

    @Test public void failureCountTracksPerKey() {
        TrainingsGate gate = new TrainingsGate();
        assertEquals(0, gate.failureCount("k1"));
        gate.onSearchFailed("k1");
        gate.onSearchFailed("k1");
        assertEquals(2, gate.failureCount("k1"));
        assertEquals(0, gate.failureCount("k2")); // 别的 key 不受影响
    }

    @Test public void retryHalvesSearchNWithFloor() {
        assertEquals(2048, TrainingsGate.retrySearchN(4096, 1));
        assertEquals(1024, TrainingsGate.retrySearchN(4096, 2));
        assertEquals(32, TrainingsGate.retrySearchN(4096, 10)); // 下限 32
        assertEquals(4096, TrainingsGate.retrySearchN(4096, 0));
    }

    // ── 文案 ─────────────────────────────────────────────────────────

    @Test public void headsWarningOnlyWhenMissing() {
        assertEquals("", TrainingsGate.headsWarning(true));
        String w = TrainingsGate.headsWarning(false);
        assertTrue(w, w.contains("⚠无人头"));
    }

    @Test public void failureTextShowsStreakAndReason() {
        assertTrue(TrainingsGate.failureText("panic during search", 1).contains("搜索失败：panic during search"));
        assertTrue(TrainingsGate.failureText("panic during search", 3).contains("搜索失败(第3次)"));
        assertTrue(TrainingsGate.failureText(null, 1).contains("native无结果"));
    }

    @Test public void fmtMsUsesUsLocale() {
        assertEquals("800ms", TrainingsGate.fmtMs(800));
        assertEquals("12.7s", TrainingsGate.fmtMs(12697));
    }
}
