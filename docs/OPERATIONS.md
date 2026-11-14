# 运维手册

> 更新：2026-10-26。按「装 → 配 → 部署三节点 → 日常检查 → 故障处理」排。

## 1. 构建手机 APK

环境（Fedora 本机实测）：

| 依赖 | 路径/版本 |
|---|---|
| JDK | `/opt/jdk-17`（17） |
| Android SDK | `/opt/android-sdk`（platform 34、build-tools 34） |
| Gradle | wrapper 7.3.3（`gradlew` 需 LF；CRLF 会直接报语法错） |
| AGP | 7.2.1 |

```bash
export JAVA_HOME=/opt/jdk-17
cd AiAgentForAndroid
./gradlew assembleDebug          # 产物 app/build/outputs/apk/debug/app-debug.apk
```

`local.properties` 里的 `sdk.dir` 必须是 Linux 路径。

## 2. 安装 / 升级

```bash
adb -s <serial> install -r app/build/outputs/apk/debug/app-debug.apk   # 保留数据
adb -s <serial> shell am start -n com.jxev.ai/.MainActivity
```

升级后如果有 SharedPreferences 结构变化，需重建进程才生效（`run-as com.jxev.ai kill <pid>` 后重启 Activity）。

**禁止** `am force-stop com.jxev.ai`（会连带停掉本地 adb 客户端）；杀进程只用：

```bash
adb shell run-as com.jxev.ai kill $(adb shell pidof com.jxev.ai)
```

## 3. 配置键（SharedPreferences `mcp_prefs`）

| 键 | 类型 | 说明 |
|---|---|---|
| `server_host` | string | 服务器地址（wg 内网 IP，如 `192.0.2.1`） |
| `server_socket_port` | int | 控制通道端口（9444） |
| `device_token` | string | 握手 token，须与服务端 `MCP_DEVICE_TOKEN` 一致 |
| `listen_enabled` | bool | 是否开 wg 直连兜底监听（默认 true） |
| `listen_port` | int | 兜底监听端口（默认 9444，-1 未设） |
| `tunnel_enabled` | bool | 是否启用内置用户态 WG 隧道（**必须 true**） |
| `tunnel_socks_port` | int | 隧道本地 SOCKS5 端口（10800） |
| `tunnel_private_key` | string | 手机侧 WG 私钥 |
| `tunnel_peer_public_key` | string | 服务器 wg0 公钥 |
| `tunnel_endpoint` | string | `203.0.113.10:51820` |
| `tunnel_address` | string | 手机在 wg 内的地址（如 `192.0.2.8/32`） |
| `tunnel_allowed_ips` | string | 默认 `192.0.2.0/24`（只走内网，不全局代理） |
| `tunnel_mtu` | int | 1280（1420 会卡 TLS 握手） |
| `tunnel_transport` | string | `udp`（默认，直连 WG）或 `tcp`（WG 封进 TCP，用于只放行 TCP 的网络） |
| `tunnel_tcp_endpoint` | string | TCP 承载对端，默认 `203.0.113.10:443` |
| `tunnel_tcp_local_port` | int | TCP 承载的本地回环 UDP 端口（51821），此时 WG 的 Endpoint 自动指向 `127.0.0.1:<该端口>` |

改法（不改代码）：

```bash
adb shell "run-as com.jxev.ai sh -c 'cat shared_prefs/mcp_prefs.xml'"
# 备份 → 改 → run-as kill → 重启 Activity
```

隧道缺失时 **fail-closed**：控制通道不启动，日志 `built-in tunnel not ready, control channel NOT started`。

## 4. 三节点部署

### 4.1 ECS（纯转发）

```bash
# /etc/systemd/system/phone-relay.service
[Unit]
Description=Phone MCP relay (L4)
After=network-online.target

[Service]
ExecStart=/usr/bin/socat TCP-LISTEN:9444,bind=192.0.2.1,fork,reuseaddr TCP:192.0.2.10:9444
Restart=always

[Install]
WantedBy=multi-user.target
```

```bash
systemctl daemon-reload && systemctl enable --now phone-relay
systemctl disable --now mcp-bridge   # 旧 bridge 已退役，别同时开
```

### 4.2 个人服务器（控制面 + 最终存储）

