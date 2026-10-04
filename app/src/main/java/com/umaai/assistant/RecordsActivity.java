package com.umaai.assistant;

import android.app.*;
import android.content.*;
import android.net.Uri;
import android.os.*;
import android.webkit.*;
import android.widget.*;
import com.umaai.assistant.service.*;
import org.json.JSONObject;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;

/** Review is performed only after the live service releases its engine process. */
public final class RecordsActivity extends Activity implements EngineClient.Listener {
    private static final int EXPORT=501;
    private final ExecutorService io=Executors.newSingleThreadExecutor();
    private final Handler main=new Handler(Looper.getMainLooper());
    private LinearLayout content;
    private TextView status;
    private File selected;
    private EngineClient engine;
    private WebView report;
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);setTitle("对局记录与本地复盘");
        ScrollView scroll=new ScrollView(this);content=new LinearLayout(this);content.setOrientation(LinearLayout.VERTICAL);content.setPadding(24,20,24,24);scroll.addView(content);setContentView(scroll);
        status=text("局包包含采集快照、候选建议与错误记录。未观测到完整结算的局会保留为未完成。",15);
        io.execute(()-> {
            File root=new File(getFilesDir(),"runs");File[] runs=root.listFiles(File::isDirectory);
            for(String name:new String[]{"decision_log.jsonl","decision_log.jsonl.1"}) {
                File legacy=new File(getFilesDir(),name);
                if(legacy.isFile())runOnUiThread(()->button(name+" · 旧版记录缺少完整快照",()->{selected=legacy;export();}));
            }
            if(runs==null||runs.length==0){show("尚无对局记录；连接采集端后开始记录。");return;}
            Arrays.sort(runs,(a,b)->Long.compare(b.lastModified(),a.lastModified()));
            for(File run:runs) {
                try {
                    JSONObject meta=new JSONObject(PrivateFiles.read(new File(run,"meta.json"),1024*1024));
                    JSONObject instances=meta.optJSONObject("collector_instances");
                    String label=run.getName()+" · "+(meta.optBoolean("complete")?"完整":"未完成")+"\n"+meta.optInt("snapshots")+" 份快照 / "+meta.optInt("decision_rows")+" 条决策"
                        +(instances!=null&&instances.length()>1?"\n含 "+instances.length()+" 个采集进程，运气按恢复段记录":"");
                    runOnUiThread(()->button(label,()->select(run)));
                }catch(Exception error){runOnUiThread(()->button(run.getName()+" · 元信息损坏",()->select(run)));}
            }
        });
    }
    private void select(File run) {
        selected=run;
        new AlertDialog.Builder(this).setTitle(run.getName()).setItems(new String[]{"查看回合时间线","导出完整局包 ZIP","暂停实时计算并本地复盘"},(dialog,choice)-> {
            if(choice==0)timeline(run);else if(choice==1)export();else review(run);
        }).show();
    }
    private void timeline(File run) {
        status.setText("正在读取时间线…");io.execute(()-> {
            try {
                File file=new File(run,"events.jsonl");
                if(!file.isFile()){show("该局暂无决策事件；原始采集快照已保存在局包。");return;}
                StringBuilder text=new StringBuilder();
                try(BufferedReader reader=new BufferedReader(new InputStreamReader(new FileInputStream(file),java.nio.charset.StandardCharsets.UTF_8))) {
                    String line;int rows=0;
                    while((line=reader.readLine())!=null) {
                        JSONObject entry=new JSONObject(line),event=entry.getJSONObject("event");
                        String type=event.optString("type");
                        if(type.equals("started")||type.equals("completed"))continue;
                        if(++rows>500){text.append("\n更多事件请查看导出的局包。");break;}
                        text.append("采集 ").append(entry.optString("collector_instance_id",SnapshotEnvelope.LEGACY_INSTANCE))
                            .append(" / 快照 ").append(entry.optLong("snapshot_id")).append(" · ").append(type).append('\n');
                        JSONObject decision=event.optJSONObject("decision");
                        if(decision!=null) {
                            org.json.JSONArray candidates=decision.optJSONArray("candidate_descriptions");int selected=decision.optInt("action_index",-1);
                            text.append(candidates!=null&&selected>=0?candidates.optString(selected):"动作缺失");
                        }else text.append(event.optString("reason",event.optString("error")));
                        text.append("\n\n");
                    }
                }
                String body=text.length()==0?"当前仅有采集记录。":text.toString();
                runOnUiThread(()-> {
                    if(isDestroyed())return;
                    TextView view=new TextView(this);view.setPadding(24,16,24,16);view.setText(body);view.setTextIsSelectable(true);
                    ScrollView scroll=new ScrollView(this);scroll.addView(view);
                    new AlertDialog.Builder(this).setTitle(run.getName()+" 时间线").setView(scroll).setPositiveButton("关闭",null).show();
                });
            }catch(Exception error){show("时间线读取失败："+error.getMessage());}
        });
    }
    private void export() {
        if(selected==null)return;
        Intent intent=new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
            .setType(selected.isDirectory()?"application/zip":"application/x-ndjson")
            .putExtra(Intent.EXTRA_TITLE,selected.getName()+(selected.isDirectory()?".zip":""));
        startActivityForResult(intent,EXPORT);
    }
    @Override protected void onActivityResult(int request,int result,Intent data) {
        super.onActivityResult(request,result,data);
        if(request!=EXPORT||result!=RESULT_OK||data==null||data.getData()==null||selected==null)return;
        File source=selected;Uri target=data.getData();status.setText("正在导出…");
        io.execute(()-> {
            try(OutputStream out=getContentResolver().openOutputStream(target,"w")) {
                if(out==null)throw new IOException("无法创建导出文件");
                if(source.isDirectory())RunArchive.export(source,out);
                else try(InputStream in=new FileInputStream(source)) {byte[] bytes=new byte[8192];int n;while((n=in.read(bytes))!=-1)out.write(bytes,0,n);}
                show("已导出所选记录");
            }catch(Exception error){show("导出失败："+error.getMessage());}
        });
    }
    private void review(File run) {
        stopService(new Intent(this,FloatingWindowService.class));
        getSharedPreferences("runtime_status",MODE_PRIVATE).edit().putString("status","已停止").putBoolean("paused",true).apply();
        if(engine!=null)engine.close();
        status.setText("实时计算已停止，正在准备本地复盘…");
        main.postDelayed(()-> {
            if(isDestroyed())return;
            try {
                JSONObject options=EngineSettings.read(this);
                // The review runtime does not require a neural model.
                options.put("policy","mcts");options.remove("model_path");
                options.put("config_id","review-"+System.nanoTime());
                engine=new EngineClient(this,options,this);engine.start();
                long runId=Long.parseLong(run.getName().substring(4));
                engine.submit(new EngineClient.Task(runId,0,engine.configId(),run,true));
            }catch(Exception error){show("无法开始复盘："+error.getMessage());}
        },500);
    }
    @Override public void onStatus(String value){show(value);}
    @Override public void onEvent(EngineClient.Task task,JSONObject event){}
    @Override public void onFailure(EngineClient.Task task,String error){show("复盘失败："+error);}
    @Override public void onResult(EngineClient.Task task,JSONObject result) {
        if(!result.optBoolean("ok")){show("复盘失败："+result.optString("error"));return;}
        io.execute(()-> {
            try {
                File html=PrivateFiles.within(task.input,result.getString("report_path"));
                String body=PrivateFiles.read(html,16*1024*1024);
                runOnUiThread(()-> {
                    if(isDestroyed())return;
                    if(report!=null)report.destroy();report=new WebView(this);
                    report.getSettings().setJavaScriptEnabled(false);report.getSettings().setAllowFileAccess(false);
                    report.getSettings().setAllowContentAccess(false);report.getSettings().setBlockNetworkLoads(true);
                    report.setLayoutParams(new android.view.ViewGroup.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                        Math.round(getResources().getDisplayMetrics().heightPixels*0.75f)));
                    report.setWebViewClient(new WebViewClient(){@Override public boolean shouldOverrideUrlLoading(WebView view,WebResourceRequest request){return true;}});
                    report.loadDataWithBaseURL(null,body,"text/html","UTF-8",null);
                    new AlertDialog.Builder(this).setTitle("本地复盘").setView(report).setPositiveButton("关闭",(d,w)->{report.destroy();report=null;}).show();
                    status.setText("本地复盘已生成，可随局包导出");
                });
            }catch(Exception error){show("无法打开复盘报告："+error.getMessage());}
        });
    }
    private TextView text(String value,int size){TextView view=new TextView(this);view.setText(value);view.setTextSize(size);view.setPadding(0,12,0,12);content.addView(view);return view;}
    private void button(String value,Runnable action){if(isDestroyed())return;Button button=new Button(this);button.setText(value);button.setOnClickListener(v->action.run());content.addView(button);}
    private void show(String value){runOnUiThread(()->{if(!isDestroyed())status.setText(value);});}
    @Override protected void onDestroy(){main.removeCallbacksAndMessages(null);if(engine!=null)engine.close();if(report!=null)report.destroy();io.shutdown();super.onDestroy();}
}
