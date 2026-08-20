package com.umaai.assistant.service;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.util.Log;

import androidx.documentfile.provider.DocumentFile;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Mirrors small collector JSON files to a user-selected SAF directory.
 * The app-private files remain authoritative; export failures never interrupt collection.
 */
public final class LocalBackupManager {
    private static final String TAG = "LocalBackupManager";
    private static final String PREFS = "local_backup";
    private static final String KEY_TREE_URI = "tree_uri";
    private static final String KEY_LAST_OK = "last_ok";
    private static final String KEY_LAST_ERROR = "last_error";
    private static final long MAX_JSON_BYTES = 8L * 1024L * 1024L;
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "LocalBackupWorker");
        t.setDaemon(true);
        return t;
    });

    private LocalBackupManager() {}

    public static void setTreeUri(Context context, Uri uri) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_TREE_URI, uri.toString())
                .remove(KEY_LAST_ERROR)
                .apply();
    }

    public static String getTreeUri(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_TREE_URI, null);
    }

    public static String getStatus(Context context) {
        SharedPreferences p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String uri = p.getString(KEY_TREE_URI, null);
        if (uri == null) return "本地备份：未选择目录";
        String error = p.getString(KEY_LAST_ERROR, "");
        if (!error.isEmpty()) return "本地备份失败：" + error;
        long lastOk = p.getLong(KEY_LAST_OK, 0);
        return lastOk > 0 ? "本地备份：已启用（最后成功 " + lastOk + "）" : "本地备份：已启用";
    }

    /** Mirror current state and all pending session payloads without exposing caches or credentials. */
    public static void backupAllAsync(Context context) {
        Context app = context.getApplicationContext();
        EXECUTOR.execute(() -> backupAllNow(app));
    }

    /** Mirror one current-state file after a successful private atomic write. */
    public static void backupCurrentAsync(Context context, File stateFile) {
        Context app = context.getApplicationContext();
        EXECUTOR.execute(() -> {
            try {
                DocumentFile root = getRoot(app);
                if (root == null || stateFile == null || !stateFile.isFile()) return;
                copyJson(app, root, "current_session.json", stateFile);
                markSuccess(app);
            } catch (Exception e) {
                markError(app, e);
            }
        });
    }

    /** Preserve a finalized/checkpoint payload under sessions; upload success does not delete it. */
    public static void backupSessionAsync(Context context, String sessionId, File payloadFile) {
        Context app = context.getApplicationContext();
        EXECUTOR.execute(() -> {
            try {
                DocumentFile root = getRoot(app);
                if (root == null || payloadFile == null || !payloadFile.isFile()) return;
                DocumentFile sessions = findOrCreateDir(root, "sessions");
                copyJson(app, sessions, safeSessionName(sessionId) + ".json", payloadFile);
                markSuccess(app);
            } catch (Exception e) {
                markError(app, e);
            }
        });
    }

    private static void backupAllNow(Context context) {
        try {
            DocumentFile root = getRoot(context);
            if (root == null) return;
            File files = context.getFilesDir();
            File state = new File(files, "uma_collector_state.json");
            if (state.isFile()) copyJson(context, root, "current_session.json", state);

            File pending = new File(files, "pending_uploads");
            File[] payloads = pending.listFiles((dir, name) -> name.endsWith(".json"));
            if (payloads != null) {
                DocumentFile sessions = findOrCreateDir(root, "sessions");
                for (File payload : payloads) copyJson(context, sessions, payload.getName(), payload);
            }
            markSuccess(context);
        } catch (Exception e) {
            markError(context, e);
        }
    }

    private static DocumentFile getRoot(Context context) throws Exception {
        String value = getTreeUri(context);
        if (value == null || value.isEmpty()) return null;
        DocumentFile root = DocumentFile.fromTreeUri(context, Uri.parse(value));
        if (root == null || !root.exists() || !root.canWrite()) {
            throw new IllegalStateException("目录授权已失效，请重新选择");
        }
        return root;
    }

    private static DocumentFile findOrCreateDir(DocumentFile parent, String name) throws Exception {
        DocumentFile child = parent.findFile(name);
        if (child == null) child = parent.createDirectory(name);
        if (child == null || !child.isDirectory()) throw new IllegalStateException("无法创建 " + name);
        return child;
    }

    private static void copyJson(Context context, DocumentFile parent, String name,
                                 File source) throws Exception {
        long size = source.length();
        if (size < 0 || size > MAX_JSON_BYTES) {
            throw new IllegalStateException(name + " 超过 8 MiB 安全上限");
        }
        DocumentFile target = parent.findFile(name);
        if (target == null) target = parent.createFile("application/json", name);
        if (target == null) throw new IllegalStateException("无法创建 " + name);

        try (InputStream in = new FileInputStream(source);
             OutputStream out = context.getContentResolver()
                     .openOutputStream(target.getUri(), "wt")) {
            if (out == null) throw new IllegalStateException("无法写入 " + name);
            byte[] buffer = new byte[16 * 1024];
            int n;
            while ((n = in.read(buffer)) >= 0) out.write(buffer, 0, n);
            out.flush();
        }
    }

    private static void markSuccess(Context context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putLong(KEY_LAST_OK, System.currentTimeMillis())
                .remove(KEY_LAST_ERROR)
                .apply();
    }

    private static void markError(Context context, Exception e) {
        String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        Log.w(TAG, "Local backup failed: " + message);
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_LAST_ERROR, message)
                .apply();
    }

    private static String safeSessionName(String value) {
        if (value == null || value.isEmpty()) return "unknown_session";
        return value.replaceAll("[^A-Za-z0-9._-]", "_");
    }
}
