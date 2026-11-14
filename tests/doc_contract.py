#!/usr/bin/env python3
"""文档契约测试：把 docs/*.md 里的可机械核对的断言，和代码/线上服务对一遍。

用法：python3 tests/doc_contract.py            # 只对本地代码
      python3 tests/doc_contract.py --live     # 额外对线上（需能 ssh 到 MCP_RELAY_HOST / MCP_BRIDGE_HOST）

线上主机与 SSH 私钥走环境变量（默认值是文档占位符，必须覆盖才能跑 --live）：
      MCP_BRIDGE_HOST  bridge 所在主机（默认 192.0.2.9）
      MCP_RELAY_HOST   relay 所在主机（默认 192.0.2.1）
      MCP_SSH_KEY      SSH 私钥路径（默认 ~/.ssh/id_ed25519）
退出码：0 全过；1 有 FAIL。
"""
import re
import os
import sys
import json
import subprocess

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
BRIDGE_HOST = os.environ.get("MCP_BRIDGE_HOST", "192.0.2.9")
RELAY_HOST = os.environ.get("MCP_RELAY_HOST", "192.0.2.1")
SSH_KEY = os.path.expanduser(os.environ.get("MCP_SSH_KEY", "~/.ssh/id_ed25519"))
DOCS = os.path.join(ROOT, "docs")
FAILS, OKS = [], []


def ok(msg):
    OKS.append(msg)
    print(f"  OK   {msg}")


def fail(msg):
    FAILS.append(msg)
    print(f"  FAIL {msg}")


def read(p):
    with open(p, encoding="utf-8") as f:
        return f.read()


# ---------- 1. MCP 工具：代码 vs 文档 ----------
def check_tools():
    print("[1] MCP 工具（代码 ↔ INTERFACE.md）")
    src = read(os.path.join(ROOT, "ai_mcp_server", "bridge_mcp.py"))
    code_tools = re.findall(r"@mcp\.tool\([^)]*\)\s*\ndef (\w+)\(", src)
    doc = read(os.path.join(DOCS, "INTERFACE.md"))
    # 只看 §2 工具目录：行首单元格里所有反引号标识符（支持 `a` / `b` 这种合写）
    sec2 = doc.split("## 2. MCP 工具目录")[1].split("## 3.")[0]
    doc_tools = set()
    for line in sec2.splitlines():
        if not line.startswith("| `"):
            continue
        cell = line.split("|")[1]
        doc_tools |= set(re.findall(r"`([a-z_]+)`", cell))
    missing_doc = sorted(set(code_tools) - doc_tools)
    extra_doc = sorted(doc_tools - set(code_tools))
    (fail if missing_doc else ok)(
        f"代码有 {len(code_tools)} 个工具" + (f"，文档缺：{missing_doc}" if missing_doc else "，文档全覆盖"))
    if extra_doc:
        fail(f"文档列了代码没有的工具：{extra_doc}")
    m = re.search(r"## 2\. MCP 工具目录（(\d+) 个）", doc)
    if m and int(m.group(1)) != len(code_tools):
        fail(f"文档写 {m.group(1)} 个，代码 {len(code_tools)} 个")
    elif m:
        ok(f"文档数量标注 {m.group(1)} 个与代码一致")
    readme = read(os.path.join(ROOT, "README.md"))
    rm = re.search(r"(\d+) 个工具", readme)
    if rm and int(rm.group(1)) != len(code_tools):
        fail(f"README 写 {rm.group(1)} 个工具，代码 {len(code_tools)} 个")
    elif rm:
        ok("README 工具数一致")
    return code_tools


