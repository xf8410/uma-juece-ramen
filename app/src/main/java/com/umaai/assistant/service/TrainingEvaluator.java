package com.umaai.assistant.service;

import org.json.JSONArray;
import org.json.JSONObject;

/** Fallback only: ranks final runtime gains already calculated by hlpatch. */
public final class TrainingEvaluator {
    private static final String[] NAMES = {"Speed", "Stamina", "Power", "Guts", "Wisdom"};
    private static final String[] LABELS = {"速", "耐", "力", "根", "智"};

    public String recommend(JSONObject summary) {
        JSONArray trainings = summary.optJSONArray("trainings");
        if (trainings == null || trainings.length() == 0) return "等待训练数据";
        JSONObject best = null;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < trainings.length(); i++) {
            JSONObject training = trainings.optJSONObject(i);
            if (training == null || training.optInt("is_enable", 1) == 0) continue;
            JSONObject gains = training.optJSONObject("gains");
            if (gains == null) continue;
            int total = gains.optInt("Speed") + gains.optInt("Stamina") + gains.optInt("Power")
                    + gains.optInt("Guts") + gains.optInt("Wiz") + gains.optInt("SkillPt") / 2;
            int hp = gains.optInt("HP", 0);
            int failure = training.optInt("failure_rate", 0);
            double score = total + hp * 0.8 - failure * 2.0;
            if (score > bestScore) { bestScore = score; best = training; }
        }
        if (best == null) return "等待可用训练";
        String name = best.optString("name", "");
        String label = name;
        for (int i = 0; i < NAMES.length; i++) if (NAMES[i].equalsIgnoreCase(name)) label = LABELS[i];
        return "训练兜底：" + label + "（运行时收益评分，非上游搜索）";
    }
}
