package com.jxev.ai.client;

import android.accessibilityservice.AccessibilityService;
import android.app.Notification;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Bundle;
import android.service.notification.StatusBarNotification;
import android.util.Base64;
import android.util.DisplayMetrics;
import android.util.Log;

import com.jxev.ai.hub.HubNotificationListener;
import com.jxev.ai.hub.HubStore;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 统一 actionType → 本地 adb shell 命令映射（2026-08-14 重构）。
 * 全部通过 AdbLocalClient.execShell（127.0.0.1:5555，本机 adbd）执行。
 */
public class AdbActionExecutor {
    private static final String TAG = "AdbActionExecutor";

    private final AdbLocalClient adb;
    private final Context appContext;

    public AdbActionExecutor(Context ctx) {
        this.adb = AdbLocalClient.getInstance(ctx);
        this.appContext = ctx.getApplicationContext();
    }

    /** 这些动作有进程内无障碍/系统 API 兜底，没有 adb 也能跑（2026-11-12 扩展）。 */
    private static boolean runsWithoutAdb(String actionType) {
        switch (actionType) {
            case "get_ui_state":
            case "click":
            case "long_click":
            case "wait_for_element":
            // 全局按键 / 手势 / 截屏：无障碍 performGlobalAction / dispatchGesture / takeScreenshot
            case "press_back":
            case "press_home":
            case "press_recents":
            case "swipe":
            case "scroll":
            case "screenshot":
            // 只读信息：PackageManager / BatteryManager / DisplayMetrics
            case "get_current_app":
            case "get_installed_apps":
            case "battery_info":
            case "get_screen_size":
            // 实验性：滚动行为判据（长截图 / 滚动定位用）
            case "scroll_probe":
            // 系统 API 兜底：Intent 启动 / 输入法 / 通知监听
            case "type_text":
            case "launch_app":
            case "open_url":
            case "get_notifications":
                return true;
            default:
                return false;
        }
    }

    /** 执行动作，返回结果 map。失败时 map 含 success=false + error。 */
    public Map<String, Object> execute(String actionType, JSONObject params) {
        // 无障碍动作不需要 adb 通道：get_ui_state / click / long_click / wait_for_element
        // 在 assets/adbkey 缺失时仍可用（点不动时再由 tap() 报 tapped=false）。
        if (adb == null && !runsWithoutAdb(actionType)) {
            return errorResult("AdbLocalClient 初始化失败（assets/adbkey 缺失或损坏）");
        }
        try {
            switch (actionType) {
                case "adb_shell":
                    return adbShell(params);
                case "tts_play":
                    return ttsPlay(params);
                case "launch_app":
                    return launchApp(params);
                case "click":
                    return tap(params, false);
                case "long_click":
                    return tap(params, true);
                case "swipe":
                    return swipe(params);
                case "type_text":
                    return typeText(params);
                case "scroll":
                    return scroll(params);
                case "scroll_probe":
                    return scrollProbe(params);
                case "press_back":
                    return globalKey("press_back", "4", AccessibilityService.GLOBAL_ACTION_BACK);
                case "press_home":
                    return globalKey("press_home", "3", AccessibilityService.GLOBAL_ACTION_HOME);
                case "press_recents":
                    return globalKey("press_recents", "187", AccessibilityService.GLOBAL_ACTION_RECENTS);
                case "screenshot":
                    return screenshot();
                case "get_ui_state":
                    return getUiState();
                case "get_installed_apps":
                    return getInstalledApps();
                case "get_notifications":
                    return getNotifications();
                case "dismiss_notification":
                    return dismissNotification(params);
                // ============ 开源手机 MCP 对标新增（全部 adb shell 实现，无无障碍依赖） ============
                case "get_screen_size":
                    return getScreenSizeInfo();
                case "open_url":
                    return openUrl(params);
                case "key_event":
                    return keyEventGeneric(params);
                case "volume":
                    return volumeControl(params);
                case "media_control":
                    return mediaControl(params);
                case "wifi":
                    return wifiToggle(params);
                case "bluetooth":
                    return bluetoothToggle(params);
                case "airplane_mode":
                    return airplaneToggle(params);
                case "brightness":
                    return brightness(params);
                case "get_current_app":
                    return getCurrentApp();
                case "channel_status":
                    return channelStatus();
                case "battery_info":
                    return batteryInfo();
                case "clear_app_data":
                    return clearAppData(params);
                case "uninstall_app":
                    return uninstallApp(params);
                case "install_app":
                    return installApp(params);
                case "file_read":
                    return fileRead(params);
                case "file_write":
                    return fileWrite(params);
                case "wait_for_element":
                    return waitForElement(params);
                case "set_rotation":
                    return setRotation(params);
                // 信息中枢（2026-10-24）：通知统一落库查询
                case "hub_query":
                    return hubQuery(params);
                case "hub_stats":
                    return hubStats();
                case "open_app_settings":
                    return openAppSettings(params);
                default:
                    return errorResult("未知 actionType: " + actionType);
            }
        } catch (JSONException e) {
            Log.e(TAG, "参数解析失败", e);
            return errorResult("参数解析失败: " + e.getMessage());
        }
    }

    // ================== action 实现 ==================

    /** tts_play：base64 解码 wav → 写缓存 → MediaPlayer（主线程）异步播放。 */
    private Map<String, Object> ttsPlay(JSONObject params) throws JSONException {
        String audioBase64 = params.optString("audio_base64", "");
        if (audioBase64.isEmpty()) {
            return errorResult("tts_play: 缺少 audio_base64");
        }
        String format = params.optString("format", "wav");
        byte[] wavData;
        try {
            wavData = Base64.decode(audioBase64, Base64.DEFAULT);
        } catch (IllegalArgumentException e) {
            Log.e(TAG, "tts_play base64 解码失败", e);
            return errorResult("tts_play: base64 解码失败: " + e.getMessage());
        }
        if (wavData.length < 4
                || wavData[0] != 'R' || wavData[1] != 'I'
                || wavData[2] != 'F' || wavData[3] != 'F') {
            return errorResult("tts_play: 音频数据不是 WAV（缺 RIFF 头）");
        }
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("message", "已开始播放 TTS 音频（格式=" + format
                + "，异步播放，完成/失败见 logcat）");
        result.put("audioBytes", wavData.length);
        result.put("async", true);
        TtsPlayer.getInstance().play(appContext, wavData, (success, msg) -> {
            Log.i(TAG, "tts_play 播放结果: success=" + success + " msg=" + msg);
        });
        return result;
    }

