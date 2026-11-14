"""wg 直连手机 MCP 的 TLS 客户端（2026-09-29）。

架构反转：手机 App（com.jxev.ai / MCPListenServer）在 wg 内网 IP（192.0.2.4:9444）
上以 TLS 服务端监听；本类作为拨号方主动连入，协议与原出站链路一致：

    本类（任意 wg peer）──TLS──> 手机 MCPListenServer
    handshake(token) → handshake_response(ok)
    request → response（adb_shell / click / script_* ...）
    heartbeat → heartbeat_response

接口与 mcp.mcp_interface.DeviceConnection 对齐（send_request(request, callback)），
bridge_mcp.py 设 PHONE_DIAL_HOST 后即可用本类替代 accept 模式的手机连接。
"""
from __future__ import annotations

import json
import logging
import socket
import ssl
import threading
import time
import types
import uuid

logger = logging.getLogger("phone_dial")


class _ResponseMsg:
    """与 DeviceConnection 回调返回的 response 对象同形（status/data/error）。"""

    def __init__(self, status: str = "error", data: dict | None = None,
                 error: str | None = None, request_id: str = ""):
        self.status = status
        self.data = dict(data or {})
        self.error = error
        self.request_id = request_id


class PhoneDirectClient:
    """手机 wg 直连客户端：TLS + token 握手 + 心跳 + 自动重连。"""

    HEARTBEAT_INTERVAL = 30.0
    RECONNECT_INITIAL = 1.0
    RECONNECT_MAX = 60.0
    HANDSHAKE_TIMEOUT = 10.0

    def __init__(self, host: str, port: int, ca_file: str, token: str,
                 device_id: str = "wg-dial", request_timeout: float = 60.0,
                 on_phone_request=None):
        self.host = host
        self.port = int(port)
        self.ca_file = ca_file
        self.token = token or ""
        self.device_id = device_id
        self.request_timeout = float(request_timeout)
        self.on_phone_request = on_phone_request  # (device_id, message_dict) -> None

        self._sock: socket.socket | None = None
        self._writer_lock = threading.Lock()
        self._pending: dict[str, object] = {}
        self._pending_lock = threading.Lock()
        self._connected = threading.Event()
        self._stop = threading.Event()
        self._conn_thread: threading.Thread | None = None

    # ------------------------------------------------------------------
    # 生命周期
    # ------------------------------------------------------------------
    def start(self) -> None:
        if self._conn_thread and self._conn_thread.is_alive():
            return
        self._stop.clear()
        self._conn_thread = threading.Thread(
            target=self._connect_loop, name="phone-dial", daemon=True
        )
        self._conn_thread.start()

    def stop(self) -> None:
        self._stop.set()
        self._close_socket()

    @property
    def connected(self) -> bool:
        return self._connected.is_set()

    # ------------------------------------------------------------------
    # 连接 / 重连
    # ------------------------------------------------------------------
    def _connect_loop(self) -> None:
        backoff = self.RECONNECT_INITIAL
        while not self._stop.is_set():
            ok = self._connect_once()
            if ok:
                backoff = self.RECONNECT_INITIAL
                try:
                    self._read_loop()
                except Exception as exc:  # noqa: BLE001 任何读循环异常都不允许杀死重连线程
                    logger.warning("读循环异常（将重连）: %s", exc)
                    self._connected.clear()
                    self._close_socket()
                    self._fail_all_pending("手机直连已断开")
            if self._stop.is_set():
                break
            logger.info("手机直连断开，%.1fs 后重连 %s:%d", backoff, self.host, self.port)
            if self._stop.wait(backoff):
                break
            backoff = min(backoff * 2, self.RECONNECT_MAX)

    def _connect_once(self) -> bool:
        try:
            ctx = ssl.create_default_context(cafile=self.ca_file)
            raw = socket.create_connection((self.host, self.port), timeout=10)
            raw.settimeout(None)
            tls = ctx.wrap_socket(raw, server_hostname=self.host)
            self._sock = tls
            handshake = {
                "type": "handshake",
                "deviceId": self.device_id,
                "timestamp": time.time(),
            }
            if self.token:
                handshake["token"] = self.token
            self._send_json(handshake)

            reply = self._read_line(timeout=self.HANDSHAKE_TIMEOUT)
            if reply is None:
                raise ConnectionError("无握手响应")
            msg = json.loads(reply)
            if msg.get("type") != "handshake_response" or msg.get("status") != "ok":
                raise ConnectionError(f"握手被拒: {reply}")
            self._connected.set()
            logger.info("手机直连握手成功 %s:%d (CA=%s)", self.host, self.port, self.ca_file)
            return True
        except Exception as exc:  # noqa: BLE001
            logger.warning("手机直连失败 %s:%d: %s", self.host, self.port, exc)
            self._close_socket()
            return False

    def _read_loop(self) -> None:
        sock = self._sock
        if sock is None:
            return
        heartbeat = threading.Thread(target=self._heartbeat_loop, daemon=True)
        heartbeat.start()
        try:
            while not self._stop.is_set():
                line = self._read_line(timeout=None)
                if line is None:
                    break
                try:
                    msg = json.loads(line)
                except json.JSONDecodeError:
                    logger.warning("非 JSON 消息，忽略")
                    continue
                mtype = msg.get("type")
                if mtype == "response":
                    self._complete(msg)
                elif mtype == "heartbeat":
                    self._send_json({"type": "heartbeat_response", "timestamp": time.time()})
                elif mtype == "request":
                    if self.on_phone_request is not None:
                        threading.Thread(
                            target=self.on_phone_request,
                            args=(msg.get("deviceId", "phone"), msg),
                            daemon=True,
                        ).start()
                elif mtype in ("handshake_response", "welcome"):
                    pass
                else:
                    logger.warning("未知消息类型: %s", mtype)
        finally:
            self._connected.clear()
            self._close_socket()
            self._fail_all_pending("手机直连已断开")

    def _heartbeat_loop(self) -> None:
        while self._connected.is_set() and not self._stop.is_set():
            if self._stop.wait(self.HEARTBEAT_INTERVAL):
                break
            try:
                self._send_json({
                    "type": "heartbeat",
                    "deviceId": self.device_id,
                    "timestamp": time.time(),
                })
            except Exception:  # noqa: BLE001
                logger.info("心跳发送失败，触发重连")
                self._close_socket()
                return

    # ------------------------------------------------------------------
    # 请求收发
    # ------------------------------------------------------------------
    def send_request(self, request, callback) -> None:
        """与 DeviceConnection.send_request 同形：request 鸭子类型 + callback(response)。"""
        request_id = getattr(request, "request_id", None) or str(uuid.uuid4())
        if not self.connected:
            if callback is not None:
                callback(_ResponseMsg(
                    status="error",
                    error=f"手机直连未建立（{self.host}:{self.port}）",
                    request_id=request_id,
                ))
            return
        message = {
            "type": "request",
            "requestId": request_id,
            "actionType": getattr(request, "action_type", ""),
            "parameters": dict(getattr(request, "parameters", {}) or {}),
            "timestamp": time.time(),
        }
        box = {"callback": callback}

        def _timeout() -> None:
            with self._pending_lock:
                pending = self._pending.pop(request_id, None)
            if pending is not None:
                pending(_ResponseMsg(
                    status="timeout",
                    error=f"请求 {getattr(request, 'action_type', '')} 在 "
                          f"{self.request_timeout}s 内未收到手机回执",
                    request_id=request_id,
                ))

        if callback is not None:
            with self._pending_lock:
                self._pending[request_id] = _make_callback_adapter(callback, box)
            timer = threading.Timer(self.request_timeout, _timeout)
            timer.daemon = True
            timer.start()
        try:
            self._send_json(message)
        except Exception as exc:  # noqa: BLE001
            with self._pending_lock:
                self._pending.pop(request_id, None)
            if callback is not None:
                callback(_ResponseMsg(status="error", error=str(exc), request_id=request_id))

    def _complete(self, msg: dict) -> None:
        request_id = str(msg.get("requestId", ""))
        with self._pending_lock:
            entry = self._pending.pop(request_id, None)
        if entry is None:
            logger.warning("收到未知 requestId 的回执: %s", request_id)
            return
        data = msg.get("data") or {}
        entry(_ResponseMsg(
            status=str(data.get("status", msg.get("status", "success"))),
            data=data if isinstance(data, dict) else {},
            error=data.get("error") if isinstance(data, dict) else None,
            request_id=request_id,
        ))

    def _fail_all_pending(self, reason: str) -> None:
        with self._pending_lock:
            entries = list(self._pending.values())
            self._pending.clear()
        for entry in entries:
            try:
                entry(_ResponseMsg(status="error", error=reason))
            except Exception:  # noqa: BLE001
                pass

    # ------------------------------------------------------------------
    # 底层 IO
    # ------------------------------------------------------------------
    def send_json(self, obj: dict) -> None:
        """公开发送口（pc_notify 回执等 reverse-direction 消息）。"""
        self._send_json(obj)

    def _close_socket(self) -> None:
        sock = self._sock
        self._sock = None
        if sock is not None:
            try:
                sock.close()
            except OSError:
                pass

    def _send_json(self, obj: dict) -> None:
        sock = self._sock
        if sock is None:
            raise ConnectionError("socket 未建立")
        payload = (json.dumps(obj, ensure_ascii=False) + "\n").encode("utf-8")
        with self._writer_lock:
            sock.sendall(payload)

    def _read_line(self, timeout: float | None) -> str | None:
        sock = self._sock
        if sock is None:
            return None
        if timeout is not None:
            sock.settimeout(timeout)
        else:
            sock.settimeout(None)
        buf = bytearray()
        while True:
            char = sock.recv(1)
            if not char:
                return None
            if char == b"\n":
                return buf.decode("utf-8")
            buf += char


def _make_callback_adapter(callback, box: dict):
    """把 bridge 风格 callback(response) 包一层：超时后丢弃迟到回执。"""

    def _call(response) -> None:
        if box.get("done"):
            return
        box["done"] = True
        callback(response)

    return _call
