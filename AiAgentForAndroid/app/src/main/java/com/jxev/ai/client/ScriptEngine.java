package com.jxev.ai.client;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.util.Calendar;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * 手机端脚本引擎（2026-08-14 新增，任务书 CODEX_TASK_phone_gateway）。
 *
 * - 存储：context.getFilesDir()/scripts.json（App 私有目录，无需权限）
 * - 调度：AlarmManager（API 31+ 有精确闹钟权限用 setExactAndAllowWhileIdle，
 *   否则用 setInexactRepeating 兜底）；BOOT/重建由 BootReceiver/MCPService 重新注册
 * - 执行：逐 step（shell / speak / notify_pc），失败即停（fail-fast），
 *   结果写 lastRun + logs 数组
 * - 线程：执行在后台线程（单线程串行），TTS 播放由 TtsPlayer 切主线程
 */
public class ScriptEngine {
    private static final String TAG = "ScriptEngine";

    private static final String FILE_NAME = "scripts.json";
    private static final String ALARM_ACTION = "com.jxev.ai.action.RUN_SCRIPT";
    private static final String ALARM_EXTRA_NAME = "script_name";
    private static final long NOTIFY_WAIT_MS = 20000;
    private static final long TTS_WAIT_MS = 60000;
    private static final int MAX_LOGS = 200;

    private static final Pattern TIME_RE = Pattern.compile("^([01]\\d|2[0-3]):[0-5]\\d$");

    private static volatile ScriptEngine instance;

    private final Context appContext;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Object runLock = new Object();
    private volatile PcNotifySender pcNotifySender;

    /** pc_notify 步骤通过 socket 发 Bridge 的发送器（由 MCPSocketClient 注入）。 */
    public interface PcNotifySender {
        void sendNotify(String title, String text, MCPSocketClient.RequestCallback callback);
    }

    public static ScriptEngine getInstance(Context context) {
        if (instance == null) {
            synchronized (ScriptEngine.class) {
                if (instance == null) {
                    instance = new ScriptEngine(context.getApplicationContext());
                }
            }
        }
        return instance;
    }

    private ScriptEngine(Context context) {
        this.appContext = context;
    }

    public void setPcNotifySender(PcNotifySender sender) {
        this.pcNotifySender = sender;
    }

    /** 闹钟触发入口：后台线程执行 + 执行后重挂次日闹钟。 */
    public void runScriptAsync(final String name) {
        executor.execute(() -> {
            Map<String, Object> result = runScriptInternal(name, "schedule");
            Log.i(TAG, "闹钟脚本执行完成 " + name + ": "
                    + result.get("status") + " / " + result.get("results"));
            reArmIfEnabled(name);
        });
    }

    // ================== socket action 分发 ==================

    /** MCPSocketClient 新增分支：script_* 动作统一入口。 */
    public Map<String, Object> handleAction(String actionType, JSONObject params) {
        try {
            switch (actionType) {
                case "script_create":
                    return scriptCreate(params);
                case "script_list":
                    return scriptList();
                case "script_delete":
                    return scriptDelete(params);
                case "script_run":
                    return scriptRun(params);
                case "script_schedule":
                    return scriptSchedule(params);
                case "script_enable":
                    return scriptSetEnabled(params, true);
                case "script_disable":
                    return scriptSetEnabled(params, false);
                default:
                    return errorResult("未知脚本 action: " + actionType);
            }
        } catch (JSONException e) {
            Log.e(TAG, "脚本 action 参数解析失败: " + actionType, e);
            return errorResult("参数解析失败: " + e.getMessage());
        }
    }

    // ================== CRUD ==================

