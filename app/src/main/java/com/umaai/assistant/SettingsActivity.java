package com.umaai.assistant;

import android.app.*;
import android.content.*;
import android.os.Bundle;
import android.text.InputType;
import android.widget.*;
import com.umaai.assistant.service.*;
import org.json.JSONObject;
import java.io.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class SettingsActivity extends Activity {
    private static final int IMPORT_MODEL=401;
    private final ExecutorService io=Executors.newSingleThreadExecutor();
    private TextView status;
    private EditText budget,threads,seed;
    private Spinner policy;
    private String modelPath="";
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);setTitle("策略、数据与模型");
        ScrollView scroll=new ScrollView(this);LinearLayout column=new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);column.setPadding(28,24,28,24);scroll.addView(column);setContentView(scroll);
        SharedPreferences p=getSharedPreferences(EngineSettings.PREFS,MODE_PRIVATE);
        text(column,"上游对齐配置",22);text(column,"默认搜索 8192 次。调整配置后，引擎会重启并重新计算当前盘面。",15);
        policy=new Spinner(this);policy.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,new String[]{"MCTS 搜索","MCTS + NN 参考","纯 NN"}));column.addView(policy);
        String saved=p.getString("policy","mcts");policy.setSelection("nn".equals(saved)?2:"mcts_nn_hint".equals(saved)?1:0);
        budget=number(column,"搜索次数",p.getInt("search_n",8192));threads=number(column,"并行线程",p.getInt("threads",4));seed=number(column,"随机种子",p.getLong("seed",61444));
        modelPath=p.getString("model_path","");
        status=text(column,modelPath.isEmpty()?"未安装 NN 模型。MCTS 可独立使用。":"已选择模型："+new File(modelPath).getName(),15);
        button(column,"保存并重算",()->save());
        button(column,"校验当前数据与版本",()->io.execute(()-> {
            try {
                File directory=VersionedAssets.install(this);JSONObject manifest=new JSONObject(PrivateFiles.read(new File(directory,"manifest.json"),1024*1024));
                show("数据校验通过\n引擎 "+manifest.getString("engine_revision")+"\n配置 "+manifest.getString("config_version"));
            }catch(Exception e){show("数据校验失败："+e.getMessage());}
        }));
        button(column,"导入本地 NN 模型包",()-> {
            Intent intent=new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("application/zip").addCategory(Intent.CATEGORY_OPENABLE);startActivityForResult(intent,IMPORT_MODEL);
        });
        text(column,"模型包需要 manifest.json、ONNX 文件及对应上游旁车配置。文件校验后，原生引擎还会验证模型输入输出和适用卡组。",14);
    }
    private void save() {
        try {
            int n=Integer.parseInt(budget.getText().toString()), t=Integer.parseInt(threads.getText().toString());long s=Long.parseLong(seed.getText().toString());
            if(n<1||n>1048576||t<1||t>32||s<0)throw new IllegalArgumentException("搜索次数需为 1–1048576，线程 1–32，种子非负");
            String selected=new String[]{"mcts","mcts_nn_hint","nn"}[policy.getSelectedItemPosition()];
            if(!selected.equals("mcts")&&modelPath.isEmpty())throw new IllegalArgumentException("请先导入匹配的 NN 模型包");
            io.execute(()-> {
                try {
                    if(!selected.equals("mcts")) {
                        File model=PrivateFiles.within(new File(getFilesDir(),"models"),modelPath);
                        ModelStore.validate(model.getParentFile(),engineRevision());
                    }
                    getSharedPreferences(EngineSettings.PREFS,MODE_PRIVATE).edit().putString("policy",selected).putInt("search_n",n)
                        .putInt("threads",t).putLong("seed",s).putString("model_path",modelPath).commit();
                    runOnUiThread(()-> {
                        if(getSharedPreferences("runtime_status",MODE_PRIVATE).getBoolean("running",false)
                            &&android.provider.Settings.canDrawOverlays(this))
                            startForegroundService(new Intent(this,FloatingWindowService.class).setAction(FloatingWindowService.ACTION_RECONFIGURE));
                        status.setText("配置已保存；使用该配置的计算将重新开始");
                    });
                }catch(Exception e){show("无法保存配置："+e.getMessage());}
            });
        }catch(Exception error){status.setText(error.getMessage());}
    }
    private String engineRevision() throws Exception {
        try(InputStream in=getAssets().open("gamedata/manifest.json")) { return new JSONObject(PrivateFiles.read(in,1024*1024)).getString("engine_revision"); }
    }
    @Override protected void onActivityResult(int request,int result,Intent data) {
        super.onActivityResult(request,result,data);
        if(request!=IMPORT_MODEL||result!=RESULT_OK||data==null||data.getData()==null)return;
        status.setText("正在导入并校验模型…");io.execute(()-> {
            try(InputStream in=getContentResolver().openInputStream(data.getData())) {
                if(in==null)throw new IOException("无法打开模型包");
                File model=ModelStore.install(in,new File(getFilesDir(),"models"),engineRevision());modelPath=model.getAbsolutePath();
                show("模型文件校验通过。选择策略并保存后，由引擎校验模型图。");
            }catch(Exception e){show("模型导入失败："+e.getMessage());}
        });
    }
    private TextView text(LinearLayout column,String value,int size) { TextView view=new TextView(this);view.setText(value);view.setTextSize(size);view.setPadding(0,14,0,14);column.addView(view);return view; }
    private EditText number(LinearLayout column,String label,long value) {text(column,label,15);EditText input=new EditText(this);input.setInputType(InputType.TYPE_CLASS_NUMBER);input.setText(Long.toString(value));input.setSingleLine(true);column.addView(input);return input;}
    private void button(LinearLayout column,String title,Runnable action){Button button=new Button(this);button.setText(title);button.setOnClickListener(v->action.run());column.addView(button);}
    private void show(String value){runOnUiThread(()->{if(!isDestroyed())status.setText(value);});}
    @Override protected void onDestroy(){io.shutdown();super.onDestroy();}
}
