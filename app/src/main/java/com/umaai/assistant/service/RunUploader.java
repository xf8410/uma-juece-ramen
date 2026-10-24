package com.umaai.assistant.service;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 完成局自动入库：把 {@code runs/game<id>/} 原样打成 zip，直传训练数据仓。
 *
 * <p>设计口径：
 * <ul>
 *   <li>只上传「局包」，不做有损转换——envelopes / decisions.csv / meta.json 全在 zip 里；
 *   <li>队列落盘在 {@code files/pending_packs/}，网络失败不丢包，下次启动续传；
 *   <li>token 为空时完全停用（开发包默认不启用），不产生任何队列文件；
 *   <li>上传路径固定 {@code training_sessions/Ramen/packs/game<id>.zip}，与旧版
 *       {@code training_sessions/Ramen/<session>.json} 并存，互不覆盖。
 * </ul>
 */
public final class RunUploader {
    static final String REPO = "xf8410/uma-data";
    static final String BRANCH = "main";
    static final String DIR = "training_sessions/Ramen/packs";
    /** GitHub contents API 单文件上限 100MB；留足 base64 与内存余量，超过就不传。 */
    static final long MAX_PACK_BYTES = 32L * 1024 * 1024;

    private final File pendingDir;
    private final File runsDir;
    private final File stateDir;
    private final String token;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "RunPackUpload");
        t.setDaemon(true);
        return t;
    });
    private final AtomicBoolean draining = new AtomicBoolean();

    public RunUploader(File filesDirectory, String token) {
        this.pendingDir = new File(filesDirectory, "pending_packs");
        this.runsDir = new File(filesDirectory, "runs");
        this.stateDir = new File(filesDirectory, "upload_state");
        this.token = token == null ? "" : token.trim();
    }

    /** 有凭证才启用；没有凭证时局包只留在本机 runs/ 下。 */
    public boolean enabled() {
        return !token.isEmpty();
    }

    /** 把一局打包并入队。调用方可在任意线程；磁盘 IO 与网络都在上传线程串行执行。 */
    public void enqueue(File runDirectory, long runId) {
        if (!enabled() || runDirectory == null || runId <= 0) return;
        worker.execute(() -> {
            try {
                pack(runDirectory, pendingDir, runId);
                drain();
            } catch (Exception error) {
                android.util.Log.e("RamenUpload", "局包入队失败 game" + runId, error);
            }
        });
    }

    /** App 启动时续传上次遗留的局包；并补收上次进程被杀死前没来得及回调的局。 */
    public void resume() {
        if (!enabled()) return;
        worker.execute(() -> {
            scanExistingRuns();
            drain();
        });
    }

    /**
     * 扫描 runs/game&lt;id&gt;/，把上次进程没有完成上传的局重新打包。
     * 每局上传成功后在 upload_state/ 写下与包文件同 mtime 的标记；
     * meta.json 在标记之后又被写过（局继续进行）时才会再次入队。
     */
    private void scanExistingRuns() {
        File[] runs = runsDir.listFiles();
        if (runs == null) return;
        for (File run : runs) {
            if (!run.isDirectory()) continue;
            File meta = new File(run, "meta.json");
            long runId = parseRunId(run.getName());
            if (runId <= 0 || !meta.isFile()) continue;
            File state = new File(stateDir, "game" + runId + ".stamp");
            if (state.isFile() && state.lastModified() >= meta.lastModified()) continue;
            try {
                pack(run, pendingDir, runId);
            } catch (Exception error) {
                android.util.Log.w("RamenUpload", "启动补收失败 game" + runId, error);
            }
        }
    }

    /** 打包实现独立出来便于 JVM 测试：先在 pending 目录写临时文件，再原子改名。 */
    static File pack(File runDirectory, File pendingDirectory, long runId) throws IOException {
        if (runDirectory == null || !runDirectory.isDirectory()) {
            throw new IOException("局目录不存在：" + runDirectory);
        }
        if (!pendingDirectory.isDirectory() && !pendingDirectory.mkdirs()) {
            throw new IOException("无法创建局包队列目录");
        }
        File destination = new File(pendingDirectory, "game" + runId + ".zip");
        File temporary = new File(pendingDirectory, "game" + runId + ".zip.tmp");
        try {
            try (OutputStream out = new BufferedOutputStream(new FileOutputStream(temporary))) {
                RunArchive.export(runDirectory, out);
            }
        } catch (IOException | RuntimeException error) {
            temporary.delete();
            throw error;
        }
        if (temporary.length() > MAX_PACK_BYTES) {
            // 超限包不留在队列里反复重试；原始局记录仍在 runs/ 下。
            temporary.delete();
            throw new IOException("局包超过上限：" + temporary.length() + " bytes");
        }
        if (!temporary.renameTo(destination)) {
            temporary.delete();
            throw new IOException("局包重命名失败");
        }
        return destination;
    }

    private void drain() {
        if (!draining.compareAndSet(false, true)) return;
        try {
            File[] files = pendingDir.listFiles();
            if (files == null) return;
            List<File> packs = new ArrayList<>();
            for (File file : files) {
                if (file.isFile() && file.getName().endsWith(".zip")) packs.add(file);
            }
            packs.sort(Comparator.comparing(File::getName));
            for (File pack : packs) {
                if (upload(pack)) {
                    long runId = runIdOf(pack.getName());
                    markUploaded(pack, runId);
                    if (!pack.delete()) android.util.Log.w("RamenUpload", "已上传但删除失败：" + pack.getName());
                } else {
                    // 单包失败就停：网络整体不可用时不必把剩余包全部试一遍。
                    break;
                }
            }
        } finally {
            draining.set(false);
        }
    }

    private boolean upload(File pack) {
        long runId = runIdOf(pack.getName());
        if (runId <= 0 || pack.length() <= 0) return false;
        String path = DIR + "/" + pack.getName();
        String api = "https://api.github.com/repos/" + REPO + "/contents/" + path;
        try {
            String encoded = Base64.getEncoder().encodeToString(readAll(pack));
            String sha = remoteSha(api);
            for (int attempt = 0; attempt < 2; attempt++) {
                int code = put(api, pack, encoded, sha);
                if (code == 200 || code == 201) return true;
                // 另一台设备刚写过同一局包时 sha 会过期；刷新一次再试。
                if (code == 409 && attempt == 0) { sha = remoteSha(api); continue; }
                android.util.Log.w("RamenUpload", "上传失败 HTTP " + code + "：" + pack.getName());
                break;
            }
        } catch (Exception error) {
            android.util.Log.w("RamenUpload", "上传异常：" + pack.getName(), error);
        }
        return false;
    }

    private int put(String api, File pack, String encoded, String sha) throws Exception {
        org.json.JSONObject body = new org.json.JSONObject();
        body.put("message", "run pack " + pack.getName().replace(".zip", "")
                + " (" + (pack.length() / 1024) + "KB)");
        body.put("content", encoded);
        body.put("branch", BRANCH);
        if (sha != null) body.put("sha", sha);

        HttpURLConnection connection = (HttpURLConnection) new URL(api).openConnection();
        try {
            connection.setRequestMethod("PUT");
            connection.setRequestProperty("Authorization", "Bearer " + token);
            connection.setRequestProperty("Accept", "application/vnd.github+json");
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(60000);
            connection.setDoOutput(true);
            try (OutputStream out = connection.getOutputStream()) {
                out.write(body.toString().getBytes(StandardCharsets.UTF_8));
            }
            return connection.getResponseCode();
        } finally {
            connection.disconnect();
        }
    }

    /** 上传成功后记录“这一版已入仓”，标记 mtime 与包一致；之后 meta 再变才会重传。 */
    private void markUploaded(File pack, long runId) {
        if (runId <= 0) return;
        if (!stateDir.isDirectory() && !stateDir.mkdirs()) return;
        File state = new File(stateDir, "game" + runId + ".stamp");
        try {
            if (!state.isFile() && !state.createNewFile()) return;
            if (!state.setLastModified(pack.lastModified())) {
                android.util.Log.w("RamenUpload", "入库标记时间写入失败：" + state.getName());
            }
        } catch (IOException error) {
            android.util.Log.w("RamenUpload", "入库标记写入失败：" + state.getName(), error);
        }
    }

    private String remoteSha(String api) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(api).openConnection();
            connection.setRequestMethod("GET");
            connection.setRequestProperty("Authorization", "Bearer " + token);
            connection.setRequestProperty("Accept", "application/vnd.github+json");
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(20000);
            if (connection.getResponseCode() != 200) return null;
            org.json.JSONObject existing = new org.json.JSONObject(
                    new String(readAll(connection.getInputStream()), StandardCharsets.UTF_8));
            String sha = existing.optString("sha", "");
            return sha.isEmpty() ? null : sha;
        } catch (Exception ignored) {
            return null;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static byte[] readAll(File file) throws IOException {
        try (InputStream in = new FileInputStream(file)) {
            return readAll(in);
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
        return out.toByteArray();
    }

    static long runIdOf(String packName) {
        if (packName == null || !packName.startsWith("game") || !packName.endsWith(".zip")) return -1;
        return parseRunId(packName.substring(0, packName.length() - 4));
    }

    static long parseRunId(String directoryName) {
        if (directoryName == null || !directoryName.startsWith("game")) return -1;
        try {
            return Long.parseLong(directoryName.substring(4));
        } catch (NumberFormatException error) {
            return -1;
        }
    }
}
