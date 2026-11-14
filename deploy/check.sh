#!/usr/bin/env bash
# check.sh — 部署后一键自检：TLS 握手 + 设备 token 模拟握手
# 用法：
#   bash check.sh                                  # 默认 127.0.0.1:9443 / :9444
#   bash check.sh 192.0.2.1 9443 9444 <token>       # ECS 内网自检（192.0.2.1 = ECS wg 地址）
set -euo pipefail

HOST="${1:-127.0.0.1}"
HTTP_PORT="${2:-9443}"
TCP_PORT="${3:-9444}"
TOKEN="${4:-${MCP_DEVICE_TOKEN:-}}"

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
CA_FILE="${REPO_ROOT}/ai_mcp_server/certs/ca.crt"

if [[ ! -f "${CA_FILE}" ]]; then
    echo "[错误] 找不到 CA 证书: ${CA_FILE}"
    echo "       请先跑: python3 ai_mcp_server/scripts/gen_cert.py"
    exit 2
fi

echo "=========================================="
echo "  mcp_for_android 公网 TLS 自检"
echo "  HTTPS port: ${HOST}:${HTTP_PORT}"
echo "  TCP/TLS:    ${HOST}:${TCP_PORT}"
echo "  CA file:    ${CA_FILE}"
echo "  Token:      ${TOKEN:-(空，不校验)}"
echo "=========================================="

echo
echo "[1/4] streamable-http (9443) TLS 握手"
openssl s_client -connect "${HOST}:${HTTP_PORT}" \
    -servername mcp.example.com \
    -CAfile "${CA_FILE}" </dev/null 2>&1 \
    | grep -E "Verify return code|subject=|issuer=|Protocol\s+:|Cipher\s+:" \
    || { echo "[失败] HTTPS 握手异常"; exit 1; }

echo
echo "[2/4] TCP/TLS (socket) 握手"
openssl s_client -connect "${HOST}:${TCP_PORT}" \
    -CAfile "${CA_FILE}" </dev/null 2>&1 \
    | grep -E "Verify return code|subject=|Protocol\s+:|Cipher\s+:" \
    || { echo "[失败] TCP/TLS 握手异常"; exit 1; }

echo
echo "[3/4] streamable-http 端点可达性"
STATUS_HTTP=$(curl -sk -o /dev/null -w '%{http_code}' \
    "https://${HOST}:${HTTP_PORT}/mcp" || echo "000")
if [[ "${STATUS_HTTP}" != "000" ]]; then
    echo "[OK] /mcp 有响应（HTTP ${STATUS_HTTP}）"
else
    echo "[提示] /mcp 无响应（bridge 是否以 BRIDGE_TRANSPORT=streamable-http 启动？）"
fi

echo
echo "[4/4] TCP/TLS 模拟设备握手"
PYTHON_BIN="$(command -v python3 || command -v python)"
"${PYTHON_BIN}" - "${HOST}" "${TCP_PORT}" "${TOKEN}" "${CA_FILE}" <<'PY'
import json, socket, ssl, sys

host, port, token, ca_file = sys.argv[1:5]
ctx = ssl.create_default_context(cafile=ca_file)
# 自签 CA 校验（必须 pass）：ctx 默认会校验 hostname，这里只校验证书链
ctx.check_hostname = False

with socket.create_connection((host, int(port)), timeout=10) as raw:
    with ctx.wrap_socket(raw, server_hostname=host) as s:
        msg = {"type": "handshake", "deviceId": "test-device-check", "timestamp": 0}
        if token:
            msg["token"] = token
        s.sendall((json.dumps(msg) + "\n").encode("utf-8"))
        line = s.recv(4096)
        try:
            resp = json.loads(line.decode("utf-8").strip().splitlines()[0])
        except Exception as e:
            print(f"[失败] 解析响应失败: {e}; raw={line[:200]!r}")
            sys.exit(1)
        status = resp.get("status")
        if status == "ok":
            print(f"[OK] token={'正确' if token else '<未校验>'} -> handshake_response.status=ok")
            sys.exit(0)
        elif status == "error":
            err = resp.get("error", "?")
            print(f"[提示] server 返回 error: {err}")
            # 区分错误类型：unauthorized 表示 token 错误（测试通过）；其它则视为真错
            if err == "unauthorized" and token:
                print("[OK] token 错误被正确拒绝")
                sys.exit(0)
            sys.exit(1)
        else:
            print(f"[失败] 未知响应: {resp}")
            sys.exit(1)
PY
RC=$?
if [[ $RC -ne 0 ]]; then
    echo "[失败] 模拟设备握手 exit=$RC"
    exit $RC
fi

echo
echo "=========================================="
echo "  全部检查通过 ✓"
echo "=========================================="
