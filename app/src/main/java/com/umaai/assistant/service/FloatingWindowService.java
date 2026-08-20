package com.umaai.assistant.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;

import androidx.core.app.NotificationCompat;

import com.umaai.assistant.R;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

public final class FloatingWindowService extends Service implements HttpDataService.OnDataListener {
    private static final String CHANNEL = "ramen_overlay";
    private static final int NOTIFICATION_ID = 1401;
    private static final long STALE_MS = 5000;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final TrainingEvaluator evaluator = new TrainingEvaluator();
    private WindowManager windowManager;
    private WindowManager.LayoutParams params;
    private View panel;
    private TextView turnView, recommendView, statusView, ramenView, trainingsView, sourceView;
    private HttpDataService server;
    private volatile boolean polling;
    private volatile long lastDataAt;

    @Override public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        startForeground(NOTIFICATION_ID, notification("等待拉面杯数据"));
        createPanel();
        try { server = new HttpDataService(this); server.startServer(); }
        catch (Exception e) { stopSelf(); return; }
        startPolling();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
    @Override public int onStartCommand(Intent intent, int flags, int startId) { return START_STICKY; }

    @Override public void onDestroy() {
        polling = false;
        if (server != null) server.stopServer();
        if (panel != null && windowManager != null) windowManager.removeView(panel);
        super.onDestroy();
    }

    @Override public void onDataReceived(String data) { consume(data, "push:18766"); }

    private void consume(String data, String source) {
        if (data == null || data.isEmpty()) return;
        try {
            JSONObject summary = new JSONObject(data);
            if (!summary.has("stats")) return;
            String scenario = summary.optString("scenario", "");
            lastDataAt = System.currentTimeMillis();
            main.post(() -> render(summary, scenario, source));
        } catch (Exception ignored) {}
    }

