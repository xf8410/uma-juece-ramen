package com.umaai.assistant.service;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * TrainingDataStore 的 JVM 单测（临时目录，无 Android 依赖）。
 * 覆盖：按 run 分文件原文落盘、seq 位置派生与跨重启延续、after/limit 读盘
 * 分页、DELETE 清盘 + seq 地板记账（清盘后不复用）、写失败重试不丢、
 * 落盘时效（≤1 秒预算）、以及用户点名的「连续写入后模拟异常终止，重读
 * 数据完整」场景。
 */
public class TrainingDataStoreTest {
    private File parent;

    @Before
    public void setUp() throws Exception {
        parent = new File(tmpRoot(), "store-" + System.nanoTime());
        parent.mkdirs();
    }

    /** 测试根目录：默认 build/tmp-tests（gitignored）；可用 -Dramen.test.tmpdir
     *  覆盖（个别网络文件系统存在写后可见性怪癖，本地验证可指到本地盘）。 */
    private static File tmpRoot() {
        String override = System.getProperty("ramen.test.tmpdir");
        return new File(override != null ? override : "build/tmp-tests");
    }

    @After
    public void tearDown() {
        deleteRecursively(parent);
    }

    private static TrainingDataStore store(File parentDir) throws IOException {
        // 退避起点 10ms：写失败重试场景快速收敛
        return new TrainingDataStore(parentDir, 10L);
    }

    private TrainingDataStore open() throws IOException {
        return store(parent);
    }

    private static List<String> lines(TrainingDataStore s, long after, int limit) throws Exception {
        List<String> out = new ArrayList<>();
        for (RamenRecord r : s.read(after, limit)) out.add(r.jsonl);
        return out;
    }

    @Test
    public void appendWritesPerRunJsonlFilesWithExactLines() throws Exception {
        TrainingDataStore s = open();
        s.append("r20260829_012233", "{\"type\":\"turn\",\"run\":\"r20260829_012233\",\"turn\":1}");
        s.append("r20260829_012233", "{\"type\":\"turn\",\"run\":\"r20260829_012233\",\"turn\":2}");
        s.append("r20260901_153045", "{\"type\":\"outcome\",\"run\":\"r20260901_153045\"}");
        assertTrue("≤1 秒落盘预算（逐条 write+force，不攒批）", s.flushPending(1_000));

        File dir = new File(parent, TrainingDataStore.DIR_NAME);
        File run1 = new File(dir, "r20260829_012233.jsonl");
        File run2 = new File(dir, "r20260901_153045.jsonl");
        assertTrue("一局一个 .jsonl 文件", run1.isFile() && run2.isFile());
        assertEquals(2, TrainingDataStore.countLines(run1));
        assertEquals(1, TrainingDataStore.countLines(run2));
        assertEquals("{\"type\":\"turn\",\"run\":\"r20260829_012233\",\"turn\":1}",
                firstLine(run1));
        assertEquals(2, s.getPersistedRuns());
        assertEquals(3, s.getPersistedLines());
        s.shutdown();
    }

    @Test
    public void seqDerivedFromPositionSurvivesReopenAndContinues() throws Exception {
        TrainingDataStore s = open();
        for (int i = 0; i < 5; i++) s.append("rA", "{\"i\":" + i + "}");
        assertTrue(s.flushPending(1_000));
        List<RamenRecord> first = s.read(0, 100);
        assertEquals(5, first.size());
        for (int i = 0; i < 5; i++) assertEquals("位置即 seq", i + 1, first.get(i).seq);
        s.shutdown();

        // 模拟进程重启：新实例挂同一目录（meta 不存在 → seqBase=0 → 位置重推）
        TrainingDataStore s2 = open();
        List<RamenRecord> recovered = s2.read(0, 100);
        assertEquals("重启后 seq 与重启前一致", 1, recovered.get(0).seq);
        assertEquals(5, recovered.get(4).seq);
        // 新记录从已发号最大值之上继续（不重不漏）
        RamenRecord next = s2.append("rB", "{\"i\":5}");
        assertEquals("seq 跨重启延续", 6, next.seq);
        assertTrue(s2.flushPending(1_000));
        assertEquals(6, s2.read(5, 10).get(0).seq);
        s2.shutdown();
    }

