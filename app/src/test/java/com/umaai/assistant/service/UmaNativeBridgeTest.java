package com.umaai.assistant.service;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class UmaNativeBridgeTest {
    @Test public void structuredAcquisitionGaugesAreRemovedWithoutMutatingSummary() throws Exception {
        JSONObject summary = new JSONObject(
                "{\"scenario\":\"Ramen\",\"ramen\":{\"checkpoint_pt\":630," +
                "\"acquisition_gauges\":[{\"type\":1,\"value\":7},{\"type\":2,\"value\":1}]}}"
        );
        JSONObject nativeState = UmaNativeBridge.stateForNative(summary);

        assertTrue(summary.getJSONObject("ramen").has("acquisition_gauges"));
        assertFalse(nativeState.getJSONObject("ramen").has("acquisition_gauges"));
        assertEquals(630, nativeState.getJSONObject("ramen").getInt("checkpoint_pt"));
    }
}
