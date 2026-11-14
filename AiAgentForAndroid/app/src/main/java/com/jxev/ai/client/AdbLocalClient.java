package com.jxev.ai.client;

import android.content.Context;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.zip.CRC32;

/**
 * 本地 adb 客户端：连 127.0.0.1:5555 跑 shell 命令。
 * 实现 adb wire 协议最小子集（CNXN / AUTH / OPEN / WRTE / CLSE / OKAY / FAIL）。
 * 私钥来自 assets/adbkey（PKCS#8 "PRIVATE KEY" PEM）。
 *
 * AUTH 协议（任务书写死，2026-08-14 已按实测核对）：
 *   1. 客户端连上后发 CNXN（version=0x01000000, maxdata=4096, banner）
 *   2. adbd 发 AUTH（arg0=1, token 挑战）
 *   3. 客户端发 AUTH（arg0=2, payload=SHA1(token) 的私钥签名）
 *   4. 若签名公钥未授权：adbd 再发 AUTH（arg0=1 新 token）
 *      → 客户端发 AUTH（arg0=3, payload=adbkey.pub 的 base64 文本 + 一个 \x00 终止字节）
 *   5. 授权通过后 adbd 发 CNXN → OPEN(shell:cmd) → WRTE → CLSE
 */
public class AdbLocalClient {
    private static final String TAG = "AdbLocalClient";

    // adb 协议常量（任务书写死）
    private static final int A_VERSION = 0x01000000;
    private static final int MAX_DATA = 4096;
    private static final int CMD_CNXN = 0x4e584e43; // "CNXN"
    private static final int CMD_AUTH = 0x48545541; // "AUTH"
    private static final int CMD_STLS = 0x534c5453; // "STLS"
    private static final int CMD_OPEN = 0x4e45504f; // "OPEN"
    private static final int CMD_OKAY = 0x59414b4f; // "OKAY"
    private static final int CMD_CLSE = 0x45534c43; // "CLSE"
    private static final int CMD_WRTE = 0x45545257; // "WRTE"
    private static final int CMD_FAIL = 0x4c494146; // "FAIL"

    // AUTH arg0
    private static final int AUTH_TOKEN = 1;
    private static final int AUTH_SIGNATURE = 2;
    private static final int AUTH_RSAPUBLICKEY = 3;

    // adbkey.pub 解码后的 AOSP android_pubkey 结构：len(4)+n0inv(4)+n[256]+rr[256]+exponent(4)=524 字节
    private static final int PUBKEY_STRUCT_SIZE = 524;
    private static final int RSA_EXPONENT = 65537;

    // 结果对象
    public static class Result {
        public final boolean success;
        public final String output;
        public final int exitCode;
        public final String error;

        public Result(boolean success, String output, int exitCode, String error) {
            this.success = success;
            this.output = output;
            this.exitCode = exitCode;
            this.error = error;
        }

        public static Result fail(String error) {
            return new Result(false, "", -1, error);
        }
    }

    // 单例
    private static volatile AdbLocalClient instance;
    private final Context appContext;
    private final byte[] privateKeyBytes;       // PKCS#8 DER（通道 A 用）
    private final byte[] publicKeyAuthPayload;  // adbkey.pub 文本 + \x00（AUTH arg0=3 payload）
    private volatile byte[] activePriv;         // 当前握手使用的私钥（通道 C 换成 wifi key）
    private volatile byte[] activePub;
    private static final Object KEY_LOCK = new Object();
    private static WifiDebugChannel wifiChannel;

    private AdbLocalClient(Context ctx) throws Exception {
        this.appContext = ctx.getApplicationContext();
        this.privateKeyBytes = readPkcs8PrivateKey("adbkey");
        this.publicKeyAuthPayload = readPublicKeyPayloadFromAsset("adbkey.pub");
        this.activePriv = privateKeyBytes;
        this.activePub = publicKeyAuthPayload;
        Log.i(TAG, "AdbLocalClient 初始化完成：私钥 " + privateKeyBytes.length
                + " 字节，公钥 auth payload " + publicKeyAuthPayload.length + " 字节");
    }

