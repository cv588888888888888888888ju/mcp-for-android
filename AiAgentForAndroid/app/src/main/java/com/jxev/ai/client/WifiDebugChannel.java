package com.jxev.ai.client;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.security.KeyFactory;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * 通道 C：无线调试（adb over TLS）直连。
 * 用一把自己配对过的 key（assets/adbkey_wifi*）连到手机自己的 adbd（_adb-tls-connect._tcp），
 * 不依赖 PC、不依赖 5555 端口，重启后只要「无线调试」还开着就能重连。
 *
 * 端点发现：先试缓存端口 → mDNS → 本机回环高位端口扫描（mDNS 在多播被拦或 VPN 占默认网络时常失效）。
 */
public class WifiDebugChannel {
    private static final String TAG = "WifiDebugChannel";
    private static final String SERVICE_TYPE = "_adb-tls-connect._tcp";
    private static final int SCAN_FROM = 30000;
    private static final int SCAN_TO = 65535;

    private final Context ctx;
    private SSLContext sslCtx;
    private volatile int lastPort;

    public WifiDebugChannel(Context ctx) {
        this.ctx = ctx.getApplicationContext();
    }

    public String lastEndpoint() {
        return lastPort > 0 ? ("127.0.0.1:" + lastPort) : "";
    }

    public void remember(int port) {
        this.lastPort = port;
    }

    /** 候选端口：缓存 → mDNS → 回环扫描。 */
    public List<Integer> candidates() {
        List<Integer> out = new ArrayList<>();
        if (lastPort > 0) out.add(lastPort);
        int mdns = discoverMdns();
        if (mdns > 0 && !out.contains(mdns)) out.add(mdns);
        for (Integer p : scanLoopbackPorts()) if (!out.contains(p)) out.add(p);
        StringBuilder sb = new StringBuilder();
        for (Integer p : out) sb.append(p).append(',');
        Log.i(TAG, "通道 C 候选端口：" + sb);
        return out;
    }

