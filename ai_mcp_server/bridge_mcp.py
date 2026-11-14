"""
Phone MCP Bridge — 把手机能力通过标准 MCP 协议暴露给外部 MCP 客户端（如 Hermes）。

进程模型：
    MCP 客户端（Hermes）
        ↕ stdio JSON-RPC（FastMCP）
    bridge_mcp.py（本进程，主线程跑 mcp.run）
        ├─ 后台线程：MCPServer TCP/TLS 监听（默认 127.0.0.1:9444，env MCP_HOST 可覆盖）
        │  （复用 mcp.mcp_interface.MCPServer）
        ↕ TLS（设备主动出站）
    手机 App（com.jxev.ai，MCPSocketClient）

设计要点：
- 复用 `mcp.mcp_interface.MCPServer`/`DeviceConnection` 全部链路（TLS 握手、token 校验、
  pending_requests 派发），零改动本地包；不新建 socket 协议、不解析自然语言。
- 每个 MCP tool handler 把"动作 → Request → 同步等回调"包装成同步函数；
  DeviceConnection.send_request 自带 60s 超时回调，因此总耗时上限可控。
- 无设备在线时直接返回结构化错误，便于客户端识别后让用户先连手机。

参数键名约定与 Android 端 `MCPAccessibilityService.executeAction` switch 一一对齐：
    click/long_click : selector | id | text | desc | x+y
    type_text        : text + selector | text + id
    swipe            : x1, y1, x2, y2 (+ 可选 duration)
    scroll           : down: bool   ← bridge 接受 direction 字符串后翻译
    launch_app       : packageName
    get_ui_state / get_installed_apps : 无 params
"""
from __future__ import annotations

import asyncio
import base64
import http.server
import importlib
import importlib.util
import inspect
import json
import logging
import os
import re
import ssl
import string
import sys
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
from contextlib import asynccontextmanager, redirect_stdout
from datetime import datetime
from typing import Annotated, Any, Callable, Optional

from pydantic import Field

_SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))


def _import_fastmcp_sdk():
    """隔离本地 regular package 后加载 site-packages 的 FastMCP SDK。"""
    script_dir_normalized = os.path.normcase(os.path.abspath(_SCRIPT_DIR))
    original_mcp_modules = {
        name: module
        for name, module in sys.modules.items()
        if name == "mcp" or name.startswith("mcp.")
    }
    local_path_indexes = [
        index
        for index, path in enumerate(sys.path)
        if (
            os.path.normcase(os.path.abspath(path or os.getcwd())) == script_dir_normalized
            and os.path.isfile(os.path.join(path or os.getcwd(), "mcp", "__init__.py"))
        )
    ]
    removed_paths = [sys.path.pop(index) for index in reversed(local_path_indexes)]
    for module_name in list(sys.modules):
        if module_name == "mcp" or module_name.startswith("mcp."):
            del sys.modules[module_name]
    try:
        from mcp.server.fastmcp import FastMCP as ImportedFastMCP  # noqa: E402
        from mcp.types import ImageContent as ImportedImageContent  # noqa: E402
    except Exception:
        for index, path in reversed(list(zip(local_path_indexes, removed_paths))):
            sys.path.insert(index, path)
        sys.modules.update(original_mcp_modules)
        raise
    else:
        for index, path in reversed(list(zip(local_path_indexes, removed_paths))):
            sys.path.insert(index, path)
    return ImportedFastMCP, ImportedImageContent


def _load_local_mcp_modules():
    """加载项目自己的 mcp 包，避免与 site-packages SDK 互相遮蔽。"""
    sdk_mcp_modules = {
        name: module
        for name, module in sys.modules.items()
        if name == "mcp" or name.startswith("mcp.")
    }
    for module_name in list(sys.modules):
        if module_name == "mcp" or module_name.startswith("mcp."):
            del sys.modules[module_name]

    package_name = "_phone_mcp_local"
    package_path = os.path.join(_SCRIPT_DIR, "mcp")
    package_spec = importlib.util.spec_from_file_location(
        package_name,
        os.path.join(package_path, "__init__.py"),
        submodule_search_locations=[package_path],
    )
    if package_spec is None or package_spec.loader is None:
        sys.modules.update(sdk_mcp_modules)
        raise ImportError(f"无法加载本地 MCP 包: {package_path}")
    package_module = importlib.util.module_from_spec(package_spec)
    sys.modules[package_name] = package_module
    try:
        package_spec.loader.exec_module(package_module)
        interface_module = importlib.import_module(f"{package_name}.mcp_interface")
        protocol_module = importlib.import_module(f"{package_name}.mcp_protocol")
    except Exception:
        for module_name in list(sys.modules):
            if (
                (module_name == "mcp" or module_name.startswith("mcp."))
                and module_name not in sdk_mcp_modules
            ):
                del sys.modules[module_name]
        raise
    finally:
        sys.modules.update(sdk_mcp_modules)
    return (
        interface_module.MCPServer,
        protocol_module.Context,
        protocol_module.MCPActionTypes,
        protocol_module.Request,
    )


FastMCP, ImageContent = _import_fastmcp_sdk()

# 把脚本所在目录（ai_mcp_server/）放进 sys.path[0]，确保 `import mcp.mcp_interface`
# 解析到本地 regular package（ai_mcp_server/mcp/）而非 site-packages 的 mcp SDK。
if _SCRIPT_DIR not in sys.path:
    sys.path.insert(0, _SCRIPT_DIR)

# 本地包导入（必须在 sys.path 处理之后）
MCPServer, Context, MCPActionTypes, Request = _load_local_mcp_modules()

# -----------------------------------------------------------------------------
# 日志：全部走 stderr，避免污染 stdio JSON-RPC 流（stdout）
# -----------------------------------------------------------------------------
_handler = logging.StreamHandler(sys.stderr)
_handler.setFormatter(
    logging.Formatter("%(asctime)s [bridge] %(levelname)s %(name)s: %(message)s")
)
root_logger = logging.getLogger()
# 先清空可能存在的 handler（避免 RichHandler 等把日志写到 stdout）
root_logger.handlers.clear()
root_logger.addHandler(_handler)
root_logger.setLevel(logging.INFO)
logger = logging.getLogger("phone_bridge")


# -----------------------------------------------------------------------------
# 配置（全部可用 env 覆盖；默认值即本仓库的推荐部署形态）
# -----------------------------------------------------------------------------
HOST = os.environ.get("MCP_HOST", "127.0.0.1")
PORT = int(os.environ.get("PHONE_BRIDGE_PORT", os.environ.get("MCP_TCP_PORT", "9444")))
CERT_FILE = os.environ.get("MCP_CERT_FILE", "certs/server.crt")
KEY_FILE = os.environ.get("MCP_KEY_FILE", "certs/server.key")
DEVICE_TOKEN = os.environ.get("MCP_DEVICE_TOKEN", "")  # 空 = 不校验
REQUEST_TIMEOUT_SEC = float(os.environ.get("PHONE_BRIDGE_TIMEOUT", "60"))

