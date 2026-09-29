package com.umaai.assistant.service;

import android.content.*;
import android.os.*;
import org.json.JSONObject;
import java.io.File;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Owner-thread coordinator: cancel old work, retain one latest pending input. */
public final class EngineClient implements AutoCloseable {
    public interface Listener {
        void onStatus(String status);
        void onEvent(Task task, JSONObject event);
        void onResult(Task task, JSONObject result);
        void onFailure(Task task, String error);
        default void onDiscarded(Task task, String reason) {}
    }
    public static final class Task extends LatestRequestQueue.Request {
        public final File input; public final boolean review;
        public Task(long run, long sequence, String config, File input, boolean review) {
            super(run, sequence, UUID.randomUUID().toString(), config); this.input = input; this.review = review;
        }
    }
    private final Context context; private final Listener listener; private final JSONObject options;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService reader = Executors.newSingleThreadExecutor();
    private final LatestRequestQueue<Task> queue = new LatestRequestQueue<>();
    private final Map<String,Integer> crashes = new HashMap<>();
    private final InitializationRetryBudget initialization=new InitializationRetryBudget();
    private Messenger remote; private ServiceConnection connection;
    private boolean bound, ready, closed; private int generation; private String engineVersion = "";
    public EngineClient(Context context, JSONObject options, Listener listener) {
        this.context = context.getApplicationContext(); this.options = options; this.listener = listener;
    }
    public void start() { checkThread(); bind(); }
    public void invalidate() {
        checkThread();
        Task task = queue.running(); if (task != null) listener.onDiscarded(task, "当前输入不能用于决策");
        disconnect(); queue.clear();
    }
    public String configId() { return options.optString("config_id"); }
    public boolean submit(Task task) {
        checkThread();
        if(!initialization.canAttempt()) { listener.onFailure(task,"引擎初始化连续失败，已停止自动重试；请重新连接或修改配置");return false; }
        Task replaced=queue.pending();
        if (closed || !task.configId.equals(configId()) || !queue.offer(task)) return false;
        if(replaced!=null)listener.onDiscarded(replaced,"更新的快照替换了待处理任务");
        listener.onStatus(task.review ? "正在准备本地复盘" : "正在计算当前盘面…");
        Task running = queue.running();
        if (running != null) {
            listener.onDiscarded(running, "新快照已到达，旧任务取消");
            Bundle cancel = new Bundle(); cancel.putString("request_id", running.requestId); send(EngineProtocol.CANCEL, cancel);
            main.postDelayed(() -> { if (!closed && queue.running() == running) restart(false, "旧任务取消超时，正在重启引擎"); }, 1000);
        }
        if (!bound) bind(); dispatch(); return true;
    }
    private void bind() {
        if (closed || bound || !initialization.canAttempt()) return;
        int epoch = ++generation;
        Messenger replies = new Messenger(new Handler(Looper.getMainLooper(), message -> receive(epoch, message)));
        connection = new ServiceConnection() {
            @Override public void onServiceConnected(ComponentName name, IBinder binder) {
                if (closed || epoch != generation) return;
                remote = new Messenger(binder); Bundle init = new Bundle(); init.putString("options", options.toString());
                send(EngineProtocol.INIT, init, replies);
            }
            @Override public void onServiceDisconnected(ComponentName name) {
                if (!closed && epoch == generation) restart(true, "引擎进程退出，正在恢复");
            }
            @Override public void onBindingDied(ComponentName name) { onServiceDisconnected(name); }
            @Override public void onNullBinding(ComponentName name) { if (!closed && epoch == generation) failInitialization("引擎服务未提供连接"); }
        };
        bound = context.bindService(new Intent(context, EngineService.class), connection, Context.BIND_AUTO_CREATE);
        if (!bound) failInitialization("无法连接本地引擎服务");
        else {
            listener.onStatus("正在校验游戏数据并加载引擎…");
            main.postDelayed(() -> { if (!closed && epoch == generation && !ready) failInitialization("引擎初始化超时，请检查数据包与原生库"); }, 30000);
        }
    }
    private boolean receive(int epoch, Message message) {
        if (closed || epoch != generation) return true;
        Bundle body = message.getData();
        if (message.what == EngineProtocol.READY) {
            if (!configId().equals(body.getString("config_id"))) { failInitialization("初始化配置不一致"); return true; }
            engineVersion = body.getString("engine_version", ""); ready = true; listener.onStatus("本地引擎就绪"); dispatch(); return true;
        }
        Task task = queue.running();
        if (message.what == EngineProtocol.ERROR && body.getString("request_id", "").isEmpty()) {
            failInitialization(body.getString("error", "引擎初始化失败")); return true;
        }
        if (task == null || !task.requestId.equals(body.getString("request_id")) || task.runId != body.getLong("run_id")
                || task.snapshotId != body.getLong("snapshot_id") || !task.configId.equals(body.getString("config_id"))
                || !engineVersion.equals(body.getString("engine_version"))) return true;
        if (message.what == EngineProtocol.ERROR) {
            boolean current = queue.finish(task.requestId);
            if (current) listener.onFailure(task, body.getString("error", "计算失败")); dispatch(); return true;
        }
        boolean terminal = message.what == EngineProtocol.RESULT;
        if (!terminal && message.what != EngineProtocol.EVENT) return false;
        String path = body.getString("file", "");
        reader.execute(() -> {
            JSONObject result;
            try { result = new JSONObject(PrivateFiles.read(PrivateFiles.within(context.getFilesDir(), path), 16 * 1024 * 1024)); }
            catch (Exception error) {
                main.post(() -> {
                    if (closed || epoch != generation) return;
                    if (queue.finish(task.requestId)) listener.onFailure(task, "无法读取引擎结果：" + error.getMessage()); dispatch();
                }); return;
            }
            main.post(() -> {
                if (closed || epoch != generation) return;
                if (terminal) { if (queue.finish(task.requestId)) listener.onResult(task, result); dispatch(); }
                else if (queue.accepts(task.requestId)) listener.onEvent(task, result);
            });
        }); return true;
    }
    private void dispatch() {
        if (closed || !ready) return;
        Task task = queue.startNext(); if (task == null) return;
        Bundle args = new Bundle(); args.putString("input", task.input.getAbsolutePath()); args.putString("request_id", task.requestId);
        args.putString("config_id", task.configId); args.putString("options", options.toString());
        args.putLong("run_id", task.runId); args.putLong("snapshot_id", task.snapshotId);
        send(task.review ? EngineProtocol.REVIEW : EngineProtocol.EVALUATE, args);
    }
    private void send(int what, Bundle args) {
        int epoch = generation;
        send(what, args, new Messenger(new Handler(Looper.getMainLooper(), message -> receive(epoch, message))));
    }
    private void send(int what, Bundle args, Messenger replies) {
        if (remote == null) return;
        try { Message message = Message.obtain(null, what); message.setData(args); message.replyTo = replies; remote.send(message); }
        catch (RemoteException error) { restart(true, "引擎连接断开"); }
    }
    private void restart(boolean crash, String status) {
        if(crash&&!ready) { failInitialization("引擎初始化期间进程退出");return; }
        Task interrupted = queue.releaseRunning();
        if (interrupted != null && crash) {
            String key = interrupted.runId + ":" + interrupted.snapshotId + ":" + interrupted.configId;
            int count = crashes.containsKey(key) ? crashes.get(key) + 1 : 1; crashes.put(key, count);
            if (count < 2) queue.retry(interrupted);
            else if (queue.accepts(interrupted.requestId)) listener.onFailure(interrupted, "同一快照连续两次导致引擎退出，已停止自动重试");
        }
        disconnect(); listener.onStatus(status); main.postDelayed(this::bind, 250);
    }
    private void failInitialization(String error) {
        if(closed||!initialization.canAttempt())return;
        boolean retry=initialization.recordFailure();
        disconnect();
        if(retry) {
            listener.onStatus(error+"；正在执行最后一次初始化重试");
            main.postDelayed(this::bind,250);return;
        }
        Task task = queue.releaseRunning(); if (task == null) task = queue.startNext();
        if (task != null) queue.finish(task.requestId);
        listener.onFailure(task,error+"；初始化已失败两次，停止自动重试。请重新连接或修改配置");
    }
    private void disconnect() {
        generation++; ready = false;
        if (remote != null) { try { remote.send(Message.obtain(null, EngineProtocol.SHUTDOWN)); } catch (RemoteException ignored) {} }
        if (bound && connection != null) context.unbindService(connection);
        bound = false; remote = null;
    }
    @Override public void close() {
        checkThread(); if (closed) return;
        Task task=queue.running();if(task!=null)listener.onDiscarded(task,"已暂停或停止计算");
        Task pending=queue.pending();if(pending!=null)listener.onDiscarded(pending,"尚未开始的任务已取消");
        closed = true; disconnect(); queue.clear(); main.removeCallbacksAndMessages(null); reader.shutdownNow();
    }
    private static void checkThread() {
        if (Looper.myLooper() != Looper.getMainLooper()) throw new IllegalStateException("EngineClient 必须由主线程调用");
    }
}
