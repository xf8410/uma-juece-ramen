package com.umaai.assistant.service;

import org.json.JSONObject;

/** Combines safety fallback and native search into one user-facing action recommendation. */
public final class ActionRecommendation {
    private ActionRecommendation() {}

    public static String display(JSONObject summary, String fallback, JSONObject search, boolean running) {
        // Safety rules are hard constraints. A risky training search must not overwrite "rest".
        if (fallback != null && (fallback.contains("休息") || fallback.contains("治病")))
            return fallback.replace("行动兜底：", "建议：");
        if (running) return fallback + " · AI复核中";
        if (search != null && search.optBoolean("ok", false)) {
            String action = translate(search.optString("action_display", "?"));
            int trials = search.optInt("search_n", 0);
            return "AI建议：" + action + (trials > 0 ? "（每个选项试算" + trials + "局）" : "");
        }
        return fallback;
    }

    static String translate(String action) {
        if (action == null) return "?";
        return action.replace("普通出行", "外出")
                .replace("友人出行", "友人外出")
                .replace("Speed训练", "速度训练")
                .replace("Stamina训练", "耐力训练")
                .replace("Power训练", "力量训练")
                .replace("Guts训练", "根性训练")
                .replace("Wisdom训练", "智力训练");
    }
}
