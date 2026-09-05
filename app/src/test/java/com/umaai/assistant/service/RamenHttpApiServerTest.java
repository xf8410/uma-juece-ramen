package com.umaai.assistant.service;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * RamenHttpApiServer 的 JVM 单测：临时端口起真实 HTTP 服务（回环），请求
 * 打到 127.0.0.1 验证 API 契约一字不改。
 *
 * 覆盖：/health 契约字段、/status 七字段（含 persisted_len/persisted_runs）、
 * /data 读持久化层分页（after/limit 边界、seq 派生）、DELETE /data 盘+内存
 * 合计清盘且累计计数保留、/summary 数据源与兜底、404/405、limit/after 参数
 * 解析边界、回环绑定（严禁 0.0.0.0）。
 */
public class RamenHttpApiServerTest {
    private static final String VERSION = "0.3.0-cloud";

    private File parent;
    private TrainingDataStore store;
    private GitHubUploader uploader;
    private RamenHttpApiServer server;
    private int port;

    @Before
    public void setUp() {
        // 每个用例自建 store/server（start()），避免端口与状态串扰
    }

    @After
    public void tearDown() {
        if (server != null) server.stopServer();
        if (store != null) store.shutdown();
        if (uploader != null) uploader.shutdown();
        deleteRecursively(parent);
    }

    private void start(RamenHttpApiServer.SummaryProvider provider) throws IOException {
        parent = new File(tmpRoot(), "api-" + System.nanoTime());
        store = new TrainingDataStore(parent, 10L);
        GitHubUploaderTest.FakeTransport t = new GitHubUploaderTest.FakeTransport();
        uploader = new GitHubUploader(t, 80L);
        uploader.setCredential("test-credential");
        uploader.setEnabled(true);
        port = freePort();
        server = new RamenHttpApiServer(uploader, store, provider, VERSION, port);
        server.startServer();
    }

    /** 回环绑定探测：拿一个空闲回环端口，关闭后给被测服务使用。 */
    private static int freePort() throws IOException {
        try (ServerSocket ss = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            return ss.getLocalPort();
        }
    }

    /** 测试根目录：默认 build/tmp-tests（gitignored）；可用 -Dramen.test.tmpdir
     *  覆盖（个别网络文件系统存在写后可见性怪癖，本地验证可指到本地盘）。 */
    private static File tmpRoot() {
        String override = System.getProperty("ramen.test.tmpdir");
        return new File(override != null ? override : "build/tmp-tests");
    }

    private static final class Resp {
        final int code;
        final String body;

        Resp(int code, String body) {
            this.code = code;
            this.body = body;
        }

        JSONObject json() {
            return new JSONObject(body);
        }
    }

    private Resp http(String method, String path) throws IOException {
        HttpURLConnection conn = (HttpURLConnection)
                new URL("http://127.0.0.1:" + port + path).openConnection();
        conn.setRequestMethod(method);
        conn.setConnectTimeout(2000);
        conn.setReadTimeout(5000);
        int code = conn.getResponseCode();
        InputStream in = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        if (in != null) {
            byte[] b = new byte[4096];
            int n;
            while ((n = in.read(b)) > 0) buf.write(b, 0, n);
            in.close();
        }
        conn.disconnect();
        return new Resp(code, new String(buf.toByteArray(), StandardCharsets.UTF_8));
    }

    // ── /health ──────────────────────────────────────────────────────

    @Test
    public void healthHasExactlyTheContractFields() throws Exception {
        start(null);
        Resp r = http("GET", "/health");
        assertEquals(200, r.code);
        JSONObject o = r.json();
        assertEquals("契约恰 3 字段（一字不改，允许减不许增删）", 3, o.length());
        assertTrue(o.getBoolean("ok"));
        assertEquals("juece-ramen", o.getString("app"));
        assertEquals(VERSION, o.getString("version"));
    }

    @Test
    public void serverBindsLoopbackOnly() throws Exception {
        start(null);
        assertEquals("回环隔离硬要求：严禁 0.0.0.0", "127.0.0.1", server.getHostname());
    }

    // ── /status ──────────────────────────────────────────────────────

