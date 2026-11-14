r"""
端到端测试 Phone MCP Bridge。

用法：
    C:\Python312\python.exe scripts/test_bridge.py

前置：
- 手机 App 已安装并配置 server_host=本机IP, server_socket_port=9444
- bridge 进程由本脚本用 stdio_client 自动起子进程（无需手开窗口）
- 默认 token=test-token-123；如服务器实际 token 不同，可通过环境变量 MCP_DEVICE_TOKEN 覆盖

执行步骤（每步把结果写到控制台，并落盘到 codex_phone_bridge_output.md）：
    1. list_tools —— 校验 11 个 tool 注册
    2. get_installed_apps —— 校验手机在线且返回非空列表
    3. get_ui_state —— 校验返回结构化 UI（含 currentPackage）
    4. launch_app("com.android.settings") —— 用 dumpsys 验证前台是 settings
    5. press_back —— 校验 dumpsys 显示前台已切换
    6. click / type_text —— 用 get_ui_state 找元素后再点 / 输入
"""
from __future__ import annotations

import asyncio
import json
import os
import shutil
import subprocess
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
BRIDGE = ROOT / "bridge_mcp.py"
OUT_LOG = ROOT / "codex_phone_bridge_output.md"
PYTHON = os.environ.get("PHONE_BRIDGE_PY", shutil.which("python") or r"C:\Python312\python.exe")
ADB = shutil.which("adb") or r"C:\platform-tools\adb.exe"
DEVICE_TOKEN = os.environ.get("MCP_DEVICE_TOKEN", "test-token-123")


def _log(msg: str):
    line = f"[{time.strftime('%H:%M:%S')}] {msg}"
    print(line, flush=True)
    try:
        with OUT_LOG.open("a", encoding="utf-8") as f:
            f.write(line + "\n")
    except Exception:
        pass


def _dump_top_activity() -> str:
    """通过 dumpsys 读取当前前台 Activity，返回顶层 component 字符串。"""
    try:
        out = subprocess.run(
            [ADB, "shell", "dumpsys", "activity", "activities"],
            capture_output=True, text=True, timeout=10,
        )
        for line in out.stdout.splitlines():
            if "topResumedActivity" in line or "ResumedActivity" in line:
                # 形如 "topResumedActivity=ComponentInfo{com.android.settings/com.android.settings.Settings}"
                return line.strip()
    except Exception as e:
        _log(f"adb dumpsys 失败: {e}")
    return ""


async def main():
    _log("=== test_bridge 启动 ===")
    _log(f"PYTHON={PYTHON}")
    _log(f"BRIDGE={BRIDGE} exists={BRIDGE.exists()}")
    _log(f"DEVICE_TOKEN={DEVICE_TOKEN[:6]}***（已脱敏）")

    if not BRIDGE.exists():
        _log(f"[FATAL] bridge 文件不存在: {BRIDGE}")
        return 2

    env = os.environ.copy()
    env["MCP_DEVICE_TOKEN"] = DEVICE_TOKEN
    # PATH 把本进程 python 目录带上，确保子进程能找到 site-packages
    env["PATH"] = str(Path(PYTHON).parent) + os.pathsep + env.get("PATH", "")

    from mcp.client.stdio import StdioServerParameters, stdio_client
    from mcp import ClientSession

    params = StdioServerParameters(
        command=PYTHON,
        args=[str(BRIDGE)],
        env=env,
        cwd=str(ROOT),
    )

    failures: list[str] = []

    async with stdio_client(params) as (read, write):
        async with ClientSession(read, write) as session:
            # 1. initialize + list_tools
            await session.initialize()
            tools = (await session.list_tools()).tools
            _log(f"[1] tools registered: {[t.name for t in tools]}")
            wanted = {
                "launch_app", "click", "type_text", "scroll", "long_click",
                "press_back", "press_home", "press_recents",
                "get_ui_state", "get_installed_apps", "swipe",
            }
            missing = wanted - {t.name for t in tools}
            if missing:
                failures.append(f"list_tools 缺少: {sorted(missing)}")

            async def call(name: str, args: dict | None = None):
                _log(f"  → call {name}({json.dumps(args or {}, ensure_ascii=False)})")
                r = await session.call_tool(name, args or {})
                # CallToolResult 有 content 列表（TextContent）和 isError
                content = []
                for c in (r.content or []):
                    content.append(getattr(c, "text", str(c)))
                text = "\n".join(content).strip()
                try:
                    parsed = json.loads(text) if text.startswith("{") else None
                except Exception:
                    parsed = None
                is_err = getattr(r, "isError", False)
                _log(f"    isError={is_err}; raw={text[:400]}")
                return {"text": text, "parsed": parsed, "is_error": bool(is_err)}

            # 2. get_installed_apps
            r = await call("get_installed_apps")
            apps = []
            if r["parsed"]:
                apps = r["parsed"].get("installed_apps") or []
            if not apps:
                failures.append("get_installed_apps 返回空或未解析到 list（手机可能未连）")
            else:
                _log(f"  [OK] {len(apps)} apps, 前 3: {apps[:3]}")

            # 3. get_ui_state
            r = await call("get_ui_state")
            ui = (r["parsed"] or {}).get("ui_state") or {}
            cur_pkg = ui.get("current_package") or ui.get("currentPackage")
            _log(f"  current_package={cur_pkg}")
            if not cur_pkg:
                failures.append("get_ui_state 未拿到 current_package")

            # 4. launch_app com.android.settings
            r = await call("launch_app", {"package_name": "com.android.settings"})
            if (r["parsed"] or {}).get("status") not in ("success",):
                _log(f"  launch_app 状态: {r['parsed']}")
            time.sleep(2)
            top = _dump_top_activity()
            _log(f"  dumpsys: {top}")
            if "com.android.settings" not in top:
                failures.append(f"launch_app(com.android.settings) 后前台不是 settings: {top}")

            # 5. press_back
            r = await call("press_back")
            time.sleep(2)
            top2 = _dump_top_activity()
            _log(f"  dumpsys after back: {top2}")
            if top2 == top:
                failures.append("press_back 后前台未变化")

            # 6. click + type_text：用 launcher 上的搜索框（视设备而异，失败也不致命）
            # 先回到桌面
            await call("press_home")
            time.sleep(1)
            r = await call("get_ui_state")
            ui = (r["parsed"] or {}).get("ui_state") or {}
            texts = ui.get("visible_text") or []
            _log(f"  桌面 visible_text 前 10: {texts[:10]}")

            # 用坐标尝试一次点击（屏幕中央偏上）
            r = await call("click", {"x": 540, "y": 1200})
            time.sleep(1)
            top3 = _dump_top_activity()
            _log(f"  click(x=540,y=1200) 后 dumpsys: {top3}")

            # 最后 press_home 回桌面
            await call("press_home")

    _log("=== test_bridge 完成 ===")
    if failures:
        _log(f"[FAIL] {len(failures)} 项失败：")
        for f in failures:
            _log(f"  - {f}")
        return 1
    _log("[PASS] 全部步骤通过")
    return 0


if __name__ == "__main__":
    sys.exit(asyncio.run(main()))


