package com.umaai.assistant.service;

import android.app.Service;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;

import org.json.JSONObject;

/**
 * 远端同步设置面板（App 内操作，无需 PC/adb）。
 *
 * 入口：长按浮窗右上角「简/详」小按钮。独立悬浮窗（可聚焦，键盘可输入），
 * 程序化构建（无 XML 资源，避免与浮窗布局改动冲突）。保存后写入应用私有
 * 配置（MODE_PRIVATE，与浮窗共用同一配置文件），并实时通知 RamenDecisionLogger
 * 生效；已积攒的 RAM 队列不受影响。
 *
 * 配置键名刻意中性（sync_on / sync_key），不写明用途；凭据不进日志、不进
 * 数据文件，仅存于本应用私有配置。
 */
final class GitHubUploadSettings {
    /** 与 FloatingWindowService 共用的私有配置文件名 */
    static final String PREFS_NAME = "ramen_overlay";
    /** 上传开关 */
    static final String PREF_ENABLED = "sync_on";
    /** 远端同步凭据（中性键名） */
    static final String PREF_CREDENTIAL = "sync_key";

    private static LinearLayout current;

    private GitHubUploadSettings() {}

    static synchronized void show(Service svc) {
        dismiss(svc);
        WindowManager wm = (WindowManager) svc.getSystemService(Service.WINDOW_SERVICE);
        try {
            current = buildPanel(svc);
            wm.addView(current, panelParams());
        } catch (Exception ignored) {
            current = null;
        }
    }

    static synchronized void dismiss(Service svc) {
        if (current == null) return;
        try {
            WindowManager wm = (WindowManager) svc.getSystemService(Service.WINDOW_SERVICE);
            wm.removeView(current);
        } catch (Exception ignored) {
        }
        current = null;
    }

    private static WindowManager.LayoutParams panelParams() {
        int type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
        WindowManager.LayoutParams p = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                type,
                // 不加 FLAG_NOT_FOCUSABLE：面板需要键盘输入；面板外触摸不拦截
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT);
        p.gravity = Gravity.CENTER;
        return p;
    }

    private static LinearLayout buildPanel(Service svc) {
        JSONObject st = status();
        boolean onNow = st.optBoolean("enabled", false);
        String credentialNow = svc.getSharedPreferences(PREFS_NAME, Service.MODE_PRIVATE)
                .getString(PREF_CREDENTIAL, "");

        LinearLayout root = new LinearLayout(svc);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(svc, 14);
        root.setPadding(pad, pad, pad, pad);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xF7FFFFFF);
        bg.setCornerRadius(dp(svc, 10));
        root.setBackground(bg);

        TextView title = new TextView(svc);
        title.setText("上传设置");
        title.setTextSize(15);
        title.setTextColor(0xFF202020);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title);

        Switch on = new Switch(svc);
        on.setText("开启上传");
        on.setTextSize(13);
        on.setTextColor(0xFF202020);
        on.setChecked(onNow);
        root.addView(on);

        TextView credLabel = new TextView(svc);
        credLabel.setText("同步密钥（设置页生成后粘贴到这里）");
        credLabel.setTextSize(11);
        credLabel.setTextColor(0xFF666666);
        root.addView(credLabel);

        EditText cred = new EditText(svc);
        cred.setHint("在此粘贴");
        cred.setSingleLine(true);
        cred.setTextSize(12);
        cred.setTextColor(0xFF202020);
        cred.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        cred.setText(credentialNow);
        cred.setTypeface(Typeface.MONOSPACE);
        LinearLayout.LayoutParams credLp = new LinearLayout.LayoutParams(
                dp(svc, 280), LinearLayout.LayoutParams.WRAP_CONTENT);
        root.addView(cred, credLp);

        TextView status = new TextView(svc);
        status.setTextSize(11);
        status.setTextColor(0xFF666666);
        status.setText(describeStatus(st));
        root.addView(status);

        LinearLayout row = new LinearLayout(svc);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        int margin = dp(svc, 4);
        bp.setMargins(margin, margin, margin, 0);

        Button save = button(svc, "保存");
        save.setOnClickListener(v -> {
            String val = cred.getText() == null ? "" : cred.getText().toString().trim();
            svc.getSharedPreferences(PREFS_NAME, Service.MODE_PRIVATE)
                    .edit()
                    .putString(PREF_CREDENTIAL, val)
                    .putBoolean(PREF_ENABLED, on.isChecked())
                    .apply();
            RamenDecisionLogger.setUploadConfig(val, on.isChecked());
            status.setText("已保存。" + describeStatus(status()));
        });
        row.addView(save, bp);

        Button clear = button(svc, "清除");
        clear.setOnClickListener(v -> {
            cred.setText("");
            svc.getSharedPreferences(PREFS_NAME, Service.MODE_PRIVATE)
                    .edit().remove(PREF_CREDENTIAL).apply();
            RamenDecisionLogger.setUploadConfig("", on.isChecked());
            status.setText("已清除。" + describeStatus(status()));
        });
        row.addView(clear, bp);

        Button close = button(svc, "关闭");
        close.setOnClickListener(v -> dismiss(svc));
        row.addView(close, bp);

        root.addView(row);
        return root;
    }

    private static Button button(Service svc, String text) {
        Button b = new Button(svc);
        b.setText(text);
        b.setTextSize(12);
        b.setAllCaps(false);
        return b;
    }

    private static JSONObject status() {
        try {
            return new JSONObject(RamenDecisionLogger.uploadStatus());
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    /** 队列/丢弃/最近结果一行文，与本地接口 GET /decision_log 同源。 */
    private static String describeStatus(JSONObject s) {
        if (s.length() == 0) return "状态不可用";
        String err = s.optString("last_error", "");
        String path = s.optString("last_upload_path", "");
        String recent;
        if (!err.isEmpty()) {
            recent = "最近错误：" + err;
        } else if (!path.isEmpty()) {
            recent = "最近上传：" + path.substring(path.lastIndexOf('/') + 1);
        } else {
            recent = "尚无上传记录";
        }
        return "队列 " + s.optInt("queue_lines") + "/" + s.optInt("queue_max")
                + " 条 · 累计丢弃 " + s.optInt("dropped_total")
                + " · 开关" + (s.optBoolean("enabled") ? "开" : "关")
                + " · 密钥" + (s.optBoolean("credential_set") ? "已设" : "未设")
                + "\n" + recent;
    }

    private static int dp(Service svc, int v) {
        return Math.round(v * svc.getResources().getDisplayMetrics().density);
    }
}