    public static AdbLocalClient getInstance(Context ctx) {
        if (instance == null) {
            synchronized (AdbLocalClient.class) {
                if (instance == null) {
                    try {
                        instance = new AdbLocalClient(ctx);
                    } catch (Exception e) {
                        Log.e(TAG, "AdbLocalClient 初始化失败", e);
                        return null;
                    }
                }
            }
        }
        return instance;
    }

    /** 执行 shell 命令：返回 stdout+stderr 合并输出 + exit code。失败返回 Result.fail。 */
    /**
     * 双通道入口：优先本机 adbd(127.0.0.1:5555)，端口不通时自动降级到 Shizuku（uid 2000）。
     * 只有「通道不可用」才降级；命令本身返回非零不会触发降级（避免副作用执行两次）。
     */
    public Result execShell(String command) {
        // 顺序：A 本机 adbd(5555) → C 无线调试 TLS → B Shizuku
        if (adbPortOpen()) {
            Result r = execShellAdb(command);
            if (r != null && r.success) return r;
            if (r == null || r.error == null || !r.error.startsWith("CHANNEL_DOWN")) return r;
        }
        Result w = execShellWifiDebug(command);
        if (w != null && w.success) return w;
        Result r2 = ShizukuBridge.exec(command, 30000);
        Log.i(TAG, "通道=Shizuku（降级）success=" + r2.success);
        return r2;
    }

    private WifiDebugChannel wifiChannel() {
        if (wifiChannel == null) {
            synchronized (AdbLocalClient.class) {
                if (wifiChannel == null) wifiChannel = new WifiDebugChannel(appContext);
            }
        }
        return wifiChannel;
    }

    /** 通道 C 是否可用（自检用，不执行命令）。 */
    public String wifiDebugEndpoint() {
        return wifiChannel().lastEndpoint();
    }

    /** 给外部（自检 / 路由）判断本机 adbd 是否在 5555 监听。 */
    public boolean isAdbPortOpen() {
        return adbPortOpen();
    }

    private boolean adbPortOpen() {
        Socket probe = new Socket();
        try {
            probe.connect(new InetSocketAddress("127.0.0.1", 5555), 400);
            return true;
        } catch (Exception e) {
            return false;
        } finally {
            try { probe.close(); } catch (Exception ignore) {}
        }
    }

    private Result execShellAdb(String command) {
        Socket sock = null;
        try {
            sock = new Socket();
            sock.connect(new InetSocketAddress("127.0.0.1", 5555), 5000);
            sock.setSoTimeout(15000);
            return execShellOnSocket(sock, command);
        } catch (IOException e) {
            return Result.fail("CHANNEL_DOWN: " + e.getMessage());
        } finally {
            try { if (sock != null) sock.close(); } catch (IOException ignore) {}
        }
    }

    /** 通道 C：无线调试 TLS 直连，走同一套 adb 握手。 */
    private Result execShellWifiDebug(String command) {
        WifiDebugChannel ch = wifiChannel();
        String lastErr = "无线调试未发现";
        synchronized (KEY_LOCK) {
            byte[] oldPriv = activePriv, oldPub = activePub;
            try {
                activePriv = readPkcs8PrivateKey("adbkey_wifi");
                activePub = readPublicKeyPayloadFromAsset("adbkey_wifi.pub");
            } catch (Exception e) {
                Log.w(TAG, "无线调试 key 加载失败：" + e.getMessage());
            }
            try {
                for (Integer port : ch.candidates()) {
                    Socket sock = null;
                    // 先试明文（adbd 对 loopback 常允许明文 adb 帧），失败再走 TLS
                    try {
                        sock = ch.openPlainSocket(port);
                        Result r = execShellOnSocket(sock, command);
                        Log.i(TAG, "通道=无线调试(明文) " + port + " success=" + r.success + " err=" + r.error);
                        if (r != null && r.success) return r;
                    } catch (Exception e) {
                        Log.i(TAG, "通道 C 明文 " + port + " 不可用：" + e.getMessage());
                    } finally {
                        try { if (sock != null) sock.close(); } catch (IOException ignore) {}
                        sock = null;
                    }
                    try {
                        sock = ch.openSocket(port);
                        sock.setSoTimeout(15000);
                        Result r = execShellOnSocket(sock, command);
                        Log.i(TAG, "通道=无线调试(TLS) " + port + " success=" + r.success);
                        return r;
                    } catch (Exception e) {
                        lastErr = port + ": " + e.getMessage();
                        Log.w(TAG, "通道 C 端口 " + port + " 失败：" + e.getMessage());
                    } finally {
                        try { if (sock != null) sock.close(); } catch (IOException ignore) {}
                    }
                }
            } finally {
                activePriv = oldPriv;
                activePub = oldPub;
            }
        }
        return Result.fail("CHANNEL_DOWN: " + lastErr);
    }

