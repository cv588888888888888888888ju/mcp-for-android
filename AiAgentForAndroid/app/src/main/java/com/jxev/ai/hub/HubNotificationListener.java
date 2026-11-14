package com.jxev.ai.hub;

import android.app.Notification;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.util.Log;

/**
 * 信息中枢入口（2026-10-24）：监听全机通知，统一落 HubStore。
 * 取代原 Lifestate 的通知落库能力（同一条路：事件驱动 + 本地库），因此不再依赖 adb 常通。
 */
public class HubNotificationListener extends NotificationListenerService {
    private static final String TAG = "HubNotify";
    private static volatile HubNotificationListener instance;

    @Override
    public void onListenerConnected() {
        instance = this;
        Log.i(TAG, "通知监听已连接");
    }

    @Override
    public void onListenerDisconnected() {
        if (instance == this) {
            instance = null;
        }
        Log.i(TAG, "通知监听已断开");
    }

    /** 无 adb 通道时读当前活动通知（AdbActionExecutor.getNotifications 兜底）。 */
    public static StatusBarNotification[] activeNotificationsOrEmpty() {
        HubNotificationListener s = instance;
        if (s == null) {
            return new StatusBarNotification[0];
        }
        try {
            StatusBarNotification[] arr = s.getActiveNotifications();
            return arr == null ? new StatusBarNotification[0] : arr;
        } catch (Throwable t) {
            Log.w(TAG, "读取活动通知失败", t);
            return new StatusBarNotification[0];
        }
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        if (sbn == null) return;
        Notification n = sbn.getNotification();
        if (n == null) return;

        final String pkg = sbn.getPackageName();
        if (getPackageName().equals(pkg)) return;

        // 常驻型通知（音乐、下载进度、前台服务）不入中枢，避免噪音
        int flags = n.flags;
        if ((flags & Notification.FLAG_ONGOING_EVENT) != 0
                || (flags & Notification.FLAG_FOREGROUND_SERVICE) != 0) {
            return;
        }
        // 群通知汇总行通常没有正文，跳过
        if (n.getGroup() != null && (flags & Notification.FLAG_GROUP_SUMMARY) != 0) {
            return;
        }

        Bundle ex = n.extras;
        if (ex == null) return;
        String title = str(ex.getCharSequence(Notification.EXTRA_TITLE));
        String text = str(ex.getCharSequence(Notification.EXTRA_TEXT));
        if (text.isEmpty()) text = str(ex.getCharSequence(Notification.EXTRA_BIG_TEXT));
        String sub = str(ex.getCharSequence(Notification.EXTRA_SUB_TEXT));
        String cat = n.category;
        String raw = str(ex.getCharSequence(Notification.EXTRA_TEXT_LINES));

        HubStore.get(this).insert(
                sbn.getPostTime() > 0 ? sbn.getPostTime() : System.currentTimeMillis(),
                pkg, appLabel(pkg), title, text, sub, cat, sbn.getKey(), raw);
    }

    private String appLabel(String pkg) {
        try {
            PackageManager pm = getPackageManager();
            ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
            CharSequence label = pm.getApplicationLabel(ai);
            return label != null ? label.toString() : pkg;
        } catch (Exception e) {
            return pkg;
        }
    }

    private static String str(CharSequence cs) {
        return cs == null ? "" : cs.toString().trim();
    }
}
