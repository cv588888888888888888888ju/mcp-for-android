# -*- coding: utf-8 -*-
"""mcp_probe.py — 最小 MCP streamable-http 客户端，用于端到端验证 bridge。

支持 streamable-http 会话：initialize 响应头 Mcp-Session-Id 会被后续请求携带，
否则 tools/list 会返回 400 Bad Request。

用法：
    python deploy/mcp_probe.py list                       # 列 tools
    python deploy/mcp_probe.py get_ui_state               # 调一个 tool（默认 get_ui_state）
    python deploy/mcp_probe.py adb_shell '{"command":"echo hi"}'

跨机/内网用 env 覆盖端点，例如：
    MCP_URL=http://192.0.2.1:9500/mcp python deploy/mcp_probe.py get_ui_state
"""
import json
import os
import sys
import urllib.error
import urllib.request

URL = os.environ.get("MCP_URL", "http://127.0.0.1:9500/mcp")
PROTOCOL_VERSION = os.environ.get("MCP_PROTOCOL_VERSION", "2025-11-25")

_session_id = None


def _parse_body(body: str):
    """streamable-http 可能返回 JSON，也可能返回 SSE(data: 行)。"""
    lines = [ln[5:].strip() for ln in body.splitlines() if ln.startswith("data:")]
    if lines:
        return json.loads("".join(lines))
    return json.loads(body)


def call(payload, timeout=120, session=None):
    """POST 一个 JSON-RPC 消息；返回 (response_dict_or_None, session_id)。"""
    global _session_id
    data = json.dumps(payload).encode()
    headers = {
        "Content-Type": "application/json",
        "Accept": "application/json, text/event-stream",
    }
    sid = session if session is not None else _session_id
    if sid:
        headers["Mcp-Session-Id"] = sid

    req = urllib.request.Request(URL, data=data, headers=headers)
    try:
        resp = urllib.request.urlopen(req, timeout=timeout)
    except urllib.error.HTTPError as exc:
        return {"_http_error": exc.code, "_body": exc.read().decode("utf-8", "replace")[:1000]}, sid

    new_sid = resp.headers.get("Mcp-Session-Id") or sid
    if new_sid:
        _session_id = new_sid
    raw = resp.read().decode("utf-8", "replace")
    if not raw.strip():
        # 202 Accepted / 通知类响应无 body
        return None, new_sid
    try:
        return _parse_body(raw), new_sid
    except Exception:
        return {"_raw": raw[:2000]}, new_sid


def initialize():
    r, sid = call(
        {
            "jsonrpc": "2.0",
            "id": 1,
            "method": "initialize",
            "params": {
                "protocolVersion": PROTOCOL_VERSION,
                "capabilities": {},
                "clientInfo": {"name": "mcp-probe", "version": "1.0"},
            },
        }
    )
    print("INIT:", json.dumps(r, ensure_ascii=False)[:300])
    print("SESSION:", sid)
    # 完成初始化握手（通知，无响应体）
    call({"jsonrpc": "2.0", "method": "notifications/initialized", "params": {}}, session=sid)
    return sid


def main() -> int:
    sid = initialize()

    if len(sys.argv) > 1 and sys.argv[1] == "list":
        r, _ = call({"jsonrpc": "2.0", "id": 2, "method": "tools/list", "params": {}}, session=sid)
        tools = (r or {}).get("result", {}).get("tools", [])
        if not tools:
            print("tools/list 返回异常:", json.dumps(r, ensure_ascii=False)[:500])
            return 1
        for t in tools:
            print(f"  {t['name']}: {t.get('description', '')[:70]}")
        print("TOTAL:", len(tools))
        return 0

    name = sys.argv[1] if len(sys.argv) > 1 else "get_ui_state"
    args = json.loads(sys.argv[2]) if len(sys.argv) > 2 else {}
    r, _ = call(
        {
            "jsonrpc": "2.0",
            "id": 3,
            "method": "tools/call",
            "params": {"name": name, "arguments": args},
        },
        session=sid,
    )
    text = json.dumps(r, ensure_ascii=False)
    print("CALL:", text[:4000])

    # 尽量抽出 status 字段，便于验收判定
    try:
        content = r["result"]["content"][0]["text"]
        payload = json.loads(content)
        print("STATUS:", payload.get("status"))
    except Exception:
        pass
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