    @Test
    public void statusHasAllSevenFieldsIncludingPersisted() throws Exception {
        start(null);
        store.append("rA", "{\"type\":\"turn\",\"i\":0}");
        store.append("rA", "{\"type\":\"turn\",\"i\":1}");
        assertTrue(store.flushPending(1_000));
        uploader.setEnabled(false); // 关开关：内存区状态可预期（不触发上传）
        uploader.enqueueRecord(new RamenRecord("{\"i\":2}", 3L)); // 显式进双区

        Resp r = http("GET", "/status");
        assertEquals(200, r.code);
        JSONObject o = r.json();
        assertEquals("契约七字段", 7, o.length());
        assertEquals(1, o.getInt("queue_len"));
        assertEquals(1, o.getInt("recent_len"));
        assertEquals(0, o.getInt("uploaded_total"));
        assertEquals(0, o.getInt("dropped_total"));
        assertEquals("与上传器状态一致", uploader.hasCredential(), o.getBoolean("token_configured"));
        assertEquals(2, o.getInt("persisted_len"));
        assertEquals(1, o.getInt("persisted_runs"));
    }

    // ── /data ────────────────────────────────────────────────────────

    @Test
    public void dataReadsPersistedStoreWithSeqAndJsonlFields() throws Exception {
        start(null);
        for (int i = 0; i < 5; i++) store.append("rA", "{\"type\":\"turn\",\"i\":" + i + "}");
        assertTrue(store.flushPending(1_000));

        Resp r = http("GET", "/data");
        assertEquals(200, r.code);
        JSONObject o = r.json();
        assertEquals("count 与记录数一致", 5, o.getInt("count"));
        JSONObject first = o.getJSONArray("records").getJSONObject(0);
        assertEquals("seq 从 1 起（位置派生）", 1, first.getLong("seq"));
        assertEquals("JSONL 字段原样带出（一字不改）", 0, first.getInt("i"));
        assertEquals("turn", first.getString("type"));

        // after：只返回 seq 更大的记录
        Resp after3 = http("GET", "/data?after=3");
        assertEquals(2, after3.json().getInt("count"));
        assertEquals(4, after3.json().getJSONArray("records").getJSONObject(0).getLong("seq"));

        // limit 截断 + 组合参数
        Resp page = http("GET", "/data?limit=2&after=2");
        assertEquals(2, page.json().getInt("count"));
        assertEquals(3, page.json().getJSONArray("records").getJSONObject(0).getLong("seq"));

        // 非法参数回落默认（limit<1 → 500；after 负数 → 0）
        Resp fallback = http("GET", "/data?limit=abc&after=-5");
        assertEquals(5, fallback.json().getInt("count"));
    }

    @Test
    public void parseLimitAndAfterBoundaries() {
        assertEquals(RamenHttpApiServer.DEFAULT_DATA_LIMIT, RamenHttpApiServer.parseLimit(null));
        assertEquals(RamenHttpApiServer.DEFAULT_DATA_LIMIT, RamenHttpApiServer.parseLimit(""));
        assertEquals(RamenHttpApiServer.DEFAULT_DATA_LIMIT, RamenHttpApiServer.parseLimit("abc"));
        assertEquals(RamenHttpApiServer.DEFAULT_DATA_LIMIT, RamenHttpApiServer.parseLimit("0"));
        assertEquals(RamenHttpApiServer.DEFAULT_DATA_LIMIT, RamenHttpApiServer.parseLimit("-5"));
        assertEquals(1, RamenHttpApiServer.parseLimit("1"));
        assertEquals(RamenHttpApiServer.MAX_DATA_LIMIT, RamenHttpApiServer.parseLimit("99999"));
        assertEquals(RamenHttpApiServer.MAX_DATA_LIMIT, RamenHttpApiServer.parseLimit("2000"));

        assertEquals(0L, RamenHttpApiServer.parseAfter(null));
        assertEquals(0L, RamenHttpApiServer.parseAfter(""));
        assertEquals(0L, RamenHttpApiServer.parseAfter("abc"));
        assertEquals(0L, RamenHttpApiServer.parseAfter("-1"));
        assertEquals(3L, RamenHttpApiServer.parseAfter("3"));
    }

