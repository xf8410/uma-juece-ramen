package com.umaai.assistant.service;

import org.json.JSONArray;
import org.json.JSONObject;

/** Conservative UI advice; it is intentionally not presented as upstream search output. */
public final class RamenDecisionSupport {
    private RamenDecisionSupport() {}

    public static String recommend(JSONObject summary) {
        JSONObject ramen = summary.optJSONObject("ramen");
        if (ramen == null) return "等待拉面杯状态";
        int turn = summary.optInt("turn", -1);
        if (turn >= RamenUpstreamData.SUPER_RAMEN_FIRST_TURN
                && turn <= RamenUpstreamData.SUPER_RAMEN_LAST_TURN) {
            return "超级拉面阶段：普通地区面不可用";
        }
        int year = RamenUpstreamData.yearIndexForExternalTurn(turn);
        if (year < 0) return "等待有效回合";
        int pt = ramen.optInt("checkpoint_pt", -1);
        int left = RamenUpstreamData.RMJ_TURNS[year] - turn;
        int target = RamenUpstreamData.RMJ_SUCCESS_PT[year];
        boolean canEat = canEatAny(ramen);
        int stock = sum(ramen.optJSONArray("sozai"));

        if (!canEat) return pt >= 0
                ? "当前不能做面 · RMJ差" + Math.max(0, target - pt) : "当前不能做面";
        if (stock >= RamenUpstreamData.FEELING_CAPACITY)
            return "建议吃面：诀窍库存已满，避免FIFO溢出";
        if (pt >= 0 && pt < target && left <= 3)
            return "建议吃面：RMJ剩" + Math.max(0, left) + "回合，差" + (target - pt) + "Pt";
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
        if (stock != null && stock.length() >= 3)
            out.append(" | 诀窍 ").append(stock.optInt(0)).append('/')
                    .append(stock.optInt(1)).append('/').append(stock.optInt(2));
        out.append(" | 隐味 ").append(ramen.optInt("special_feeling_num", 0))
                .append('/').append(RamenUpstreamData.SPECIAL_FEELING_CAPACITY);
        if (gauge != null && gauge.length() >= 3)
            out.append("\n槽 ").append(gauge.optInt(0)).append('/')
                    .append(gauge.optInt(1)).append('/').append(gauge.optInt(2));
        if (regions != null && regions.length() > 0) {
            out.append(" | 地区 ");
            for (int i = 0; i < regions.length(); i++) {
                if (i > 0) out.append('+');
                out.append(regions.optInt(i));
            }
        }
        return out.toString();
    }

    static boolean canEatAny(JSONObject ramen) {
        JSONArray selected = ramen.optJSONArray("selected_region_ids");
        JSONArray stock = ramen.optJSONArray("sozai");
        if (selected == null || stock == null || stock.length() < 3) return false;
        int special = Math.min(RamenUpstreamData.MAX_SPECIAL_PER_RAMEN,
                Math.max(0, ramen.optInt("special_feeling_num", 0)));
        for (int i = 0; i < selected.length(); i++) {
            int recipe = RamenUpstreamData.recipeIndexForRegionId(selected.optInt(i, 0));
            if (recipe < 0) continue;
            int missing = 0;
            for (int k = 0; k < 3; k++)
                missing += Math.max(0, RamenUpstreamData.REGION_FEELING[recipe][k] - stock.optInt(k));
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