# wg 直连拨号模式（2026-09-29）：设 PHONE_DIAL_HOST 后，bridge 主动拨入手机
# MCPListenServer（手机=TLS 服务端），不依赖手机出站连接 —— 任意 wg peer 均可
# 用此模式直连手机，不必绑定某台固定服务器。留空则保持原 accept 模式。
PHONE_DIAL_HOST = os.environ.get("PHONE_DIAL_HOST", "").strip()
PHONE_DIAL_PORT = int(os.environ.get("PHONE_DIAL_PORT", "9444"))
PHONE_CA_FILE = os.environ.get("PHONE_CA_FILE", "certs/ca.crt")

_SCREENSHOT_ACTION = "screenshot"
_GET_NOTIFICATIONS_ACTION = "get_notifications"
_DISMISS_NOTIFICATION_ACTION = "dismiss_notification"
_ADB_SHELL_ACTION = "adb_shell"


def _resolve(p: str) -> str:
    return p if os.path.isabs(p) else os.path.join(_SCRIPT_DIR, p)


# -----------------------------------------------------------------------------
# MCPServer 后台实例（手机 TLS 连进来就注册到 self.devices）
# -----------------------------------------------------------------------------
ssl_ctx: Optional[ssl.SSLContext] = None
cert_path = _resolve(CERT_FILE)
key_path = _resolve(KEY_FILE)
if os.path.isfile(cert_path) and os.path.isfile(key_path):
    ssl_ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    ssl_ctx.load_cert_chain(certfile=cert_path, keyfile=key_path)
    logger.info("TLS 已启用 cert=%s key=%s", cert_path, key_path)
else:
    logger.warning(
        "TLS 证书缺失（cert=%s, key=%s），bridge 9444 将以明文启动（仅本地调试用）。"
        "手机端公网部署必须 TLS。",
        cert_path, key_path,
    )

mcp_server = MCPServer(
    host=HOST,
    port=PORT,
    mcp_context=None,
    ssl_context=ssl_ctx,
    device_token=DEVICE_TOKEN,
)
if PHONE_DIAL_HOST:
    logger.info(
        "直连拨号模式（PHONE_DIAL_HOST=%s）：accept 监听 %s:%d 不启动",
        PHONE_DIAL_HOST, HOST, PORT,
    )
else:
    mcp_server.start()
logger.info("MCPServer %s，token=%s",
            f"监听 {HOST}:{PORT}" if not PHONE_DIAL_HOST
            else f"待机（accept 关闭，拨号 {PHONE_DIAL_HOST}:{PHONE_DIAL_PORT}）",
            "启用" if DEVICE_TOKEN else "禁用（空）")


# -----------------------------------------------------------------------------
# FastMCP server（对外暴露的 MCP 接口，支持 stdio / streamable-http 两种传输）
# host/port 仅 streamable-http 模式下生效；stdio 模式下 host/port 参数被忽略
# -----------------------------------------------------------------------------
_BRIDGE_TRANSPORT = os.environ.get("BRIDGE_TRANSPORT", "stdio").strip().lower()
_BRIDGE_HOST = os.environ.get("BRIDGE_HOST", "127.0.0.1").strip() or "127.0.0.1"
try:
    _BRIDGE_PORT = int(os.environ.get("BRIDGE_PORT", "9500"))
except ValueError:
    logger.warning("BRIDGE_PORT 非法，回退 9500")
    _BRIDGE_PORT = 9500


# -----------------------------------------------------------------------------
# TTS（SiliconFlow）——实现拆到独立文件 tts_service.py（不上传 GitHub）
# 本地/ECS 保留该文件即恢复 TTS；缺失时 tts_speak / speak step 返回"TTS 未安装"
# -----------------------------------------------------------------------------
_TTS_ACTION = "tts_play"
try:
    from tts_service import tts_generate, TTS_DEFAULT_VOICE  # noqa: E402

    _TTS_AVAILABLE = True
    logger.info("TTS 服务已加载（tts_service.py）")
except ImportError:
    _TTS_AVAILABLE = False

    def tts_generate(text: str, voice: str) -> bytes:  # type: ignore[misc]
        raise RuntimeError("TTS 未安装（缺少 ai_mcp_server/tts_service.py）")

    TTS_DEFAULT_VOICE = "FunAudioLLM/CosyVoice2-0.5B:alex"  # type: ignore[assignment]
    logger.warning("TTS 未安装：缺少 tts_service.py，tts_speak / speak step 将返回错误")

# 手机是脚本库权威（2026-08-14 起）：脚本存手机、手机 AlarmManager 调度、手机执行。
# Bridge 不再本地存储/执行脚本，只做 script_* 动作转发 + pc_notify 入队。
_SCRIPT_TIME_RE = re.compile(r"^([01]\d|2[0-3]):[0-5]\d$")


# -----------------------------------------------------------------------------
# 脚本调度已下沉手机（AlarmManager），Bridge 不再跑 ECS 本地调度器。
# -----------------------------------------------------------------------------


@asynccontextmanager
async def _bridge_lifespan(_app: Any):
    """FastMCP lifespan：脚本调度已下沉手机（AlarmManager），Bridge 无本地常驻任务
    （18999 通知端点由 main 入口独立启动，不随会话开关）。"""
    yield {}


mcp = FastMCP(
    "phone-controller",
    host=_BRIDGE_HOST,
    port=_BRIDGE_PORT,
    lifespan=_bridge_lifespan,
)


# -----------------------------------------------------------------------------
# 脚本存储 / TTS / 脚本执行实现（tools 与调度器共用）
# -----------------------------------------------------------------------------
_PC_NOTIFICATIONS: list[dict] = []
_PC_NOTIFICATIONS_MAX = 100
_NOTIFY_LOCK = threading.Lock()
_NOTIFY_PORT = 18999
# 18999 通知端点监听地址（默认仅 WireGuard 内网，env PC_NOTIFY_HOST 可覆盖）
_NOTIFY_HOST = os.environ.get("PC_NOTIFY_HOST", "127.0.0.1")
_NOTIFY_TOKEN = os.environ.get("NOTIFY_TOKEN", "").strip() or DEVICE_TOKEN
_notify_httpd: Optional[http.server.ThreadingHTTPServer] = None


def _validate_script_steps(steps: Any) -> Optional[str]:
    """校验 steps：非空数组，每项必须含 shell/speak/notify_pc 之一且类型正确。"""
    if not isinstance(steps, list) or not steps:
        return "steps 必须是非空数组"
    for index, step in enumerate(steps, start=1):
        if not isinstance(step, dict):
            return f"第 {index} 步必须是 JSON object"
        has_shell = isinstance(step.get("shell"), str)
        has_speak = isinstance(step.get("speak"), str)
        has_notify = isinstance(step.get("notify_pc"), dict)
        if not (has_shell or has_speak or has_notify):
            return (
                f"第 {index} 步必须包含 shell/speak/notify_pc 之一"
                "（shell/speak 为字符串，notify_pc 为 object）"
            )
    return None


