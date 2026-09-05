package com.umaai.assistant.service;

import org.json.JSONObject;

import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * 训练数据上传器（数据零落盘直传 GitHub）。
 *
 * RamenDecisionLogger 产出的 JSONL 行只进 RAM 队列，后台单线程攒批经
 * GitHub Contents API 直传远端仓库（main 分支）。HTTP 200/201 → 这批数据
 * 立即从内存释放；5xx/网络异常 → 留队列等下轮重试；4xx（凭据失效等重试
 * 无意义）→ 直接丢弃并记入错误状态（状态里可见）。任何分支都不写文件、
 * 不写游戏目录。
 *
 * 队列上限 200 条，满了丢最旧并累计丢弃数（新数据比旧数据对调参更有价值，
 * 丢最旧是刻意取舍）；溢出开始时经 OverflowListener 提示一次，队列清空后
 * 重置，下次溢出再提示，避免刷屏。
 *
 * 攒批触发：队列 ≥40 条，或距上次上传 ≥60 秒（两者先到先传）。积压超过
 * 单文件容量时自动分多次传（单文件行数/字节有上限，避免超出 Contents API
 * 请求体积限制）。文件名 data/{yyyyMMdd}/{HHmmss-SSS}-{uuid8}.jsonl 全局
 * 唯一 → 永远 201 新建，不存在并发覆盖。
 *
 * 无凭据或开关关闭时队列照常工作（容量/丢弃逻辑不变），补上凭据后下一轮
 * 自动把积攒数据传出去。
 *
 * 纯 Java（无 android.* 依赖），可 JVM 单测；HTTP 层抽为 Transport 接口，
 * 测试注入 fake transport。
 */
public final class GitHubUploader {
    /** HTTP 层抽象：真实实现走 HttpURLConnection，测试可注入 fake。 */
    public interface Transport {
        /**
         * 上传一个 Base64 内容到 apiPath（GitHub Contents API PUT）。
         *
         * @return HTTP 状态码（200/201 = 成功）
         * @throws IOException 网络异常（超时/连接失败）
         */
        int upload(String apiPath, String credential, String base64Content) throws IOException;
    }

    /** 队列溢出提示（丢最旧开始时回调一次，队列清空后重置）。 */
    public interface OverflowListener {
        void onOverflow(int totalDropped);
    }

    static final String API_BASE = "https://api.github.com/";
    static final String REPO_CONTENTS = "repos/xf8410/uma-lamianbei-yuchengshuju/contents/";

    static final int MAX_QUEUE = 2000;
    static final int BATCH_TRIGGER_LINES = 40;
    static final int MAX_BATCH_LINES = 60;
    static final int MAX_BATCH_BYTES = 512 * 1024;
    static final long DEFAULT_FLUSH_INTERVAL_MS = 60_000L;
    /** 空转轮询间隔（无凭据/关开关/队列为空时；被 notify 会提前唤醒）。 */
    static final long IDLE_POLL_MS = 5_000L;

    private final Object lock = new Object();
    private final ArrayDeque<String> queue = new ArrayDeque<>();
    private final Transport transport;
    private final long flushIntervalMs;

    private String credential;
    private boolean enabled;
    private boolean uploading;
    private int uploadAttempts;
    private int totalDropped;
    private boolean overflowNotified;
    private String lastError;
    private long lastErrorAt;
    private String lastUploadPath;
    private long lastUploadAt;
    private long lastAttemptAt;
    private volatile boolean running = true;
    private volatile OverflowListener overflowListener;

    private final Thread worker;

    public GitHubUploader() {
        this(new HttpTransport(), DEFAULT_FLUSH_INTERVAL_MS);
    }

    /** 测试构造：注入 fake transport 与更短的攒批间隔。 */
    GitHubUploader(Transport transport, long flushIntervalMs) {
        this.transport = transport;
        this.flushIntervalMs = flushIntervalMs;
        this.lastAttemptAt = System.currentTimeMillis();
        worker = new Thread(this::loop, "RamenUpload");
        worker.setDaemon(true);
        worker.start();
    }

    // ── 对外接口 ──────────────────────────────────────────────────────

    /** 设置/更换远端同步凭据（null 或空白 = 清除）；已积攒队列不受影响。 */
    public void setCredential(String value) {
        String v = value == null ? null : value.trim();
        synchronized (lock) {
            credential = v == null || v.isEmpty() ? null : v;
            lock.notifyAll();
        }
    }

    /** 上传总开关；关闭时队列照常积攒（容量/丢弃逻辑不变）。 */
    public void setEnabled(boolean on) {
        synchronized (lock) {
            enabled = on;
            lock.notifyAll();
        }
    }