    private Map<String, Object> scriptCreate(JSONObject params) throws JSONException {
        String name = params.optString("name", "").trim();
        if (name.isEmpty()) {
            return errorResult("script_create: name 不能为空");
        }
        String description = params.optString("description", "");
        JSONArray steps = params.optJSONArray("steps");
        String stepError = validateSteps(steps);
        if (stepError != null) {
            return errorResult("script_create: " + stepError);
        }
        JSONObject schedule = params.optJSONObject("schedule");
        String scheduleError = validateSchedule(schedule);
        if (scheduleError != null) {
            return errorResult("script_create: " + scheduleError);
        }

        synchronized (runLock) {
            JSONObject store = loadStore();
            if (findScript(store, name) != null) {
                return errorResult("script_create: 脚本已存在: " + name);
            }
            JSONObject script = new JSONObject();
            script.put("name", name);
            script.put("description", description);
            script.put("steps", steps);
            script.put("lastRun", "");
            if (schedule != null) {
                script.put("schedule", schedule);
            }
            store.getJSONArray("scripts").put(script);
            saveStore(store);
            if (schedule != null && schedule.optBoolean("enabled", false)) {
                scheduleAlarm(name, schedule.optString("time", "08:00"));
            }
        }

        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("message", "脚本已创建（数据存手机）: " + name);
        result.put("script", name);
        return result;
    }

    private Map<String, Object> scriptList() {
        JSONObject store = loadStore();
        JSONArray scripts = store.optJSONArray("scripts");
        JSONArray list = new JSONArray();
        if (scripts != null) {
            for (int i = 0; i < scripts.length(); i++) {
                JSONObject s = scripts.optJSONObject(i);
                if (s == null) {
                    continue;
                }
                JSONObject item = new JSONObject();
                try {
                    item.put("name", s.optString("name", ""));
                    item.put("description", s.optString("description", ""));
                    if (s.has("schedule")) {
                        item.put("schedule", s.optJSONObject("schedule"));
                    }
                    item.put("lastRun", s.optString("lastRun", ""));
                    JSONArray steps = s.optJSONArray("steps");
                    JSONArray summary = new JSONArray();
                    int count = 0;
                    if (steps != null) {
                        count = steps.length();
                        for (int j = 0; j < steps.length(); j++) {
                            JSONObject step = steps.optJSONObject(j);
                            if (step != null) {
                                JSONObject t = new JSONObject();
                                if (step.has("shell")) {
                                    t.put("type", "shell");
                                } else if (step.has("speak")) {
                                    t.put("type", "speak");
                                } else if (step.has("notify_pc")) {
                                    t.put("type", "notify_pc");
                                } else {
                                    t.put("type", "unknown");
                                }
                                summary.put(t);
                            }
                        }
                    }
                    item.put("stepCount", count);
                    item.put("steps", summary);
                } catch (JSONException e) {
                    Log.w(TAG, "script_list 序列化失败", e);
                }
                list.put(item);
            }
        }
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("scripts", list.toString());
        result.put("message", list.toString());
        return result;
    }

    private Map<String, Object> scriptDelete(JSONObject params) throws JSONException {
        String name = params.optString("name", "").trim();
        if (name.isEmpty()) {
            return errorResult("script_delete: name 不能为空");
        }
        synchronized (runLock) {
            JSONObject store = loadStore();
            JSONArray scripts = store.optJSONArray("scripts");
            int removed = 0;
            JSONArray kept = new JSONArray();
            if (scripts != null) {
                for (int i = 0; i < scripts.length(); i++) {
                    JSONObject s = scripts.optJSONObject(i);
                    if (s != null && name.equals(s.optString("name"))) {
                        removed++;
                        continue;
                    }
                    kept.put(s);
                }
            }
            if (removed == 0) {
                return errorResult("script_delete: 脚本不存在: " + name);
            }
            store.put("scripts", kept);
            saveStore(store);
            cancelAlarm(name);
        }
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("message", "已删除脚本: " + name);
        return result;
    }

    private Map<String, Object> scriptRun(JSONObject params) throws JSONException {
        String name = params.optString("name", "").trim();
        if (name.isEmpty()) {
            Map<String, Object> r = errorResult("script_run: name 不能为空");
            r.put("results", new JSONArray().toString());
            return r;
        }
        return runScriptInternal(name, "manual");
    }

