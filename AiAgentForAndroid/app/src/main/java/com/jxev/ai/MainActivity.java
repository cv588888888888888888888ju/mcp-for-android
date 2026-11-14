package com.jxev.ai;

import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.jxev.ai.server.MCPService;

/**
 * \u6781\u7b80\u72b6\u6001\u9875\uff082026-08-14 \u91cd\u6784\uff09\uff1a\u65e0\u8f93\u5165\u6846\u3001\u65e0\u6309\u94ae\u4ea4\u4e92\u3002
 * \u4ec5\u5c55\u793a\u201c\u670d\u52a1\u8fd0\u884c\u4e2d + \u914d\u7f6e\u4fe1\u606f\u201d\uff0c\u5e76\u8d1f\u8d23\u542f\u52a8\u540e\u53f0 MCPService\u3002
 */
public class MainActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // \u542f\u52a8\u7eaf\u540e\u53f0\u670d\u52a1\uff08\u65e0 HTTP \u6ce8\u518c\uff0conCreate \u5185\u76f4\u63a5\u8d77 socket \u8fde\u63a5\uff09
        Intent serviceIntent = new Intent(this, MCPService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }

        // Android 13+ 通知运行时权限：info-hub 常驻通知靠它
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(
                    new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 1001);
        }

        TextView statusView = findViewById(R.id.statusTextView);
        TextView configView = findViewById(R.id.configTextView);

        statusView.setText("\u670d\u52a1\u8fd0\u884c\u4e2d\n" + MCPService.getLastStatus());

        String host = ConfigManager.getServerHost(this);
        int port = ConfigManager.getServerSocketPort(this);
        String token = ConfigManager.getDeviceToken(this);
        String tokenDisplay = (token == null || token.isEmpty())
                ? "<\u7a7a>"
                : token.substring(0, Math.min(4, token.length())) + "***";
        configView.setText("\u670d\u52a1\u5668: " + host + ":" + port
                + "\nToken: " + tokenDisplay
                + "\n\u6267\u884c\u5c42: \u672c\u5730 adb (127.0.0.1:5555)");
    }
}