# ---------- 2. device_action 动作：代码 vs INTERFACE ----------
def check_actions():
    print("[2] device_action 动作（代码 ↔ INTERFACE.md §3）")
    ex = read(os.path.join(ROOT, "AiAgentForAndroid/app/src/main/java/com/jxev/ai/client/AdbActionExecutor.java"))
    cases = set(re.findall(r'case "([a-z_]+)":', ex))
    sock = read(os.path.join(ROOT, "AiAgentForAndroid/app/src/main/java/com/jxev/ai/client/MCPSocketClient.java"))
    cap_block = re.search(r"getDeviceCapabilities\(\)\s*\{\s*return new String\[\]\{(.*?)\};", sock, re.S)
    caps = set(re.findall(r'"([a-z_]+)"', cap_block.group(1))) if cap_block else set()
    doc = read(os.path.join(DOCS, "INTERFACE.md"))
    sec3 = doc.split("## 3.")[1].split("## 4.")[0]
    doc_actions = set()
    for line in sec3.splitlines():
        if line.startswith("|"):
            doc_actions |= set(re.findall(r"`([a-z_]+)`", line))
    # 文档里显式说明 script_* 由 ScriptEngine 接管，算覆盖
    doc_actions |= {a for a in (cases | caps) if a.startswith("script_")}
    value_words = {"up", "down", "mute", "unmute", "toggle", "play", "pause",
                   "next", "prev", "previous", "stop"}
    missing = sorted((cases | caps) - doc_actions - value_words)
    if missing:
        fail(f"文档 §3 缺动作：{missing}")
    else:
        ok(f"{len(cases | caps)} 个动作文档全覆盖")
    param_words = {"action_type", "device_action", "up", "down", "mute", "unmute", "toggle",
                   "play", "pause", "next", "prev", "previous", "stop"}
    nonexistent = sorted(doc_actions - (cases | caps) - param_words)
    if nonexistent:
        fail(f"文档写了代码不存在的动作：{nonexistent}")
    else:
        ok("文档里的动作都存在于代码")


# ---------- 3. 配置键：ConfigManager vs OPERATIONS ----------
def check_config_keys():
    print("[3] 配置键（ConfigManager ↔ OPERATIONS.md §3）")
    cm = read(os.path.join(ROOT, "AiAgentForAndroid/app/src/main/java/com/jxev/ai/ConfigManager.java"))
    keys = set(re.findall(r'pref(?:String|Int|Bool|Long)\([^,]+,\s*"([a-z_]+)"', cm))
    keys |= {m.group(1) for m in re.finditer(r'get(?:String|Int|Boolean|Long)\("([a-z_]+)"', cm)}
    ops = read(os.path.join(DOCS, "OPERATIONS.md"))
    doc_keys = set(re.findall(r"^\| `([a-z_]+)` \|", ops, re.M))
    missing = sorted(keys - doc_keys)
    if missing:
        fail(f"OPERATIONS 配置表缺键：{missing}")
    else:
        ok(f"{len(keys)} 个配置键文档全覆盖")
    ghost = sorted(doc_keys - keys)
    if ghost:
        fail(f"文档里的键代码没有：{ghost}")
    else:
        ok("文档里的配置键都存在于代码")


# ---------- 4. hub schema：代码 vs 文档 ----------
def check_hub_schema():
    print("[4] 中枢表结构（hub_server.py ↔ INTERFACE.md §5）")
    hs = read(os.path.join(ROOT, "ai_mcp_server/hub_server.py"))
    tables = set(re.findall(r"CREATE TABLE IF NOT EXISTS (\w+)", hs))
    doc = read(os.path.join(DOCS, "INTERFACE.md"))
    need = {"message", "tag", "attachment"}
    if tables >= need:
        ok(f"代码建表 {sorted(tables)}")
    else:
        fail(f"代码缺表：{sorted(need - tables)}")
    for t in need:
        if f"CREATE TABLE IF NOT EXISTS {t}" in doc or f"{t}(" in doc:
            pass
    if all(w in doc for w in ("message(", "tag(", "attachment(")):
        ok("文档 §5 三张表都在")
    else:
        fail("文档 §5 表定义不全")


