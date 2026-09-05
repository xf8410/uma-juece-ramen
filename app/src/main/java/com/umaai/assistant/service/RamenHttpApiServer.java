package com.umaai.assistant.service;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import fi.iki.elonen.NanoHTTPD;

/**
 * 本机 HTTP API（回环隔离）：供同设备的另一个 App（Agora-Workbench）读取/
 * 清空训练数据。API 契约与对端客户端一字不改（允许新增字段）：
 * <pre>
 * GET    /health  → {"ok":true,"app":"juece-ramen","version":"&lt;App版本名&gt;"}
 * GET    /status  → {"queue_len":N,"recent_len":N,"uploaded_total":N,"dropped_total":N,
 *                    "token_configured":bool,"persisted_len":N,"persisted_runs":N}
 * GET    /data?limit=N&amp;after=seq → {"count":N,"records":[JSONL字段+seq]}
 * GET    /summary → 当前黑板/训练状态 JSON（含 turn 与关键状态摘要）
 * DELETE /data    → {"ok":true,"deleted":N}
 * </pre>
 * 数据源分层：
 * - GET /data 读持久化层（TrainingDataStore，私有目录按 run 分文件全量保留），
 *   进程重启后数据与 seq 延续（seq = seqBase + 磁盘全局位置，不重不漏）
 * - /status 的 queue_len/recent_len/uploaded_total/dropped_total/token_configured
 *   来自内存双区（GitHubUploader，可选保底上传通道）；persisted_len/persisted_runs
 *   来自持久化层
 * - DELETE /data 清持久化文件 + 内存两区，deleted 返回合计；累计计数不清零
 *
 * 回环隔离硬要求：NanoHTTPD 用带 hostname 的构造器显式绑定 127.0.0.1，
 * 严禁 0.0.0.0——服务只对本机进程（含 adb forward）可见。
 *
 * 纯 Java（无 android.* 依赖）：上传器/持久化层/摘要源/版本名全部注入，
 * 可 JVM 单测（临时端口起真实 HTTP 服务）。
 */
public final class RamenHttpApiServer extends NanoHTTPD {
    /** 本机端口（契约固定）；绑定主机恒为回环地址。 */
    public static final int PORT = 18767;
    public static final String HOST_LOOPBACK = "127.0.0.1";
    public static final String APP_NAME = "juece-ramen";
    /** GET /data 参数：limit 默认 500、上限 2000；after 默认 0（从头拉）。 */
    public static final int DEFAULT_DATA_LIMIT = 500;
    public static final int MAX_DATA_LIMIT = 2000;
    static final long DEFAULT_AFTER = 0L;
    /** DELETE 前等待持久化写盘队列清空的上限（毫秒）。 */
    static final long DELETE_DRAIN_TIMEOUT_MS = 2_000L;

    static final String PATH_HEALTH = "/health";
    static final String PATH_STATUS = "/status";
    static final String PATH_DATA = "/data";
    static final String PATH_SUMMARY = "/summary";
    static final String MIME_JSON = "application/json; charset=utf-8";
    static final String MIME_TEXT = "text/plain; charset=utf-8";

    /** /summary 数据源：浮窗当前黑板/训练状态（FloatingWindowService 提供）。 */
    public interface SummaryProvider {
        JSONObject summary();
    }

    private final GitHubUploader uploader;
    private final TrainingDataStore store;
    private final SummaryProvider summaryProvider;
    private final String versionName;

    /** 生产构造：绑定 127.0.0.1:18767。 */
    public RamenHttpApiServer(GitHubUploader uploader, TrainingDataStore store,
                              SummaryProvider summaryProvider, String versionName) throws IOException {
        this(uploader, store, summaryProvider, versionName, PORT);
    }

    /** 测试构造：port 传临时端口避免冲突；hostname 仍恒为回环地址。 */
    RamenHttpApiServer(GitHubUploader uploader, TrainingDataStore store,
                       SummaryProvider summaryProvider, String versionName, int port) throws IOException {
        super(HOST_LOOPBACK, port); // 显式回环绑定（严禁 0.0.0.0，硬要求）
        this.uploader = uploader;
        this.store = store;
        this.summaryProvider = summaryProvider;
        this.versionName = versionName == null ? "" : versionName;
    }

    public void startServer() throws IOException {
        start(SOCKET_READ_TIMEOUT, false);
    }

    public void stopServer() {
        stop();
    }

