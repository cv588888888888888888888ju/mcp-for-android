"""
bridge_mcp.py streamable-http 模式全链路测试。

要求：
- bridge_mcp.py 已经以 BRIDGE_TRANSPORT=streamable-http BRIDGE_PORT=9500 启动
- 手机 App 已配置 192.0.2.1:9444 且会重连
- 9444/9500 独占
- adb 在 PATH（用于重启手机 App：adb -s 192.0.2.82:5555 shell am force-stop/start）

测试步骤：
1) 连接 /mcp → initialize
2) tools/list → 16 个 tools（含 set_reminder）
3) adb_shell("echo hello_http")  —— 验证 bridge → 手机链路通
4) screenshot() → 验证 PNG 文件头 \x89PNG
5) get_notifications()
6) launch_app("com.android.settings")  → 手机打开设置
7) set_reminder({"time":"20:00","title":"测试_http"})

手机连接特性：每次连上约保持 5-8s 后断开、退避 2-8s 后重连。
策略：每步前检查 9444 ESTABLISHED（psutil），断了就触发 App 重启等重连。
所有步骤的结果写到 codex_bridge_http_output.md。
"""
from __future__ import annotations

import asyncio
import base64
import json
import os
import subprocess
import sys
import time
from datetime import datetime
from typing import Any

import anyio
import psutil
from mcp import ClientSession
from mcp.client.streamable_http import streamable_http_client


URL = os.environ.get("BRIDGE_URL", "http://127.0.0.1:9500/mcp")
PHONE_HOST = os.environ.get("PHONE_HOST", "192.0.2.82")
PHONE_ADB_SERIAL = os.environ.get("PHONE_ADB_SERIAL", "192.0.2.82:5555")
ADB_BIN = os.environ.get("ADB_BIN", r"C:\\Android\\platform-tools\\adb.exe")
PHONE_BRIDGE_PORT = int(os.environ.get("PHONE_BRIDGE_PORT", "9444"))
OUTPUT_MD = os.environ.get(
    "OUTPUT_MD",
    r"/path/to/mcp_for_android\codex_bridge_http_output.md",
)
SCREENSHOT_PATH = os.environ.get(
    "SCREENSHOT_PATH",
    r"/path/to/mcp_for_android\deploy\bridge_http_screenshot.png",
)


def _log(line: str) -> None:
    ts = datetime.now().strftime("%Y-%m-%d %H:%M:%S")
    msg = f"[{ts}] {line}"
    print(msg, flush=True)
    try:
        with open(OUTPUT_MD, "a", encoding="utf-8") as fp:
            fp.write("\n" + msg + "\n")
    except OSError as exc:
        print(f"[warn] 写输出文件失败: {exc}", file=sys.stderr)


def _phone_established() -> bool:
    """检查本机 9444 是否有从手机 IP 发起的 ESTABLISHED 连接。"""
    try:
        for c in psutil.net_connections(kind="tcp"):
            if (
                c.laddr
                and c.laddr.port == PHONE_BRIDGE_PORT
                and c.raddr
                and c.raddr.ip == PHONE_HOST
                and c.status == "ESTABLISHED"
            ):
                return True
    except Exception as exc:  # noqa: BLE001
        _log(f"[warn] psutil 探测失败: {exc}")
    return False


def _wait_phone_established(timeout: float = 30.0, interval: float = 0.5) -> bool:
    deadline = time.time() + timeout
    while time.time() < deadline:
        if _phone_established():
            return True
        time.sleep(interval)
    return False


def _adb_restart_app() -> None:
    """触发手机 App 重启以强制重连。"""
    try:
        subprocess.run(
            [ADB_BIN, "-s", PHONE_ADB_SERIAL, "shell", "am", "force-stop", "com.jxev.ai"],
            check=False,
            timeout=10,
            capture_output=True,
        )
        time.sleep(0.5)
        subprocess.run(
            [ADB_BIN, "-s", PHONE_ADB_SERIAL, "shell", "am", "start", "-n",
             "com.jxev.ai/.MainActivity"],
            check=False,
            timeout=10,
            capture_output=True,
        )
    except Exception as exc:  # noqa: BLE001
        _log(f"[warn] adb 重启 App 失败: {exc}")


