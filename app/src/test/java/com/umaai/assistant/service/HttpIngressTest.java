package com.umaai.assistant.service;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import org.json.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class HttpIngressTest {
    private static final class Reply {int code;JSONObject body;}
    private Reply request(HttpDataService server,String path,String data)throws Exception {
        HttpURLConnection connection=(HttpURLConnection)new URL("http://127.0.0.1:"+server.getListeningPort()+path).openConnection();
        connection.setConnectTimeout(2000);connection.setReadTimeout(2000);
        try {
            if(data!=null){byte[] bytes=data.getBytes(StandardCharsets.UTF_8);connection.setRequestMethod("POST");connection.setDoOutput(true);connection.setRequestProperty("Content-Type","application/json");connection.setFixedLengthStreamingMode(bytes.length);try(OutputStream out=connection.getOutputStream()){out.write(bytes);}}
            Reply reply=new Reply();reply.code=connection.getResponseCode();
            try(InputStream in=reply.code>=400?connection.getErrorStream():connection.getInputStream()){reply.body=new JSONObject(PrivateFiles.read(in,65536));}return reply;
        }finally{connection.disconnect();}
    }
    @Test public void actualHttpAckRemainsReceivedUntilRuntimeValidationAndStatusAdvertisesV2()throws Exception {
        CollectorConnection collector=new CollectorConnection();collector.confirm(CollectorRecoveryTest.capabilities("live"),100);
        IngressReceipts ledger=new IngressReceipts();
        HttpDataService server=new HttpDataService(0,data->{try{SnapshotEnvelope snapshot=SnapshotEnvelope.parse(data);return ledger.receive(snapshot,collector.rejection(snapshot,101));}catch(Exception e){throw new IllegalArgumentException(e);}},()->{try{return ledger.status();}catch(Exception e){throw new IllegalStateException(e);}});
        server.startServer();try {
            SnapshotEnvelope snapshot=CollectorRecoveryTest.snapshot("live",1);Reply ack=request(server,"/data",snapshot.json.toString());
            assertEquals(202,ack.code);assertEquals("received",ack.body.getString("status"));assertTrue(ack.body.getBoolean("accepted_for_validation"));assertFalse(ack.body.getBoolean("computed"));
            Reply status=request(server,"/status",null);assertEquals(2,status.body.getJSONArray("supported_snapshot_schema_versions").getInt(1));
            assertEquals("received",status.body.getJSONObject("runtime").getJSONArray("recent").getJSONObject(0).getString("status"));
            ledger.outcome(new EngineClient.Task(snapshot,"cfg",new File("unused"),ack.body.getString("receipt_id")),"validated",null,false);
            Reply checked=request(server,"/status",null);assertEquals("validated",checked.body.getJSONObject("runtime").getJSONArray("recent").getJSONObject(0).getString("status"));
        }finally{server.stopServer();}
    }
    @Test public void oldEpochCannotReceiveSuccessfulTransportAcceptance()throws Exception {
        CollectorConnection collector=new CollectorConnection();collector.confirm(CollectorRecoveryTest.capabilities("new"),100);IngressReceipts ledger=new IngressReceipts();
        HttpDataService server=new HttpDataService(0,data->{try{SnapshotEnvelope snapshot=SnapshotEnvelope.parse(data);return ledger.receive(snapshot,collector.rejection(snapshot,101));}catch(Exception e){throw new IllegalArgumentException(e);}},JSONObject::new);
        server.startServer();try{Reply ack=request(server,"/data",CollectorRecoveryTest.snapshot("old",1).json.toString());assertEquals(400,ack.code);assertFalse(ack.body.getBoolean("accepted_for_validation"));assertEquals("collector_instance_mismatch",ack.body.getString("error"));}finally{server.stopServer();}
    }
    @Test public void unknownRunIsAcknowledgedOnlyForConfirmedProcessWithoutClaimingReady()throws Exception {
        CollectorConnection collector=new CollectorConnection();collector.confirm(CollectorRecoveryTest.capabilities("live"),100);IngressReceipts ledger=new IngressReceipts();
        HttpDataService server=new HttpDataService(0,data->{try{return ledger.receiveUnassigned(new JSONObject(data),collector,101,"cfg");}catch(Exception e){throw new IllegalArgumentException(e);}},JSONObject::new);
        JSONObject partial=new JSONObject().put("schema_version",2).put("collector_instance_id","live").put("run_id",JSONObject.NULL).put("snapshot_id",1).put("ready",false);
        server.startServer();try {
            Reply ack=request(server,"/data",partial.toString());assertEquals(202,ack.code);assertEquals("received",ack.body.getString("status"));
            assertTrue(ack.body.isNull("run_id"));assertEquals("live",ack.body.getString("collector_instance_id"));assertEquals(1,ack.body.getLong("snapshot_id"));assertFalse(ack.body.getBoolean("computed"));
            ledger.rejected(ack.body.getString("receipt_id"),"run_id unavailable");assertEquals("rejected",ledger.status().getJSONArray("recent").getJSONObject(0).getString("status"));
            partial.put("collector_instance_id","old");assertEquals(400,request(server,"/data",partial.toString()).code);
            partial.put("collector_instance_id","live").put("snapshot_id",-1);assertEquals(400,request(server,"/data",partial.toString()).code);
        }finally{server.stopServer();}
    }
}
