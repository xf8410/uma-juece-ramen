package com.umaai.assistant.service;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * GitHubUploader 的 JVM 单测：HTTP 层注入 fake transport，无网络依赖。
 * 覆盖：攒批触发（数量/时间）、201 后队列清空、5xx/网络异常保留重试、
 * 4xx 丢弃并标记错误、队列满丢最旧且提示一次、无凭据/关闭时只积攒。
 */
public class GitHubUploaderTest {

    /** fake transport：可预设返回码与异常；contents 只记录 200/201 的成功产物。 */
    static class FakeTransport implements GitHubUploader.Transport {
        final List<String> paths = new ArrayList<>();
        final List<String> credentials = new ArrayList<>();
        final List<String> contents = new ArrayList<>();
        volatile int nextCode = 201;
        volatile boolean throwIo = false;

        @Override
        public int upload(String apiPath, String credential, String base64Content) throws java.io.IOException {
            paths.add(apiPath);
            credentials.add(credential);
            String decoded = new String(Base64.getDecoder().decode(base64Content), StandardCharsets.UTF_8);
            if (throwIo) throw new java.io.IOException("connection reset");
            if (nextCode == 200 || nextCode == 201) contents.add(decoded);
            return nextCode;
        }
    }

    private static GitHubUploader uploader(FakeTransport t) {
        GitHubUploader u = new GitHubUploader(t, 80L);
        u.setCredential("test-credential");
        u.setEnabled(true);
        return u;
    }

    private static List<String> uploadedLines(FakeTransport t) {
        List<String> out = new ArrayList<>();
        for (String c : t.contents) {
            for (String l : c.split("\n")) {
                if (!l.trim().isEmpty()) out.add(l);
            }
        }
        return out;
    }

    @Test public void batchTriggerUploadsAndClearsQueueOn201() throws Exception {
        FakeTransport t = new FakeTransport();
        GitHubUploader u = new GitHubUploader(t, 80L);
        u.setCredential("test-credential");
        u.setEnabled(false); // 先关开关填满，避免攒批途中被传走
        for (int i = 0; i < 40; i++) u.enqueue("{\"i\":" + i + "}");
        assertEquals(40, u.getQueueSize());

        u.setEnabled(true);
        u.awaitAttempts(1, 5000);

        assertEquals(1, t.contents.size());
        List<String> lines = uploadedLines(t);
        assertEquals(40, lines.size());
        for (int i = 0; i < 40; i++) {
            assertEquals("{\"i\":" + i + "}", lines.get(i)); // FIFO 顺序不变
        }
        assertEquals(0, u.getQueueSize());
        assertTrue("路径形如 repos/…/contents/data/yyyyMMdd/HHmmss-SSS-uuid8.jsonl",
                t.paths.get(0).matches(GitHubUploader.REPO_CONTENTS
                        + "data/\\d{8}/\\d{6}-\\d{3}-[0-9a-f]{8}\\.jsonl"));
        assertEquals(t.paths.get(0), u.getLastUploadPath());
        assertTrue("Base64 内容可无损还原", lines.get(39).endsWith("39}"));
    }

    @Test public void retainsBatchOn5xxAndRetries() throws Exception {
        FakeTransport t = new FakeTransport();
        t.nextCode = 500;
        GitHubUploader u = uploader(t);
        u.enqueue("{\"a\":1}");
        u.enqueue("{\"a\":2}");
        u.awaitAttempts(1, 5000);

        assertEquals("5xx 后数据留在队列", 2, u.getQueueSize());
        assertTrue(u.getLastError().contains("HTTP 500"));

        t.nextCode = 201;
        u.awaitAttempts(2, 5000);
        assertEquals("下一轮重试成功后清空", 0, u.getQueueSize());
        assertEquals(2, uploadedLines(t).size());
    }

