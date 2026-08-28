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

/** JNI bridge and search entry point for the ramen decision engine. */
public final class UmaNativeBridge {
    private static final String TAG = "UmaNativeBridge";
    public static final int DEFAULT_SEARCH_N = 4096;
    private static boolean loaded = false;
    private static boolean initialized = false;
    private static String cloudUrl = "";

    static {
        try { System.loadLibrary("uma_jni"); loaded = true; Log.i(TAG, "libuma_jni.so loaded"); }
        catch (UnsatisfiedLinkError e) { Log.w(TAG, "libuma_jni.so not found, native search disabled"); }
    }

    public static native String nativeInit(String dataDir);
    public static native String nativeSearch(String stateJson, String configJson);
    public static native String nativeVersion();
    public static boolean isLoaded() { return loaded; }
    public static boolean isInitialized() { return initialized; }
    public static boolean isAvailable() { return loaded && initialized; }
    public static void setCloudUrl(String url) { cloudUrl = url != null ? url : ""; }
    public static String getCloudUrl() { return cloudUrl; }

    public static boolean init(Context context) {
        if (!loaded) return false;
        if (initialized) return true;
        File dataDir = context.getFilesDir();
        File gamedataDir = new File(dataDir, "gamedata");
        gamedataDir.mkdirs();
        String[] files = {"constants.json", "cardDB.json", "umaDB.json", "text_data_dict.json", "events.json", "scenario_ramen.json", "scenario_onsen.json", "default_config.toml"};
        AssetManager am = context.getAssets();
        for (String f : files) {
            File target = new File(gamedataDir, f);
            if (!target.exists() || target.length() == 0) {
                try (InputStream is = am.open("gamedata/" + f); OutputStream os = new FileOutputStream(target)) {
                    byte[] buf = new byte[8192]; int len;
                    while ((len = is.read(buf)) != -1) os.write(buf, 0, len);
                } catch (Exception e) { Log.w(TAG, "Failed to copy gamedata/" + f, e); }
            }
        }
        try {
            JSONObject json = new JSONObject(nativeInit(dataDir.getAbsolutePath()));
            initialized = json.optBoolean("ok", false);
            return initialized;
        } catch (Exception e) { Log.e(TAG, "Native init exception", e); initialized = false; return false; }
    }

    /** Search ramen actions, while retaining a visible independent training recommendation. */
    public static JSONObject search(JSONObject summary, int umaId, int[] cards, int searchN) {
        int n = searchN > 0 ? searchN : DEFAULT_SEARCH_N;
        if (!cloudUrl.isEmpty()) {
            JSONObject result = withTraining(searchCloud(summary, umaId, cards, n), summary);
            if (result != null) return result;
        }
        if (!isAvailable()) return null;
        return withTraining(searchLocal(summary, umaId, cards, n), summary);
    }

    /** Native output previously replaced the fallback text in the overlay. Keep both decisions. */
    private static JSONObject withTraining(JSONObject result, JSONObject summary) {
        if (result == null) return null;
        try {
            JSONObject decision = result.optJSONObject("decision");
            if (decision != null) {
                String training = new TrainingEvaluator().recommend(summary);
                String action = decision.optString("action_display", "?");
                if (!training.isEmpty() && !action.contains("训练建议")) {
                    decision.put("action_display", action + "\n训练建议：" + training.replace("训练兜底：", ""));
                }
            }
        } catch (Exception e) { Log.w(TAG, "Unable to append training recommendation", e); }
        return result;
    }

    private static JSONObject searchLocal(JSONObject summary, int umaId, int[] cards, int searchN) {
        try { return new JSONObject(nativeSearch(summary.toString(), makeConfig(umaId, cards, searchN).toString())); }
        catch (Exception e) { Log.e(TAG, "searchLocal exception", e); return null; }
    }

    private static JSONObject makeConfig(int umaId, int[] cards, int searchN) throws Exception {
        JSONObject config = new JSONObject(); config.put("uma_id", umaId);
        JSONArray cardsArr = new JSONArray(); for (int c : cards) cardsArr.put(c);
        config.put("cards", cardsArr); config.put("search_n", searchN);
        config.put("blue_count", new JSONArray("[15,3,0,0,0]"));
        config.put("extra_count", new JSONArray("[0,30,0,0,30,30]"));
        return config;
    }

    private static JSONObject searchCloud(JSONObject summary, int umaId, int[] cards, int searchN) {
        HttpURLConnection conn = null;
        try {
            JSONObject payload = new JSONObject(); payload.put("state", summary); payload.put("config", makeConfig(umaId, cards, searchN));
            conn = (HttpURLConnection) new URL(cloudUrl + "/search").openConnection();
            conn.setRequestMethod("POST"); conn.setRequestProperty("Content-Type", "application/json"); conn.setDoOutput(true);
            conn.setConnectTimeout(5000); conn.setReadTimeout(60000);
            try (OutputStream os = conn.getOutputStream()) { os.write(payload.toString().getBytes("UTF-8")); }
            if (conn.getResponseCode() != 200) return null;
            StringBuilder sb = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"))) {
                String line; while ((line = reader.readLine()) != null) sb.append(line);
            }
            return new JSONObject(sb.toString());
        } catch (Exception e) { Log.w(TAG, "Cloud search failed, falling back to local", e); return null; }
        finally { if (conn != null) conn.disconnect(); }
    }
}
