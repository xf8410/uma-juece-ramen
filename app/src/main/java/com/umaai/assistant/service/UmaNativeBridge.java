package com.umaai.assistant.service;

import android.content.Context;
import android.content.res.AssetManager;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * JNI 桥接层：手机版与上游 umaai-rs 模拟器的通信入口。
 *
 * 架构（v0.2.0+）：
 * - hlpatch 推送 JSON（chara + month/half）→ 透传给 nativeSearch
 * - Rust 侧 reconcile 校正 → inject_state → RamenMctsTrainer 搜索
 * - 返回标准结构化 JSON（view + decision + reconcile + warnings）
 * - Java 侧直接渲染，不再自己拼文案或算评分
 *
 * 通信方式：
 * - 本地 JNI（默认）：libuma.so 在手机端跑搜索
 * - 云端搜索（可选）：setCloudUrl 后优先走云端，本地兜底
 */
public final class UmaNativeBridge {
    private static final String TAG = "UmaNativeBridge";

    /** 搜索次数：用户指定 4096（PC 端基准：进门分 61000，结算 US5 69173） */
    public static final int DEFAULT_SEARCH_N = 4096;

    private static boolean loaded = false;
    private static boolean initialized = false;
    private static String cloudUrl = "";

    static {
        try {
            System.loadLibrary("uma_jni");
            loaded = true;
            Log.i(TAG, "libuma_jni.so loaded");
        } catch (UnsatisfiedLinkError e) {
            loaded = false;
            Log.w(TAG, "libuma_jni.so not found, native search disabled");
        }
    }

    public static native String nativeInit(String dataDir);
    public static native String nativeSearch(String stateJson, String configJson);
    public static native String nativeVersion();

    public static boolean isLoaded() { return loaded; }
    public static boolean isInitialized() { return initialized; }
    public static boolean isAvailable() { return loaded && initialized; }
    public static void setCloudUrl(String url) { cloudUrl = url != null ? url : ""; }
    public static String getCloudUrl() { return cloudUrl; }

    /**
     * 初始化 native 层：复制 gamedata 到内部存储，加载游戏数据。
     *
     * Rust 侧 init_global() 通过相对路径 "gamedata/xxx.json" 加载，
     * 所以 dataDir 必须是包含 gamedata/ 子目录的父目录。
     */
    public static boolean init(Context context) {
        if (!loaded) return false;
        if (initialized) return true;

        File dataDir = context.getFilesDir();
        File gamedataDir = new File(dataDir, "gamedata");
        gamedataDir.mkdirs();

        // 从 assets 复制 gamedata 文件到内部存储
        String[] files = {
            "constants.json", "cardDB.json", "umaDB.json",
            "text_data_dict.json", "events.json", "scenario_ramen.json",
            "scenario_onsen.json", "default_config.toml"
        };
        AssetManager am = context.getAssets();
        for (String f : files) {
            File target = new File(gamedataDir, f);
            if (!target.exists() || target.length() == 0) {
                try (InputStream is = am.open("gamedata/" + f);
                     OutputStream os = new FileOutputStream(target)) {
                    byte[] buf = new byte[8192];
                    int len;
                    while ((len = is.read(buf)) != -1) os.write(buf, 0, len);
                } catch (Exception e) {
                    Log.w(TAG, "Failed to copy gamedata/" + f, e);
                }
            }
        }

        // 调用 native init（传 dataDir，Rust 侧 set_current_dir 后用相对路径加载）
        try {
            String result = nativeInit(dataDir.getAbsolutePath());
            JSONObject json = new JSONObject(result);
            initialized = json.optBoolean("ok", false);
            if (initialized) {
                Log.i(TAG, "Native init OK");
            } else {
                Log.e(TAG, "Native init failed: " + json.optString("error", "unknown"));
            }
            return initialized;
        } catch (Exception e) {
            Log.e(TAG, "Native init exception", e);
            initialized = false;
            return false;
        }
    }

    /**
     * 搜索入口：把 hlpatch JSON 透传给 Rust，返回结构化结果。
     *
     * @param summary hlpatch 推送的完整 JSON（chara + month/half + 可选 ramen）
     * @param umaId   马娘 ID
     * @param cards   6 张支援卡 ID
     * @param searchN 搜索次数（0 表示用默认值 4096）
     * @return SearchResponse JSON，或 null（native 不可用且云端未配置）
     */
    public static JSONObject search(JSONObject summary, int umaId, int[] cards, int searchN) {
        int n = searchN > 0 ? searchN : DEFAULT_SEARCH_N;

        // 优先云端
        if (!cloudUrl.isEmpty()) {
            JSONObject cloudResult = searchCloud(summary, umaId, cards, n);
            if (cloudResult != null) return cloudResult;
        }

        // 本地 JNI
        if (!isAvailable()) return null;
        return searchLocal(summary, umaId, cards, n);
    }

    /**
     * 直接透传 hlpatch JSON 给 native，不再删除任何字段。
     * Rust 侧的 reconcile 层会处理 acquisition_gauges 等格式差异。
     */
    private static JSONObject searchLocal(JSONObject summary, int umaId, int[] cards, int searchN) {
        try {
            JSONObject config = makeConfig(umaId, cards, searchN);
            String result = nativeSearch(summary.toString(), config.toString());
            return new JSONObject(result);
        } catch (Exception e) {
            Log.e(TAG, "searchLocal exception", e);
            return null;
        }
    }

    private static JSONObject makeConfig(int umaId, int[] cards, int searchN) throws Exception {
        JSONObject config = new JSONObject();
        config.put("uma_id", umaId);
        JSONArray cardsArr = new JSONArray();
        for (int c : cards) cardsArr.put(c);
        config.put("cards", cardsArr);
        config.put("search_n", searchN);
        // 默认因子继承（后续可从 UI 配置）
        config.put("blue_count", new JSONArray("[15,3,0,0,0]"));
        config.put("extra_count", new JSONArray("[0,30,0,0,30,30]"));
        return config;
    }

    private static JSONObject searchCloud(JSONObject summary, int umaId, int[] cards, int searchN) {
        try {
            JSONObject payload = new JSONObject();
            payload.put("state", summary);
            payload.put("config", makeConfig(umaId, cards, searchN));

            URL url = new URL(cloudUrl + "/search");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setDoOutput(true);
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(60000); // 4096 次搜索可能较慢

            OutputStream os = conn.getOutputStream();
            os.write(payload.toString().getBytes("UTF-8"));
            os.close();

            if (conn.getResponseCode() != 200) return null;
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) sb.append(line);
            reader.close();
            conn.disconnect();
            return new JSONObject(sb.toString());
        } catch (Exception e) {
            Log.w(TAG, "Cloud search failed, falling back to local", e);
            return null;
        }
    }
}