# ---------- 5. 文档内链接 ----------
def check_links():
    print("[5] 文档内相对链接")
    files = [os.path.join(ROOT, "README.md")] + [
        os.path.join(DOCS, f) for f in os.listdir(DOCS) if f.endswith(".md")]
    bad = []
    for f in files:
        for link in re.findall(r"\]\(([^)#]+\.md)\)", read(f)):
            target = os.path.normpath(os.path.join(os.path.dirname(f), link))
            if not os.path.exists(target):
                bad.append(f"{os.path.relpath(f, ROOT)} -> {link}")
    if bad:
        fail(f"断链：{bad}")
    else:
        ok(f"{len(files)} 份文档链接全部可达")


# ---------- 6. 线上：工具列表 / 端口 / 表 ----------
def ssh(host, cmd, timeout=25):
    return subprocess.run(
        ["ssh", "-i", SSH_KEY,
         "-o", "StrictHostKeyChecking=no", "-o", "BatchMode=yes", "-o", f"ConnectTimeout=8",
         f"root@{host}", cmd],
        capture_output=True, text=True, timeout=timeout)


def check_live(code_tools):
    print(f"[6] 线上（{BRIDGE_HOST} / {RELAY_HOST}）")
    r = ssh(BRIDGE_HOST, "cd /tmp && /opt/mcp_for_android/ai_mcp_server/.venv/bin/python - <<'EOF'\n"
                        "import asyncio, json\n"
                        "from mcp import ClientSession\n"
                        "from mcp.client.streamable_http import streamablehttp_client\n"
                        "async def m():\n"
                        "    async with streamablehttp_client('http://'+__import__('os').environ.get('MCP_BRIDGE_HOST','192.0.2.9')+':9500/mcp') as (a,b,_):\n"
                        "        async with ClientSession(a,b) as s:\n"
                        "            await s.initialize()\n"
                        "            t=await s.list_tools()\n"
                        "            print(json.dumps(sorted(x.name for x in t.tools)))\n"
                        "asyncio.run(m())\n"
                        "EOF")
    if r.returncode != 0:
        fail(f"线上 list_tools 失败：{r.stderr.strip()[:200]}")
        return
    try:
        live = json.loads(r.stdout.strip().splitlines()[-1])
    except Exception:
        fail(f"线上 list_tools 输出无法解析：{r.stdout.strip()[:200]}")
        return
    missing = sorted(set(code_tools) - set(live))
    extra = sorted(set(live) - set(code_tools))
    if missing:
        fail(f"线上缺内置工具：{missing}")
    else:
        ok(f"线上包含全部 {len(code_tools)} 个内置工具")
    if extra:
        ok(f"线上额外工具（应为 PHONE_TOOLS_JSON 外部配置）：{extra}")
    else:
        ok("线上无额外工具")

    r = ssh(BRIDGE_HOST, "ss -tln | awk '{print $4}' | grep -E ':(9444|9500|18999)$' | sort")
    ports = set(p.split(":")[-1] for p in r.stdout.split()) if r.returncode == 0 else set()
    want = {"9444", "9500", "18999"}
    (ok if ports >= want else fail)(f"{BRIDGE_HOST} 监听：{sorted(ports)}（文档要求 9444/9500/18999）")

    r = ssh(BRIDGE_HOST, "cd /opt/mcp_for_android/ai_mcp_server && python3 -c \""
                        "import sqlite3;c=sqlite3.connect('hub.db');"
                        "print(sorted(r[0] for r in c.execute(\\\"select name from sqlite_master where type='table'\\\")))\"")
    tables = r.stdout.strip() if r.returncode == 0 else ""
    (ok if all(t in tables for t in ("message", "tag", "attachment")) else fail)(
        f"线上 hub.db 表：{tables}")

    r = ssh(RELAY_HOST, "systemctl is-active phone-relay; systemctl is-enabled mcp-bridge 2>/dev/null || echo not-found")
    out = r.stdout.strip().replace("\n", " / ")
    (ok if "active" in out else fail)(f"ECS：{out}（文档要求 phone-relay active、mcp-bridge 不在）")




