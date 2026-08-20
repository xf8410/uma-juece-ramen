package com.umaai.assistant.service;

import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.assertTrue;

public class TrainingEvaluatorTest {
    private final TrainingEvaluator evaluator = new TrainingEvaluator();

    @Test public void headCountTiltsStaminaOverHigherRawWiz() throws Exception {
        JSONObject summary = new JSONObject(
                "{\"trainings\":[" +
                "{\"name\":\"Stamina\",\"is_enable\":1,\"failure_rate\":10,\"heads\":5,\"shining\":0," +
                "\"gains\":{\"Speed\":0,\"Stamina\":14,\"Power\":6,\"Guts\":0,\"Wiz\":0,\"SkillPt\":12,\"HP\":-8}}," +
                "{\"name\":\"Wiz\",\"is_enable\":1,\"failure_rate\":10,\"heads\":0,\"shining\":0," +
                "\"gains\":{\"Speed\":0,\"Stamina\":0,\"Power\":0,\"Guts\":0,\"Wiz\":18,\"SkillPt\":24,\"HP\":-8}}]}");
        String recommendation = evaluator.recommend(summary);
        assertTrue(recommendation, recommendation.contains("耐"));
        assertTrue(recommendation, recommendation.contains("×5"));
    }

    @Test public void missingHeadFieldFallsBackToRawGains() throws Exception {
        JSONObject summary = new JSONObject(
                "{\"trainings\":[" +
                "{\"name\":\"Stamina\",\"is_enable\":1,\"failure_rate\":10," +
                "\"gains\":{\"Speed\":0,\"Stamina\":14,\"Power\":6,\"Guts\":0,\"Wiz\":0,\"SkillPt\":12,\"HP\":-8}}," +
                "{\"name\":\"Wiz\",\"is_enable\":1,\"failure_rate\":10," +
                "\"gains\":{\"Speed\":0,\"Stamina\":0,\"Power\":0,\"Guts\":0,\"Wiz\":18,\"SkillPt\":24,\"HP\":-8}}]}");
        String recommendation = evaluator.recommend(summary);
        assertTrue(recommendation, recommendation.contains("智"));
    }

    @Test public void shiningBonusCountsInScore() throws Exception {
        JSONObject summary = new JSONObject(
                "{\"trainings\":[" +
                "{\"name\":\"Stamina\",\"is_enable\":1,\"failure_rate\":10,\"heads\":2,\"shining\":2," +
                "\"gains\":{\"Speed\":0,\"Stamina\":14,\"Power\":6,\"Guts\":0,\"Wiz\":0,\"SkillPt\":12,\"HP\":-8}}," +
                "{\"name\":\"Speed\",\"is_enable\":1,\"failure_rate\":10,\"heads\":3,\"shining\":0," +
                "\"gains\":{\"Speed\":18,\"Stamina\":0,\"Power\":0,\"Guts\":0,\"Wiz\":0,\"SkillPt\":12,\"HP\":-8}}]}");
        String recommendation = evaluator.recommend(summary);
        assertTrue(recommendation, recommendation.contains("耐"));
    }
}