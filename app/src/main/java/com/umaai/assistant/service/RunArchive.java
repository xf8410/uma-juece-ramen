package com.umaai.assistant.service;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Lossless envelopes plus PC-compatible state snapshots and decision CSV. Single IO owner. */
public final class RunArchive {
    private final File root;
    private final Set<String> recordedEvents = new HashSet<>();
    private final Map<String,JSONObject> configurations=new HashMap<>();
    private Long activeRun;
    private final Set<Long> seenRuns=new HashSet<>();
    public RunArchive(File filesDirectory) { root = new File(filesDirectory, "runs"); }
    public File root() { return root; }
    public File run(long id) { return new File(root, "game" + id); }
    public synchronized void registerConfiguration(JSONObject options,JSONObject values) throws Exception {
        String id=options.optString("config_id","unversioned");
        JSONObject value=new JSONObject().put("options",new JSONObject(options.toString())).put("versions",new JSONObject(values.toString()));
        JSONObject previous=configurations.get(id);
        if(previous!=null&&!JsonValues.same(previous,value))throw new IOException("同一 config_id 的版本信息发生变化");
        configurations.put(id,value);
    }
    public synchronized File capture(SnapshotEnvelope snapshot, JSONObject options) throws Exception {
        requireConfiguration(options);
        if(!seenRuns.contains(snapshot.runId)) {
            if(activeRun!=null&&activeRun!=snapshot.runId)finish("switch");
            activeRun=snapshot.runId;seenRuns.add(snapshot.runId);
        }
        File directory = run(snapshot.runId);
        File envelope = new File(directory,snapshot.schemaVersion==1?"envelopes/"+snapshot.snapshotId+".json":
            "envelopes/"+snapshot.collectorInstanceId+"/"+snapshot.snapshotId+".json");
        if (envelope.isFile()) {
            JSONObject previous = new JSONObject(PrivateFiles.read(envelope, PrivateFiles.MAX_SNAPSHOT_BYTES));
            if (!JsonValues.same(previous,snapshot.json)) throw new IOException("同一快照标识内容发生变化，采集端需要递增 snapshot_id");
            ArchiveSnapshotIndex.resolve(directory,snapshot);
            JSONObject existing=readMeta(directory);applyConfiguration(existing,options);
            PrivateFiles.write(new File(directory,"meta.json"),existing.toString(2));
            return envelope;
        }
        ArchiveSnapshotIndex.Entry mapping=ArchiveSnapshotIndex.resolve(directory,snapshot);
        JSONObject state = snapshot.json.optJSONObject("state");
        if (state != null) {
            JSONObject reviewState=new JSONObject(state.toString()).put("runtime_stage",snapshot.stage);
            reviewState.put("runtime_schema_version",snapshot.schemaVersion).put("runtime_collector_instance_id",snapshot.collectorInstanceId)
                .put("runtime_snapshot_id",snapshot.snapshotId).put("runtime_archive_seq",mapping.sequence);
            JSONObject continuation=snapshot.json.optJSONObject("continuation");
            if(continuation!=null)reviewState.put("runtime_continuation",new JSONObject(continuation.toString()));
            PrivateFiles.write(new File(directory,mapping.file),reviewState.toString());
        }
        PrivateFiles.write(envelope, snapshot.json.toString());
        JSONObject meta = readMeta(directory);
        JSONObject base = state == null ? null : state.optJSONObject("baseGame");
        if (!meta.has("game")) {
            meta.put("game", snapshot.runId).put("start_turn", Math.max(0, snapshot.turn()))
                .put("mid_entry", snapshot.turn() != 0).put("uma_id", base == null ? 0 : base.optInt("umaId"));
        }
        meta.put("schema_version", 2).put("snapshots",Math.max(meta.optInt("snapshots")+1,ArchiveSnapshotIndex.capturedCount(directory)))
            .put("last_snapshot_id", snapshot.snapshotId).put("options", options)
            .put("last_collector_instance_id",snapshot.collectorInstanceId).put("last_archive_seq",mapping.sequence)
            .put("collector_version", snapshot.json.optString("collector_version", "unknown"))
            .put("game_version", snapshot.json.optString("game_version", "unknown"))
            .put("updated_at_ms", System.currentTimeMillis());
        applyConfiguration(meta,options);
        JSONObject instances=meta.optJSONObject("collector_instances");if(instances==null)instances=new JSONObject();
        File[] legacyEnvelopes=new File(directory,"envelopes").listFiles(f->f.isFile()&&f.getName().matches("[0-9]+\\.json"));
        if(legacyEnvelopes!=null&&legacyEnvelopes.length>0&&!instances.has(SnapshotEnvelope.LEGACY_INSTANCE))
            instances.put(SnapshotEnvelope.LEGACY_INSTANCE,new JSONObject().put("schema_version",1).put("origin","existing_v1_envelopes"));
        JSONObject instance=instances.optJSONObject(snapshot.collectorInstanceId);
        if(instance==null)instance=new JSONObject().put("schema_version",snapshot.schemaVersion).put("first_snapshot_id",snapshot.snapshotId)
            .put("first_archive_seq",mapping.sequence).put("first_turn",snapshot.turn());
        instance.put("last_snapshot_id",snapshot.snapshotId).put("last_archive_seq",mapping.sequence);
        instances.put(snapshot.collectorInstanceId,instance);meta.put("collector_instances",instances);
        if(instances.length()>1)meta.put("luck_continuity","collector_segments");
        boolean finished = "settlement".equals(snapshot.stage) && snapshot.turn() == 77
                && snapshot.json.optBoolean("game_complete", false);
        if(finished)meta.put("end_reason","game_end").put("complete",true);
        else if(!meta.optBoolean("complete")&&activeRun==snapshot.runId)meta.put("end_reason","incomplete").put("complete",false);
        PrivateFiles.write(new File(directory, "meta.json"), meta.toString(2));
        return envelope;
    }
    public synchronized void event(EngineClient.Task task, JSONObject event) throws Exception {
        event(task,event,0);
    }
    public synchronized void event(EngineClient.Task task, JSONObject event, int sequence) throws Exception {
        File directory = run(task.runId);
        String key = task.requestId + ":" + (sequence>0?"seq:"+sequence:PrivateFiles.sha256(event.toString().getBytes(StandardCharsets.UTF_8)));
        if (!recordedEvents.add(key)) return;
        JSONObject entry = new JSONObject().put("request_id", task.requestId).put("run_id", task.runId)
                .put("snapshot_id", task.snapshotId).put("config_id", task.configId)
                .put("snapshot_schema_version",task.schemaVersion).put("collector_instance_id",task.collectorInstanceId)
                .put("event_seq",sequence).put("recorded_at_ms", System.currentTimeMillis()).put("event", event);
        append(new File(directory, "events.jsonl"), entry + "\n");
        String type = event.optString("type");
        if (!"decision".equals(type) && !"skipped".equals(type) && !"failed".equals(type) && !"cancelled".equals(type)) return;
        SnapshotEnvelope snapshot = SnapshotEnvelope.parse(PrivateFiles.read(task.input, PrivateFiles.MAX_SNAPSHOT_BYTES));
        if(snapshot.runId!=task.runId||snapshot.snapshotId!=task.snapshotId||snapshot.schemaVersion!=task.schemaVersion
            ||!snapshot.collectorInstanceId.equals(task.collectorInstanceId))throw new IOException("请求与存档快照身份不一致");
        ArchiveSnapshotIndex.Entry mapping=ArchiveSnapshotIndex.resolve(directory,snapshot);
        JSONObject decision = event.optJSONObject("decision");
        JSONObject meta = readMeta(directory);
        JSONObject history=meta.optJSONObject("config_history");
        JSONObject generation=history==null?null:history.optJSONObject(task.configId);
        JSONObject eventVersions=generation==null?null:generation.optJSONObject("versions");
        File csv = new File(directory, "decisions.csv");
        ensureCsvSchema(csv);
        append(csv, row(snapshot,mapping,task,decision,event,eventVersions) + "\n");
        meta.put("csv_rows", meta.optInt("csv_rows") + 1);
        if (decision != null) meta.put("decision_rows", meta.optInt("decision_rows") + 1);
        JSONObject extra=decision==null?null:decision.optJSONObject("scenario_extra");
        Double total=number(extra,"total_luck_score");
        if(total!=null) {
            meta.put("total_luck_end",total);
            JSONObject segments=meta.optJSONObject("luck_segments");if(segments==null)segments=new JSONObject();
            String start=extra==null?"unknown":extra.optString("luck_segment_start_snapshot_id","unknown");
            segments.put(task.collectorInstanceId+":"+start+":"+task.configId,new JSONObject().put("collector_instance_id",task.collectorInstanceId)
                .put("config_id",task.configId).put("start_snapshot_id",start).put("last_archive_seq",mapping.sequence).put("total_luck_end",total));
            meta.put("luck_segments",segments);
            if(extra!=null&&extra.has("history_complete"))meta.put("history_complete",extra.get("history_complete"));
            if(extra!=null&&extra.has("luck_scope"))meta.put("total_luck_scope",extra.get("luck_scope"));
        }
        PrivateFiles.write(new File(directory, "meta.json"), meta.toString(2));
    }
    public synchronized void result(EngineClient.Task task, JSONObject result) throws Exception {
        PrivateFiles.write(new File(run(task.runId), "results/" + task.requestId + ".json"), result.toString());
        if(result.has("engine_revision")) {
            JSONObject meta=readMeta(run(task.runId));meta.put("engine_revision",result.get("engine_revision"));
            PrivateFiles.write(new File(run(task.runId),"meta.json"),meta.toString(2));
        }
        JSONObject evaluation = result.optJSONObject("evaluation");
        if (evaluation == null) evaluation = result;
        JSONArray events = evaluation.optJSONArray("events");
        if (events != null) for (int i = 0; i < events.length(); i++) event(task, events.getJSONObject(i),i+1);
        if (result.has("ok") && !result.optBoolean("ok")) event(task, new JSONObject().put("type", "failed").put("error", result.optString("error")));
    }
    public synchronized void finish(String reason) throws Exception {
        if(activeRun==null)return;
        File directory=run(activeRun);JSONObject meta = readMeta(directory);
        if (!meta.optBoolean("complete")) { meta.put("end_reason", reason); PrivateFiles.write(new File(directory,"meta.json"),meta.toString(2)); }
    }
    public static String stateName(SnapshotEnvelope s) { return "game" + s.runId + "_turn" + Math.max(0,s.turn()) + "_" + s.snapshotId + ".json"; }
    private static JSONObject readMeta(File directory) throws Exception {
        File file = new File(directory, "meta.json"); return file.isFile() ? new JSONObject(PrivateFiles.read(file,1024*1024)) : new JSONObject();
    }
    private void applyConfiguration(JSONObject meta,JSONObject options) throws Exception {
        String id=options.optString("config_id","unversioned");
        JSONObject generation=requireConfiguration(options);
        JSONObject history=meta.optJSONObject("config_history");if(history==null)history=new JSONObject();
        JSONObject previous=history.optJSONObject(id);
        if(previous!=null&&!JsonValues.same(previous,generation))throw new IOException("局记录中同一 config_id 的配置不一致");
        history.put(id,new JSONObject(generation.toString()));
        JSONObject values=generation.getJSONObject("versions");
        meta.put("config_history",history).put("active_config_id",id).put("config",new JSONObject(options.toString()))
            .put("options",new JSONObject(options.toString()))
            .put("engine_revision",values.optString("engine_revision","unavailable"))
            .put("data_version",values.optString("data_version","unavailable"))
            .put("model_version",values.optString("model_version","unavailable"));
    }
    private JSONObject requireConfiguration(JSONObject options) throws IOException {
        if(options==null)throw new IOException("配置代尚未发布");
        String id=options.optString("config_id","unversioned");
        JSONObject generation=configurations.get(id);
        if(generation==null)throw new IOException("配置代尚未登记："+id);
        if(!JsonValues.same(generation.optJSONObject("options"),options))throw new IOException("配置代内容发生变化："+id);
        return generation;
    }
    private static void append(File file, String text) throws IOException {
        File directory = file.getParentFile(); if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("无法创建局目录");
        try (FileOutputStream out = new FileOutputStream(file,true)) { out.write(text.getBytes(StandardCharsets.UTF_8)); out.getFD().sync(); }
    }
    private static String header() {
        List<String> columns = new ArrayList<>(Arrays.asList("game","file","turn","seq","source","playing_state","stage","outcome","reason","step","chain_len","decision_kind","n_actions"));
        for (String suffix : new String[]{"desc","score","n"}) for (int n=1;n<=5;n++) columns.add("cand"+n+"_"+suffix);
        columns.addAll(Arrays.asList("chosen_idx","chosen_desc","chosen_action_luck","t_n_raw","t_n_display","total_luck","turn_delta"));
        columns.addAll(Arrays.asList("snapshot_schema_version","collector_instance_id","snapshot_id","request_id","config_id"));
        columns.addAll(Arrays.asList("luck_segment_start_snapshot_id","history_complete","luck_scope"));
        return String.join(",",columns);
    }
    private static String row(SnapshotEnvelope snapshot,ArchiveSnapshotIndex.Entry mapping,EngineClient.Task task,JSONObject decision,JSONObject event,JSONObject versions) throws Exception {
        JSONObject state = snapshot.json.optJSONObject("state"), base = state == null ? null : state.optJSONObject("baseGame");
        JSONArray names = decision == null ? null : decision.optJSONArray("candidate_descriptions");
        JSONArray scores = decision == null ? null : decision.optJSONArray("candidate_scores");
        JSONArray counts = decision == null ? null : decision.optJSONArray("candidate_n");
        int count = names == null ? 0 : names.length(); List<Integer> ranks = new ArrayList<>();
        for (int i=0;i<count;i++) ranks.add(i);
        if (scores != null && scores.length()==count) ranks.sort((a,b)->Double.compare(scores.optDouble(b),scores.optDouble(a)));
        List<String> row = new ArrayList<>(Arrays.asList(Long.toString(snapshot.runId),mapping.file,Integer.toString(snapshot.turn()),
            Long.toString(mapping.sequence),base==null?"":base.optString("source"),base==null?"":base.optString("playing_state"),snapshot.stage,
            decision==null?"skip":"calc",event.optString("reason",event.optString("error")),"0","1",decision==null?"":decision.optString("decision_kind"),Integer.toString(count)));
        for (int n=0;n<5;n++) row.add(n<count?names.optString(ranks.get(n)):"");
        for (int n=0;n<5;n++) row.add(n<count&&scores!=null&&ranks.get(n)<scores.length()&&!scores.isNull(ranks.get(n))?scores.optString(ranks.get(n)):"");
        for (int n=0;n<5;n++) row.add(n<count&&counts!=null&&ranks.get(n)<counts.length()?counts.optString(ranks.get(n)):"");
        int chosen = decision==null?-1:decision.optInt("action_index",-1);
        row.add(chosen<0?"":Integer.toString(chosen)); row.add(chosen>=0&&chosen<count?names.optString(chosen):"");
        JSONObject extra=decision==null?null:decision.optJSONObject("scenario_extra");
        JSONObject actionLuck=extra==null?null:extra.optJSONObject("action_luck");
        Double display=number(extra,"current_terminal_baseline");
        Double raw=number(extra,"current_terminal_baseline_raw");
        JSONObject view=event.optJSONObject("view");
        Double maxTurn=number(view,"max_turn"),viewTurn=number(view,"turn"),bonus=number(versions,"mcts_turn_bonus");
        if(raw==null&&display!=null&&maxTurn!=null&&viewTurn!=null&&bonus!=null)raw=display-(maxTurn-viewTurn)*bonus;
        row.add(numberColumn(chosen<0?null:number(actionLuck,Integer.toString(chosen))));
        row.add(numberColumn(raw));row.add(numberColumn(display));
        row.add(numberColumn(number(extra,"total_luck_score")));row.add(numberColumn(number(extra,"last_turn_delta")));
        row.addAll(Arrays.asList(Integer.toString(snapshot.schemaVersion),snapshot.collectorInstanceId,Long.toString(snapshot.snapshotId),task.requestId,task.configId));
        row.addAll(Arrays.asList(extra==null?"":extra.optString("luck_segment_start_snapshot_id",""),
            extra==null||!extra.has("history_complete")?"":extra.optString("history_complete"),extra==null?"":extra.optString("luck_scope","")));
        List<String> escaped = new ArrayList<>(); for (String value:row) escaped.add(csv(value)); return String.join(",",escaped);
    }
    private static Double number(JSONObject json,String name) {
        if(json==null)return null;Object value=json.opt(name);if(!(value instanceof Number))return null;
        double number=((Number)value).doubleValue();return Double.isFinite(number)?number:null;
    }
    private static String numberColumn(Double value) { return value==null?"":String.format(Locale.ROOT,"%.2f",value); }
    static String csv(String value) { return "\"" + value.replace("\"","\"\"") + "\""; }
    private static void ensureCsvSchema(File file)throws Exception {
        if(!file.isFile()){append(file,header()+"\n");return;}
        String current=PrivateFiles.read(file,64*1024*1024);List<List<String>> rows=ArchiveCsv.parse(current);
        List<String> expected=Arrays.asList(header().split(","));
        if(!rows.isEmpty()&&rows.get(0).equals(expected))return;
        int width=rows.isEmpty()?0:rows.get(0).size();
        if((width!=35&&width!=40)||!rows.get(0).equals(expected.subList(0,width)))throw new IOException("局包 CSV schema 不匹配，保留原文件");
        File backup=new File(file.getParentFile(),width==35?"decisions.legacy-v1.csv":"decisions.identity-v2.csv");
        if(!backup.exists())PrivateFiles.write(backup,current);
        StringBuilder upgraded=new StringBuilder(header()).append('\n');
        for(int i=1;i<rows.size();i++) {
            List<String> row=rows.get(i);if(row.size()!=width)throw new IOException("旧 CSV 列数异常，保留原文件");
            if(width==35)row.addAll(Arrays.asList("1",SnapshotEnvelope.LEGACY_INSTANCE,row.get(3),"",""));
            row.addAll(Arrays.asList("","",""));upgraded.append(ArchiveCsv.write(row)).append('\n');
        }
        PrivateFiles.write(file,upgraded.toString());
    }
    public static void export(File directory, OutputStream stream) throws IOException {
        try (ZipOutputStream zip = new ZipOutputStream(stream)) { zipTree(directory,directory,zip); }
    }
    private static void zipTree(File root, File current, ZipOutputStream zip) throws IOException {
        File[] files = current.listFiles(); if(files==null) throw new IOException("无法读取局记录");
        Arrays.sort(files,Comparator.comparing(File::getName));
        for(File file:files) {
            PrivateFiles.within(root,file.getPath());
            if(file.isDirectory()) zipTree(root,file,zip);
            else if(!file.getName().endsWith(".tmp")) {
                zip.putNextEntry(new ZipEntry(root.toPath().relativize(file.toPath()).toString().replace('\\','/')));
                try(InputStream in=new FileInputStream(file)) { byte[] bytes=new byte[8192];int n;while((n=in.read(bytes))!=-1)zip.write(bytes,0,n); }
                zip.closeEntry();
            }
        }
    }
}