class _PendingNotificationsHandler(http.server.BaseHTTPRequestHandler):
    """GET /pending_notifications?token=<NOTIFY_TOKEN> → 取走并清空通知队列。"""

    def do_GET(self) -> None:  # noqa: N802
        parsed = urllib.parse.urlparse(self.path)
        if parsed.path != "/pending_notifications":
            self._send_json(404, {"error": "not found"})
            return
        if not _NOTIFY_TOKEN:
            self._send_json(503, {"error": "notify endpoint disabled (NOTIFY_TOKEN 为空)"})
            return
        query = urllib.parse.parse_qs(parsed.query)
        token = (query.get("token") or [""])[0]
        if token != _NOTIFY_TOKEN:
            self._send_json(401, {"error": "unauthorized"})
            return
        with _NOTIFY_LOCK:
            notifications = list(_PC_NOTIFICATIONS)
            _PC_NOTIFICATIONS.clear()
        self._send_json(200, {"notifications": notifications})

    def _send_json(self, code: int, payload: dict) -> None:
        body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        try:
            self.wfile.write(body)
        except OSError:
            pass

    def log_message(self, fmt: str, *args: Any) -> None:
        logger.info("notify_http: %s", fmt % args)


def _start_notify_httpd() -> None:
    """独立线程启动 18999 通知轮询端点（token 为空时端点仍启动但返回 503）。"""
    global _notify_httpd
    if _notify_httpd is not None:
        return
    try:
        httpd = http.server.ThreadingHTTPServer(
            (_NOTIFY_HOST, _NOTIFY_PORT), _PendingNotificationsHandler
        )
    except OSError as exc:
        logger.warning("PC 通知端点 18999 启动失败（%s），通知功能不可用", exc)
        return
    thread = threading.Thread(target=httpd.serve_forever, name="notify-httpd", daemon=True)
    _notify_httpd = httpd
    thread.start()
    logger.info("PC 通知端点已启动 http://%s:%d/pending_notifications", _NOTIFY_HOST, _NOTIFY_PORT)


# -----------------------------------------------------------------------------
# 直连拨号客户端（PHONE_DIAL_HOST 模式）：主动拨入手机 MCPListenServer
# -----------------------------------------------------------------------------
_dial_client = None


def _handle_dial_phone_request(device_id: str, message: dict) -> None:
    """直连通道上手机发来的 request（目前只有脚本 pc_notify）→ 入队 + 回执。"""
    if not isinstance(message, dict) or message.get("actionType") != "pc_notify":
        logger.warning("直连通道收到不支持的手机请求: %s", message.get("actionType"))
        return
    request_id = message.get("requestId")
    params = message.get("parameters") or {}
    title = str(params.get("title") or "手机脚本提醒")
    text = str(params.get("text") or "")
    ts = datetime.now().strftime("%Y-%m-%d %H:%M:%S")
    with _NOTIFY_LOCK:
        _PC_NOTIFICATIONS.append({"title": title, "text": text, "ts": ts})
        if len(_PC_NOTIFICATIONS) > _PC_NOTIFICATIONS_MAX:
            del _PC_NOTIFICATIONS[: len(_PC_NOTIFICATIONS) - _PC_NOTIFICATIONS_MAX]
    logger.info("收到手机 pc_notify（dial, device=%s, title=%s）", device_id, title)
    response = {
        "type": "response",
        "requestId": request_id,
        "data": {"status": "success", "queued": True, "message": "pc_notify 已入队"},
        "timestamp": time.time(),
    }
    if _dial_client is not None:
        try:
            _dial_client.send_json(response)
        except OSError:
            logger.exception("pc_notify 回执发送失败")


if PHONE_DIAL_HOST:
    from phone_direct_client import PhoneDirectClient  # noqa: E402

    _dial_client = PhoneDirectClient(
        host=PHONE_DIAL_HOST,
        port=PHONE_DIAL_PORT,
        ca_file=_resolve(PHONE_CA_FILE),
        token=DEVICE_TOKEN,
        device_id=os.environ.get("PHONE_DIAL_DEVICE_ID", "wg-dial"),
        request_timeout=REQUEST_TIMEOUT_SEC,
        on_phone_request=_handle_dial_phone_request,
    )
    _dial_client.start()
    logger.info(
        "手机直连客户端已启动: %s:%d (CA=%s)", PHONE_DIAL_HOST, PHONE_DIAL_PORT, PHONE_CA_FILE
    )


def _pick_device():
    """直连模式返回拨号客户端；否则从 mcp_server.devices 选第一个在线设备。"""
    if PHONE_DIAL_HOST:
        if _dial_client is not None and _dial_client.connected:
            return _dial_client
        raise RuntimeError(
            f"手机直连未建立（{PHONE_DIAL_HOST}:{PHONE_DIAL_PORT}）。"
            "请确认手机 App 前台服务在运行、WireGuard 隧道可达，"
            "且手机 MCPListenServer 已监听。"
        )
    with mcp_server.devices_lock:
        for dev in mcp_server.devices.values():
            if dev.connected and dev.socket:
                return dev
    raise RuntimeError(
        "手机未连接（9444 端口无在线设备）。"
        "请确认手机 App 已配置 server_host=本机IP, server_port=9444 并保持 TLS 链路。"
    )


def _send_action(action_type: str, params: Optional[dict] = None, timeout: float = REQUEST_TIMEOUT_SEC) -> dict:
    """发动作给手机并同步等响应。返回结构化 dict。

    返回字段：
        status            : "success" | "error" | "timeout" | "no_device"
        action_type       : 原始动作
        params            : 透传给手机的参数
        request_id        : 本次请求 ID（手机回执用同 ID）
        error             : 失败时的错误描述
        data              : 手机返回 data 字段全量（status/success/message/deviceState 等）
        ui_state          : 若 data.message 是 JSON 字符串且可解析，则放这里（仅 GET_UI_STATE）
        installed_apps    : 若 data.message 是 JSON 数组字符串且可解析，则放这里（仅 GET_INSTALLED_APPS）
    """
    params = params or {}
    request_id = str(uuid.uuid4())
    request = Request(
        request_id=request_id,
        action_type=action_type,
        parameters=params,
        context=Context(session_id="bridge"),
    )

    try:
        device = _pick_device()
    except RuntimeError as e:
        return {
            "status": "no_device",
            "action_type": action_type,
            "params": params,
            "error": str(e),
        }

    result_box: dict = {}
    event = threading.Event()

    def _cb(response):
        result_box["response"] = response
        event.set()

    device.send_request(request, _cb)

    if not event.wait(timeout=timeout):
        return {
            "status": "timeout",
            "action_type": action_type,
            "params": params,
            "request_id": request_id,
            "error": f"动作 {action_type} 在 {timeout}s 内未收到手机回执",
        }

    response = result_box["response"]
    data = dict(response.data) if response.data else {}

    # 把 data.message 这种"JSON 字符串载荷"预先解析出来（GET_UI_STATE/GET_INSTALLED_APPS）
    msg = data.get("message")
    extra: dict = {}
    if isinstance(msg, str) and msg:
        try:
            parsed = json.loads(msg)
            extra["message_parsed"] = parsed
            if isinstance(parsed, dict):
                extra["ui_state"] = parsed
            elif isinstance(parsed, list):
                extra["installed_apps"] = parsed
        except json.JSONDecodeError:
            pass

    return {
        "status": response.status or data.get("status", "unknown"),
        "action_type": action_type,
        "params": params,
        "request_id": request_id,
        "data": data,
        "error": response.error,
        **extra,
    }


