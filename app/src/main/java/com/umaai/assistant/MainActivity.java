package com.umaai.assistant;

import android.Manifest;
import android.app.Activity;
import android.content.*;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import android.widget.*;
import com.umaai.assistant.service.*;

public class MainActivity extends Activity {
    private static final int OVERLAY = 123, NOTIFICATIONS = 124;
    private TextView status;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            String value=getSharedPreferences("runtime_status",MODE_PRIVATE).getString("status","尚未启动");
            status.setText(value);main.postDelayed(this,1500);
        }
    };
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);setContentView(R.layout.activity_main);status=findViewById(R.id.tv_status);
        findViewById(R.id.btn_start_float).setOnClickListener(v->requestStart());
        findViewById(R.id.btn_test_http).setOnClickListener(v-> {
            boolean paused=getSharedPreferences("runtime_status",MODE_PRIVATE).getBoolean("paused",false);
            startServiceAction(paused?FloatingWindowService.ACTION_RESUME:FloatingWindowService.ACTION_PAUSE);
        });
        findViewById(R.id.btn_stop_float).setOnClickListener(v-> {
            stopService(new Intent(this,FloatingWindowService.class));
            getSharedPreferences("runtime_status",MODE_PRIVATE).edit().putString("status","已停止").apply();status.setText("已停止");
        });
        findViewById(R.id.btn_settings).setOnClickListener(v->startActivity(new Intent(this,SettingsActivity.class)));
        findViewById(R.id.btn_records).setOnClickListener(v->startActivity(new Intent(this,RecordsActivity.class)));
        findViewById(R.id.btn_overlay).setOnClickListener(v-> {
            new android.app.AlertDialog.Builder(this).setTitle("实时浮窗")
                .setMessage("浮窗显示当前建议。轻点把手展开候选与理由；拖动把手移动位置。字体随系统字号缩放。")
                .setPositiveButton("启动浮窗",(d,w)->requestStart()).setNegativeButton("返回",null).show();
        });
    }
    @Override protected void onResume() { super.onResume();main.post(refresh); }
    @Override protected void onPause() { main.removeCallbacks(refresh);super.onPause(); }
    private void requestStart() {
        if(!Settings.canDrawOverlays(this)) {
            startActivityForResult(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,Uri.parse("package:"+getPackageName())),OVERLAY);return;
        }
        if(Build.VERSION.SDK_INT>=33&&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},NOTIFICATIONS);return;
        }
        startServiceAction(FloatingWindowService.ACTION_RESUME);
    }
    @Override protected void onActivityResult(int request,int result,Intent data) {
        super.onActivityResult(request,result,data);
        if(request==OVERLAY&&Settings.canDrawOverlays(this))requestStart();
        else if(request==OVERLAY)status.setText("需要悬浮窗权限才能在游戏中显示建议");
    }
    @Override public void onRequestPermissionsResult(int request,String[] permissions,int[] results) {
        super.onRequestPermissionsResult(request,permissions,results);
        if(request==NOTIFICATIONS) {
            startServiceAction(FloatingWindowService.ACTION_RESUME);
            if(results.length==0||results[0]!=PackageManager.PERMISSION_GRANTED)
                Toast.makeText(this,"通知未授权，可在此页面暂停或停止辅助",Toast.LENGTH_LONG).show();
        }
    }
    private void startServiceAction(String action) {
        if(!Settings.canDrawOverlays(this)) { requestStart();return; }
        try {
            startForegroundService(new Intent(this,FloatingWindowService.class).setAction(action));
            status.setText("正在启动本地辅助…");
        } catch(RuntimeException error) { status.setText("启动失败："+error.getMessage()); }
    }
}
