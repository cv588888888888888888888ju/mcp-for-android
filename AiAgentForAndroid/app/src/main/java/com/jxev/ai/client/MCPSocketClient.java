package com.jxev.ai.client;

import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.jxev.ai.ConfigManager;
import com.jxev.ai.MCPTls;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.net.ssl.SSLSocket;

/**
 * MCP Socket Client
 * TLS socket + token 握手 + 重连 + 心跳。
 * 收到 action 后交给 {@link AdbActionExecutor}（本地 adb shell）执行。
 */
public class MCPSocketClient {
    private static final String TAG = "MCPSocketClient";

    private String serverHost;
    private int serverPort;
    // 内建隧道的本机 SOCKS5 端口；0 = 直连
    private final int socksPort;
    private Socket socket;
    private BufferedWriter writer;
    private BufferedReader reader;
    private volatile boolean isConnected = false;
    private volatile boolean isRunning = false;
    // 设备 token（握手时携带，与服务器 MCP_DEVICE_TOKEN 对齐）
    private final String deviceToken;
    // 自动重连控制
    private volatile boolean reconnectEnabled = false;
    private volatile long lastInboundMs = System.currentTimeMillis();
    private Thread reconnectThread;
    // 重连退避（毫秒）：1s -> 2s -> 4s -> ... 上限 60s
    private static final long RECONNECT_INITIAL_MS = 1000L;
    private static final long RECONNECT_MAX_MS = 60000L;

    private final String deviceId;
    private final Context context;

    private final ExecutorService executorService = Executors.newCachedThreadPool();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private final Map<String, RequestCallback> pendingRequests = new ConcurrentHashMap<>();

    private ConnectionCallback connectionCallback;

    private static final long HEARTBEAT_INTERVAL = 30000; // 30 秒
    private static final long HEARTBEAT_TIMEOUT_MS = 90000; // 90 秒收不到任何入站帧就判定半开连接

    public interface RequestCallback {
        void onResponse(JSONObject response);
        void onError(String error);
    }

    public interface ActionResultCallback {
        void onResult(Map<String, Object> result, String error);
    }

    public interface ConnectionCallback {
        void onConnected();
        void onDisconnected(String reason);
        void onConnectionFailed(String error);
    }

    public MCPSocketClient(Context context, String serverHost, int serverPort) {
        this(context, serverHost, serverPort, 0);
    }

    /**
     * @param socksPort >0 时经本机内建隧道（SOCKS5 127.0.0.1:socksPort）出站；0 = 直连（旧路径）
     */
    public MCPSocketClient(Context context, String serverHost, int serverPort, int socksPort) {
        this.context = context;
        this.serverHost = serverHost;
        this.serverPort = serverPort;
        this.socksPort = socksPort;
        this.deviceId = getDeviceId();
        this.deviceToken = ConfigManager.getDeviceToken(context);
    }

    public void setConnectionCallback(ConnectionCallback callback) {
        this.connectionCallback = callback;
    }

    public void connect() {
        if (isConnected) {
            Log.w(TAG, "Already connected to server");
            return;
        }
        isRunning = true;
        reconnectEnabled = true;
        executorService.execute(this::connectOnce);
        startReconnectLoop();
    }

    /** 单次建链（TLS 包裹 + 握手），失败时不上抛，由 reconnect 循环接管 */
    private void connectOnce() {
        if (isConnected) {
            return;
        }
        try {
            MCPTls tls = MCPTls.getInstance(context);
            Socket raw;
            if (socksPort > 0) {
                // 经内建隧道出站：先连本机 SOCKS5，再在其上包 TLS（目标仍是 wg 内网地址）
                Socket under = new Socket(new java.net.Proxy(java.net.Proxy.Type.SOCKS,
                        new InetSocketAddress("127.0.0.1", socksPort)));
                under.connect(new InetSocketAddress(serverHost, serverPort), 15000);
                raw = tls.getSSLSocketFactory().createSocket(under, serverHost, serverPort, true);
            } else {
                raw = tls.getSSLSocketFactory().createSocket();
                raw.connect(new InetSocketAddress(serverHost, serverPort), 10000);
            }
            if (raw instanceof SSLSocket) {
                ((SSLSocket) raw).startHandshake();
            }
            raw.setKeepAlive(true);
            socket = raw;
            lastInboundMs = System.currentTimeMillis();
            OutputStream os = socket.getOutputStream();
            InputStream is = socket.getInputStream();
            writer = new BufferedWriter(new OutputStreamWriter(os, "UTF-8"));
            reader = new BufferedReader(new InputStreamReader(is, "UTF-8"));

            performHandshake();

            isRunning = true;
            listenForMessages();
            startHeartbeat();
            isConnected = true;

            if (connectionCallback != null) {
                mainHandler.post(connectionCallback::onConnected);
            }
        } catch (IOException e) {
            Log.e(TAG, "Connection failed: " + e.getMessage());
            if (connectionCallback != null) {
                final String error = e.getMessage();
                mainHandler.post(() -> connectionCallback.onConnectionFailed(error));
            }
            cleanupSocketResourcesOnly();
        }
    }

