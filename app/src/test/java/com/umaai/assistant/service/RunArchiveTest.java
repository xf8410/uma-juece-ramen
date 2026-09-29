package com.umaai.assistant.service;
import java.io.*;
import org.json.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;
public class RunArchiveTest {
    @Rule public TemporaryFolder temp=new TemporaryFolder();
    private SnapshotEnvelope snapshot(long seq)throws Exception{return SnapshotEnvelope.parse("{\"schema_version\":1,\"run_id\":9,\"snapshot_id\":"+seq+",\"stage\":\"train\",\"state\":{\"baseGame\":{\"turn\":3,\"umaId\":123},\"ramen\":{}}}");}
    @Test public void keepsEachPhaseAndDeduplicatesPushPoll()throws Exception{
        RunArchive archive=new RunArchive(temp.getRoot());JSONObject options=new JSONObject();
        archive.registerConfiguration(options,new JSONObject());
        File a=archive.capture(snapshot(10),options);archive.capture(snapshot(10),options);File b=archive.capture(snapshot(11),options);
        assertNotEquals(a,b);JSONObject meta=new JSONObject(PrivateFiles.read(new File(archive.run(9),"meta.json"),65536));assertEquals(2,meta.getInt("snapshots"));assertFalse(meta.getBoolean("complete"));
        assertTrue(new File(archive.run(9),"game9_turn3_10.json").isFile());assertTrue(new File(archive.run(9),"game9_turn3_11.json").isFile());
    }
    @Test public void streamedDecisionAndFinalResultProduceOneCsvRow()throws Exception{
        RunArchive archive=new RunArchive(temp.getRoot());JSONObject config=new JSONObject().put("config_id","cfg");archive.registerConfiguration(config,new JSONObject());File path=archive.capture(snapshot(10),config);
        EngineClient.Task task=new EngineClient.Task(9,10,"cfg",path,false);
        JSONObject event=new JSONObject("{\"type\":\"decision\",\"decision\":{\"decision_kind\":\"train\",\"action_index\":1,\"candidate_descriptions\":[\"训练,速\",\"休息\"],\"candidate_scores\":[10,20],\"candidate_n\":[2,2]}}");
        archive.event(task,event,1);archive.result(task,new JSONObject().put("ok",true).put("evaluation",new JSONObject().put("events",new JSONArray().put(event))));
        JSONObject meta=new JSONObject(PrivateFiles.read(new File(archive.run(9),"meta.json"),65536));assertEquals(1,meta.getInt("decision_rows"));
        String csv=PrivateFiles.read(new File(archive.run(9),"decisions.csv"),65536);assertEquals(2,csv.split("\n").length);assertTrue(csv.contains("\"训练,速\""));
    }
    @Test(expected=IOException.class) public void identicalIdCannotSilentlyOverwriteState()throws Exception{
        RunArchive archive=new RunArchive(temp.getRoot());SnapshotEnvelope a=snapshot(1);archive.registerConfiguration(new JSONObject(),new JSONObject());archive.capture(a,new JSONObject());a.json.put("stage","event");archive.capture(a,new JSONObject());
    }
    @Test public void pcCsvLuckColumnsUseObservedExtraAndMatchingGenerationBonus()throws Exception {
        RunArchive archive=new RunArchive(temp.getRoot());JSONObject config=new JSONObject().put("config_id","cfg");
        archive.registerConfiguration(config,new JSONObject().put("mcts_turn_bonus",2));
        File path=archive.capture(snapshot(10),config);EngineClient.Task task=new EngineClient.Task(9,10,"cfg",path,false);
        JSONObject event=new JSONObject("{\"type\":\"decision\",\"view\":{\"turn\":14,\"max_turn\":78},\"decision\":{\"decision_kind\":\"train\",\"action_index\":1,\"candidate_descriptions\":[\"速度\",\"休息\"],\"candidate_scores\":[100,200],\"scenario_extra\":{\"action_luck\":{\"0\":-50,\"1\":25},\"current_terminal_baseline\":60410.5,\"total_luck_score\":152.5,\"last_turn_delta\":-12.4}}}");
        archive.event(task,event,1);
        String[] row=csvRow(archive.run(9),1);
        assertEquals("25.00",row[30]);assertEquals("60282.50",row[31]);assertEquals("60410.50",row[32]);
        assertEquals("152.50",row[33]);assertEquals("-12.40",row[34]);
        JSONObject meta=new JSONObject(PrivateFiles.read(new File(archive.run(9),"meta.json"),65536));assertEquals(152.5,meta.getDouble("total_luck_end"),0.0);
    }
    @Test public void missingScoresAndLuckRemainEmptyInsteadOfZero()throws Exception {
        RunArchive archive=new RunArchive(temp.getRoot());JSONObject config=new JSONObject().put("config_id","cfg");archive.registerConfiguration(config,new JSONObject());File path=archive.capture(snapshot(10),config);
        JSONObject event=new JSONObject("{\"type\":\"decision\",\"decision\":{\"decision_kind\":\"event\",\"action_index\":0,\"candidate_descriptions\":[\"选项1\"],\"candidate_scores\":[]}}");
        archive.event(new EngineClient.Task(9,10,"cfg",path,false),event,1);String[] row=csvRow(archive.run(9),1);
        for(int col=30;col<=34;col++)assertEquals("",row[col]);assertEquals("",row[18]);
        assertFalse(new JSONObject(PrivateFiles.read(new File(archive.run(9),"meta.json"),65536)).has("total_luck_end"));
    }
    @Test public void continuationIsPreservedForPcReviewWithoutMutatingEnvelopeState()throws Exception {
        RunArchive archive=new RunArchive(temp.getRoot());SnapshotEnvelope snapshot=snapshot(10);
        archive.registerConfiguration(new JSONObject(),new JSONObject());
        JSONObject continuation=new JSONObject().put("inherit_extra_count",new JSONArray("[1,2,3,4,5,6]")).put("eat_count",3);
        snapshot.json.put("continuation",continuation);archive.capture(snapshot,new JSONObject());
        JSONObject state=new JSONObject(PrivateFiles.read(new File(archive.run(9),RunArchive.stateName(snapshot)),65536));
        assertEquals(6,state.getJSONObject("runtime_continuation").getJSONArray("inherit_extra_count").getInt(5));
        assertFalse(snapshot.json.getJSONObject("state").has("runtime_continuation"));assertFalse(snapshot.json.getJSONObject("state").has("runtime_stage"));
    }
    @Test public void recomputingSameSnapshotRecordsBothImmutableConfigGenerations()throws Exception {
        RunArchive archive=new RunArchive(temp.getRoot());JSONObject a=new JSONObject().put("config_id","a").put("policy","mcts");
        JSONObject versionsA=new JSONObject().put("model_version","not_used").put("mcts_turn_bonus",2);
        archive.registerConfiguration(a,versionsA);File path=archive.capture(snapshot(10),a);
        JSONObject b=new JSONObject().put("config_id","b").put("policy","nn");
        archive.registerConfiguration(b,new JSONObject().put("model_version","model-b").put("mcts_turn_bonus",99));archive.capture(snapshot(10),b);
        versionsA.put("model_version","mutated-after-registration");
        JSONObject event=new JSONObject("{\"type\":\"decision\",\"view\":{\"turn\":14,\"max_turn\":78},\"decision\":{\"action_index\":0,\"candidate_descriptions\":[\"速度\"],\"candidate_scores\":[100],\"scenario_extra\":{\"current_terminal_baseline\":60410.5}}}");
        archive.event(new EngineClient.Task(9,10,"a",path,false),event,1);
        JSONObject meta=new JSONObject(PrivateFiles.read(new File(archive.run(9),"meta.json"),65536));
        assertEquals(1,meta.getInt("snapshots"));assertEquals("b",meta.getString("active_config_id"));assertEquals("model-b",meta.getString("model_version"));
        assertEquals(2,meta.getJSONObject("config_history").length());
        assertEquals("not_used",meta.getJSONObject("config_history").getJSONObject("a").getJSONObject("versions").getString("model_version"));
        assertEquals("60282.50",csvRow(archive.run(9),1)[31]);
    }
    @Test public void captureBetweenConfigRequestAndRegistrationCannotPoisonNewGeneration()throws Exception {
        RunArchive archive=new RunArchive(temp.getRoot());JSONObject a=new JSONObject().put("config_id","a");
        JSONObject b=new JSONObject().put("config_id","b").put("policy","nn");
        archive.registerConfiguration(a,new JSONObject().put("model_version","not_used"));archive.capture(snapshot(10),a);
        // Reproduce HTTP arriving after B was requested but before its IO registration.
        try { archive.capture(snapshot(11),b);fail("Unregistered B must not be stored with empty versions"); }
        catch(IOException expected) { assertTrue(expected.getMessage().contains("尚未登记")); }
        assertFalse(new File(archive.run(9),"envelopes/11.json").exists());
        JSONObject before=new JSONObject(PrivateFiles.read(new File(archive.run(9),"meta.json"),65536));
        assertFalse(before.getJSONObject("config_history").has("b"));assertEquals(1,before.getInt("snapshots"));
        archive.registerConfiguration(b,new JSONObject().put("model_version","verified-model-b").put("data_version","verified-data-b"));
        archive.capture(snapshot(11),b);
        JSONObject after=new JSONObject(PrivateFiles.read(new File(archive.run(9),"meta.json"),65536));
        assertEquals(2,after.getInt("snapshots"));assertEquals("b",after.getString("active_config_id"));
        assertEquals("verified-model-b",after.getJSONObject("config_history").getJSONObject("b").getJSONObject("versions").getString("model_version"));
    }
    private static String[] csvRow(File run,int line)throws Exception {
        String row=PrivateFiles.read(new File(run,"decisions.csv"),65536).split("\n")[line];
        // These fixtures deliberately contain no commas or escaped quotes in names.
        String[] columns=row.substring(1,row.length()-1).split("\",\"",-1);assertEquals(35,columns.length);return columns;
    }
}