def _result_data(result: dict) -> dict:
    """提取手机端 response.data，同时容忍没有 data 的失败结果。"""
    data = result.get("data")
    return data if isinstance(data, dict) else {}


def _result_succeeded(result: dict) -> bool:
    """判断手机动作是否成功，兼容 status=success + data.success=false。"""
    if result.get("status") != "success":
        return False
    return _result_data(result).get("success", True) is not False


def _result_error(result: dict) -> str:
    """返回手机端错误或 Bridge 的可读失败信息。"""
    data = _result_data(result)
    error = data.get("error") or result.get("error")
    if error:
        return str(error)
    action_type = result.get("action_type", "动作")
    status = result.get("status", "unknown")
    return f"{action_type} 执行失败（status={status}）"


def _handle_pc_notify_request(device_id: str, message: dict) -> None:
    """手机脚本 notify_pc 步骤 → Bridge 写 _PC_NOTIFICATIONS 队列 + 回执手机。"""
    request_id = message.get("requestId")
    params = message.get("parameters") or {}
    title = str(params.get("title") or "手机脚本提醒")
    text = str(params.get("text") or "")
    ts = datetime.now().strftime("%Y-%m-%d %H:%M:%S")
    with _NOTIFY_LOCK:
        _PC_NOTIFICATIONS.append({"title": title, "text": text, "ts": ts})
        if len(_PC_NOTIFICATIONS) > _PC_NOTIFICATIONS_MAX:
            del _PC_NOTIFICATIONS[: len(_PC_NOTIFICATIONS) - _PC_NOTIFICATIONS_MAX]
    logger.info("收到手机 pc_notify（device=%s, title=%s）", device_id, title)

    response = {
        "type": "response",
        "requestId": request_id,
        "data": {"status": "success", "queued": True, "message": "pc_notify 已入队"},
        "timestamp": time.time(),
    }
    with mcp_server.devices_lock:
        dev = mcp_server.devices.get(device_id)
        if dev is not None and dev.socket:
            try:
                dev.socket.sendall((json.dumps(response) + "\n").encode("utf-8"))
            except OSError:
                logger.exception("pc_notify 回执发送失败")


def _handle_device_message(device_id: str, message: dict) -> None:
    """拦截手机发来的 pc_notify 请求；其余消息走原 MCPServer 处理（协议层零改动）。"""
    if isinstance(message, dict) and message.get("type") == "request" \
            and message.get("actionType") == "pc_notify":
        _handle_pc_notify_request(device_id, message)
        return
    _ORIGINAL_HANDLE_CLIENT_MESSAGE(device_id, message)


# 把实例方法替换为包装版（不改动 mcp/mcp_interface.py 协议层）
_ORIGINAL_HANDLE_CLIENT_MESSAGE = mcp_server._handle_client_message
mcp_server._handle_client_message = _handle_device_message


# -----------------------------------------------------------------------------
# MCP Tools —— 每个 tool handler 把 MCP 参数映射到手机 params
# -----------------------------------------------------------------------------

@mcp.tool()
def launch_app(package_name: str) -> dict:
    """通过包名启动 Android 应用，例如 com.android.settings / com.jxev.ai。"""
    return _send_action(MCPActionTypes.LAUNCH_APP, {"packageName": package_name})


@mcp.tool()
def device_action(action_type: str, parameters: dict = {}) -> dict:
    """通用手机动作执行器（全量覆盖 40+ 动作，全部 adb 实现无障碍依赖）。

    常用 action_type（parameters 为对应参数 object）：
      get_screen_size {}；open_url {url}；key_event {code}；
      volume {action: up/down/mute/unmute, times}；media_control {action: play/pause/toggle/next/prev/stop}；
      wifi {enable}；bluetooth {enable}；airplane_mode {enable}；brightness {level: 0-255}；
      get_current_app {}；battery_info {}；clear_app_data {packageName}；uninstall_app {packageName}；
      install_app {apkPath}；file_read {path}；file_write {path, content}；
      wait_for_element {text/id/desc/selector, timeout}；set_rotation {lock, orientation}；
      open_app_settings {packageName}；
      以及原有全部：click/long_click {selector|x,y}、type_text {text,selector}、swipe {x1,y1,x2,y2,duration}、
      scroll {down}、press_back/press_home/press_recents {}、get_ui_state {}、get_installed_apps {}、
      screenshot {}、get_notifications {}、dismiss_notification {key}、adb_shell {command}、
      script_run {name}、script_list {} 等。
    """
    return _send_action(action_type, parameters)


@mcp.tool()
def open_url(url: str) -> dict:
    """在手机上打开 URL / 深度链接（VIEW intent）。"""
    return _send_action("open_url", {"url": url})


@mcp.tool()
def media_control(action: str) -> dict:
    """媒体播放控制：play / pause / toggle / next / prev / stop（对系统级播放器生效）。"""
    return _send_action("media_control", {"action": action})


@mcp.tool()
def volume_control(action: str, times: int = 1) -> dict:
    """音量控制：up / down / mute / unmute，可指定次数。"""
    return _send_action("volume", {"action": action, "times": times})


@mcp.tool()
def battery_info() -> dict:
    """读取手机电量/充电状态/温度。"""
    return _send_action("battery_info", {})


@mcp.tool()
def get_current_app() -> dict:
    """读取手机当前前台应用（package + activity）。"""
    return _send_action("get_current_app", {})


@mcp.tool()
def wifi_toggle(enable: bool) -> dict:
    """开关手机 Wi-Fi（svc wifi）。注意灭屏+纯数据网络场景下启用有延迟。"""
    return _send_action("wifi", {"enable": enable})


@mcp.tool()
def brightness(level: int) -> dict:
    """设置屏幕亮度 0-255（自动先关闭自动亮度）。"""
    return _send_action("brightness", {"level": level})


@mcp.tool()
def clear_app_data(package_name: str) -> dict:
    """清除指定应用全部数据（pm clear，慎用：等同重置该应用）。"""
    return _send_action("clear_app_data", {"packageName": package_name})


