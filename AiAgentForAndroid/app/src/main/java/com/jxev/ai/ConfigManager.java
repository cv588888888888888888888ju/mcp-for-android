package com.jxev.ai;

import android.content.Context;
import android.util.Log;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;

/**
 * 配置读取：SharedPreferences（mcp_prefs）优先，缺失时回退 assets/config.json。
 * 保持 adb run-as 写 prefs 的远程配置能力（服务重启后生效）。
 */
public final class ConfigManager {
    private static final String TAG = "ConfigManager";
    private static final String PREFS = "mcp_prefs";

    // 默认连接目标：ECS 的 WireGuard 内网地址（手机经 wg 隧道出站，不经公网）
    // 可用 SharedPreferences(mcp_prefs).server_host 或 assets/config.json 覆盖
    public static final String DEFAULT_HOST = "192.0.2.1"; // 占位符，首次配置时由 CLI/App 覆盖
    public static final int DEFAULT_SOCKET_PORT = 9444;

    // wg 直连监听（2026-09-29）：手机在本机 wg IP 上开 TLS 服务端，任意 wg peer 可拨入
    public static final int DEFAULT_LISTEN_PORT = 9444;

    private ConfigManager() {
    }

    /** wg 直连监听开关（mcp_prefs.listen_enabled，默认开）。 */
    public static boolean isListenEnabled(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean("listen_enabled", true);
    }

    /** wg 直连监听端口（mcp_prefs.listen_port，默认 9444）。 */
    public static int getListenPort(Context ctx) {
        int v = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getInt("listen_port", -1);
        if (v > 0 && v <= 65535) {
            return v;
        }
        return DEFAULT_LISTEN_PORT;
    }

    public static String getServerHost(Context ctx) {
        String v = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString("server_host", null);
        if (v != null && !v.trim().isEmpty()) {
            return v.trim();
        }
        return assetString(ctx, "server_host", DEFAULT_HOST);
    }

    public static int getServerSocketPort(Context ctx) {
        int v = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getInt("server_socket_port", -1);
        if (v > 0 && v <= 65535) {
            return v;
        }
        return assetInt(ctx, "server_socket_port", DEFAULT_SOCKET_PORT);
    }

    // ── 内建加密隧道（2026-10-22）：app 自带用户态 WireGuard，不占系统 VPN 槽 ──
    // prefs 键：tunnel_enabled / tunnel_socks_port / tunnel_private_key /
    //   tunnel_peer_public_key / tunnel_endpoint / tunnel_address / tunnel_allowed_ips / tunnel_mtu
    public static final int DEFAULT_TUNNEL_SOCKS_PORT = 1080;
    public static final String DEFAULT_TUNNEL_ENDPOINT = "203.0.113.10:51820";
    public static final String DEFAULT_TUNNEL_ALLOWED_IPS = "10.0.0.0/24";

    public static boolean isTunnelEnabled(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean("tunnel_enabled", false);
    }

    public static int getTunnelSocksPort(Context ctx) {
        int v = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getInt("tunnel_socks_port", -1);
        if (v > 0 && v <= 65535) {
            return v;
        }
        return DEFAULT_TUNNEL_SOCKS_PORT;
    }

    public static String getTunnelPrivateKey(Context ctx) {
        return prefString(ctx, "tunnel_private_key", "");
    }

    public static String getTunnelPeerPublicKey(Context ctx) {
        return prefString(ctx, "tunnel_peer_public_key", "");
    }

    public static String getTunnelEndpoint(Context ctx) {
        String v = prefString(ctx, "tunnel_endpoint", "");
        return v.isEmpty() ? DEFAULT_TUNNEL_ENDPOINT : v;
    }

    public static String getTunnelAddress(Context ctx) {
        String v = prefString(ctx, "tunnel_address", "");
        return v.isEmpty() ? "192.0.2.7/32" : v;
    }

    public static String getTunnelAllowedIps(Context ctx) {
        String v = prefString(ctx, "tunnel_allowed_ips", "");
        return v.isEmpty() ? DEFAULT_TUNNEL_ALLOWED_IPS : v;
    }

    public static int getTunnelMtu(Context ctx) {
        int v = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getInt("tunnel_mtu", -1);
        return v > 0 ? v : 1280;
    }

    // ── 隧道传输形态（2026-10-26）：udp=直连 WG；tcp=WG 封进 TCP（应对只放行 TCP 的网络）──
    // prefs 键：tunnel_transport / tunnel_tcp_endpoint / tunnel_tcp_local_port
    public static final String DEFAULT_TUNNEL_TCP_ENDPOINT = "203.0.113.10:443";
    public static final int DEFAULT_TUNNEL_TCP_LOCAL_PORT = 51821;

    public static String getTunnelTransport(Context ctx) {
        String v = prefString(ctx, "tunnel_transport", "");
        return v.isEmpty() ? "udp" : v;
    }

    public static String getTunnelTcpEndpoint(Context ctx) {
        String v = prefString(ctx, "tunnel_tcp_endpoint", "");
        return v.isEmpty() ? DEFAULT_TUNNEL_TCP_ENDPOINT : v;
    }

    public static int getTunnelTcpLocalPort(Context ctx) {
        int v = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getInt("tunnel_tcp_local_port", -1);
        return (v > 0 && v <= 65535) ? v : DEFAULT_TUNNEL_TCP_LOCAL_PORT;
    }

    private static String prefString(Context ctx, String key, String def) {
        String v = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(key, def);
        return v == null ? def : v.trim();
    }

    public static String getDeviceToken(Context ctx) {
        String v = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString("device_token", null);
        if (v != null) {
            // 空字符串也是有效值（服务端可能不校验 token）
            return v;
        }
        return assetString(ctx, "device_token", "");
    }

    private static String assetString(Context ctx, String key, String def) {
        JSONObject j = loadAssets(ctx);
        if (j != null) {
            String v = j.optString(key, "");
            if (!v.isEmpty()) {
                return v;
            }
        }
        return def;
    }

    private static int assetInt(Context ctx, String key, int def) {
        JSONObject j = loadAssets(ctx);
        if (j != null && j.has(key)) {
            return j.optInt(key, def);
        }
        return def;
    }

    private static JSONObject loadAssets(Context ctx) {
        InputStream is = null;
        try {
            is = ctx.getAssets().open("config.json");
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[1024];
            int n;
            while ((n = is.read(buf)) > 0) {
                bos.write(buf, 0, n);
            }
            return new JSONObject(bos.toString("UTF-8"));
        } catch (Exception e) {
            Log.w(TAG, "读取 assets/config.json 失败: " + e.getMessage());
            return null;
        } finally {
            if (is != null) {
                try {
                    is.close();
                } catch (Exception ignored) {
                }
            }
        }
    }
}