    private Result execShellOnSocket(Socket sock, String command) {
        DataInputStream in = null;
        DataOutputStream out = null;
        try {
            in = new DataInputStream(sock.getInputStream());
            out = new DataOutputStream(sock.getOutputStream());

            // 1. 发 CNXN（version=0x01000000, maxdata=4096, banner）
            String banner = "host::mcp=true";
            writePacket(out, CMD_CNXN, A_VERSION, MAX_DATA, banner.getBytes(StandardCharsets.UTF_8));

            // 2. 无线调试端口会先回 STLS：客户端 ack 后升级 TLS，再重新收 CNXN
            PacketHeader h0 = readHeader(in);
            if (h0.command == CMD_STLS) {
                Log.i(TAG, "收到 STLS，发 ack");
                writePacket(out, CMD_STLS, 1, 0, new byte[0]);
                Log.i(TAG, "STLS ack 已发，开始 TLS");
                Socket tls = wifiChannel().wrapTls(sock);
                in = new DataInputStream(tls.getInputStream());
                out = new DataOutputStream(tls.getOutputStream());
                // TLS 升级后服务端会主动发 CNXN（不再需要客户端重发）
                Log.i(TAG, "TLS 已升级，等待服务端 CNXN");
                if (!handshake(in, out)) {
                    return Result.fail("TLS 内 adb 握手失败（key 未授权？）");
                }
                h0 = null;
            }
            if (h0 != null) {
                if (h0.command != CMD_CNXN) {
                    return Result.fail("adb 握手失败：期望 CNXN，收到 0x" + Integer.toHexString(h0.command));
                }
                readData(in, h0.dataLength);
            }

            // 3. 发 OPEN shell:command（包装命令打印真实退出码：CLSE.arg1 不是退出码）
            int localId = 1;
            String wrapped = "( " + command + " ); echo __ADB_EXIT__$?";
            String openPayload = "shell:" + wrapped;
            writePacket(out, CMD_OPEN, localId, 0, openPayload.getBytes(StandardCharsets.UTF_8));

            // 4. 收 OKAY
            PacketHeader first = readHeader(in);
            if (first.command == CMD_FAIL) {
                byte[] reasonBytes = readData(in, first.dataLength);
                String reason = reasonBytes != null
                        ? new String(reasonBytes, StandardCharsets.UTF_8) : "unknown";
                return Result.fail("OPEN 失败：" + reason);
            }
            if (first.command != CMD_OKAY) {
                return Result.fail("期望 OKAY，实际收到 0x" + Integer.toHexString(first.command));
            }
            int remoteId = first.arg0; // OKAY 的 arg0 是 server 的 local id（即 remote id）

            // 5. 读 WRTE 数据，循环直到 CLSE；退出码从输出尾部 __ADB_EXIT__N 提取
            ByteArrayOutputStream outputBuf = new ByteArrayOutputStream();
            while (true) {
                PacketHeader h = readHeader(in);
                if (h.command == CMD_WRTE) {
                    byte[] data = readData(in, h.dataLength);
                    if (data != null && data.length > 0) {
                        outputBuf.write(data);
                    }
                    // 回 OKAY 告诉 server 我们收完了
                    writePacket(out, CMD_OKAY, h.arg1, h.arg0, null);
                } else if (h.command == CMD_CLSE) {
                    break;
                } else if (h.command == CMD_FAIL) {
                    byte[] data = readData(in, h.dataLength);
                    return Result.fail("FAIL：" + (data != null ? new String(data, StandardCharsets.UTF_8) : ""));
                } else {
                    return Result.fail("未期望的包: 0x" + Integer.toHexString(h.command));
                }
            }

            String output = new String(outputBuf.toByteArray(), StandardCharsets.UTF_8);
            int exitCode = -1;
            int markerIdx = output.lastIndexOf("__ADB_EXIT__");
            if (markerIdx >= 0) {
                String tail = output.substring(markerIdx + "__ADB_EXIT__".length()).trim();
                try {
                    exitCode = Integer.parseInt(tail.split("\\s+")[0]);
                } catch (NumberFormatException ignore) {
                }
                output = output.substring(0, markerIdx);
            }
            return new Result(true, output, exitCode, null);

        } catch (IOException e) {
            Log.e(TAG, "execShell IO 失败", e);
            return Result.fail("CHANNEL_DOWN: adb 连接失败：" + e.getMessage()
                    + "（请先用 USB/无线调试连接一次执行 adb tcpip 5555）");
        } catch (Exception e) {
            Log.e(TAG, "execShell 异常", e);
            return Result.fail("adb 异常：" + e.getMessage());
        } finally {
            try { if (in != null) in.close(); } catch (Exception ignore) {}
            try { if (out != null) out.close(); } catch (Exception ignore) {}
            try { if (sock != null) sock.close(); } catch (Exception ignore) {}
        }
    }

