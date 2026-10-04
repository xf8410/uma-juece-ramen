package com.umaai.assistant.service;
import java.io.*;
import java.util.*;
import org.json.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public class ArchiveRecoveryTest {
    @Rule public TemporaryFolder temp=new TemporaryFolder();
    private JSONObject options()throws Exception{return new JSONObject().put("config_id","cfg");}
    private RunArchive recorder()throws Exception{RunArchive r=new RunArchive(temp.getRoot());r.registerConfiguration(options(),new JSONObject().put("mcts_turn_bonus",2));return r;}
    @Test public void gameAndAppRestartKeepBothSequenceOneSnapshotsAndTheirCsvMappings()throws Exception {
        SnapshotEnvelope a=CollectorRecoveryTest.snapshot("first-process",1),b=CollectorRecoveryTest.snapshot("next-process",1);
        a.json.getJSONObject("state").getJSONObject("baseGame").put("turn",10);
        RunArchive old=recorder();File first=old.capture(a,options());
        RunArchive restarted=recorder();File second=restarted.capture(b,options());assertNotEquals(first,second);
        assertTrue(first.isFile());assertTrue(second.isFile());
        ArchiveSnapshotIndex.Entry one=ArchiveSnapshotIndex.resolve(restarted.run(5001),a),two=ArchiveSnapshotIndex.resolve(restarted.run(5001),b);
        assertNotEquals(one.sequence,two.sequence);assertNotEquals(one.file,two.file);
        JSONObject exported=new JSONObject(PrivateFiles.read(new File(restarted.run(5001),two.file),65536));
        assertEquals("next-process",exported.getString("runtime_collector_instance_id"));assertEquals(1,exported.getLong("runtime_snapshot_id"));
        JSONObject event=new JSONObject("{\"type\":\"decision\",\"decision\":{\"action_index\":0,\"candidate_descriptions\":[\"休息\"],\"candidate_scores\":[]}}");
        old.event(new EngineClient.Task(a,"cfg",first,""),event,1);restarted.event(new EngineClient.Task(b,"cfg",second,""),event,1);
        List<List<String>> csv=ArchiveCsv.parse(PrivateFiles.read(new File(restarted.run(5001),"decisions.csv"),65536));
        assertEquals(3,csv.size());assertEquals(one.file,csv.get(1).get(1));assertEquals(two.file,csv.get(2).get(1));
        assertEquals(Long.toString(two.sequence),csv.get(2).get(3));assertEquals("next-process",csv.get(2).get(36));assertEquals("1",csv.get(2).get(37));
        JSONObject meta=new JSONObject(PrivateFiles.read(new File(restarted.run(5001),"meta.json"),65536));assertEquals(2,meta.getInt("snapshots"));assertEquals(2,meta.getJSONObject("collector_instances").length());
    }
    @Test public void sameInstanceAndSequenceStillCannotOverwriteDifferentStateAfterAppRestart()throws Exception {
        SnapshotEnvelope a=CollectorRecoveryTest.snapshot("a",1);recorder().capture(a,options());a.json.getJSONObject("state").put("changed",true);
        try{recorder().capture(a,options());fail("same-instance identity collision must remain an error");}catch(IOException expected){}
    }
    @Test public void legacyCsvMigrationPreservesQuotedMultilineRowsAndOriginalBackup()throws Exception {
        RunArchive archive=recorder();SnapshotEnvelope a=CollectorRecoveryTest.snapshot("a",1);File path=archive.capture(a,options());
        JSONObject event=new JSONObject("{\"type\":\"decision\",\"decision\":{\"action_index\":0,\"candidate_descriptions\":[\"休息\"],\"candidate_scores\":[]}}");
        archive.event(new EngineClient.Task(a,"cfg",path,""),event,1);
        File csvFile=new File(archive.run(5001),"decisions.csv");List<List<String>> rows=ArchiveCsv.parse(PrivateFiles.read(csvFile,65536));
        List<String> oldHeader=new ArrayList<>(rows.get(0).subList(0,35)),oldRow=new ArrayList<>(rows.get(1).subList(0,35));oldRow.set(29,"quoted,\"name\"\nsecond line");
        String legacy=String.join(",",oldHeader)+"\n"+ArchiveCsv.write(oldRow)+"\n";PrivateFiles.write(csvFile,legacy);
        SnapshotEnvelope b=CollectorRecoveryTest.snapshot("b",1);File next=archive.capture(b,options());archive.event(new EngineClient.Task(b,"cfg",next,""),event,1);
        List<List<String>> updated=ArchiveCsv.parse(PrivateFiles.read(csvFile,65536));assertEquals(43,updated.get(0).size());assertEquals(oldRow.get(29),updated.get(1).get(29));
        assertEquals("legacy-v1",updated.get(1).get(36));assertEquals(legacy,PrivateFiles.read(new File(archive.run(5001),"decisions.legacy-v1.csv"),65536));
    }
}
