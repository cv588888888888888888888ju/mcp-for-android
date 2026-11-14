package com.jxev.ai.server;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.jxev.ai.ConfigManager;
import com.jxev.ai.client.ActionDispatcher;
import com.jxev.ai.client.MCPSocketClient;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.security.KeyStore;
import java.util.Collections;
import java.util.Enumeration;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocketFactory;
import javax.net.ssl.SSLSocket;

/**
 * wg 直连监听服务（2026-09-29 新增）：
 * 手机在 WireGuard 内网 IP（192.0.2.4）上监听 TLS 端口，任何 wg peer 可主动拨入，
 * 协议与出站链路完全一致（handshake+token → request/response JSON 行）。
 * 与 MCPSocketClient 出站链路并存；出站挂掉时 wg 侧仍可直连控制手机。
 */
public class MCPListenServer {
    private static final String TAG = "MCPListenServer";
    // res/raw 下的 PKCS12（CA 签发，SAN: 192.0.2.1/192.0.2.4/127.0.0.1/phone）
    private static final String RAW_KEYSTORE_RES = "phone_keystore";
    private static final String KEYSTORE_PASSWORD = "mcpphone";
    // 客户端 30s 心跳即保活；超过该读超时未收到任何数据视为死链
    private static final int SESSION_SO_TIMEOUT_MS = 150000;
    private static final long WG_SCAN_INTERVAL_MS = 30000L;

    private final Context context;
    private final int port;
    private final String expectedToken;
    private final ExecutorService sessionExecutor = Executors.newCachedThreadPool();
    private final AtomicInteger sessionCount = new AtomicInteger();
    // 已完成握手会话的 writer（pc_notify 广播用）
    private final CopyOnWriteArrayList<BufferedWriter> clientWriters = new CopyOnWriteArrayList<>();
    // pc_notify 请求的回调（sessionId → 回调）
    private final ConcurrentHashMap<String, MCPSocketClient.RequestCallback> pendingPcNotify =
            new ConcurrentHashMap<>();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private volatile boolean running = false;
    private volatile SSLServerSocketFactory serverSocketFactory;
    private volatile String listenAddress = ""; // "192.0.2.4:9444"
    private Thread acceptThread;
    private ServerSocket currentServerSocket;

    public MCPListenServer(Context context, int port) {
        this.context = context.getApplicationContext();
        this.port = port;
        this.expectedToken = ConfigManager.getDeviceToken(this.context);
    }

    /** 当前监听地址（未监听时为空串），供通知栏展示。 */
    public String getListenAddress() {
        return listenAddress;
    }

    /** 监听地址变化回调（绑定成功/丢失时触发）。 */
    public interface AddressListener {
        void onAddressChanged(String address);
    }

    private volatile AddressListener addressListener;

    public void setAddressListener(AddressListener listener) {
        addressListener = listener;
    }

    private void notifyAddressChanged() {
        AddressListener listener = addressListener;
        if (listener != null) {
            listener.onAddressChanged(listenAddress);
        }
    }

    /** 是否有拨入的直连客户端在线。 */
    public boolean hasClients() {
        return sessionCount.get() > 0;
    }

    public synchronized void start() {
        if (running) {
            return;
        }
        try {
            serverSocketFactory = buildServerSocketFactory();
        } catch (Exception e) {
            Log.e(TAG, "TLS 服务端初始化失败，监听模式不可用", e);
            return;
        }
        running = true;
        acceptThread = new Thread(this::acceptLoop, "MCP-Listen");
        acceptThread.setDaemon(true);
        acceptThread.start();
    }

    public synchronized void stop() {
        running = false;
        try {
            ServerSocket ss = currentServerSocket;
            if (ss != null && !ss.isClosed()) {
                ss.close();
            }
        } catch (Exception ignored) {
        }
        if (acceptThread != null) {
            acceptThread.interrupt();
        }
        listenAddress = "";
        Log.i(TAG, "MCP 监听已停止");
    }

    private SSLServerSocketFactory buildServerSocketFactory() throws Exception {
        int resId = context.getResources().getIdentifier(RAW_KEYSTORE_RES, "raw", context.getPackageName());
        if (resId == 0) {
            throw new IllegalStateException("res/raw/" + RAW_KEYSTORE_RES + " 不存在");
        }
        KeyStore ks = KeyStore.getInstance("PKCS12");
        InputStream is = null;
        try {
            is = context.getResources().openRawResource(resId);
            ks.load(is, KEYSTORE_PASSWORD.toCharArray());
        } finally {
            if (is != null) {
                try {
                    is.close();
                } catch (Exception ignored) {
                }
            }
        }
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(ks, KEYSTORE_PASSWORD.toCharArray());
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(kmf.getKeyManagers(), null, null);
        return ctx.getServerSocketFactory();
    }

