package com.jxev.ai.server;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

/**
 * info-hub 常驻通知（2026-11-10）。
 *
 * 用户口径：入口是「系统常驻消息」——通知栏里一条 ongoing 通知「INFO-HUB · N 条待处理」，
 * 点开进 HubListActivity；红条只用于强提醒，不再当入口。
 * 这条通知：低打扰（IMPORTANCE_LOW，不响不震）、不可划掉（ongoing）、只在数量变化时更新。
 */
public final class HubNotifier {
    private static final String TAG = "HubNotifier";
    private static final String CHANNEL_ID = "hub_entry";
    private static final int NOTIF_ID = 41001;

    private HubNotifier() {
    }

    public static void update(Context ctx, int count) {
        try {
            NotificationManager nm =
                    (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) {
                return;
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                NotificationChannel ch = new NotificationChannel(
                        CHANNEL_ID, "info-hub 待办入口", NotificationManager.IMPORTANCE_LOW);
                ch.setShowBadge(true);
                ch.setDescription("常驻显示 info-hub 待处理条数，点开看全部");
                nm.createNotificationChannel(ch);
            }
            if (count <= 0) {
                nm.cancel(NOTIF_ID);
                return;
            }
            Intent open = new Intent(ctx, HubListActivity.class);
            open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                flags |= PendingIntent.FLAG_IMMUTABLE;
            }
            PendingIntent pi = PendingIntent.getActivity(ctx, 0, open, flags);

            Notification.Builder b = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                    ? new Notification.Builder(ctx, CHANNEL_ID)
                    : new Notification.Builder(ctx);
            b.setSmallIcon(ctx.getApplicationInfo().icon)
                    .setContentTitle("INFO-HUB · " + count + " 条待处理")
                    .setContentText("点开看全部")
                    .setOngoing(true)
                    .setOnlyAlertOnce(true)
                    .setShowWhen(false)
                    .setCategory(Notification.CATEGORY_STATUS)
                    .setContentIntent(pi);
            nm.notify(NOTIF_ID, b.build());
        } catch (Exception e) {
            Log.w(TAG, "常驻通知更新失败", e);
        }
    }
}
