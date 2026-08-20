package com.umaai.assistant.service;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Conservative Ramen display advice. Mechanic constants are aligned with
 * xulai1001/umaai-rs gamedata/scenario_ramen.json; this is not an MCTS replacement.
 */
public final class RamenDecisionSupport {
    private static final int[] RMJ_TARGETS = {1500, 3000, 3500};
    private static final int[] RMJ_TURNS_EXTERNAL = {24, 48, 72};
    private static final int[][] RECIPES = {
            {2,2,1},{1,2,2},{3,1,1},{2,3,0},{1,1,3},
            {2,0,3},{3,2,0},{0,3,2},{2,1,2},{1,3,1}
    };

    private RamenDecisionSupport() {}

    public static String recommend(JSONObject summary) {
        JSONObject ramen = summary.optJSONObject("ramen");
        if (ramen == null) return "等待拉面杯状态";
        int turn = summary.optInt("turn", -1);
        if (turn >= 73) return "超级拉面阶段：普通地区面不可用";
        if (turn <= 0) return "等待有效回合";
        int year = turn <= 24 ? 0 : turn <= 48 ? 1 : 2;
        int pt = ramen.optInt("checkpoint_pt", -1);
        int left = RMJ_TURNS_EXTERNAL[year] - turn;
        int target = RMJ_TARGETS[year];
        boolean canEat = canEatAny(ramen);
        int stock = sum(ramen.optJSONArray("sozai"));

        if (!canEat) return pt >= 0 ? "当前不能做面 · RMJ差" + Math.max(0, target - pt) : "当前不能做面";
        if (stock >= 10) return "建议吃面：普通诀窍库存已满，避免FIFO溢出";
        if (pt >= 0 && pt < target && left <= 3) return "建议吃面：RMJ剩" + Math.max(0, left) + "回合，差" + (target - pt) + "Pt";
        if (pt >= target) return "已达RMJ目标，可留给高收益训练回合";
        return "可做面；优先高收益训练回合，最终策略待接入上游搜索";
    }

    public static String stateLine(JSONObject summary) {
        JSONObject ramen = summary.optJSONObject("ramen");
        if (ramen == null) return "拉面数据：无";
        JSONArray stock = ramen.optJSONArray("sozai");
        JSONArray gauge = ramen.optJSONArray("acquisition_gauges");
        JSONArray regions = ramen.optJSONArray("selected_region_ids");
        StringBuilder out = new StringBuilder();
        out.append("Pt ").append(ramen.optInt("checkpoint_pt", 0));
        if (stock != null && stock.length() >= 3) out.append(" | 诀窍 ").append(stock.optInt(0)).append('/').append(stock.optInt(1)).append('/').append(stock.optInt(2));
        out.append(" | 隐味 ").append(ramen.optInt("special_feeling_num", 0)).append("/4");
        if (gauge != null && gauge.length() >= 3) out.append("\n槽 ").append(gauge.optInt(0)).append('/').append(gauge.optInt(1)).append('/').append(gauge.optInt(2));
        if (regions != null && regions.length() > 0) {
            out.append(" | 地区 ");
            for (int i = 0; i < regions.length(); i++) { if (i > 0) out.append('+'); out.append(regions.optInt(i)); }
        }
        return out.toString();
    }

    private static boolean canEatAny(JSONObject ramen) {
        JSONArray selected = ramen.optJSONArray("selected_region_ids");
        JSONArray stock = ramen.optJSONArray("sozai");
        if (selected == null || stock == null || stock.length() < 3) return false;
        int special = Math.min(2, Math.max(0, ramen.optInt("special_feeling_num", 0)));
        for (int i = 0; i < selected.length(); i++) {
            int id = selected.optInt(i, 0);
            int recipe = (id - 1) % 10;
            if (recipe < 0) continue;
            int missing = 0;
            for (int k = 0; k < 3; k++) missing += Math.max(0, RECIPES[recipe][k] - stock.optInt(k));
            if (missing <= special) return true;
        }
        return false;
    }

    private static int sum(JSONArray values) {
        if (values == null) return 0;
        int result = 0;
        for (int i = 0; i < values.length(); i++) result += Math.max(0, values.optInt(i));
        return result;
    }
}