    /** 扫描本机网口，返回 wg 内网 IPv4（10.0.0.x）；没有则 null。 */
    private static String findWgIp() {
        try {
            Enumeration<NetworkInterface> nis = NetworkInterface.getNetworkInterfaces();
            for (NetworkInterface ni : Collections.list(nis)) {
                if (!ni.isUp() || ni.isLoopback()) {
                    continue;
                }
                Enumeration<InetAddress> addrs = ni.getInetAddresses();
                for (InetAddress addr : Collections.list(addrs)) {
                    if (!addr.isLoopbackAddress() && addr.getAddress().length == 4) {
                        byte[] b = addr.getAddress();
                        if (b[0] == 10 && b[1] == 0 && b[2] == 0) {
                            return addr.getHostAddress();
                        }
                    }
                }
            }
        } catch (SocketException ignored) {
        }
        return null;
    }

    private void acceptLoop() {
        boolean loggedNoWg = false;
        while (running) {
            String wgIp = findWgIp();
            if (wgIp == null) {
                listenAddress = "";
                if (!loggedNoWg) {
                    Log.i(TAG, "未发现 wg 网口（10.0.0.x），每 " + (WG_SCAN_INTERVAL_MS / 1000) + "s 重试");
                    loggedNoWg = true;
                    notifyAddressChanged();
                }
                sleep(WG_SCAN_INTERVAL_MS);
                continue;
            }
            loggedNoWg = false;
            ServerSocket ss = null;
            try {
                // 通配绑定（0.0.0.0）：实测 Java 的指定-IP 绑定会落成 AF_INET6-mapped
                // socket，收不到 wg tun 入站（本机可连、hub 经 wg 超时）；adbd/Termux
                // 的 0.0.0.0 绑定则 wg 入站正常。wg-only 语义由 accept 后的来源过滤保证。
                ss = serverSocketFactory.createServerSocket(port, 8,
                        InetAddress.getByAddress(new byte[]{0, 0, 0, 0}));
                currentServerSocket = ss;
                listenAddress = wgIp + ":" + port + " (0.0.0.0)";
                notifyAddressChanged();
                Log.i(TAG, "MCP wg 直连监听已启动: " + listenAddress);
                while (running) {
                    Socket client = ss.accept();
                    if (!isWgSource(client.getInetAddress())) {
                        Log.w(TAG, "非 wg 网段来源，拒绝: " + client.getInetAddress());
                        try {
                            client.close();
                        } catch (Exception ignored) {
                        }
                        continue;
                    }
                    handleClient(client);
                }
            } catch (Exception e) {
                if (running) {
                    Log.w(TAG, "监听异常，5s 后重绑 " + wgIp + ":" + port + ": " + e);
                    sleep(5000);
                }
            } finally {
                if (ss != null && !ss.isClosed()) {
                    try {
                        ss.close();
                    } catch (Exception ignored) {
                    }
                }
                currentServerSocket = null;
                listenAddress = "";
                notifyAddressChanged();
            }
        }
    }

    /** 只接受 wg 内网（10.0.0.0/24）来源；其余（WiFi 局域网等）一律拒绝。 */
    private static boolean isWgSource(InetAddress addr) {
        byte[] b = addr.getAddress();
        return b.length == 4 && b[0] == 10 && b[1] == 0 && b[2] == 0;
    }

    private void handleClient(Socket raw) {
        sessionCount.incrementAndGet();
        sessionExecutor.execute(() -> {
            SSLSocket socket = (SSLSocket) raw;
            String peer = String.valueOf(socket.getInetAddress());
            BufferedWriter writer = null;
            try {
                socket.setSoTimeout(SESSION_SO_TIMEOUT_MS);
                socket.setTcpNoDelay(true);
                OutputStream os = socket.getOutputStream();
                InputStream is = socket.getInputStream();
                writer = new BufferedWriter(new OutputStreamWriter(os, "UTF-8"));
                BufferedReader reader = new BufferedReader(new InputStreamReader(is, "UTF-8"));

                // 握手：与出站链路同协议，客户端（wg peer）携带 token
                String line = reader.readLine();
                JSONObject hello = line == null ? new JSONObject() : new JSONObject(line);
                if (!"handshake".equals(hello.optString("type"))) {
                    Log.w(TAG, "首包非握手，拒绝: " + peer);
                    return;
                }
                if (expectedToken != null && !expectedToken.isEmpty()
                        && !expectedToken.equals(hello.optString("token"))) {
                    writer.write(new JSONObject()
                            .put("type", "handshake_response")
                            .put("status", "unauthorized").toString() + "\n");
                    writer.flush();
                    Log.w(TAG, "token 校验失败，拒绝: " + peer);
                    return;
                }
                String peerDevice = hello.optString("deviceId", "unknown");
                writer.write(new JSONObject()
                        .put("type", "handshake_response")
                        .put("status", "ok")
                        .put("timestamp", System.currentTimeMillis() / 1000.0).toString() + "\n");
                writer.flush();
                clientWriters.add(writer);
                Log.i(TAG, "wg 直连客户端已接入: " + peerDevice + " (" + peer + ")");

                // 会话循环
                while (running && (line = reader.readLine()) != null) {
                    if (line.trim().isEmpty()) {
                        continue;
                    }
                    JSONObject msg;
                    try {
                        msg = new JSONObject(line);
                    } catch (Exception e) {
                        Log.w(TAG, "非 JSON 消息，忽略");
                        continue;
                    }
                    String type = msg.optString("type");
                    if ("request".equals(type)) {
                        handleRequest(msg, writer);
                    } else if ("heartbeat".equals(type)) {
                        synchronized (writer) {
                            writer.write(new JSONObject()
                                    .put("type", "heartbeat_response")
                                    .put("timestamp", System.currentTimeMillis() / 1000.0).toString() + "\n");
                            writer.flush();
                        }
                    } else if ("response".equals(type)) {
                        completePcNotify(msg);
                    } else if (!"handshake".equals(type)) {
                        Log.w(TAG, "未知消息类型: " + type);
                    }
                }
            } catch (Exception e) {
                Log.i(TAG, "直连会话结束 " + peer + ": " + e);
            } finally {
                sessionCount.decrementAndGet();
                if (writer != null) {
                    clientWriters.remove(writer);
                }
                try {
                    socket.close();
                } catch (Exception ignored) {
                }
            }
        });
    }

