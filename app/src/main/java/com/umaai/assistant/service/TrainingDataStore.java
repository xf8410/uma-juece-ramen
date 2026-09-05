package com.umaai.assistant.service;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.channels.FileChannel;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * 训练数据持久化层（App 私有目录，全量保留，数据不丢）。
 *
 * 布局：{filesDir}/training_data/ 下按 run 分文件（一局一个 .jsonl，run id
 * 命名，文件名排序即产生顺序），JSONL 行原文落盘（字段一字不改）；不做
 * 总量上限、不做自动清理。另有 seq_state 记账文件（只在 DELETE /data 时
 * 原子改写），记录已发号 seq 地板，保证清盘后 seq 永不复用。
 *
 * seq 语义（跨进程重启不重不漏的关键）：
 * - 读侧 seq = seqBase + 全局位置（文件按 run 名排序拼接后的 1-based 行号）。
 *   无删除历史时 meta 不存在 → seqBase=0 → 位置即 seq；重启后文件集合不变，
 *   派生值与重启前完全一致，零记账成本。
 * - DELETE 时把「seqBase + 现存行数」（已对外可见的最大 seq）写入 meta 并
 *   清空文件，新 seq 从其上继续，永不回退、永不复用。
 * - 写侧逐条发号（append 在锁内 ++lastIssuedSeq），与写盘队列 FIFO 顺序、
 *   磁盘位置三者一致。
 *
 * 时效红线（用户点名回合连续性）：落盘通道不攒批——记录产生后写盘线程
 * 立即逐条 write + force，单条产生到 append 完成远小于 1 秒（攒批只允许
 * 存在于上传通道）。进程被杀最多丢最后 1 条的极小窗口；force 保证连掉电
 * 也不丢已写记录。
 *
 * 写入失败（磁盘满/IO 异常）不丢数据：记录塞回重试（指数退避），内存
 * 上传队列不受影响；失败经 WriteErrorListener 上报（服务侧记日志）。
 *
 * 纯 Java（无 android.* 依赖），父目录由服务注入 getFilesDir()，可 JVM 单测
 * （临时目录 + 重开实例模拟进程重启）。
 */
public final class TrainingDataStore {
    /** 私有目录下的数据子目录名（挂在 getFilesDir() 下）。 */
    public static final String DIR_NAME = "training_data";

    static final String SEQ_FILE = "seq_state";
    static final String SEQ_FILE_TMP = "seq_state.tmp";
    static final String FILE_SUFFIX = ".jsonl";
    static final String UNKNOWN_RUN = "unknown";
    static final long RETRY_BACKOFF_MAX_MS = 5_000L;
    static final long WRITER_IDLE_POLL_MS = 500L;
    static final int DRAIN_POLL_MS = 20;

    /** 写盘失败回调（重试期间持续失败时可见；不影响内存队列）。 */
    public interface WriteErrorListener {
        void onWriteError(String message, int pendingCount);
    }

    /** 写盘队列元素：run 文件键 + 记录（queue FIFO 顺序 = 磁盘行序 = seq 序）。 */
    private static final class WriteItem {
        final String runKey;
        final RamenRecord record;

        WriteItem(String runKey, RamenRecord record) {
            this.runKey = runKey;
            this.record = record;
        }
    }

    /** 写线程的当前文件状态（run 切换时关旧开新）。 */
    private static final class WriterState {
        FileChannel channel;
        String runKey;
    }

    private final Object lock = new Object();
    private final File dir;
    private final long retryBackoffBaseMs;
    private final LinkedBlockingQueue<WriteItem> writeQueue = new LinkedBlockingQueue<>();

    private long seqBase;        // 读侧 seq 派生基准（boot=meta；DELETE 后抬到已发号最大值）
    private long lastIssuedSeq;  // 进程生命周期内已分配的最大 seq（永不回退）
    private int outstanding;     // 已 append 未落盘的记录数（含写线程手中 in-flight）
    private int persistedLines;  // 磁盘现存记录数（写线程成功 append 时维护）
    private int persistedRuns;   // 磁盘现存 run 文件数
    private String lastWriteError;
    private long lastWriteErrorAt;

