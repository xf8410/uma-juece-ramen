package com.umaai.assistant;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.umaai.assistant.service.DataCollector;
import com.umaai.assistant.service.FloatingWindowService;
import com.umaai.assistant.service.HttpDataService;
import com.umaai.assistant.service.LocalBackupManager;
import com.umaai.assistant.service.RemoteDataLoader;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

public class MainActivity extends Activity {
    private static final int OVERLAY_PERMISSION_REQUEST = 123;
    private static final int BACKUP_DIRECTORY_REQUEST = 124;
    public static final String PREFS_NAME = "uma_juece_prefs";
    public static final String KEY_SCENARIO = "selected_scenario";

    // 剧本列表：中文显示名 → 内部标识（按实装顺序，共13个）
    public static final String[] SCENARIO_LABELS = {
        "URA总决赛",
        "青春杯",
        "巅峰杯",
        "偶像杯",
        "女神杯",
        "凯旋门杯",
        "UAF运动会",
        "种田杯",
        "赛博杯",
        "传奇杯",
        "无人岛杯",
        "温泉杯",
        "育马者杯",
        "拉面杯"
    };
    public static final String[] SCENARIO_IDS = {
        "URA",
        "Aoharu",
        "Climax",
        "GrandDrive",
        "GrandMasters",
        "LArc",
        "UAF",
        "Harvest",
        "Mecha",
        "Legends",
        "DesertIsland",
        "HotSpring",
        "Dreams",
        "Ramen"
    };

    private TextView tvStatus;
    private TextView tvDataStatus;
    private TextView tvBackupStatus;
    private Spinner spinnerScenario;
    private boolean spinnerInitialized = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        tvStatus = findViewById(R.id.tv_status);

