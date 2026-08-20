package com.umaai.assistant.service;

import org.json.JSONArray;
import org.json.JSONObject;

/** Compatibility projection for existing hlpatch Ramen summary fields. */
public final class RamenRuntimeStateAdapter {
    private RamenRuntimeStateAdapter() {}

    public static RamenRuntimeState parse(JSONObject summary) {
        if (summary == null) return invalid("summary is null", false);
        boolean ramenScenario = "Ramen".equals(summary.optString("scenario", ""));
        if (!ramenScenario) return invalid("scenario is not Ramen", false);
        JSONObject ramen = summary.optJSONObject("ramen");
        if (ramen == null) return invalid("ramen object is missing", true);

        int turn = positiveInt(summary, "turn");
        Integer pt = boundedInt(ramen, "checkpoint_pt", 0, Integer.MAX_VALUE);
        int[] stock = fixedIntArray(ramen.optJSONArray("sozai"), 3, 0,
                RamenUpstreamData.FEELING_CAPACITY);
        Integer special = boundedInt(ramen, "special_feeling_num", 0,
                RamenUpstreamData.SPECIAL_FEELING_CAPACITY);
        int[] regionIds = intArray(ramen.optJSONArray("selected_region_ids"), 1, 20);
        int[] regionIndices = null;
        if (regionIds != null) {
            regionIndices = new int[regionIds.length];
            for (int i = 0; i < regionIds.length; i++) regionIndices[i] = regionIds[i] - 1;
        }
        JSONArray gauges = cloneArray(ramen.optJSONArray("acquisition_gauges"));
        JSONArray queue = cloneArray(ramen.optJSONArray("feeling_info"));

        return new RamenRuntimeState(true, turn, pt, stock, special, regionIds, regionIndices,
                gauges, queue,
                turn > 0 ? RamenRuntimeState.Source.OBSERVED : RamenRuntimeState.Source.UNKNOWN,
                pt != null ? RamenRuntimeState.Source.OBSERVED : RamenRuntimeState.Source.UNKNOWN,
                stock != null ? RamenRuntimeState.Source.OBSERVED : RamenRuntimeState.Source.UNKNOWN,
                special != null ? RamenRuntimeState.Source.OBSERVED : RamenRuntimeState.Source.UNKNOWN,
                regionIds != null ? RamenRuntimeState.Source.OBSERVED : RamenRuntimeState.Source.UNKNOWN,
                regionIndices != null ? RamenRuntimeState.Source.DERIVED_ID_CONVERSION : RamenRuntimeState.Source.UNKNOWN,
                gauges != null ? RamenRuntimeState.Source.OBSERVED : RamenRuntimeState.Source.UNKNOWN,
                queue != null ? RamenRuntimeState.Source.OBSERVED : RamenRuntimeState.Source.UNKNOWN,
                null);
    }

    private static RamenRuntimeState invalid(String error, boolean ramenScenario) {
        return new RamenRuntimeState(ramenScenario, -1, null, null, null, null, null,
                null, null, RamenRuntimeState.Source.UNKNOWN, RamenRuntimeState.Source.UNKNOWN,
                RamenRuntimeState.Source.UNKNOWN, RamenRuntimeState.Source.UNKNOWN,
                RamenRuntimeState.Source.UNKNOWN, RamenRuntimeState.Source.UNKNOWN,
                RamenRuntimeState.Source.UNKNOWN, RamenRuntimeState.Source.UNKNOWN, error);
    }

    private static int positiveInt(JSONObject object, String key) {
        if (!object.has(key) || object.isNull(key)) return -1;
        Object raw = object.opt(key);
        if (!(raw instanceof Number)) return -1;
        int value = ((Number) raw).intValue();
        return value > 0 ? value : -1;
    }

    private static Integer boundedInt(JSONObject object, String key, int min, int max) {
        if (!object.has(key) || object.isNull(key)) return null;
        Object raw = object.opt(key);
        if (!(raw instanceof Number)) return null;
        int value = ((Number) raw).intValue();
        return value >= min && value <= max ? value : null;
    }

    private static int[] fixedIntArray(JSONArray values, int size, int min, int max) {
        if (values == null || values.length() != size) return null;
        return intArray(values, min, max);
    }

    private static int[] intArray(JSONArray values, int min, int max) {
        if (values == null) return null;
        int[] out = new int[values.length()];
        for (int i = 0; i < values.length(); i++) {
            Object raw = values.opt(i);
            if (!(raw instanceof Number)) return null;
            int value = ((Number) raw).intValue();
            if (value < min || value > max) return null;
            out[i] = value;
        }
        return out;
    }

    private static JSONArray cloneArray(JSONArray value) {
        if (value == null) return null;
        try { return new JSONArray(value.toString()); }
        catch (Exception ignored) { return null; }
    }
}