    /** 追加一行 JSONL；队列满时丢最旧并计数（溢出开始时回调提示一次）。 */
    public void enqueue(String jsonlLine) {
        if (jsonlLine == null || jsonlLine.isEmpty()) return;
        boolean notifyOverflow = false;
        synchronized (lock) {
            if (queue.size() >= MAX_QUEUE) {
                queue.pollFirst();
                totalDropped++;
                notifyOverflow = !overflowNotified;
                overflowNotified = true;
            }
            queue.addLast(jsonlLine);
            lock.notifyAll();
        }
        if (notifyOverflow) {
            OverflowListener l = overflowListener;
            if (l != null) {
                try {
                    l.onOverflow(totalDropped);
                } catch (Exception ignored) {
                }
            }
        }
    }

    /** 立即尝试把积攒数据传出去（跳过攒批等待；无凭据/关闭时仅唤醒空转）。 */
    public void flushNow() {
        synchronized (lock) {
            lastAttemptAt = 0;
            lock.notifyAll();
        }
    }

    /** 停止后台线程（进程退出前调用；RAM 队列随进程消亡，符合零落盘设计）。 */
    public void shutdown() {
        running = false;
        synchronized (lock) {
            lock.notifyAll();
        }
    }

    public void setOverflowListener(OverflowListener l) {
        overflowListener = l;
    }

    public int getQueueSize() {
        synchronized (lock) {
            return queue.size();
        }
    }

    public int getTotalDropped() {
        synchronized (lock) {
            return totalDropped;
        }
    }

    public boolean hasCredential() {
        synchronized (lock) {
            return credential != null;
        }
    }

    public boolean isEnabled() {
        synchronized (lock) {
            return enabled;
        }
    }

    public String getLastError() {
        synchronized (lock) {
            return lastError;
        }
    }

    public String getLastUploadPath() {
        synchronized (lock) {
            return lastUploadPath;
        }
    }

    /** 本地接口展示用：上传状态 JSON（队列条数/累计丢弃/最近结果/最近错误）。 */
    public String statusJson() {
        synchronized (lock) {
            JSONObject o = new JSONObject();
            o.put("enabled", enabled);
            o.put("credential_set", credential != null);
            o.put("queue_lines", queue.size());
            o.put("queue_max", MAX_QUEUE);
            o.put("dropped_total", totalDropped);
            o.put("upload_attempts", uploadAttempts);
            o.put("last_upload_path", lastUploadPath == null ? "" : lastUploadPath);
            o.put("last_upload_at", lastUploadAt);
            o.put("last_error", lastError == null ? "" : lastError);
            o.put("last_error_at", lastErrorAt);
            return o.toString();
        }
    }

    // ── 后台攒批上传 ──────────────────────────────────────────────────

    private void loop() {
        while (running) {
            try {
                boolean go;
                synchronized (lock) {
                    boolean warm = credential != null && enabled && !queue.isEmpty();
                    try {
                        lock.wait(warm ? flushIntervalMs : IDLE_POLL_MS);
                    } catch (InterruptedException e) {
                        return;
                    }
                    go = readyForUploadLocked();
                }
                if (go) uploadOneBatch();
            } catch (Throwable t) {
                // 后台线程永不带异常退出
                recordError("内部异常 " + t.getClass().getSimpleName());
                sleepQuiet(1000);
            }
        }
    }

    private boolean readyForUploadLocked() {
        if (credential == null || !enabled || queue.isEmpty()) return false;
        long now = System.currentTimeMillis();
        return queue.size() >= BATCH_TRIGGER_LINES
                || now - lastAttemptAt >= flushIntervalMs;
    }

    private void uploadOneBatch() {
        String[] batch;
        synchronized (lock) {
            if (queue.isEmpty()) return;
            batch = drainBatchLocked();
            uploading = true;
            lastAttemptAt = System.currentTimeMillis();
            uploadAttempts++;
        }

        String apiPath = null;
        boolean removed = false;   // 这批数据是否已从内存移除（成功 或 4xx 丢弃）
        boolean succeeded = false; // 200/201（区别于 4xx 丢弃：只有成功才算上传记录）
        try {
            apiPath = buildApiPath();
            StringBuilder payload = new StringBuilder();
            for (String line : batch) payload.append(line).append('\n');
            String b64 = Base64.getEncoder()
                    .encodeToString(payload.toString().getBytes(StandardCharsets.UTF_8));
            int code = transport.upload(apiPath, credential, b64);
            if (code == 200 || code == 201) {
                removed = true;
                succeeded = true;
            } else if (code >= 400 && code < 500) {
                // 凭据失效/请求被拒等：重试无意义，直接丢弃并在错误状态可见
                removed = true;
                succeeded = false;
                recordError("HTTP " + code + " " + httpHint(code)
                        + "，已丢弃本批 " + batch.length + " 行");
            } else {
                removed = false;
                succeeded = false;
                requeueFrontLocked(batch);
                recordError("HTTP " + code + " " + httpHint(code));
            }
        } catch (IOException e) {
            removed = false;
            succeeded = false;
            requeueFrontLocked(batch);
            recordError("网络异常 " + e.getClass().getSimpleName());
        } catch (RuntimeException e) {
            removed = false;
            succeeded = false;
            requeueFrontLocked(batch);
            recordError("上传异常 " + e.getClass().getSimpleName());
        } finally {
            synchronized (lock) {
                uploading = false;
                if (removed) {
                    if (succeeded) {
                        lastUploadPath = apiPath;
                        lastUploadAt = System.currentTimeMillis();
                    }
                    if (queue.isEmpty()) overflowNotified = false; // 溢出提示复位
                }
                lock.notifyAll();
            }
        }
    }