# ---------- 7. 常量与行为断言 ----------
def check_constants():
    print("[7] 常量/行为（代码 ↔ 文档）")
    sock = read(os.path.join(ROOT, "AiAgentForAndroid/app/src/main/java/com/jxev/ai/client/MCPSocketClient.java"))
    checks = [
        ("HEARTBEAT_INTERVAL = 30000", "心跳 30s", "心跳间隔"),
        ("HEARTBEAT_TIMEOUT_MS = 90000", "90s 无入站判半开", "半开超时"),
    ]
    for needle, label, _ in checks:
        (ok if needle in sock else fail)(f"{label}：{'代码一致' if needle in sock else '代码里没有 ' + needle}")
    svc = read(os.path.join(ROOT, "AiAgentForAndroid/app/src/main/java/com/jxev/ai/server/MCPService.java"))
    (ok if ", 10, 20," in svc else fail)("hub 上报周期 20s（首轮 10s）")
    up = read(os.path.join(ROOT, "AiAgentForAndroid/app/src/main/java/com/jxev/ai/hub/HubUploader.java"))
    (ok if "BATCH = 200" in up else fail)("每批 200 条")
    tun = read(os.path.join(ROOT, "AiAgentForAndroid/app/src/main/java/com/jxev/ai/TunnelManager.java"))
    svc_txt = read(os.path.join(ROOT, "AiAgentForAndroid/app/src/main/java/com/jxev/ai/server/MCPService.java")) + tun
    (ok if "built-in tunnel not ready" in svc_txt else fail)("fail-closed 日志行存在")
    lis = read(os.path.join(ROOT, "AiAgentForAndroid/app/src/main/java/com/jxev/ai/server/MCPListenServer.java"))
    ((ok if ("0.0.0.0" in lis and "10.0.0.0/24" in lis) else fail))("兜底监听 0.0.0.0 + 来源过滤 10.0.0.0/24")
    adb = read(os.path.join(ROOT, "AiAgentForAndroid/app/src/main/java/com/jxev/ai/client/AdbLocalClient.java"))
    order = [adb.find("execShellAdb(command)"), adb.find("execShellWifiDebug(command)"),
             adb.find("ShizukuBridge.exec(command")]
    if all(i >= 0 for i in order) and order == sorted(order):
        ok("通道顺序 5555 → 无线调试 → Shizuku 与文档一致")
    else:
        fail(f"通道顺序异常：{order}")
    req = read(os.path.join(ROOT, "ai_mcp_server/requirements.txt"))
    (ok if "mcp==1.29.0" in req else fail)("requirements.txt 钉住 mcp==1.29.0")


def check_protocol_types():
    print("[8] socket 帧类型（代码 ↔ INTERFACE.md §4）")
    iface = read(os.path.join(ROOT, "ai_mcp_server/mcp/mcp_interface.py"))
    sock = read(os.path.join(ROOT, "AiAgentForAndroid/app/src/main/java/com/jxev/ai/client/MCPSocketClient.java"))
    server_types = ["handshake_response", "welcome", "heartbeat_response", "hub_ack"]
    for t in server_types:
        (ok if t in iface else fail)(f"服务端发出 {t}")
    up_txt = read(os.path.join(ROOT, "AiAgentForAndroid/app/src/main/java/com/jxev/ai/hub/HubUploader.java"))
    client_types = {"handshake": sock, "heartbeat": sock, "hub_batch": up_txt}
    for t, src in client_types.items():
        (ok if f'"{t}"' in src else fail)(f"手机发出 {t}")
    (ok if '"request"' in iface else fail)("服务端发出 request 帧")


def main():
    print(f"ROOT={ROOT}\n")
    tools = check_tools()
    check_actions()
    check_config_keys()
    check_hub_schema()
    check_links()
    check_constants()
    check_protocol_types()
    if "--live" in sys.argv:
        check_live(tools)
    print(f"\n结果：OK {len(OKS)} / FAIL {len(FAILS)}")
    if FAILS:
        for f in FAILS:
            print("  - " + f)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
