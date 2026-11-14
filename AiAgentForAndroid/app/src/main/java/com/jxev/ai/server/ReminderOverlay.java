package com.jxev.ai.server;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 顶部常驻提醒条（2026-11-10）。
 *
 * 形态：TYPE_APPLICATION_OVERLAY 悬浮窗，贴在「页面顶端」（状态栏下方），
 * 全宽、高度随文字，红底白字，带一个 ✕。它不是通知：不点掉就一直挂在那儿，
 * 切到别的 app 也在，灭屏再亮屏还在。
 *
 * 常驻：show() 把状态落 SharedPreferences，MCPService.onCreate 调 restore() 重放，
 * 所以进程被杀/重启后条会自己回来。
 *
 * 边界（写清楚，避免以后误判）：
 *  · 悬浮窗盖不住状态栏和锁屏（系统层在它上面）；锁屏要另用 showWhenLocked 的 Activity。
 *  · 依赖 SYSTEM_ALERT_WINDOW；权限被撤 → 挂载失败，返回 false。
 *  · 进程被冻结时不会自己弹，只能亮屏后恢复显示；常驻靠 KeepAliveAccessibilityService。
 */
public final class ReminderOverlay {
    private static final String TAG = "ReminderOverlay";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private static final String PREF = "reminder_state";
    private static final String K_SHOWN = "shown";
    private static final String K_TITLE = "title";
    private static final String K_TEXT = "text";
    private static final String K_TIMEOUT = "timeout";
    private static final String K_SET_AT = "set_at";

    private static WindowManager windowManager;
    private static FrameLayout rootView;
    private static TextView titleView;
    private static TextView bodyView;
    private static Context appContext;
    private static boolean visible;
    private static Runnable autoHide;

    private ReminderOverlay() {
    }

    /** 悬浮窗权限是否已授予。 */
    public static boolean canShow(Context ctx) {
        return Settings.canDrawOverlays(ctx.getApplicationContext());
    }

    public static synchronized boolean isShowing() {
        return visible;
    }

    /**
     * 显示/更新顶部提醒条，并落盘（进程重启后 restore 会重放）。
     *
     * @param title      标题（可空，默认「提醒」）
     * @param text       正文
     * @param timeoutSec >0 则到点自动收起；0 = 一直挂着直到点 ✕
     * @return 是否已受理（权限缺失或异常返回 false）
     */
    public static boolean show(final Context ctx, final String title, final String text,
                               final int timeoutSec) {
        final Context app = ctx.getApplicationContext();
        appContext = app;
        if (!canShow(app)) {
            Log.w(TAG, "悬浮窗权限未授予（SYSTEM_ALERT_WINDOW），提醒条不可用");
            return false;
        }
        saveState(app, title, text, timeoutSec);
        MAIN.post(() -> {
            try {
                showOnMain(app, title, text, timeoutSec);
            } catch (Exception e) {
                Log.e(TAG, "提醒条挂载失败", e);
            }
        });
        return true;
    }

    /**
     * 清掉旧版「INFO-HUB · N 条待处理」悬浮条（2026-11-10 之后入口改为系统常驻通知）。
     * 只认标题前缀，不误伤真正的强提醒条。
     */
    public static void hideSummaryBar(Context ctx) {
        try {
            SharedPreferences sp = ctx.getApplicationContext()
                    .getSharedPreferences(PREF, Context.MODE_PRIVATE);
            String title = sp.getString(K_TITLE, "");
            if (sp.getBoolean(K_SHOWN, false) && title != null && title.startsWith("INFO-HUB ·")) {
                hide();
            }
        } catch (Exception ignored) {
        }
    }

    /** 用户点 ✕ / 外部 reminder_clear：收起并清掉落盘状态。 */
    public static void hide() {
        if (appContext != null) {
            clearState(appContext);
        }
        MAIN.post(ReminderOverlay::hideOnMain);
    }

    /** 进程启动时重放上次没撤的提醒条（MCPService.onCreate 调）。 */
    public static void restore(Context ctx) {
        final Context app = ctx.getApplicationContext();
        appContext = app;
        if (!canShow(app)) {
            return;
        }
        SharedPreferences sp = app.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        if (!sp.getBoolean(K_SHOWN, false)) {
            return;
        }
        String title = sp.getString(K_TITLE, "");
        String text = sp.getString(K_TEXT, "");
        int timeout = sp.getInt(K_TIMEOUT, 0);
        long setAt = sp.getLong(K_SET_AT, 0L);
        if (timeout > 0 && setAt > 0 && System.currentTimeMillis() - setAt > timeout * 1000L) {
            clearState(app);
            return;
        }
        int remain = timeout > 0 ? (int) ((timeout * 1000L - (System.currentTimeMillis() - setAt)) / 1000L) : 0;
        Log.i(TAG, "恢复提醒条: " + title);
        MAIN.post(() -> {
            try {
                showOnMain(app, title, text, Math.max(0, remain));
            } catch (Exception e) {
                Log.e(TAG, "提醒条恢复失败", e);
            }
        });
    }

