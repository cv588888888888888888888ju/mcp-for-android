package com.jxev.ai.client;

import android.content.pm.PackageManager;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.concurrent.TimeUnit;

import rikka.shizuku.Shizuku;

/**
 * Shizuku 执行通道：当本机 adbd 的 5555 端口不可用（例如重启后还没跑过 adb tcpip）时，
 * 用 Shizuku 的 shell 身份（uid 2000）执行同一条 shell 命令。
 *
 * 前提：Shizuku 服务在跑 + 本应用已获 Shizuku 授权（moe.shizuku.manager.permission.API_V23）。
 */
public final class ShizukuBridge {
    private static final String TAG = "ShizukuBridge";

    private ShizukuBridge() {}

    /** Shizuku binder 活着且本应用有权限。 */
    public static boolean available() {
        try {
            boolean ping = Shizuku.pingBinder();
            int ver = ping ? Shizuku.getVersion() : -1;
            int perm = ping ? Shizuku.checkSelfPermission() : -1;
            Log.i(TAG, "available: ping=" + ping + " version=" + ver + " perm=" + perm);
            if (!ping) return false;
            if (ver < 10) return false;
            return perm == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable t) {
            return false;
        }
    }

    /** binder 是否活着（不管有没有权限）。 */
    public static boolean ping() {
        try { return Shizuku.pingBinder(); } catch (Throwable t) { return false; }
    }

    /** Shizuku 服务活着但还没授权时，弹一次授权请求。 */
    public static boolean requestPermissionIfNeeded() {
        try {
            if (!Shizuku.pingBinder()) return false;
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) return true;
            if (Shizuku.shouldShowRequestPermissionRationale()) return false;
            Shizuku.requestPermission(0);
            return false;
        } catch (Throwable t) {
            Log.w(TAG, "requestPermission 失败: " + t.getMessage());
            return false;
        }
    }

    /** 用 shell 身份执行命令，返回 AdbLocalClient.Result（复用同一结果结构）。 */
    public static AdbLocalClient.Result exec(String command, long timeoutMs) {
        if (!available()) {
            return AdbLocalClient.Result.fail("Shizuku 不可用（服务未运行或未授权）");
        }
        Process p = null;
        try {
            // uid 2000 的进程里再起 sh -c
            java.lang.reflect.Method m = Shizuku.class.getDeclaredMethod(
                    "newProcess", String[].class, String[].class, String.class);
            m.setAccessible(true);
            p = (Process) m.invoke(null, new String[]{"sh", "-c", command}, null, "/");
            final Process proc = p;
            final ByteArrayOutputStream outBuf = new ByteArrayOutputStream();
            final ByteArrayOutputStream errBuf = new ByteArrayOutputStream();
            Thread t1 = new Thread(() -> copy(proc.getInputStream(), outBuf), "shizuku-stdout");
            Thread t2 = new Thread(() -> copy(proc.getErrorStream(), errBuf), "shizuku-stderr");
            t1.setDaemon(true); t2.setDaemon(true);
            t1.start(); t2.start();

            boolean finished = p.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
            if (!finished) {
                p.destroy();
                return AdbLocalClient.Result.fail("Shizuku 执行超时（" + timeoutMs + "ms）");
            }
            t1.join(2000); t2.join(2000);

            String out = outBuf.toString("UTF-8");
            String err = errBuf.toString("UTF-8");
            int code = p.exitValue();
            if (code != 0 && out.isEmpty() && !err.isEmpty()) out = err;
            return new AdbLocalClient.Result(code == 0, out, code, code == 0 ? null : err);
        } catch (Throwable t) {
            Log.e(TAG, "Shizuku exec 失败: " + t.getMessage());
            return AdbLocalClient.Result.fail("Shizuku 执行失败：" + t.getMessage());
        } finally {
            if (p != null) try { p.destroy(); } catch (Throwable ignore) {}
        }
    }

    private static void copy(InputStream in, ByteArrayOutputStream out) {
        try {
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        } catch (Throwable ignore) {
        }
    }
}