```bash
cd /opt/mcp_for_android/ai_mcp_server
python3 -m venv .venv && .venv/bin/pip install -r requirements.txt   # 含 mcp==1.29.0
systemctl enable --now mcp-bridge
systemctl status mcp-bridge --no-pager
ss -tlnp | grep -E '9444|9500|18999'      # 三个端口都要在 192.0.2.10 上
```

### 4.3 手机

```bash
adb -s <serial> shell am start -n com.jxev.ai/.MainActivity
adb logcat -s MCPSocketClient:V TunnelManager:V | grep -E 'TUNNEL|Handshake'
```

期望顺序：`TUNNEL_UP` → `Handshake successful` → 每 15s `heartbeat_response`。

### 4.4 本机镜像

```bash
systemctl --user list-timers | grep info-hub        # 每 5 分钟
journalctl --user -u info-hub-collect.service -n 20 # 看 emitted=N
```

## 5. 日常检查（30 秒版）

```bash
# 1) 手机连接
ssh root@192.0.2.10 "ss -tnp | grep :9444"                    # 有 ESTAB 才对
# 2) 服务（bridge 在个人服务器，relay 在 ECS）
ssh root@192.0.2.10 "systemctl is-active mcp-bridge"
ssh root@192.0.2.1 "systemctl is-active phone-relay"
# 3) 中枢在长
ssh root@192.0.2.10 "cd /opt/mcp_for_android/ai_mcp_server && .venv/bin/python -c 'import hub_server as h;print(h.stats()[\"total\"])'"
# 4) 镜像在跟
sqlite3 ~/.dsh/info-hub/hub.db "select count(*),max(ts) from events where source='phone-hub'"
```

## 6. 故障矩阵

| 现象 | 先看 | 处理 |
|---|---|---|
| MCP 工具全报「手机未连接」 | 服务器 `ss -tnp \| grep 9444` | 无 ESTAB → 看手机 `logcat \| grep MCPSocketClient` |
| 手机日志 `built-in tunnel not ready` | `shared_prefs` 里 `tunnel_enabled` / 私钥 | 补齐配置后重启 Activity |
| `Handshake failed: unauthorized` | `device_token` 与 `MCP_DEVICE_TOKEN` | 两边对齐 |
| 连接在但命令无输出 | `channel_status` | 三条通道逐一排查（见 CHANNELS.md） |
| 手机进程被冻 | `cat /sys/fs/cgroup/apps/uid_<uid>/pid_<pid>/cgroup.freeze` | 返回 1 即被冻结；保活靠前台服务+长连接 |
| 手机重启后无线调试没了 | 设置里开关 | 手点一次（已接受） |
| 服务器重启后手机不重连 | 手机 logcat 有无「心跳超时」 | 90s 判超时后自动重连；否则重启 app |
| 通知不进库 | 手机 `logcat -s HubUploader`；服务器 `journalctl -u mcp-bridge \| grep hub_batch` | 手机侧无「上报 N 条」→ 监听器授权丢失 |
| 镜像不增长 | `journalctl --user -u info-hub-collect` | 检查 `hub.config.yaml` 的 `ssh_host` 与 wg 路由 |

## 7. 备份与回滚

| 对象 | 位置 | 备份方式 |
|---|---|---|
| 服务器 `hub.db` | `/opt/mcp_for_android/ai_mcp_server/hub.db` | 改结构前 `cp hub.db hub.db.bak-<date>` |
| 附件 | 同目录 `attachments/` | 内容寻址，可整体 rsync |
| 证书 | `ai_mcp_server/certs/` | 改动前整目录备份（换 CA 需同步手机 `res/raw`） |
| APK | `AiAgentForAndroid/app/build/outputs/apk/debug/` | 保留上一版，便于回滚 |
| 手机数据 | `/data/data/com.jxev.ai` | `run-as com.jxev.ai tar`（需先停 app） |

## 8. 已知限制

- 手机重启后无线调试需手点一次；隧道与控制通道会自动恢复。
- 重启/重装后 `adb forward` 全部失效，需要重设。
- 通知只能拿到「已发出的通知」；系统折叠/静音的消息可能不出现。
- 附件目前只能从服务器/MCP 侧写入（手机通知图片未做分片上报）。
