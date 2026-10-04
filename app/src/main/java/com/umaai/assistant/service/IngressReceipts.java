package com.umaai.assistant.service;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** A transport ACK is distinct from runtime validation and decision completion. */
final class IngressReceipts {
    private final LinkedHashMap<String,JSONObject> entries=new LinkedHashMap<>();
    private long received,validated,rejected;
    synchronized JSONObject receive(SnapshotEnvelope snapshot,String rejection) throws Exception {
        return receive(snapshot,rejection,"");
    }
    synchronized JSONObject receive(SnapshotEnvelope snapshot,String rejection,String configId) throws Exception {
        String id=UUID.randomUUID().toString();received++;
        JSONObject entry=new JSONObject().put("receipt_id",id).put("transport_received",true)
            .put("accepted_for_validation",rejection.isEmpty()).put("status",rejection.isEmpty()?"received":"rejected")
            .put("computed",false).put("config_id",configId);
        if(snapshot!=null)entry.put("schema_version",snapshot.schemaVersion).put("collector_instance_id",snapshot.collectorInstanceId)
            .put("run_id",snapshot.runId).put("snapshot_id",snapshot.snapshotId);
        if(!rejection.isEmpty()){entry.put("error",rejection);rejected++;}
        entries.put(id,entry);while(entries.size()>64)entries.remove(entries.keySet().iterator().next());
        return new JSONObject(entry.toString());
    }
    synchronized void validated(String id) { update(id,"validated",null,false); }
    synchronized JSONObject receiveUnassigned(JSONObject json,CollectorConnection collector,long now,String configId) throws Exception {
        Object sequence=json.opt("snapshot_id");
        String instance=json.optString("collector_instance_id");
        if(json.optInt("schema_version")!=2||!SnapshotEnvelope.validCollectorInstance(instance)
            ||!json.has("run_id")||!json.isNull("run_id")||!json.has("ready")||json.optBoolean("ready",true)
            ||!(sequence instanceof Number)||!sequence.toString().matches("[0-9]+")||((Number)sequence).longValue()<0)
            return receive(null,"invalid_partial_identity",configId);
        JSONObject ack=receive(null,collector.rejection(2,instance,now),configId);
        JSONObject entry=entries.get(ack.getString("receipt_id"));
        entry.put("schema_version",2).put("collector_instance_id",instance).put("run_id",JSONObject.NULL).put("snapshot_id",sequence);
        return new JSONObject(entry.toString());
    }
    synchronized void rejected(String id,String reason) { update(id,"rejected",reason,false); }
    synchronized void completed(String id) { update(id,"validated",null,true); }
    synchronized void outcome(EngineClient.Task task,String state,String error,boolean computed) {
        for(Map.Entry<String,JSONObject> entry:entries.entrySet()) {
            JSONObject value=entry.getValue();
            if(value.optLong("run_id",-1)==task.runId&&value.optLong("snapshot_id",-1)==task.snapshotId
                &&value.optInt("schema_version")==task.schemaVersion&&task.collectorInstanceId.equals(value.optString("collector_instance_id"))
                &&(value.optString("config_id").isEmpty()||task.configId.equals(value.optString("config_id"))))
                update(entry.getKey(),state,error,computed);
        }
    }
    synchronized void reuseOutcome(String id) {
        JSONObject target=entries.get(id);if(target==null)return;
        for(Map.Entry<String,JSONObject> entry:entries.entrySet()) {
            JSONObject value=entry.getValue();if(entry.getKey().equals(id)||"received".equals(value.optString("status")))continue;
            if(value.optLong("run_id",-1)==target.optLong("run_id",-2)&&value.optLong("snapshot_id",-1)==target.optLong("snapshot_id",-2)
                &&value.optInt("schema_version")==target.optInt("schema_version")&&value.optString("collector_instance_id").equals(target.optString("collector_instance_id"))
                &&value.optString("config_id").equals(target.optString("config_id")))
                update(id,value.optString("status"),value.has("error")?value.optString("error"):null,value.optBoolean("computed"));
        }
    }
    private void update(String id,String state,String error,boolean complete) {
        JSONObject value=entries.get(id);if(value==null)return;
        try {
            String before=value.optString("status");
            if(!before.equals(state)){if(state.equals("validated"))validated++;else if(state.equals("rejected"))rejected++;}
            value.put("status",state).put("computed",complete);if(state.equals("validated"))value.put("validation_scope","runtime_snapshot");if(error!=null)value.put("error",error);
        }catch(Exception impossible){throw new IllegalStateException(impossible);}
    }
    synchronized JSONObject status() throws Exception {
        JSONArray list=new JSONArray();for(Map.Entry<String,JSONObject> entry:entries.entrySet())list.put(new JSONObject(entry.getValue().toString()));
        return new JSONObject().put("received",received).put("validated",validated).put("rejected",rejected).put("recent",list);
    }
}