    private Map<String, Object> scriptSchedule(JSONObject params) throws JSONException {
        String name = params.optString("name", "").trim();
        if (name.isEmpty()) {
            return errorResult("script_schedule: name 不能为空");
        }
        String time = params.optString("time", "");
        if (!TIME_RE.matcher(time).matches()) {
            return errorResult("script_schedule: time 必须是 HH:MM");
        }
        boolean enabled = params.optBoolean("enabled", true);
        synchronized (runLock) {
            JSONObject store = loadStore();
            JSONObject script = findScript(store, name);
            if (script == null) {
                return errorResult("script_schedule: 脚本不存在: " + name);
            }
            JSONObject schedule = new JSONObject();
            schedule.put("time", time);
            schedule.put("enabled", enabled);
            script.put("schedule", schedule);
            saveStore(store);
            if (enabled) {
                scheduleAlarm(name, time);
            } else {
                cancelAlarm(name);
            }
        }
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("message", "已设置脚本调度 " + name + " @ " + time
                + (enabled ? "（启用）" : "（停用）"));
        return result;
    }

    private Map<String, Object> scriptSetEnabled(JSONObject params, boolean enabled)
            throws JSONException {
        String name = params.optString("name", "").trim();
        if (name.isEmpty()) {
            return errorResult((enabled ? "script_enable" : "script_disable") + ": name 不能为空");
        }
        synchronized (runLock) {
            JSONObject store = loadStore();
            JSONObject script = findScript(store, name);
            if (script == null) {
                return errorResult((enabled ? "script_enable" : "script_disable")
                        + ": 脚本不存在: " + name);
            }
            JSONObject schedule = script.optJSONObject("schedule");
            String time = schedule != null ? schedule.optString("time", "08:00") : "08:00";
            schedule = new JSONObject();
            schedule.put("time", time);
            schedule.put("enabled", enabled);
            script.put("schedule", schedule);
            saveStore(store);
            if (enabled) {
                scheduleAlarm(name, time);
            } else {
                cancelAlarm(name);
            }
        }
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("message", "脚本调度已" + (enabled ? "启用" : "停用") + ": " + name);
        return result;
    }

    // ================== 执行 ==================

    /** 同步执行（调用方须在后台线程），fail-fast，写 lastRun + logs。 */
    private Map<String, Object> runScriptInternal(String name, String source) {
        synchronized (runLock) {
            JSONObject store = loadStore();
            JSONObject script = findScript(store, name);
            if (script == null) {
                return errorResult("脚本不存在: " + name);
            }
            JSONArray steps = script.optJSONArray("steps");
            if (steps == null || steps.length() == 0) {
                return errorResult("脚本没有可执行步骤: " + name);
            }

            JSONArray results = new JSONArray();
            String finalStatus = "success";
            for (int i = 0; i < steps.length(); i++) {
                JSONObject step = steps.optJSONObject(i);
                JSONObject stepResult = new JSONObject();
                try {
                    stepResult.put("step", i + 1);
                } catch (JSONException ignored) {
                }
                if (step == null) {
                    stepResult = failStep(i + 1, "第 " + (i + 1) + " 步不是 JSON object");
                    results.put(stepResult);
                    finalStatus = "error";
                    break;
                }

                if (step.has("shell")) {
                    stepResult = runShellStep(i + 1, step.optString("shell", ""));
                } else if (step.has("speak")) {
                    stepResult = runSpeakStep(i + 1, step.optString("speak", ""));
                } else if (step.has("notify_pc")) {
                    stepResult = runNotifyPcStep(i + 1, step.optJSONObject("notify_pc"));
                } else {
                    stepResult = failStep(i + 1,
                            "第 " + (i + 1) + " 步必须包含 shell/speak/notify_pc 之一");
                }

                results.put(stepResult);
                if (!"success".equals(stepResult.optString("status"))) {
                    finalStatus = "error";
                    break;
                }
            }

            String runTime = Calendar.getInstance().getTime().toString();
            try {
                script.put("lastRun", runTime);
                JSONArray logs = store.optJSONArray("logs");
                if (logs == null) {
                    logs = new JSONArray();
                    store.put("logs", logs);
                }
                JSONObject logEntry = new JSONObject();
                logEntry.put("name", name);
                logEntry.put("time", runTime);
                logEntry.put("source", source);
                logEntry.put("status", finalStatus);
                logEntry.put("results", results);
                logs.put(logEntry);
                while (logs.length() > MAX_LOGS) {
                    logs.remove(0);
                }
                saveStore(store);
            } catch (JSONException e) {
                Log.e(TAG, "写脚本执行结果失败", e);
            }

            Map<String, Object> result = new HashMap<>();
            result.put("success", "success".equals(finalStatus));
            result.put("status", finalStatus);
            result.put("script", name);
            result.put("source", source);
            result.put("results", results.toString());
            result.put("message", "脚本执行" + ("success".equals(finalStatus) ? "成功" : "失败")
                    + ": " + name);
            Log.i(TAG, "脚本执行完成 " + name + " status=" + finalStatus);
            return result;
        }
    }

