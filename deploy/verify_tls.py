# -*- coding: utf-8 -*-
"""独立验收: mcp_for_android TLS 服务端实测 (模拟真实手机行为)
验证: 1) HTTPS /status 2) TCP/TLS 设备握手三态 (正确token/错误token/无token)
"""
import json
import socket
import ssl
import urllib.request

BASE = "/path/to/mcp_for_android/ai_mcp_server"
CA = BASE + r"\certs\ca.crt"
TOKEN = "test-token-123"
results = []

def check(name, ok, detail=""):
    results.append((name, ok, detail))
    print(f"[{'PASS' if ok else 'FAIL'}] {name} {detail}")

# ---------- 1. HTTPS /status ----------
try:
    ctx = ssl.create_default_context(cafile=CA)
    resp = urllib.request.urlopen("https://127.0.0.1:9443/status", context=ctx, timeout=10)
    body = resp.read().decode("utf-8", "replace")[:200]
    check("HTTPS /status", resp.status == 200, f"HTTP {resp.status} body={body!r}")
except Exception as e:
    check("HTTPS /status", False, str(e))

# ---------- 2. TCP/TLS 设备握手 ----------
def tls_handshake(payload: dict) -> dict:
    ctx = ssl.create_default_context(cafile=CA)
    ctx.check_hostname = False  # 连 IP, 证书 SAN 校验由业务层做
    raw = socket.create_connection(("127.0.0.1", 9444), timeout=10)
    with ctx.wrap_socket(raw, server_hostname="mcp.example.com") as s:
        s.sendall((json.dumps(payload) + "\n").encode("utf-8"))
        line = s.recv(4096).decode("utf-8", "replace")
        return json.loads(line)

try:
    r = tls_handshake({"type": "handshake", "deviceId": "verify-dev", "token": TOKEN})
    check("握手-正确token", r.get("status") == "ok", json.dumps(r, ensure_ascii=False)[:120])
except Exception as e:
    check("握手-正确token", False, str(e))

try:
    r = tls_handshake({"type": "handshake", "deviceId": "verify-dev", "token": "WRONG-TOKEN"})
    check("握手-错误token", r.get("status") == "error", json.dumps(r, ensure_ascii=False)[:120])
except Exception as e:
    check("握手-错误token", False, str(e))

try:
    r = tls_handshake({"type": "handshake", "deviceId": "verify-dev"})
    check("握手-无token", r.get("status") == "error", json.dumps(r, ensure_ascii=False)[:120])
except Exception as e:
    check("握手-无token", False, str(e))

print("\n=== SUMMARY ===")
fails = [x for x in results if not x[1]]
print(f"{len(results) - len(fails)}/{len(results)} passed")
if fails:
    print("FAILED:", [x[0] for x in fails])
    raise SystemExit(1)
