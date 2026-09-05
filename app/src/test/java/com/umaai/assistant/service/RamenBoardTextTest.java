package com.umaai.assistant.service;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class RamenBoardTextTest {

    @Test public void decisionLineLabelsScoreAsFinalMean() throws Exception {
        JSONObject decision = new JSONObject(
                "{\"action_index\":1,\"action_display\":\"吃面/函馆-耐\",\"score\":66972.3," +
                "\"search_n\":4096,\"elapsed_ms\":12697}");
        String line = RamenBoardText.decisionLine(decision);
        assertTrue(line, line.contains("建议：吃面/函馆-耐"));
        // v0.3.6: mean 是模拟到终局的期望总分，必须标明「终局均分」，
        // 不能让五位数被误读成本回合训练得分
        assertTrue(line, line.contains("终局均分 66972"));
        assertFalse(line, line.contains("mean"));
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

    @Test public void trainingAdviceLineShowsDeltaNotAbsoluteScore() throws Exception {
        // 训练建议（Rust Train 补搜）：显示「较次优 +Δ」，不显示五位数绝对分
        JSONObject td = new JSONObject(
                "{\"action_index\":0,\"action_display\":\"耐×5\"," +
                "\"candidate_scores\":[65000,64688,64310],\"search_n\":64,\"elapsed_ms\":1200}");
        String line = RamenBoardText.trainingAdviceLine(td);
        assertTrue(line, line.contains("建议：耐×5"));
        assertTrue(line, line.contains("较次优 +312"));
        assertFalse(line, line.contains("65000"));
        assertFalse(line, line.contains("终局均分"));
        assertTrue(line, line.contains("64次"));
        assertTrue(line, line.contains("1.2s"));
    }

    @Test public void trainingAdviceLineOmitsDeltaWithoutScores() throws Exception {
        JSONObject td = new JSONObject(
                "{\"action_index\":0,\"action_display\":\"耐×5\",\"candidate_scores\":[0],\"search_n\":64}");
        String line = RamenBoardText.trainingAdviceLine(td);
        assertTrue(line, line.contains("建议：耐×5"));
        assertFalse(line, line.contains("较次优"));
        assertTrue(line, line.contains("64次"));
    }

    @Test public void bestVsSecondDeltaComputesGap() throws Exception {
        JSONObject td = new JSONObject(
                "{\"action_index\":1,\"candidate_scores\":[100,312,50]}");
        assertEquals(212.0, RamenBoardText.bestVsSecondDelta(td), 0.001);

        JSONObject allZero = new JSONObject(
                "{\"action_index\":0,\"candidate_scores\":[0,0,0]}");
        assertEquals(0.0, RamenBoardText.bestVsSecondDelta(allZero), 0.001);

        JSONObject single = new JSONObject("{\"action_index\":0,\"candidate_scores\":[100]}");
        assertEquals(0.0, RamenBoardText.bestVsSecondDelta(single), 0.001);
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

    @Test public void trainingLinesUseRenShuWording() throws Exception {
        JSONArray trainings = new JSONArray(
                "[{\"name\":\"Speed\",\"is_enable\":1,\"failure_rate\":10,\"heads\":3,\"shining\":2," +
                "\"gains\":{\"Speed\":46,\"Stamina\":0,\"Power\":14,\"Guts\":0,\"Wiz\":0,\"SkillPt\":27,\"HP\":-25}}," +
                "{\"name\":\"Stamina\",\"is_enable\":1,\"failure_rate\":0,\"heads\":5,\"shining\":0," +
                "\"gains\":{\"Speed\":0,\"Stamina\":36,\"Power\":0,\"Guts\":12,\"Wiz\":0,\"SkillPt\":19,\"HP\":-26}}," +
                "{\"name\":\"Rest\",\"is_enable\":1,\"failure_rate\":0,\"gains\":{\"HP\":40}}]");
        String text = RamenBoardText.trainingLines(trainings);
        // v0.3.6: 人头计数写作「人数N」（此前「头N」易误读为编号）
        assertTrue(text, text.contains("速: 速+46 力+14 27pt 体力-25 失败10% 人数3光2"));
        assertTrue(text, text.contains("耐: 耐+36 根+12 19pt 体力-26 人数5"));
        // 休息没有属性收益明细，不占行
        assertTrue(text, !text.contains("休息"));
    }

    @Test public void translateNormalizesHeadCountAndKeepsFriendOutings() {
        // 上游候选「人N」→「人数N」
        assertEquals("速训练 人数3", RamenBoardText.translate("Speed训练 人3"));
        assertEquals("吃面/函馆-耐 人数5光2", RamenBoardText.translate("吃面/函馆-耐 人5光2"));
        // 多位数逐个命中
        assertEquals("人数12", RamenBoardText.translate("人12"));
        // 友人出行/友人外出不含数字，不受影响；友人也是人数的一种，无需特判
        assertEquals("友人外出", RamenBoardText.translate("友人出行"));
        assertEquals("友人外出", RamenBoardText.translate("友人出行"));
        // 已是「人数N」的输入不会二次替换
        assertEquals("人数3", RamenBoardText.translate("人数3"));
    }
}
