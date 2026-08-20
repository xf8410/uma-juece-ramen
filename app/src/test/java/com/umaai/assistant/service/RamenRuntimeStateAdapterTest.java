package com.umaai.assistant.service;

import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.*;

public class RamenRuntimeStateAdapterTest {
    @Test public void parsesOnlyObservedFactsAndMarksConversion() throws Exception {
        JSONObject summary = new JSONObject("{\"scenario\":\"Ramen\",\"turn\":25,\"ramen\":{"
                + "\"checkpoint_pt\":1700,\"sozai\":[2,3,1],\"special_feeling_num\":2,"
                + "\"selected_region_ids\":[6,8,10],\"acquisition_gauges\":[1,2,3],"
                + "\"feeling_info\":[{\"FeelingId\":1,\"FeelingIndex\":0}]}}" );
        RamenRuntimeState state = RamenRuntimeStateAdapter.parse(summary);
        assertTrue(state.isValid());
        assertArrayEquals(new int[]{2,3,1}, state.feelingStock);
        assertArrayEquals(new int[]{6,8,10}, state.selectedRegionIds);
        assertArrayEquals(new int[]{5,7,9}, state.selectedRegionIndices);
        assertEquals(RamenRuntimeState.Source.OBSERVED, state.selectedRegionIdsSource);
        assertEquals(RamenRuntimeState.Source.DERIVED_ID_CONVERSION, state.selectedRegionIndicesSource);
        assertEquals(RamenRuntimeState.Source.UNKNOWN, state.feelingSlotSource);
        assertNotNull(state.acquisitionGaugesRaw);
    }

    @Test public void rejectsNonRamenAndMissingRamenObject() throws Exception {
        assertFalse(RamenRuntimeStateAdapter.parse(new JSONObject("{\"scenario\":\"URA\"}")).isValid());
        RamenRuntimeState missing = RamenRuntimeStateAdapter.parse(new JSONObject("{\"scenario\":\"Ramen\"}"));
        assertFalse(missing.isValid());
        assertTrue(missing.error.contains("missing"));
    }

    @Test public void invalidRangesBecomeUnknownRatherThanClamped() throws Exception {
        JSONObject summary = new JSONObject("{\"scenario\":\"Ramen\",\"turn\":1,\"ramen\":{"
                + "\"sozai\":[11,0,0],\"special_feeling_num\":5,\"selected_region_ids\":[1,1,2]}}" );
        RamenRuntimeState state = RamenRuntimeStateAdapter.parse(summary);
        assertTrue(state.isValid());
        assertNull(state.feelingStock);
        assertNull(state.specialFeeling);
        assertNull(state.selectedRegionIds);
        assertEquals(RamenRuntimeState.Source.UNKNOWN, state.feelingStockSource);
    }

    @Test public void missingFieldsStayUnknown() throws Exception {
        RamenRuntimeState state = RamenRuntimeStateAdapter.parse(
                new JSONObject("{\"scenario\":\"Ramen\",\"ramen\":{}}"));
        assertTrue(state.isValid());
        assertNull(state.scenarioPt);
        assertNull(state.feelingStock);
        assertEquals(RamenRuntimeState.Source.UNKNOWN, state.turnSource);
        assertEquals(RamenRuntimeState.Source.UNKNOWN, state.feelingQueueSource);
    }
}
