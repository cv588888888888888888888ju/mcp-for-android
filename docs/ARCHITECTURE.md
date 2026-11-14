# 架构总览

> 更新：2026-10-26。本文描述「手机 MCP 服务」当前的真实形态——它已经不是单机脚本，而是三节点服务。

一句话：**手机是执行端和采集端，个人服务器是控制面与最终存储，ECS 只是流量中转。**

## 1. 拓扑

```
┌──────────────┐  MCP streamable-http          ┌────────────────────────────┐
│  MCP 客户端   │ ────────────────────────────▶ │ 个人服务器 192.0.2.10:9500    │
│ (Hermes/DSH) │ ◀──────────────────────────── │ bridge_mcp.py (FastMCP)     │
└──────────────┘                               │ hub_server.py（最终存储）    │
                                               │ hub_dump.py（镜像导出）      │
                                               └──────────▲─────────────────┘
                                                          │ TLS 9444（CA pin + device token）
                                                          │ 手机主动出站，长连接
                                               ┌──────────┴─────────────────┐
                                               │ ECS 192.0.2.1:9444           │
                                               │ phone-relay: socat 纯 L4 转发 │
                                               │ 不解密、不落库、只有一条通道  │
                                               └──────────▲─────────────────┘
                                                          │ 加密内建的用户态 WG 隧道（UDP 51820 / 或封进 TCP 443）
                                               ┌──────────┴─────────────────┐
                                               │ 手机 com.jxev.ai            │
                                               │ 后台服务，无 UI，无 root     │
                                               └────────────────────────────┘

本机 Fedora ──ssh wg──▶ 192.0.2.10，每 5 分钟拉 hub_dump，落 info-hub（镜像）
```

方向很关键：**手机永远主动向外连**，所以没有入站端口、不需要 NAT 穿透、不占 VPN 槽。

## 2. 组件职责

### 手机 `com.jxev.ai`（执行端 + 采集端）

| 组件 | 职责 |
|---|---|
| `MCPService` | 前台服务，持有隧道/控制通道/通知中枢的生命周期 |
| `TunnelManager` | 拉起内置 `libwgproxy.so`（wireproxy 补丁版），本地 SOCKS5 `127.0.0.1:10800`；`tunnel_transport=tcp` 时先拉起 `libwgtcp.so` 把 WG 的 UDP 封进 TCP 443（对端 ECS `wgtcp.service`），Endpoint 自动指向 `127.0.0.1:51821`；fail-closed |
| `MCPSocketClient` | 出站 TLS 长连接（经隧道 SOCKS → ECS 9444），握手/心跳/重连/半开检测 |
| `MCPListenServer` | wg 直连模式下的兜底监听（`0.0.0.0:9444`，accept 后只放 `192.0.2.0/24`） |
| `AdbLocalClient` | 三条执行通道路由：5555 → 无线调试 TLS → Shizuku（见 `CHANNELS.md`） |
| `AdbActionExecutor` | 40+ 设备动作，全部走 adb（input/screencap/dumpsys/monkey/svc） |
| `ScriptEngine` | 手机端脚本库 + AlarmManager 每日定时（shell/speak/notify_pc），离线可跑 |
| `HubStore` / `HubNotificationListener` / `HubUploader` | 通知采集 → 本地库 → 20s 一轮批量上报 |
| `ConfigManager` | SharedPreferences（`mcp_prefs`）配置，运行时改、进程重建生效 |

### 个人服务器 `192.0.2.10`

| 组件 | 职责 |
|---|---|
| `bridge_mcp.py` | FastMCP（**必须 streamable-http**），MCP 工具面；9444 TLS 服务端；PC 通知端点 18999 |
| `hub_server.py` | 消息中枢数据库（`hub.db`）：message / tag / attachment 三表 + 附件内容寻址 |
| `hub_dump.py` | 增量导出（`WHERE ts > ? ORDER BY ts ASC LIMIT ?`），给本机镜用 |
| `certs/` | CA + server 证书，手机侧 pin CA |

