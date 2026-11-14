package com.jxev.ai.client;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * 脚本闹钟触发接收器（2026-08-14 新增）。
 * AlarmManager 到点 → 本接收器 → ScriptEngine 后台执行（手机在后台/锁屏也能触发）。
 */
public class ScriptAlarmReceiver extends BroadcastReceiver {
    private static final String TAG = "ScriptAlarmReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        String name = intent != null ? intent.getStringExtra("script_name") : null;
        if (name == null || name.trim().isEmpty()) {
            Log.w(TAG, "闹钟广播缺少 script_name");
            return;
        }
        Log.i(TAG, "闹钟触发，开始执行脚本: " + name);
        ScriptEngine.getInstance(context).runScriptAsync(name.trim());
    }
}
