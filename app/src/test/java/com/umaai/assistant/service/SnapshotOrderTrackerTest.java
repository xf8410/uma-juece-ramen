package com.umaai.assistant.service;
import org.junit.Test;
import static org.junit.Assert.*;
public class SnapshotOrderTrackerTest {
    @Test public void incompleteNewRunRetiresOldRunEvenWhenSearchQueueIsCleared() {
        SnapshotOrderTracker input=new SnapshotOrderTracker();LatestRequestQueue<LatestRequestQueue.Request> tasks=new LatestRequestQueue<>();
        assertTrue(input.observe(10,5));tasks.offer(new LatestRequestQueue.Request(10,5,"A","cfg"));tasks.startNext();
        assertTrue(input.observe(20,1));tasks.clear(); // B has missing fields, so no B search is submitted.
        assertFalse(input.observe(10,6));assertTrue(input.observe(20,2));
    }
    @Test public void UnknownRunAndReconnectNeverResetWatermark() {
        SnapshotOrderTracker input=new SnapshotOrderTracker();input.observe(10,3);input.observe(20,4);
        assertFalse(input.observe(0,0));assertFalse(input.observe(10,99));assertFalse(input.observe(20,3));
        assertTrue(input.observe(20,4));assertTrue(input.observe(20,5));
    }
}
