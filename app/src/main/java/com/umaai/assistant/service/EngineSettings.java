package com.umaai.assistant.service;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;

public final class EngineSettings {
    public static final String PREFS = "engine_v1";
    public static JSONObject read(Context context) throws Exception {
        SharedPreferences p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        JSONObject options = new JSONObject();
        options.put("policy", p.getString("policy", "mcts"));
        options.put("search_n", p.getInt("search_n", 8192));
        options.put("threads", p.getInt("threads", 4));
        options.put("seed", p.getLong("seed", 61444));
        options.put("deadline_ms", 0);
        String model = p.getString("model_path", "");
        if (!model.isEmpty()) options.put("model_path", model);
        String dataVersion="unavailable";
        try(java.io.InputStream in=context.getAssets().open("gamedata/manifest.json")) {
            dataVersion=PrivateFiles.sha256(PrivateFiles.read(in,1024*1024).getBytes(StandardCharsets.UTF_8));
        }catch(java.io.IOException unavailable) { /* Initialization will report the concrete missing-asset error. */ }
        String fingerprint=new JSONObject().put("options",options).put("data_version",dataVersion).toString();
        options.put("config_id", PrivateFiles.sha256(fingerprint.getBytes(StandardCharsets.UTF_8)));
        return options;
    }
    private EngineSettings() {}
}