    // ================== 协议细节 ==================

    /**
     * 走握手：处理 AUTH 挑战，直到收到 CNXN（成功）或 FAIL（失败）。
     * 第一个 token → AUTH arg0=2 签名；若未授权再来 token → AUTH arg0=3 公钥文本。
     */
    private boolean handshake(DataInputStream in, DataOutputStream out) throws Exception {
        boolean signatureSent = false;
        for (int i = 0; i < 8; i++) {
            PacketHeader h = readHeader(in);
            if (h.command == CMD_CNXN) {
                byte[] banner = readData(in, h.dataLength);
                Log.i(TAG, "CNXN 成功 banner=" + (banner != null ? new String(banner, StandardCharsets.UTF_8) : ""));
                return true;
            } else if (h.command == CMD_AUTH) {
                if (h.arg0 == AUTH_TOKEN) {
                    byte[] token = readData(in, h.dataLength);
                    if (token == null || token.length == 0) {
                        Log.e(TAG, "AUTH token 为空");
                        return false;
                    }
                    if (!signatureSent) {
                        // 第一次 token：用私钥签名 SHA1(token) → AUTH arg0=2
                        byte[] signed = signToken(token);
                        writePacket(out, CMD_AUTH, AUTH_SIGNATURE, 0, signed);
                        signatureSent = true;
                        Log.i(TAG, "AUTH arg0=2 签名已发送（token " + token.length + "B）");
                    } else {
                        // 签名未授权：第二次 token → AUTH arg0=3 发送公钥 base64 文本 + \x00
                        writePacket(out, CMD_AUTH, AUTH_RSAPUBLICKEY, 0, activePub);
                        Log.i(TAG, "AUTH arg0=3 公钥已发送（" + activePub.length + "B）");
                    }
                } else {
                    Log.e(TAG, "AUTH 未知 arg0=" + h.arg0);
                    return false;
                }
            } else if (h.command == CMD_FAIL) {
                byte[] reason = readData(in, h.dataLength);
                Log.e(TAG, "FAIL: " + (reason != null ? new String(reason, StandardCharsets.UTF_8) : ""));
                return false;
            } else {
                Log.e(TAG, "握手收到未期望的包: 0x" + Integer.toHexString(h.command));
                return false;
            }
        }
        Log.e(TAG, "握手循环超过 8 轮");
        return false;
    }

