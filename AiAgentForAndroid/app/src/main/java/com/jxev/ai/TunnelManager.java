package com.jxev.ai;

import android.content.Context;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.Charset;

/**
 * 内建加密隧道（2026-10-22）：
 * 以子进程方式启动用户态 WireGuard 客户端（libwgproxy.so，wireproxy 同款静态二进制），
 * 在本机 127.0.0.1:<socks_port> 暴露 SOCKS5，控制通道经它出站到 ECS:9444。
 *
 * 语义：不申请 VpnService、不建 TUN、不占系统 VPN 槽；隧道不通时由调用方 fail-closed。
 * 配置来自 mcp_prefs（见 ConfigManager），可用 adb run-as 写入后重启服务生效。
 */
public final class TunnelManager {
    private static final String TAG = "TunnelManager";
    private static final String BIN_NAME = "libwgproxy.so";
    private static final String SHIM_NAME = "libwgtcp.so";
    private static final String CONF_NAME = "wg-tunnel.conf";
    private static final String LOG_NAME = "wg-tunnel.log";
    private static final String SHIM_LOG_NAME = "wg-tcp.log";
    private static final int READY_TIMEOUT_MS = 45000;
    private static final int SUPERVISE_INTERVAL_MS = 20000;

    private final Context ctx;
    private Process proc;
    private Process shim;
    private Thread supervisor;
    private volatile boolean running;
    private volatile boolean up;

    public TunnelManager(Context ctx) {
        this.ctx = ctx.getApplicationContext();
    }

    /** 启动隧道并等待 SOCKS 端口可连；失败返回 false（调用方应拒绝启动控制通道）。 */
    public synchronized boolean start() {
        if (up && isPortOpen(socksPort())) {
            return true;
        }
        if (ConfigManager.getTunnelPrivateKey(ctx).isEmpty()) {
            Log.e(TAG, "隧道未配置（mcp_prefs.tunnel_private_key 为空），拒绝启动");
            return false;
        }
        try {
            if (isTcpTransport() && !startShim()) {
                Log.e(TAG, "TCP 承载进程启动失败，拒绝启动隧道");
                return false;
            }
            File conf = writeConf();
            File bin = new File(ctx.getApplicationInfo().nativeLibraryDir, BIN_NAME);
            if (!bin.isFile()) {
                Log.e(TAG, "隧道二进制不存在: " + bin);
                return false;
            }
            proc = new ProcessBuilder(bin.getAbsolutePath(), "-c", conf.getAbsolutePath())
                    .redirectErrorStream(true)
                    .start();
            running = true;
            pumpLog();
            boolean ok = waitPort(socksPort(), READY_TIMEOUT_MS);
            up = ok;
            Log.i(TAG, ok ? "隧道就绪 socks=127.0.0.1:" + socksPort()
                    : "隧道启动失败（SOCKS 端口未就绪）");
            if (ok) {
                startSupervisor();
            } else {
                stop();
            }
            return ok;
        } catch (Exception e) {
            Log.e(TAG, "隧道启动异常: " + e.getMessage());
            stop();
            return false;
        }
    }

    public synchronized void stop() {
        running = false;
        up = false;
        if (proc != null) {
            try {
                proc.destroy();
            } catch (Exception ignored) {
            }
            proc = null;
        }
        stopShim();
        if (supervisor != null) {
            supervisor.interrupt();
            supervisor = null;
        }
    }

    public boolean isUp() {
        return up && proc != null && isPortOpen(socksPort());
    }

    public int socksPort() {
        return ConfigManager.getTunnelSocksPort(ctx);
    }

    // ── 内部 ──

    private boolean isTcpTransport() {
        return "tcp".equalsIgnoreCase(ConfigManager.getTunnelTransport(ctx));
    }

    /** TCP 承载时，WG 的 Endpoint 指向本机回环，由 libwgtcp.so 转成 TCP 出去。 */
    private String effectiveEndpoint() {
        if (isTcpTransport()) {
            return "127.0.0.1:" + ConfigManager.getTunnelTcpLocalPort(ctx);
        }
        return ConfigManager.getTunnelEndpoint(ctx);
    }

    /** 启动 UDP-over-TCP 垫片：本地 UDP 127.0.0.1:<port> ⇄ TCP <endpoint>。 */
    private boolean startShim() {
        try {
            File bin = new File(ctx.getApplicationInfo().nativeLibraryDir, SHIM_NAME);
            if (!bin.isFile()) {
                Log.e(TAG, "TCP 承载二进制不存在: " + bin);
                return false;
            }
            String local = "127.0.0.1:" + ConfigManager.getTunnelTcpLocalPort(ctx);
            shim = new ProcessBuilder(bin.getAbsolutePath(),
                    "-mode", "client",
                    "-listen", local,
                    "-tcp", ConfigManager.getTunnelTcpEndpoint(ctx))
                    .redirectErrorStream(true)
                    .start();
            pumpShimLog();
            // 垫片是 UDP 监听，没法用 TCP 端口探测；给它 1.5s 起来即可
            Thread.sleep(1500);
            Log.i(TAG, "TCP 承载已启动 " + local + " ⇄ " + ConfigManager.getTunnelTcpEndpoint(ctx));
            return shim.isAlive();
        } catch (Exception e) {
            Log.e(TAG, "TCP 承载启动异常: " + e.getMessage());
            stopShim();
            return false;
        }
    }

