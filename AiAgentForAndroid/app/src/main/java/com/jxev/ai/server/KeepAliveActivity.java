package com.jxev.ai.server;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;

/**
 * 1px 透明保活 Activity（2026-09-29 新增，同 VMOS 时代中国区 ROM 标准做法）：
 * 灭屏瞬间由 MCPService 拉起（进程持 SYSTEM_ALERT_WINDOW 可后台启动 Activity），
 * 进程进入 TOP_SLEEPING（可见级）——cgroup 冻结器按 AOSP 规则不冻结 TOP 进程；
 * 亮屏瞬间自毁。视觉上 1px 透明窗体，用户无感。
 */
public class KeepAliveActivity extends Activity {
    private static final String TAG = "KeepAliveActivity";

    private final BroadcastReceiver screenOnReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            Log.i(TAG, "SCREEN_ON → 自毁");
            finish();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // 1px 透明视窗
        View v = new View(this);
        v.setBackgroundColor(0x00000000);
        setContentView(v);
        getWindow().setLayout(1, 1);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true);
            setTurnScreenOn(false);
        }
        registerReceiver(screenOnReceiver, new IntentFilter(Intent.ACTION_SCREEN_ON));
        Log.i(TAG, "1px 保活 Activity 已显示（灭屏保活）");
    }

    @Override
    protected void onDestroy() {
        try {
            unregisterReceiver(screenOnReceiver);
        } catch (Exception ignored) {
        }
        Log.i(TAG, "1px 保活 Activity 已销毁");
        super.onDestroy();
    }
}
