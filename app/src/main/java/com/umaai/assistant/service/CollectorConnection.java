package com.umaai.assistant.service;

import org.json.JSONArray;
import org.json.JSONObject;

/** Confirmed process identity is established by capabilities, never by an unsolicited snapshot. */
final class CollectorConnection {
    static final long HANDSHAKE_MAX_AGE_MS=15_000;
    private final SnapshotOrderTracker order=new SnapshotOrderTracker();
    private int schema;
    private long confirmedAt=-1;
    private String version="unknown";
    synchronized boolean confirm(JSONObject capabilities,long now) throws Exception {
        String instance=capabilityInstance(capabilities);
        String previous=order.instance();
        if(!order.confirmInstance(instance))throw new IllegalArgumentException("retired_or_invalid_collector_instance");
        schema=2;confirmedAt=now;version=capabilities.optString("collector_version","unknown");
        return !instance.equals(previous);
    }
    static String capabilityInstance(JSONObject capabilities)throws Exception {
        if(capabilities.getInt("capability_schema_version")!=1)throw new IllegalArgumentException("unsupported_capabilities");
        JSONArray supported=capabilities.getJSONArray("snapshot_schema_versions");boolean v2=false;
        for(int i=0;i<supported.length();i++)if(supported.optInt(i)==2)v2=true;
        if(!v2)throw new IllegalArgumentException("collector_requires_supported_v2");
        String instance=capabilities.getString("collector_instance_id");
        if(!SnapshotEnvelope.validCollectorInstance(instance))throw new IllegalArgumentException("invalid_collector_instance");
        return instance;
    }
    static boolean matchesPull(JSONObject capabilities,JSONObject snapshot)throws Exception {
        return snapshot.optInt("schema_version",-1)==2&&capabilityInstance(capabilities).equals(snapshot.optString("collector_instance_id"));
    }
    synchronized boolean confirmLegacy(long now) {
        if(schema==2)return false;
        if(!order.confirmInstance(SnapshotEnvelope.LEGACY_INSTANCE))return false;
        schema=1;confirmedAt=now;return true;
    }
    synchronized String rejection(SnapshotEnvelope snapshot,long now) {
        return rejection(snapshot.schemaVersion,snapshot.collectorInstanceId,now);
    }
    synchronized String rejection(int snapshotSchema,String instance,long now) {
        if(order.instance()==null||confirmedAt<0||now-confirmedAt>HANDSHAKE_MAX_AGE_MS)return "collector_handshake_required";
        if(snapshotSchema!=schema||!order.instance().equals(instance))return "collector_instance_mismatch";
        return "";
    }
    synchronized String accept(SnapshotEnvelope snapshot,long now) {
        String reason=rejection(snapshot,now);if(!reason.isEmpty())return reason;
        return order.observe(snapshot)?"":"stale_or_retired_snapshot";
    }
    synchronized String instance() { return order.instance(); }
    synchronized int schema() { return schema; }
    synchronized JSONObject status(long now) throws Exception {
        return new JSONObject().put("schema_version",schema).put("collector_instance_id",order.instance()==null?JSONObject.NULL:order.instance())
            .put("collector_version",version).put("handshake_age_ms",confirmedAt<0?JSONObject.NULL:Math.max(0,now-confirmedAt));
    }
}