@mcp.tool()
def click(
    selector: Optional[str] = None,
    id: Optional[str] = None,
    text: Optional[str] = None,
    desc: Optional[str] = None,
    x: Optional[int] = None,
    y: Optional[int] = None,
) -> dict:
    """点击元素。优先 selector（id=xxx/text=xxx/class=xxx/desc=xxx 或 JSON），也可直接传 id/text/desc 或屏幕坐标 (x, y)。"""
    params: dict = {}
    if selector is not None:
        params["selector"] = selector
    elif id is not None:
        params["id"] = id
    elif text is not None:
        params["text"] = text
    elif desc is not None:
        params["desc"] = desc
    elif x is not None and y is not None:
        params["x"] = int(x)
        params["y"] = int(y)
    else:
        return {
            "status": "error",
            "error": "click: 必须提供 selector / id / text / desc / (x,y) 之一",
        }
    return _send_action(MCPActionTypes.CLICK, params)


@mcp.tool()
def long_click(
    selector: Optional[str] = None,
    id: Optional[str] = None,
    text: Optional[str] = None,
    desc: Optional[str] = None,
    x: Optional[int] = None,
    y: Optional[int] = None,
) -> dict:
    """长按元素。参数语义同 click。"""
    params: dict = {}
    if selector is not None:
        params["selector"] = selector
    elif id is not None:
        params["id"] = id
    elif text is not None:
        params["text"] = text
    elif desc is not None:
        params["desc"] = desc
    elif x is not None and y is not None:
        params["x"] = int(x)
        params["y"] = int(y)
    else:
        return {
            "status": "error",
            "error": "long_click: 必须提供 selector / id / text / desc / (x,y) 之一",
        }
    return _send_action(MCPActionTypes.LONG_CLICK, params)


@mcp.tool()
def type_text(text: str, selector: Optional[str] = None, id: Optional[str] = None) -> dict:
    """向输入框写入文本。需要先定位输入框（selector 或 id），再传 text。"""
    if not text:
        return {"status": "error", "error": "type_text: text 不能为空"}
    params: dict = {"text": text}
    if selector is not None:
        params["selector"] = selector
    elif id is not None:
        params["id"] = id
    else:
        return {"status": "error", "error": "type_text: 必须提供 selector 或 id 定位输入框"}
    return _send_action(MCPActionTypes.TYPE_TEXT, params)


@mcp.tool()
def swipe(x1: int, y1: int, x2: int, y2: int, duration: int = 300) -> dict:
    """从 (x1,y1) 滑到 (x2,y2)，duration 默认 300ms。"""
    return _send_action(
        MCPActionTypes.SWIPE,
        {
            "x1": int(x1), "y1": int(y1),
            "x2": int(x2), "y2": int(y2),
            "duration": int(duration),
        },
    )


@mcp.tool()
def scroll(direction: str = "down") -> dict:
    """滚动页面。direction: 'down' | 'up'（其它取值视为 down）。"""
    direction = (direction or "down").lower()
    is_down = direction in ("down", "d", "forward", "f")
    return _send_action(MCPActionTypes.SCROLL, {"down": is_down})


@mcp.tool()
def press_back() -> dict:
    """按返回键。"""
    return _send_action(MCPActionTypes.PRESS_BACK, {})


@mcp.tool()
def press_home() -> dict:
    """按 Home 键。"""
    return _send_action(MCPActionTypes.PRESS_HOME, {})


@mcp.tool()
def press_recents() -> dict:
    """打开最近任务。"""
    return _send_action(MCPActionTypes.PRESS_RECENTS, {})


@mcp.tool()
def get_ui_state() -> dict:
    """抓取手机当前界面（currentPackage/Activity/UI 层级/可见文本/设备信息），返回结构化 dict。

    手机端会把 device_state JSON 字符串塞进 data.message；bridge 已自动 json.loads，
    直接看返回里的 ui_state 字段即可。
    """
    return _send_action(MCPActionTypes.GET_UI_STATE, {})


@mcp.tool()
def get_installed_apps() -> dict:
    """列出手机已安装应用（带启动入口），返回结构化 list。元素含 packageName / appName。

    手机端把 apps 列表 JSON 数组塞进 data.message；bridge 已自动 json.loads，
    直接看返回里的 installed_apps 字段即可。
    """
    return _send_action(MCPActionTypes.GET_INSTALLED_APPS, {})


@mcp.tool(structured_output=False)
def screenshot():
    """截取手机当前屏幕；成功时返回 PNG 图片，失败时返回文本错误。"""
    result = _send_action(_SCREENSHOT_ACTION, {})
    data = _result_data(result)
    if _result_succeeded(result):
        image_data = data.get("base64")
        if isinstance(image_data, str) and image_data:
            return ImageContent(type="image", data=image_data, mimeType="image/png")
    error = _result_error(result)
    if result.get("status") == "no_device":
        error += "；请先通过 USB 开启 tcpip（adb tcpip 5555）后重试。"
    return f"截图失败：{error}"


@mcp.tool()
def get_notifications() -> dict:
    """读取手机当前通知列表。"""
    result = _send_action(_GET_NOTIFICATIONS_ACTION, {})
    data = _result_data(result)
    if _result_succeeded(result):
        notifications = data.get("notifications", [])
        if not isinstance(notifications, list):
            notifications = []
        return {"success": True, "notifications": notifications}
    return {
        "success": False,
        "notifications": [],
        "error": _result_error(result),
        "status": result.get("status"),
    }


@mcp.tool()
def dismiss_notification(key: str) -> dict:
    """清除指定 key 的手机通知。"""
    if not isinstance(key, str) or not key.strip():
        return {"success": False, "message": "dismiss_notification: key 不能为空"}
    result = _send_action(_DISMISS_NOTIFICATION_ACTION, {"key": key})
    if _result_succeeded(result):
        return {
            "success": True,
            "message": _result_data(result).get("message", "通知已清除"),
        }
    return {
        "success": False,
        "message": _result_error(result),
        "status": result.get("status"),
    }


@mcp.tool()
def hub_query(since_hours: float = 24, pkg: str = "", source: str = "", tag: str = "",
              kind: str = "", limit: int = 50) -> dict:
    """消息中枢：查消息（手机通知只是其中一种）。

    since_hours=回看小时数；source=phone/wechat/qq/note/...；tag=按标签过滤；
    kind=notify/chat/report/event/...；pkg 可按手机包名过滤（QQ=com.tencent.mobileqq）。
    """
    try:
        import hub_server
        rows = hub_server.query(since_hours=since_hours, source=source, tag=tag,
                                kind=kind, pkg=pkg, limit=limit)
        return {"success": True, "source_store": "server", "count": len(rows),
                "messages": json.dumps(rows, ensure_ascii=False)}
    except Exception as e:
        logger.error(f"hub_query(server) 失败: {e}")
    params = {"since": int(__import__("time").time() * 1000 - since_hours * 3600 * 1000),
              "limit": limit}
    if pkg:
        params["pkg"] = pkg
    result = _send_action("hub_query", params, timeout=20)
    data = _result_data(result)
    if _result_succeeded(result):
        return {"success": True, "count": data.get("count", 0), "messages": data.get("messages", "[]")}
    return {"success": False, "error": _result_error(result), "status": result.get("status")}


