package com.umaai.assistant.service;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Arrays;

/** Immutable strict projection of directly observed hlpatch Ramen fields. */
public final class RamenRuntimeState {
    public enum Source {
        OBSERVED,
        DERIVED_ID_CONVERSION,
        UNKNOWN
    }

    public final boolean ramenScenario;
    public final int externalTurn;
    public final Integer scenarioPt;
    public final int[] feelingStock;
    public final Integer specialFeeling;
    public final int[] selectedRegionIds;
    public final int[] selectedRegionIndices;
    public final JSONArray acquisitionGaugesRaw;
    public final JSONArray feelingQueueRaw;
    public final Source turnSource;
    public final Source scenarioPtSource;
    public final Source feelingStockSource;
    public final Source specialFeelingSource;
    public final Source selectedRegionIdsSource;
    public final Source selectedRegionIndicesSource;
    public final Source feelingSlotSource;
    public final Source feelingQueueSource;
    public final String error;

    RamenRuntimeState(boolean ramenScenario, int externalTurn, Integer scenarioPt,
                      int[] feelingStock, Integer specialFeeling, int[] selectedRegionIds,
                      int[] selectedRegionIndices, JSONArray acquisitionGaugesRaw,
                      JSONArray feelingQueueRaw, Source turnSource, Source scenarioPtSource,
                      Source feelingStockSource, Source specialFeelingSource,
                      Source selectedRegionIdsSource, Source selectedRegionIndicesSource,
                      Source feelingSlotSource, Source feelingQueueSource, String error) {
        this.ramenScenario = ramenScenario;
        this.externalTurn = externalTurn;
        this.scenarioPt = scenarioPt;
        this.feelingStock = copy(feelingStock);
        this.specialFeeling = specialFeeling;
        this.selectedRegionIds = copy(selectedRegionIds);
        this.selectedRegionIndices = copy(selectedRegionIndices);
        this.acquisitionGaugesRaw = acquisitionGaugesRaw;
        this.feelingQueueRaw = feelingQueueRaw;
        this.turnSource = turnSource;
        this.scenarioPtSource = scenarioPtSource;
        this.feelingStockSource = feelingStockSource;
        this.specialFeelingSource = specialFeelingSource;
        this.selectedRegionIdsSource = selectedRegionIdsSource;
        this.selectedRegionIndicesSource = selectedRegionIndicesSource;
        this.feelingSlotSource = feelingSlotSource;
        this.feelingQueueSource = feelingQueueSource;
        this.error = error;
    }

    public boolean isValid() { return ramenScenario && error == null; }
    public boolean hasObservedStock() { return feelingStockSource == Source.OBSERVED; }

    /** Never labels acquisition_gauges as upstream feeling_slot until semantics are verified. */
    public JSONObject diagnostics() {
        JSONObject out = new JSONObject();
        try {
            out.put("valid", isValid());
            if (error != null) out.put("error", error);
            put(out, "external_turn", externalTurn > 0 ? externalTurn : null, turnSource);
            put(out, "scenario_pt", scenarioPt, scenarioPtSource);
            put(out, "feeling_stock", feelingStock == null ? null : new JSONArray(feelingStock), feelingStockSource);
            put(out, "special_feeling", specialFeeling, specialFeelingSource);
            put(out, "selected_region_ids", selectedRegionIds == null ? null : new JSONArray(selectedRegionIds), selectedRegionIdsSource);
            put(out, "selected_region_indices", selectedRegionIndices == null ? null : new JSONArray(selectedRegionIndices), selectedRegionIndicesSource);
            put(out, "feeling_slot", null, feelingSlotSource);
            put(out, "feeling_queue", feelingQueueRaw, feelingQueueSource);
            if (acquisitionGaugesRaw != null) out.put("acquisition_gauges_raw", acquisitionGaugesRaw);
        } catch (Exception ignored) {}
        return out;
    }

    private static void put(JSONObject out, String key, Object value, Source source) throws Exception {
        JSONObject field = new JSONObject();
        field.put("source", source.name().toLowerCase());
        field.put("value", value == null ? JSONObject.NULL : value);
        out.put(key, field);
    }

    private static int[] copy(int[] values) {
        return values == null ? null : Arrays.copyOf(values, values.length);
    }
}
