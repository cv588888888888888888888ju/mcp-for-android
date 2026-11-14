package com.jxev.ai.server;

import android.content.Context;
import android.graphics.PixelFormat;
import android.os.Build;
import android.provider.Settings;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;

/**
 * 悬浮窗保活（2026-09-29 新增，用户指定方案，同 VMOS 做法）：
 * 挂一个 2px 透明 TYPE_APPLICATION_OVERLAY 窗口，进程即被系统视为
 * "可感知(perceptible)"——高于"服务"级、永不进入 Cached 组，从而绕过
 * MIUI 灭屏冻结（前台服务/wakelock/Doze 白名单均不够，实测见 docs）。
 */
public final class KeepAliveOverlay {
    private static final String TAG = "KeepAliveOverlay";
    private static WindowManager windowManager;
    private static View overlayView;

    private KeepAliveOverlay() {
    }

    /** 悬浮窗权限是否已授予。 */
    public static boolean canShow(Context ctx) {
        return Settings.canDrawOverlays(ctx.getApplicationContext());
    }

    /** 显示保活悬浮窗（2px 透明，置顶不可交互）。返回是否成功。 */
    public static synchronized boolean show(Context ctx) {
        if (overlayView != null) {
            return true;
        }
        Context app = ctx.getApplicationContext();
        if (!canShow(app)) {
            Log.w(TAG, "悬浮窗权限未授予（SYSTEM_ALERT_WINDOW），保活悬浮窗不可用");
            return false;
        }
        try {
            WindowManager wm = (WindowManager) app.getSystemService(Context.WINDOW_SERVICE);
            int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                    ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                    : WindowManager.LayoutParams.TYPE_PHONE;
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    2, 2, type,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.TOP | Gravity.START;
            lp.x = 0;
            lp.y = 0;
            View v = new View(app);
            v.setBackgroundColor(0x00000000);
            wm.addView(v, lp);
            windowManager = wm;
            overlayView = v;
            Log.i(TAG, "保活悬浮窗已挂载（2px 透明，进程=可感知级）");
            return true;
        } catch (Exception e) {
            Log.e(TAG, "悬浮窗挂载失败", e);
            return false;
        }
    }

    /** 移除保活悬浮窗。 */
    public static synchronized void hide() {
        try {
            if (overlayView != null && windowManager != null) {
                windowManager.removeView(overlayView);
            }
        } catch (Exception ignored) {
        }
        overlayView = null;
        windowManager = null;
        Log.i(TAG, "保活悬浮窗已移除");
    }
}
