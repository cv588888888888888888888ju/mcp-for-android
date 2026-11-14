package com.jxev.ai;

import android.app.Application;
import android.util.Log;

public class AppBase extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        // 强制纯 IPv4 socket：Android 的 Java socket 默认绑成 IPv4-mapped IPv6
        // （ss 显示 [::ffff:192.0.2.4]），这种绑定收不到 IPv4 连接（实测 nc 超时），
        // wg 直连监听必须用 AF_INET 绑定 192.0.2.4（2026-09-29 实测定案）。
        System.setProperty("java.net.preferIPv4Stack", "true");
        Log.d("Jerry","AppBase onCreate Success");
    }
}
