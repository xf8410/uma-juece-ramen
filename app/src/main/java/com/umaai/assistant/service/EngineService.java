package com.umaai.assistant.service;

import android.app.Service;
import android.content.Intent;
import android.os.*;
import org.json.JSONObject;
import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/** Bound, non-exported service hosted in :engine. Binder carries paths, never snapshots. */
public final class EngineService extends Service {
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Messenger inbox = new Messenger(new Handler(Looper.getMainLooper(), this::receive));
    private volatile String activeRequest = "", configId = "", engineVersion = "";
    private volatile boolean ready, initializing;
    @Override public IBinder onBind(Intent intent) { return inbox.getBinder(); }
    private boolean receive(Message message) {
        Bundle args = message.getData(); Messenger destination = message.replyTo;
        if (message.what == EngineProtocol.SHUTDOWN) { shutdown(); return true; }
        if (message.what == EngineProtocol.CANCEL) {
            String request = args.getString("request_id", "");
            if (UmaNativeBridge.isLoaded()) UmaNativeBridge.nativeCancel(request);
            return true;
        }
        if (message.what == EngineProtocol.INIT) {
            if (initializing || ready) { sendError(destination, args, "引擎已初始化；配置变更需要重启进程"); return true; }
            initializing = true;
            worker.execute(() -> {
                try {
                    if (!UmaNativeBridge.isLoaded()) throw new IllegalStateException("APK 缺少当前架构的 libuma_jni.so");
                    JSONObject options = new JSONObject(args.getString("options", "{}"));
                    engineVersion=UmaNativeBridge.nativeVersion();
                    String nativeRevision=new JSONObject(engineVersion).getString("engine_revision");
                    File data = VersionedAssets.install(this,nativeRevision);
                    if(!"mcts".equals(options.optString("policy","mcts"))) {
                        File model=PrivateFiles.within(new File(getFilesDir(),"models"),options.getString("model_path"));
                        if(!ModelStore.validate(model.getParentFile(),nativeRevision).equals(model))throw new IllegalArgumentException("所选模型与已校验清单不一致");
                    }
                    JSONObject result = new JSONObject(UmaNativeBridge.nativeInit(data.getAbsolutePath(), options.toString()));
                    if (!result.optBoolean("ok", false)) throw new IllegalStateException(result.optString("error", "引擎初始化失败"));
                    configId = options.getString("config_id");
                    engineVersion = UmaNativeBridge.nativeVersion(); ready = true;
                    Bundle reply = new Bundle(); reply.putString("engine_version", engineVersion);
                    reply.putString("config_id", configId); reply.putString("data_directory", data.getAbsolutePath());
                    send(destination, EngineProtocol.READY, reply);
                } catch (Throwable error) { sendError(destination, args, detail(error)); }
                finally { initializing = false; }
            });
            return true;
        }
        if (message.what != EngineProtocol.EVALUATE && message.what != EngineProtocol.REVIEW) return false;
        if (!ready) { sendError(destination, args, "引擎尚未就绪"); return true; }
        if (!activeRequest.isEmpty()) { sendError(destination, args, "已有计算任务"); return true; }
        if (!configId.equals(args.getString("config_id"))) { sendError(destination, args, "引擎配置已变化，请重启计算"); return true; }
        String request = args.getString("request_id", "");
        if (!request.matches("[a-zA-Z0-9-]{1,100}")) { sendError(destination, args, "无效请求标识"); return true; }
        activeRequest = request;
        boolean review = message.what == EngineProtocol.REVIEW;
        worker.execute(() -> {
            try {
                File input = PrivateFiles.within(getFilesDir(), args.getString("input", ""));
                File output = new File(getFilesDir(), "engine-results/" + request);
                if (!output.isDirectory() && !output.mkdirs()) throw new IllegalStateException("无法创建结果目录");
                AtomicInteger sequence = new AtomicInteger(); String result;
                if (review) result = UmaNativeBridge.nativeReview(input.getAbsolutePath(), new File(input, "review").getAbsolutePath());
                else result = UmaNativeBridge.nativeEvaluate(input.getAbsolutePath(), args.getString("options"), request, event -> {
                    try {
                        File file = new File(output, "event-" + sequence.incrementAndGet() + ".json"); PrivateFiles.write(file, event);
                        Bundle reply = responseIdentity(args); reply.putString("file", file.getAbsolutePath()); send(destination, EngineProtocol.EVENT, reply);
                    } catch (Exception error) { throw new IllegalStateException("无法保存引擎事件", error); }
                });
                File file = new File(output, "result.json"); PrivateFiles.write(file, result);
                Bundle reply = responseIdentity(args); reply.putString("file", file.getAbsolutePath());
                activeRequest = ""; send(destination, EngineProtocol.RESULT, reply);
            } catch (Throwable error) { activeRequest = ""; sendError(destination, args, detail(error)); }
        });
        return true;
    }
    private Bundle responseIdentity(Bundle request) {
        Bundle response = new Bundle();
        response.putString("request_id", request.getString("request_id", "")); response.putString("config_id", configId);
        response.putString("engine_version", engineVersion); response.putLong("run_id", request.getLong("run_id"));
        response.putLong("snapshot_id", request.getLong("snapshot_id")); return response;
    }
    private void sendError(Messenger destination, Bundle request, String error) {
        Bundle response = responseIdentity(request); response.putString("error", error); send(destination, EngineProtocol.ERROR, response);
    }
    private static String detail(Throwable error) { return error.getClass().getSimpleName() + ": " + String.valueOf(error.getMessage()); }
    private static void send(Messenger destination, int type, Bundle args) {
        if (destination == null) return;
        try { Message response = Message.obtain(null, type); response.setData(args); destination.send(response); }
        catch (RemoteException ignored) { /* Owner is gone; onUnbind cancels the task. */ }
    }
    private void shutdown() {
        if (!activeRequest.isEmpty() && UmaNativeBridge.isLoaded()) UmaNativeBridge.nativeCancel(activeRequest);
        main.postDelayed(() -> android.os.Process.killProcess(android.os.Process.myPid()), 100); stopSelf();
    }
    @Override public boolean onUnbind(Intent intent) { shutdown(); return false; }
    @Override public void onDestroy() {
        worker.shutdownNow(); main.postDelayed(() -> android.os.Process.killProcess(android.os.Process.myPid()), 100); super.onDestroy();
    }
}
