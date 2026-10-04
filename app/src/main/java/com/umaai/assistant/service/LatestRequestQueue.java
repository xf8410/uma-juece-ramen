package com.umaai.assistant.service;

import java.util.HashSet;
import java.util.Set;

/** One running task and one latest pending task. All calls belong to one owner thread. */
public final class LatestRequestQueue<T extends LatestRequestQueue.Request> {
    public static class Request {
        public final long runId, snapshotId;
        public final int schemaVersion;
        public final String collectorInstanceId;
        public final String requestId, configId;
        public Request(long runId, long snapshotId, String requestId, String configId) {
            this(runId,snapshotId,1,SnapshotEnvelope.LEGACY_INSTANCE,requestId,configId);
        }
        public Request(long runId,long snapshotId,int schema,String instance,String requestId,String configId) {
            this.runId = runId; this.snapshotId = snapshotId; this.requestId = requestId; this.configId = configId;
            schemaVersion=schema;collectorInstanceId=instance;
        }
    }
    private final Set<Long> retiredRuns = new HashSet<>();
    private final Set<String> retiredCollectors=new HashSet<>();
    private String selectedCollector;
    private T running, pending, latest;
    public boolean offer(T request) {
        String collector=collectorKey(request.schemaVersion,request.collectorInstanceId);
        if(selectedCollector==null&&request.schemaVersion==1)confirmCollector(1,SnapshotEnvelope.LEGACY_INSTANCE);
        if(!collector.equals(selectedCollector)||retiredCollectors.contains(collector))return false;
        if (retiredRuns.contains(request.runId)) return false;
        if (latest != null) {
            boolean sameCollector=collector.equals(collectorKey(latest.schemaVersion,latest.collectorInstanceId));
            if (sameCollector&&request.runId == latest.runId && request.snapshotId < latest.snapshotId) return false;
            if (sameCollector&&request.runId == latest.runId && request.snapshotId == latest.snapshotId
                    && request.configId.equals(latest.configId)) return false;
            if (request.runId != latest.runId) retiredRuns.add(latest.runId);
        }
        latest = request; pending = request; return true;
    }
    public T startNext() {
        if (running != null || pending == null) return null;
        running = pending; pending = null; return running;
    }
    public T running() { return running; }
    public T pending() { return pending; }
    public boolean accepts(String requestId) { return latest != null && latest.requestId.equals(requestId)&&collectorKey(latest.schemaVersion,latest.collectorInstanceId).equals(selectedCollector); }
    public boolean confirmCollector(int schema,String instance) {
        if((schema!=1&&schema!=2)||!SnapshotEnvelope.validInstance(instance))return false;
        if(schema==2&&!SnapshotEnvelope.validCollectorInstance(instance))return false;
        String key=collectorKey(schema,instance);if(retiredCollectors.contains(key))return false;
        if(!key.equals(selectedCollector)&&selectedCollector!=null)retiredCollectors.add(selectedCollector);
        if(pending!=null&&!key.equals(collectorKey(pending.schemaVersion,pending.collectorInstanceId)))pending=null;
        selectedCollector=key;return true;
    }
    private static String collectorKey(int schema,String instance){return schema+":"+instance;}
    public boolean finish(String requestId) {
        if (running == null || !running.requestId.equals(requestId)) return false;
        boolean valid = accepts(requestId); running = null; return valid;
    }
    public T releaseRunning() { T old = running; running = null; return old; }
    public void retry(T request) { if (request != null && accepts(request.requestId)) pending = request; }
    public void cancelTasks(){running=pending=latest=null;}
    public void clear() { cancelTasks();retiredRuns.clear();retiredCollectors.clear();selectedCollector=null; }
}