    private JSONObject runShellStep(int index, String command) {
        JSONObject stepResult = new JSONObject();
        try {
            stepResult.put("step", index);
            stepResult.put("type", "shell");
            if (command == null || command.trim().isEmpty()) {
                return failStep(index, "shell 命令不能为空");
            }
            AdbActionExecutor executor = new AdbActionExecutor(appContext);
            JSONObject params = new JSONObject();
            params.put("command", command);
            Map<String, Object> r = executor.execute("adb_shell", params);
            if (Boolean.FALSE.equals(r.get("success"))) {
                return failStep(index, String.valueOf(r.get("error")));
            }
            stepResult.put("status", "success");
            stepResult.put("output", r.get("output") == null ? "" : r.get("output"));
            stepResult.put("exitCode", r.get("exitCode") == null ? 0 : r.get("exitCode"));
        } catch (JSONException e) {
            Log.e(TAG, "shell step 异常", e);
            return failStep(index, "shell step 异常: " + e.getMessage());
        }
        return stepResult;
    }

    private JSONObject runSpeakStep(int index, String text) {
        JSONObject stepResult = new JSONObject();
        try {
            stepResult.put("step", index);
            stepResult.put("type", "speak");
            stepResult.put("text", text);
            if (text == null || text.trim().isEmpty()) {
                return failStep(index, "speak 文本不能为空");
            }
            final CountDownLatch latch = new CountDownLatch(1);
            final boolean[] ok = {false};
            final String[] msg = {""};
            TtsEngine.getInstance().speak(appContext, text, (success, message) -> {
                ok[0] = success;
                msg[0] = message == null ? "" : message;
                latch.countDown();
            });
            if (!latch.await(TTS_WAIT_MS, TimeUnit.MILLISECONDS)) {
                return failStep(index, "TTS 等待超时（" + TTS_WAIT_MS + "ms）");
            }
            if (!ok[0]) {
                return failStep(index, msg[0]);
            }
            stepResult.put("status", "success");
            stepResult.put("message", msg[0]);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return failStep(index, "TTS 等待被打断: " + e.getMessage());
        } catch (JSONException e) {
            return failStep(index, "speak step 异常: " + e.getMessage());
        }
        return stepResult;
    }

    private JSONObject runNotifyPcStep(int index, JSONObject notify) {
        JSONObject stepResult = new JSONObject();
        try {
            stepResult.put("step", index);
            stepResult.put("type", "notify_pc");
            if (notify == null) {
                return failStep(index, "notify_pc 必须是 JSON object");
            }
            final String title = notify.optString("title", "手机脚本提醒");
            final String text = notify.optString("text", "");
            stepResult.put("title", title);
            stepResult.put("text", text);

            PcNotifySender sender = pcNotifySender;
            if (sender == null) {
                return failStep(index, "pc_notify: Bridge socket 未就绪，无法转发");
            }
            final CountDownLatch latch = new CountDownLatch(1);
            final boolean[] ok = {false};
            final String[] msg = {""};
            sender.sendNotify(title, text, new MCPSocketClient.RequestCallback() {
                @Override
                public void onResponse(JSONObject response) {
                    JSONObject data = response.optJSONObject("data");
                    ok[0] = data != null && "success".equals(data.optString("status"));
                    msg[0] = data != null && data.optString("message").length() > 0
                            ? data.optString("message") : "pc_notify 已入队";
                    latch.countDown();
                }

                @Override
                public void onError(String error) {
                    msg[0] = error == null ? "pc_notify 发送失败" : error;
                    latch.countDown();
                }
            });
            if (!latch.await(NOTIFY_WAIT_MS, TimeUnit.MILLISECONDS)) {
                return failStep(index, "pc_notify 等待 Bridge 响应超时");
            }
            if (!ok[0]) {
                return failStep(index, msg[0]);
            }
            stepResult.put("status", "success");
            stepResult.put("queued", true);
            stepResult.put("message", msg[0]);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return failStep(index, "pc_notify 等待被打断: " + e.getMessage());
        } catch (JSONException e) {
            return failStep(index, "notify_pc step 异常: " + e.getMessage());
        }
        return stepResult;
    }

