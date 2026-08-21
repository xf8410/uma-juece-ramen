package com.umaai.assistant.service;

import android.content.Context;
import android.content.res.AssetManager;

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
 * JNI bridge + cloud search.
 *
 * If CLOUD_URL is set, search runs on the cloud server (手机发状态 → 云端算 → 返回推荐).
 * Falls back to local JNI if cloud is unavailable.
 */
public final class UmaNativeBridge {
    private static boolean loaded = false;
    private static boolean initialized = false;
    private static JSONObject optimizedStrategy = null;
    private static String cloudUrl = "";

    static {
        try {
            System.loadLibrary("uma_jni");
            loaded = true;
        } catch (UnsatisfiedLinkError e) {
            loaded = false;
        }
    }

    public static native String nativeInit(String dataDir);
    public static native String nativeSearch(String stateJson, String configJson);
    public static native String nativeVersion();

    public static boolean isLoaded() { return loaded; }
    public static boolean isInitialized() { return initialized; }
    public static boolean isAvailable() { return loaded && initialized; }
    public static boolean hasOptimizedStrategy() { return optimizedStrategy != null; }
    public static void setCloudUrl(String url) { cloudUrl = url != null ? url : ""; }
    public static String getCloudUrl() { return cloudUrl; }

    public static boolean init(Context context) {
        if (!loaded) return false;
        if (initialized) return true;

        File dataDir = context.getFilesDir();
        File gamedataDir = new File(dataDir, "gamedata");
        gamedataDir.mkdirs();

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
                } catch (Exception e) { }
            }
        }

        try (InputStream is = am.open("strategy_optimized.json")) {
            byte[] buf = new byte[is.available()];
            is.read(buf);
            String json = new String(buf, "UTF-8").trim();
            if (!json.isEmpty()) optimizedStrategy = new JSONObject(json);
        } catch (Exception e) { }

        try {
            String result = nativeInit(dataDir.getAbsolutePath());
            JSONObject json = new JSONObject(result);
            initialized = json.optBoolean("ok", false);
            return initialized;
        } catch (Exception e) {
            initialized = false;
            return false;
        }
    }

    public static JSONObject search(JSONObject summary, int umaId, int[] cards, int searchN) {
        // 云端优先
        if (!cloudUrl.isEmpty()) {
            JSONObject cloudResult = searchCloud(summary, umaId, cards, searchN);
            if (cloudResult != null) return cloudResult;
        }
        // 本地 JNI 回退
        if (!isAvailable()) return null;
        return searchLocal(summary, umaId, cards, searchN);
    }

    private static JSONObject searchLocal(JSONObject summary, int umaId, int[] cards, int searchN) {
        try {
            JSONObject config = new JSONObject();
            config.put("uma_id", umaId);
            JSONArray cardsArr = new JSONArray();
            for (int c : cards) cardsArr.put(c);
            config.put("cards", cardsArr);
            config.put("search_n", searchN);
            config.put("blue_count", new JSONArray("[15,3,0,0,0]"));
            config.put("extra_count", new JSONArray("[0,30,0,0,30,30]"));
            if (optimizedStrategy != null) config.put("strategy", optimizedStrategy);

            String result = nativeSearch(summary.toString(), config.toString());
            return new JSONObject(result);
        } catch (Exception e) {
            return null;
        }
    }

    /** 云端搜索：POST state + config → 返回推荐 */
    private static JSONObject searchCloud(JSONObject summary, int umaId, int[] cards, int searchN) {
        try {
            JSONObject payload = new JSONObject();
            payload.put("state", summary);

            JSONObject config = new JSONObject();
            config.put("uma_id", umaId);
            JSONArray cardsArr = new JSONArray();
            for (int c : cards) cardsArr.put(c);
            config.put("cards", cardsArr);
            config.put("search_n", searchN);
            config.put("blue_count", new JSONArray("[15,3,0,0,0]"));
            config.put("extra_count", new JSONArray("[0,30,0,0,30,30]"));
            payload.put("config", config);

            URL url = new URL(cloudUrl + "/search");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setDoOutput(true);
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(30000);

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
            return null;
        }
    }
}
