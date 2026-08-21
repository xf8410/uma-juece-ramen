package com.umaai.assistant.service;

import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.assertTrue;

public class TrainingEvaluatorOutingTest {
    private final TrainingEvaluator evaluator=new TrainingEvaluator();
    @Test public void badMotivationRecommendsOuting() throws Exception {
        JSONObject s=new JSONObject("{\"stats\":{\"vital\":70,\"max_vital\":100,\"motivation\":\"Bad\"},\"trainings\":[{\"name\":\"Speed\",\"failure_rate\":0,\"heads\":5,\"gains\":{\"Speed\":40,\"HP\":-20}}]}");
        assertTrue(evaluator.recommend(s).contains("外出"));
    }
    @Test public void bestMotivationDoesNotRecommendOuting() throws Exception {
        JSONObject s=new JSONObject("{\"stats\":{\"vital\":70,\"max_vital\":100,\"motivation\":\"Best\"},\"trainings\":[{\"name\":\"Speed\",\"failure_rate\":0,\"heads\":1,\"gains\":{\"Speed\":20,\"HP\":-20}}]}");
        assertTrue(!evaluator.recommend(s).contains("外出"));
    }
}
