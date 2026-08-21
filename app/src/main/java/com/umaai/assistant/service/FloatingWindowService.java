package com.umaai.assistant.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;
import androidx.core.app.NotificationCompat;
import com.umaai.assistant.R;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

/** Horizontal, click-through Ramen overlay. Stop/move it from the main app. */
public final class FloatingWindowService extends Service implements HttpDataService.OnDataListener {
    private static final String CHANNEL="ramen_overlay";
    private static final int NOTIFICATION_ID=1401;
    private static final long STALE_MS=5000;
    private static final int DEFAULT_UMA_ID=102601;
    private static final int[] DEFAULT_CARDS={302424,302894,303044,302924,303024,303054};
    private static final int SEARCH_TRIALS=32;

    private final Handler main=new Handler(Looper.getMainLooper());
    private final TrainingEvaluator evaluator=new TrainingEvaluator();
    private WindowManager windowManager; private View panel;
    private TextView turnView,recommendView,statusView,ramenView,trainingsView,sourceView;
    private HttpDataService server; private volatile boolean polling,searchRunning;
    private volatile long lastDataAt; private volatile String lastSearchKey="";
    private volatile JSONObject lastSearchResult,pendingSummary; private Thread searchThread;

    @Override public void onCreate(){super.onCreate();createNotificationChannel();startForeground(NOTIFICATION_ID,notification("等待拉面杯数据"));createPanel();try{server=new HttpDataService(this);server.startServer();}catch(Exception e){stopSelf();return;}startPolling();initNativeSearch();}
    @Override public IBinder onBind(Intent i){return null;}
    @Override public int onStartCommand(Intent i,int f,int id){return START_STICKY;}
    @Override public void onDestroy(){polling=false;if(server!=null)server.stopServer();if(panel!=null&&windowManager!=null)windowManager.removeView(panel);super.onDestroy();}
    @Override public void onDataReceived(String data){consume(data,"实时");}

    private void consume(String data,String source){if(data==null||data.isEmpty())return;try{JSONObject s=new JSONObject(data);if(!s.has("stats"))return;lastDataAt=System.currentTimeMillis();main.post(()->render(s,s.optString("scenario",""),source));}catch(Exception ignored){}}

    private void render(JSONObject s,String scenario,String source){
        if(!"Ramen".equals(scenario)){turnView.setText("非拉面杯");recommendView.setText("此版本仅支持拉面杯");statusView.setText("");ramenView.setText("");trainingsView.setText("");return;}
        JSONObject st=s.optJSONObject("stats");if(st==null)return;int turn=s.optInt("turn",-1);
        String fallback=evaluator.recommend(s);
        turnView.setText((turn>0?"第"+turn+"回合":"拉面杯")+" · "+RamenDecisionSupport.recommend(s));
        recommendView.setText(ActionRecommendation.display(s,fallback,lastSearchResult,searchRunning));
        int total=st.optInt("speed")+st.optInt("stamina")+st.optInt("power")+st.optInt("guts")+st.optInt("wiz");
        statusView.setText("速"+st.optInt("speed")+" 耐"+st.optInt("stamina")+" 力"+st.optInt("power")+" 根"+st.optInt("guts")+" 智"+st.optInt("wiz")+"  总"+total+" Pt"+st.optInt("skill_point")+"\n体力"+st.optInt("vital")+"/"+st.optInt("max_vital")+" 干劲"+st.optString("motivation","?"));
        ramenView.setText(RamenDecisionSupport.stateLine(s));trainingsView.setText(renderTrainings(s.optJSONArray("trainings")));
        sourceView.setText(source+(UmaNativeBridge.isAvailable()?" · AI就绪":" · 安全兜底"));
        NotificationManager m=getSystemService(NotificationManager.class);if(m!=null)m.notify(NOTIFICATION_ID,notification("拉面杯 第"+turn+"回合"));
        String key=searchKey(s);if(!key.equals(lastSearchKey)&&!searchRunning&&UmaNativeBridge.isAvailable())triggerSearch(s,key);
    }