    private volatile boolean running = true;
    private volatile WriteErrorListener errorListener;
    private final Thread writer;

    /** 生产构造：挂在 parentDir（服务传 getFilesDir()）下，默认退避起点。 */
    public TrainingDataStore(File parentDir) throws IOException {
        this(parentDir, 250L);
    }

    /** 测试构造：retryBackoffBaseMs 可调小，写失败重试场景快速收敛。 */
    TrainingDataStore(File parentDir, long retryBackoffBaseMs) throws IOException {
        this.dir = new File(parentDir, DIR_NAME);
        this.retryBackoffBaseMs = Math.max(1L, retryBackoffBaseMs);
        if (!dir.isDirectory() && !dir.mkdirs() && !dir.isDirectory()) {
            throw new IOException("cannot create dir " + dir);
        }
        synchronized (lock) {
            this.seqBase = readSeqMetaLocked();
            File[] existing = listRunFiles();
            this.persistedRuns = existing.length;
            this.persistedLines = countLines(existing);
            // 已发号最大值 = seqBase + 现存行数：重启后新记录从存量之上继续，
            // 保证 after 增量跨进程不重不漏（存量记录的 seq 由位置派生）
            this.lastIssuedSeq = this.seqBase + (long) this.persistedLines;
        }
        writer = new Thread(this::writeLoop, "RamenTrainingStore");
        writer.setDaemon(true);
        writer.start();
    }

    public void setWriteErrorListener(WriteErrorListener l) {
        errorListener = l;
    }

    // ── 写入 ──────────────────────────────────────────────────────────

    /**
     * 记录产生入口（RamenDecisionLogger 调用，主线程零 IO）：分配全局单调
     * seq 并入异步写盘队列；写盘线程立即逐条 write+force（≤1 秒落盘）。
     *
     * @return 已发号的记录（seq 与磁盘全局位置一致）；jsonl 空时返回 null
     */
    public RamenRecord append(String runId, String jsonl) {
        if (jsonl == null || jsonl.isEmpty()) return null;
        RamenRecord record;
        synchronized (lock) {
            record = new RamenRecord(jsonl, ++lastIssuedSeq);
            outstanding++; // 已发号未落盘；写线程 append 成功（或放弃）后归还
        }
        writeQueue.add(new WriteItem(sanitizeRunKey(runId), record));
        return record;
    }