    private void render(JSONObject summary, String scenario, String source) {
        if (!"Ramen".equals(scenario)) {
            turnView.setText("检测到剧本：" + (scenario.isEmpty() ? "未知" : scenario));
            recommendView.setText("本安装包仅支持拉面杯，请安装对应剧本版本");
            recommendView.setTextColor(0xFFFF5555);
            statusView.setText(""); ramenView.setText(""); trainingsView.setText("");
            sourceView.setText(source);
            return;
        }
        JSONObject stats = summary.optJSONObject("stats");
        if (stats == null) return;
        int turn = summary.optInt("turn", -1);
        turnView.setText(turn > 0 ? "第" + turn + "回合" : "拉面杯");
        String ramenAdvice = RamenDecisionSupport.recommend(summary);
        String ai = summary.optJSONObject("ai") != null
                ? summary.optJSONObject("ai").optString("best", "") : "";
        recommendView.setText(ai.isEmpty() ? ramenAdvice : "插件推荐：" + ai + " | " + ramenAdvice);
        recommendView.setTextColor(0xFF00FF88);
        statusView.setText("速" + stats.optInt("speed") + " 耐" + stats.optInt("stamina")
                + " 力" + stats.optInt("power") + " 根" + stats.optInt("guts")
                + " 智" + stats.optInt("wiz") + " Pt" + stats.optInt("skill_point")
                + "\n体力" + stats.optInt("vital") + "/" + stats.optInt("max_vital")
                + " 干劲" + stats.optString("motivation", "?"));
        ramenView.setText(RamenDecisionSupport.stateLine(summary));
        trainingsView.setText(renderTrainings(summary.optJSONArray("trainings"))
                + "\n" + evaluator.recommend(summary));
        sourceView.setText(source + " · upstream xulai1001/umaai-rs");
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) manager.notify(NOTIFICATION_ID, notification("拉面杯 第" + turn + "回合"));
    }

    private String renderTrainings(JSONArray trainings) {
        if (trainings == null) return "训练数据：无";
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < trainings.length(); i++) {
            JSONObject tr = trainings.optJSONObject(i);
            if (tr == null) continue;
            JSONObject g = tr.optJSONObject("gains");
            if (out.length() > 0) out.append('\n');
            out.append(shortName(tr.optString("name", "?"))).append(':');
            if (g != null) {
                appendGain(out, "速", g.optInt("Speed")); appendGain(out, "耐", g.optInt("Stamina"));
                appendGain(out, "力", g.optInt("Power")); appendGain(out, "根", g.optInt("Guts"));
                appendGain(out, "智", g.optInt("Wiz")); appendGain(out, "Pt", g.optInt("SkillPt"));
                int hp = g.optInt("HP"); if (hp != 0) out.append(" HP").append(hp > 0 ? "+" : "").append(hp);
            }
            int failure = tr.optInt("failure_rate", 0); if (failure > 0) out.append(" 失败").append(failure).append('%');
        }
        return out.toString();
    }

    private static void appendGain(StringBuilder out, String label, int value) { if (value != 0) out.append(' ').append(label).append(value > 0 ? "+" : "").append(value); }
    private static String shortName(String name) {
        if ("Speed".equalsIgnoreCase(name)) return "速"; if ("Stamina".equalsIgnoreCase(name)) return "耐";
        if ("Power".equalsIgnoreCase(name)) return "力"; if ("Guts".equalsIgnoreCase(name)) return "根";
        if ("Wisdom".equalsIgnoreCase(name)) return "智"; return name;
    }

    private void createPanel() {
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        panel = LayoutInflater.from(this).inflate(R.layout.floating_window, null);
        int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY : WindowManager.LayoutParams.TYPE_PHONE;
        params = new WindowManager.LayoutParams(300, WindowManager.LayoutParams.WRAP_CONTENT, type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START; params.x = 20; params.y = 180;
        turnView = panel.findViewById(R.id.tv_turn); recommendView = panel.findViewById(R.id.tv_recommend);
        statusView = panel.findViewById(R.id.tv_status); ramenView = panel.findViewById(R.id.tv_ramen);
        trainingsView = panel.findViewById(R.id.tv_trainings); sourceView = panel.findViewById(R.id.tv_source);
        panel.findViewById(R.id.btn_close).setOnClickListener(v -> stopSelf());
        panel.setOnTouchListener(new View.OnTouchListener() {
            int startX, startY; float downX, downY;
            @Override public boolean onTouch(View v, MotionEvent e) {
                if (e.getAction() == MotionEvent.ACTION_DOWN) { startX=params.x; startY=params.y; downX=e.getRawX(); downY=e.getRawY(); return true; }
                if (e.getAction() == MotionEvent.ACTION_MOVE) { params.x=startX+(int)(e.getRawX()-downX); params.y=startY+(int)(e.getRawY()-downY); windowManager.updateViewLayout(panel, params); return true; }
                return false;
            }
        });
        windowManager.addView(panel, params);
    }

    private void startPolling() {
        polling = true;
        Thread thread = new Thread(() -> {
            while (polling) {
                if (System.currentTimeMillis() - lastDataAt > STALE_MS) {
                    String body = get("http://127.0.0.1:18765/summary");
                    if (body != null) consume(body, "poll:18765");
                }
                try { Thread.sleep(2000); } catch (InterruptedException e) { return; }
            }
        }, "RamenSummaryPoll");
        thread.setDaemon(true); thread.start();
    }

    private static String get(String address) {
        HttpURLConnection c = null;
        try {
            c=(HttpURLConnection)new URL(address).openConnection(); c.setConnectTimeout(1500); c.setReadTimeout(2000);
            if(c.getResponseCode()!=200)return null; BufferedReader r=new BufferedReader(new InputStreamReader(c.getInputStream(),"UTF-8"));
            StringBuilder b=new StringBuilder(); String line; while((line=r.readLine())!=null)b.append(line); r.close(); return b.toString();
        } catch(Exception ignored){return null;} finally{if(c!=null)c.disconnect();}
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager m=getSystemService(NotificationManager.class);
            if(m!=null)m.createNotificationChannel(new NotificationChannel(CHANNEL,"拉面杯浮窗",NotificationManager.IMPORTANCE_LOW));
        }
    }
    private Notification notification(String text) { return new NotificationCompat.Builder(this,CHANNEL).setContentTitle("拉面杯决策浮窗").setContentText(text).setSmallIcon(android.R.drawable.ic_menu_info_details).setOngoing(true).build(); }
}
