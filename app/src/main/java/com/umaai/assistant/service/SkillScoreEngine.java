package com.umaai.assistant.service;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

/**
 * 技能评分评估引擎（v0.4.0）
 *
 * 移植自 URA 小黑板 SkillTipsResponseAnalyzer（C#）→ Java，算法语义逐行对齐：
 *  1. Apply（SkillManager.cs:111-171）：tips 展开 + 天赋 + 已学 + 补下位链 + 建链
 *     + ApplyHint 折扣（hint level 0-5 → 0-40%，切者 effect=7 额外 -10%）
 *     + ApplyProper 适性修正（跑法/距离各 4 项，8 档 G=1..S=8；S/A=×1.1 B/C=×0.9
 *       D/E/F=×0.8 G=×0.7；场地适性按原版语义不参与）
 *  2. CalculateSkillScoreCost（Class1.cs:268-321）：已学 Cost=MAX 沿链蔓延 + removeInferiors
 *  3. DP（Class1.cs:323-410）：技能点背包，每技能三档选择（本尊/下位/下下位），dpLog 回溯
 *  4. 汇总（Class1.cs:100-260）：已学评分 + 属性评价点 + GradeRank 298 档 + 三性价比
 *
 * 数据源（assets/data/，来自 URA Assets 仓库 GameData/ja-JP/*.br 解包）：
 *  - skill_data.json：2124 技能 {id,groupId,rarity,rate,grade,cost,displayOrder,upgraded,propers,category,name}
 *  - talent_skills.json：固有技能 {cardId:[{skillId,rank}]}
 *  - status_to_point.json：属性→评价点 2501 档
 *  - grade_rank.json：298 评级档 {id,min,max,rank}
 *
 * 与原版的已知差异（第一版简化）：
 *  - 固有/剧本技能进化替换（ReplaceAllSkillWithUpgradeSkill）未实现，
 *    已学固有技能按基础档 Grade 计分（进化差额通常为正，实际评分略保守）
 */
public final class SkillScoreEngine {

    private static final Object LOCK = new Object();
    private static volatile boolean loaded = false;

    // 数据表
    private static HashMap<Integer, Sk> protoById;              // 原始技能表（只读原型）
    private static HashMap<Integer, List<Sk>> protoByGroup;     // groupId → 组内全部档
    private static HashMap<Integer, List<int[]>> talentByCard;  // cardId → [skillId, rank]
    private static int[] statusToPoint;                          // 属性→评价点（下标=属性值）
    private static Sk[] gradeRank;                               // 298 档（name=评级名, displayOrder=min, cost=max）

    /** 引擎内可变技能节点（Clone 自原型，带链指针） */
    static final class Sk {
        int id, groupId, rarity, rate, grade, cost, displayOrder;
        String name = "";
        int[][] propers; // {ground, distance, style}（原版 ApplyProper 只用 distance/style）
        Sk superior, inferior;
        boolean learned; // 该档对应技能已学（skill_array 命中）

        Sk copy() {
            Sk s = new Sk();
            s.id = id; s.groupId = groupId; s.rarity = rarity; s.rate = rate;
            s.grade = grade; s.cost = cost; s.displayOrder = displayOrder;
            s.name = name; s.propers = propers;
            return s;
        }
    }

    private SkillScoreEngine() { }

    // ── 数据加载 ─────────────────────────────────────────────────────

