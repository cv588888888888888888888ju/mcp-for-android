package com.jxev.ai.server;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

import com.jxev.ai.client.ScriptEngine;

/**
 * 开机自启：BOOT_COMPLETED → startForegroundService(MCPService) + 重新注册脚本闹钟。
 */
public class BootReceiver extends BroadcastReceiver {
    private static final String TAG = "BootReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent != null ? intent.getAction() : null;
        Log.i(TAG, "收到广播: " + action);
        if (Intent.ACTION_BOOT_COMPLETED.equals(action)) {
            Intent svc = new Intent(context, MCPService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(svc);
            } else {
                context.startService(svc);
            }
            // 重启后脚本定时不丢：重注册全部启用脚本的 AlarmManager 闹钟
            ScriptEngine.getInstance(context).rescheduleAll();
            // 看门狗自续：MIUI 拦 START_STICKY 重启的场景由周期闹钟兜底
            WatchdogReceiver.schedule(context);
            Log.i(TAG, "已请求启动 MCPService（开机自启）并重注册脚本闹钟");
        }
    }
}
