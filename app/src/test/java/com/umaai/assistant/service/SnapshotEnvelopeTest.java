package com.umaai.assistant.service;
import org.junit.Test;
import static org.junit.Assert.*;
public class SnapshotEnvelopeTest {
    @Test public void keepsTurnZeroAndMissingFieldsWithoutInventingState() throws Exception {
        SnapshotEnvelope s=SnapshotEnvelope.parse("{\"schema_version\":1,\"run_id\":7,\"snapshot_id\":12,\"stage\":\"train\",\"state\":{\"baseGame\":{\"turn\":0}},\"missing_fields\":[\"persons\"]}");
        assertEquals(0,s.turn());assertTrue(s.missingReason().contains("persons"));assertFalse(s.json.getJSONObject("state").has("ramen"));
    }
    @Test(expected=IllegalArgumentException.class) public void legacySummaryNeverBecomesASearchSnapshot() throws Exception {SnapshotEnvelope.parse("{\"turn\":1,\"chara\":{}}");}
    @Test(expected=IllegalArgumentException.class) public void negativeIdentityRejected() throws Exception {SnapshotEnvelope.parse("{\"schema_version\":1,\"run_id\":-1,\"snapshot_id\":0,\"stage\":\"train\"}");}
    @Test public void incompleteCollectorResponseRetainsSpecificMissingFieldsWithoutFakeRun() throws Exception {
        org.json.JSONObject json=new org.json.JSONObject("{\"schema_version\":1,\"ready\":false,\"run_id\":null,\"snapshot_id\":null,\"missing_fields\":[\"baseGame.cardId\"],\"display_summary\":{\"chara\":{\"vital\":90}}}");
        assertFalse(SnapshotEnvelope.hasIdentity(json));assertTrue(SnapshotEnvelope.readinessProblem(json).contains("baseGame.cardId"));assertTrue(json.isNull("run_id"));
    }
}
