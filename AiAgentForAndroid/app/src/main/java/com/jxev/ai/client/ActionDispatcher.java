package com.jxev.ai.client;

import android.content.Context;
import android.util.Log;

import org.json.JSONObject;

import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 动作执行统一入口（2026-09-29 抽取）：MCPSocketClient（出站会话）与
 * MCPListenServer（wg 直连监听会话）共用。
 * script_* 走 ScriptEngine（手机端 gateway），reminder_* 走 ReminderOverlay（顶部常驻条），
 * 其余走 AdbActionExecutor（本地 adb shell）。
 */
public final class ActionDispatcher {
    private static final String TAG = "ActionDispatcher";

    public interface ResultCallback {
        void onResult(Map<String, Object> result, String error);
    }

    private static final ExecutorService EXECUTOR = Executors.newCachedThreadPool();

    private ActionDispatcher() {
    }

    public static void dispatch(Context ctx, final String actionType,
                                final JSONObject params, final ResultCallback callback) {
        EXECUTOR.execute(() -> {
            Map<String, Object> result = null;
            String error = null;
            try {
                if (actionType.startsWith("reminder_") || actionType.startsWith("hub_")) {
                    result = handleReminder(ctx, actionType, params);
                } else if (actionType.startsWith("script_")) {
                    result = ScriptEngine.getInstance(ctx).handleAction(actionType, params);
                } else if (actionType.startsWith("element_")) {
                    // 视觉元素（实验性，2026-11-12）：模板注册/匹配/点击，见 VisualElementActions
                    result = VisualElementActions.handle(ctx, actionType, params);
                } else {
                    result = new AdbActionExecutor(ctx).execute(actionType, params);
                }
                if (result == null) {
                    error = "执行器返回空结果";
                } else if (Boolean.FALSE.equals(result.get("success"))) {
                    Object e = result.get("error");
                    error = e != null ? String.valueOf(e) : "执行失败";
                }
            } catch (Exception e) {
                Log.e(TAG, "执行动作异常: " + actionType, e);
                error = "执行异常: " + e.getMessage();
            }
            callback.onResult(result, error);
        });
    }

    /**
     * 顶部常驻提醒条（2026-11-10）：reminder_show / reminder_clear。
     * 不走 AdbActionExecutor —— 它开头就要求 adb 可用，提醒条不该被 adb 断了连坐。
     *
     * reminder_show 参数：title（可空）、text、timeout（秒，0=一直挂着）
     * reminder_clear 参数：无
     */
    private static Map<String, Object> handleReminder(Context ctx, String actionType, JSONObject params) {
        Map<String, Object> out = new java.util.HashMap<>();
        if ("reminder_show".equals(actionType)) {
            String title = params == null ? null : params.optString("title", null);
            String text = params == null ? "" : params.optString("text", "");
            int timeout = params == null ? 0 : params.optInt("timeout", 0);
            boolean fullscreen = params != null && params.optBoolean("fullscreen", false);
            String todoId = params == null ? "" : params.optString("todo_id", "");
            boolean ok = com.jxev.ai.server.ReminderOverlay.show(ctx, title, text, timeout);
            boolean locked = isKeyguardLocked(ctx);
            boolean full = fullscreen || locked;
            if (full) {
                com.jxev.ai.server.ReminderActivity.show(ctx, title, text, todoId);
            }
            out.put("success", ok);
            out.put("shown", ok);
            out.put("timeout", timeout);
            out.put("fullscreen", full);
            out.put("keyguard", locked);
            if (!ok) {
                out.put("error", "悬浮窗权限未授予（SYSTEM_ALERT_WINDOW）或挂载失败");
            }
            return out;
        }
        if ("reminder_clear".equals(actionType)) {
            com.jxev.ai.server.ReminderOverlay.hide();
            out.put("success", true);
            out.put("cleared", true);
            return out;
        }
        if ("reminder_ack_drain".equals(actionType)) {
            return drainAcks(ctx);
        }
        if ("hub_sync".equals(actionType)) {
            // info-hub 常驻入口：PC 推来 open/doing 待办全量，落盘并更新顶部条
            org.json.JSONArray items = params == null ? null : params.optJSONArray("items");
            int count = params == null
                    ? (items == null ? 0 : items.length())
                    : params.optInt("count", items == null ? 0 : items.length());
            com.jxev.ai.server.HubSummary.save(ctx, items, count);
            // 入口 = 系统常驻通知；红条只用于强提醒（用户 m00728）
            com.jxev.ai.server.ReminderOverlay.hideSummaryBar(ctx);
            com.jxev.ai.server.HubNotifier.update(ctx, count);
            out.put("success", true);
            out.put("count", count);
            return out;
        }
        out.put("success", false);
        out.put("error", "未知提醒动作: " + actionType);
        return out;
    }

    /** 锁屏检测：acK 靠它决定要不要额外拉起全屏页。 */
    private static boolean isKeyguardLocked(Context ctx) {
        try {
            android.app.KeyguardManager km =
                    (android.app.KeyguardManager) ctx.getSystemService(Context.KEYGUARD_SERVICE);
            return km != null && km.isKeyguardLocked();
        } catch (Exception e) {
            return false;
        }
    }

    /** 取走并清空「知道了」回执，PC 侧据此回写 info-hub 待办状态。 */
    private static Map<String, Object> drainAcks(Context ctx) {
        Map<String, Object> out = new java.util.HashMap<>();
        try {
            android.content.SharedPreferences sp =
                    ctx.getSharedPreferences("reminder_state", Context.MODE_PRIVATE);
            String raw = sp.getString("acks", "[]");
            org.json.JSONArray arr = new org.json.JSONArray(raw == null || raw.isEmpty() ? "[]" : raw);
            sp.edit().putString("acks", "[]").apply();
            out.put("success", true);
            out.put("acks", arr);
            out.put("count", arr.length());
        } catch (Exception e) {
            out.put("success", false);
            out.put("error", "读取回执失败: " + e.getMessage());
        }
        return out;
    }
}
