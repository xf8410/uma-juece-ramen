package com.umaai.assistant.service;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class RamenBoardTextTest {

    @Test public void decisionLineShowsActionMeanAndScale() throws Exception {
        JSONObject decision = new JSONObject(
                "{\"action_index\":1,\"action_display\":\"吃面/函馆-耐\",\"score\":66972.3," +
                "\"search_n\":4096,\"elapsed_ms\":12697}");
        String line = RamenBoardText.decisionLine(decision);
        assertTrue(line, line.contains("建议：吃面/函馆-耐"));
        assertTrue(line, line.contains("mean 66972"));
        assertTrue(line, line.contains("4096次"));
        assertTrue(line, line.contains("12.7s"));
    }

    @Test public void shortElapsedKeepsMsAndChineseActionTranslated() throws Exception {
        JSONObject decision = new JSONObject(
                "{\"action_index\":0,\"action_display\":\"Speed训练\",\"search_n\":32,\"elapsed_ms\":800}");
        String line = RamenBoardText.decisionLine(decision);
        assertTrue(line, line.contains("速度训练"));
        assertTrue(line, line.contains("800ms"));
    }

    @Test public void candidateDeltasShowOthersRelativeToChosen() throws Exception {
        JSONObject decision = new JSONObject(
                "{\"action_index\":1," +
                "\"candidate_displays\":[\"不吃面\",\"吃面/函馆-耐\",\"吃面/东京-智\",\"吃面/中山-速力智\"]," +
                "\"candidate_scores\":[65973,66972,66241,66930]}");
        String deltas = RamenBoardText.candidateDeltas(decision, 3);
        // 相对选中（66972）的差值
        assertTrue(deltas, deltas.contains("#0 不吃面 -999"));
        assertTrue(deltas, deltas.contains("#2 吃面/东京-智 -731"));
        assertTrue(deltas, deltas.contains("#3 吃面/中山-速力智 -42"));
        assertTrue(deltas, !deltas.contains("#1"));
    }

    @Test public void candidateDeltasEmptyWithoutScores() throws Exception {
        JSONObject decision = new JSONObject(
                "{\"action_index\":0,\"candidate_displays\":[\"不吃面\"],\"candidate_scores\":[0]}");
        assertEquals("", RamenBoardText.candidateDeltas(decision, 3));

        JSONObject noScores = new JSONObject("{\"action_index\":0}");
        assertEquals("", RamenBoardText.candidateDeltas(noScores, 3));
    }

    @Test public void trainingLinesMatchPcBoardStyle() throws Exception {
        JSONArray trainings = new JSONArray(
                "[{\"name\":\"Speed\",\"is_enable\":1,\"failure_rate\":10,\"heads\":3,\"shining\":2," +
                "\"gains\":{\"Speed\":46,\"Stamina\":0,\"Power\":14,\"Guts\":0,\"Wiz\":0,\"SkillPt\":27,\"HP\":-25}}," +
                "{\"name\":\"Stamina\",\"is_enable\":1,\"failure_rate\":0,\"heads\":5,\"shining\":0," +
                "\"gains\":{\"Speed\":0,\"Stamina\":36,\"Power\":0,\"Guts\":12,\"Wiz\":0,\"SkillPt\":19,\"HP\":-26}}," +
                "{\"name\":\"Rest\",\"is_enable\":1,\"failure_rate\":0,\"gains\":{\"HP\":40}}]");
        String text = RamenBoardText.trainingLines(trainings);
        assertTrue(text, text.contains("速: 速+46 力+14 27pt 体力-25 失败10% 头3光2"));
        assertTrue(text, text.contains("耐: 耐+36 根+12 19pt 体力-26 头5"));
        // 休息没有属性收益明细，不占行
        assertTrue(text, !text.contains("休息"));
    }
}