@mcp.tool()
def hub_put(text: str, title: str = "", tags: str = "", source: str = "note",
            kind: str = "note", ts_ms: int = 0, attachment_b64: str = "",
            attachment_name: str = "", attachment_mime: str = "") -> dict:
    """消息中枢：写一条消息（任意来源），可带标签与一个附件（base64）。

    tags 用逗号分隔；source 默认 note（可用 agent / wechat / qq / behavior ...）。
    """
    try:
        import hub_server
        tag_list = [t.strip() for t in tags.split(",") if t.strip()]
        item = {"text": text, "title": title or None, "tags": tag_list,
                "kind": kind, "ts": ts_ms or None}
        if attachment_b64:
            item["attachments"] = [{"name": attachment_name or "file",
                                    "mime": attachment_mime or "application/octet-stream",
                                    "data_b64": attachment_b64}]
        mid = hub_server.put(item, source=source)
        return {"success": True, "id": mid}
    except Exception as e:
        logger.error(f"hub_put 失败: {e}")
        return {"success": False, "error": str(e)}


@mcp.tool()
def hub_stats() -> dict:
    """消息中枢：总量、按来源/标签统计、附件数。"""
    try:
        import hub_server
        st = hub_server.stats()
        return {"success": True, "source_store": "server", "total": st["total"],
                "sources": json.dumps(st["sources"], ensure_ascii=False),
                "tags": json.dumps(st["tags"], ensure_ascii=False),
                "attachments": json.dumps(st["attachments"], ensure_ascii=False)}
    except Exception as e:
        logger.error(f"hub_stats(server) 失败: {e}")
        return {"success": False, "error": str(e)}


@mcp.tool()
def adb_shell(command: str) -> dict:
    """通过手机 App 在本机 adbd 上执行 shell 命令。"""
    if not isinstance(command, str) or not command.strip():
        return {"output": "", "exitCode": None, "error": "adb_shell: command 不能为空"}
    result = _send_action(_ADB_SHELL_ACTION, {"command": command})
    data = _result_data(result)
    if _result_succeeded(result):
        exit_code = data.get("exitCode")
        if exit_code is None:
            exit_code = data.get("exit_code", 0)
        return {"output": data.get("output", ""), "exitCode": exit_code}
    error = _result_error(result)
    if result.get("status") == "no_device":
        error += "；请先通过 USB 开启 tcpip（adb tcpip 5555）后重试。"
    return {
        "output": data.get("output", ""),
        "exitCode": data.get("exitCode", data.get("exit_code")),
        "error": error,
    }


@mcp.tool()
def tts_speak(text: str, voice: str = TTS_DEFAULT_VOICE) -> dict:
    """让手机说话：调 SiliconFlow TTS 生成语音并下发手机播放（tts_play）。"""
    if not isinstance(text, str) or not text.strip():
        return {"status": "error", "error": "tts_speak: text 不能为空"}
    try:
        wav_bytes = tts_generate(text.strip(), voice)
    except RuntimeError as exc:
        return {"status": "error", "error": str(exc)}
    audio_base64 = base64.b64encode(wav_bytes).decode("ascii")
    result = _send_action(
        _TTS_ACTION,
        {"audio_base64": audio_base64, "format": "wav"},
    )
    summary: dict = {
        "status": result.get("status"),
        "text": text.strip(),
        "voice": voice,
        "audio_bytes": len(wav_bytes),
        "audio_base64_len": len(audio_base64),
    }
    if _result_succeeded(result):
        data = _result_data(result)
        summary["message"] = data.get("message") or "手机已开始播放 TTS 音频"
        summary["success"] = True
    else:
        summary["success"] = False
        summary["error"] = _result_error(result)
    return summary


# -----------------------------------------------------------------------------
# 脚本宿主 tools（2026-08-14 起纯转发：脚本库以手机为准，手机 AlarmManager 调度）
# -----------------------------------------------------------------------------

@mcp.tool()
def script_list() -> dict:
    """列出手机端全部脚本（数据以手机为准，含 schedule/step 摘要/lastRun）。"""
    return _send_action("script_list", {})


@mcp.tool()
def script_create(
    name: str,
    description: str = "",
    steps: Optional[list] = None,
    schedule: Optional[dict] = None,
) -> dict:
    """在手机端新建脚本（脚本库以手机为准）。steps 每项为 {"shell": "..."} 或 {"speak": "..."} 或 {"notify_pc": {...}}。"""
    if not isinstance(name, str) or not name.strip():
        return {"success": False, "error": "script_create: name 不能为空"}
    step_error = _validate_script_steps(steps)
    if step_error:
        return {"success": False, "error": f"script_create: {step_error}"}
    if schedule is not None:
        if not isinstance(schedule, dict):
            return {"success": False, "error": "script_create: schedule 必须是 JSON object"}
        schedule_time = schedule.get("time")
        if not isinstance(schedule_time, str) or not _SCRIPT_TIME_RE.fullmatch(schedule_time):
            return {"success": False, "error": "script_create: schedule.time 必须是 HH:MM"}
        schedule_enabled = schedule.get("enabled", True)
        if not isinstance(schedule_enabled, bool):
            return {"success": False, "error": "script_create: schedule.enabled 必须是布尔值"}
    params: dict = {
        "name": name,
        "description": description if isinstance(description, str) else "",
        "steps": list(steps or []),
    }
    if schedule is not None:
        params["schedule"] = schedule
    return _send_action("script_create", params)


@mcp.tool()
def script_delete(name: str) -> dict:
    """删除手机端指定脚本。"""
    if not isinstance(name, str) or not name.strip():
        return {"success": False, "error": "script_delete: name 不能为空"}
    return _send_action("script_delete", {"name": name})


@mcp.tool()
def script_run(name: str) -> dict:
    """转发手机执行脚本（shell/speak/notify_pc 均在手机端执行）；手机离线返回"手机未连接"。"""
    if not isinstance(name, str) or not name.strip():
        return {"status": "error", "error": "script_run: name 不能为空", "results": []}
    return _send_action("script_run", {"name": name})


@mcp.tool()
def script_schedule(name: str, time: str, enabled: bool = True) -> dict:
    """在手机端设置/取消脚本每日定时（手机 AlarmManager 调度；time 格式 HH:MM）。"""
    if not isinstance(name, str) or not name.strip():
        return {"success": False, "error": "script_schedule: name 不能为空"}
    if not isinstance(time, str) or not _SCRIPT_TIME_RE.fullmatch(time):
        return {"success": False, "error": "script_schedule: time 必须是 HH:MM"}
    if not isinstance(enabled, bool):
        return {"success": False, "error": "script_schedule: enabled 必须是布尔值"}
    return _send_action("script_schedule", {"name": name, "time": time, "enabled": enabled})


@mcp.tool()
def script_enable(name: str) -> dict:
    """启用手机端脚本每日定时（等价 schedule.enabled=true，保留原时间）。"""
    if not isinstance(name, str) or not name.strip():
        return {"success": False, "error": "script_enable: name 不能为空"}
    return _send_action("script_enable", {"name": name})