    @Test public void retainsBatchOnNetworkError() throws Exception {
        FakeTransport t = new FakeTransport();
        t.throwIo = true;
        GitHubUploader u = uploader(t);
        u.enqueue("{\"a\":1}");
        u.awaitAttempts(1, 5000);

        assertEquals("网络异常后数据留在队列", 1, u.getQueueSize());
        assertTrue(u.getLastError().contains("网络异常"));
    }

    @Test public void authErrorDropsBatchAndMarksErrorVisible() throws Exception {
        FakeTransport t = new FakeTransport();
        t.nextCode = 401;
        GitHubUploader u = uploader(t);
        u.enqueue("{\"a\":1}");
        u.enqueue("{\"a\":2}");
        u.awaitAttempts(1, 5000);

        assertEquals("4xx 重试无意义：本批直接丢弃", 0, u.getQueueSize());
        assertTrue("错误状态可见", u.getLastError().contains("401"));
        assertEquals("丢弃计数只统计队列溢出，不含 4xx 丢弃", 0, u.getTotalDropped());
    }

    @Test public void queueFullDropsOldestNotifiesOnceThenRecovers() throws Exception {
        FakeTransport t = new FakeTransport();
        GitHubUploader u = new GitHubUploader(t, 80L);
        u.setCredential("test-credential");
        u.setEnabled(false); // 关开关灌队列，行为确定
        AtomicInteger overflowCallbacks = new AtomicInteger();
        u.setOverflowListener(dropped -> overflowCallbacks.incrementAndGet());

        for (int i = 0; i < 2050; i++) u.enqueue("{\"i\":" + i + "}");
        assertEquals("队列上限 2000", 2000, u.getQueueSize());
        assertEquals("丢最旧 50 条", 50, u.getTotalDropped());
        assertEquals("溢出提示只发一次", 1, overflowCallbacks.get());

        // 开关打开 → 积攒数据全部传出，内容为最后 2000 条且顺序保持
        t.nextCode = 201;
        u.setEnabled(true);
        assertTrue(u.awaitDrained(8000));
        List<String> lines = uploadedLines(t);
        assertEquals(2000, lines.size());
        assertEquals("最旧的是第 50 条", "{\"i\":50}", lines.get(0));
        assertEquals("最新的是第 2049 条", "{\"i\":2049}", lines.get(lines.size() - 1));
        assertEquals("队列清空后溢出提示复位", 1, overflowCallbacks.get());

        // 文件名全局唯一（uuid 后缀），多次上传不重名
        Set<String> unique = new HashSet<>(t.paths);
        assertEquals(t.paths.size(), unique.size());
    }

    @Test public void noCredentialQueuesOnlyThenUploadsWhenSet() throws Exception {
        FakeTransport t = new FakeTransport();
        GitHubUploader u = new GitHubUploader(t, 80L);
        u.setEnabled(true); // 无凭据
        for (int i = 0; i < 5; i++) u.enqueue("{\"i\":" + i + "}");
        Thread.sleep(400); // 给足空转轮询时间
        assertEquals("无凭据不上传", 0, u.getUploadAttempts());
        assertEquals(5, u.getQueueSize());

        u.setCredential(" late-credential ");
        u.awaitAttempts(1, 3000);
        assertEquals(0, u.getQueueSize());
        assertEquals(5, uploadedLines(t).size());
        assertEquals("凭据 trim 后生效", "late-credential", t.credentials.get(0));
    }

    @Test public void disabledSwitchHoldsQueueThenReleases() throws Exception {
        FakeTransport t = new FakeTransport();
        GitHubUploader u = new GitHubUploader(t, 80L);
        u.setCredential("test-credential");
        u.setEnabled(false);
        for (int i = 0; i < 10; i++) u.enqueue("{\"i\":" + i + "}");
        Thread.sleep(400);
        assertEquals("关闭开关不上传", 0, u.getUploadAttempts());
        assertEquals(10, u.getQueueSize());

        u.setEnabled(true);
        u.awaitAttempts(1, 3000);
        assertTrue(u.awaitDrained(3000));
        assertEquals(10, uploadedLines(t).size());
    }

