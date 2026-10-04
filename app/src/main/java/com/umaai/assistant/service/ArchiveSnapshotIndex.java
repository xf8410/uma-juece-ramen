package com.umaai.assistant.service;

import org.json.JSONObject;
import java.io.File;
import java.io.IOException;
import java.util.Iterator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Persistent archive sequence is distinct from the collector's process-local sequence. */
final class ArchiveSnapshotIndex {
    static final class Entry {
        final String file,envelope;final long sequence;
        Entry(JSONObject value)throws Exception{file=value.getString("file");envelope=value.getString("envelope");sequence=value.getLong("archive_seq");}
    }
    static Entry resolve(File directory,SnapshotEnvelope snapshot)throws Exception {
        File indexFile=new File(directory,"snapshot-index.json");
        JSONObject index=indexFile.isFile()?new JSONObject(PrivateFiles.read(indexFile,16*1024*1024)):new JSONObject();
        JSONObject entries=index.optJSONObject("entries");if(entries==null)entries=new JSONObject();
        JSONObject existing=entries.optJSONObject(snapshot.identityKey());if(existing!=null)return new Entry(existing);
        String relative=snapshot.schemaVersion==1?"envelopes/"+snapshot.snapshotId+".json":
            "envelopes/"+snapshot.collectorInstanceId+"/"+snapshot.snapshotId+".json";
        long maximum=index.optLong("last_archive_seq",-1);
        Pattern pattern=Pattern.compile("game"+snapshot.runId+"_turn[0-9]+(?:_([0-9]+))?\\.json");
        File[] files=directory.listFiles();
        if(files!=null)for(File file:files){Matcher match=pattern.matcher(file.getName());if(match.matches())maximum=Math.max(maximum,match.group(1)==null?0:Long.parseLong(match.group(1)));}
        long seq;
        String legacyName=RunArchive.stateName(snapshot);
        if(snapshot.schemaVersion==1&&new File(directory,relative).isFile()&&new File(directory,legacyName).isFile())seq=snapshot.snapshotId;
        else if(snapshot.schemaVersion==1&&snapshot.snapshotId>maximum)seq=snapshot.snapshotId;
        else seq=maximum+1;
        if(seq>0xffff_ffffL)throw new IOException("archive_seq 超出复盘格式范围");
        String name="game"+snapshot.runId+"_turn"+Math.max(0,snapshot.turn())+"_"+seq+".json";
        JSONObject value=new JSONObject().put("file",name).put("envelope",relative).put("archive_seq",seq)
            .put("schema_version",snapshot.schemaVersion).put("collector_instance_id",snapshot.collectorInstanceId)
            .put("snapshot_id",snapshot.snapshotId).put("turn",snapshot.turn());
        entries.put(snapshot.identityKey(),value);
        index.put("schema_version",2).put("last_archive_seq",Math.max(maximum,seq)).put("entries",entries);
        PrivateFiles.write(indexFile,index.toString(2));return new Entry(value);
    }
    static int capturedCount(File directory)throws Exception {
        File file=new File(directory,"snapshot-index.json");if(!file.isFile())return 0;
        JSONObject entries=new JSONObject(PrivateFiles.read(file,16*1024*1024)).getJSONObject("entries");
        int count=0;Iterator<String> keys=entries.keys();
        while(keys.hasNext()){String path=entries.getJSONObject(keys.next()).getString("envelope");if(PrivateFiles.within(directory,new File(directory,path).getPath()).isFile())count++;}
        return count;
    }
}
