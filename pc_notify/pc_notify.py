# -*- coding: utf-8 -*-
"""pc_notify.py — 电脑端系统通知轮询服务
每 10s 轮询 ECS Bridge 的 /pending_notifications 端点，有新通知则弹 Windows toast。
纯 PowerShell toast（零依赖）。用法: python pc_notify.py [--token XXX] [--interval 10]
"""
import argparse
import subprocess
import sys
import time
import urllib.request
import urllib.parse

DEFAULT_URL = "http://127.0.0.1:18999/pending_notifications"

TOAST_PS = r"""
param($title, $text)
[Windows.UI.Notifications.ToastNotificationManager, Windows.UI.Notifications, ContentType = WindowsRuntime] | Out-Null
[Windows.Data.Xml.Dom.XmlDocument, Windows.Data.Xml.Dom.XmlDocument, ContentType = WindowsRuntime] | Out-Null
$xml = New-Object Windows.Data.Xml.Dom.XmlDocument
$xml.LoadXml("<toast><visual><binding template='ToastText02'><text id='1'>" + $title + "</text><text id='2'>" + $text + "</text></binding></visual></toast>")
[Windows.UI.Notifications.ToastNotificationManager]::CreateToastNotifier('PhoneMCP').Show([Windows.UI.Notifications.ToastNotification]::new($xml))
"""


def show_toast(title: str, text: str) -> bool:
    """PowerShell 弹系统通知；返回是否成功。变量嵌入命令串（-Command 后不接受自定义参数）。"""
    try:
        # 转义单引号，嵌入 PowerShell 变量
        safe_title = title.replace("'", "''")
        safe_text = text.replace("'", "''")
        cmd = (f"$t = '{safe_title}'; $x = '{safe_text}'; " + TOAST_PS)
        ps = ["powershell", "-NoProfile", "-STA", "-Command", cmd]
        r = subprocess.run(ps, capture_output=True, timeout=15)
        if r.returncode != 0:
            print(f"[pc_notify] toast stderr: {r.stderr.decode('utf-8', 'replace')[:200]}", flush=True)
        return r.returncode == 0
    except Exception as e:
        print(f"[pc_notify] toast 失败: {e}", flush=True)
        return False


def poll_once(url: str, token: str) -> list:
    """拉取一次待通知队列；成功返回通知列表，失败返回空。"""
    try:
        q = urllib.parse.urlencode({"token": token})
        req = urllib.request.Request(f"{url}?{q}", method="GET")
        with urllib.request.urlopen(req, timeout=10) as resp:
            if resp.status != 200:
                return []
            import json
            data = json.loads(resp.read().decode("utf-8", "replace"))
            return data.get("notifications", [])
    except Exception as e:
        print(f"[pc_notify] 轮询失败: {e}", flush=True)
        return []


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--token", default=None)
    ap.add_argument("--url", default=DEFAULT_URL)
    ap.add_argument("--interval", type=int, default=10)
    args = ap.parse_args()

    import os
    token = args.token or os.environ.get("NOTIFY_TOKEN", "")
    if not token:
        print("[pc_notify] 缺少 token（--token 或 NOTIFY_TOKEN）", flush=True)
        sys.exit(2)

    print(f"[pc_notify] 启动：轮询 {args.url}，每 {args.interval}s（token 已配置）", flush=True)
    while True:
        notifs = poll_once(args.url, token)
        for n in notifs:
            title = n.get("title", "手机脚本提醒")
            text = n.get("text", "")
            ok = show_toast(title, text)
            print(f"[pc_notify] toast {'OK' if ok else 'FAIL'}: {title} | {text[:40]}", flush=True)
        time.sleep(args.interval)


if __name__ == "__main__":
    main()