    @Test public void timeTriggerUploadsSmallBacklog() throws Exception {
        FakeTransport t = new FakeTransport();
        GitHubUploader u = uploader(t);
        // 3 条 < 40 条阈值，只能靠时间触发（测试间隔 80ms）
        u.enqueue("{\"i\":0}");
        u.enqueue("{\"i\":1}");
        u.enqueue("{\"i\":2}");
        u.awaitAttempts(1, 3000);
        assertEquals(3, uploadedLines(t).size());
        assertEquals(0, u.getQueueSize());
    }

    // ── 本机 HTTP API 适配（双区 / 计数 / 清盘） ─────────────────────

    @Test public void enqueueRecordFeedsBothZonesKeepsRecentAndCountsUploaded() throws Exception {
        FakeTransport t = new FakeTransport();
        GitHubUploader u = uploader(t);
        // 正常路径：持久化层分配 seq 的记录引用进双区（同一对象）
        for (int i = 0; i < 3; i++) u.enqueueRecord(new RamenRecord("{\"i\":" + i + "}", i + 1L));
        u.awaitAttempts(1, 3000);
        assertTrue(u.awaitDrained(3000));
        assertEquals("上传成功清空队列", 0, u.getQueueSize());
        assertEquals("recent 不随上传清空（会话内热数据 + recent_len 来源）", 3, u.getRecentSize());
        assertEquals("uploaded_total 累计成功上传条数", 3, u.getUploadedTotal());
        assertEquals(3, uploadedLines(t).size());
    }

    @Test public void recentRingDropsOldestSilentlyAtCapacity() {
        FakeTransport t = new FakeTransport();
        GitHubUploader u = new GitHubUploader(t, 80L);
        u.setCredential("test-credential");
        u.setEnabled(false); // 关开关：行为确定，不触发上传
        for (int i = 0; i < 2010; i++) u.enqueueRecord(new RamenRecord("{\"i\":" + i + "}", i + 1L));
        assertEquals("pending 队列 2000 上限丢最旧计数", 2000, u.getQueueSize());
        assertEquals("recent 环形缓存 2000 独立丢最旧", 2000, u.getRecentSize());
        assertEquals("丢弃只计 pending 溢出（recent 静默）", 10, u.getTotalDropped());
    }

    @Test public void clearAllDataEmptiesBothZonesKeepsCountersResetsNotice() {
        FakeTransport t = new FakeTransport();
        GitHubUploader u = new GitHubUploader(t, 80L);
        u.setCredential("test-credential");
        u.setEnabled(false);
        AtomicInteger overflowCallbacks = new AtomicInteger();
        u.setOverflowListener(dropped -> overflowCallbacks.incrementAndGet());

        for (int i = 0; i < 2050; i++) u.enqueue("{\"i\":" + i + "}");
        assertEquals(50, u.getTotalDropped());
        assertEquals("溢出提示一次", 1, overflowCallbacks.get());

        int deleted = u.clearAllData();
        assertEquals("两区合计（同一记录在 queue/recent 各计一次）", 4000, deleted);
        assertEquals(0, u.getQueueSize());
        assertEquals(0, u.getRecentSize());
        assertEquals("累计丢弃保留不清零", 50, u.getTotalDropped());
        assertEquals("队列清空 → 溢出提示复位（不破坏复位语义）", 1, overflowCallbacks.get());

        // 复位后再次溢出：再提示一次，丢弃计数继续累加
        for (int i = 0; i < 2050; i++) u.enqueue("{\"j\":" + i + "}");
        assertEquals("丢弃累计继续", 100, u.getTotalDropped());
        assertEquals("复位后再次提示", 2, overflowCallbacks.get());
        assertEquals(2000, u.getQueueSize());
    }
}
