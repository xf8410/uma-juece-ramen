package com.umaai.assistant.service;

import org.json.JSONArray;
import org.json.JSONObject;

/** Fallback only: ranks final runtime gains already calculated by hlpatch, weighted by partner heads. */
public final class TrainingEvaluator {
    private static final String[] NAMES = {"Speed", "Stamina", "Power", "Guts", "Wiz"};
    private static final String[] LABELS = {"速", "耐", "力", "根", "智"};

    /** Partner heads: each person on the training slot brings friendship/hint/feeling-gauge
     *  value beyond the current-turn gains hlpatch reports, so weight them in. */
    private static final double HEADS_WEIGHT = 25.0;
    private static final double SHINING_WEIGHT = 30.0;

    public String recommend(JSONObject summary) {
        JSONArray trainings = summary.optJSONArray("trainings");
        if (trainings == null || trainings.length() == 0) return "等待训练数据";
        JSONObject best = null;
        int bestHeads = 0;
        int bestShining = 0;
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
            int heads = Math.max(0, training.optInt("heads", 0));
            int shining = Math.max(0, training.optInt("shining", 0));
            double score = total + hp * 0.8 - failure * 2.0
                    + heads * HEADS_WEIGHT + shining * SHINING_WEIGHT;
            if (score > bestScore) { bestScore = score; best = training; bestHeads = heads; bestShining = shining; }
        }
        if (best == null) return "等待可用训练";
        String name = best.optString("name", "");
        String label = name;
        for (int i = 0; i < NAMES.length; i++) if (NAMES[i].equalsIgnoreCase(name)) label = LABELS[i];
        String partners = bestHeads > 0 ? "×" + bestHeads + (bestShining > 0 ? "光" + bestShining : "") : "";
        return "训练兜底：" + label + partners + "（运行时收益评分，非上游搜索）";
    }
}