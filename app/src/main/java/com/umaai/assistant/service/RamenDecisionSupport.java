package com.umaai.assistant.service;

import org.json.JSONArray;
import org.json.JSONObject;

/** Conservative UI advice; it is intentionally not presented as upstream search output. */
public final class RamenDecisionSupport {
    private RamenDecisionSupport() {}

    public static String recommend(JSONObject summary) {
        RamenRuntimeState state = RamenRuntimeStateAdapter.parse(summary);
        if (!state.isValid()) return "等待可信拉面杯状态";
        int turn = state.externalTurn;
        if (turn >= RamenUpstreamData.SUPER_RAMEN_FIRST_TURN
                && turn <= RamenUpstreamData.SUPER_RAMEN_LAST_TURN)
            return "超级拉面阶段：普通地区面不可用";
        int year = RamenUpstreamData.yearIndexForExternalTurn(turn);
        if (year < 0) return "等待有效回合";
        if (state.scenarioPt == null) return "RMJ Pt未知：不生成吃面时机建议";
        if (state.feelingStock == null || state.specialFeeling == null
                || state.selectedRegionIds == null)
            return "库存/隐味/地区不完整：不生成吃面时机建议";

        int pt = state.scenarioPt;
        int left = RamenUpstreamData.RMJ_TURNS[year] - turn;
        int target = RamenUpstreamData.RMJ_SUCCESS_PT[year];
        boolean canEat = canEatAny(state);
        int stock = sum(state.feelingStock);
        if (!canEat) return "当前不能做面 · RMJ差" + Math.max(0, target - pt);
        if (stock >= RamenUpstreamData.FEELING_CAPACITY)
            return "建议吃面：诀窍库存已满，避免FIFO溢出";
        if (pt < target && left <= 3)
            return "建议吃面：RMJ剩" + Math.max(0, left) + "回合，差" + (target - pt) + "Pt";
        if (pt >= target) return "已达RMJ目标，可留给高收益训练回合";
        return "可做面；优先高收益训练回合，最终策略待接入上游搜索";
    }

    public static String stateLine(JSONObject summary) {
        RamenRuntimeState state = RamenRuntimeStateAdapter.parse(summary);
        if (!state.isValid()) return "拉面数据：无可信状态";
        StringBuilder out = new StringBuilder();
        out.append("Pt ").append(state.scenarioPt == null ? "?" : state.scenarioPt);
        if (state.feelingStock != null)
            out.append(" | 诀窍 ").append(state.feelingStock[0]).append('/')
                    .append(state.feelingStock[1]).append('/').append(state.feelingStock[2]);
        else out.append(" | 诀窍 ?");
        out.append(" | 隐味 ").append(state.specialFeeling == null ? "?" : state.specialFeeling)
                .append('/').append(RamenUpstreamData.SPECIAL_FEELING_CAPACITY);
        // acquisition_gauges is deliberately shown as raw runtime data. It is not
        // called upstream feeling_slot until whether it stores value/remaining is proven.
        if (state.acquisitionGaugesRaw != null)
            out.append("\n槽原始值 ").append(compact(state.acquisitionGaugesRaw));
        if (state.selectedRegionIds != null && state.selectedRegionIds.length > 0) {
            out.append(" | 地区ID ");
            for (int i = 0; i < state.selectedRegionIds.length; i++) {
                if (i > 0) out.append('+');
                out.append(state.selectedRegionIds[i]);
            }
        }
        return out.toString();
    }

    static boolean canEatAny(JSONObject ramen) {
        JSONObject wrapper = new JSONObject();
        try {
            wrapper.put("scenario", "Ramen");
            wrapper.put("turn", 1);
            wrapper.put("ramen", ramen);
        } catch (Exception ignored) { return false; }
        return canEatAny(RamenRuntimeStateAdapter.parse(wrapper));
    }

    static boolean canEatAny(RamenRuntimeState state) {
        if (state == null || !state.isValid() || state.selectedRegionIds == null
                || state.feelingStock == null || state.specialFeeling == null) return false;
        int special = Math.min(RamenUpstreamData.MAX_SPECIAL_PER_RAMEN, state.specialFeeling);
        for (int regionId : state.selectedRegionIds) {
            int recipe = RamenUpstreamData.recipeIndexForRegionId(regionId);
            if (recipe < 0) continue;
            int missing = 0;
            for (int k = 0; k < 3; k++)
                missing += Math.max(0,
                        RamenUpstreamData.REGION_FEELING[recipe][k] - state.feelingStock[k]);
            if (missing <= special) return true;
        }
        return false;
    }

    private static int sum(int[] values) {
        int result = 0;
        for (int value : values) result += value;
        return result;
    }

    private static String compact(JSONArray values) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < values.length(); i++) {
            if (i > 0) out.append('/');
            Object value = values.opt(i);
            if (value instanceof JSONObject) {
                JSONObject object = (JSONObject) value;
                int remaining = object.optInt("remaining", Integer.MIN_VALUE);
                out.append(remaining == Integer.MIN_VALUE ? object.toString() : remaining);
            } else out.append(String.valueOf(value));
        }
        return out.toString();
    }
}
