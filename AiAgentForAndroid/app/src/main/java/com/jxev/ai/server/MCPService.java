package com.jxev.ai.server;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.BroadcastReceiver;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.PowerManager;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import com.jxev.ai.ConfigManager;
import com.jxev.ai.MainActivity;
import com.jxev.ai.TunnelManager;
import com.jxev.ai.client.MCPSocketClient;
import com.jxev.ai.client.ScriptEngine;

/**
 * 纯后台服务（2026-08-14 重构）：无 HTTP 注册（registerDevice/OkHttp 已移除）。
 * onCreate 直接启动 TLS socket 连接 + 前台服务（startForeground），START_STICKY。
 */
public class MCPService extends Service {
    private static final String TAG = "MCPService";

    // 前台服务通知相关
    private static final int NOTIF_ID = 1001;
    private static final String CHANNEL_ID = "mcp_service";
    private static final String CHANNEL_NAME = "MCP \u670d\u52a1\u8fd0\u884c\u4e2d";
    private static final String CHANNEL_DESC = "\u4fdd\u6301 MCP \u4e0e\u670d\u52a1\u5668\u7684\u8fde\u63a5\uff0c\u907f\u514d\u540e\u53f0\u88ab\u7cfb\u7edf\u6740\u6b7b";

    // 供极简状态页展示（无 UI 交互，仅显示）
    private static volatile String lastStatus = "\u670d\u52a1\u8fd0\u884c\u4e2d";

    private MCPSocketClient socketClient;
    private MCPListenServer listenServer;
    private TunnelManager tunnelManager;
    private NotificationManager notificationManager;
    // MIUI 灭屏后会冻结 app 进程（实测：灭屏约 1 分钟后监听静默，亮屏立刻恢复），
    // 持有 PARTIAL_WAKE_LOCK 防冻结（Termux sshd 同款保活姿势）。
    private PowerManager.WakeLock wakeLock;
    // 灭屏监听：灭屏瞬间拉起 1px 保活 Activity（TOP_SLEEPING 不进冻结名单）
    private BroadcastReceiver screenReceiver;

    private static volatile boolean sRunning = false;

    /** 看门狗判活依据：服务是否处于运行状态。 */
    public static boolean isRunning() {
        return sRunning;
    }

    public static String getLastStatus() {
        return lastStatus;
    }

    private java.util.concurrent.ScheduledExecutorService hubPump;

    private void startHubPump() {
        if (hubPump != null) return;
        hubPump = java.util.concurrent.Executors.newSingleThreadScheduledExecutor();
        hubPump.scheduleWithFixedDelay(() -> {
            try {
                com.jxev.ai.hub.HubUploader.flush(getApplicationContext(), socketClient);
            } catch (Throwable t) {
                Log.w(TAG, "hub pump: " + t.getMessage());
            }
        }, 10, 20, java.util.concurrent.TimeUnit.SECONDS);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        startHubPump();
        Log.i(TAG, "MCPService onCreate: \u76f4\u63a5\u542f\u52a8 socket \u8fde\u63a5\uff08\u65e0 HTTP \u6ce8\u518c\uff09");

        // 1) \u521b\u5efa\u901a\u77e5\u901a\u9053\uff08API >= 26\uff09
        createNotificationChannel();

        // 2) \u7acb\u5373\u63d0\u5347\u4e3a\u524d\u53f0\u670d\u52a1
        startForeground(NOTIF_ID, buildNotification("\u670d\u52a1\u8fd0\u884c\u4e2d"));

        sRunning = true;

        // 3) 悬浮窗保活（用户指定方案，同 VMOS）：TYPE_APPLICATION_OVERLAY 使进程
        //    保持"可感知"级，绕过 MIUI 灭屏冻结（前台服务+wakelock 不够，实测）
        if (KeepAliveOverlay.show(this)) {
            Log.i(TAG, "悬浮窗保活已启用");
        } else {
            Log.w(TAG, "悬浮窗保活未启用（缺 SYSTEM_ALERT_WINDOW 授权），灭屏可能被冻结");
        }

        // 3b) 恢复上次没撤的提醒条（进程被杀/重启后条要自己回来）
        ReminderOverlay.restore(this);
        // 3c) 恢复 info-hub 常驻通知（数量来自上次同步的摘要）
        HubNotifier.update(this, HubSummary.count(this));

        // 4) 防冻结：持有 partial wakelock（灭屏后仍可被 wg 侧直连）
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm != null) {
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MCP:listen");
            wakeLock.setReferenceCounted(false);
            wakeLock.acquire();
            Log.i(TAG, "已持有 partial wakelock（防 MIUI 冻结）");
        }