@mcp.tool()
def script_disable(name: str) -> dict:
    """停用手机端脚本每日定时（等价 schedule.enabled=false）。"""
    if not isinstance(name, str) or not name.strip():
        return {"success": False, "error": "script_disable: name 不能为空"}
    return _send_action("script_disable", {"name": name})


# -----------------------------------------------------------------------------
# 实验性视觉元素 tools（默认关闭：MCP_EXPERIMENTAL=1 才注册，未设置时不出现在 tools/list）
# 实现见 ai_mcp_server/visual/；手机端见 client/VisualElementActions.java
# -----------------------------------------------------------------------------
if os.environ.get("MCP_EXPERIMENTAL", "").strip() == "1":
    from visual import register_visual_tools as _register_visual_tools

    _register_visual_tools(mcp, _send_action)
    logger.info("已注册实验性视觉元素 tools（element_register/element_find/element_click）")


# -----------------------------------------------------------------------------
# 外部配置 tools
# -----------------------------------------------------------------------------
_PARAMETER_NAME_PATTERN = re.compile(r"^[A-Za-z_][A-Za-z0-9_]*$")
_PARAMETER_TYPES = {
    "str": str,
    "string": str,
    "int": int,
    "integer": int,
    "number": float,
    "float": float,
    "bool": bool,
    "boolean": bool,
    "list": list,
    "array": list,
    "dict": dict,
    "object": dict,
    "any": Any,
}


def _resolve_parameter_type(type_name: Any) -> Any:
    """把配置中的 JSON 类型名转换为 FastMCP/Pydantic 可识别的类型。"""
    if not isinstance(type_name, str) or not type_name.strip():
        raise ValueError("param.type 必须是非空字符串")
    normalized = type_name.strip().lower()
    if normalized in _PARAMETER_TYPES:
        return _PARAMETER_TYPES[normalized]
    if normalized.endswith("[]") and normalized[:-2] in _PARAMETER_TYPES:
        return list[_PARAMETER_TYPES[normalized[:-2]]]
    if normalized.startswith("array<") and normalized.endswith(">"):
        element_type = _resolve_parameter_type(normalized[6:-1])
        return list[element_type]
    raise ValueError(f"不支持的 param.type: {type_name!r}")


def _parameter_annotation(type_name: Any, required: bool, description: Any) -> Any:
    """为动态 tool 生成带 description 的参数注解。"""
    resolved_type = _resolve_parameter_type(type_name)
    annotation = resolved_type if required else Optional[resolved_type]
    if description:
        if not isinstance(description, str):
            raise ValueError("param.description 必须是字符串")
        annotation = Annotated[annotation, Field(description=description)]
    return annotation


def _format_template(template: str, values: dict, optional_names: set[str]) -> str:
    """使用 str.format 语义替换命令模板；可选参数缺失时替换为空字符串。"""
    formatter = string.Formatter()

    class FormatMap(dict):
        def __missing__(self, key: str) -> str:
            root_name = re.split(r"[.\[]", key, maxsplit=1)[0]
            if root_name in values:
                if values[root_name] is not None:
                    return values[root_name]
                if root_name in optional_names:
                    return ""
            if root_name in optional_names:
                return ""
            raise KeyError(key)

    format_map = FormatMap(values)
    for optional_name in optional_names:
        if format_map.get(optional_name) is None:
            format_map[optional_name] = ""
    try:
        return formatter.vformat(template, (), format_map)
    except (KeyError, IndexError, ValueError) as exc:
        raise ValueError(f"命令模板替换失败: {exc}") from exc


def _render_value(value: Any, values: dict, optional_names: set[str]) -> Any:
    """递归渲染字符串、列表和对象中的参数占位符。"""
    if isinstance(value, str):
        return _format_template(value, values, optional_names)
    if isinstance(value, list):
        return [_render_value(item, values, optional_names) for item in value]
    if isinstance(value, dict):
        return {
            key: _render_value(item, values, optional_names)
            for key, item in value.items()
        }
    return value


def _build_configured_tool(
    name: str,
    description: str,
    parameters: list[dict],
    steps: list[dict],
) -> Callable[..., dict]:
    """把一个外部 tool definition 编译成可注册、可校验参数的动态函数。"""
    optional_names = {
        parameter["name"]
        for parameter in parameters
        if not parameter["required"]
    }
    signature_parameters: list[inspect.Parameter] = []
    for parameter in parameters:
        annotation = _parameter_annotation(
            parameter.get("type"),
            parameter["required"],
            parameter.get("description", ""),
        )
        if parameter["required"]:
            default = inspect.Parameter.empty
        else:
            default = parameter.get("default", None)
        signature_parameters.append(
            inspect.Parameter(
                parameter["name"],
                inspect.Parameter.POSITIONAL_OR_KEYWORD,
                annotation=annotation,
                default=default,
            )
        )

    def configured_tool(**arguments: Any) -> dict:
        step_results: list[dict] = []
        for step_index, raw_step in enumerate(steps, start=1):
            if not isinstance(raw_step, dict):
                return {
                    "status": "error",
                    "tool": name,
                    "step": step_index,
                    "error": f"第 {step_index} 步必须是 JSON object",
                }
            rendered_step = _render_value(raw_step, arguments, optional_names)
            if "adb_shell" in rendered_step:
                command = rendered_step["adb_shell"]
                if not isinstance(command, str):
                    return {
                        "status": "error",
                        "tool": name,
                        "step": step_index,
                        "error": f"第 {step_index} 步 adb_shell 必须是字符串",
                    }
                step_result = _send_action(
                    _ADB_SHELL_ACTION,
                    {"command": command},
                )
            elif "action" in rendered_step:
                action_name = rendered_step["action"]
                action_params = rendered_step.get("params", {})
                if not isinstance(action_name, str) or not isinstance(action_params, dict):
                    return {
                        "status": "error",
                        "tool": name,
                        "step": step_index,
                        "error": f"第 {step_index} 步 action/params 格式错误",
                    }
                step_result = _send_action(action_name, action_params)
            else:
                return {
                    "status": "error",
                    "tool": name,
                    "step": step_index,
                    "error": f"第 {step_index} 步缺少 adb_shell 或 action",
                }
            step_result = dict(step_result)
            step_result["step"] = step_index
            step_results.append(step_result)
            if not _result_succeeded(step_result):
                return {
                    "status": "error",
                    "tool": name,
                    "step": step_index,
                    "error": _result_error(step_result),
                    "steps": step_results,
                }
        return {"status": "success", "tool": name, "steps": step_results}

    configured_tool.__name__ = "configured_tool"
    configured_tool.__doc__ = description
    configured_tool.__signature__ = inspect.Signature(
        signature_parameters,
        return_annotation=dict,
    )
    return configured_tool


