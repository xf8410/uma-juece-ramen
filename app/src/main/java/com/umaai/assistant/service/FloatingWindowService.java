package com.umaai.assistant.service;

import android.app.*;
import android.content.*;
import android.content.res.Configuration;
import android.graphics.PixelFormat;
import android.os.*;
import android.provider.Settings;
import android.view.*;
import android.widget.TextView;
import androidx.core.app.NotificationCompat;
import com.umaai.assistant.MainActivity;
import com.umaai.assistant.R;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.net.*;
import java.util.Locale;
import java.util.concurrent.*;

/** Foreground collection and display. Computation lives exclusively in :engine. */
public final class FloatingWindowService extends Service implements HttpDataService.OnDataListener, EngineClient.Listener {
    public static final String ACTION_PAUSE = "com.umaai.assistant.PAUSE";
    public static final String ACTION_RESUME = "com.umaai.assistant.RESUME";
    public static final String ACTION_RECONFIGURE = "com.umaai.assistant.RECONFIGURE";
    public static final String ACTION_STOP = "com.umaai.assistant.STOP";
    private static final String CHANNEL = "ramen_overlay";
    private static final int NOTIFICATION = 1401;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ScheduledExecutorService poller = Executors.newSingleThreadScheduledExecutor();
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final SnapshotOrderTracker inputOrder=new SnapshotOrderTracker();
    private HttpDataService server;
    private EngineClient engine;
    private RunArchive archive;
    private volatile JSONObject options;
    private volatile JSONObject requestedOptions;
    private SnapshotEnvelope latest;
    private File latestFile;
    private JSONObject lastDecision;
    private volatile boolean destroyed, paused;
    private volatile long lastPushAt;
    private volatile String status = "等待采集端连接";
    private String source = "";
    private WindowManager windows;
    private View panel;
    private TextView title, recommendation, stats, details, origin, toggle;
    private BoardChartsView chart;
    private WindowManager.LayoutParams panelParams, toggleParams;
    private boolean compact;
    private float downX, downY;
    private int startX, startY;
    private boolean moved;
    private int pointer = -1;