    @Test
    public void readAfterLimitPaginationOverDisk() throws Exception {
        TrainingDataStore s = open();
        for (int i = 0; i < 10; i++) s.append("rA", "{\"i\":" + i + "}");
        assertTrue(s.flushPending(1_000));

        assertEquals("limit 截断", 3, s.read(0, 3).size());
        List<RamenRecord> after3 = s.read(3, 100);
        assertEquals("after 只回更大 seq", 7, after3.size());
        assertEquals(4, after3.get(0).seq);
        assertEquals(0, s.read(999, 100).size()); // after 超过最大 → 空
        assertEquals(10, s.read(0, 100).size());
        assertEquals(0, s.read(0, 0).size());     // limit<=0 → 空
        s.shutdown();
    }

    @Test
    public void clearAllDeletesFilesBumpsSeqFloorNeverReuses() throws Exception {
        TrainingDataStore s = open();
        for (int i = 0; i < 3; i++) s.append("rA", "{\"i\":" + i + "}");
        assertTrue(s.flushPending(1_000));

        int deleted = s.clearAll(1_000);
        assertEquals("磁盘删除条数", 3, deleted);
        assertEquals(0, s.getPersistedLines());
        assertEquals(0, s.getPersistedRuns());
        assertEquals(0, s.read(0, 100).size());

        // 清盘后 seq 从已发号最大值之上继续（meta 记账），永不复用
        RamenRecord next = s.append("rB", "{\"i\":100}");
        assertEquals(4, next.seq);
        assertTrue(s.flushPending(1_000));
        assertEquals(4, s.read(0, 10).get(0).seq);
        s.shutdown();

        // 重启后 meta 生效：seqBase=3，存量记录（rB 1 条）派生 seq 4
        TrainingDataStore s2 = open();
        List<RamenRecord> recovered = s2.read(0, 10);
        assertEquals(1, recovered.size());
        assertEquals(4, recovered.get(0).seq);
        assertEquals("重启后新记录继续延续（seqBase=3 + 现存 1 条 → 发号从 5 起）",
                5, s2.append("rC", "{\"i\":101}").seq);
        s2.shutdown();
    }

    @Test
    public void continuousWritesThenAbnormalTerminationRereadComplete() throws Exception {
        // 用户点名场景：连续写入 → 模拟异常终止（不留优雅收尾机会）→ 重读完整。
        // 写侧逐条 write+force，页面缓存/文件内容在进程死亡后仍在；这里用
        // 「不调用 shutdown 的实例直接废弃 + 同目录重开」模拟进程被杀。
        TrainingDataStore s = open();
        for (int i = 0; i < 40; i++) s.append("r20260901_090000", "{\"type\":\"turn\",\"i\":" + i + "}");
        for (int i = 0; i < 10; i++) s.append("r20260902_100000", "{\"type\":\"turn\",\"i\":" + (40 + i) + "}");
        assertTrue("全部记录在 1 秒预算内落盘", s.flushPending(1_000));
        // 刻意不调用 shutdown()：模拟进程被杀

        TrainingDataStore s2 = open(); // 「重启」
        List<RamenRecord> all = s2.read(0, 100);
        assertEquals("50 条记录一条不少", 50, all.size());
        for (int i = 0; i < 50; i++) {
            assertEquals("内容逐条完整且有序", i, new JSONObject(all.get(i).jsonl).getInt("i"));
            assertEquals("seq 连续无缺口", i + 1, all.get(i).seq);
        }
        assertEquals(50, s2.getPersistedLines());
        assertEquals(2, s2.getPersistedRuns());
        s2.shutdown();
    }

