package com.umaai.assistant.service;

import org.json.JSONArray;
import org.json.JSONObject;

/** Fallback only: compares observed training gains with a conservative rest safety policy. */
public final class TrainingEvaluator {
    private static final String[] NAMES = {"Speed", "Stamina", "Power", "Guts", "Wiz"};
    private static final String[] LABELS = {"速", "耐", "力", "根", "智"};

    private static final double HEADS_WEIGHT = 25.0;
    private static final double SHINING_WEIGHT = 30.0;
    /** Low vitality plus a ramen-capped ~20% failure rate must not be hidden by head bonuses. */
    private static final int LOW_VITAL_FAILURE_GUARD = 15;
    private static final double LOW_VITAL_RATIO = 0.40;

    public String recommend(JSONObject summary) {
        JSONArray trainings = summary.optJSONArray("trainings");
        if (trainings == null || trainings.length() == 0) return "等待训练数据";
        JSONObject stats = summary.optJSONObject("stats");
        int vital = stats != null ? stats.optInt("vital", -1) : -1;
        int maxVital = stats != null ? stats.optInt("max_vital", -1) : -1;

        JSONObject best = null;
        int bestHeads = 0;
        int bestShining = 0;
        int bestFailure = 0;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < trainings.length(); i++) {
            JSONObject training = trainings.optJSONObject(i);
            if (training == null || training.optInt("is_enable", 1) == 0) continue;
            JSONObject gains = training.optJSONObject("gains");
            if (gains == null) continue;
            int total = gains.optInt("Speed") + gains.optInt("Stamina") + gains.optInt("Power")
                    + gains.optInt("Guts") + gains.optInt("Wiz") + gains.optInt("SkillPt") / 2;
            int hp = gains.optInt("HP", 0);
            int failure = Math.max(0, training.optInt("failure_rate", 0));
            int heads = Math.max(0, training.optInt("heads", 0));
            int shining = Math.max(0, training.optInt("shining", 0));

            // Score by expected outcome. Failure cost rises sharply around the ramen floor instead
            // of treating 20% as a small linear deduction that five heads can easily overwhelm.
            double success = Math.max(0.0, 1.0 - failure / 100.0);
            double successValue = total + hp * 0.8
                    + heads * HEADS_WEIGHT + shining * SHINING_WEIGHT;
            double failureLoss = failure >= 20 ? 500.0 : 150.0;
            double score = successValue * success - failureLoss * (1.0 - success);
            if (score > bestScore) {
                bestScore = score;
                best = training;
                bestHeads = heads;
                bestShining = shining;
                bestFailure = failure;
            }
        }
        if (best == null) return "等待可用训练";

        boolean lowVital = vital >= 0 && maxVital > 0
                && vital <= Math.ceil(maxVital * LOW_VITAL_RATIO);
        if (lowVital && bestFailure >= LOW_VITAL_FAILURE_GUARD) {
            return "行动兜底：休息（低体力" + vital + "/" + maxVital
                    + "，最佳训练仍失败" + bestFailure + "%）";
        }

        String name = best.optString("name", "");
        String label = name;
        for (int i = 0; i < NAMES.length; i++) if (NAMES[i].equalsIgnoreCase(name)) label = LABELS[i];
        String partners = bestHeads > 0 ? "×" + bestHeads + (bestShining > 0 ? "光" + bestShining : "") : "";
        return "训练兜底：" + label + partners + "（期望收益评分，非上游搜索）";
    }
}