    private void cleanupSocketResourcesOnly() {
        try { if (socket != null && !socket.isClosed()) socket.close(); } catch (IOException ignored) {}
        try { if (writer != null) writer.close(); } catch (IOException ignored) {}
        try { if (reader != null) reader.close(); } catch (IOException ignored) {}
        socket = null;
        writer = null;
        reader = null;
        isConnected = false;
    }

    private void startReconnectLoop() {
        if (reconnectThread != null && reconnectThread.isAlive()) {
            return;
        }
        reconnectThread = new Thread(() -> {
            long backoff = RECONNECT_INITIAL_MS;
            while (reconnectEnabled) {
                if (isConnected) {
                    try { Thread.sleep(1000); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); break; }
                    continue;
                }
                if (!reconnectEnabled) break;
                try {
                    Log.i(TAG, "等待 " + backoff + "ms 后重连...");
                    Thread.sleep(backoff);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
                if (!reconnectEnabled) break;
                if (isConnected) continue;
                Log.i(TAG, "尝试重连 -> " + serverHost + ":" + serverPort);
                executorService.execute(this::connectOnce);
                backoff = Math.min(backoff * 2, RECONNECT_MAX_MS);
            }
            Log.i(TAG, "重连循环已退出");
        }, "MCP-Reconnect");
        reconnectThread.setDaemon(true);
        reconnectThread.start();
    }

    public void disconnect() {
        reconnectEnabled = false;
        isRunning = false;
        closeConnection("User initiated disconnect");
    }

    private void closeConnection(String reason) {
        isRunning = false;
        try {
            if (socket != null && !socket.isClosed()) socket.close();
            if (writer != null) writer.close();
            if (reader != null) reader.close();
        } catch (IOException e) {
            Log.e(TAG, "Error closing connection: " + e.getMessage());
        }
        socket = null;
        writer = null;
        reader = null;
        isConnected = false;

        for (Map.Entry<String, RequestCallback> entry : pendingRequests.entrySet()) {
            entry.getValue().onError("Connection closed: " + reason);
        }
        pendingRequests.clear();

        if (connectionCallback != null) {
            mainHandler.post(() -> connectionCallback.onDisconnected(reason));
        }
    }

    public void cleanup() {
        disconnect();
    }

    /** TLS 握手：type=handshake + deviceId + token + deviceInfo + capabilities */
    private void performHandshake() throws IOException {
        try {
            JSONObject handshakeMessage = new JSONObject();
            handshakeMessage.put("type", "handshake");
            handshakeMessage.put("deviceId", deviceId);
            handshakeMessage.put("timestamp", System.currentTimeMillis() / 1000.0);
            if (deviceToken != null && !deviceToken.isEmpty()) {
                handshakeMessage.put("token", deviceToken);
            }

            JSONObject deviceInfo = new JSONObject();
            deviceInfo.put("model", Build.MODEL);
            deviceInfo.put("manufacturer", Build.MANUFACTURER);
            deviceInfo.put("osVersion", Build.VERSION.RELEASE);
            deviceInfo.put("sdkVersion", Build.VERSION.SDK_INT);
            handshakeMessage.put("deviceInfo", deviceInfo);

            JSONArray capabilities = new JSONArray();
            for (String capability : getDeviceCapabilities()) {
                capabilities.put(capability);
            }
            handshakeMessage.put("capabilities", capabilities);

            String messageStr = handshakeMessage.toString();
            Log.d(TAG, "Sending handshake message: " + messageStr);
            writer.write(messageStr + "\n");
            writer.flush();

            String response = reader.readLine();
            if (response == null) {
                throw new IOException("No handshake response received");
            }
            Log.d(TAG, "Received handshake response: " + response);

            JSONObject responseObj = new JSONObject(response);
            String type = responseObj.optString("type");
            String status = responseObj.optString("status");
            if (!"handshake_response".equals(type) || !"ok".equals(status)) {
                throw new IOException("Handshake failed: " + response);
            }
            Log.i(TAG, "Handshake successful");
        } catch (JSONException e) {
            Log.e(TAG, "JSON error in handshake: " + e.getMessage());
            throw new IOException("Error creating handshake message: " + e.getMessage());
        }
    }

