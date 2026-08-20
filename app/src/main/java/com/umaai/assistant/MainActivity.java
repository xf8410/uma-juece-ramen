package com.umaai.assistant;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import com.umaai.assistant.service.FloatingWindowService;
import com.umaai.assistant.service.HttpDataService;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

public class MainActivity extends Activity {
    private static final int OVERLAY_PERMISSION_REQUEST = 123;
    private TextView status;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_main);
        status = findViewById(R.id.tv_status);
        Button start = findViewById(R.id.btn_start_float);
        Button test = findViewById(R.id.btn_test_http);
        Button stop = findViewById(R.id.btn_stop_float);

        start.setOnClickListener(v -> {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
                startActivityForResult(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName())), OVERLAY_PERMISSION_REQUEST);
            } else startFloatingService();
        });
        test.setOnClickListener(v -> new Thread(() -> {
            String value = httpGet("http://127.0.0.1:" + HttpDataService.PORT + "/status");
            runOnUiThread(() -> {
                status.setText(value == null ? "本地通信：离线，请先启动浮窗" : value);
                Toast.makeText(this, value == null ? "通信失败" : "通信正常", Toast.LENGTH_SHORT).show();
            });
        }).start());
        stop.setOnClickListener(v -> {
            stopService(new Intent(this, FloatingWindowService.class));
            status.setText("本地通信：已停止");
        });
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == OVERLAY_PERMISSION_REQUEST && Settings.canDrawOverlays(this)) startFloatingService();
    }

    private void startFloatingService() {
        Intent intent = new Intent(this, FloatingWindowService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent);
        else startService(intent);
        status.setText("拉面杯浮窗已启动 · 127.0.0.1:" + HttpDataService.PORT);
    }

    private static String httpGet(String address) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(address).openConnection();
            connection.setConnectTimeout(1500);
            connection.setReadTimeout(1500);
            if (connection.getResponseCode() != 200) return null;
            BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream(), "UTF-8"));
            StringBuilder out = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) out.append(line);
            reader.close();
            return out.toString();
        } catch (Exception ignored) {
            return null;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }
}
