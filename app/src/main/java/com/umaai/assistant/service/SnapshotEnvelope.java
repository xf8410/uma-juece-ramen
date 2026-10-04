package com.umaai.assistant.service;

import org.json.JSONArray;
import org.json.JSONObject;

/** Identity validation only; the Rust runtime validates all game-state fields. */
public final class SnapshotEnvelope {
    public static final String LEGACY_INSTANCE="legacy-v1";
    public final int schemaVersion;
    public final String collectorInstanceId;
    public final long runId, snapshotId;
    public final String stage;
    public final JSONObject json;
    private SnapshotEnvelope(JSONObject json, long runId, long snapshotId, String stage,int schema,String instance) {
        this.json = json; this.runId = runId; this.snapshotId = snapshotId; this.stage = stage;
        this.schemaVersion=schema;this.collectorInstanceId=instance;
    }
    public static SnapshotEnvelope parse(String text) throws Exception {
        JSONObject json = new JSONObject(text);
        int schema=json.optInt("schema_version",-1);
        if (schema!=1&&schema!=2) throw new IllegalArgumentException("不支持的采集协议；旧版摘要仅供展示");
        String instance=schema==1?LEGACY_INSTANCE:json.getString("collector_instance_id");
        if(schema==2&&!validCollectorInstance(instance))throw new IllegalArgumentException("collector_instance_id 格式无效");
        if(!hasIdentity(json))throw new IllegalArgumentException("缺少有效的真实局和快照标识");
        long run = json.getLong("run_id"), sequence = json.getLong("snapshot_id");
        if (run <= 0 || sequence < 0) throw new IllegalArgumentException("局标识必须为正整数，快照标识必须为非负整数");
        String stage = json.getString("stage");
        if (stage.isEmpty()) throw new IllegalArgumentException("快照缺少阶段");
        return new SnapshotEnvelope(json, run, sequence, stage,schema,instance);
    }
    public static boolean validInstance(String value) { return value!=null&&value.matches("[A-Za-z0-9._-]{1,128}"); }
    public static boolean validCollectorInstance(String value){return validInstance(value)&&!value.equals(".")&&!value.equals("..")&&!value.equals(LEGACY_INSTANCE);}
    public String identityKey() { return schemaVersion+":"+collectorInstanceId+":"+runId+":"+snapshotId; }
    public boolean sameIdentity(SnapshotEnvelope other) { return other!=null&&schemaVersion==other.schemaVersion&&collectorInstanceId.equals(other.collectorInstanceId)&&runId==other.runId&&snapshotId==other.snapshotId; }
    public int turn() {
        JSONObject state = json.optJSONObject("state");
        JSONObject base = state == null ? null : state.optJSONObject("baseGame");
        return base == null ? -1 : base.optInt("turn", -1);
    }
    public String missingReason() {
        return readinessProblem(json);
    }
    public static String readinessProblem(JSONObject json) {
        JSONArray missing = json.optJSONArray("missing_fields");
        if (missing != null && missing.length() > 0) return "采集缺少：" + missing.toString();
        JSONObject completeness = json.optJSONObject("completeness");
        if (completeness != null) {
            JSONArray fields = completeness.optJSONArray("missing_fields");
            if (fields != null && fields.length() > 0) return "采集缺少：" + fields.toString();
        }
        if(json.has("ready")&&!json.optBoolean("ready"))return "采集端尚未取得完整盘面";
        if(json.optInt("schema_version")==2&&(!json.has("ready")||!"verified".equals(json.optString("capture_coherence"))))return "V2 采集一致性尚未确认";
        if(json.isNull("run_id"))return "采集端尚未取得真实局标识";
        return "";
    }
    public static boolean hasIdentity(JSONObject json) {
        Object run=json.opt("run_id"),sequence=json.opt("snapshot_id");
        return run instanceof Number && sequence instanceof Number && run.toString().matches("[0-9]+") && sequence.toString().matches("[0-9]+")
            && ((Number)run).longValue()>0 && ((Number)sequence).longValue()>=0;
    }
}