    private static void saveState(Context app, String title, String text, int timeoutSec) {
        app.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
                .putBoolean(K_SHOWN, true)
                .putString(K_TITLE, title == null ? "" : title)
                .putString(K_TEXT, text == null ? "" : text)
                .putInt(K_TIMEOUT, timeoutSec)
                .putLong(K_SET_AT, System.currentTimeMillis())
                .apply();
    }

    private static void clearState(Context app) {
        app.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putBoolean(K_SHOWN, false).apply();
    }

    private static void showOnMain(Context app, String title, String text, int timeoutSec) {
        if (autoHide != null) {
            MAIN.removeCallbacks(autoHide);
            autoHide = null;
        }
        if (rootView != null) {
            updateText(title, text);
            if (timeoutSec > 0) {
                scheduleAutoHide(timeoutSec);
            }
            return;
        }

        WindowManager wm = (WindowManager) app.getSystemService(Context.WINDOW_SERVICE);
        if (wm == null) {
            return;
        }

        FrameLayout frame = new FrameLayout(app);
        int edge = dp(app, 6);
        frame.setPadding(edge, edge, edge, 0);

        LinearLayout bar = new LinearLayout(app);
        bar.setOrientation(LinearLayout.VERTICAL);
        bar.setPadding(dp(app, 12), dp(app, 8), dp(app, 12), dp(app, 10));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xF2C62828);
        bg.setCornerRadius(dp(app, 10));
        bar.setBackground(bg);

        LinearLayout head = new LinearLayout(app);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);

        TextView tvTitle = new TextView(app);
        tvTitle.setText(title == null || title.trim().isEmpty() ? "提醒" : title.trim());
        tvTitle.setTextColor(0xFFFFFFFF);
        tvTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        tvTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        tvTitle.setSingleLine(true);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        head.addView(tvTitle, titleLp);

        TextView tvClose = new TextView(app);
        tvClose.setText("✕");
        tvClose.setTextColor(0xFFFFFFFF);
        tvClose.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        tvClose.setPadding(dp(app, 10), 0, dp(app, 2), 0);
        tvClose.setOnClickListener(v -> hide());
        head.addView(tvClose, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView tvBody = new TextView(app);
        tvBody.setTextColor(0xFFFFEBEE);
        tvBody.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        tvBody.setPadding(0, dp(app, 4), 0, 0);
        tvBody.setText(text == null ? "" : text);

        bar.addView(head);
        bar.addView(tvBody, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        frame.addView(bar, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT));

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                        ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                        : WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                android.graphics.PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = 0;
        lp.y = 0;

        wm.addView(frame, lp);
        windowManager = wm;
        rootView = frame;
        titleView = tvTitle;
        bodyView = tvBody;
        visible = true;
        Log.i(TAG, "提醒条已挂载: " + title);

        if (timeoutSec > 0) {
            scheduleAutoHide(timeoutSec);
        }
    }

    private static void scheduleAutoHide(int timeoutSec) {
        if (autoHide != null) {
            MAIN.removeCallbacks(autoHide);
        }
        final Context app = appContext;
        autoHide = () -> {
            if (app != null) {
                clearState(app);
            }
            hideOnMain();
        };
        MAIN.postDelayed(autoHide, Math.max(1, timeoutSec) * 1000L);
    }

    private static void updateText(String title, String text) {
        if (titleView != null && title != null && !title.trim().isEmpty()) {
            titleView.setText(title.trim());
        }
        if (bodyView != null && text != null) {
            bodyView.setText(text);
        }
    }

    private static void hideOnMain() {
        if (autoHide != null) {
            MAIN.removeCallbacks(autoHide);
            autoHide = null;
        }
        try {
            if (rootView != null && windowManager != null) {
                windowManager.removeView(rootView);
            }
        } catch (Exception ignored) {
        }
        rootView = null;
        titleView = null;
        bodyView = null;
        windowManager = null;
        visible = false;
        Log.i(TAG, "提醒条已移除");
    }

    private static int dp(Context ctx, int value) {
        return Math.round(value * ctx.getResources().getDisplayMetrics().density);
    }
}