    /** Turn alone is insufficient: eating ramen or opening outings can change legal actions in-place. */
    static String searchKey(JSONObject s){JSONObject st=s.optJSONObject("stats"),r=s.optJSONObject("ramen");return s.optInt("turn")+":"+(st==null?"":st.optInt("vital")+":"+st.optString("motivation"))+":"+(r==null?"":r.optInt("checkpoint_pt")+":"+r.optString("sozai")+":"+r.optInt("special_feeling_num"));}
    private void initNativeSearch(){new Thread(()->{if(UmaNativeBridge.init(this)){main.post(()->{if(sourceView!=null)sourceView.setText("AI就绪");});}},"NativeInit").start();}
    private void triggerSearch(JSONObject s,String key){searchRunning=true;lastSearchKey=key;pendingSummary=s;recommendView.setText(ActionRecommendation.display(s,evaluator.recommend(s),lastSearchResult,true));if(searchThread!=null&&searchThread.isAlive())return;searchThread=new Thread(()->{JSONObject snap=pendingSummary;JSONObject result=snap==null?null:UmaNativeBridge.search(snap,DEFAULT_UMA_ID,DEFAULT_CARDS,SEARCH_TRIALS);lastSearchResult=result;searchRunning=false;main.post(()->{if(recommendView!=null&&snap!=null)recommendView.setText(ActionRecommendation.display(snap,evaluator.recommend(snap),result,false));});},"NativeSearch");searchThread.setDaemon(true);searchThread.start();}

    private String renderTrainings(JSONArray a){if(a==null)return "训练数据：无";StringBuilder o=new StringBuilder();for(int i=0;i<a.length();i++){JSONObject t=a.optJSONObject(i);if(t==null)continue;JSONObject g=t.optJSONObject("gains");if(o.length()>0)o.append("  |  ");o.append(translate(t.optString("name","?"))).append(':');if(g!=null){gain(o,"速",g.optInt("Speed"));gain(o,"耐",g.optInt("Stamina"));gain(o,"力",g.optInt("Power"));gain(o,"根",g.optInt("Guts"));gain(o,"智",g.optInt("Wiz"));gain(o,"Pt",g.optInt("SkillPt"));int hp=g.optInt("HP");if(hp!=0)o.append(" HP").append(hp>0?"+":"").append(hp);}int f=t.optInt("failure_rate");if(f>0)o.append(" 失败").append(f).append('%');int h=t.optInt("heads");if(h>0)o.append(" 头").append(h);}return o.toString();}
    private static void gain(StringBuilder o,String n,int v){if(v!=0)o.append(' ').append(n).append(v>0?"+":"").append(v);}
    private static String translate(String n){switch(n.toLowerCase()){case"speed":return"速";case"stamina":return"耐";case"power":return"力";case"guts":return"根";case"wisdom":case"wiz":return"智";case"rest":return"休息";case"outgoing":case"outing":return"外出";default:return n;}}

    private void createPanel(){windowManager=(WindowManager)getSystemService(WINDOW_SERVICE);panel=LayoutInflater.from(this).inflate(R.layout.floating_window,null);int type=Build.VERSION.SDK_INT>=26?WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY:WindowManager.LayoutParams.TYPE_PHONE;int flags=WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE|WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN;WindowManager.LayoutParams p=new WindowManager.LayoutParams(WindowManager.LayoutParams.MATCH_PARENT,WindowManager.LayoutParams.WRAP_CONTENT,type,flags,PixelFormat.TRANSLUCENT);p.gravity=Gravity.TOP|Gravity.START;p.x=0;p.y=120;turnView=panel.findViewById(R.id.tv_turn);recommendView=panel.findViewById(R.id.tv_recommend);statusView=panel.findViewById(R.id.tv_status);ramenView=panel.findViewById(R.id.tv_ramen);trainingsView=panel.findViewById(R.id.tv_trainings);sourceView=panel.findViewById(R.id.tv_source);windowManager.addView(panel,p);}
    private void startPolling(){polling=true;Thread t=new Thread(()->{while(polling){if(System.currentTimeMillis()-lastDataAt>STALE_MS){String b=get("http://127.0.0.1:18765/summary");if(b!=null)consume(b,"轮询");}try{Thread.sleep(2000);}catch(InterruptedException e){return;}}},"RamenPoll");t.setDaemon(true);t.start();}
    private static String get(String a){HttpURLConnection c=null;try{c=(HttpURLConnection)new URL(a).openConnection();c.setConnectTimeout(1500);c.setReadTimeout(2000);if(c.getResponseCode()!=200)return null;BufferedReader r=new BufferedReader(new InputStreamReader(c.getInputStream(),"UTF-8"));StringBuilder b=new StringBuilder();String l;while((l=r.readLine())!=null)b.append(l);r.close();return b.toString();}catch(Exception e){return null;}finally{if(c!=null)c.disconnect();}}
    private void createNotificationChannel(){if(Build.VERSION.SDK_INT>=26){NotificationManager m=getSystemService(NotificationManager.class);if(m!=null)m.createNotificationChannel(new NotificationChannel(CHANNEL,"拉面杯浮窗",NotificationManager.IMPORTANCE_LOW));}}
    private Notification notification(String t){return new NotificationCompat.Builder(this,CHANNEL).setContentTitle("拉面杯决策浮窗").setContentText(t).setSmallIcon(android.R.drawable.ic_menu_info_details).setOngoing(true).build();}
}
