package com.jxev.ai.server;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

/**
 * 服务看门狗（2026-09-29 新增）：
 * MIUI 会拦掉 START_STICKY 的进程重启（实测杀掉后不自动拉起）。
 * 周期闹钟（默认 10 分钟）到点检查 MCPService 是否在跑，不在则重新拉起；
 * 闹钟由系统 AlarmManager 持有，进程被杀也能触发本接收器 → 进程复活。
 * 接收器每次触发后自行续订下一个周期。
 */
public class WatchdogReceiver extends BroadcastReceiver {
    private static final String TAG = "WatchdogReceiver";
    private static final long INTERVAL_MS = 10 * 60 * 1000L;
    private static final int REQUEST_CODE = 2001;

    public static void schedule(Context context) {
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (am == null) {
            Log.e(TAG, "AlarmManager 为 null，看门狗不可用");
            return;
        }
        long triggerAt = System.currentTimeMillis() + INTERVAL_MS;
        PendingIntent pi = buildPendingIntent(context);
        try {
            if (canScheduleExactAlarms(am)) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi);
            } else {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi);
            }
            Log.i(TAG, "看门狗已排定，下次检查: " + triggerAt);
        } catch (SecurityException e) {
            Log.e(TAG, "精确闹钟权限被拒，看门狗退化为一次性", e);
        }
    }

    private static PendingIntent buildPendingIntent(Context context) {
        Intent intent = new Intent(context, WatchdogReceiver.class);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        return PendingIntent.getBroadcast(context, REQUEST_CODE, intent, flags);
    }

    private static boolean canScheduleExactAlarms(AlarmManager am) {
        // API 31（S）起才有 canScheduleExactAlarms；compileSdk 30 无 VERSION_CODES.S，用整型常量
        if (Build.VERSION.SDK_INT < 31) {
            return true;
        }
        try {
            java.lang.reflect.Method m = AlarmManager.class.getMethod("canScheduleExactAlarms");
            Object r = m.invoke(am);
            return r instanceof Boolean && (Boolean) r;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        boolean alive = MCPService.isRunning();
        Log.i(TAG, "看门狗触发，MCPService 存活=" + alive);
        if (!alive) {
            Intent svc = new Intent(context, MCPService.class);
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(svc);
                } else {
                    context.startService(svc);
                }
                Log.i(TAG, "已重新拉起 MCPService");
            } catch (Exception e) {
                Log.e(TAG, "拉起 MCPService 失败", e);
            }
        }
        // 自续订，保证看门狗永远在途
        schedule(context);
    }
}