    private static JSONObject failStep(int index, String error) {
        JSONObject r = new JSONObject();
        try {
            r.put("step", index);
            r.put("status", "error");
            r.put("error", error);
        } catch (JSONException ignored) {
        }
        return r;
    }

    // ================== AlarmManager 调度 ==================

    /** 重建/开机后重新注册全部启用脚本的闹钟（幂等）。 */
    public void rescheduleAll() {
        JSONObject store = loadStore();
        JSONArray scripts = store.optJSONArray("scripts");
        if (scripts == null) {
            return;
        }
        for (int i = 0; i < scripts.length(); i++) {
            JSONObject script = scripts.optJSONObject(i);
            if (script == null) {
                continue;
            }
            String name = script.optString("name", "");
            JSONObject schedule = script.optJSONObject("schedule");
            if (name.isEmpty()) {
                continue;
            }
            if (schedule != null && schedule.optBoolean("enabled", false)) {
                String time = schedule.optString("time", "08:00");
                if (TIME_RE.matcher(time).matches()) {
                    scheduleAlarm(name, time);
                } else {
                    cancelAlarm(name);
                }
            } else {
                cancelAlarm(name);
            }
        }
        Log.i(TAG, "rescheduleAll 完成，共处理 " + scripts.length() + " 个脚本");
    }

    /** 执行后重挂（精确闹钟一次性的需要）；已停用则不动。 */
    private void reArmIfEnabled(String name) {
        synchronized (runLock) {
            JSONObject store = loadStore();
            JSONObject script = findScript(store, name);
            if (script == null) {
                return;
            }
            JSONObject schedule = script.optJSONObject("schedule");
            if (schedule != null && schedule.optBoolean("enabled", false)) {
                String time = schedule.optString("time", "08:00");
                cancelAlarm(name);
                scheduleAlarm(name, time);
            }
        }
    }

    private PendingIntent alarmIntent(String name) {
        Intent intent = new Intent(appContext, ScriptAlarmReceiver.class);
        intent.setAction(ALARM_ACTION);
        intent.putExtra(ALARM_EXTRA_NAME, name);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        return PendingIntent.getBroadcast(appContext, name.hashCode(), intent, flags);
    }

