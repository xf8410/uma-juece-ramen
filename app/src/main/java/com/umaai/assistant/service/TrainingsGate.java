package com.umaai.assistant.service;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Locale;

/**
 * 训练观测完整性调度（v0.3.6，bug1 防提前计算）。
 *
 * 背景：hlpatch 的推送有两种时序——
 * - 回合初的 summary：chara/ramen 有值，但 trainings 为空（还没进训练画面）
 * - 行动画面的 summary：trainings 携带每训练真实人头（v0.3.2 人头注入的数据源）
 *
 * 旧版 searchKey 不含 trainings 指纹：回合初先推 → 立刻触发一次「无人头」搜索；
 * 行动画面带着人头晚到 → key 相同不再重搜 → MCTS 对重放假盘面算完。
 *
 * 本类的职责（纯逻辑，可 JVM 单测）：
 * 1. searchKey 纳入 trainings 指纹 → 无人头/有人头是两个 key，人头到了会触发补搜
 * 2. 防抖窗口：回合切换后人头未到先等 TRAININGS_WAIT_MS（默认 3000ms），
 *    等到了用带人头的快照搜；等不到才放行无人头搜索（标 hasHeads=false ⚠）
 * 3. 状态机防抖：同一 key 的「等待→放行」只生效一次，重复 onNewSummary 不重复起线程
 *
 * 不碰 Rust、不碰 SO——纯粹是 Java 调度层的时序修正。
 */
final class TrainingsGate {

    /** 回合切换后等人头推送的窗口（毫秒）。人头推送通常在回合初 summary 后 1-2s 内。 */
    static final long TRAININGS_WAIT_MS = 3000;

    /** 训练观测（从 summary 的 trainings 数组提取的最小指纹） */
    static final class Obs {
        final boolean present;   // trainings 非空
        final int headsSum;      // 各训练 heads 之和
        final int shiningSum;    // 各训练 shining 之和

        Obs(boolean present, int headsSum, int shiningSum) {
            this.present = present;
            this.headsSum = headsSum;
            this.shiningSum = shiningSum;
        }

        static Obs fromSummary(JSONObject s) {
            JSONArray t = s == null ? null : s.optJSONArray("trainings");
            if (t == null || t.length() == 0) return new Obs(false, 0, 0);
            int heads = 0, shining = 0;
            for (int i = 0; i < t.length(); i++) {
                JSONObject o = t.optJSONObject(i);
                if (o == null) continue;
                heads += Math.max(o.optInt("heads", 0), 0);
                shining += Math.max(o.optInt("shining", 0), 0);
            }
            return new Obs(true, heads, shining);
        }
    }

    private String waitedKey = "";     // 已在等人头的 key（防重复起等待）
    private String releasedKey = "";   // 已放行无人头搜索的 key（只放行一次）
    private String searchedKey = "";   // 已实际触发过搜索的 key（防同 key 重复搜索）

    /**
     * 搜索去重键（v0.3.6）：直读 turn + month/half + vital + motivation +
     * 拉面状态 + **trainings 指纹**（人数/彩圈总和）。
     * 指纹区分「回合初无人头」与「行动画面有人头」两种推送 → 人头到达会
     * 产生新 key → 触发补搜；旧版两者同 key，人头到了也不重搜（bug1 根因）。
     */
    static String searchKey(JSONObject s) {
        JSONObject chara = s.optJSONObject("chara");
        JSONObject stats = s.optJSONObject("stats");
        JSONObject c = chara != null ? chara : stats;
        JSONObject r = s.optJSONObject("ramen");
        Obs obs = Obs.fromSummary(s);

        int turn = s.has("turn") ? s.optInt("turn", -1) : -1;
        return turn + ":" + s.optInt("month") + ":" + s.optInt("half") + ":" +
               (c == null ? "" : c.optInt("vital") + ":" + c.optString("motivation")) + ":" +
               (r == null ? "" : r.optInt("checkpoint_pt") + ":" + r.optString("sozai")) + ":" +
               (obs.present ? obs.headsSum + "h" + obs.shiningSum + "s" : "noT");
    }

    /**
     * 回合新推送进入时调用。返回本回合应采取的动作：
     * - WAIT：人头未到且在防抖窗口内 → 先不搜，等人头推送（onTrainingsArrived 再触发）
     * - RUN_NOW：可以直接搜（带人头 / 或窗口超时放行无人头）
     * - SKIP：同回合已处理过（重复推送/无人头已放行过/有人头同 key 已搜过，
     *   见 markSearched）
     *
     * @param key       searchKey(s) 的结果
     * @param obs       trainings 观测
     * @param now       当前毫秒
     */
    Decision onNewSummary(String key, Obs obs, long now) {
        // 有人头：直接可搜（旧逻辑里这本来就是最佳时机）
        if (obs.present) {
            waitedKey = "";
            // v0.3.6 防重复搜索：同 key 已触发过搜索 → SKIP。
            // 修复两类循环触发——①搜索完成回调 render(fresh,"搜索完成") 带同一条
            // 有人头 summary 重新进入本方法再次 RUN_NOW；②trainings 非空但人头
            // 全 0 时 present 恒为 true，同 key 无条件 RUN_NOW 反复命中循环搜索。
            // key 变化（人头晚到、回合推进、人头数变化）不含在此列，照常触发。
            if (key.equals(searchedKey)) return Decision.skip();
            return Decision.runNow(true);
        }
        // 无人头：
        // - 该回合从未见过且没等待过 → 进入等待窗口
        if (!key.equals(waitedKey) && !key.equals(releasedKey)) {
            waitedKey = key;
            return Decision.waitForHeads(now + TRAININGS_WAIT_MS);
        }
        // - 已在等待且窗口未超时 → 继续等（重复推送）
        // 调用方持有 deadline 判断超时；这里对「等待中重复推送」返回 SKIP
        return Decision.skip();
    }