    @Test
    public void corruptTailLineKeepsPositionAndSurfacesRaw() throws Exception {
        TrainingDataStore s = open();
        s.append("rA", "{\"i\":0}");
        s.append("rA", "{\"i\":1}");
        assertTrue(s.flushPending(1_000));
        s.shutdown();

        // 模拟掉电截断：向文件尾部追加半行损坏数据
        File dir = new File(parent, TrainingDataStore.DIR_NAME);
        File f = new File(dir, "rA.jsonl");
        try (FileOutputStream out = new FileOutputStream(f, true)) {
            out.write("{\"i\":2,\"trunca".getBytes(StandardCharsets.UTF_8));
        }

        TrainingDataStore s2 = open();
        List<RamenRecord> all = s2.read(0, 100);
        assertEquals("坏行占位不塌缩，seq 不漂移", 3, all.size());
        assertEquals(1, all.get(0).seq);
        assertEquals(2, all.get(1).seq);
        assertEquals(3, all.get(2).seq);
        assertEquals("坏行原样带出供排查", "{\"i\":2,\"trunca", all.get(2).jsonl);
        s2.shutdown();
    }

    @Test
    public void writeFailureRetriesAndKeepsOrderAfterRecovery() throws Exception {
        TrainingDataStore s = open();
        // 故障注入：把 run 文件名占位成目录 → 打开通道必然 IOException
        File dir = new File(parent, TrainingDataStore.DIR_NAME);
        File blocked = new File(dir, "rA.jsonl");
        assertTrue(blocked.mkdirs());

        List<RamenRecord> issued = new ArrayList<>();
        for (int i = 0; i < 3; i++) issued.add(s.append("rA", "{\"i\":" + i + "}"));
        Thread.sleep(150); // 重试期内（退避 10ms 起步）
        assertNotNull(s.getLastWriteError());
        assertEquals("写失败期间磁盘无数据（记录在队列/重试中，不丢）", 0, s.getPersistedLines());
        assertEquals(0, s.getPersistedRuns()); // 目录占位：run 文件从未建成
        assertEquals(3, issued.size()); // 发号路径不受写失败影响

        // 故障解除：删除占位目录 → 队列里的记录按原顺序重试成功
        String[] inside = blocked.list();
        assertTrue(inside == null || inside.length == 0);
        assertTrue(blocked.delete());
        assertTrue(s.flushPending(5_000));
        assertEquals(3, s.getPersistedLines());
        List<RamenRecord> all = s.read(0, 100);
        assertEquals(3, all.size());
        for (int i = 0; i < 3; i++) {
            assertEquals("FIFO 顺序不变", issued.get(i).jsonl, all.get(i).jsonl);
            assertEquals(issued.get(i).seq, all.get(i).seq);
        }
        s.shutdown();
    }

    @Test
    public void sanitizeRunKeyForFilename() {
        assertEquals("r20260829_012233", TrainingDataStore.sanitizeRunKey("r20260829_012233"));
        assertEquals(TrainingDataStore.UNKNOWN_RUN, TrainingDataStore.sanitizeRunKey(null));
        assertEquals(TrainingDataStore.UNKNOWN_RUN, TrainingDataStore.sanitizeRunKey(""));
        assertEquals("a_b_c_d", TrainingDataStore.sanitizeRunKey("a/b\\c:d"));
    }

    private static String firstLine(File f) throws IOException {
        List<String> lines = new ArrayList<>();
        try (java.io.BufferedReader r = new java.io.BufferedReader(
                new java.io.InputStreamReader(new java.io.FileInputStream(f), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) lines.add(line);
        }
        assertEquals(2, lines.size());
        return lines.get(0);
    }

    private static void deleteRecursively(File f) {
        File[] children = f.listFiles();
        if (children != null) {
            for (File c : children) deleteRecursively(c);
        }
        f.delete();
    }
}