    /**
     * 脚本 pc_notify 广播：向所有在线直连客户端发 pc_notify 请求，任一回执即完成。
     * （出站链路断开时，pc_notify 走 wg 直连通道送达到当前连着的 wg peer。）
     */
    public void sendPcNotify(String title, String text, MCPSocketClient.RequestCallback callback) {
        if (clientWriters.isEmpty()) {
            if (callback != null) {
                callback.onError("无在线的 wg 直连客户端");
            }
            return;
        }
        final String requestId = UUID.randomUUID().toString();
        if (callback != null) {
            pendingPcNotify.put(requestId, callback);
            mainHandler.postDelayed(() -> {
                MCPSocketClient.RequestCallback timedOut = pendingPcNotify.remove(requestId);
                if (timedOut != null) {
                    timedOut.onError("pc_notify 响应超时（30s）");
                }
            }, 30000);
        }
        JSONObject params = new JSONObject();
        try {
            params.put("title", title);
            params.put("text", text);
        } catch (Exception e) {
            Log.e(TAG, "pc_notify 参数构造失败", e);
        }
        JSONObject request;
        try {
            request = new JSONObject()
                    .put("type", "request")
                    .put("requestId", requestId)
                    .put("actionType", "pc_notify")
                    .put("parameters", params)
                    .put("timestamp", System.currentTimeMillis() / 1000.0);
        } catch (Exception e) {
            Log.e(TAG, "pc_notify 请求构造失败", e);
            return;
        }
        boolean delivered = false;
        for (BufferedWriter writer : clientWriters) {
            try {
                synchronized (writer) {
                    writer.write(request.toString() + "\n");
                    writer.flush();
                }
                delivered = true;
            } catch (Exception e) {
                Log.w(TAG, "pc_notify 广播到某个客户端失败: " + e);
            }
        }
        if (!delivered && callback != null) {
            pendingPcNotify.remove(requestId);
            callback.onError("pc_notify 投递失败（无可用直连会话）");
        }
    }

    /** 会话收到 response 时路由 pc_notify 回执。 */
    private void completePcNotify(JSONObject response) {
        String requestId = response.optString("requestId", "");
        MCPSocketClient.RequestCallback callback = pendingPcNotify.remove(requestId);
        if (callback == null) {
            return;
        }
        callback.onResponse(response);
    }

    /** request → 执行 → response（格式与 MCPSocketClient.handleRequest 一致）。 */
    private void handleRequest(JSONObject requestMessage, BufferedWriter writer) {
        String requestId = requestMessage.optString("requestId");
        String actionType = requestMessage.optString("actionType");
        JSONObject parameters = requestMessage.optJSONObject("parameters");
        if (parameters == null) {
            parameters = new JSONObject();
        }
        Log.i(TAG, "直连请求: " + actionType + " (id=" + requestId + ")");
        ActionDispatcher.dispatch(context, actionType, parameters, (result, error) -> {
            try {
                JSONObject data = new JSONObject();
                if (error != null) {
                    data.put("status", "error");
                    data.put("error", error);
                } else {
                    data.put("status", "success");
                    if (result != null) {
                        for (java.util.Map.Entry<String, Object> entry : result.entrySet()) {
                            data.put(entry.getKey(), entry.getValue());
                        }
                    }
                }
                JSONObject response = new JSONObject()
                        .put("type", "response")
                        .put("requestId", requestId)
                        .put("data", data)
                        .put("timestamp", System.currentTimeMillis() / 1000.0);
                synchronized (writer) {
                    writer.write(response.toString() + "\n");
                    writer.flush();
                }
            } catch (Exception e) {
                Log.e(TAG, "直连响应发送失败", e);
            }
        });
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
