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
 */
public final class UmaNativeBridge {
    private static boolean loaded = false;
    private static boolean initialized = false;

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

    /**
     * Copy gamedata from assets to internal storage, then call nativeInit.
     * Returns true if initialization succeeded.
     */
    public static boolean init(Context context) {
        if (!loaded) return false;
        if (initialized) return true;

        File dataDir = context.getFilesDir();
        File gamedataDir = new File(dataDir, "gamedata");
        gamedataDir.mkdirs();

        // Copy gamedata files from assets if not present
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
                    // Asset might not exist (e.g. scenario_onsen.json); skip
                }
            }
        }

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
     *
     * @param summary  hlpatch /summary JSON
     * @param umaId    character ID (e.g. 102601)
     * @param cards    6 card idranks (e.g. [302424, 302894, ...])
     * @param searchN  simulations per action (e.g. 32)
     * @return JSONObject with search results, or null if unavailable
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
            // Default inherit (can be overridden via config later)
            config.put("blue_count", new JSONArray("[15,3,0,0,0]"));
            config.put("extra_count", new JSONArray("[0,30,0,0,30,30]"));

            String result = nativeSearch(summary.toString(), config.toString());
            return new JSONObject(result);
        } catch (Exception e) {
            return null;
        }
    }
}
