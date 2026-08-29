package com.umaai.assistant.service;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.View;
import org.json.JSONArray;

/**
 * 候选条形图（小黑板「决策理由」的图形版，v0.3.2）。
 *
 * 每个候选一行（Canvas 横条）：
 * <pre>
 * #0 不吃面      ▇▇▇▇▇▇▇░░  -999
 * #1 吃面/函馆-耐 ▇▇▇▇▇▇▇▇▇  66972   ← 选中（绿色）
 * #2 吃面/东京-智 ▇▇▇▇▇▇░░░  -731
 * </pre>
 *
 * - 标签：候选名经 RamenBoardText.translate 中文化
 * - 比例条：候选 mean 分 / 最高候选 mean 分（选中绿 #55FF99，其余灰蓝）
 * - 差值：选中项显示自身 mean，其余显示相对选中的差（与原文字版口径一致）
 *
 * 数据源：Rust DecisionOutput 的 candidate_displays / candidate_scores / action_index
 * （FloatingWindowService.renderSearchResult 喂入）。
 *
 * 无候选或评分全 0（手写兜底/解析失败）时整图 GONE，不占浮窗空间。
 * 纯 Canvas 绘制，无 Android 主题依赖，可在单元测试外独立实例化。
 */
public final class BoardChartsView extends View {
    private static final int MAX_ROWS = 8;
    private static final int COLOR_SELECTED = 0xFF55FF99;
    private static final int COLOR_BAR = 0xFF8FA3B8;
    private static final int COLOR_LABEL = 0xFFDDDDDD;
    private static final int COLOR_DELTA = 0xFF99A6B3;
    private static final int COLOR_TRACK = 0x22FFFFFF;
    private static final float MIN_BAR_RATIO = 0.05f;

    private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint deltaPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint barPaint = new Paint();
    private final Paint trackPaint = new Paint();

    private String[] labels = new String[0];
    private double[] scores = new double[0];
    private int bestIndex = -1;

    private float rowHeightPx;
    private float barHeightPx;
    private float gapPx;

    public BoardChartsView(Context context) {
        this(context, null);
    }

    public BoardChartsView(Context context, AttributeSet attrs) {
        super(context, attrs);
        float textPx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 9,
                getResources().getDisplayMetrics());
        labelPaint.setTextSize(textPx);
        labelPaint.setColor(COLOR_LABEL);
        deltaPaint.setTextSize(textPx);
        deltaPaint.setColor(COLOR_DELTA);
        barPaint.setStyle(Paint.Style.FILL);
        trackPaint.setColor(COLOR_TRACK);
        trackPaint.setStyle(Paint.Style.FILL);
        rowHeightPx = dp(13);
        barHeightPx = dp(5);
        gapPx = dp(3);
        setVisibility(GONE);
    }

    /** 喂入一帧候选数据；无效输入（空数组/评分全 0）时整图隐藏。 */
    public void setCandidates(JSONArray displays, JSONArray scoresArr, int actionIndex) {
        if (displays == null || scoresArr == null) {
            clear();
            return;
        }
        int len = Math.min(displays.length(), scoresArr.length());
        if (len == 0) {
            clear();
            return;
        }

        // 全 0 说明评分没拿到（手写兜底/解析失败），比例条与差值没有意义
        boolean anyScore = false;
        for (int i = 0; i < len; i++) {
            if (scoresArr.optDouble(i, 0.0) != 0.0) {
                anyScore = true;
                break;
            }
        }
        if (!anyScore) {
            clear();
            return;
        }

        int n = Math.min(len, MAX_ROWS);
        String[] newLabels = new String[n];
        double[] newScores = new double[n];
        for (int i = 0; i < n; i++) {
            newLabels[i] = "#" + i + " " + RamenBoardText.translate(displays.optString(i, "?"));
            newScores[i] = scoresArr.optDouble(i, 0.0);
        }
        labels = newLabels;
        scores = newScores;
        bestIndex = (actionIndex >= 0 && actionIndex < n) ? actionIndex : -1;

        setVisibility(VISIBLE);
        requestLayout();
        invalidate();
    }

    /** 清空并隐藏（等待搜索/搜索失败/非拉面杯时调用）。 */
    public void clear() {
        labels = new String[0];
        scores = new double[0];
        bestIndex = -1;
        setVisibility(GONE);
        invalidate();
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int rows = labels.length;
        int desired = rows == 0 ? 0
                : (int) (getPaddingTop() + rows * rowHeightPx + getPaddingBottom() + 0.5f);
        setMeasuredDimension(
                resolveSize(getSuggestedMinimumWidth(), widthSpec),
                resolveSize(Math.max(desired, getSuggestedMinimumHeight()), heightSpec));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int n = labels.length;
        if (n == 0 || getWidth() <= 0) return;
        float w = getWidth();

        double best = (bestIndex >= 0 && bestIndex < n) ? scores[bestIndex] : 0.0;

        // 差值列：选中项显示自身 mean，其余显示相对选中的差
        String[] deltas = new String[n];
        float deltaW = 0f;
        for (int i = 0; i < n; i++) {
            deltas[i] = (i == bestIndex)
                    ? String.format("%.0f", scores[i])
                    : String.format("%+.0f", scores[i] - best);
            deltaW = Math.max(deltaW, deltaPaint.measureText(deltas[i]));
        }

        // 标签列宽：内容自适应，最多占 45%（窄浮窗时截断）
        float labelW = 0f;
        for (String l : labels) labelW = Math.max(labelW, labelPaint.measureText(l));
        labelW = Math.min(labelW, w * 0.45f);

        float barX = labelW + gapPx;
        float barEnd = w - deltaW - gapPx;
        float barW = Math.max(barEnd - barX, dp(8));
        float top = getPaddingTop();

        float maxScore = 0f;
        for (double s : scores) maxScore = Math.max(maxScore, (float) s);

        for (int i = 0; i < n; i++) {
            float cy = top + i * rowHeightPx + rowHeightPx / 2f;
            float textY = cy - (labelPaint.ascent() + labelPaint.descent()) / 2f;
            boolean selected = i == bestIndex;

            labelPaint.setColor(selected ? COLOR_SELECTED : COLOR_LABEL);
            canvas.drawText(labels[i], 0f, textY, labelPaint);

            canvas.drawRect(barX, cy - barHeightPx / 2f, barX + barW,
                    cy + barHeightPx / 2f, trackPaint);
            if (maxScore > 0 && scores[i] > 0) {
                float ratio = (float) (scores[i] / maxScore);
                barPaint.setColor(selected ? COLOR_SELECTED : COLOR_BAR);
                canvas.drawRect(barX, cy - barHeightPx / 2f,
                        barX + barW * Math.max(MIN_BAR_RATIO, ratio),
                        cy + barHeightPx / 2f, barPaint);
            }

            deltaPaint.setColor(selected ? COLOR_SELECTED : COLOR_DELTA);
            canvas.drawText(deltas[i], w - deltaPaint.measureText(deltas[i]), textY, deltaPaint);
        }
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }
}