    // ── DELETE /data ─────────────────────────────────────────────────

    @Test
    public void deleteClearsDiskAndMemorySumsDeletedKeepsCounters() throws Exception {
        start(null);
        for (int i = 0; i < 3; i++) store.append("rA", "{\"i\":" + i + "}");
        assertTrue(store.flushPending(1_000));
        // 1 条已成功上传（queue 清空、recent 保留、uploaded_total=1）
        uploader.enqueueRecord(new RamenRecord("{\"i\":9}", 99L));
        awaitQueueDrained(uploader);
        assertEquals(1, uploader.getUploadedTotal());

        Resp r = http("DELETE", "/data");
        assertEquals(200, r.code);
        JSONObject o = r.json();
        assertEquals("契约恰 2 字段", 2, o.length());
        assertTrue(o.getBoolean("ok"));
        assertEquals("deleted = 盘 3 + 内存两区 1（同一记录两区各计一次）", 4, o.getInt("deleted"));

        Resp st = http("GET", "/status");
        JSONObject s = st.json();
        assertEquals(0, s.getInt("queue_len"));
        assertEquals(0, s.getInt("recent_len"));
        assertEquals(0, s.getInt("persisted_len"));
        assertEquals(0, s.getInt("persisted_runs"));
        assertEquals("累计计数不清零（uploaded_total 保留）", 1, s.getInt("uploaded_total"));

        // 清盘后 seq 永不复用：新记录从已发号最大值之上继续（meta 记账）
        store.append("rB", "{\"i\":100}");
        assertTrue(store.flushPending(1_000));
        Resp data = http("GET", "/data");
        assertEquals(1, data.json().getInt("count"));
        assertEquals(4L, data.json().getJSONArray("records").getJSONObject(0).getLong("seq"));
    }

    // ── /summary ─────────────────────────────────────────────────────

    @Test
    public void summaryFromProvider() throws Exception {
        start(new RamenHttpApiServer.SummaryProvider() {
            @Override
            public JSONObject summary() {
                JSONObject o = new JSONObject();
                o.put("turn", 7);
                o.put("training_decision", "rest");
                return o;
            }
        });
        Resp r = http("GET", "/summary");
        assertEquals(200, r.code);
        JSONObject o = r.json();
        assertEquals(7, o.getInt("turn"));
        assertEquals("rest", o.getString("training_decision"));
    }

    @Test
    public void summaryFallsBackWhenProviderMissingOrBroken() throws Exception {
        start(new RamenHttpApiServer.SummaryProvider() {
            @Override
            public JSONObject summary() {
                throw new IllegalStateException("blackboard not ready");
            }
        });
        Resp broken = http("GET", "/summary");
        assertEquals("数据源异常走兜底（不影响其他端点）", 200, broken.code);
        assertEquals(-1, broken.json().getInt("turn"));

        server.stopServer();
        start(null); // 无 provider：同样兜底
        Resp none = http("GET", "/summary");
        assertEquals(200, none.code);
        assertEquals(-1, none.json().getInt("turn"));
    }

    // ── 错误路径 ─────────────────────────────────────────────────────

    @Test
    public void unknownPathReturns404() throws Exception {
        start(null);
        Resp r = http("GET", "/nope");
        assertEquals(404, r.code);
    }

    @Test
    public void wrongMethodReturns405() throws Exception {
        start(null);
        assertEquals(405, http("POST", "/health").code);
        assertEquals(405, http("DELETE", "/health").code);
        assertEquals(405, http("PUT", "/data").code);
        assertEquals(405, http("POST", "/status").code);
    }

    // ── 工具 ─────────────────────────────────────────────────────────

    private static void awaitQueueDrained(GitHubUploader u) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 3_000L;
        while (System.currentTimeMillis() < deadline && u.getQueueSize() > 0) {
            Thread.sleep(20L);
        }
        // fake transport 下队列必然收敛；收敛后再留一次轮询间隔余量
        Thread.sleep(50L);
    }

    private static void deleteRecursively(File f) {
        if (f == null) return;
        File[] children = f.listFiles();
        if (children != null) {
            for (File c : children) deleteRecursively(c);
        }
        f.delete();
    }
}
