package com.umaai.assistant.service;

import java.util.HashSet;
import java.util.Set;

/** Collection watermark survives cancellation, missing fields and transport reconnects. */
final class SnapshotOrderTracker {
    private final Set<Long> retiredRuns=new HashSet<>();
    private Long run;
    private long sequence;
    boolean observe(long nextRun,long nextSequence) {
        if(nextRun<=0||nextSequence<0||retiredRuns.contains(nextRun))return false;
        if(run!=null&&run==nextRun&&nextSequence<sequence)return false;
        if(run!=null&&run!=nextRun)retiredRuns.add(run);
        run=nextRun;sequence=nextSequence;return true;
    }
}
