package com.umaai.assistant.service;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * PC 黑板风格的文本组装（对齐 EtherealAO 版决策理由/训练明细的可读格式）。
 *
 * 数据来源：
 * - 决策/候选：Rust DecisionOutput（action_display + candidate_displays/candidate_scores）
 * - 训练明细：hlpatch /summary 顶层 trainings（name/gains/failure_rate/heads/shining）
 * - SO 建议：hlpatch /summary 顶层 ai（best/best_v/rest/outgoing/train）—— 当 trainings
 *   为空（hlpatch v3.27.20+ / 非训练选择画面）时，用 SO 自己算出的建议兜底
 */
public final class RamenBoardText {
    private RamenBoardText() {}

    /** 主建议行：`建议：吃面/函馆-耐（mean 66972 · 4096次/12.7s）` */
    public static String decisionLine(JSONObject decision) {
        if (decision == null) return "";
        String action = translate(decision.optString("action_display", "?")).replace('\n', ' ');
        StringBuilder b = new StringBuilder("建议：").append(action);
        int n = decision.optInt("search_n", 0);
        long ms = decision.optLong("elapsed_ms", 0);
        double score = decision.optDouble("score", 0.0);
        if (n > 0 || ms > 0) {
            b.append("（");
            boolean first = true;
            if (score != 0.0) {
                b.append("mean ").append((long) score);
                first = false;
            }
            if (n > 0) {
                if (!first) b.append(" · ");
                b.append(n).append("次");
                first = false;
            }
            if (ms > 0) {
                if (!first) b.append(" · ");
                if (ms >= 10000) b.append(String.format("%.1fs", ms / 1000.0));
                else b.append(ms).append("ms");
            }
            b.append('）');
        }
        return b.toString();
    }

    /**
     * 候选差值行（PC 黑板「决策理由」）：其余候选相对选中动作的 mean 差值。
     * `#0 不吃面 -999 ｜ #2 吃面/东京-智 -731`；无有效评分（全 0）时返回空串。
     */
    public static String candidateDeltas(JSONObject decision, int maxOthers) {
        if (decision == null || maxOthers <= 0) return "";
        JSONArray names = decision.optJSONArray("candidate_displays");
        JSONArray scores = decision.optJSONArray("candidate_scores");
        if (names == null || scores == null) return "";
        int len = Math.min(names.length(), scores.length());
        if (len == 0) return "";

        int best = decision.optInt("action_index", 0);
        if (best < 0 || best >= len) best = 0;
        double bestScore = scores.optDouble(best, 0.0);

        // 全 0 说明评分没拿到（手写兜底/解析失败），差值没有意义
        boolean anyScore = false;
        for (int i = 0; i < len; i++) {
            if (scores.optDouble(i, 0.0) != 0.0) { anyScore = true; break; }
        }
        if (!anyScore) return "";

        StringBuilder b = new StringBuilder();
        int shown = 0;
        for (int i = 0; i < len && shown < maxOthers; i++) {
            if (i == best) continue;
            double delta = scores.optDouble(i, 0.0) - bestScore;
            if (b.length() > 0) b.append(" ｜ ");
            b.append('#').append(i).append(' ')
             .append(translate(names.optString(i, "?")))
             .append(' ').append(String.format("%+.0f", delta));
            shown++;
        }
        return b.toString();
    }

    /**
     * SO 建议行（来自 hlpatch /summary 顶层 ai）。
     * 当 trainings 为空（v3.27.20+、或当前不在训练选择画面）时，SO 仍会算一个动作建议。
     * 例：`SO建议：外出(487) ｜ 休息187`；in-turn 时 ai.train 有每项训练值则换行列出。
     */
    public static String soRecommendation(JSONObject summary) {
        JSONObject ai = summary == null ? null : summary.optJSONObject("ai");
        if (ai == null) return "";
        String best = ai.optString("best", "");
        if (best.isEmpty()) return "";
        double bestV = ai.optDouble("best_v", 0);
        String bestName = actionName(best);

        StringBuilder b = new StringBuilder("SO建议：").append(bestName);
        if (bestV != 0) b.append('(').append((long) bestV).append(')');

        // 对比项：只列出与 best 不同的（避免"外出(487) ｜ 外出487"重复）
        double rest = ai.optDouble("rest", 0);
        double outing = ai.optDouble("outgoing", 0);
        StringBuilder compare = new StringBuilder();
        boolean isRest = "休息".equals(bestName);
        boolean isOuting = "外出".equals(bestName);
        if (!isRest && rest != 0) compare.append(" 休息").append((long) rest);
        if (!isOuting && outing != 0) compare.append(" 外出").append((long) outing);
        if (compare.length() > 0) b.append(" ｜").append(compare);

        String trainLines = aiTrainLines(ai.optJSONObject("train"));
        if (!trainLines.isEmpty()) b.append('\n').append(trainLines);
        return b.toString();
    }

