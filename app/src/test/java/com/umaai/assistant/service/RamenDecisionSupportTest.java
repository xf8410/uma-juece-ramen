package com.umaai.assistant.service;

import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class RamenDecisionSupportTest {
    @Test public void specialFeelingCanCoverAtMostTwoMissingItems() throws Exception {
        JSONObject twoMissing = new JSONObject("{\"selected_region_ids\":[1],\"sozai\":[0,2,1],\"special_feeling_num\":2}");
        assertTrue(RamenDecisionSupport.canEatAny(twoMissing));
        JSONObject threeMissing = new JSONObject("{\"selected_region_ids\":[1],\"sozai\":[0,1,1],\"special_feeling_num\":4}");
        assertFalse(RamenDecisionSupport.canEatAny(threeMissing));
    }

    @Test public void thirdYearRegionIdUsesRepeatedRecipe() throws Exception {
        JSONObject ramen = new JSONObject("{\"selected_region_ids\":[11],\"sozai\":[2,2,1],\"special_feeling_num\":0}");
        assertTrue(RamenDecisionSupport.canEatAny(ramen));
    }

    @Test public void externalTurn73StartsSuperRamenDisplayStage() throws Exception {
        JSONObject summary = new JSONObject("{\"turn\":73,\"ramen\":{}}");
        assertTrue(RamenDecisionSupport.recommend(summary).contains("超级拉面"));
    }
}
