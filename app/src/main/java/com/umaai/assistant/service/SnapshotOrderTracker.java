package com.umaai.assistant.service;

import java.util.HashSet;
import java.util.Set;

/** Collection watermark survives cancellation, missing fields and transport reconnects. */
final class SnapshotOrderTracker {
    private final Set<Long> retiredRuns=new HashSet<>();
    private final Set<String> retiredInstances=new HashSet<>();
    private String collectorInstance;
    private Long run;
    private long sequence;
    boolean confirmInstance(String instance) {
        if(!SnapshotEnvelope.validInstance(instance)||retiredInstances.contains(instance))return false;
        if(!SnapshotEnvelope.LEGACY_INSTANCE.equals(instance)&&!SnapshotEnvelope.validCollectorInstance(instance))return false;
        if(instance.equals(collectorInstance))return true;
        if(collectorInstance!=null)retiredInstances.add(collectorInstance);
        collectorInstance=instance;sequence=-1;return true;
    }
    boolean observe(SnapshotEnvelope snapshot) { return observe(snapshot.runId,snapshot.collectorInstanceId,snapshot.snapshotId); }
    boolean observe(long nextRun,long nextSequence) {
        if(collectorInstance==null)confirmInstance(SnapshotEnvelope.LEGACY_INSTANCE);
        return observe(nextRun,SnapshotEnvelope.LEGACY_INSTANCE,nextSequence);
    }
    boolean observe(long nextRun,String instance,long nextSequence) {
        if(!instance.equals(collectorInstance)||retiredInstances.contains(instance))return false;
        if(nextRun<=0||nextSequence<0||retiredRuns.contains(nextRun))return false;
        if(run!=null&&run==nextRun&&nextSequence<sequence)return false;
        if(run!=null&&run!=nextRun)retiredRuns.add(run);
        run=nextRun;sequence=nextSequence;return true;
    }
    String instance() { return collectorInstance; }
}