        // === 剧本选择 ===
        spinnerScenario = findViewById(R.id.spinner_scenario);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, SCENARIO_LABELS);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerScenario.setAdapter(adapter);

        // 恢复上次选择
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        String savedScenario = prefs.getString(KEY_SCENARIO, "URA");
        for (int i = 0; i < SCENARIO_IDS.length; i++) {
            if (SCENARIO_IDS[i].equals(savedScenario)) {
                spinnerScenario.setSelection(i);
                break;
            }
        }

        spinnerScenario.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int pos, long id) {
                if (!spinnerInitialized) {
                    spinnerInitialized = true;
                    return;
                }
                String scenarioId = SCENARIO_IDS[pos];
                prefs.edit().putString(KEY_SCENARIO, scenarioId).apply();
                // 通知浮窗更新剧本
                Intent intent = new Intent(FloatingWindowService.ACTION_SCENARIO);
                intent.putExtra("scenario", scenarioId);
                LocalBroadcastManager.getInstance(MainActivity.this).sendBroadcast(intent);
                Toast.makeText(MainActivity.this,
                        "剧本切换: " + SCENARIO_LABELS[pos], Toast.LENGTH_SHORT).show();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });

        // === 按钮 ===
        Button btnStart = findViewById(R.id.btn_start_float);
        btnStart.setOnClickListener(v -> {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
                Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName()));
                startActivityForResult(intent, OVERLAY_PERMISSION_REQUEST);
            } else {
                startFloatingService();
            }
        });

        Button btnTestFake = findViewById(R.id.btn_test_fake);
        btnTestFake.setOnClickListener(v -> {
            new Thread(() -> {
                String result = httpGet("http://127.0.0.1:" + HttpDataService.PORT + "/test_board");
                runOnUiThread(() -> {
                    if (result != null) {
                        Toast.makeText(this, "小黑板测试数据已推送", Toast.LENGTH_SHORT).show();
                    } else {
                        Toast.makeText(this, "推送失败，请先启动悬浮窗", Toast.LENGTH_SHORT).show();
                    }
                });
            }).start();
        });

        Button btnTestHttp = findViewById(R.id.btn_test_http);
        btnTestHttp.setOnClickListener(v -> testHttpCommunication());

        Button btnStopFloat = findViewById(R.id.btn_stop_float);
        btnStopFloat.setOnClickListener(v -> {
            stopService(new Intent(this, FloatingWindowService.class));
            Toast.makeText(this, "悬浮窗已停止", Toast.LENGTH_SHORT).show();
            updateStatus();
        });

        // === 数据收集状态 ===
        tvDataStatus = findViewById(R.id.tv_data_status);
        Button btnUploadData = findViewById(R.id.btn_upload_data);
        btnUploadData.setOnClickListener(v -> {
            // 使用显式 Service Intent：即使主界面与浮窗的广播机制不同，
            // 请求也一定交给 FloatingWindowService 处理；结果由服务按真实回合数提示。
            Intent uploadIntent = new Intent(this, FloatingWindowService.class);
            uploadIntent.setAction(FloatingWindowService.ACTION_UPLOAD_DATA);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(uploadIntent);
            } else {
                startService(uploadIntent);
            }
        });

        // === 无 Root 本地备份（Android SAF） ===
        tvBackupStatus = findViewById(R.id.tv_backup_status);
        Button btnChooseBackup = findViewById(R.id.btn_choose_backup);
        btnChooseBackup.setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                    | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                    | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
            startActivityForResult(intent, BACKUP_DIRECTORY_REQUEST);
        });
        Button btnBackupNow = findViewById(R.id.btn_backup_now);
        btnBackupNow.setOnClickListener(v -> {
            if (LocalBackupManager.getTreeUri(this) == null) {
                Toast.makeText(this, "请先选择本地数据目录", Toast.LENGTH_SHORT).show();
                return;
            }
            LocalBackupManager.backupAllAsync(this);
            Toast.makeText(this, "本地备份已在后台开始", Toast.LENGTH_SHORT).show();
            tvBackupStatus.postDelayed(this::updateBackupStatus, 800);
        });
        updateBackupStatus();

        // 启动时加载数据
        loadDataIfNeeded();
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateStatus();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == OVERLAY_PERMISSION_REQUEST) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && Settings.canDrawOverlays(this)) {
                startFloatingService();
            } else {
                Toast.makeText(this, "需要悬浮窗权限", Toast.LENGTH_SHORT).show();
            }
        } else if (requestCode == BACKUP_DIRECTORY_REQUEST && resultCode == RESULT_OK
                && data != null && data.getData() != null) {
            Uri tree = data.getData();
            int flags = data.getFlags()
                    & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            try {
                getContentResolver().takePersistableUriPermission(tree, flags);
                LocalBackupManager.setTreeUri(this, tree);
                LocalBackupManager.backupAllAsync(this);
                Toast.makeText(this, "本地目录已保存，正在备份", Toast.LENGTH_SHORT).show();
            } catch (SecurityException e) {
                Toast.makeText(this, "目录授权失败，请重新选择", Toast.LENGTH_LONG).show();
            }
            updateBackupStatus();
        }
    }

    private void loadDataIfNeeded() {
        if (RemoteDataLoader.isCached(this)) {
            SharedPreferences prefs = getSharedPreferences(RemoteDataLoader.PREFS_NAME, MODE_PRIVATE);
            tvStatus.setText("基础数据已缓存 ✓\n本地通信：127.0.0.1:" + HttpDataService.PORT);
            return;
        }

        tvStatus.setText("正在加载游戏数据...");
        RemoteDataLoader.loadAll(this, success -> {
            runOnUiThread(() -> {
                if (success) {
                    tvStatus.setText("数据加载成功 ✓\n813角色 / 8063事件 / 2008技能\n本地通信：127.0.0.1:" + HttpDataService.PORT);
                    Toast.makeText(this, "游戏数据加载完成", Toast.LENGTH_SHORT).show();
                } else {
                    tvStatus.setText("数据加载失败，使用离线模式\n本地通信：127.0.0.1:" + HttpDataService.PORT);
                    Toast.makeText(this, "数据加载失败，将使用离线模式", Toast.LENGTH_LONG).show();
                }
            });
        });
    }

    private void startFloatingService() {
        Intent intent = new Intent(this, FloatingWindowService.class);
        // 传入当前选择的剧本
        intent.putExtra("scenario", getSelectedScenario());
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
        Toast.makeText(this, "小黑板已启动", Toast.LENGTH_SHORT).show();
        tvStatus.setText("本地通信：127.0.0.1:" + HttpDataService.PORT);
    }

    public String getSelectedScenario() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        return prefs.getString(KEY_SCENARIO, "URA");
    }

    private void testHttpCommunication() {
        new Thread(() -> {
            try {
                String status = httpGet("http://127.0.0.1:" + HttpDataService.PORT + "/status");
                if (status != null) {
                    runOnUiThread(() -> {
                        tvStatus.setText("本地通信：在线\n" + status);
                        Toast.makeText(this, "本地通信正常", Toast.LENGTH_SHORT).show();
                    });

                    Thread.sleep(500);
                    String pushResult = httpGet(
                            "http://127.0.0.1:" + HttpDataService.PORT
                                    + "/data?msg=本地通信测试成功");
                    if (pushResult != null) {
                        runOnUiThread(() ->
                                Toast.makeText(this, "测试数据已推送到浮窗", Toast.LENGTH_SHORT).show());
                    }
                } else {
                    runOnUiThread(() -> {
                        tvStatus.setText("本地通信：离线\n请先启动悬浮窗");
                        Toast.makeText(this, "本地通信未启动", Toast.LENGTH_SHORT).show();
                    });
                }
            } catch (Exception e) {
                runOnUiThread(() -> tvStatus.setText("本地通信测试失败：" + e.getMessage()));
            }
        }).start();
    }

    private String httpGet(String urlStr) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(urlStr);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(2000);
            conn.setReadTimeout(2000);

            int code = conn.getResponseCode();
            if (code == 200) {
                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(conn.getInputStream(), "UTF-8"));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line);
                }
                reader.close();
                return sb.toString();
            }
            return null;
        } catch (Exception e) {
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private void updateBackupStatus() {
        if (tvBackupStatus != null) tvBackupStatus.setText(LocalBackupManager.getStatus(this));
    }

    private void updateStatus() {
        updateBackupStatus();
        if (RemoteDataLoader.isCached(this)) {
            tvStatus.setText("基础数据已缓存 ✓\n本地通信：127.0.0.1:" + HttpDataService.PORT);
        }
    }
}
