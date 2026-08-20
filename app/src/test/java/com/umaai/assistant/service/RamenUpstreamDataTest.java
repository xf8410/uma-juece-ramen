package com.umaai.assistant.service;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class RamenUpstreamDataTest {
    @Test public void externalTurnBoundariesMatchZeroBasedUpstream() {
        assertEquals(0, RamenUpstreamData.yearIndexForExternalTurn(1));
        assertEquals(0, RamenUpstreamData.yearIndexForExternalTurn(24));
        assertEquals(1, RamenUpstreamData.yearIndexForExternalTurn(25));
        assertEquals(1, RamenUpstreamData.yearIndexForExternalTurn(48));
        assertEquals(2, RamenUpstreamData.yearIndexForExternalTurn(49));
        assertEquals(2, RamenUpstreamData.yearIndexForExternalTurn(78));
        assertEquals(-1, RamenUpstreamData.yearIndexForExternalTurn(0));
        assertEquals(-1, RamenUpstreamData.yearIndexForExternalTurn(79));
    }

    @Test public void regionRecipeRowsRepeatForThirdYearIds() {
        for (int id = 1; id <= 10; id++) {
            assertEquals(id - 1, RamenUpstreamData.recipeIndexForRegionId(id));
            assertEquals(id - 1, RamenUpstreamData.recipeIndexForRegionId(id + 10));
        }
        assertEquals(-1, RamenUpstreamData.recipeIndexForRegionId(0));
        assertEquals(-1, RamenUpstreamData.recipeIndexForRegionId(21));
    }
}
