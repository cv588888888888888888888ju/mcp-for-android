package com.jxev.ai;

import android.content.Context;
import android.util.Log;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

/**
 * MCPTls — 公网 TLS 信任锚（增量改动，2026-08-14）
 *
 * 作用：
 *  - 从 res/raw/server_ca.pem 读取自签 CA 公钥
 *  - 构造只信任该 CA 的 X509TrustManager（pin CA 模式）
 *  - 提供 SSLSocketFactory + HostnameVerifier 给 MCPSocketClient (TCP/TLS) 和
 *    MCPService (OkHttp HTTPS) 共用
 *
 * 读取不到 raw 资源时 fallback 为「信任系统证书」+ 显式日志告警，不让 App 直接崩。
 */
public final class MCPTls {
    private static final String TAG = "MCPTls";
    // res/raw/ 下的文件名（无后缀的常量名映射）
    private static final String RAW_CA_RES_NAME = "server_ca";

    private static volatile MCPTls instance;

    private final SSLSocketFactory sslSocketFactory;
    private final X509TrustManager trustManager;
    private final HostnameVerifier hostnameVerifier;

    private MCPTls(Context appContext) {
        SSLSocketFactory factory;
        X509TrustManager tm;
        HostnameVerifier verifier;

        X509Certificate pinnedCa = loadCaFromResources(appContext);
        if (pinnedCa == null) {
            // fallback：未找到 raw CA 时，信任系统证书（不 pin），保证 App 至少能跑
            Log.e(TAG, "未找到 res/raw/" + RAW_CA_RES_NAME + "，fallback 为系统信任");
            try {
                TrustManagerFactory defaultTmf = TrustManagerFactory.getInstance(
                        TrustManagerFactory.getDefaultAlgorithm());
                defaultTmf.init((KeyStore) null);
                tm = (X509TrustManager) defaultTmf.getTrustManagers()[0];
                factory = (SSLSocketFactory) SSLSocketFactory.getDefault();
            } catch (Exception e) {
                Log.e(TAG, "初始化系统 TrustManager 失败", e);
                throw new IllegalStateException("无法初始化 TLS", e);
            }
        } else {
            try {
                // 用单 CA 构造 KeyStore -> TrustManager -> SSLContext
                KeyStore ks = KeyStore.getInstance(KeyStore.getDefaultType());
                ks.load(null, null);
                ks.setCertificateEntry("mcp-ca", pinnedCa);

                TrustManagerFactory tmf = TrustManagerFactory.getInstance(
                        TrustManagerFactory.getDefaultAlgorithm());
                tmf.init(ks);
                tm = (X509TrustManager) tmf.getTrustManagers()[0];

                SSLContext ctx = SSLContext.getInstance("TLS");
                ctx.init(null, new TrustManager[]{tm}, null);
                factory = ctx.getSocketFactory();
            } catch (Exception e) {
                Log.e(TAG, "初始化 pin CA TrustManager 失败", e);
                throw new IllegalStateException("无法初始化 pin TLS", e);
            }
        }

        // HostnameVerifier：允许用户配置的 host/IP/DNS；
        // 这里只校验证书里的 SAN 与当前请求 host 完全一致（不做通配符）。
        verifier = new HostnameVerifier() {
            @Override
            public boolean verify(String hostname, SSLSession session) {
                try {
                    java.security.cert.Certificate[] certs = session.getPeerCertificates();
                    if (certs.length == 0) {
                        return false;
                    }
                    X509Certificate leaf = (X509Certificate) certs[0];
                    // 1. CN 匹配
                    String cn = extractCn(leaf.getSubjectX500Principal().getName());
                    if (hostname.equalsIgnoreCase(cn)) {
                        return true;
                    }
                    // 2. SAN 匹配（subjectAltNames）
                    java.util.Collection<java.util.List<?>> sans = leaf.getSubjectAlternativeNames();
                    if (sans == null) {
                        return false;
                    }
                    for (java.util.List<?> san : sans) {
                        if (san.size() < 2) {
                            continue;
                        }
                        int type = ((Number) san.get(0)).intValue();
                        Object value = san.get(1);
                        // type 2 = DNS, type 7 = IP
                        if (type == 2 && hostname.equalsIgnoreCase(String.valueOf(value))) {
                            return true;
                        }
                        if (type == 7 && hostname.equals(String.valueOf(value))) {
                            return true;
                        }
                    }
                    return false;
                } catch (Exception e) {
                    Log.w(TAG, "HostnameVerifier 异常: " + e.getMessage());
                    return false;
                }
            }
        };

        this.trustManager = tm;
        this.sslSocketFactory = factory;
        this.hostnameVerifier = verifier;
    }

    public static MCPTls getInstance(Context context) {
        if (instance == null) {
            synchronized (MCPTls.class) {
                if (instance == null) {
                    // 必须传 applicationContext 避免泄漏 Activity
                    instance = new MCPTls(context.getApplicationContext());
                }
            }
        }
        return instance;
    }

    public SSLSocketFactory getSSLSocketFactory() {
        return sslSocketFactory;
    }

    public X509TrustManager getTrustManager() {
        return trustManager;
    }

    public HostnameVerifier getHostnameVerifier() {
        return hostnameVerifier;
    }

    /**
     * 设置 OkHttp / HttpsURLConnection 全局默认 hostname verifier
     * （用于 OkHttpClient.Builder 不方便传 HostnameVerifier 时的兜底）
     */
    public void applyAsDefault() {
        HttpsURLConnection.setDefaultHostnameVerifier(hostnameVerifier);
    }

    private static X509Certificate loadCaFromResources(Context ctx) {
        int resId = ctx.getResources().getIdentifier(RAW_CA_RES_NAME, "raw", ctx.getPackageName());
        if (resId == 0) {
            Log.w(TAG, "res/raw/" + RAW_CA_RES_NAME + " 不存在");
            return null;
        }
        InputStream is = null;
        try {
            is = new BufferedInputStream(ctx.getResources().openRawResource(resId));
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            Certificate cert = cf.generateCertificate(is);
            return (X509Certificate) cert;
        } catch (Exception e) {
            Log.e(TAG, "读取 CA 公钥失败", e);
            return null;
        } finally {
            if (is != null) {
                try { is.close(); } catch (IOException ignored) { }
            }
        }
    }

    private static String extractCn(String dn) {
        // 极简 DN 解析：取 CN= 之后的第一个逗号前内容
        int idx = dn.indexOf("CN=");
        if (idx < 0) {
            return "";
        }
        int start = idx + 3;
        int end = dn.indexOf(',', start);
        String cn = (end < 0) ? dn.substring(start) : dn.substring(start, end);
        return cn.trim();
    }
}
