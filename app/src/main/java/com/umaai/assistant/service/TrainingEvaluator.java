package com.umaai.assistant.service;

import org.json.JSONArray;
import org.json.JSONObject;

/** Conservative fallback that compares training, rest and outing. */
public final class TrainingEvaluator {
    private static final String[] NAMES={"Speed","Stamina","Power","Guts","Wiz"};
    private static final String[] LABELS={"速","耐","力","根","智"};
    private static final double HEADS_WEIGHT=25.0,SHINING_WEIGHT=30.0,LOW_VITAL_RATIO=0.40;
    private static final int LOW_VITAL_FAILURE_GUARD=15;

    public String recommend(JSONObject summary){
        JSONObject stats=stats(summary);
        int vital=stats!=null?stats.optInt("vital",-1):-1;
        int max=stats!=null?stats.optInt("max_vital",-1):-1;
        int motivation=motivation(stats);
        JSONArray trainings=trainings(summary);
        if(trainings==null||trainings.length()==0)return "等待训练数据";
        JSONObject best=null;int heads=0,shining=0,failure=0;double bestScore=Double.NEGATIVE_INFINITY;
        for(int i=0;i<trainings.length();i++){JSONObject t=trainings.optJSONObject(i);if(t==null||t.optInt("is_enable",1)==0)continue;JSONObject g=t.optJSONObject("gains");if(g==null)continue;int total=g.optInt("Speed")+g.optInt("Stamina")+g.optInt("Power")+g.optInt("Guts")+g.optInt("Wiz")+g.optInt("SkillPt")/2;int hp=g.optInt("HP");int f=Math.max(0,t.optInt("failure_rate"));int h=Math.max(0,t.optInt("heads"));int sh=Math.max(0,t.optInt("shining"));double success=Math.max(0,1-f/100.0);double value=total+hp*.8+h*HEADS_WEIGHT+sh*SHINING_WEIGHT;double loss=f>=20?500:150;double score=value*success-loss*(1-success);if(score>bestScore){bestScore=score;best=t;heads=h;shining=sh;failure=f;}}
        if(best==null)return "等待可用训练";
        boolean lowVital=vital>=0&&max>0&&vital<=Math.ceil(max*LOW_VITAL_RATIO);
        if(motivation>0&&motivation<=2)return "行动兜底：外出（干劲较低，先恢复状态）";
        if(lowVital&&failure>=LOW_VITAL_FAILURE_GUARD)return "行动兜底：休息（低体力"+vital+"/"+max+"，最佳训练仍失败"+failure+"%）";
        String name=best.optString("name","");String label=name;for(int i=0;i<NAMES.length;i++)if(NAMES[i].equalsIgnoreCase(name))label=LABELS[i];String partners=heads>0?"×"+heads+(shining>0?"光"+shining:""):"";return "训练兜底："+label+partners+"（期望收益评分）";
    }

    /** Live hlpatch uses chara; older payloads use stats. */
    static JSONObject stats(JSONObject summary){
        JSONObject value=summary.optJSONObject("stats");
        return value!=null?value:summary.optJSONObject("chara");
    }

    /** Accept trainings at the top level and in nested chara/stats payloads. */
    static JSONArray trainings(JSONObject summary){
        JSONArray value=summary.optJSONArray("trainings");
        if(value!=null)return value;
        JSONObject chara=summary.optJSONObject("chara");
        if(chara!=null){value=chara.optJSONArray("trainings");if(value!=null)return value;}
        JSONObject stats=summary.optJSONObject("stats");
        return stats==null?null:stats.optJSONArray("trainings");
    }

    private static int motivation(JSONObject stats){if(stats==null)return 0;Object raw=stats.opt("motivation");if(raw instanceof Number)return ((Number)raw).intValue();String s=String.valueOf(raw).toLowerCase();if(s.contains("worst")||s.contains("绝不")||s.contains("絶不"))return 1;if(s.equals("bad")||s.contains("不调")||s.contains("不調"))return 2;if(s.equals("normal")||s.contains("普通"))return 3;if(s.equals("good")||s.contains("好调")||s.contains("好調"))return 4;if(s.equals("best")||s.contains("绝好")||s.contains("絶好"))return 5;return 0;}
}