    private void scheduleAlarm(String name, String hhmm) {
        AlarmManager am = (AlarmManager) appContext.getSystemService(Context.ALARM_SERVICE);
        if (am == null) {
            Log.e(TAG, "AlarmManager 为 null，无法调度 " + name);
            return;
        }
        PendingIntent pi = alarmIntent(name);
        long triggerAt = nextTriggerMillis(hhmm);
        boolean exact = canScheduleExactAlarms(am);
        if (exact) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi);
        } else {
            // API 31+ 无精确闹钟权限：setInexactRepeating 兜底（每日重复）
            am.setInexactRepeating(AlarmManager.RTC_WAKEUP, triggerAt,
                    AlarmManager.INTERVAL_DAY, pi);
        }
        Log.i(TAG, "闹钟已注册 name=" + name + " time=" + hhmm
                + " next=" + triggerAt + " exact=" + exact);
    }

    /** compileSdk=30 无法直接引用 API 31 的 canScheduleExactAlarms，用反射探测。 */
    private static boolean canScheduleExactAlarms(AlarmManager am) {
        if (Build.VERSION.SDK_INT < 31) {
            return false;
        }
        try {
            java.lang.reflect.Method m = AlarmManager.class.getMethod("canScheduleExactAlarms");
            return Boolean.TRUE.equals(m.invoke(am));
        } catch (Exception e) {
            Log.w(TAG, "canScheduleExactAlarms 反射调用失败: " + e.getMessage());
            return false;
        }
    }

    private void cancelAlarm(String name) {
        AlarmManager am = (AlarmManager) appContext.getSystemService(Context.ALARM_SERVICE);
        if (am != null) {
            am.cancel(alarmIntent(name));
        }
    }

    private static long nextTriggerMillis(String hhmm) {
        String[] parts = hhmm.split(":");
        int hour = Integer.parseInt(parts[0]);
        int minute = Integer.parseInt(parts[1]);
        Calendar cal = Calendar.getInstance();
        cal.set(Calendar.HOUR_OF_DAY, hour);
        cal.set(Calendar.MINUTE, minute);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        long now = System.currentTimeMillis();
        if (cal.getTimeInMillis() <= now) {
            cal.add(Calendar.DAY_OF_MONTH, 1);
        }
        return cal.getTimeInMillis();
    }

    // ================== 存储 ==================

    private JSONObject loadStore() {
        File f = new File(appContext.getFilesDir(), FILE_NAME);
        JSONObject store = new JSONObject();
        if (!f.exists()) {
            try {
                store.put("scripts", new JSONArray());
                store.put("logs", new JSONArray());
            } catch (JSONException ignored) {
            }
            return store;
        }
        try (BufferedReader reader = new BufferedReader(new FileReader(f))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
            store = new JSONObject(sb.toString());
            if (!store.has("scripts")) {
                store.put("scripts", new JSONArray());
            }
            if (!store.has("logs")) {
                store.put("logs", new JSONArray());
            }
        } catch (Exception e) {
            Log.e(TAG, "读取 scripts.json 失败，返回空存储: " + e.getMessage());
            try {
                store = new JSONObject();
                store.put("scripts", new JSONArray());
                store.put("logs", new JSONArray());
            } catch (JSONException ignored) {
            }
        }
        return store;
    }

    private void saveStore(JSONObject store) {
        File dir = appContext.getFilesDir();
        File tmp = new File(dir, FILE_NAME + ".tmp");
        File target = new File(dir, FILE_NAME);
        try {
            try (FileWriter writer = new FileWriter(tmp)) {
                writer.write(store.toString(2));
            }
            if (tmp.exists() && tmp.renameTo(target)) {
                return;
            }
            // rename 失败（罕见）直接覆盖写
            try (FileWriter writer = new FileWriter(target)) {
                writer.write(store.toString(2));
            }
        } catch (Exception e) {
            Log.e(TAG, "写 scripts.json 失败", e);
        }
    }

    private static JSONObject findScript(JSONObject store, String name) {
        JSONArray scripts = store.optJSONArray("scripts");
        if (scripts == null) {
            return null;
        }
        for (int i = 0; i < scripts.length(); i++) {
            JSONObject s = scripts.optJSONObject(i);
            if (s != null && name.equals(s.optString("name"))) {
                return s;
            }
        }
        return null;
    }

    private static String validateSteps(JSONArray steps) {
        if (steps == null || steps.length() == 0) {
            return "steps 必须是非空数组";
        }
        for (int i = 0; i < steps.length(); i++) {
            JSONObject step = steps.optJSONObject(i);
            if (step == null) {
                return "第 " + (i + 1) + " 步必须是 JSON object";
            }
            boolean hasShell = step.optString("shell", "").length() > 0;
            boolean hasSpeak = step.optString("speak", "").length() > 0;
            boolean hasNotify = step.has("notify_pc");
            if (!hasShell && !hasSpeak && !hasNotify) {
                return "第 " + (i + 1) + " 步必须包含 shell/speak/notify_pc 之一"
                        + "（shell/speak 为非空字符串，notify_pc 为 object）";
            }
        }
        return null;
    }

    private static String validateSchedule(JSONObject schedule) {
        if (schedule == null) {
            return null;
        }
        String time = schedule.optString("time", "");
        if (!TIME_RE.matcher(time).matches()) {
            return "schedule.time 必须是 HH:MM";
        }
        if (schedule.has("enabled") && !schedule.isNull("enabled")) {
            Object enabled = schedule.opt("enabled");
            if (!(enabled instanceof Boolean)) {
                return "schedule.enabled 必须是布尔值";
            }
        }
        return null;
    }

    private static Map<String, Object> errorResult(String error) {
        Map<String, Object> result = new HashMap<>();
        result.put("success", false);
        result.put("error", error);
        return result;
    }
}