    /**
     * 用 PKCS#8 私钥签名 adb token（2026-08-14 实测修正）。
     *
     * AOSP adb 的 AUTH arg0=2 payload 是 RSA_sign(NID_sha1, token, 20)：
     * 直接把 20 字节 token 当作 SHA1 摘要，签名内容是 DigestInfo(SHA1) || token
     * 再做 RSA PKCS#1 v1.5。Java 的 SHA1withRSA 会先对 token 再算一次 SHA1
     * （双重哈希），导致 adbd 拒签（AUTH arg0=3 后 Read timed out），
     * 因此必须用 NONEwithRSA + 手拼 DigestInfo 复刻。
     */
    private byte[] signToken(byte[] token) throws Exception {
        KeyFactory kf = KeyFactory.getInstance("RSA");
        PrivateKey priv = kf.generatePrivate(new PKCS8EncodedKeySpec(activePriv));
        Signature sig = Signature.getInstance("NONEwithRSA");
        sig.initSign(priv);
        // DigestInfo for SHA1（ASN.1 DER）
        byte[] digestInfoSha1 = new byte[]{
                0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e,
                0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14
        };
        byte[] data = new byte[digestInfoSha1.length + token.length];
        System.arraycopy(digestInfoSha1, 0, data, 0, digestInfoSha1.length);
        System.arraycopy(token, 0, data, digestInfoSha1.length, token.length);
        sig.update(data);
        return sig.sign();
    }

    private void writePacket(DataOutputStream out, int cmd, int arg0, int arg1, byte[] data) throws IOException {
        int len = (data == null) ? 0 : data.length;
        int crc = (data == null || data.length == 0) ? 0 : crc32(data);
        // adb wire 协议 24 字节头全部小端序（实测：大端序会被 adbd 直接断开）
        writeIntLE(out, cmd);
        writeIntLE(out, arg0);
        writeIntLE(out, arg1);
        writeIntLE(out, len);
        writeIntLE(out, crc);               // data_crc32 = crc32(data)
        writeIntLE(out, cmd ^ 0xffffffff);  // magic = command ^ 0xFFFFFFFF
        if (data != null && data.length > 0) {
            out.write(data);
        }
        out.flush();
    }

    private PacketHeader readHeader(DataInputStream in) throws IOException {
        PacketHeader h = new PacketHeader();
        // 小端序读取（与 adbd 发送一致）
        h.command = readIntLE(in);
        h.arg0 = readIntLE(in);
        h.arg1 = readIntLE(in);
        h.dataLength = readIntLE(in);
        h.dataCrc32 = readIntLE(in);
        h.magic = readIntLE(in);
        if (h.magic != (h.command ^ 0xffffffff)) {
            throw new IOException("包 magic 不匹配：cmd=0x" + Integer.toHexString(h.command)
                    + " magic=0x" + Integer.toHexString(h.magic));
        }
        return h;
    }

    private static void writeIntLE(DataOutputStream out, int v) throws IOException {
        out.write(v & 0xff);
        out.write((v >>> 8) & 0xff);
        out.write((v >>> 16) & 0xff);
        out.write((v >>> 24) & 0xff);
    }

    private static int readIntLE(DataInputStream in) throws IOException {
        int b0 = in.readUnsignedByte();
        int b1 = in.readUnsignedByte();
        int b2 = in.readUnsignedByte();
        int b3 = in.readUnsignedByte();
        return b0 | (b1 << 8) | (b2 << 16) | (b3 << 24);
    }

    private byte[] readData(DataInputStream in, int len) throws IOException {
        if (len <= 0) return null;
        if (len > 1024 * 1024) throw new IOException("data 过长：" + len);
        byte[] buf = new byte[len];
        in.readFully(buf);
        return buf;
    }

    private static int crc32(byte[] data) {
        CRC32 c = new CRC32();
        c.update(data, 0, data.length);
        return (int) c.getValue();
    }

    private static class PacketHeader {
        int command;
        int arg0;
        int arg1;
        int dataLength;
        int dataCrc32;
        int magic;
    }