    private Map<String, Object> adbShell(JSONObject params) throws JSONException {
        String command = params.optString("command", "");
        if (command.trim().isEmpty()) {
            return errorResult("adb_shell: command 不能为空");
        }
        return toResult(adb.execShell(command), "adb_shell 执行失败");
    }

    private Map<String, Object> launchApp(JSONObject params) throws JSONException {
        String pkg = params.optString("packageName", "");
        if (pkg.isEmpty()) {
            pkg = params.optString("package_name", "");
        }
        if (pkg.isEmpty()) {
            return errorResult("launch_app: 缺少 packageName/package_name");
        }
        if (adb == null) {
            Intent intent = appContext.getPackageManager().getLaunchIntentForPackage(pkg);
            if (intent == null) {
                return errorResult("launch_app: 找不到启动入口 " + pkg);
            }
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                appContext.startActivity(intent);
            } catch (Throwable t) {
                return errorResult("launch_app 失败: " + t);
            }
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("success", true);
            result.put("source", "system");
            result.put("package", pkg);
            return result;
        }
        AdbLocalClient.Result r = adb.execShell("am start -n " + pkg);
        if (r.success && r.exitCode == 0 && !r.output.contains("Error")) {
            return toResult(r, "launch_app 失败");
        }
        // -n 需要组件名，裸包名回退 monkey 启动
        AdbLocalClient.Result r2 = adb.execShell(
                "monkey -p " + pkg + " -c android.intent.category.LAUNCHER 1");
        if (r2.success && !r2.output.contains("Error")) {
            return toResult(r2, "launch_app 失败");
        }
        return errorResult("launch_app 失败: " + pkg + "（am start: "
                + r.output.trim() + "；monkey: " + r2.output.trim() + "）");
    }

    /**
     * 点击/长按。定位顺序：坐标 → 无障碍树（精确→包含）→ uiautomator dump。
     * 结果里带 found / source / actionable / tapped / verified，见 docs/INTERFACE.md「元素定位与点击确定性」。
     */
    private Map<String, Object> tap(JSONObject params, boolean longPress) throws JSONException {
        UiTreeProvider.Match m = resolveMatch(params);
        if (m == null) {
            Map<String, Object> err = errorResult("未找到可点击目标（需 x/y 或可匹配的 selector/id/text/desc）");
            err.put("found", false);
            return err;
        }
        String snapBefore = UiTreeProvider.snapshot();
        // 优先用无障碍动作（ACTION_CLICK），它不注入手势坐标、不需要 adb，
        // 对 WebView/Compose 节点也更可靠；被拒时才回退 input tap。
        boolean axPerformed = false;
        if (!"coord".equals(m.source) && UiTreeProvider.isReady()) {
            axPerformed = UiTreeProvider.performClick(m.id, m.text, m.desc, longPress);
        }
        Map<String, Object> result;
        if (axPerformed) {
            result = new HashMap<>();
            result.put("success", true);
            result.put("message", longPress ? "长按已执行（无障碍动作）" : "点击已执行（无障碍动作）");
            result.put("tapped", true);
        } else if (adb == null) {
            // 报「点不动」而不是报错：调用方要的是可判定的字段，不是异常。
            result = new HashMap<>();
            result.put("success", true);
            result.put("tapped", false);
            result.put("note", m.actionable
                    ? "无障碍动作未被受理（节点声明可点但拒绝 ACTION_CLICK）"
                    : "目标存在但不可点击（自身与 3 层内祖先都没有 clickable），且无 adb 通道可回退手势");
        } else {
            String cmd;
            if (longPress) {
                cmd = "input swipe " + m.centerX() + " " + m.centerY() + " "
                        + m.centerX() + " " + m.centerY() + " 800";
            } else {
                cmd = "input tap " + m.centerX() + " " + m.centerY();
            }
            AdbLocalClient.Result r = adb.execShell(cmd);
            result = toResult(r, longPress ? "长按失败" : "点击失败");
            result.put("tapped", r.success);
        }
        result.put("found", true);
        result.put("source", m.source);
        result.put("x", m.centerX());
        result.put("y", m.centerY());
        result.put("target", m.label());
        result.put("actionable", m.actionable);
        result.put("scrollable", m.scrollable);
        UiTreeProvider.ScrollState ss = UiTreeProvider.scrollState();
        result.put("pageScrollable", ss.scrollable);
        result.put("pageCanScrollForward", ss.canForward);
        result.put("pageCanScrollBackward", ss.canBackward);
        result.put("verified", verifyAfterTap(snapBefore));
        return result;
    }

    /** 点击后 350ms 对比界面文本指纹：true=界面有变化，false=没变化，unknown=拿不到树。 */
    private Object verifyAfterTap(String before) {
        if (before == null) {
            return "unknown";
        }
        try {
            Thread.sleep(350);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "unknown";
        }
        String after = UiTreeProvider.snapshot();
        if (after == null) {
            return "unknown";
        }
        return !after.equals(before);
    }

    private Map<String, Object> swipe(JSONObject params) throws JSONException {
        if (!params.has("x1") || !params.has("y1") || !params.has("x2") || !params.has("y2")) {
            return errorResult("swipe: 需要 x1/y1/x2/y2");
        }
        int duration = params.optInt("duration", 300);
        int x1 = params.getInt("x1");
        int y1 = params.getInt("y1");
        int x2 = params.getInt("x2");
        int y2 = params.getInt("y2");
        if (adb == null) {
            boolean ok = UiTreeProvider.dispatchSwipe(x1, y1, x2, y2, duration);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("success", ok);
            result.put("source", "ax");
            result.put("x1", x1);
            result.put("y1", y1);
            result.put("x2", x2);
            result.put("y2", y2);
            result.put("duration", duration);
            if (!ok) {
                result.put("error", "swipe: 无障碍手势派发失败（服务未就绪？）");
            }
            return result;
        }
        String cmd = "input swipe " + x1 + " " + y1 + " " + x2 + " " + y2 + " " + duration;
        Map<String, Object> result = toResult(adb.execShell(cmd), "滑动失败");
        result.put("source", "adb");
        return result;
    }

    /** 全局按键：有 adb 走 keyevent，没有则走无障碍 performGlobalAction。 */
    private Map<String, Object> globalKey(String actionType, String keyCode, int globalAction) {
        if (adb == null) {
            boolean ok = UiTreeProvider.performGlobalAction(globalAction);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("success", ok);
            result.put("source", "ax");
            result.put("action", actionType);
            if (!ok) {
                result.put("error", actionType + ": 无障碍服务未就绪，无法执行全局动作");
            }
            return result;
        }
        Map<String, Object> result = keyEvent(keyCode);
        result.put("source", "adb");
        result.put("action", actionType);
        return result;
    }

    private Map<String, Object> typeText(JSONObject params) throws JSONException {
        String text = params.optString("text", "");
        if (text.isEmpty()) {
            return errorResult("type_text: text 不能为空");
        }
        if (adb == null) {
            boolean ok = UiTreeProvider.setText(text);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("success", ok);
            result.put("source", "ax");
            result.put("text", text);
            if (!ok) {
                result.put("error", "type_text: 没有聚焦的可编辑节点（先 click 输入框，或该输入框不支持 ACTION_SET_TEXT）");
            }
            return result;
        }
        int[] xy = resolveCoordinates(params);
        if (xy != null) {
            AdbLocalClient.Result tap = adb.execShell("input tap " + xy[0] + " " + xy[1]);
            if (!tap.success) {
                return errorResult("type_text: 聚焦输入框失败: " + tap.error);
            }
        }
        // adb input text 的空格用 %s 转义
        String escaped = text.replace(" ", "%s");
        return toResult(adb.execShell("input text " + escaped), "输入文本失败");
    }

    private Map<String, Object> scroll(JSONObject params) {
        boolean down = params.optBoolean("down", true);
        int[] size = getScreenSize();
        if (size == null) {
            return errorResult("scroll: 无法获取屏幕尺寸（wm size）");
        }
        int cx = size[0] / 2;
        int yTop = (int) (size[1] * 0.3);
        int yBottom = (int) (size[1] * 0.7);
        int fromY = down ? yBottom : yTop;
        int toY = down ? yTop : yBottom;
        if (adb == null) {
            boolean ok = UiTreeProvider.dispatchSwipe(cx, fromY, cx, toY, 400);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("success", ok);
            result.put("source", "ax");
            if (!ok) {
                result.put("error", "scroll: 无障碍手势派发失败（服务未就绪？）");
            }
            return result;
        }
        return toResult(adb.execShell("input swipe " + cx + " " + fromY + " " + cx + " " + toY + " 400"),
                "滚动失败");
    }

    /**
     * [experimental] 滚一次并返回「界面是否真的动了」——长截图/滚动定位的行为判据。
     * 结构信号（canScrollForward）只作参考，见 docs/INTERFACE.md §10。
     */
    private Map<String, Object> scrollProbe(JSONObject params) {
        boolean forward = params.optBoolean("forward", true);
        UiTreeProvider.ScrollProbe p = UiTreeProvider.scrollProbe(forward);
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("success", true);
        r.put("experimental", true);
        r.put("source", "ax");
        r.put("direction", forward ? "forward" : "backward");
        r.put("moved", p.moved);
        r.put("canScrollFurther", p.moved);
        r.put("structureCanForward", p.canForward);
        r.put("structureCanBackward", p.canBackward);
        r.put("before", p.before);
        r.put("after", p.after);
        r.put("how", p.how);
        r.put("settleMs", p.settleMs);
        r.put("latencyMs", p.latencyMs);
        return r;
    }

    private Map<String, Object> keyEvent(String keyCode) {
        return toResult(adb.execShell("input keyevent " + keyCode), "按键失败");
    }

    private Map<String, Object> screenshot() {
        if (adb == null) {
            Bitmap bmp = UiTreeProvider.screenshot(5000);
            if (bmp == null) {
                return errorResult("screenshot: 无障碍截屏不可用（需 Android 11+ 且无障碍服务已启用；FLAG_SECURE 窗口会失败）");
            }
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            bmp.compress(Bitmap.CompressFormat.PNG, 100, bos);
            String b64str = Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP);
            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("base64", b64str);
            result.put("mimeType", "image/png");
            result.put("width", bmp.getWidth());
            result.put("height", bmp.getHeight());
            result.put("source", "ax");
            result.put("message", "截图成功（无障碍通道，" + b64str.length() + " chars）");
            return result;
        }
        String path = "/sdcard/mcp_shot.png";
        AdbLocalClient.Result cap = adb.execShell("screencap -p " + path);
        if (!cap.success) {
            return errorResult("screenshot: screencap 失败: " + cap.error);
        }
        AdbLocalClient.Result b64 = adb.execShell("base64 " + path);
        adb.execShell("rm -f " + path); // 尽力清理
        if (!b64.success) {
            return errorResult("screenshot: base64 读取失败: " + b64.error);
        }
        String b64str = b64.output.replaceAll("\\s+", "");
        if (b64str.isEmpty()) {
            return errorResult("screenshot: base64 为空");
        }
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("base64", b64str);
        result.put("mimeType", "image/png");
        int[] size = getScreenSize();
        if (size != null) {
            result.put("width", size[0]);
            result.put("height", size[1]);
        }
        result.put("message", "截图成功 (" + (b64str.length() * 3 / 4) + " bytes)");
        return result;
    }

    /**
     * get_ui_state：优先读进程内无障碍树（几十毫秒），不可用时回退 uiautomator dump（约 2.77s）。
     * 返回的 message 是 JSON 字符串，bridge 会 json.loads 后放进 ui_state 字段。
     */
    private Map<String, Object> getUiState() {
        long t0 = System.currentTimeMillis();
        JSONObject ax = UiTreeProvider.dump();
        if (ax != null) {
            try {
                ax.put("latencyMs", System.currentTimeMillis() - t0);
            } catch (JSONException ignored) {
            }
            String json = ax.toString();
            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("message", json);
            result.put("ui_state", json);
            result.put("source", "ax");
            result.put("latencyMs", System.currentTimeMillis() - t0);
            return result;
        }
        if (adb == null) {
            return errorResult("get_ui_state: 无障碍树不可用（KeepAliveAccessibilityService 未启用？），且无 adb 通道");
        }
        AdbLocalClient.Result dump = adb.execShell("uiautomator dump /sdcard/mcp_ui.xml");
        if (!dump.success) {
            return errorResult("get_ui_state: 无障碍树不可用且 uiautomator dump 失败: " + dump.error);
        }
        AdbLocalClient.Result cat = adb.execShell("cat /sdcard/mcp_ui.xml");
        if (!cat.success) {
            return errorResult("get_ui_state: 读取 XML 失败: " + cat.error);
        }
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("message", cat.output); // bridge 会尝试 json.loads，失败则原样透传
        result.put("xml", cat.output);
        result.put("source", "dump");
        result.put("latencyMs", System.currentTimeMillis() - t0);
        return result;
    }

    private Map<String, Object> getInstalledApps() {
        if (adb == null) {
            PackageManager pm = appContext.getPackageManager();
            JSONArray arr = new JSONArray();
            for (ApplicationInfo ai : pm.getInstalledApplications(0)) {
                if ((ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0) {
                    continue;
                }
                JSONObject o = new JSONObject();
                try {
                    o.put("packageName", ai.packageName);
                } catch (JSONException ignored) {
                }
                arr.put(o);
            }
            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("source", "system");
            result.put("count", arr.length());
            result.put("message", arr.toString());
            result.put("installedApps", arr.toString());
            return result;
        }
        AdbLocalClient.Result r = adb.execShell("pm list packages -3");
        if (!r.success) {
            return errorResult("get_installed_apps: " + r.error);
        }
        JSONArray arr = new JSONArray();
        for (String line : r.output.split("\n")) {
            String t = line.trim();
            if (t.startsWith("package:")) {
                JSONObject o = new JSONObject();
                try {
                    o.put("packageName", t.substring("package:".length()));
                } catch (JSONException ignored) {
                }
                arr.put(o);
            }
        }
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("message", arr.toString());
        result.put("installedApps", arr.toString());
        return result;
    }

    private Map<String, Object> getNotifications() {
        if (adb == null) {
            StatusBarNotification[] active = HubNotificationListener.activeNotificationsOrEmpty();
            if (active.length == 0) {
                return errorResult("get_notifications: 通知监听未授权，或当前没有通知");
            }
            JSONArray arr = new JSONArray();
            for (StatusBarNotification sbn : active) {
                Notification n = sbn.getNotification();
                Bundle extras = n == null ? null : n.extras;
                String title = extras == null ? ""
                        : String.valueOf(extras.getCharSequence(Notification.EXTRA_TITLE, ""));
                String body = extras == null ? ""
                        : String.valueOf(extras.getCharSequence(Notification.EXTRA_TEXT, ""));
                addNotification(arr, sbn.getPackageName(), title, body);
            }
            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("source", "listener");
            result.put("count", arr.length());
            result.put("message", arr.toString());
            result.put("notifications", arr.toString());
            return result;
        }
        AdbLocalClient.Result r = adb.execShell("dumpsys notification");
        if (!r.success) {
            return errorResult("get_notifications: " + r.error);
        }
        JSONArray arr = parseNotifications(r.output);
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("notifications", arr);
        result.put("message", arr.toString());
        return result;
    }

    private Map<String, Object> dismissNotification(JSONObject params) throws JSONException {
        String key = params.optString("key", "");
        AdbLocalClient.Result r = adb.execShell("cmd notification cancel " + key);
        if (r.success && r.exitCode == 0 && !r.output.contains("error")) {
            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("message", "通知已清除: " + key);
            return result;
        }
        return errorResult("dismiss_notification: shell 权限无法清除通知（key=" + key
                + "），请手动在通知栏清除");
    }

    // ================== 开源手机 MCP 对标新增（全部 adb shell 实现） ==================

    /** get_screen_size：无 adb 时用 DisplayMetrics。 */
    private Map<String, Object> getScreenSizeInfo() {
        if (adb == null) {
            DisplayMetrics dm = appContext.getResources().getDisplayMetrics();
            Map<String, Object> result = new HashMap<>();
            result.put("width", dm.widthPixels);
            result.put("height", dm.heightPixels);
            result.put("density", dm.densityDpi);
            result.put("source", "system");
            result.put("success", true);
            return result;
        }
        AdbLocalClient.Result size = adb.execShell("wm size");
        AdbLocalClient.Result density = adb.execShell("wm density");
        if (!size.success) {
            return errorResult("get_screen_size: wm size 失败: " + size.error);
        }
        Map<String, Object> result = new HashMap<>();
        int[] wh = getScreenSize();
        if (wh != null) {
            result.put("width", wh[0]);
            result.put("height", wh[1]);
        }
        Matcher dm = Pattern.compile("(\\d{3,4})").matcher(density.output);
        if (dm.find()) {
            result.put("density", Integer.parseInt(dm.group(1)));
        }
        result.put("success", true);
        result.put("raw", size.output.trim() + " | " + density.output.trim());
        return result;
    }

    /** open_url：VIEW intent 打开链接/深度链接。 */
    private Map<String, Object> openUrl(JSONObject params) throws JSONException {
        String url = params.optString("url", "");
        if (url.isEmpty()) {
            return errorResult("open_url: 缺少 url");
        }
        if (adb == null) {
            try {
                Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                appContext.startActivity(intent);
            } catch (Throwable t) {
                return errorResult("open_url 失败: " + t);
            }
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("success", true);
            result.put("source", "system");
            result.put("url", url);
            return result;
        }
        return toResult(adb.execShell("am start -a android.intent.action.VIEW -d '" + url + "'"),
                "open_url 失败");
    }

    /** key_event：任意 keycode（26=电源 24/25=音量 126/127=播放暂停 4=返回...）。 */
    private Map<String, Object> keyEventGeneric(JSONObject params) throws JSONException {
        int code = params.optInt("code", params.optInt("keycode", -1));
        if (code < 0 || code > 65535) {
            return errorResult("key_event: 缺少合法 code（0-65535）");
        }
        return toResult(adb.execShell("input keyevent " + code), "key_event 失败");
    }

    /** volume：up/down/mute/unmute。 */
    private Map<String, Object> volumeControl(JSONObject params) throws JSONException {
        String action = params.optString("action", params.optString("direction", ""));
        String code;
        switch (action) {
            case "up": code = "24"; break;
            case "down": code = "25"; break;
            case "mute": case "toggle": code = "164"; break;
            case "unmute": code = "165"; break;
            default:
                return errorResult("volume: action 必须是 up/down/mute/unmute");
        }
        int times = params.optInt("times", 1);
        AdbLocalClient.Result r = null;
        for (int i = 0; i < Math.max(1, Math.min(times, 10)); i++) {
            r = adb.execShell("input keyevent " + code);
        }
        return toResult(r, "volume 失败");
    }

    /** media_control：play/pause/next/prev/stop（media keyevent，对所有播放器生效）。 */
    private Map<String, Object> mediaControl(JSONObject params) throws JSONException {
        String action = params.optString("action", "");
        String code;
        switch (action) {
            case "play": case "pause": case "toggle": code = "126"; break;
            case "next": code = "87"; break;
            case "prev": case "previous": code = "88"; break;
            case "stop": code = "86"; break;
            default:
                return errorResult("media_control: action 必须是 play/pause/toggle/next/prev/stop");
        }
        return toResult(adb.execShell("input keyevent " + code), "media_control 失败");
    }

    /** wifi：svc wifi enable/disable（shell 权限足够）。 */
    private Map<String, Object> wifiToggle(JSONObject params) throws JSONException {
        boolean enable = params.optBoolean("enable", params.optBoolean("on", true));
        AdbLocalClient.Result r = adb.execShell("svc wifi " + (enable ? "enable" : "disable"));
        if (!r.success) {
            return errorResult("wifi 失败: " + r.error);
        }
        AdbLocalClient.Result state = adb.execShell("cmd wifi status");
        Map<String, Object> result = toResult(r, "wifi 失败");
        result.put("enabled", enable);
        result.put("status", state.output.trim());
        return result;
    }

    /** bluetooth：svc bluetooth enable/disable。 */
    private Map<String, Object> bluetoothToggle(JSONObject params) throws JSONException {
        boolean enable = params.optBoolean("enable", params.optBoolean("on", true));
        return toResult(adb.execShell("svc bluetooth " + (enable ? "enable" : "disable")),
                "bluetooth 失败");
    }

    /** airplane_mode：settings + 广播（shell 权限可发）。 */
    private Map<String, Object> airplaneToggle(JSONObject params) throws JSONException {
        boolean enable = params.optBoolean("enable", params.optBoolean("on", true));
        adb.execShell("settings put global airplane_mode_on " + (enable ? 1 : 0));
        AdbLocalClient.Result r = adb.execShell(
                "am broadcast -a android.intent.action.AIRPLANE_MODE --ez state "
                        + (enable ? "true" : "false"));
        Map<String, Object> result = toResult(r, "airplane_mode 失败");
        result.put("enabled", enable);
        return result;
    }

    /** brightness：0-255（自动亮度先关，避免被系统覆盖）。 */
    private Map<String, Object> brightness(JSONObject params) throws JSONException {
        int level = params.optInt("level", -1);
        if (level < 0 || level > 255) {
            return errorResult("brightness: level 必须 0-255");
        }
        adb.execShell("settings put system screen_brightness_mode 0");
        return toResult(adb.execShell("settings put system screen_brightness " + level),
                "brightness 失败");
    }

    /** get_current_app：无 adb 时用最近一次无障碍树的包名。 */
    private Map<String, Object> getCurrentApp() {
        if (adb == null) {
            String pkg = UiTreeProvider.currentPackage();
            if (pkg.isEmpty()) {
                UiTreeProvider.dump();
                pkg = UiTreeProvider.currentPackage();
            }
            Map<String, Object> result = new HashMap<>();
            result.put("package", pkg);
            result.put("activity", "");
            result.put("source", "ax");
            result.put("success", !pkg.isEmpty());
            if (pkg.isEmpty()) {
                result.put("error", "get_current_app: 无障碍树不可用");
            }
            return result;
        }
        AdbLocalClient.Result r = adb.execShell("dumpsys window | grep mCurrentFocus");
        if (!r.success) {
            return errorResult("get_current_app: " + r.error);
        }
        Matcher m = Pattern.compile("u0 ([A-Za-z0-9._]+)/([A-Za-z0-9.$_]+)").matcher(r.output);
        Map<String, Object> result = new HashMap<>();
        if (m.find()) {
            result.put("package", m.group(1));
            result.put("activity", m.group(2));
        } else {
            result.put("package", "");
            result.put("activity", "");
            result.put("raw", r.output.trim());
        }
        result.put("success", true);
        return result;
    }

    /** 双通道自检：哪条通道在线、Shizuku 是否可用、真跑一条命令验证。 */
    private Map<String, Object> channelStatus() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("success", true);
        boolean adbUp = adb.isAdbPortOpen();
        m.put("adb_port_open", adbUp);
        boolean shizukuUp = ShizukuBridge.available();
        if (!shizukuUp && ShizukuBridge.ping()) {
            ShizukuBridge.requestPermissionIfNeeded();
        }
        m.put("shizuku_available", shizukuUp);
        AdbLocalClient.Result r = adb.execShell("id");
        m.put("channel_used", adbUp ? "adb" : (shizukuUp ? "shizuku" : "none"));
        m.put("probe_success", r != null && r.success);
        m.put("probe_output", r != null ? r.output.trim() : null);
        AdbLocalClient.Result sz = ShizukuBridge.exec("id", 8000);
        m.put("shizuku_probe_success", sz != null && sz.success);
        m.put("shizuku_probe_output", sz != null ? String.valueOf(sz.output).trim() : null);
        return m;
    }

    /** battery_info：无 adb 时用 BatteryManager 广播。 */
    private Map<String, Object> batteryInfo() {
        if (adb == null) {
            Intent batt = appContext.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (batt == null) {
                return errorResult("battery_info: 读取电池状态失败");
            }
            int level = batt.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int scale = batt.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
            int status = batt.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
            int temp = batt.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0);
            Map<String, Object> result = new HashMap<>();
            result.put("level", scale > 0 ? level * 100 / scale : level);
            result.put("status", status);
            result.put("charging", status == BatteryManager.BATTERY_STATUS_CHARGING
                    || status == BatteryManager.BATTERY_STATUS_FULL);
            result.put("temperature", temp / 10.0);
            result.put("source", "system");
            result.put("success", true);
            return result;
        }
        AdbLocalClient.Result r = adb.execShell("dumpsys battery");
        if (!r.success) {
            return errorResult("battery_info: " + r.error);
        }
        Map<String, Object> result = new HashMap<>();
        Matcher lv = Pattern.compile("level:\\s*(\\d+)").matcher(r.output);
        if (lv.find()) result.put("level", Integer.parseInt(lv.group(1)));
        Matcher st = Pattern.compile("status:\\s*(\\d+)").matcher(r.output);
        if (st.find()) result.put("status", Integer.parseInt(st.group(1)));
        result.put("charging", r.output.contains("AC powered: true")
                || r.output.contains("USB powered: true")
                || r.output.contains("Wireless powered: true"));
        Matcher tp = Pattern.compile("temperature:\\s*(\\d+)").matcher(r.output);
        if (tp.find()) result.put("temperature", Integer.parseInt(tp.group(1)) / 10.0);
        result.put("success", true);
        return result;
    }

    /** clear_app_data：pm clear（shell 可用，数据全清）。 */
    private Map<String, Object> clearAppData(JSONObject params) throws JSONException {
        String pkg = params.optString("packageName", params.optString("package_name", ""));
        if (pkg.isEmpty()) {
            return errorResult("clear_app_data: 缺少 packageName");
        }
        return toResult(adb.execShell("pm clear " + pkg), "clear_app_data 失败");
    }

    /** uninstall_app：pm uninstall（对 user 0 生效）。 */
    private Map<String, Object> uninstallApp(JSONObject params) throws JSONException {
        String pkg = params.optString("packageName", params.optString("package_name", ""));
        if (pkg.isEmpty()) {
            return errorResult("uninstall_app: 缺少 packageName");
        }
        return toResult(adb.execShell("pm uninstall " + pkg), "uninstall_app 失败");
    }

    /** install_app：pm install -r <设备内 apk 路径>（apk 需先在设备上，如 /data/local/tmp）。 */
    private Map<String, Object> installApp(JSONObject params) throws JSONException {
        String path = params.optString("apkPath", params.optString("path", ""));
        if (path.isEmpty() || !path.startsWith("/")) {
            return errorResult("install_app: 缺少 apkPath（设备内绝对路径）");
        }
        return toResult(adb.execShell("pm install -r " + path), "install_app 失败");
    }

    /** file_read：cat 设备文件。 */
    private Map<String, Object> fileRead(JSONObject params) throws JSONException {
        String path = params.optString("path", "");
        if (path.isEmpty() || !path.startsWith("/")) {
            return errorResult("file_read: 缺少 path（设备内绝对路径）");
        }
        AdbLocalClient.Result r = adb.execShell("cat " + path);
        if (!r.success) {
            return errorResult("file_read 失败: " + r.error);
        }
        Map<String, Object> result = toResult(r, "file_read 失败");
        result.put("content", r.output);
        return result;
    }

    /** file_write：内容 base64 化后走管道，规避引号/换行转义问题。 */
    private Map<String, Object> fileWrite(JSONObject params) throws JSONException {
        String path = params.optString("path", "");
        String content = params.optString("content", params.optString("text", ""));
        if (path.isEmpty() || !path.startsWith("/")) {
            return errorResult("file_write: 缺少 path（设备内绝对路径）");
        }
        if (content.length() > 20_000) {
            return errorResult("file_write: content 过大（>20KB），请改用 adb push");
        }
        String b64 = Base64.encodeToString(content.getBytes(), Base64.NO_WRAP);
        return toResult(adb.execShell("echo " + b64 + " | base64 -d > " + path),
                "file_write 失败");
    }

    /** wait_for_element：轮询 uiautomator dump 直到 selector 出现（默认 15s）。 */
    private Map<String, Object> waitForElement(JSONObject params) throws JSONException {
        int timeoutMs = params.optInt("timeout", 15000);
        long deadline = System.currentTimeMillis() + Math.min(timeoutMs, 60000);
        int[] xy = null;
        while (System.currentTimeMillis() < deadline) {
            xy = resolveCoordinates(params);
            if (xy != null) {
                break;
            }
            try {
                // 无障碍树可用时 150ms 轮询（树读取是内存操作），否则维持 1200ms 避免 dump 过载
                Thread.sleep(UiTreeProvider.isReady() ? 150 : 1200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        Map<String, Object> result = new HashMap<>();
        if (xy != null) {
            result.put("success", true);
            result.put("x", xy[0]);
            result.put("y", xy[1]);
            result.put("found", true);
        } else {
            result.put("success", false);
            result.put("found", false);
            result.put("error", "wait_for_element: 超时未找到元素（"
                    + params.optString("selector", params.optString("text", "")) + "）");
        }
        return result;
    }

    /** set_rotation：lock=true 锁定方向（0=竖 1=横 90°），lock=false 恢复自动。 */
    private Map<String, Object> setRotation(JSONObject params) throws JSONException {
        boolean lock = params.optBoolean("lock", true);
        adb.execShell("settings put system accelerometer_rotation " + (lock ? 0 : 1));
        if (lock) {
            int orientation = params.optInt("orientation", 0);
            if (orientation < 0 || orientation > 3) {
                return errorResult("set_rotation: orientation 必须 0-3（0=竖 1=横 2/3=反向）");
            }
            return toResult(adb.execShell("settings put system user_rotation " + orientation),
                    "set_rotation 失败");
        }
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("message", "已恢复自动旋转");
        return result;
    }

    /** open_app_settings：跳应用详情页。 */
    private Map<String, Object> openAppSettings(JSONObject params) throws JSONException {
        String pkg = params.optString("packageName", params.optString("package_name", ""));
        if (pkg.isEmpty()) {
            return errorResult("open_app_settings: 缺少 packageName");
        }
        return toResult(adb.execShell(
                "am start -a android.settings.APPLICATION_DETAILS_SETTINGS -d package:" + pkg),
                "open_app_settings 失败");
    }

    // ================== 工具方法 ==================

    private Map<String, Object> toResult(AdbLocalClient.Result r, String failMsg) {
        Map<String, Object> result = new HashMap<>();
        if (r.success) {
            result.put("success", true);
            result.put("output", r.output);
            result.put("exitCode", r.exitCode);
            result.put("exit_code", r.exitCode);
            result.put("message", r.output.trim());
        } else {
            result.put("success", false);
            result.put("error", failMsg + ": " + r.error);
        }
        return result;
    }

    // ================== 信息中枢 ==================

    private Map<String, Object> hubQuery(JSONObject params) throws JSONException {
        long since = params.optLong("since", 0L);
        if (since <= 0) {
            since = System.currentTimeMillis() - 24L * 3600 * 1000;
        }
        String pkg = params.optString("pkg", "");
        int limit = params.optInt("limit", 50);
        if (limit <= 0 || limit > 500) limit = 50;
        JSONArray arr = HubStore.get(appContext).query(since, pkg, limit);
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("count", arr.length());
        result.put("messages", arr.toString());
        return result;
    }

    private Map<String, Object> hubStats() {
        JSONArray arr = HubStore.get(appContext).stats();
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("apps", arr.toString());
        return result;
    }

    private Map<String, Object> errorResult(String error) {
        Map<String, Object> result = new HashMap<>();
        result.put("success", false);
        result.put("error", error);
        return result;
    }

    /** 解析目标元素：坐标 → 无障碍树 → uiautomator dump。返回 null 表示没找到。 */
    private UiTreeProvider.Match resolveMatch(JSONObject params) throws JSONException {
        if (params.has("x") && params.has("y")) {
            int x = params.getInt("x");
            int y = params.getInt("y");
            return new UiTreeProvider.Match("", "", "", "", x, y, x, y, true, true, "coord");
        }
        String id = params.optString("id", "");
        String text = params.optString("text", "");
        String desc = params.optString("desc", "");
        String selector = params.optString("selector", "");
        if (!selector.isEmpty()) {
            String[] loc = new String[]{id, text, desc};
            parseSelector(selector, loc);
            id = loc[0];
            text = loc[1];
            desc = loc[2];
        }
        if (id.isEmpty() && text.isEmpty() && desc.isEmpty()) {
            return null;
        }
        UiTreeProvider.Match m = UiTreeProvider.find(id, text, desc);
        if (m != null) {
            return m;
        }
        return findMatchByDump(id, text, desc);
    }

    /** 兼容旧调用点：只要中心点坐标。 */
    private int[] resolveCoordinates(JSONObject params) throws JSONException {
        UiTreeProvider.Match m = resolveMatch(params);
        if (m == null) {
            return null;
        }
        return new int[]{m.centerX(), m.centerY()};
    }

    private void parseSelector(String selector, String[] out) {
        String s = selector.trim();
        if (s.startsWith("{")) {
            try {
                JSONObject j = new JSONObject(s);
                if (out[0].isEmpty()) out[0] = j.optString("id", "");
                if (out[1].isEmpty()) out[1] = j.optString("text", "");
                if (out[2].isEmpty()) out[2] = j.optString("desc", "");
            } catch (JSONException ignored) {
            }
            return;
        }
        int eq = s.indexOf('=');
        if (eq > 0) {
            String key = s.substring(0, eq).trim().toLowerCase();
            String value = s.substring(eq + 1).trim();
            if ("id".equals(key)) out[0] = value;
            else if ("text".equals(key)) out[1] = value;
            else if ("desc".equals(key) || "description".equals(key)) out[2] = value;
            else if (out[1].isEmpty()) out[1] = value;
        } else if (out[1].isEmpty()) {
            out[1] = s;
        }
    }

    /** 回退路径：uiautomator dump 解析（约 2.77s/次），命中后返回带 clickable 的 Match。 */
    private UiTreeProvider.Match findMatchByDump(String id, String text, String desc) {
        if (adb == null) {
            return null; // 无 adb 通道（assets/adbkey 缺失）时只能靠无障碍树
        }
        AdbLocalClient.Result dump = adb.execShell("uiautomator dump /sdcard/mcp_ui.xml");
        if (!dump.success) {
            return null;
        }
        AdbLocalClient.Result cat = adb.execShell("cat /sdcard/mcp_ui.xml");
        if (!cat.success) {
            return null;
        }
        Pattern nodePattern = Pattern.compile("<node\\b[^>]*>");
        Matcher m = nodePattern.matcher(cat.output);
        while (m.find()) {
            String node = m.group();
            String rid = attr(node, "resource-id");
            String rtext = attr(node, "text");
            String rdesc = attr(node, "content-desc");
            boolean exact = true;
            boolean match = true;
            if (!id.isEmpty()) {
                match = rid != null && rid.contains(id);
                exact = exact && id.equals(rid);
            }
            if (match && !text.isEmpty()) {
                boolean hit = rtext != null && rtext.contains(text);
                exact = exact && text.equals(rtext);
                match = hit;
            }
            if (match && !desc.isEmpty()) {
                boolean hit = rdesc != null && rdesc.contains(desc);
                exact = exact && desc.equals(rdesc);
                match = hit;
            }
            if (match && (!id.isEmpty() || !text.isEmpty() || !desc.isEmpty())) {
                int[] b = boundsRect(attr(node, "bounds"));
                if (b == null) continue;
                boolean clickable = "true".equals(attr(node, "clickable"));
                return new UiTreeProvider.Match(rtext, rdesc, rid, attr(node, "class"),
                        b[0], b[1], b[2], b[3], clickable, exact, "dump");
            }
        }
        return null;
    }

    /** bounds="[l,t][r,b]" → {l,t,r,b}。 */
    private static int[] boundsRect(String bounds) {
        if (bounds == null || bounds.isEmpty()) {
            return null;
        }
        Matcher m = Pattern.compile("\\[(\\d+),(\\d+)\\]\\[(\\d+),(\\d+)\\]").matcher(bounds);
        if (!m.find()) {
            return null;
        }
        return new int[]{Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)),
                Integer.parseInt(m.group(3)), Integer.parseInt(m.group(4))};
    }

    private static String attr(String node, String name) {
        Matcher m = Pattern.compile(name + "=\"([^\"]*)\"").matcher(node);
        return m.find() ? m.group(1) : "";
    }

    /** bounds="[l,t][r,b]" → 中心点。 */
    private static int[] boundsCenter(String bounds) {
        if (bounds == null || bounds.isEmpty()) {
            return null;
        }
        Matcher m = Pattern.compile("\\[(\\d+),(\\d+)\\]\\[(\\d+),(\\d+)\\]").matcher(bounds);
        if (!m.find()) {
            return null;
        }
        int l = Integer.parseInt(m.group(1));
        int t = Integer.parseInt(m.group(2));
        int r = Integer.parseInt(m.group(3));
        int b = Integer.parseInt(m.group(4));
        return new int[]{(l + r) / 2, (t + b) / 2};
    }

    private int[] getScreenSize() {
        if (adb == null) {
            DisplayMetrics dm = appContext.getResources().getDisplayMetrics();
            return new int[]{dm.widthPixels, dm.heightPixels};
        }
        AdbLocalClient.Result r = adb.execShell("wm size");
        if (!r.success) {
            return null;
        }
        Matcher m = Pattern.compile("(\\d{2,5})x(\\d{2,5})").matcher(r.output);
        if (m.find()) {
            return new int[]{Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2))};
        }
        return null;
    }

    /** 简化解析 dumpsys notification：抽 NotificationRecord 块里的 pkg/title/text。 */
    private JSONArray parseNotifications(String text) {
        JSONArray arr = new JSONArray();
        String[] lines = text.split("\n");
        String curPkg = null;
        String curTitle = null;
        String curText = null;
        boolean inRecord = false;
        for (String line : lines) {
            String t = line.trim();
            if (t.contains("NotificationRecord(")) {
                addNotification(arr, curPkg, curTitle, curText);
                inRecord = true;
                curPkg = null;
                curTitle = null;
                curText = null;
                int i = t.indexOf("pkg=");
                if (i >= 0) {
                    String p = t.substring(i + 4);
                    int j = p.indexOf(',');
                    if (j >= 0) p = p.substring(0, j);
                    curPkg = p;
                }
                continue;
            }
            if (!inRecord) {
                continue;
            }
            if (t.startsWith("android.title=")) {
                curTitle = t.substring("android.title=".length());
            } else if (t.startsWith("android.text=")) {
                curText = t.substring("android.text=".length());
            } else if (t.isEmpty() && (curPkg != null || curTitle != null)) {
                addNotification(arr, curPkg, curTitle, curText);
                inRecord = false;
                curPkg = null;
                curTitle = null;
                curText = null;
            }
        }
        addNotification(arr, curPkg, curTitle, curText);
        if (arr.length() == 0) {
            // 兜底：返回原始文本（截断，避免响应过大）
            JSONObject o = new JSONObject();
            try {
                String raw = text.length() > 20000 ? text.substring(0, 20000) : text;
                o.put("raw", raw);
            } catch (JSONException ignored) {
            }
            arr.put(o);
        }
        return arr;
    }

    private void addNotification(JSONArray arr, String pkg, String title, String text) {
        if (pkg == null && title == null && text == null) {
            return;
        }
        JSONObject o = new JSONObject();
        try {
            o.put("key", (pkg == null ? "?" : pkg) + "-" + (title == null ? "" : title));
            o.put("packageName", pkg == null ? "" : pkg);
            o.put("title", title == null ? "" : title);
            o.put("text", text == null ? "" : text);
        } catch (JSONException ignored) {
        }
        arr.put(o);
    }
}