    private String[] getDeviceCapabilities() {
        return new String[]{
                "click", "long_click", "swipe", "type_text", "scroll",
                "launch_app", "press_back", "press_home", "press_recents",
                "get_ui_state", "get_installed_apps", "screenshot",
                "get_notifications", "dismiss_notification", "adb_shell",
                "get_screen_size", "open_url", "key_event", "volume", "media_control",
                "wifi", "bluetooth", "airplane_mode", "brightness",
                "get_current_app", "battery_info", "clear_app_data", "uninstall_app",
                "install_app", "file_read", "file_write", "wait_for_element",
                "set_rotation", "open_app_settings",
                "tts_play", "script_create", "script_list", "script_delete",
                "script_run", "script_schedule", "script_enable", "script_disable",
                "hub_query", "hub_stats"
        };
    }

    private void startHeartbeat() {
        executorService.execute(() -> {
            while (isRunning && isConnected) {
                try {
                    Thread.sleep(HEARTBEAT_INTERVAL);
                    if (isConnected && System.currentTimeMillis() - lastInboundMs > HEARTBEAT_TIMEOUT_MS) {
                        Log.w(TAG, "心跳超时（" + (System.currentTimeMillis() - lastInboundMs)
                                + "ms 无入站），主动断开重连");
                        closeConnection("heartbeat timeout");
                        break;
                    }
                    if (isConnected) {
                        JSONObject heartbeat = new JSONObject();
                        heartbeat.put("type", "heartbeat");
                        heartbeat.put("deviceId", deviceId);
                        heartbeat.put("timestamp", System.currentTimeMillis() / 1000.0);
                        sendMessage(heartbeat);
                    }
                } catch (InterruptedException e) {
                    Log.w(TAG, "Heartbeat interrupted: " + e.getMessage());
                    Thread.currentThread().interrupt();
                    break;
                } catch (JSONException e) {
                    Log.e(TAG, "Error creating heartbeat message: " + e.getMessage());
                } catch (IOException e) {
                    Log.e(TAG, "Error sending heartbeat: " + e.getMessage());
                    closeConnection("Heartbeat failed: " + e.getMessage());
                    break;
                }
            }
        });
    }

    private void listenForMessages() {
        executorService.execute(() -> {
            try {
                String message;
                while (isRunning && (message = reader.readLine()) != null) {
                    lastInboundMs = System.currentTimeMillis();
                    try {
                        JSONObject jsonMessage = new JSONObject(message);
                        String messageType = jsonMessage.optString("type");
                        switch (messageType) {
                            case "request":
                                handleRequest(jsonMessage);
                                break;
                            case "response":
                                handleResponse(jsonMessage);
                                break;
                            case "welcome":
                                Log.i(TAG, "Received welcome message: " + jsonMessage.optString("message"));
                                break;
                            case "heartbeat_response":
                                break;
                            default:
                                Log.w(TAG, "Received unknown message type: " + messageType);
                                break;
                        }
                    } catch (JSONException e) {
                        Log.e(TAG, "Error parsing message: " + e.getMessage());
                    }
                }
            } catch (IOException e) {
                if (isRunning) {
                    Log.e(TAG, "Error reading from socket: " + e.getMessage());
                    closeConnection("Connection error: " + e.getMessage());
                }
            }
        });
    }

    private void handleRequest(JSONObject requestMessage) {
        try {
            String requestId = requestMessage.getString("requestId");
            String actionType = requestMessage.getString("actionType");
            JSONObject parameters = requestMessage.optJSONObject("parameters");
            if (parameters == null) {
                parameters = new JSONObject();
            }
            Log.i(TAG, "Received request: " + actionType + " with ID " + requestId);

            executeAction(actionType, parameters, (result, error) -> {
                try {
                    JSONObject responseMessage = new JSONObject();
                    responseMessage.put("type", "response");
                    responseMessage.put("requestId", requestId);

                    JSONObject data = new JSONObject();
                    if (error != null) {
                        data.put("status", "error");
                        data.put("error", error);
                    } else {
                        data.put("status", "success");
                        if (result != null) {
                            for (Map.Entry<String, Object> entry : result.entrySet()) {
                                data.put(entry.getKey(), entry.getValue());
                            }
                        }
                    }
                    responseMessage.put("data", data);
                    responseMessage.put("timestamp", System.currentTimeMillis() / 1000.0);

                    executorService.execute(() -> {
                        try {
                            sendMessage(responseMessage);
                        } catch (IOException e) {
                            Log.e(TAG, "Error sending response: " + e.getMessage());
                        }
                    });
                } catch (JSONException e) {
                    Log.e(TAG, "Error sending response: " + e.getMessage());
                }
            });
        } catch (JSONException e) {
            Log.e(TAG, "Error parsing request: " + e.getMessage());
        }
    }