    /**
     * 等待窗口超时回调：无人头放行（每回合至多一次）。
     * 返回 true 表示本次允许无人头搜索（调用方需在结果上标 hasHeads=false ⚠）。
     */
    boolean releaseWithoutTrainings(String key) {
        if (key.equals(waitedKey) && !key.equals(releasedKey)) {
            releasedKey = key;
            waitedKey = "";
            return true;
        }
        return false;
    }

    /** 人头推送到达（等待期间）：放行带人头搜索 */
    void onTrainingsArrived() {
        waitedKey = "";
    }

    /**
     * 标记该 key 的搜索已实际触发（v0.3.6 防重复搜索）。
     * 所有触发搜索的路径（onNewSummary 的 RUN_NOW、超时放行的无人头搜索）
     * 统一在 FloatingWindowService.triggerSearch 入口调用本方法打标——
     * 之后同 key 再次进入 onNewSummary 一律 SKIP；key 变化（人头晚到、
     * 回合推进）产生新 key 不受影响，照常触发。
     */
    void markSearched(String key) {
        searchedKey = key;
    }

    /** 是否对该 key 已放行过无人头搜索 */
    boolean releasedWithoutTrainings(String key) {
        return key.equals(releasedKey);
    }

    /** onNewSummary 的结果 */
    static final class Decision {
        enum Kind { WAIT, RUN_NOW, SKIP }
        final Kind kind;
        final boolean hasHeads;   // RUN_NOW 时：本次搜索是否带人头
        final long waitDeadline;  // WAIT 时：超时时刻

        private Decision(Kind k, boolean hasHeads, long waitDeadline) {
            this.kind = k;
            this.hasHeads = hasHeads;
            this.waitDeadline = waitDeadline;
        }

        static Decision runNow(boolean hasHeads) { return new Decision(Kind.RUN_NOW, hasHeads, 0); }
        // 注意：不能叫 wait(long)——与 Object.wait(long) 撞名（静态方法不能"覆盖"Object）
        static Decision waitForHeads(long deadline) { return new Decision(Kind.WAIT, false, deadline); }
        static Decision skip() { return new Decision(Kind.SKIP, false, 0); }

        boolean isWait() { return kind == Kind.WAIT; }
        boolean isRunNow() { return kind == Kind.RUN_NOW; }
        boolean isSkip() { return kind == Kind.SKIP; }
    }

    // ── 搜索失败重试（bug2 辅助）：同 key 失败后搜索次数减半重试一次 ──

    /** 失败重试状态：key → 已重试次数 */
    private String failedKey = "";
    private int failedCount = 0;

    /** 记录一次搜索失败 */
    void onSearchFailed(String key) {
        if (key.equals(failedKey)) failedCount++;
        else { failedKey = key; failedCount = 1; }
        // 失败后解除本回合防重标记：bug2 的减半重试依赖「搜索完成后 render
        // 回到 onNewSummary」再触发，不解除会把失败重试拦死（行为回归）
        if (key.equals(searchedKey)) searchedKey = "";
    }

    /** 该 key 失败了几次（0 = 没失败过） */
    int failureCount(String key) {
        return key.equals(failedKey) ? failedCount : 0;
    }

    /** 重试时的搜索次数：失败 N 次后 = 原始次数 / 2^N（下限 32，防抖到不可用） */
    static int retrySearchN(int baseN, int failures) {
        int n = baseN;
        for (int i = 0; i < failures; i++) n /= 2;
        return Math.max(n, 32);
    }

    /** ⚠ 提示行：无人头搜索的标注 */
    static String headsWarning(boolean hasHeads) {
        return hasHeads ? "" : " ⚠无人头(已等待" + (TRAININGS_WAIT_MS / 1000) + "s)";
    }

    /** 搜索失败提示行（bug2）：显式失败，含重试序号 */
    static String failureText(String error, int failures) {
        String base = error == null || error.isEmpty() ? "native无结果" : error;
        String s = "搜索失败";
        if (failures > 1) s += "(第" + failures + "次)";
        return s + "：" + base;
    }

    /** 格式化耗时（决策行用，Locale.US 防本地化数字） */
    static String fmtMs(long ms) {
        return ms >= 10000 ? String.format(Locale.US, "%.1fs", ms / 1000.0)
                           : String.format(Locale.US, "%dms", ms);
    }
}