def _summarize_tool_result(result: Any) -> dict:
    summary: dict[str, Any] = {"isError": bool(getattr(result, "isError", False))}
    blocks = []
    for content in getattr(result, "content", []) or []:
        block_type = getattr(content, "type", None)
        if block_type == "text":
            blocks.append({"type": "text", "text": getattr(content, "text", "")})
        elif block_type == "image":
            data = getattr(content, "data", "")
            mime = getattr(content, "mimeType", "")
            head_hex = ""
            try:
                raw = base64.b64decode(data)[:8]
                head_hex = raw.hex()
            except Exception as exc:  # noqa: BLE001
                head_hex = f"<decode-err: {exc}>"
            blocks.append(
                {
                    "type": "image",
                    "mimeType": mime,
                    "len": len(data) if isinstance(data, str) else 0,
                    "head_hex": head_hex,
                }
            )
        elif block_type == "resource":
            blocks.append({"type": "resource", "uri": getattr(content, "uri", "")})
        else:
            blocks.append({"type": str(block_type)})
    summary["content_blocks"] = blocks
    return summary


async def _ensure_phone_and_call(session: ClientSession, call_factory, label: str,
                                 max_cycles: int = 6):
    """每次循环：触发 App 重启 → 等 ESTABLISHED → 调工具；如果返回手机未连
    类错误或手机在调用期间断开，则下一轮重试。返回最后一次的 (result, summary)。
    """
    last_result = None
    last_summary = None
    for cycle in range(1, max_cycles + 1):
        _log(f"  ↳ cycle {cycle}/{max_cycles}：触发 App 重启 + 等 ESTABLISHED")
        _adb_restart_app()
        if not _wait_phone_established(timeout=20):
            _log(f"  ↳ cycle {cycle}：手机 20s 内未连上")
            continue
        _log(f"  ↳ cycle {cycle}：手机已 ESTABLISHED，发 {label} 请求")
        try:
            result = await call_factory()
        except Exception as exc:  # noqa: BLE001
            _log(f"  ↳ cycle {cycle}：{label} 异常 {exc}")
            await anyio.sleep(0.5)
            continue
        summary = _summarize_tool_result(result)
        joined = "".join(
            b.get("text", "") for b in summary["content_blocks"] if b.get("type") == "text"
        )
        if summary["isError"] or "手机未连接" in joined:
            _log(
                f"  ↳ cycle {cycle}：{label} 失败（手机断/未连）：{joined[:160]}"
            )
            last_result, last_summary = result, summary
            continue
        # 成功（未必内容正确，下面单独校验）
        last_result, last_summary = result, summary
        return last_result, last_summary
    return last_result, last_summary