    public static void ensureLoaded(Context ctx) {
        if (loaded) return;
        synchronized (LOCK) {
            if (loaded) return;
            try {
                JSONArray skills = new JSONArray(readAsset(ctx, "data/skill_data.json"));
                protoById = new HashMap<>();
                protoByGroup = new HashMap<>();
                for (int i = 0; i < skills.length(); i++) {
                    JSONObject o = skills.getJSONObject(i);
                    Sk s = new Sk();
                    s.id = o.optInt("id");
                    s.groupId = o.optInt("groupId");
                    s.rarity = o.optInt("rarity");
                    s.rate = o.optInt("rate");
                    s.grade = o.optInt("grade");
                    s.cost = o.optInt("cost");
                    s.displayOrder = o.optInt("displayOrder");
                    s.name = o.optString("name", "");
                    JSONArray pr = o.optJSONArray("propers");
                    if (pr != null && pr.length() > 0) {
                        s.propers = new int[pr.length()][];
                        for (int j = 0; j < pr.length(); j++) {
                            JSONObject p = pr.getJSONObject(j);
                            s.propers[j] = new int[]{p.optInt("ground"), p.optInt("distance"), p.optInt("style")};
                        }
                    }
                    protoById.put(s.id, s);
                    List<Sk> g = protoByGroup.get(s.groupId);
                    if (g == null) { g = new ArrayList<>(); protoByGroup.put(s.groupId, g); }
                    g.add(s);
                }

                JSONObject talent = new JSONObject(readAsset(ctx, "data/talent_skills.json"));
                talentByCard = new HashMap<>();
                java.util.Iterator<String> it = talent.keys();
                while (it.hasNext()) {
                    String cardId = it.next();
                    JSONArray arr = talent.optJSONArray(cardId);
                    if (arr == null) continue;
                    List<int[]> list = new ArrayList<>();
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject t = arr.getJSONObject(i);
                        list.add(new int[]{t.optInt("skillId"), t.optInt("rank")});
                    }
                    try { talentByCard.put(Integer.parseInt(cardId), list); } catch (NumberFormatException ignored) { }
                }

                JSONArray stp = new JSONArray(readAsset(ctx, "data/status_to_point.json"));
                statusToPoint = new int[stp.length()];
                for (int i = 0; i < stp.length(); i++) statusToPoint[i] = stp.optInt(i);

                JSONArray gr = new JSONArray(readAsset(ctx, "data/grade_rank.json"));
                gradeRank = new Sk[gr.length()];
                for (int i = 0; i < gr.length(); i++) {
                    JSONObject o = gr.getJSONObject(i);
                    Sk s = new Sk();
                    s.id = o.optInt("id");
                    s.displayOrder = o.optInt("min");   // min
                    s.cost = o.optInt("max");           // max
                    s.name = o.optString("rank", "");   // 评级名
                    gradeRank[i] = s;
                }
                loaded = true;
            } catch (Exception e) {
                android.util.Log.e("SkillScoreEngine", "data load failed", e);
            }
        }
    }

    private static String readAsset(Context ctx, String path) throws Exception {
        InputStream in = ctx.getAssets().open(path);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        in.close();
        return out.toString("UTF-8");
    }

    public static boolean isLoaded() { return loaded; }

    // ── 主入口 ───────────────────────────────────────────────────────

    /**
     * 输入 hlpatch /summary 的顶层 JSON，输出技能评分评估结果（display_ready JSON）。
     * 数据不足（无 skill_tips / 未加载）时返回 {"ok":false,"reason":...}。
     */
    public static JSONObject evaluate(JSONObject summary) {
        JSONObject out = new JSONObject();
        try {
            JSONObject chara = summary.optJSONObject("chara");
            if (chara == null) return out.put("ok", false).put("reason", "no_chara");
            JSONArray tipsArr = summary.optJSONArray("skill_tips");
            if (tipsArr == null) return out.put("ok", false).put("reason", "no_skill_tips");

            int skillPoint = chara.optInt("skill_point", 0);
            if (skillPoint <= 0) return out.put("ok", false).put("reason", "no_skill_point");

            int cardId = summary.optInt("chara_id", 0);
            int talentLevel = summary.optInt("talent_level", 1);

            // 已学技能 {id: level}
            HashSet<Integer> learnedIds = new HashSet<>();
            HashMap<Integer, Integer> learnedLevel = new HashMap<>();
            JSONObject skillsObj = summary.optJSONObject("skills");
            if (skillsObj != null) {
                JSONArray list = skillsObj.optJSONArray("list");
                if (list != null) {
                    for (int i = 0; i < list.length(); i++) {
                        JSONObject sk = list.optJSONObject(i);
                        if (sk == null) continue;
                        int id = sk.optInt("id");
                        learnedIds.add(id);
                        learnedLevel.put(id, sk.optInt("lv", 1));
                    }
                }
            }

            // 适性：8 维（4 跑法 + 4 距离），8 档 G=1..S=8。
            // hlpatch 偏移读取按 0-based 记录（G=0..S=7），auto-detect 归一：
            // 存在 0 值 → 0-based，全体 +1；否则按 1-8 直读。
            int[] apt = new int[]{
                chara.optInt("proper_style_nige", 0), chara.optInt("proper_style_senko", 0),
                chara.optInt("proper_style_sashi", 0), chara.optInt("proper_style_oikomi", 0),
                chara.optInt("proper_dist_short", 0), chara.optInt("proper_dist_mile", 0),
                chara.optInt("proper_dist_mid", 0), chara.optInt("proper_dist_long", 0)
            };
            boolean zeroBased = false;
            for (int v : apt) if (v == 0) zeroBased = true;
            if (zeroBased) for (int i = 0; i < 8; i++) apt[i] = Math.min(8, apt[i] + 1);
            // 跑法→apt[0..3]，距离→apt[4..7]（SkillProper.StyleType 1-4 / DistanceType 1-4 同序）

            List<String> warnings = new ArrayList<>();

            // ── 1. Apply：构建 tips 并建链 ──
            List<Sk> tips = new ArrayList<>();
            HashSet<Integer> used = new HashSet<>();

            for (int i = 0; i < tipsArr.length(); i++) {
                JSONObject t = tipsArr.optJSONObject(i);
                if (t == null) continue;
                int gid = t.optInt("group_id"), rarity = t.optInt("rarity");
                List<Sk> found = findByGroupExact(gid, rarity);
                if (found == null || found.isEmpty()) {
                    warnings.add("未知技能组 g" + gid + " r" + rarity);
                    continue;
                }
                for (Sk p : found) {
                    if (p.rate <= 0) continue;
                    Sk c = p.copy();
                    tips.add(c); used.add(c.id);
                }
            }

            // 天赋技能
            List<int[]> talents = cardId > 0 ? talentByCard.get(cardId) : null;
            boolean unknownUma = talents == null;
            if (talents != null) {
                for (int[] tn : talents) {
                    if (tn[1] > talentLevel) continue;
                    if (used.contains(tn[0]) || learnedIds.contains(tn[0])) continue;
                    Sk p = protoById.get(tn[0]);
                    if (p == null) continue;
                    Sk c = p.copy(); tips.add(c); used.add(c.id);
                }
            } else {
                warnings.add("固有技能表未收录 cardId=" + cardId);
            }

            // 已学技能
            for (Integer lid : learnedIds) {
                if (used.contains(lid)) continue;
                Sk p = protoById.get(lid);
                if (p == null) continue;
                Sk c = p.copy(); c.learned = true; tips.add(c); used.add(c.id);
            }

            // 补下位链（组内 Rarity ≤ tips 内该组最大 Rarity 且 Rate>0）
            HashMap<Integer, Integer> groupMaxRarity = new HashMap<>();
            for (Sk s : tips) {
                Integer m = groupMaxRarity.get(s.groupId);
                groupMaxRarity.put(s.groupId, (m == null || s.rarity > m) ? s.rarity : m);
            }
            for (Map.Entry<Integer, Integer> e : groupMaxRarity.entrySet()) {
                List<Sk> group = protoByGroup.get(e.getKey());
                if (group == null) continue;
                for (Sk p : group) {
                    if (p.rate <= 0 || p.rarity > e.getValue() || used.contains(p.id)) continue;
                    Sk c = p.copy(); tips.add(c); used.add(c.id);
                }
            }

            // 建链（Superior/Inferior，对齐原版四级匹配）
            for (Sk s : tips) {
                for (Sk o : tips) {
                    if (o.groupId != s.groupId || o.id == s.id) continue;
                    if (o.rarity == s.rarity && o.rate == s.rate + 1) { s.superior = o; break; }
                }
                if (s.superior == null) {
                    for (Sk o : tips) {
                        if (o.groupId != s.groupId || o.id == s.id) continue;
                        if (o.rarity == s.rarity + 1 && o.rate == s.rate + 1) { s.superior = o; break; }
                    }
                }
                for (Sk o : tips) {
                    if (o.groupId != s.groupId || o.id == s.id) continue;
                    if (o.rarity == s.rarity && o.rate == s.rate - 1) { s.inferior = o; break; }
                }
                if (s.inferior == null) {
                    for (Sk o : tips) {
                        if (o.groupId != s.groupId || o.id == s.id) continue;
                        if (o.rarity == s.rarity - 1 && o.rate == s.rate - 1) { s.inferior = o; break; }
                    }
                }
            }

            // 切者折扣（chara_effect_ids 含 7）
            boolean hasSaiSha = false;
            JSONArray effIds = chara.optJSONArray("chara_effect_ids");
            if (effIds != null) {
                for (int i = 0; i < effIds.length(); i++) if (effIds.optInt(i) == 7) { hasSaiSha = true; break; }
            }

            // ApplyHint + ApplyProper
            for (Sk s : tips) {
                int level = 0;
                for (int i = 0; i < tipsArr.length(); i++) {
                    JSONObject t = tipsArr.optJSONObject(i);
                    if (t != null && t.optInt("group_id") == s.groupId && t.optInt("rarity") == s.rarity) {
                        level = t.optInt("level", 0); break;
                    }
                }
                int cutted = hasSaiSha ? 10 : 0;
                int off;
                switch (Math.min(level, 5)) {
                    case 1: off = 10; break;
                    case 2: off = 20; break;
                    case 3: off = 30; break;
                    case 4: off = 35; break;
                    case 5: off = 40; break;
                    default: off = 0; break;
                }
                s.cost = s.cost * (100 - off - cutted) / 100;
                applyProper(s, apt);
            }

            // 链累计（Rate 降序）：已学下位 → 扣 Grade；未学 → Cost 累计
            List<Sk> byRateDesc = new ArrayList<>(tips);
            java.util.Collections.sort(byRateDesc, (a, b) -> b.rate != a.rate
                    ? Integer.compare(b.rate, a.rate) : Integer.compare(b.rarity, a.rarity));
            for (Sk s : byRateDesc) {
                Sk inf = s.inferior;
                while (inf != null) {
                    if (learnedIds.contains(inf.id)) { s.grade -= inf.grade; break; }
                    s.cost += inf.cost;
                    inf = inf.inferior;
                }
            }

            // ── 2. CalculateSkillScoreCost：已学封价 + removeInferiors ──
            for (Sk s : tips) {
                if (!learnedIds.contains(s.id)) continue;
                s.cost = Integer.MAX_VALUE;
                Sk inf = s.inferior;
                while (inf != null) { inf.cost = Integer.MAX_VALUE; inf = inf.inferior; }
            }

            // removeInferiors：每组 (rarity,rate) 降序 Skip(1) 移除
            HashMap<Integer, List<Sk>> byGroupTips = new HashMap<>();
            for (Sk s : tips) {
                List<Sk> g = byGroupTips.get(s.groupId);
                if (g == null) { g = new ArrayList<>(); byGroupTips.put(s.groupId, g); }
                g.add(s);
            }
            HashSet<Integer> keepIds = new HashSet<>();
            for (Map.Entry<Integer, List<Sk>> e : byGroupTips.entrySet()) {
                List<Sk> g = e.getValue();
                java.util.Collections.sort(g, (a, b) -> b.rarity != a.rarity
                        ? Integer.compare(b.rarity, a.rarity) : Integer.compare(b.rate, a.rate));
                keepIds.add(g.get(0).id);
            }
            List<Sk> dpTips = new ArrayList<>();
            HashMap<Integer, Sk> tipById = new HashMap<>();
            for (Sk s : tips) {
                if (keepIds.contains(s.id)) { dpTips.add(s); tipById.put(s.id, s); }
            }

            // ── 3. DP：技能点背包 ──
            int totalSP = skillPoint;
            int dpSize = totalSP + 101;
            int[] dp = new int[dpSize];
            @SuppressWarnings("unchecked")
            List<Integer>[] dpLog = new List[dpSize];
            for (int i = 0; i < dpSize; i++) dpLog[i] = new ArrayList<>();
            for (Sk s : dpTips) {
                int[] supId = new int[]{s.id, 0, 0};
                int[] supCost = new int[]{s.cost, Integer.MAX_VALUE, Integer.MAX_VALUE};
                int[] supGrade = new int[]{s.grade, Integer.MIN_VALUE, Integer.MIN_VALUE};
                Sk cur = s;
                if (supCost[0] != 0 && cur.inferior != null) {
                    cur = cur.inferior;
                    supId[1] = cur.id; supCost[1] = cur.cost; supGrade[1] = cur.grade;
                    if (supCost[1] != 0 && cur.inferior != null) {
                        cur = cur.inferior;
                        supId[2] = cur.id; supCost[2] = cur.cost; supGrade[2] = cur.grade;
                    }
                }
                if (supGrade[0] == 0) supCost[0] = Integer.MAX_VALUE;
                if (supGrade[1] == 0) supCost[1] = Integer.MAX_VALUE;
                if (supGrade[2] == 0) supCost[2] = Integer.MAX_VALUE;

                for (int j = totalSP + 100; j >= 0; j--) {
                    int c0 = dp[j];
                    int c1 = (j - supCost[0] >= 0 && supCost[0] != Integer.MAX_VALUE) ? dp[j - supCost[0]] + supGrade[0] : -1;
                    int c2 = (j - supCost[1] >= 0 && supCost[1] != Integer.MAX_VALUE) ? dp[j - supCost[1]] + supGrade[1] : -1;
                    int c3 = (j - supCost[2] >= 0 && supCost[2] != Integer.MAX_VALUE) ? dp[j - supCost[2]] + supGrade[2] : -1;
                    int best = Math.max(Math.max(c0, c1), Math.max(c2, c3));
                    // 原版 IsBestOption：平局取编号小者（choice[0] 优先=不买）
                    if (c0 >= best) { /* keep dp[j] */ }
                    else if (c1 == best) { dp[j] = c1; dpLog[j] = new ArrayList<>(dpLog[j - supCost[0]]); dpLog[j].add(supId[0]); }
                    else if (c2 == best) { dp[j] = c2; dpLog[j] = new ArrayList<>(dpLog[j - supCost[1]]); dpLog[j].add(supId[1]); }
                    else if (c3 == best) { dp[j] = c3; dpLog[j] = new ArrayList<>(dpLog[j - supCost[2]]); dpLog[j].add(supId[2]); }
                }
            }

            // 回溯 learn 清单（对齐原版：tips 内本尊/下位/下下位三档匹配）
            List<Sk> learn = new ArrayList<>();
            for (Integer id : dpLog[totalSP]) {
                for (Sk s : dpTips) {
                    Sk inf = s.inferior;
                    Sk inf2 = inf != null ? inf.inferior : null;
                    if (s.id == id) { learn.add(s); totalSP -= s.cost; break; }
                    if (inf != null && inf.id == id) { learn.add(inf); totalSP -= inf.cost; break; }
                    if (inf2 != null && inf2.id == id) { learn.add(inf2); totalSP -= inf2.cost; break; }
                }
            }
            java.util.Collections.sort(learn, (a, b) -> Integer.compare(a.displayOrder, b.displayOrder));

            int willLearnPoint = 0;
            for (Sk s : learn) willLearnPoint += s.grade;

            // ── 4. 汇总评分 ──
            int spd = chara.optInt("speed", 0), sta = chara.optInt("stamina", 0);
            int pow = chara.optInt("power", 0), gut = chara.optInt("guts", 0), wiz = chara.optInt("wiz", 0);
            int statusPoint = stp(spd) + stp(sta) + stp(pow) + stp(gut) + stp(wiz);

            int previousLearnPoint = 0;
            for (Map.Entry<Integer, Integer> e : learnedLevel.entrySet()) {
                int id = e.getKey();
                if (id > 1000000 && id < 2000000) continue;
                String ids = String.valueOf(id);
                if (ids.charAt(0) == '1' && id > 100000 && id < 200000) {
                    previousLearnPoint += 170 * e.getValue();
                } else if (ids.length() == 5) {
                    previousLearnPoint += 120 * e.getValue();
                } else {
                    Sk p = tipById.get(id);
                    if (p == null) {
                        // 不在 tips 的已学（罕见）：查原型（Proper 修正前 Grade）
                        Sk proto = protoById.get(id);
                        if (proto != null) previousLearnPoint += proto.grade;
                        continue;
                    }
                    previousLearnPoint += p.grade; // 进化替换第一版不实现
                }
            }

            int totalPoint = willLearnPoint + previousLearnPoint + statusPoint;

            Sk lv = null, lvNext = null;
            for (int i = 0; i < gradeRank.length; i++) {
                if (gradeRank[i].displayOrder <= totalPoint && totalPoint <= gradeRank[i].cost) {
                    lv = gradeRank[i];
                    lvNext = (i + 1 < gradeRank.length) ? gradeRank[i + 1] : null;
                    break;
                }
            }

            // ── 5. 性价比三指标 ──
            int totalSP0 = skillPoint;
            String avgEff = totalSP0 > 0
                    ? String.format(java.util.Locale.US, "%.3f", (double) willLearnPoint / totalSP0) : null;
            String marginalEff = null;
            if (totalSP0 > 50) {
                double sxy = 0, sx2 = 0;
                for (int x = -50; x <= 50; x++) {
                    int y = dp[totalSP0 + x];
                    sxy += (double) x * y;
                    sx2 += (double) x * x;
                }
                marginalEff = String.format(java.util.Locale.US, "%.3f", sxy / sx2);
            }
            JSONArray expectedEff = new JSONArray();
            for (int t = 1; t <= 10; t++) {
                int start = totalSP0 - t * 50 - 25;
                if (start < 0) break;
                double sum = 0;
                for (int i = start; i < start + 51; i++) sum += dp[i];
                double meanReduced = sum / 51.0;
                double eff = (dp[totalSP0] - meanReduced) / (t * 50.0);
                JSONObject eo = new JSONObject();
                try { eo.put("sp", t * 50); eo.put("eff", String.format(java.util.Locale.US, "%.3f", eff)); expectedEff.put(eo); } catch (Exception ignored) { }
            }

            // ── 6. 输出 ──
            out.put("ok", true);
            out.put("total_point", totalPoint);
            out.put("skill_eval", previousLearnPoint);
            out.put("will_learn", willLearnPoint);
            out.put("status_point", statusPoint);
            out.put("sp_total", totalSP0);
            out.put("sp_left", totalSP);
            out.put("sp_used", totalSP0 - totalSP);
            JSONArray learnArr = new JSONArray();
            for (int i = 0; i < Math.min(learn.size(), 8); i++) {
                Sk s = learn.get(i);
                JSONObject lo = new JSONObject();
                lo.put("name", s.name.isEmpty() ? ("技能" + s.id) : s.name);
                lo.put("cost", s.cost);
                lo.put("grade", s.grade);
                learnArr.put(lo);
            }
            out.put("learn", learnArr);
            out.put("learn_total", learn.size());
            if (lv != null) {
                out.put("rank", lv.name);
                out.put("rank_progress", lvNext != null
                        ? clamp((double) (totalPoint - lv.displayOrder) / Math.max(1, lvNext.displayOrder - lv.displayOrder), 0, 1)
                        : 1.0);
                if (lvNext != null) {
                    out.put("next_rank", lvNext.name);
                    out.put("points_to_next", lvNext.displayOrder - totalPoint);
                }
            }
            if (avgEff != null) out.put("avg_eff", avgEff);
            if (marginalEff != null) out.put("marginal_eff", marginalEff);
            out.put("expected_eff", expectedEff);
            JSONArray warnArr = new JSONArray();
            for (String w : warnings) warnArr.put(w);
            if (unknownUma) warnArr.put("固有技能表未收录该马娘");
            out.put("warnings", warnArr);
            return out;
        } catch (Exception e) {
            try { return out.put("ok", false).put("reason", "exception:" + e.getMessage()); }
            catch (Exception ignored) { return out; }
        }
    }

    /** ApplyProper：Propers 非空时取所有触发条件修正后的最大 Grade（对齐原版 Max 语义） */
    private static void applyProper(Sk s, int[] apt) {
        if (s.propers == null || s.propers.length == 0) return;
        int best = Integer.MIN_VALUE;
        for (int[] p : s.propers) {
            int grade = s.grade;
            int style = p[2];
            if (style >= 1 && style <= 4) grade = applyProperLevel(grade, apt[style - 1]);
            int dist = p[1];
            if (dist >= 1 && dist <= 4) grade = applyProperLevel(grade, apt[3 + dist]);
            if (grade > best) best = grade;
        }
        s.grade = best;
    }

    /** 适性等级→倍率：S8/A7=1.1，B6/C5=0.9，D4/E3/F2=0.8，G1=0.7，无效=0 */
    private static int applyProperLevel(int grade, int level) {
        switch (level) {
            case 8: case 7: return (int) Math.round(grade * 1.1);
            case 6: case 5: return (int) Math.round(grade * 0.9);
            case 4: case 3: case 2: return (int) Math.round(grade * 0.8);
            case 1: return (int) Math.round(grade * 0.7);
            default: return 0;
        }
    }

    private static List<Sk> findByGroupExact(int groupId, int rarity) {
        List<Sk> group = protoByGroup.get(groupId);
        if (group == null) return null;
        List<Sk> out = new ArrayList<>();
        for (Sk s : group) if (s.rarity == rarity) out.add(s);
        return out;
    }

    private static int stp(int v) {
        if (v < 0) return 0;
        if (v >= statusToPoint.length) return statusToPoint[statusToPoint.length - 1];
        return statusToPoint[v];
    }

    private static double clamp(double v, double lo, double hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }
}