    /**
     * ai.train 对象 → 每项训练值行。
     * 返回 `训练：速300 耐200 力180 根120 智260`；为空返回空串。
     */
    public static String aiTrainLines(JSONObject train) {
        if (train == null || train.length() == 0) return "";
        StringBuilder b = new StringBuilder("训练：");
        java.util.Iterator<String> keys = train.keys();
        boolean first = true;
        while (keys.hasNext()) {
            String k = keys.next();
            long v = (long) train.optDouble(k, 0);
            if (!first) b.append(' ');
            b.append(actionName(k)).append(v);
            first = false;
        }
        return b.toString();
    }

    /**
     * 训练明细行（PC 黑板「训练:」节）：每个可用训练一行。
     * `速 速46 力14 27pt 体力-25 失败10% 头3光2`
     */
    public static String trainingLines(JSONArray trainings) {
        if (trainings == null) return "";
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < trainings.length(); i++) {
            JSONObject t = trainings.optJSONObject(i);
            if (t == null || t.optInt("is_enable", 1) == 0) continue;
            String label = shortName(t.optString("name"));
            if (label.isEmpty()) continue; // 休息/外出等无训练收益明细，不占行
            JSONObject g = t.optJSONObject("gains");
            if (g == null) continue;
            StringBuilder line = new StringBuilder(label).append(':');
            appendGain(line, " 速", g.optInt("Speed"));
            appendGain(line, " 耐", g.optInt("Stamina"));
            appendGain(line, " 力", g.optInt("Power"));
            appendGain(line, " 根", g.optInt("Guts"));
            appendGain(line, " 智", g.optInt("Wiz"));
            int pt = g.optInt("SkillPt");
            if (pt != 0) line.append(' ').append(pt).append("pt");
            int hp = g.optInt("HP");
            if (hp != 0) line.append(" 体力").append(hp > 0 ? "+" : "").append(hp);
            int f = t.optInt("failure_rate");
            if (f > 0) line.append(" 失败").append(f).append('%');
            int heads = t.optInt("heads");
            int shining = t.optInt("shining");
            if (heads > 0 || shining > 0) {
                line.append(" 头").append(Math.max(heads, 0));
                if (shining > 0) line.append("光").append(shining);
            }
            if (out.length() > 0) out.append('\n');
            out.append(line);
        }
        return out.toString();
    }

    private static void appendGain(StringBuilder line, String label, int v) {
        if (v != 0) line.append(label).append(v > 0 ? "+" : "").append(v);
    }

    /** 训练命令名 → 单字标签；休息/外出等返回空串（明细行跳过） */
    static String shortName(String name) {
        if (name == null) return "";
        switch (name.toLowerCase()) {
            case "speed": return "速";
            case "stamina": return "耐";
            case "power": return "力";
            case "guts": return "根";
            case "wiz":
            case "wisdom": return "智";
            default: return "";
        }
    }

    /** 动作名（SO/Rust）→ 中文；未知原样返回 */
    static String actionName(String name) {
        if (name == null) return "?";
        switch (name.toLowerCase()) {
            case "speed": case "速度": return "速";
            case "stamina": case "耐力": return "耐";
            case "power": case "力量": return "力";
            case "guts": case "根性": return "根";
            case "wisdom":
            case "wiz":
            case "智力": return "智";
            case "rest": case "休息": return "休息";
            case "outgoing":
            case "普通出行":
            case "外出": return "外出";
            case "race": case "比赛": return "比赛";
            default: return translate(name);
        }
    }

    /** Rust 动作名 → 中文（与 ActionRecommendation 相同的映射） */
    static String translate(String action) {
        if (action == null) return "?";
        return action.replace("普通出行", "外出")
                .replace("友人出行", "友人外出")
                .replace("Speed训练", "速度训练")
                .replace("Stamina训练", "耐力训练")
                .replace("Power训练", "力量训练")
                .replace("Guts训练", "根性训练")
                .replace("Wisdom训练", "智力训练");
    }
}