    /**
     * 等待写盘队列清空（服务 onDestroy / DELETE 前调用）：等待所有已发号
     * 记录（含写线程手中 in-flight，即已 poll 出队列的那条）落盘完毕。
     * 只看 outstanding==0，不查队列空——队列空不等于 in-flight 写完。
     */
    public boolean flushPending(long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (outstandingForFlush()) return true;
            try {
                Thread.sleep(DRAIN_POLL_MS);
            } catch (InterruptedException e) {
                return outstandingForFlush();
            }
        }
        return outstandingForFlush();
    }

    private boolean outstandingForFlush() {
        synchronized (lock) {
            return outstanding == 0;
        }
    }

    /** 停止写盘线程（进程退出前调用；正常退出先 flushPending）。 */
    public void shutdown() {
        running = false;
        writer.interrupt();
    }

    /**
     * 清空持久化（DELETE /data 的落盘侧）：先等所有已发号记录落盘（队内与
     * in-flight 一并计入删除数），把「seqBase + 现存行数」（已对外可见的最大
     * seq）原子写入 meta 后删除全部 run 文件。seq 永不复用（lastIssuedSeq
     * 不回退）。
     *
     * @return 磁盘删除的记录条数（内存两区由调用方另计后合计）
     */
    public int clearAll(long drainTimeoutMs) {
        flushPending(drainTimeoutMs);
        int deleted;
        synchronized (lock) {
            File[] files = listRunFiles();
            deleted = countLines(files);
            writeSeqMetaLocked(seqBase + (long) deleted);
            for (File f : files) f.delete(); // 尽力而为；残留空壳不影响读侧
            seqBase += (long) deleted;
            persistedLines = 0;
            persistedRuns = 0;
        }
        return deleted;
    }

    // ── 读取（/data 与 /status 的数据源） ─────────────────────────────

    /**
     * 读盘分页：按 seq 升序返回 seq&gt;after 的前 limit 条（seq = seqBase +
     * 全局位置；run 文件按名字排序拼接，行序即产生顺序）。单文件读失败
     * 跳过不影响其余文件；损坏行原样带出（位置占位不塌缩，seq 不漂移）。
     */
    public List<RamenRecord> read(long after, int limit) {
        List<RamenRecord> out = new ArrayList<>();
        if (limit <= 0) return out;
        long base;
        synchronized (lock) {
            base = seqBase;
        }
        long position = 0;
        for (File f : listRunFiles()) {
            if (out.size() >= limit) break;
            try (BufferedReader r = new BufferedReader(new InputStreamReader(
                    new FileInputStream(f), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    if (line.isEmpty()) continue; // 写侧不产生空行，防御
                    position++;
                    long seq = base + position;
                    if (seq <= after) continue;
                    if (out.size() >= limit) break;
                    out.add(new RamenRecord(line, seq));
                }
            } catch (IOException e) {
                // 单文件读失败：跳过（数据仍在磁盘，下轮可读）
            }
        }
        return out;
    }

    /** 磁盘现存记录总数（/status 的 persisted_len）。 */
    public int getPersistedLines() {
        synchronized (lock) {
            return persistedLines;
        }
    }

    /** 磁盘现存 run 文件数（/status 的 persisted_runs）。 */
    public int getPersistedRuns() {
        synchronized (lock) {
            return persistedRuns;
        }
    }

    /** 最近一次写盘失败原因（null = 无失败；诊断用）。 */
    public String getLastWriteError() {
        synchronized (lock) {
            return lastWriteError;
        }
    }

    /** 当前已分配最大 seq（测试/诊断用）。 */
    public long getLastIssuedSeq() {
        synchronized (lock) {
            return lastIssuedSeq;
        }
    }

    // ── 写盘线程 ──────────────────────────────────────────────────────

    private void writeLoop() {
        WriterState st = new WriterState();
        while (running) {
            WriteItem item;
            try {
                item = writeQueue.poll(WRITER_IDLE_POLL_MS, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                break; // shutdown：flushPending 已在此前把队列写完
            }
            if (item != null) writeItemWithRetry(st, item);
        }
        closeQuiet(st);
    }

    /** 逐条 write+force；失败重试（指数退避），顺序不变、数据不丢。 */
    private void writeItemWithRetry(WriterState st, WriteItem item) {
        long backoff = retryBackoffBaseMs;
        while (running) {
            try {
                appendToFile(st, item); // 成功路径已在锁内 outstanding--
                return;
            } catch (IOException e) {
                closeQuiet(st); // 通道可能失效（磁盘满/目录被动过）：下次重开
                recordWriteError(e);
                if (!running) break;
                sleepQuiet(backoff);
                backoff = Math.min(backoff * 2, RETRY_BACKOFF_MAX_MS);
            }
        }
        // shutdown 放弃：记录未落盘（进程退出场景），复位计数避免误挂 flushPending
        synchronized (lock) {
            outstanding--;
        }
    }

    private void appendToFile(WriterState st, WriteItem item) throws IOException {
        boolean newFile = false;
        if (st.channel == null || !item.runKey.equals(st.runKey)) {
            closeQuiet(st);
            File f = new File(dir, item.runKey + FILE_SUFFIX);
            newFile = !f.isFile();
            st.channel = new FileOutputStream(f, true).getChannel();
            st.runKey = item.runKey;
        }
        byte[] bytes = (item.record.jsonl + "\n").getBytes(StandardCharsets.UTF_8);
        ByteBuffer buf = ByteBuffer.wrap(bytes);
        while (buf.hasRemaining()) st.channel.write(buf);
        st.channel.force(true); // 逐条刷盘：掉电也不丢（记录频率约每回合 1 条，代价可忽略）
        synchronized (lock) {
            persistedLines++;
            if (newFile) persistedRuns++;
            outstanding--; // 该条已落盘：归还发号时的 +1
        }
    }

    private void recordWriteError(IOException e) {
        String msg = String.valueOf(e.getMessage());
        synchronized (lock) {
            lastWriteError = msg;
            lastWriteErrorAt = System.currentTimeMillis();
        }
        WriteErrorListener l = errorListener;
        if (l != null) {
            try {
                l.onWriteError(msg, writeQueue.size());
            } catch (Exception ignored) {
            }
        }
    }

    // ── 磁盘工具 ──────────────────────────────────────────────────────

    /** run 文件清单（名字升序 = 产生顺序；run id 内嵌时间戳）。 */
    private File[] listRunFiles() {
        File[] files = dir.listFiles((d, name) -> name.endsWith(FILE_SUFFIX));
        if (files == null) return new File[0];
        Arrays.sort(files, Comparator.comparing(File::getName));
        return files;
    }

    /** 记录数 = 换行数 + 末行无换行时的 1（与 read() 的 readLine 口径一致）。 */
    static int countLines(File[] files) {
        int total = 0;
        for (File f : files) total += countLines(f);
        return total;
    }

    static int countLines(File f) {
        if (f.length() == 0) return 0;
        try (InputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[8192];
            long newlines = 0;
            boolean endsWithNewline = false;
            int n;
            while ((n = in.read(buf)) > 0) {
                for (int i = 0; i < n; i++) {
                    if (buf[i] == '\n') newlines++;
                }
                endsWithNewline = buf[n - 1] == '\n';
            }
            return (int) (endsWithNewline ? newlines : newlines + 1);
        } catch (IOException e) {
            return 0;
        }
    }

    /** seq 地板记账（仅 DELETE 时调用）：tmp 写入 + rename 原子落盘。 */
    private void writeSeqMetaLocked(long value) {
        File tmp = new File(dir, SEQ_FILE_TMP);
        File target = new File(dir, SEQ_FILE);
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write((value + "\n").getBytes(StandardCharsets.UTF_8));
            out.getChannel().force(true);
        } catch (IOException e) {
            synchronized (lock) {
                lastWriteError = "seq meta: " + e.getMessage();
                lastWriteErrorAt = System.currentTimeMillis();
            }
            tmp.delete();
            return; // 保留旧 meta；下次 DELETE 再补记账
        }
        if (!tmp.renameTo(target)) {
            tmp.delete(); // rename 失败同样保留旧 meta（degraded 而非损坏）
        }
    }

    /** 启动恢复：meta 缺失/损坏 → 0（位置即 seq；仅无删除历史时语义一致）。 */
    private long readSeqMetaLocked() {
        File f = new File(dir, SEQ_FILE);
        if (!f.isFile()) return 0L;
        try (BufferedReader r = new BufferedReader(new InputStreamReader(
                new FileInputStream(f), StandardCharsets.UTF_8))) {
            String line = r.readLine();
            return line == null ? 0L : Math.max(0L, Long.parseLong(line.trim()));
        } catch (Exception e) {
            return 0L;
        }
    }

    /** run 文件名净化（自产 run id 恒安全，防御外部输入）。 */
    static String sanitizeRunKey(String runId) {
        if (runId == null || runId.isEmpty()) return UNKNOWN_RUN;
        String cleaned = runId.replaceAll("[^A-Za-z0-9._-]", "_");
        return cleaned.isEmpty() ? UNKNOWN_RUN : cleaned;
    }

    private static void closeQuiet(WriterState st) {
        if (st.channel != null) {
            try {
                st.channel.close();
            } catch (IOException ignored) {
            }
            st.channel = null;
            st.runKey = null;
        }
    }

    private static void sleepQuiet(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
