package com.jxev.ai.server;

import android.accessibilityservice.AccessibilityService;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;

import com.jxev.ai.client.UiTreeProvider;

/**
 * 常驻无障碍服务（2026-09-29 新增保活，2026-11-12 起兼任 UI 树来源）。
 *
 * 两个职责：
 * 1. 保活：HyperOS 灭屏冻结连"可感知"级进程都冻（悬浮窗+1px Activity+前台服务+wakelock 全部无效，
 *    实测 cgroup.freeze=1），唯一认可的豁免是被系统永久绑定的无障碍服务。
 * 2. UI 树：向 UiTreeProvider 暴露服务实例，get_ui_state / click(text=...) 直接读进程内节点树，
 *    替代原来 2.77s/次的 uiautomator dump（见 docs/INTERFACE.md「元素定位与点击确定性」）。
 *
 * 注意：本服务启用 canRetrieveWindowContent 后不再是"零功能"。用户可在系统设置随时关闭；
 * 关闭后所有调用方自动回退到 uiautomator dump（慢 2-3s，但功能不中断）。
 */
public class KeepAliveAccessibilityService extends AccessibilityService {
    private static final String TAG = "KeepAliveA11y";

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        UiTreeProvider.attach(this);
        Log.i(TAG, "无障碍服务已连接（保活 + UI 树）");
    }

    @Override
    public boolean onUnbind(android.content.Intent intent) {
        UiTreeProvider.detach(this);
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        UiTreeProvider.detach(this);
        super.onDestroy();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // 不消费事件：树是按需拉取的，避免事件风暴带来的开销
    }

    @Override
    public void onInterrupt() {
    }
}