def _parse_configured_tool(definition: Any) -> tuple[str, str, Callable[..., dict]]:
    """校验单个外部 tool definition 并生成动态函数。"""
    if not isinstance(definition, dict):
        raise ValueError("tools 中的每一项必须是 JSON object")
    name = definition.get("name")
    if not isinstance(name, str) or not name or not _PARAMETER_NAME_PATTERN.fullmatch(name):
        raise ValueError("tool.name 必须是非空且合法的标识符")
    description = definition.get("description", f"外部配置工具 {name}")
    if not isinstance(description, str):
        raise ValueError("tool.description 必须是字符串")

    raw_parameters = definition.get("params", [])
    raw_steps = definition.get("steps", [])
    if not isinstance(raw_parameters, list):
        raise ValueError(f"{name}.params 必须是数组")
    if not isinstance(raw_steps, list):
        raise ValueError(f"{name}.steps 必须是数组")

    parameters: list[dict] = []
    parameter_names: set[str] = set()
    for raw_parameter in raw_parameters:
        if not isinstance(raw_parameter, dict):
            raise ValueError(f"{name}.params 中的每一项必须是 JSON object")
        parameter_name = raw_parameter.get("name")
        if (
            not isinstance(parameter_name, str)
            or not _PARAMETER_NAME_PATTERN.fullmatch(parameter_name)
        ):
            raise ValueError(f"{name}: param.name 必须是合法标识符")
        if parameter_name in parameter_names:
            raise ValueError(f"{name}: param.name {parameter_name!r} 重复")
        required = raw_parameter.get("required", False)
        if not isinstance(required, bool):
            raise ValueError(f"{name}.{parameter_name}.required 必须是布尔值")
        description_text = raw_parameter.get("description", "")
        if not isinstance(description_text, str):
            raise ValueError(f"{name}.{parameter_name}.description 必须是字符串")
        _parameter_annotation(
            raw_parameter.get("type"),
            required,
            description_text,
        )
        parameter = dict(raw_parameter)
        parameter["name"] = parameter_name
        parameter["required"] = required
        parameters.append(parameter)
        parameter_names.add(parameter_name)

    for step_index, raw_step in enumerate(raw_steps, start=1):
        if not isinstance(raw_step, dict):
            raise ValueError(f"{name}.steps[{step_index}] 必须是 JSON object")
        has_adb_shell = "adb_shell" in raw_step
        has_action = "action" in raw_step
        if has_adb_shell == has_action:
            raise ValueError(
                f"{name}.steps[{step_index}] 必须且只能包含 adb_shell 或 action"
            )
        if has_adb_shell and not isinstance(raw_step["adb_shell"], str):
            raise ValueError(f"{name}.steps[{step_index}].adb_shell 必须是字符串")
        if has_action and not isinstance(raw_step["action"], str):
            raise ValueError(f"{name}.steps[{step_index}].action 必须是字符串")
        if "params" in raw_step and not isinstance(raw_step["params"], dict):
            raise ValueError(f"{name}.steps[{step_index}].params 必须是 JSON object")

    configured_tool = _build_configured_tool(name, description, parameters, raw_steps)
    return name, description, configured_tool


def _resolve_config_path(raw_path: str) -> str:
    """将相对配置路径解析为当前目录、Bridge 目录或仓库根目录。"""
    expanded_path = os.path.expanduser(raw_path)
    if os.path.isabs(expanded_path):
        return expanded_path
    search_roots = [os.getcwd(), _SCRIPT_DIR, os.path.dirname(_SCRIPT_DIR)]
    for search_root in search_roots:
        candidate_path = os.path.abspath(os.path.join(search_root, expanded_path))
        if os.path.isfile(candidate_path):
            return candidate_path
    return os.path.abspath(os.path.join(os.getcwd(), expanded_path))


def _load_external_tools() -> list[dict]:
    """读取 PHONE_TOOLS_JSON，并把有效定义注册到 FastMCP。"""
    raw_path = os.environ.get("PHONE_TOOLS_JSON", "").strip()
    if not raw_path:
        return []
    config_path = _resolve_config_path(raw_path)
    if not os.path.isfile(config_path):
        logger.warning("外部配置 tools 文件不存在: %s", config_path)
        return []
    try:
        with open(config_path, "r", encoding="utf-8") as config_file:
            config = json.load(config_file)
    except (OSError, UnicodeError, json.JSONDecodeError) as exc:
        logger.warning("加载外部配置 tools 失败: %s (%s)", config_path, exc)
        return []
    if not isinstance(config, dict) or not isinstance(config.get("tools"), list):
        logger.warning("加载外部配置 tools 失败: %s 顶层必须包含 tools 数组", config_path)
        return []

    registered: list[dict] = []
    seen_names: set[str] = set()
    for definition in config["tools"]:
        try:
            name, description, configured_tool = _parse_configured_tool(definition)
            if name in seen_names:
                raise ValueError(f"tool.name {name!r} 重复")
            seen_names.add(name)
            if mcp._tool_manager.get_tool(name) is not None:
                logger.warning("跳过外部配置 tool %s：与已有 tool 重名", name)
                continue
            mcp.add_tool(
                configured_tool,
                name=name,
                description=description,
            )
            registered.append({"name": name, "description": description})
        except Exception as exc:
            logger.warning("跳过外部配置 tool 定义: %s", exc)
    logger.info(
        "已加载 %d 个外部配置 tools: [%s]",
        len(registered),
        ", ".join(tool["name"] for tool in registered),
    )
    return registered


# 必须在 stdio run 之前注册配置 tools。
_load_external_tools()


# -----------------------------------------------------------------------------
# 入口
# -----------------------------------------------------------------------------
_mcp_server_shutdown = False


def _shutdown(*_args):
    global _mcp_server_shutdown, _notify_httpd
    if _mcp_server_shutdown:
        return
    _mcp_server_shutdown = True
    if _notify_httpd is not None:
        try:
            _notify_httpd.shutdown()
            _notify_httpd.server_close()
        except OSError:
            pass
        _notify_httpd = None
    try:
        with redirect_stdout(sys.stderr):
            mcp_server.stop()
    except Exception:  # noqa: BLE001
        logger.exception("停止 MCPServer 失败")
    if _dial_client is not None:
        try:
            _dial_client.stop()
        except Exception:  # noqa: BLE001
            logger.exception("停止手机直连客户端失败")


if __name__ == "__main__":
    import atexit
    atexit.register(_shutdown)
    # 18999 通知端点独立于 lifespan 启动（lifespan 每会话执行，会话结束会关端口——必须模块级常驻）
    _start_notify_httpd()
    if _BRIDGE_TRANSPORT == "streamable-http":
        logger.info(
            "FastMCP streamable-http server 启动（name=phone-controller） http=%s:%d/mcp | 手机 TLS %s:%d",
            _BRIDGE_HOST, _BRIDGE_PORT, HOST, PORT,
        )
        try:
            mcp.run(transport="streamable-http")
        except KeyboardInterrupt:
            logger.info("收到 KeyboardInterrupt，退出")
        finally:
            _shutdown()
    else:
        logger.info(
            "FastMCP stdio server 启动（name=phone-controller） | 手机 TLS %s:%d",
            HOST, PORT,
        )
        try:
            mcp.run()  # 默认 stdio 传输
        except KeyboardInterrupt:
            logger.info("收到 KeyboardInterrupt，退出")
        finally:
            _shutdown()