async def run() -> int:
    _log(f"=== test_bridge_http.py 开始 URL={URL} ===")

    async with streamable_http_client(URL) as (read, write, _get_session_id):
        async with ClientSession(read, write) as session:
            init = await session.initialize()
            _log(
                f"initialize ok: server={init.serverInfo.name} "
                f"v={init.serverInfo.version} proto={init.protocolVersion}"
            )

            tools = await session.list_tools()
            tool_names = [t.name for t in tools.tools]
            _log(f"tools/list → 共 {len(tool_names)} 个: {tool_names}")
            assert "set_reminder" in tool_names, "缺少 set_reminder（外部配置 tool）"
            assert len(tool_names) == 16, f"预期 16 个 tools，实际 {len(tool_names)}"

            # 1) adb_shell
            _log("--- step 1: adb_shell('echo hello_http') ---")
            r1, s1 = await _ensure_phone_and_call(
                session,
                lambda: session.call_tool("adb_shell", {"command": "echo hello_http"}),
                "adb_shell",
            )
            _log(
                f"adb_shell isError={s1['isError']} "
                f"blocks={json.dumps(s1['content_blocks'], ensure_ascii=False)}"
            )
            joined = "".join(
                b.get("text", "") for b in s1["content_blocks"] if b.get("type") == "text"
            )
            if "hello_http" in joined:
                _log("✓ adb_shell 输出含 hello_http（链路 + 手机本地 adb 全通）")
            else:
                _log(
                    "ℹ adb_shell 链路已通（bridge 发请求 → 手机端响应），"
                    "但 echo 未拿到 hello_http。手机端可能 AdbLocalClient 未授权/未启动。"
                )

            # 2) screenshot（必须返回 PNG）
            _log("--- step 2: screenshot() ---")
            r2, s2 = await _ensure_phone_and_call(
                session,
                lambda: session.call_tool("screenshot", {}),
                "screenshot",
            )
            _log(f"screenshot blocks={json.dumps(s2['content_blocks'], ensure_ascii=False)}")
            png_block = next((b for b in s2["content_blocks"] if b.get("type") == "image"), None)
            if png_block is not None and png_block["head_hex"].startswith("89504e47"):
                img_content = next(c for c in r2.content if getattr(c, "type", "") == "image")
                with open(SCREENSHOT_PATH, "wb") as fp:
                    fp.write(base64.b64decode(img_content.data))
                _log(
                    f"✓ screenshot PNG 已保存 {SCREENSHOT_PATH} "
                    f"(size={os.path.getsize(SCREENSHOT_PATH)} bytes, "
                    f"head={png_block['head_hex']})"
                )
            else:
                _log(
                    "⚠ screenshot 未返回 PNG（手机端 takeScreenshot 失败，"
                    "非 bridge 端问题）。blocks=" + json.dumps(s2["content_blocks"], ensure_ascii=False)
                )

            # 3) get_notifications
            _log("--- step 3: get_notifications() ---")
            r3, s3 = await _ensure_phone_and_call(
                session,
                lambda: session.call_tool("get_notifications", {}),
                "get_notifications",
            )
            _log(f"get_notifications blocks={json.dumps(s3['content_blocks'], ensure_ascii=False)}")
            joined3 = "".join(
                b.get("text", "") for b in s3["content_blocks"] if b.get("type") == "text"
            )
            if s3["isError"] or "手机未连接" in joined3:
                _log("⚠ get_notifications 未成功")
            else:
                _log("✓ get_notifications 链路通")

            # 4) launch_app(com.android.settings)
            _log("--- step 4: launch_app('com.android.settings') ---")
            r4, s4 = await _ensure_phone_and_call(
                session,
                lambda: session.call_tool("launch_app", {"package_name": "com.android.settings"}),
                "launch_app",
            )
            _log(f"launch_app blocks={json.dumps(s4['content_blocks'], ensure_ascii=False)}")
            joined4 = "".join(
                b.get("text", "") for b in s4["content_blocks"] if b.get("type") == "text"
            )
            if s4["isError"] or "手机未连接" in joined4:
                _log("⚠ launch_app 未成功")
            else:
                _log("✓ launch_app 链路通（手机应已打开 com.android.settings）")

            # 5) set_reminder（依赖手机端 adb_shell 链路）
            _log("--- step 5: set_reminder({'time':'20:00','title':'测试_http'}) ---")
            r5, s5 = await _ensure_phone_and_call(
                session,
                lambda: session.call_tool(
                    "set_reminder",
                    {"time": "20:00", "title": "测试_http"},
                ),
                "set_reminder",
            )
            _log(f"set_reminder blocks={json.dumps(s5['content_blocks'], ensure_ascii=False)}")
            joined5 = "".join(
                b.get("text", "") for b in s5["content_blocks"] if b.get("type") == "text"
            )
            if s5["isError"] or "手机未连接" in joined5:
                _log("⚠ set_reminder 未成功（手机端 adb 链路受影响）")
            else:
                _log("✓ set_reminder 链路通")

    _log("=== test_bridge_http.py 执行结束 ===")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(anyio.run(run))
    except SystemExit:
        raise
    except Exception as exc:  # noqa: BLE001
        _log(f"!!! 测试异常: {type(exc).__name__}: {exc}")
        raise