        // 4b) 灭屏监听：拉起 1px 保活 Activity（进程持 SAW 可后台启动 Activity）
        screenReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (Intent.ACTION_SCREEN_OFF.equals(intent.getAction())) {
                    try {
                        Intent i = new Intent(MCPService.this, KeepAliveActivity.class);
                        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        startActivity(i);
                        Log.i(TAG, "灭屏：已拉起 1px 保活 Activity");
                    } catch (Exception e) {
                        Log.e(TAG, "灭屏保活 Activity 启动失败", e);
                    }
                }
            }
        };
        IntentFilter screenFilter = new IntentFilter(Intent.ACTION_SCREEN_OFF);
        registerReceiver(screenReceiver, screenFilter);

        // 4) wg \u76f4\u8fde\u76d1\u542c\uff08\u4efb\u610f wg peer \u53ef\u62e8\u5165\uff0c\u4e0d\u4f9d\u8d56 ECS\uff09
        if (ConfigManager.isListenEnabled(this)) {
            listenServer = new MCPListenServer(this, ConfigManager.getListenPort(this));
            listenServer.setAddressListener(address -> updateStatus("\u670d\u52a1\u8fd0\u884c\u4e2d"));
            listenServer.start();
        } else {
            Log.i(TAG, "wg \u76f4\u8fde\u76d1\u542c\u5df2\u5728\u914d\u7f6e\u4e2d\u5173\u95ed\uff08listen_enabled=false\uff09");
        }

        // 4) \u811a\u672c\u5f15\u64ce pc_notify \u901a\u9053\uff1a\u51fa\u7ad9\u4f18\u5148\uff0c\u51fa\u7ad9\u65ad\u5f00\u65f6\u8d70 wg \u76f4\u8fde\u4f1a\u8bdd
        ScriptEngine.getInstance(this).setPcNotifySender((title, text, cb) -> {
            if (socketClient != null && socketClient.isConnected()) {
                java.util.Map<String, Object> params = new java.util.HashMap<>();
                params.put("title", title);
                params.put("text", text);
                socketClient.sendRequest("pc_notify", params, cb);
            } else if (listenServer != null && listenServer.hasClients()) {
                listenServer.sendPcNotify(title, text, cb);
            } else if (cb != null) {
                cb.onError("\u65e0\u53ef\u7528\u901a\u9053\uff08\u51fa\u7ad9\u672a\u8fde\u63a5\u4e14\u65e0 wg \u76f4\u8fde\u5ba2\u6237\u7aef\uff09");
            }
        });

        // 5) \u8bfb\u53d6\u914d\u7f6e\u5e76\u542f\u52a8 TLS socket \u51fa\u7ad9\u8fde\u63a5\uff08\u4fdd\u7559 ECS \u94fe\u8def\uff09
        startSocketClient();

        // 6) App \u91cd\u5efa/\u8fdb\u7a0b\u91cd\u542f\u540e\u91cd\u65b0\u6ce8\u518c\u811a\u672c\u95f9\u949f\uff08\u4e0e BootReceiver \u4e92\u8865\uff09
        ScriptEngine.getInstance(this).rescheduleAll();

        // 7) \u770b\u95e8\u72d7\u81ea\u7eed\uff1a\u88ab MIUI \u6740\u638b\u540e\u7531\u7cfb\u7edf\u95f9\u949f\u590d\u6d3b
        WatchdogReceiver.schedule(this);
    }

    /** \u4ece SharedPreferences/assets \u8bfb\u53d6\u914d\u7f6e\uff0c\u521b\u5efa MCPSocketClient \u5e76\u8fde\u63a5\u3002 */
    private void startSocketClient() {
        // 启动过程会阻塞等待 SOCKS 就绪（最长 45s），不能占主线程：
        // MIUI 的 AppScout 会把主线程长时间 sleep 判为 HANG，然后整个 uid 被 freeze。
        Thread t = new Thread(this::startSocketClientBlocking, "mcp-startup");
        t.setDaemon(true);
        t.start();
    }

    private void startSocketClientBlocking() {
        String host = ConfigManager.getServerHost(this);
        int port = ConfigManager.getServerSocketPort(this);
        Log.i(TAG, "socket \u8fde\u63a5\u76ee\u6807: " + host + ":" + port
                + " \uff08\u6267\u884c\u5c42\u4f9d\u8d56\u672c\u5730 adb 127.0.0.1:5555\uff0c\u82e5\u4e0d\u53ef\u8fde\u8bf7\u91cd\u65b0\u5f00\u542f\u65e0\u7ebf\u8c03\u8bd5: adb tcpip 5555\uff09");

        // Built-in encrypted tunnel (tunnel_enabled=true): user-space WG first, then SOCKS egress.
        // Fail-closed: if the tunnel is not ready, do not start the control channel at all.
        int socksPort = 0;
        if (ConfigManager.isTunnelEnabled(this)) {
            tunnelManager = new TunnelManager(getApplicationContext());
            if (tunnelManager.start()) {
                socksPort = tunnelManager.socksPort();
                Log.i(TAG, "built-in tunnel ready, control channel via socks5 127.0.0.1:" + socksPort);
            } else {
                Log.e(TAG, "built-in tunnel not ready, control channel NOT started (fail-closed)");
                updateStatus("built-in tunnel not ready, control channel refused");
                return;
            }
        }
        socketClient = new MCPSocketClient(getApplicationContext(), host, port, socksPort);
        socketClient.setConnectionCallback(new MCPSocketClient.ConnectionCallback() {
            @Override
            public void onConnected() {
                updateStatus("\u5df2\u8fde\u63a5 " + host + ":" + port);
            }

            @Override
            public void onDisconnected(String reason) {
                updateStatus("\u8fde\u63a5\u65ad\u5f00\uff0c\u81ea\u52a8\u91cd\u8fde\u4e2d: " + reason);
            }

            @Override
            public void onConnectionFailed(String error) {
                updateStatus("\u8fde\u63a5\u5931\u8d25\uff0c\u81ea\u52a8\u91cd\u8fde\u4e2d: " + error);
            }
        });
        socketClient.connect();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (notificationManager == null) {
                Log.e(TAG, "NotificationManager \u4e3a\u7a7a\uff0c\u65e0\u6cd5\u521b\u5efa channel");
                return;
            }
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    CHANNEL_NAME,
                    NotificationManager.IMPORTANCE_LOW);
            channel.setDescription(CHANNEL_DESC);
            channel.setShowBadge(false);
            channel.setSound(null, null);
            channel.enableLights(false);
            channel.enableVibration(false);
            notificationManager.createNotificationChannel(channel);
        }
    }

    /** \u6784\u9020\u524d\u53f0\u670d\u52a1\u901a\u77e5\u3002\u70b9\u51fb\u901a\u77e5\u6253\u5f00\u6781\u7b80\u72b6\u6001\u9875\u3002 */
    private Notification buildNotification(String contentText) {
        Intent launchIntent = new Intent(this, MainActivity.class);
        launchIntent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int pendingFlags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            pendingFlags |= PendingIntent.FLAG_IMMUTABLE;
        }
        PendingIntent contentIntent = PendingIntent.getActivity(this, 0, launchIntent, pendingFlags);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_notify_sync_noanim)
                .setContentTitle("MCP \u5ba2\u6237\u7aef")
                .setContentText(contentText)
                .setContentIntent(contentIntent)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .build();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // \u5c4f\u7b49\uff1a\u5237\u65b0\u901a\u77e5\uff1bSTART_STICKY \u4fdd\u8bc1\u8fdb\u7a0b\u88ab\u56de\u6536\u540e\u81ea\u52a8\u91cd\u542f
        updateStatus("\u670d\u52a1\u8fd0\u884c\u4e2d");
        return START_STICKY;
    }

    /** \u72b6\u6001\u6587\u6838\u540e\u62fc\u63a5 wg \u76f4\u8fde\u76d1\u542c\u5730\u5740\uff0c\u4fbf\u4e8e\u901a\u77e5\u680f\u4e00\u773c\u770b\u61c2\u53cc\u901a\u9053\u3002 */
    private String describeWithListen(String base) {
        if (listenServer != null && !listenServer.getListenAddress().isEmpty()) {
            return base + " \uff5c \u76f4\u8fde\u76d1\u542c " + listenServer.getListenAddress();
        }
        return base;
    }

    private void updateStatus(String status) {
        lastStatus = status;
        if (notificationManager != null) {
            try {
                notificationManager.notify(NOTIF_ID, buildNotification(describeWithListen(status)));
            } catch (Exception e) {
                Log.w(TAG, "\u5237\u65b0\u524d\u53f0\u901a\u77e5\u5931\u8d25", e);
            }
        }
        Log.i(TAG, "\u72b6\u6001\u66f4\u65b0: " + describeWithListen(status));
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null; // \u7eaf\u540e\u53f0\u670d\u52a1\uff0c\u65e0\u7ed1\u5b9a\u9700\u6c42
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        sRunning = false;
        KeepAliveOverlay.hide();
        if (screenReceiver != null) {
            try {
                unregisterReceiver(screenReceiver);
            } catch (Exception ignored) {
            }
            screenReceiver = null;
        }
        if (wakeLock != null) {
            try {
                wakeLock.release();
            } catch (Exception ignored) {
            }
            wakeLock = null;
        }
        if (listenServer != null) {
            listenServer.stop();
            listenServer = null;
        }
        if (socketClient != null) {
            socketClient.cleanup();
            socketClient = null;
        }
        if (tunnelManager != null) {
            tunnelManager.stop();
            tunnelManager = null;
        }
        if (notificationManager != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            notificationManager.cancel(NOTIF_ID);
        }
        Log.i(TAG, "MCPService onDestroy");
    }
}
