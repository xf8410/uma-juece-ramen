package com.umaai.assistant.service;
import org.json.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class CollectorRecoveryTest {
    static JSONObject capabilities(String id)throws Exception{return new JSONObject().put("capability_schema_version",1).put("snapshot_schema_versions",new JSONArray().put(2)).put("collector_instance_id",id).put("collector_version","test");}
    static SnapshotEnvelope snapshot(String id,long seq)throws Exception{return SnapshotEnvelope.parse(new JSONObject().put("schema_version",2).put("collector_instance_id",id).put("run_id",5001).put("snapshot_id",seq)
        .put("stage","train").put("ready",true).put("capture_coherence","verified").put("state",new JSONObject().put("baseGame",new JSONObject().put("turn",20))).toString());}
    @Test public void sameRunRecoversSequenceOneOnlyAfterNewCollectorHandshake()throws Exception {
        CollectorConnection connection=new CollectorConnection();connection.confirm(capabilities("process-a"),100);
        assertEquals("",connection.accept(snapshot("process-a",40),101));
        assertEquals("collector_instance_mismatch",connection.accept(snapshot("process-b",1),102));
        connection.confirm(capabilities("process-b"),103);assertEquals("",connection.accept(snapshot("process-b",1),104));
        assertEquals("stale_or_retired_snapshot",connection.accept(snapshot("process-b",0),105));
        assertEquals("collector_instance_mismatch",connection.accept(snapshot("process-a",41),106));
        try{connection.confirm(capabilities("process-a"),107);fail("retired handshake must not reactivate A");}catch(IllegalArgumentException expected){}
    }
    @Test public void incompleteNewProcessRetiresOldWhileRequiringFullCurrentSnapshot()throws Exception {
        CollectorConnection connection=new CollectorConnection();connection.confirm(capabilities("a"),100);connection.accept(snapshot("a",40),101);
        connection.confirm(capabilities("b"),102);SnapshotEnvelope partial=snapshot("b",1);partial.json.put("ready",false).put("missing_fields",new JSONArray().put("continuation"));
        assertEquals("",connection.accept(partial,103));assertFalse(partial.missingReason().isEmpty());
        assertFalse(connection.accept(snapshot("a",99),104).isEmpty());assertEquals("",connection.accept(snapshot("b",2),105));
    }
    @Test public void pushesCannotRenewExpiredCapabilityLease()throws Exception {
        CollectorConnection connection=new CollectorConnection();connection.confirm(capabilities("a"),100);
        assertEquals("",connection.accept(snapshot("a",1),1000));
        assertEquals("collector_handshake_required",connection.accept(snapshot("a",2),15101));
        connection.confirm(capabilities("a"),15102);assertEquals("",connection.accept(snapshot("a",2),15103));
    }
    @Test public void snapshotMustMatchThisPullHandshakeEvenIfAnotherEpochWasPreviouslyConfirmed()throws Exception {
        assertTrue(CollectorConnection.matchesPull(capabilities("a"),snapshot("a",1).json));
        assertFalse(CollectorConnection.matchesPull(capabilities("a"),snapshot("b",1).json));
        assertFalse(CollectorConnection.matchesPull(capabilities("a"),new JSONObject().put("schema_version",1).put("collector_instance_id","a")));
    }
    @Test public void v1KeepsOriginalSequenceRuleAndCannotDowngradeV2()throws Exception {
        CollectorConnection connection=new CollectorConnection();assertTrue(connection.confirmLegacy(100));
        SnapshotEnvelope old=SnapshotEnvelope.parse("{\"schema_version\":1,\"run_id\":5001,\"snapshot_id\":40,\"stage\":\"train\"}");
        assertEquals("",connection.accept(old,101));
        SnapshotEnvelope rewind=SnapshotEnvelope.parse("{\"schema_version\":1,\"run_id\":5001,\"snapshot_id\":1,\"stage\":\"train\"}");
        assertEquals("stale_or_retired_snapshot",connection.accept(rewind,102));
        connection.confirm(capabilities("a"),103);assertFalse(connection.confirmLegacy(104));assertEquals("collector_instance_mismatch",connection.accept(old,105));
    }
    @Test public void queueSeparatesSameRunAndSequenceAcrossConfirmedProcesses()throws Exception {
        LatestRequestQueue<LatestRequestQueue.Request> queue=new LatestRequestQueue<>();queue.confirmCollector(2,"a");
        LatestRequestQueue.Request first=new LatestRequestQueue.Request(5001,40,2,"a","old","cfg");queue.offer(first);queue.startNext();
        assertFalse(queue.offer(new LatestRequestQueue.Request(5001,1,2,"b","unconfirmed","cfg")));
        queue.confirmCollector(2,"b");assertFalse(queue.accepts("old"));
        LatestRequestQueue.Request next=new LatestRequestQueue.Request(5001,1,2,"b","new","cfg");assertTrue(queue.offer(next));
        assertFalse(queue.finish("old"));assertSame(next,queue.startNext());assertFalse(queue.confirmCollector(2,"a"));
        assertFalse(queue.offer(new LatestRequestQueue.Request(5001,41,2,"a","late","cfg")));
    }
    @Test public void instanceValidationRejectsUnsafeReservedAndNonAsciiNames()throws Exception {
        for(String id:new String[]{"",".","..","legacy-v1","a/b","a\\b","ä","a".repeat(129)}) {
            try{snapshot(id,1);fail("must reject "+id);}catch(IllegalArgumentException expected){}
        }
        assertEquals("A.z_1-9",snapshot("A.z_1-9",1).collectorInstanceId);
    }
    @Test public void receiptsDoNotClaimTransportAckIsRuntimeValidation()throws Exception {
        IngressReceipts receipts=new IngressReceipts();SnapshotEnvelope snapshot=snapshot("a",1);
        JSONObject ack=receipts.receive(snapshot,"");assertEquals("received",ack.getString("status"));assertFalse(ack.getBoolean("computed"));
        EngineClient.Task task=new EngineClient.Task(snapshot,"cfg",new java.io.File("unused"),ack.getString("receipt_id"));
        receipts.outcome(task,"validated",null,false);
        JSONObject checked=receipts.status().getJSONArray("recent").getJSONObject(0);assertEquals("validated",checked.getString("status"));assertFalse(checked.getBoolean("computed"));
        JSONObject rejected=receipts.receive(snapshot("old",2),"collector_instance_mismatch");assertFalse(rejected.getBoolean("accepted_for_validation"));assertEquals("rejected",rejected.getString("status"));
    }
    @Test public void recomputingSameIdentityCannotReuseAnotherConfigurationReceipt()throws Exception {
        IngressReceipts receipts=new IngressReceipts();SnapshotEnvelope snapshot=snapshot("a",1);
        JSONObject old=receipts.receive(snapshot,"","old");receipts.outcome(new EngineClient.Task(snapshot,"old",new java.io.File("unused"),old.getString("receipt_id")),"validated",null,true);
        JSONObject next=receipts.receive(snapshot,"","new");receipts.reuseOutcome(next.getString("receipt_id"));
        JSONObject recorded=receipts.status().getJSONArray("recent").getJSONObject(1);assertEquals("received",recorded.getString("status"));assertFalse(recorded.getBoolean("computed"));
    }
}