    // ================== Asset 加载 ==================

    private byte[] readAsset(String name) throws IOException {
        try (InputStream is = appContext.getAssets().open(name)) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = is.read(buf)) > 0) {
                bos.write(buf, 0, n);
            }
            return bos.toByteArray();
        }
    }

    /**
     * 读取 PKCS#8 私钥：支持 PEM 文本（-----BEGIN PRIVATE KEY-----）或裸 DER。
     * adbkey asset 是 PEM 文本，需剥掉 header/footer 并 base64 解码为 DER。
     */
    private byte[] readPkcs8PrivateKey(String name) throws IOException {
        byte[] bytes = readAsset(name);
        String text = new String(bytes, "UTF-8");
        if (text.contains("-----BEGIN PRIVATE KEY-----")) {
            int begin = text.indexOf("-----BEGIN PRIVATE KEY-----");
            int end = text.indexOf("-----END PRIVATE KEY-----");
            if (end < 0) {
                throw new IOException("adbkey PEM 缺少 END 标记");
            }
            String body = text.substring(begin + "-----BEGIN PRIVATE KEY-----".length(), end);
            StringBuilder sb = new StringBuilder();
            for (String line : body.split("\r\n|\r|\n")) {
                String t = line.trim();
                if (!t.isEmpty()) sb.append(t);
            }
            return android.util.Base64.decode(sb.toString(), android.util.Base64.DEFAULT);
        }
        return bytes; // 已是裸 DER
    }

    /**
     * 解析 adbkey.pub（adb 专用公钥格式，任务书核实）：
     * base64 编码的 524 字节 struct RSAPublicKey：
     *   modulus_size_words(4, 小端 int) + n0inv(4, 小端 uint32)
     *   + modulus[256] + rr[256] + exponent(4, 小端 int)
     * 文件文本 = base64 + " user@host"。
     * 返回 AUTH arg0=3 的 payload：adbkey.pub 的 base64 文本 + 一个 \x00 终止字节。
     */
    private byte[] readPublicKeyPayloadFromAsset(String name) throws IOException {
        String text;
        try (InputStream is = appContext.getAssets().open(name)) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
            text = bos.toString("UTF-8");
        }
        text = text.trim();
        int sp = text.indexOf(' ');
        String b64 = (sp > 0 ? text.substring(0, sp) : text).trim();
        byte[] raw = android.util.Base64.decode(b64, android.util.Base64.DEFAULT);

        // 校验结构
        if (raw.length != PUBKEY_STRUCT_SIZE) {
            throw new IOException("adbkey.pub 解析失败：结构长度=" + raw.length
                    + "（应为 " + PUBKEY_STRUCT_SIZE + "）");
        }
        int words = readInt32LE(raw, 0);
        if (words != 64) {
            Log.w(TAG, "adbkey.pub modulus_size_words=" + words + "（预期 64）");
        }
        int exp = readInt32LE(raw, PUBKEY_STRUCT_SIZE - 4);
        if (exp != RSA_EXPONENT) {
            throw new IOException("adbkey.pub 解析失败：exponent=" + exp + "（预期 " + RSA_EXPONENT + "）");
        }
        Log.i(TAG, "adbkey.pub 结构校验通过：words=" + words
                + " n0inv=" + readInt32LE(raw, 4)
                + " exponent=" + exp + " rawLen=" + raw.length);

        // payload = 文件文本（base64 + " user@host"）+ \x00
        byte[] textBytes = text.getBytes("UTF-8");
        byte[] payload = new byte[textBytes.length + 1];
        System.arraycopy(textBytes, 0, payload, 0, textBytes.length);
        payload[textBytes.length] = 0x00;
        return payload;
    }

    private static int readInt32LE(byte[] buf, int off) {
        return (buf[off] & 0xff)
                | ((buf[off + 1] & 0xff) << 8)
                | ((buf[off + 2] & 0xff) << 16)
                | ((buf[off + 3] & 0xff) << 24);
    }
}
