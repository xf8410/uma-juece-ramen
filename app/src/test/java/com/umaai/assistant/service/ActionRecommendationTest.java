package com.umaai.assistant.service;

import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.assertTrue;

public class ActionRecommendationTest {
    @Test public void safetyRestOverridesRiskyNativeTraining() throws Exception {
        JSONObject search=new JSONObject("{\"ok\":true,\"action_display\":\"Speed训练\",\"search_n\":32}");
        String text=ActionRecommendation.display(new JSONObject(),"行动兜底：休息（低体力）",search,false);
        assertTrue(text,text.contains("休息"));
        assertTrue(text,!text.contains("速度训练"));
    }
    @Test public void outingIsVisibleAsMainRecommendation() throws Exception {
        JSONObject search=new JSONObject("{\"ok\":true,\"action_display\":\"普通出行\",\"search_n\":32}");
        String text=ActionRecommendation.display(new JSONObject(),"训练兜底：智",search,false);
        assertTrue(text,text.contains("模拟建议：外出"));
        assertTrue(text,text.contains("每个选项试算32局"));
        assertTrue(text,!text.contains("N="));
    }
    @Test public void liveOutingSafetyOverridesApproximateTrainingSearch() throws Exception {
        JSONObject search=new JSONObject("{\"ok\":true,\"action_display\":\"Speed训练\",\"search_n\":32}");
        String text=ActionRecommendation.display(new JSONObject(),"行动兜底：外出（干劲较低）",search,false);
        assertTrue(text,text.contains("建议：外出"));
        assertTrue(text,!text.contains("速度训练"));
    }
    @Test public void searchKeyChangesWithinSameTurnWhenVitalsChange() throws Exception {
        JSONObject a=new JSONObject("{\"turn\":6,\"stats\":{\"vital\":34,\"motivation\":\"Best\"},\"ramen\":{\"checkpoint_pt\":630,\"sozai\":[1,2,5]}}");
        JSONObject b=new JSONObject("{\"turn\":6,\"stats\":{\"vital\":84,\"motivation\":\"Best\"},\"ramen\":{\"checkpoint_pt\":630,\"sozai\":[1,2,5]}}");
        assertTrue(!FloatingWindowService.searchKey(a).equals(FloatingWindowService.searchKey(b)));
    }
    @Test public void searchKeyChangesWhenDirectTurnAdvances() throws Exception {
        // hlpatch 直读 turn：同一 month/half 内推进回合必须重新搜索
        JSONObject a=new JSONObject("{\"turn\":31,\"month\":4,\"half\":1,\"chara\":{\"vital\":60,\"motivation\":\"Best\"}}");
        JSONObject b=new JSONObject("{\"turn\":32,\"month\":4,\"half\":1,\"chara\":{\"vital\":60,\"motivation\":\"Best\"}}");
        assertTrue(!FloatingWindowService.searchKey(a).equals(FloatingWindowService.searchKey(b)));
    }
}