    /** 处理 Bridge 回执：匹配 pendingRequests 并回调（pc_notify 等 sendRequest 依赖此链路）。 */
    private void handleResponse(JSONObject message) {
        String requestId = message.optString("requestId", "");
        RequestCallback callback = pendingRequests.remove(requestId);
        if (callback == null) {
            Log.w(TAG, "收到未知 requestId 的响应: " + requestId);
            return;
        }
        callback.onResponse(message);
    }

    /** 执行 action：统一走 ActionDispatcher（与 wg 直连监听会话共用），无无障碍依赖。 */
    private void executeAction(String actionType, JSONObject parameters, ActionResultCallback callback) {
        ActionDispatcher.dispatch(context, actionType, parameters, callback::onResult);
    }

    private ScriptEngine getScriptEngine() {
        return ScriptEngine.getInstance(context);
    }

    /** 供信息中枢等模块主动发消息（连接不可用时返回 false）。 */
    public boolean sendJson(JSONObject message) {
        if (!isConnected()) return false;
        try {
            sendMessage(message);
            return true;
        } catch (Exception e) {
            Log.w(TAG, "sendJson failed: " + e.getMessage());
            return false;
        }
    }

    private synchronized void sendMessage(JSONObject message) throws IOException {
        if (!isConnected || writer == null) {
            throw new IOException("Not connected to server");
        }
        String messageStr = message.toString() + "\n";
        Log.d(TAG, "Sending message: " + messageStr);
        try {
            writer.write(messageStr);
            writer.flush();
        } catch (IOException e) {
            Log.e(TAG, "Error sending message: " + e.getMessage());
            isConnected = false;
            throw e;
        }
    }

    public void sendRequest(String actionType, Map<String, Object> parameters, RequestCallback callback) {
        if (!isConnected) {
            if (callback != null) {
                callback.onError("Not connected to server");
            }
            return;
        }
        executorService.execute(() -> {
            try {
                String requestId = UUID.randomUUID().toString();
                JSONObject requestMessage = new JSONObject();
                requestMessage.put("type", "request");
                requestMessage.put("requestId", requestId);
                requestMessage.put("actionType", actionType);

                JSONObject paramsObj = new JSONObject();
                if (parameters != null) {
                    for (Map.Entry<String, Object> entry : parameters.entrySet()) {
                        paramsObj.put(entry.getKey(), entry.getValue());
                    }
                }
                requestMessage.put("parameters", paramsObj);
                requestMessage.put("timestamp", System.currentTimeMillis() / 1000.0);

                if (callback != null) {
                    pendingRequests.put(requestId, callback);
                    mainHandler.postDelayed(() -> {
                        RequestCallback timeoutCallback = pendingRequests.remove(requestId);
                        if (timeoutCallback != null) {
                            timeoutCallback.onError("Request timed out");
                        }
                    }, 30000);
                }
                sendMessage(requestMessage);
            } catch (JSONException | IOException e) {
                Log.e(TAG, "Error sending request: " + e.getMessage());
                if (callback != null) {
                    callback.onError(e.getMessage());
                }
            }
        });
    }

    public void sendEvent(String eventType, Map<String, Object> data) {
        if (!isConnected) {
            Log.w(TAG, "Cannot send event, not connected to server");
            return;
        }
        executorService.execute(() -> {
            try {
                JSONObject eventMessage = new JSONObject();
                eventMessage.put("type", "event");
                eventMessage.put("eventType", eventType);
                eventMessage.put("deviceId", deviceId);
                if (data != null) {
                    for (Map.Entry<String, Object> entry : data.entrySet()) {
                        eventMessage.put(entry.getKey(), entry.getValue());
                    }
                }
                eventMessage.put("timestamp", System.currentTimeMillis() / 1000.0);
                sendMessage(eventMessage);
            } catch (JSONException | IOException e) {
                Log.e(TAG, "Error sending event: " + e.getMessage());
            }
        });
    }

    public boolean isConnected() {
        return isConnected;
    }

    private String getDeviceId() {
        // 2026-11-10：原来缺省 "unknown"，bridge 多设备在线时无法定向。
        // 现在生成一次稳定 id 并落盘（jxev-<model>），bridge 的 _pick_device 据此优先选本机。
        android.content.SharedPreferences sp =
                context.getSharedPreferences("mcp_prefs", Context.MODE_PRIVATE);
        String id = sp.getString("device_id", null);
        if (id == null || id.isEmpty() || "unknown".equals(id)) {
            id = "jxev-" + android.os.Build.MODEL.replace(' ', '-');
            sp.edit().putString("device_id", id).apply();
        }
        return id;
    }
}
