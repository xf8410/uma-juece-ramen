package com.umaai.assistant.service;

import org.json.JSONArray;
import org.json.JSONObject;

/** Scenario 14 RMJ threshold projection using only catalog thresholds and base points per ramen. */
public final class RamenCheckpointPlanner {
    private RamenCheckpointPlanner() {}

    public static String buildSummary(JSONObject summary, String checkpointRaw, String actionRaw) {
        if (summary == null || checkpointRaw == null || actionRaw == null) return "";
        JSONObject ramen = summary.optJSONObject("ramen");
        if (ramen == null) return "";
        int point = ramen.optInt("checkpoint_pt", ramen.optInt("check_point_pt", -1));
        int turn = summary.optInt("turn", -1);
        if (point < 0 || turn < 0) return "";
        try {
            JSONArray checkpoints = new JSONObject(checkpointRaw).optJSONArray("checkpoints");
            JSONArray stages = new JSONObject(actionRaw).optJSONArray("stages");
            if (checkpoints == null || stages == null) return "";

            JSONObject next = null;
            for (int i = 0; i < checkpoints.length(); i++) {
                JSONObject candidate = checkpoints.optJSONObject(i);
                if (candidate == null || candidate.optInt("turn", -1) < turn) continue;
                if (next == null || candidate.optInt("turn") < next.optInt("turn")) next = candidate;
            }
            if (next == null) return "拉面大赛：检查点已结束";

            int checkpointTurn = next.optInt("turn");
            int stage = checkpointTurn == 24 ? 1 : checkpointTurn == 48 ? 2 : 3;
            int basePoint = -1;
            for (int i = 0; i < stages.length(); i++) {
                JSONObject row = stages.optJSONObject(i);
                if (row != null && row.optInt("stage", -1) == stage) {
                    basePoint = row.optInt("base_checkpoint_pt_gain", -1);
                    break;
                }
            }
            int success = next.optInt("success_pt", -1);
            if (success <= 0 || basePoint <= 0) return "";
            int shortfall = Math.max(0, success - point);
            int bowls = (shortfall + basePoint - 1) / basePoint;

            StringBuilder out = new StringBuilder("拉面大赛 第").append(checkpointTurn).append("回合：");
            if (shortfall == 0) out.append("已达目标");
            else out.append("还差").append(shortfall).append("点，至少再吃").append(bowls).append("碗");

            int great = next.optInt("great_success_pt", 0);
            if (great > 0) {
                int greatShortfall = Math.max(0, great - point);
                int greatBowls = (greatShortfall + basePoint - 1) / basePoint;
                if (greatShortfall == 0) out.append("；已达大成功目标");
                else out.append("；大成功至少再吃").append(greatBowls).append("碗");
            }
            return out.toString();
        } catch (Exception ignored) {
            return "";
        }
    }
}
