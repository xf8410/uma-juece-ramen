package com.umaai.assistant.service;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * PC 黑板风格的文本组装（对齐 EtherealAO 版决策理由/训练明细的可读格式）。
 *
 * 目标显示效果（竖排平铺，每个候选一行）：
 * <pre>
 * 建议：吃面/函馆-耐（终局均分 66972 · 4096次/12.7s）
 * #0 不吃面 -999
 * #2 吃面/东京-智 -731
 * #3 吃面/中山-速力智 -42
 * 速 速46 力14 27pt 体力-25 失败10% 人数3光2
 * 耐 耐36 根12 19pt 体力-26 人数5
 * 训练建议：耐×5（较次优 +312 · 64次/1.2s）
 * </pre>
 *
 * v0.3.6 显示语义修正：
 * - 主建议行的 mean 标注为「终局均分」——它是模拟到育成结束的期望总分，
 *   不是本回合得分（避免五位数被误读成本回合训练得分）
 * - 训练建议行改用 {@link #trainingAdviceLine}：去掉绝对 mean，
 *   改显示「较次优 +Δ」（差值才是选训练的依据）；本回合真实收益
 *   仍由 hlpatch trainings 明细行承担
 * - 人头计数统一写作「人数N」（训练明细「头3」→「人数3」，上游候选文本
 *   「人N」→「人数N」）。友人也是人数的一种，无需特判——替换规则
 *   仅命中「人」后紧跟数字的情况，「友人出行」等不含数字的词不受影响
 *
 * 数据来源：
 * - 决策/候选：Rust DecisionOutput（action_display + candidate_displays/candidate_scores）
 * - 训练明细：hlpatch /summary 顶层 trainings（name/gains/failure_rate/heads/shining）
 */
public final class RamenBoardText {
    private RamenBoardText() {}

    /** 主建议行：`建议：吃面/函馆-耐（终局均分 66972 · 4096次/12.7s）` */
    public static String decisionLine(JSONObject decision) {
        if (decision == null) return "";
        String action = translate(decision.optString("action_display", "?")).replace('\n', ' ');
        StringBuilder b = new StringBuilder("建议：").append(action);
        int n = decision.optInt("search_n", 0);
        long ms = decision.optLong("elapsed_ms", 0);
        double score = decision.optDouble("score", 0.0);
        if (n > 0 || ms > 0 || score != 0.0) {
            b.append("（");
            boolean first = true;
            if (score != 0.0) {
                b.append("终局均分 ").append((long) score);
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
     * 训练建议行（Rust Train 阶段补搜结果）：`建议：耐×5（较次优 +312 · 64次/1.2s）`。
     *
     * 与主建议行的区别：不显示绝对 mean（那是模拟到终局的全局期望分，
     * 五位数对"这回合练哪个"毫无意义），改显示选中动作相对次优候选的
     * 差值 Δ——Δ 越大说明该训练优势越大；Δ 接近 0 说明练哪个都差不多。
     * 候选评分缺失（全 0，手写兜底路径）时只显示搜索规模，不显示差值。
     */
    public static String trainingAdviceLine(JSONObject trainingDecision) {
        if (trainingDecision == null) return "";
        String action = translate(trainingDecision.optString("action_display", "?")).replace('\n', ' ');
        StringBuilder b = new StringBuilder("建议：").append(action);
        int n = trainingDecision.optInt("search_n", 0);
        long ms = trainingDecision.optLong("elapsed_ms", 0);
        double bestDelta = bestVsSecondDelta(trainingDecision);

        b.append("（");
        boolean first = true;
        if (bestDelta != 0.0) {
            b.append("较次优 ").append(String.format("%+.0f", bestDelta));
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
        // 全部无内容时去掉空括号
        if (first) return "建议：" + action;
        b.append('）');
        return b.toString();
    }

    /** 选中动作相对次优候选的差值（candidate_scores 全 0 或不足 2 个有效值时返回 0） */
    static double bestVsSecondDelta(JSONObject decision) {
        if (decision == null) return 0.0;
        JSONArray scores = decision.optJSONArray("candidate_scores");
        if (scores == null) return 0.0;
        int len = scores.length();
        if (len < 2) return 0.0;

        int best = decision.optInt("action_index", 0);
        if (best < 0 || best >= len) best = 0;
        double bestScore = scores.optDouble(best, 0.0);
        if (bestScore == 0.0) return 0.0; // 全 0 = 无有效评分（手写兜底）

        double second = 0.0;
        boolean any = false;
        for (int i = 0; i < len; i++) {
            if (i == best) continue;
            double v = scores.optDouble(i, 0.0);
            if (v > 0.0 && (!any || v > second)) {
                second = v;
                any = true;
            }
        }
        if (!any) return 0.0;
        return bestScore - second;
    }

    /**
     * 候选差值（PC 黑板「决策理由」）：其余候选相对选中动作的 mean 差值。
     * 每个候选独占一行（竖排平铺）：
     * <pre>
     * #0 不吃面 -999
     * #2 吃面/东京-智 -731
     * </pre>
     * 无有效评分（全 0）时返回空串。
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
            if (b.length() > 0) b.append('\n');
            b.append('#').append(i).append(' ')
             .append(translate(names.optString(i, "?")))
             .append(' ').append(String.format("%+.0f", delta));
            shown++;
        }
        return b.toString();
    }

    /**
     * 训练明细行（PC 黑板「训练:」节）：每个可用训练一行。
     * `速 速46 力14 27pt 体力-25 失败10% 人数3光2`
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
                line.append(" 人数").append(Math.max(heads, 0));
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

    /** Rust 动作名 → 中文（与 ActionRecommendation 相同的映射） */
    static String translate(String action) {
        if (action == null) return "?";
        String out = action.replace("普通出行", "外出")
                .replace("友人出行", "友人外出")
                // 隐藏风味替换代码：A=面 B=汤 C=料（hlpatch sozai 顺序：麺/スープ/トッピング）
                // "(替换Bx1+Cx1)" → "(替换汤1+料1)"
                .replace("Ax", "面")
                .replace("Bx", "汤")
                .replace("Cx", "料")
                .replace("Speed训练", "速度训练")
                .replace("Stamina训练", "耐力训练")
                .replace("Power训练", "力量训练")
                .replace("Guts训练", "根性训练")
                .replace("Wisdom训练", "智力训练");
        // 人头计数规范：「人N」→「人数N」（上游候选文本的缩写）。
        // 只命中「人」紧跟数字的情况；「友人出行」「友人外出」无数字，不受影响，
        // 友人本身也计入人数，无需特判。
        for (char d = '0'; d <= '9'; d++) {
            out = out.replace("人" + d, "人数" + d);
        }
        return out;
    }
}