    @Override
    public Response serve(IHTTPSession session) {
        try {
            String uri = session.getUri();
            Method method = session.getMethod();
            if (PATH_HEALTH.equals(uri)) return onlyGet(method, health());
            if (PATH_STATUS.equals(uri)) return onlyGet(method, status());
            if (PATH_DATA.equals(uri)) {
                if (method == Method.GET) return json(Response.Status.OK, data(session.getParms()));
                if (method == Method.DELETE) return json(Response.Status.OK, deleteData());
                return methodNotAllowed();
            }
            if (PATH_SUMMARY.equals(uri)) return summaryResponse();
            return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_TEXT,
                    "unknown endpoint, use /health /status /data /summary");
        } catch (Exception e) {
            // 单请求异常不拖垮服务进程
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_TEXT,
                    String.valueOf(e.getMessage()));
        }
    }

    // ── 端点实现 ──────────────────────────────────────────────────────

    private Response onlyGet(Method method, JSONObject body) throws JSONException {
        return method == Method.GET ? json(Response.Status.OK, body) : methodNotAllowed();
    }

    private Response methodNotAllowed() {
        return newFixedLengthResponse(Response.Status.METHOD_NOT_ALLOWED, MIME_TEXT,
                "method not allowed");
    }

    private Response json(Response.IStatus status, JSONObject body) {
        return newFixedLengthResponse(status, MIME_JSON, body.toString());
    }

    private JSONObject health() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("ok", true);
        o.put("app", APP_NAME);
        o.put("version", versionName);
        return o;
    }

    private JSONObject status() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("queue_len", uploader.getQueueSize());
        o.put("recent_len", uploader.getRecentSize());
        o.put("uploaded_total", uploader.getUploadedTotal());
        o.put("dropped_total", uploader.getTotalDropped());
        o.put("token_configured", uploader.hasCredential());
        o.put("persisted_len", store == null ? 0 : store.getPersistedLines());
        o.put("persisted_runs", store == null ? 0 : store.getPersistedRuns());
        return o;
    }

    private JSONObject data(Map<String, String> parms) throws JSONException {
        int limit = parseLimit(parms == null ? null : parms.get("limit"));
        long after = parseAfter(parms == null ? null : parms.get("after"));

        JSONArray records = new JSONArray();
        if (store != null) {
            List<RamenRecord> snapshot = store.read(after, limit);
            for (RamenRecord record : snapshot) {
                records.put(toApiRecord(record));
            }
        }
        JSONObject o = new JSONObject();
        o.put("count", records.length());
        o.put("records", records);
        return o;
    }

    /** 记录对象 = 现有 JSONL 行字段（一字不改）+ 新增 seq；坏行原样带出。 */
    private static JSONObject toApiRecord(RamenRecord record) {
        try {
            JSONObject o = new JSONObject(record.jsonl);
            o.put("seq", record.seq);
            return o;
        } catch (JSONException e) {
            // 决策行由 JSONObject.toString() 产出，正常不会走到这里；
            // 防御性兜底（如掉电截断行）：原样带出，不让单条坏行打掉整个响应
            JSONObject o = new JSONObject();
            try {
                o.put("seq", record.seq);
                o.put("raw_jsonl", record.jsonl);
            } catch (JSONException ignored) {
            }
            return o;
        }
    }

    /** limit：默认 500、上限 2000；非法/小于 1 回落默认。 */
    static int parseLimit(String raw) {
        if (raw != null && !raw.isEmpty()) {
            try {
                int v = Integer.parseInt(raw);
                if (v >= 1) return Math.min(v, MAX_DATA_LIMIT);
            } catch (NumberFormatException ignored) {
            }
        }
        return DEFAULT_DATA_LIMIT;
    }

    /** after：默认 0；非法/负数回落 0（全量）。 */
    static long parseAfter(String raw) {
        if (raw != null && !raw.isEmpty()) {
            try {
                long v = Long.parseLong(raw);
                if (v > DEFAULT_AFTER) return v;
            } catch (NumberFormatException ignored) {
            }
        }
        return DEFAULT_AFTER;
    }

    private JSONObject deleteData() throws JSONException {
        int diskDeleted = store == null ? 0 : store.clearAll(DELETE_DRAIN_TIMEOUT_MS);
        int memDeleted = uploader.clearAllData();
        JSONObject o = new JSONObject();
        o.put("ok", true);
        o.put("deleted", diskDeleted + memDeleted);
        return o;
    }

    private Response summaryResponse() {
        SummaryProvider provider = summaryProvider;
        if (provider != null) {
            try {
                JSONObject s = provider.summary();
                if (s != null) return json(Response.Status.OK, s);
            } catch (Exception ignored) {
                // 数据源异常走兜底，不影响其他端点
            }
        }
        return newFixedLengthResponse(Response.Status.OK, MIME_JSON, EMPTY_SUMMARY_JSON);
    }

    /** 兜底摘要（无数据/数据源异常）：字面量常量，响应路径零构造异常。 */
    private static final String EMPTY_SUMMARY_JSON = "{\"turn\":-1}";
}
