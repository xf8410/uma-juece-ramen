package com.umaai.assistant.service;

import android.content.Context;
import android.content.res.AssetManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * JNI bridge to upstream umaai-rs flat Monte Carlo search.
 *
 * Loads libuma_jni.so, initializes gamedata, and exposes native search.
 * Falls back gracefully if the native library is not available.
 *
 * If assets/strategy_optimized.json exists (produced by CMA-ES/Bayes CI),
 * it is loaded as the strategy parameter set for all searches.
 */
public final class UmaNativeBridge {
    private static boolean loaded = false;
    private static boolean initialized = false;
    private static JSONObject optimizedStrategy = null;

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

    /**
     * Copy gamedata from assets to internal storage, then call nativeInit.
     * Also loads strategy_optimized.json from assets if present.
     */
    public static boolean init(Context context) {
        if (!loaded) return false;
        if (initialized) return true;

        File dataDir = context.getFilesDir();
        File gamedataDir = new File(dataDir, "gamedata");
        gamedataDir.mkdirs();

        // Copy gamedata files from assets
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
                } catch (Exception e) { /* skip */ }
            }
        }

        // Load optimized strategy from assets (CMA-ES/Bayes CI output)
        try (InputStream is = am.open("strategy_optimized.json")) {
            byte[] buf = new byte[is.available()];
            is.read(buf);
            String json = new String(buf, "UTF-8").trim();
            if (!json.isEmpty()) {
                optimizedStrategy = new JSONObject(json);
            }
        } catch (Exception e) { /* no optimized strategy, use default */ }

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

    /**
     * Run flat Monte Carlo search for the current turn.
     * Uses optimized strategy if loaded, otherwise JNI defaults.
     */
    public static JSONObject search(JSONObject summary, int umaId, int[] cards, int searchN) {
        if (!isAvailable()) return null;
        try {
            JSONObject config = new JSONObject();
            config.put("uma_id", umaId);
            JSONArray cardsArr = new JSONArray();
            for (int c : cards) cardsArr.put(c);
            config.put("cards", cardsArr);
            config.put("search_n", searchN);
            config.put("blue_count", new JSONArray("[15,3,0,0,0]"));
            config.put("extra_count", new JSONArray("[0,30,0,0,30,30]"));

            // Inject optimized strategy if available
            if (optimizedStrategy != null) {
                config.put("strategy", optimizedStrategy);
            }

            String result = nativeSearch(summary.toString(), config.toString());
            return new JSONObject(result);
        } catch (Exception e) {
            return null;
        }
    }
}