    /** 出队攒一批：行数与字节双上限；单行超限也至少带走一行，避免卡队。 */
    private String[] drainBatchLocked() {
        List<String> out = new ArrayList<>();
        long bytes = 0;
        while (!queue.isEmpty() && out.size() < MAX_BATCH_LINES) {
            String line = queue.peekFirst();
            int len = line.getBytes(StandardCharsets.UTF_8).length + 1;
            if (!out.isEmpty() && bytes + len > MAX_BATCH_BYTES) break;
            queue.pollFirst();
            out.add(line);
            bytes += len;
        }
        return out.toArray(new String[0]);
    }

    /** 上传失败：按原顺序放回队头（FIFO 不变）；容量满则按丢最旧原则腾位。 */
    private void requeueFrontLocked(String[] batch) {
        for (int i = batch.length - 1; i >= 0; i--) {
            if (queue.size() >= MAX_QUEUE) {
                queue.pollFirst();
                totalDropped++;
            }
            queue.addFirst(batch[i]);
        }
    }

    /** 唯一文件名：data/{yyyyMMdd}/{HHmmss-SSS}-{uuid8}.jsonl → 永远 201 新建。 */
    private static String buildApiPath() {
        Date now = new Date();
        String day = new SimpleDateFormat("yyyyMMdd", Locale.US).format(now);
        String clock = new SimpleDateFormat("HHmmss-SSS", Locale.US).format(now);
        String uid = UUID.randomUUID().toString().substring(0, 8);
        return REPO_CONTENTS + "data/" + day + "/" + clock + "-" + uid + ".jsonl";
    }

    private static String httpHint(int code) {
        switch (code) {
            case 401: return "凭据无效或已过期";
            case 403: return "无权限或被限流";
            case 404: return "仓库或路径不存在";
            case 409: return "路径冲突";
            case 422: return "请求被拒绝";
            default:  return "";
        }
    }

    private void recordError(String msg) {
        synchronized (lock) {
            lastError = new SimpleDateFormat("HH:mm:ss", Locale.US)
                    .format(new Date()) + " " + msg;
            lastErrorAt = System.currentTimeMillis();
        }
    }

    private static void sleepQuiet(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ── 测试辅助（包私有） ────────────────────────────────────────────

    int getUploadAttempts() {
        synchronized (lock) {
            return uploadAttempts;
        }
    }

    /** 等待第 n 次上传发起且该次已结束（fake transport 即时返回，确定性强）。 */
    void awaitAttempts(int n, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        synchronized (lock) {
            while ((uploadAttempts < n || uploading) && System.currentTimeMillis() < deadline) {
                try {
                    lock.wait(50);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }
    }

    /** 等待队列清空且无在途上传（批次已出队但仍在传也算未清空）。 */
    boolean awaitDrained(long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        synchronized (lock) {
            while ((!queue.isEmpty() || uploading) && System.currentTimeMillis() < deadline) {
                try {
                    lock.wait(50);
                } catch (InterruptedException e) {
                    return false;
                }
            }
            return queue.isEmpty() && !uploading;
        }
    }

    /** 默认 HTTP 实现：HttpURLConnection PUT 到 GitHub Contents API。 */
    static final class HttpTransport implements Transport {
        @Override
        public int upload(String apiPath, String credential, String base64Content) throws IOException {
            String name = apiPath.substring(apiPath.lastIndexOf('/') + 1);
            JSONObject body = new JSONObject();
            body.put("message", "sync " + name);
            body.put("content", base64Content);
            body.put("branch", "main");
            byte[] out = body.toString().getBytes(StandardCharsets.UTF_8);

            HttpURLConnection c = (HttpURLConnection) new URL(API_BASE + apiPath).openConnection();
            try {
                c.setRequestMethod("PUT");
                c.setConnectTimeout(10_000);
                c.setReadTimeout(30_000);
                c.setDoOutput(true);
                c.setFixedLengthStreamingMode(out.length);
                c.setRequestProperty("Accept", "application/vnd.github+json");
                c.setRequestProperty("Content-Type", "application/json");
                c.setRequestProperty("User-Agent", "uma-ramen-sync");
                c.setRequestProperty("Authorization", "Bearer " + credential);
                try (OutputStream os = c.getOutputStream()) {
                    os.write(out);
                }
                return c.getResponseCode();
            } finally {
                c.disconnect();
            }
        }
    }
}