### ECS `192.0.2.1`

只跑 `phone-relay.service`：`socat TCP-LISTEN:9444,bind=192.0.2.1,fork TCP:192.0.2.10:9444`。
旧 bridge 与旧库已退役（`archive/hub.db.20261026`），这正是「一条通道」约束的落地点。

### 本机 Fedora

`info-hub` 的 `phone-hub` 采集器经 wg 直连 `root@192.0.2.10` 拉增量，落 `events`（`source='phone-hub'`），只做镜像与跨源检索。

## 3. 端口表

| 端口 | 位置 | 协议 | 谁能连 |
|---|---|---|---|
| 51820/udp | ECS | WireGuard | 手机、PC |
| 9444 | ECS `192.0.2.1` | socat L4 | wg 内网 |
| 9444 | 个人服务器 `192.0.2.10` | TLS1.3 | ECS 转发进来的手机连接 |
| 9500 | 个人服务器 `192.0.2.10` | MCP streamable-http | wg 内网（MCP 客户端） |
| 18999 | 个人服务器 | HTTP | wg 内网（PC 通知拉取） |
| 10800 | 手机回环 | SOCKS5 | 仅 app 自身 |
| 5555 | 手机回环 | adb | 仅 app 自身 |

公网只开 51820/udp。

## 4. 数据面

| 面 | 流向 | 说明 |
|---|---|---|
| 控制面 | 客户端 → 9500 → 9444 → 手机 → 本地 adb | `request` / `response` 帧，单条超时由调用方给 |
| 通知面 | 手机通知 → `hub.db`（本地）→ `hub_batch` → 服务器 `hub.db` → 本机镜像 | 手机离线时积压，恢复后自动冲掉 |
| 脚本面 | 手机 AlarmManager → ScriptEngine | 不依赖服务器和客户端 |
| 出网面 | 手机 DSH/agent 的 HTTP → 隧道 → ECS → 公网 | 出口 IP = ECS 公网 IP |

## 5. 自愈与失败语义

| 故障 | 行为 |
|---|---|
| 隧道起不来 | **fail-closed**：控制通道不启动，日志 `built-in tunnel not ready, control channel NOT started` |
| 连接半开（对端静默死亡） | 30s 心跳 + 90s 无入站判超时 → 断开重连（指数退避 1s→60s） |
| 服务器重启 | 手机最多 ~2 分钟内自动重连，积压的 hub 数据补报 |
| 三条 adb 通道全不可用 | 动作返回错误，不静默成功 |
| 手机离线 | MCP 工具返回「手机未连接」类错误，服务器侧 `hub_query` 仍可查历史 |
| 附件写入 | sha256 内容寻址，重复内容不重复落盘 |

## 6. 安全模型

- 传输：TLS1.3，手机侧 pin CA（`res/raw`），主机名校验；服务器侧证书 `CN=mcp-android`。
- 认证：握手带 `device token`，不匹配直接拒；MCP 端点只在 wg 内网。
- 授权：手机 app 无 UI、无 root、不申请无障碍；执行能力来自 shell uid（adb/无线调试/Shizuku）。
- 凭证：隧道私钥在 SharedPreferences，无线调试密钥在 `assets/adbkey_wifi*`（gitignore），均不入库。
- ECS 视角：只能看到密文流，无密钥、无库。

## 7. 已知边界

- HyperOS 灭屏会冻结后台进程；本服务靠前台服务 + 隧道长连接维持，实测灭屏 30 分钟连接存活。
- 手机重启后：无线调试需手点一次开关；隧道与控制通道会自动恢复。
- 通知只能拿到「已发出的通知」，被系统折叠/静音的群消息可能不出现（MIUI 行为，非本服务可控）。
- 附件目前只能从服务器/MCP 侧写入；手机通知的图片未做分片上报。
