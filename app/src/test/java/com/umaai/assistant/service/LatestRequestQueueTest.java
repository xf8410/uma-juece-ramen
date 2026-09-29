package com.umaai.assistant.service;
import org.junit.Test;
import static org.junit.Assert.*;
public class LatestRequestQueueTest {
    private LatestRequestQueue.Request request(long run,long seq,String config) { return new LatestRequestQueue.Request(run,seq,run+"-"+seq+"-"+config,config); }
    @Test public void newSnapshotCancelsResultAndOnlyLatestPendingRuns() {
        LatestRequestQueue<LatestRequestQueue.Request> queue=new LatestRequestQueue<>();
        LatestRequestQueue.Request first=request(1,1,"a"),second=request(1,2,"a"),third=request(1,3,"a");
        assertTrue(queue.offer(first));assertSame(first,queue.startNext());
        assertTrue(queue.offer(second));assertTrue(queue.offer(third));assertNull(queue.startNext());
        assertFalse(queue.finish(first.requestId));assertSame(third,queue.startNext());assertTrue(queue.finish(third.requestId));
    }
    @Test public void duplicatedAndOutOfOrderSnapshotsDoNotSearchAgain() {
        LatestRequestQueue<LatestRequestQueue.Request> queue=new LatestRequestQueue<>();
        assertTrue(queue.offer(request(1,4,"a")));assertFalse(queue.offer(request(1,4,"a")));assertFalse(queue.offer(request(1,3,"a")));
    }
    @Test public void retiredRunCannotTakeOverAfterNewRun() {
        LatestRequestQueue<LatestRequestQueue.Request> queue=new LatestRequestQueue<>();
        assertTrue(queue.offer(request(1,10,"a")));assertTrue(queue.offer(request(2,1,"a")));assertFalse(queue.offer(request(1,11,"a")));
    }
    @Test public void changedConfigCanRecomputeSameSnapshot() {
        LatestRequestQueue<LatestRequestQueue.Request> queue=new LatestRequestQueue<>();
        LatestRequestQueue.Request old=request(1,4,"old"), fresh=request(1,4,"new");
        queue.offer(old);queue.startNext();assertTrue(queue.offer(fresh));assertFalse(queue.finish(old.requestId));assertSame(fresh,queue.startNext());
    }
    @Test public void lateCompletionCannotFinishANewerRunningTask() {
        LatestRequestQueue<LatestRequestQueue.Request> queue=new LatestRequestQueue<>();
        LatestRequestQueue.Request old=request(1,4,"a"),fresh=request(1,5,"a");
        queue.offer(old);queue.startNext();queue.offer(fresh);queue.releaseRunning();queue.startNext();
        assertFalse(queue.finish(old.requestId));assertSame(fresh,queue.running());
    }
    @Test public void crashRetriesOnlyTheCurrentSnapshot() {
        LatestRequestQueue<LatestRequestQueue.Request> queue=new LatestRequestQueue<>();
        LatestRequestQueue.Request old=request(1,4,"a"),fresh=request(1,5,"a");
        queue.offer(old);queue.startNext();queue.offer(fresh);queue.retry(queue.releaseRunning());assertSame(fresh,queue.startNext());
    }
}