    @Override public void onCreate() {
        super.onCreate();
        getSharedPreferences("runtime_status",MODE_PRIVATE).edit().putBoolean("running",true).apply();
        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel(CHANNEL, "拉面杯辅助", NotificationManager.IMPORTANCE_LOW));
        startForeground(NOTIFICATION, notification("等待采集端连接"));
        if (!Settings.canDrawOverlays(this)) { publish("尚未授予悬浮窗权限"); stopSelf(); return; }
        archive = new RunArchive(getFilesDir());
        compact = getSharedPreferences("ramen_overlay",MODE_PRIVATE).getBoolean("compact",true);
        try {
            JSONObject initialOptions = EngineSettings.read(this); createWindows();
            initializeEngine(initialOptions,0);
            server = new HttpDataService(this, () -> status); server.startServer();
            poller.scheduleWithFixedDelay(this::poll,0,2,TimeUnit.SECONDS);
        } catch (Exception error) { publish("启动失败：" + error.getMessage()); stopSelf(); }
    }
    @Override public IBinder onBind(Intent intent) { return null; }
    @Override public int onStartCommand(Intent intent,int flags,int id) {
        String action = intent == null ? "" : intent.getAction();
        if (ACTION_STOP.equals(action)) { stopSelf(); return START_NOT_STICKY; }
        if (ACTION_PAUSE.equals(action)) {
            paused = true; lastDecision = null;
            if(engine!=null) { engine.close(); engine=null; }
            publish("已暂停实时计算；采集与记录继续"); render();
        } else if (ACTION_RESUME.equals(action) || ACTION_RECONFIGURE.equals(action)) {
            paused=false; lastDecision=null;
            if(engine!=null) { engine.close();engine=null; }
            try {
                initializeEngine(EngineSettings.read(this),300);
            } catch(Exception error) { publish("配置读取失败："+error.getMessage()); }
        }
        return START_NOT_STICKY;
    }
    @Override public void onDestroy() {
        if(engine!=null)engine.close();
        destroyed=true; poller.shutdownNow();
        if(server!=null)server.stopServer();
        main.removeCallbacksAndMessages(null);
        if(windows!=null) {
            if(panel!=null && panel.isAttachedToWindow()) windows.removeView(panel);
            if(toggle!=null && toggle.isAttachedToWindow()) windows.removeView(toggle);
        }
        if(archive!=null) io.execute(() -> { try { archive.finish("process_exit"); } catch(Exception e) { android.util.Log.e("RamenArchive","关闭记录失败",e); } });
        io.shutdown(); stopForeground(STOP_FOREGROUND_REMOVE);
        getSharedPreferences("runtime_status",MODE_PRIVATE).edit().putBoolean("running",false).putString("status","已停止 · "+status).apply();
        super.onDestroy();
    }
    @Override public void onDataReceived(String data) { lastPushAt=System.currentTimeMillis();consume(data,"实时推送"); }
    private void consume(String data,String from) {
        if(destroyed || data==null || data.length()>PrivateFiles.MAX_SNAPSHOT_BYTES) return;
        io.execute(() -> {
            if(destroyed)return;
            try {
                // Publication and consumption both happen on this IO queue. A new
                // configuration is never visible before its provenance is registered.
                final JSONObject captureOptions=options;
                JSONObject json=new JSONObject(data);
                if(json.optInt("schema_version",-1)!=1) {
                    main.post(() -> {
                        if(destroyed)return;
                        latest=null; latestFile=null; lastDecision=null; source=from;
                        if(engine!=null)engine.invalidate();
                        publish("旧版摘要仅供展示，请升级采集协议 V1");
                        title.setText("拉面杯 · 旧版采集");
                        stats.setText(legacySummary(json));
                        recommendation.setText("尚无完整盘面，无法给出建议"); chart.clear();
                    }); return;
                }
                String readiness=SnapshotEnvelope.readinessProblem(json);
                if(!SnapshotEnvelope.hasIdentity(json)) {
                    String digest=PrivateFiles.sha256(data.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    PrivateFiles.write(new File(getFilesDir(),"unassigned-observations/"+digest+".json"),data);
                    main.post(()-> {
                        if(destroyed)return;
                        if(engine!=null)engine.invalidate();latest=null;latestFile=null;lastDecision=null;source=from;
                        publish(readiness.isEmpty()?"采集端尚未取得真实局与快照标识":readiness);
                        title.setText("拉面杯 · 采集中");recommendation.setText(status);chart.clear();
                        JSONObject display=json.optJSONObject("display_summary");stats.setText(display==null?"等待完整盘面":legacySummary(display));
                        details.setText(status);
                    });return;
                }
                SnapshotEnvelope snapshot=SnapshotEnvelope.parse(data);
                File file=archive.capture(snapshot,captureOptions);
                main.post(() -> {
                    if(destroyed)return;
                    if(!inputOrder.observe(snapshot.runId,snapshot.snapshotId))return;
                    if(latest!=null && latest.runId==snapshot.runId && snapshot.snapshotId<=latest.snapshotId)return;
                    source=from;
                    latest=snapshot; latestFile=file; lastDecision=null;
                    // Queue also rejects a late message from a retired run.
                    if(!snapshot.missingReason().isEmpty()) { if(engine!=null)engine.invalidate(); }
                    else submit(snapshot,file);
                    String missing=snapshot.missingReason();
                    if(!missing.isEmpty())publish(missing);
                    render();
                });
            } catch(Exception error) { main.post(() -> {
                if(!destroyed) {
                    if(engine!=null)engine.invalidate();latest=null;latestFile=null;lastDecision=null;
                    publish("采集数据无法使用："+error.getMessage());render();
                }
            }); }
        });
    }
    private void submit(SnapshotEnvelope snapshot,File file) {
        if(engine==null||paused||!snapshot.missingReason().isEmpty())return;
        final JSONObject configuration=options;
        io.execute(()-> {
            try {
                archive.capture(snapshot,configuration);
                main.post(()-> {
                    if(destroyed||paused||engine==null||configuration!=options||latest==null
                        ||latest.runId!=snapshot.runId||latest.snapshotId!=snapshot.snapshotId)return;
                    engine.submit(new EngineClient.Task(snapshot.runId,snapshot.snapshotId,engine.configId(),file,false));
                });
            }catch(Exception error){main.post(()->{if(!destroyed)publish("配置代记录失败："+error.getMessage());});}
        });
    }
    private void initializeEngine(JSONObject configuration,long delay) {
        requestedOptions=configuration;
        io.execute(()-> {
            try {
                if(destroyed||configuration!=requestedOptions)return;
                recordVersions(configuration);
                if(destroyed||configuration!=requestedOptions)return;
                options=configuration;
                main.postDelayed(()-> {
                    if(destroyed||paused||configuration!=options||configuration!=requestedOptions)return;
                    engine=new EngineClient(this,configuration,this);engine.start();
                    if(latest!=null&&latestFile!=null)submit(latest,latestFile);
                },delay);
            }catch(Exception error){main.post(()->{if(!destroyed)publish("版本信息无法登记："+error.getMessage());});}
        });
    }
    private void recordVersions(JSONObject configuration) throws Exception {
        JSONObject versions=new JSONObject();
        try {
            try(InputStream in=getAssets().open("gamedata/manifest.json")) {
                String raw=PrivateFiles.read(in,1024*1024);JSONObject manifest=new JSONObject(raw);
                versions.put("engine_revision",manifest.getString("engine_revision"))
                    .put("data_version",PrivateFiles.sha256(raw.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            }
            try(InputStream in=getAssets().open("gamedata/default_config.toml")) {
                String config=PrivateFiles.read(in,1024*1024);
                java.util.regex.Matcher bonus=java.util.regex.Pattern.compile("(?m)^\\s*mcts_turn_bonus\\s*=\\s*([+-]?\\d+)\\s*(?:#.*)?$").matcher(config);
                if(bonus.find())versions.put("mcts_turn_bonus",Integer.parseInt(bonus.group(1)));
            }
            String model="mcts".equals(configuration.optString("policy"))?"":configuration.optString("model_path","");
            versions.put("model_version",model.isEmpty()?"not_used":PrivateFiles.sha256(new File(model)));
        }catch(Exception error){android.util.Log.w("RamenArchive","版本来源尚未取得",error);}
        archive.registerConfiguration(configuration,versions);
    }
    private void poll() {
        if(destroyed||System.currentTimeMillis()-lastPushAt<5000)return;
        String value=get("http://127.0.0.1:18765/api/ai/ramen/v1/snapshot");
        if(value!=null)consume(value,"本机采集");
        else {
            String legacy=get("http://127.0.0.1:18765/summary");
            if(legacy!=null)consume(legacy,"旧版采集");
            else main.post(() -> {
                if(!destroyed&&System.currentTimeMillis()-lastPushAt>=5000) {
                    if(engine!=null)engine.invalidate();latest=null;latestFile=null;lastDecision=null;
                    publish("采集连接中断；等待本机 18765 端口");render();
                }
            });
        }
    }
    private static String get(String address) {
        HttpURLConnection connection=null;
        try {
            connection=(HttpURLConnection)new URL(address).openConnection();
            connection.setConnectTimeout(1000);connection.setReadTimeout(1500);
            if(connection.getResponseCode()!=200)return null;
            try(InputStream in=connection.getInputStream()) { return PrivateFiles.read(in,PrivateFiles.MAX_SNAPSHOT_BYTES); }
        } catch(Exception error) { return null; }
        finally { if(connection!=null)connection.disconnect(); }
    }
    @Override public void onStatus(String message) { if(!destroyed)publish(message); }
    @Override public void onEvent(EngineClient.Task task,JSONObject event) {
        JSONObject body=event.optJSONObject("event");
        if(body==null)body=event;
        final JSONObject payload=body;
        record(() -> archive.event(task,payload,event.optInt("event_seq",0)));
        if(!current(task))return;
        applyEvent(payload);render();
    }
    @Override public void onResult(EngineClient.Task task,JSONObject result) {
        record(() -> archive.result(task,result));
        if(!current(task))return;
        if(result.has("ok")&&!result.optBoolean("ok")) { publish("计算失败："+result.optString("error"));lastDecision=null;render();return; }
        JSONObject evaluation=result.optJSONObject("evaluation");
        if(evaluation==null)evaluation=result;
        JSONArray events=evaluation.optJSONArray("events");
        if(events!=null)for(int i=0;i<events.length();i++) { JSONObject event=events.optJSONObject(i);if(event!=null)applyEvent(event); }
        publish(lastDecision==null?"本阶段暂无可执行建议":"计算完成");render();
    }
    @Override public void onFailure(EngineClient.Task task,String error) {
        if(task!=null)record(() -> archive.event(task,new JSONObject().put("type","failed").put("error",error)));
        lastDecision=null;publish(error);render();
    }
    @Override public void onDiscarded(EngineClient.Task task,String reason) {
        record(() -> archive.event(task,new JSONObject().put("type","cancelled").put("reason",reason)));
    }
    private void applyEvent(JSONObject event) {
        String type=event.optString("type");
        if("decision".equals(type))lastDecision=event.optJSONObject("decision");
        else if("skipped".equals(type)||"failed".equals(type)||"cancelled".equals(type)) {
            lastDecision=null;publish(event.optString("reason",event.optString("error","等待新盘面")));
        }
    }
    private boolean current(EngineClient.Task task) {
        return !destroyed&&!paused&&latest!=null&&latest.runId==task.runId&&latest.snapshotId==task.snapshotId
            &&engine!=null&&engine.configId().equals(task.configId);
    }
    private interface DiskAction { void run() throws Exception; }
    private void record(DiskAction action) {
        if(destroyed)return;
        io.execute(() -> { try { action.run(); } catch(Exception error) { main.post(() -> { if(!destroyed)publish("记录保存失败："+error.getMessage()); }); } });
    }
    private void publish(String text) {
        status=text;
        getSharedPreferences("runtime_status",MODE_PRIVATE).edit().putString("status",text).putBoolean("paused",paused)
            .putLong("updated",System.currentTimeMillis()).apply();
        if(origin!=null)origin.setText(text);
        NotificationManager manager=getSystemService(NotificationManager.class);
        if(manager!=null&&!destroyed)manager.notify(NOTIFICATION,notification(text));
    }
    private void render() {
        if(panel==null||destroyed)return;
        if(latest==null) { recommendation.setText(status);return; }
        title.setText((latest.turn()<0?"回合未知":"第 "+(latest.turn()+1)+" 回合")+" · "+stageName(latest.stage));
        JSONObject state=latest.json.optJSONObject("state"), base=state==null?null:state.optJSONObject("baseGame");
        if(base!=null)stats.setText("体力 "+base.optString("vital","?")+"/"+base.optString("maxVital","?")
            +"  干劲 "+base.optString("motivation","?")+"\n五维 "+base.optString("fiveStatus","未采集")+"\n技能点 "+base.optString("skillPt","?"));
        else stats.setText("基础状态尚未采集");
        if(paused)recommendation.setText("已暂停计算");
        else if(lastDecision==null)recommendation.setText(status);
        else {
            JSONArray names=lastDecision.optJSONArray("candidate_descriptions"),scores=lastDecision.optJSONArray("candidate_scores");
            int selected=lastDecision.optInt("action_index",-1);
            String name=names!=null&&selected>=0&&selected<names.length()?names.optString(selected):"动作信息缺失";
            String scoreType=lastDecision.optString("score_type","");
            JSONObject extra=lastDecision.optJSONObject("scenario_extra");
            if(scoreType.isEmpty()&&extra!=null)scoreType=extra.optString("score_type","");
            recommendation.setText("建议："+name+(scoreType.isEmpty()?"":"\n评分口径："+scoreLabel(scoreType)));
            StringBuilder explanation=new StringBuilder();
            if(names!=null)for(int i=0;i<names.length();i++) {
                explanation.append(i==selected?"● ":"○ ").append(names.optString(i));
                if(scores!=null&&i<scores.length()&&!scores.isNull(i))explanation.append("  ").append(String.format(Locale.ROOT,"%.2f",scores.optDouble(i)));
                else explanation.append("  无评分");
                explanation.append('\n');
            }
            if(extra!=null) {
                JSONObject reason=extra.optJSONObject("reason");
                if(reason!=null) {
                    explanation.append("\n本次选项比较");
                    if(reason.has("chosen_n"))explanation.append(" · 已模拟 ").append(reason.optInt("chosen_n")).append(" 次");
                    explanation.append('\n');
                    JSONArray rivals=reason.optJSONArray("rivals");
                    if(rivals!=null)for(int i=0;i<rivals.length();i++) {
                        JSONObject rival=rivals.optJSONObject(i);if(rival==null)continue;
                        explanation.append(rival.optString("desc")).append("：相对建议 ")
                            .append(String.format(Locale.ROOT,"%+.1f",rival.optDouble("gap"))).append('\n');
                        for(String group:new String[]{"pros","cons"}) {
                            JSONArray changes=rival.optJSONArray(group);if(changes==null)continue;
                            for(int j=0;j<changes.length();j++) {
                                JSONObject change=changes.optJSONObject(j);if(change==null)continue;
                                explanation.append("  ").append(change.optString("label"))
                                    .append(String.format(Locale.ROOT," %+.1f",change.optDouble("delta"))).append('\n');
                            }
                        }
                    }
                }
                JSONObject hint=extra.optJSONObject("nn_hint");
                if(hint!=null)explanation.append("NN 参考：").append(hint.optString("choice","暂无建议")).append('\n');
            }
            if(base!=null)explanation.append("\n训练等级进度：").append(base.optString("trainLevelCount","未采集")).append('\n');
            JSONObject observed=latest.json.optJSONObject("display_summary");
            if(observed!=null) {
                String trainings=RamenBoardText.trainingLines(observed.optJSONArray("trainings"));
                if(!trainings.isEmpty())explanation.append("\n已观测训练\n").append(trainings);
            }
            details.setText(explanation.toString());
            if(!compact && names!=null && scores!=null && names.length()==scores.length())chart.setCandidates(names,scores,selected);
            else chart.clear();
        }
        if(lastDecision==null) { chart.clear();details.setText(latest.missingReason()); }
        stats.setVisibility(compact?View.GONE:View.VISIBLE);details.setVisibility(compact?View.GONE:View.VISIBLE);
        recommendation.setMaxLines(compact?5:Integer.MAX_VALUE);
        recommendation.setEllipsize(compact?android.text.TextUtils.TruncateAt.END:null);
        origin.setText(source+" · "+status);toggle.setText(compact?"展开":"收起");
        main.post(this::clamp);
    }
    public static String stageName(String stage) {
        switch(stage) {
            case "train":return "行动";case "ramen_select":return "吃面";case "special_select":return "隐藏风味";
            case "region_select":return "地区";case "super_ramen_select":return "超级拉面";case "event":return "事件";
            case "settlement":return "结算";default:return "等待采集";
        }
    }
    private static String scoreLabel(String type) {
        switch(type) {
            case "terminal_score":case "mcts":return "模拟终局评分";
            case "heuristic":case "handwritten":return "手写策略估值";
            case "nn":case "neural":return "模型输出";
            case "none":return "无评分";
            default:return type;
        }
    }
    private static String legacySummary(JSONObject json) {
        JSONObject c=json.optJSONObject("chara");if(c==null)c=json.optJSONObject("stats");
        return c==null?"未收到基础属性":"体力 "+c.optString("vital","?")+"  干劲 "+c.optString("motivation","?");
    }
    private void createWindows() {
        windows=getSystemService(WindowManager.class);
        panel=LayoutInflater.from(this).inflate(R.layout.floating_window,null);
        title=panel.findViewById(R.id.tv_turn);recommendation=panel.findViewById(R.id.tv_recommend);
        stats=panel.findViewById(R.id.tv_status);details=panel.findViewById(R.id.tv_trainings);
        origin=panel.findViewById(R.id.tv_source);chart=panel.findViewById(R.id.chart_candidates);
        panel.findViewById(R.id.tv_skill).setVisibility(View.GONE);panel.findViewById(R.id.tv_ramen).setVisibility(View.GONE);
        panelParams=new WindowManager.LayoutParams(dp(220),WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,PixelFormat.TRANSLUCENT);
        panelParams.gravity=Gravity.TOP|Gravity.START;panelParams.y=dp(100);
        windows.addView(panel,panelParams);
        toggle=new TextView(this);toggle.setText(compact?"展开":"收起");toggle.setTextSize(14);toggle.setTextColor(0xff17231e);
        toggle.setBackgroundColor(0xeeecf3ed);toggle.setPadding(dp(12),dp(8),dp(12),dp(8));
        toggleParams=new WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT,WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,PixelFormat.TRANSLUCENT);
        toggleParams.gravity=Gravity.TOP|Gravity.START;toggleParams.x=dp(220);toggleParams.y=dp(100);
        toggle.setContentDescription("点击展开或收起；拖动移动浮窗");
        toggle.setOnTouchListener((view,event)-> {
            switch(event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    clamp();pointer=event.getPointerId(0);downX=event.getRawX();downY=event.getRawY();startX=panelParams.x;startY=panelParams.y;moved=false;return true;
                case MotionEvent.ACTION_MOVE:
                    if(event.findPointerIndex(pointer)!=0)return true;
                    float dx=event.getRawX()-downX,dy=event.getRawY()-downY;
                    if(Math.hypot(dx,dy)>dp(6))moved=true;
                    if(moved) { panelParams.x=startX+Math.round(dx);panelParams.y=startY+Math.round(dy);clamp(); }return true;
                case MotionEvent.ACTION_UP:
                    if(!moved) { compact=!compact;getSharedPreferences("ramen_overlay",MODE_PRIVATE).edit().putBoolean("compact",compact).apply();render(); }
                    pointer=-1;return true;
                case MotionEvent.ACTION_CANCEL:pointer=-1;return true;
                default:return true;
            }
        });
        windows.addView(toggle,toggleParams);
    }
    private void clamp() {
        if(destroyed||panel==null||toggle==null)return;
        android.graphics.Rect bounds=Build.VERSION.SDK_INT>=30?windows.getCurrentWindowMetrics().getBounds():
            new android.graphics.Rect(0,0,getResources().getDisplayMetrics().widthPixels,getResources().getDisplayMetrics().heightPixels);
        int width=bounds.width(),height=bounds.height();
        panelParams.height=compact?WindowManager.LayoutParams.WRAP_CONTENT:Math.max(dp(180),Math.round(height*0.7f));
        panelParams.flags=WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|(compact?WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE:0);
        panelParams.width=Math.min(dp(220),Math.max(dp(120),width-dp(64)));
        panelParams.x=Math.max(0,Math.min(panelParams.x,width-panelParams.width));
        panelParams.y=Math.max(0,Math.min(panelParams.y,height-Math.max(dp(48),panel.getHeight())));
        toggleParams.x=Math.max(0,Math.min(panelParams.x+panelParams.width,width-Math.max(dp(56),toggle.getWidth())));
        toggleParams.y=Math.max(0,Math.min(panelParams.y,height-Math.max(dp(48),toggle.getHeight())));
        if(panel.isAttachedToWindow())windows.updateViewLayout(panel,panelParams);
        if(toggle.isAttachedToWindow())windows.updateViewLayout(toggle,toggleParams);
    }
    @Override public void onConfigurationChanged(Configuration configuration) { super.onConfigurationChanged(configuration);main.post(this::clamp); }
    private int dp(int value) { return Math.round(value*getResources().getDisplayMetrics().density); }
    private Notification notification(String text) {
        PendingIntent open=PendingIntent.getActivity(this,0,new Intent(this,MainActivity.class),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        PendingIntent pause=PendingIntent.getService(this,1,new Intent(this,FloatingWindowService.class).setAction(paused?ACTION_RESUME:ACTION_PAUSE),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop=PendingIntent.getService(this,2,new Intent(this,FloatingWindowService.class).setAction(ACTION_STOP),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        return new NotificationCompat.Builder(this,CHANNEL).setContentTitle("拉面杯本地辅助").setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_info_details).setContentIntent(open).setOngoing(true)
            .addAction(0,paused?"继续":"暂停",pause).addAction(0,"停止",stop).build();
    }
}