    /** mDNS 发现（拿不到返回 0）。 */
    private int discoverMdns() {
        final CountDownLatch latch = new CountDownLatch(1);
        final int[] found = new int[1];
        NsdManager nsd = (NsdManager) ctx.getSystemService(Context.NSD_SERVICE);
        if (nsd == null) return 0;
        NsdManager.DiscoveryListener listener = new NsdManager.DiscoveryListener() {
            @Override public void onStartDiscoveryFailed(String type, int err) { latch.countDown(); }
            @Override public void onStopDiscoveryFailed(String type, int err) { latch.countDown(); }
            @Override public void onDiscoveryStarted(String type) { }
            @Override public void onDiscoveryStopped(String type) { }
            @Override public void onServiceLost(NsdServiceInfo info) { }
            @Override public void onServiceFound(NsdServiceInfo info) {
                if (!SERVICE_TYPE.equals(info.getServiceType())) return;
                nsd.resolveService(info, new NsdManager.ResolveListener() {
                    @Override public void onResolveFailed(NsdServiceInfo i, int e) { }
                    @Override public void onServiceResolved(NsdServiceInfo r) {
                        if (r.getPort() > 0) {
                            found[0] = r.getPort();
                            latch.countDown();
                        }
                    }
                });
            }
        };
        try {
            new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
                try {
                    nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener);
                } catch (Exception e) {
                    Log.w(TAG, "discoverServices 失败：" + e.getMessage());
                    latch.countDown();
                }
            });
            latch.await(4, TimeUnit.SECONDS);
        } catch (Exception ignore) {
        } finally {
            try { nsd.stopServiceDiscovery(listener); } catch (Exception ignore) {}
        }
        Log.i(TAG, "mDNS 结果：" + found[0]);
        return found[0];
    }

    /** 扫 127.0.0.1 高位端口（adbd 无线调试监听在 *:随机端口，回环可达）。 */
    private List<Integer> scanLoopbackPorts() {
        final ConcurrentLinkedQueue<Integer> open = new ConcurrentLinkedQueue<>();
        ExecutorService pool = Executors.newFixedThreadPool(128);
        long t0 = System.currentTimeMillis();
        for (int p = SCAN_FROM; p <= SCAN_TO; p++) {
            final int port = p;
            pool.execute(() -> {
                try (Socket s = new Socket()) {
                    s.connect(new InetSocketAddress("127.0.0.1", port), 120);
                    open.add(port);
                } catch (Exception ignore) { }
            });
        }
        pool.shutdown();
        try { pool.awaitTermination(25, TimeUnit.SECONDS); } catch (InterruptedException ignore) {}
        Log.i(TAG, "回环扫描：" + open.size() + " 个开放端口，耗时 "
                + (System.currentTimeMillis() - t0) + "ms");
        return new ArrayList<>(open);
    }

    /** 把已建立的明文 socket 升级为 TLS（STLS 应答之后调用）。 */
    public Socket wrapTls(Socket plain) throws Exception {
        SSLContext sc = sslContext();
        SSLSocket s = (SSLSocket) sc.getSocketFactory()
                .createSocket(plain, "127.0.0.1", plain.getPort(), true);
        s.setSoTimeout(15000);
        s.startHandshake();
        Log.i(TAG, "TLS 握手完成（STLS 升级）proto=" + s.getSession().getProtocol());
        return s;
    }

    /** 明文 TCP（adbd 对 loopback 可能接受明文 adb 帧，先试这个最省事）。 */
    public Socket openPlainSocket(int port) throws Exception {
        Socket s = new Socket();
        s.connect(new InetSocketAddress("127.0.0.1", port), 3000);
        s.setSoTimeout(8000);
        return s;
    }

    /** 对指定端口建 TLS 隧道；adb 握手由调用方做。 */
    public Socket openSocket(int port) throws Exception {
        Socket s = connectTls("127.0.0.1", port);
        lastPort = port;
        return s;
    }

    private Socket connectTls(String host, int port) throws Exception {
        SSLContext sc = sslContext();
        SSLSocket s = (SSLSocket) sc.getSocketFactory().createSocket();
        s.connect(new InetSocketAddress(host, port), 4000);
        s.setSoTimeout(15000);
        s.startHandshake();
        Log.i(TAG, "TLS 握手完成 -> " + host + ":" + port + " proto=" + s.getSession().getProtocol());
        return s;
    }

    private SSLContext sslContext() throws Exception {
        if (sslCtx != null) return sslCtx;
        byte[] keyDer = readPem("adbkey_wifi", "PRIVATE KEY");
        byte[] certDer = readPem("adbkey_wifi_cert.pem", "CERTIFICATE");
        PrivateKey pk = KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(keyDer));
        Certificate cert = CertificateFactory.getInstance("X.509")
                .generateCertificate(new java.io.ByteArrayInputStream(certDer));
        final java.security.cert.X509Certificate x509 = (java.security.cert.X509Certificate) cert;
        final PrivateKey fixedKey = pk;
        javax.net.ssl.X509ExtendedKeyManager fixedKm = new javax.net.ssl.X509ExtendedKeyManager() {
            private final String alias = "dsha-wifi";
            @Override public String[] getClientAliases(String kt, java.security.Principal[] i) { return new String[]{alias}; }
            @Override public String chooseClientAlias(String[] kt, java.security.Principal[] i, Socket s) { return alias; }
            @Override public String[] getServerAliases(String kt, java.security.Principal[] i) { return null; }
            @Override public String chooseServerAlias(String kt, java.security.Principal[] i, Socket s) { return null; }
            @Override public java.security.cert.X509Certificate[] getCertificateChain(String a) {
                return new java.security.cert.X509Certificate[]{x509};
            }
            @Override public PrivateKey getPrivateKey(String a) { return fixedKey; }
            @Override public String chooseEngineClientAlias(String[] kt, java.security.Principal[] i, javax.net.ssl.SSLEngine e) { return alias; }
            @Override public String chooseEngineServerAlias(String kt, java.security.Principal[] i, javax.net.ssl.SSLEngine e) { return null; }
        };
        javax.net.ssl.KeyManager[] kms = new javax.net.ssl.KeyManager[]{fixedKm};
        TrustManager[] tms = new TrustManager[]{new X509TrustManager() {
            @Override public void checkClientTrusted(java.security.cert.X509Certificate[] c, String a) { }
            @Override public void checkServerTrusted(java.security.cert.X509Certificate[] c, String a) { }
            @Override public java.security.cert.X509Certificate[] getAcceptedIssuers() {
                return new java.security.cert.X509Certificate[0];
            }
        }};
        SSLContext sc = SSLContext.getInstance("TLS");
        sc.init(kms, tms, null);
        sslCtx = sc;
        return sc;
    }

    private byte[] readPem(String asset, String label) throws Exception {
        InputStream is = ctx.getAssets().open(asset);
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
        is.close();
        String text = bos.toString("UTF-8");
        String begin = "-----BEGIN " + label + "-----";
        String end = "-----END " + label + "-----";
        int b = text.indexOf(begin);
        int e = text.indexOf(end);
        if (b < 0 || e < 0) throw new Exception("PEM 解析失败：" + asset);
        String body = text.substring(b + begin.length(), e).replaceAll("\\s", "");
        return android.util.Base64.decode(body, android.util.Base64.DEFAULT);
    }
}
