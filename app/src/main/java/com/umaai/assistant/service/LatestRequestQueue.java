package com.umaai.assistant.service;

import java.util.HashSet;
import java.util.Set;

/** One running task and one latest pending task. All calls belong to one owner thread. */
public final class LatestRequestQueue<T extends LatestRequestQueue.Request> {
    public static class Request {
        public final long runId, snapshotId;
        public final String requestId, configId;
        public Request(long runId, long snapshotId, String requestId, String configId) {
            this.runId = runId; this.snapshotId = snapshotId; this.requestId = requestId; this.configId = configId;
        }
    }
    private final Set<Long> retiredRuns = new HashSet<>();
    private T running, pending, latest;
    public boolean offer(T request) {
        if (retiredRuns.contains(request.runId)) return false;
        if (latest != null) {
            if (request.runId == latest.runId && request.snapshotId < latest.snapshotId) return false;
            if (request.runId == latest.runId && request.snapshotId == latest.snapshotId
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
    public boolean accepts(String requestId) { return latest != null && latest.requestId.equals(requestId); }
    public boolean finish(String requestId) {
        if (running == null || !running.requestId.equals(requestId)) return false;
        boolean valid = accepts(requestId); running = null; return valid;
    }
    public T releaseRunning() { T old = running; running = null; return old; }
    public void retry(T request) { if (request != null && accepts(request.requestId)) pending = request; }
    public void clear() { running = pending = latest = null; retiredRuns.clear(); }
}