    private void stopShim() {
        if (shim != null) {
            try {
                shim.destroy();
            } catch (Exception ignored) {
            }
            shim = null;
        }
    }

    private void pumpShimLog() {
        final Process p = shim;
        if (p == null) {
            return;
        }
        Thread t = new Thread(() -> {
            File logFile = new File(ctx.getFilesDir(), SHIM_LOG_NAME);
            FileOutputStream fos = null;
            try {
                fos = new FileOutputStream(logFile, false);
                BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), "UTF-8"));
                String line;
                while ((line = r.readLine()) != null) {
                    byte[] b = (line + "\n").getBytes(Charset.forName("UTF-8"));
                    fos.write(b);
                    fos.flush();
                }
            } catch (Exception ignored) {
            } finally {
                if (fos != null) {
                    try {
                        fos.close();
                    } catch (Exception ignored) {
                    }
                }
            }
        }, "wg-tcp-log");
        t.setDaemon(true);
        t.start();
    }

    private File writeConf() throws Exception {
        String conf = "[Interface]\n"
                + "Address = " + ConfigManager.getTunnelAddress(ctx) + "\n"
                + "PrivateKey = " + ConfigManager.getTunnelPrivateKey(ctx) + "\n"
                + "DNS = 1.1.1.1\n"
                + "MTU = " + ConfigManager.getTunnelMtu(ctx) + "\n"
                + "\n[Peer]\n"
                + "PublicKey = " + ConfigManager.getTunnelPeerPublicKey(ctx) + "\n"
                + "Endpoint = " + effectiveEndpoint() + "\n"
                + "AllowedIPs = " + ConfigManager.getTunnelAllowedIps(ctx) + "\n"
                + "PersistentKeepalive = 25\n"
                + "\n[Socks5]\n"
                + "BindAddress = 127.0.0.1:" + socksPort() + "\n";
        File confFile = new File(ctx.getFilesDir(), CONF_NAME);
        FileOutputStream fos = new FileOutputStream(confFile, false);
        try {
            fos.write(conf.getBytes(Charset.forName("UTF-8")));
        } finally {
            fos.close();
        }
        return confFile;
    }

    private void pumpLog() {
        final Process p = proc;
        if (p == null) {
            return;
        }
        Thread t = new Thread(() -> {
            File logFile = new File(ctx.getFilesDir(), LOG_NAME);
            FileOutputStream fos = null;
            try {
                fos = new FileOutputStream(logFile, false);
                BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), "UTF-8"));
                String line;
                while ((line = r.readLine()) != null) {
                    byte[] b = (line + "\n").getBytes(Charset.forName("UTF-8"));
                    fos.write(b);
                    fos.flush();
                }
            } catch (Exception ignored) {
            } finally {
                if (fos != null) {
                    try {
                        fos.close();
                    } catch (Exception ignored) {
                    }
                }
            }
        }, "wg-tunnel-log");
        t.setDaemon(true);
        t.start();
    }

    private void startSupervisor() {
        if (supervisor != null && supervisor.isAlive()) {
            return;
        }
        supervisor = new Thread(() -> {
            int fails = 0;
            while (running) {
                try {
                    Thread.sleep(SUPERVISE_INTERVAL_MS);
                } catch (InterruptedException e) {
                    break;
                }
                if (!running) {
                    break;
                }
                if (proc != null && proc.isAlive() && isPortOpen(socksPort())) {
                    up = true;
                    fails = 0;
                    continue;
                }
                up = false;
                fails++;
                Log.w(TAG, "隧道掉线，尝试第 " + fails + " 次重启");
                if (fails > 10) {
                    Log.e(TAG, "隧道连续重启失败超过 10 次，停止监管");
                    break;
                }
                try {
                    if (proc != null) {
                        proc.destroy();
                    }
                    if (isTcpTransport() && (shim == null || !shim.isAlive())) {
                        Log.w(TAG, "TCP 承载进程已退出，重启");
                        startShim();
                    }
                    File conf = new File(ctx.getFilesDir(), CONF_NAME);
                    File bin = new File(ctx.getApplicationInfo().nativeLibraryDir, BIN_NAME);
                    proc = new ProcessBuilder(bin.getAbsolutePath(), "-c", conf.getAbsolutePath())
                            .redirectErrorStream(true)
                            .start();
                    pumpLog();
                    up = waitPort(socksPort(), READY_TIMEOUT_MS);
                } catch (Exception e) {
                    Log.e(TAG, "隧道重启失败: " + e.getMessage());
                    up = false;
                }
            }
        }, "wg-tunnel-supervisor");
        supervisor.setDaemon(true);
        supervisor.start();
    }

    private static boolean waitPort(int port, int timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (isPortOpen(port)) {
                return true;
            }
            try {
                Thread.sleep(300);
            } catch (InterruptedException e) {
                return false;
            }
        }
        return false;
    }

    private static boolean isPortOpen(int port) {
        Socket s = new Socket();
        try {
            s.connect(new InetSocketAddress("127.0.0.1", port), 800);
            return true;
        } catch (Exception e) {
            return false;
        } finally {
            try {
                s.close();
            } catch (Exception ignored) {
            }
        }
    }
}
