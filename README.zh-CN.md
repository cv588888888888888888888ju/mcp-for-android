# mcp-for-android — 把你的安卓手机变成 MCP 能力提供方

[English](README.md) | **简体中文**

> **AI 辅助项目**：本代码库由 AI 编码代理（Hermes Agent + OpenAI Codex）参与开发。手机侧网关引擎、adb 协议客户端与 MCP 桥由 AI 协助编写，并在真机上端到端验证过。

用任意 MCP 客户端（Claude、Hermes、自建代理）控制安卓手机：启动应用、点击/输入/滑动、截屏、读通知、跑 shell 命令、**用 TTS 说话**，还能**在手机本地跑定时脚本**（离线可用，像一台迷你网关）。

> **版本** `0.2.2` · [发布页](https://github.com/cv588888888888888888888ju/mcp-for-android/releases) · MIT 许可

## 架构

```
┌──────────────────────────────────────────────────┐
│  MCP 客户端（Hermes / DSH / 任意 MCP 客户端）    │
└─────────────────────────┬────────────────────────┘
                          │ streamable-http 9500
                          ▼
┌─────────────────────────┴────────────────────────┐
│  个人服务器  192.0.2.10:9500                     │
│  bridge_mcp.py + hub_server.py                   │
│  （控制面 + 最终存储）                           │
└─────────────────────────┬────────────────────────┘
                          │ TLS 9444 + 设备令牌
                          ▼
┌─────────────────────────┴────────────────────────┐
│  ECS 中转  192.0.2.1:9444                        │
│  phone-relay：socat 纯 L4 转发                   │
│  （不解密、不落库）                              │
└─────────────────────────┬────────────────────────┘
                          │ 内置用户态 WireGuard 隧道（UDP 51820）
                          ▼
┌─────────────────────────┴────────────────────────┐
│  手机  com.jxev.ai（后台服务，无 UI）            │
│  - 内置用户态 WireGuard 隧道                     │
│  - 三条可互换执行通道                            │
│  - 消息中枢 + 脚本引擎 + TTS                     │
└──────────────────────────────────────────────────┘
```

- **手机 = 执行端 + 采集端**：自己拉一条用户态 WireGuard 隧道（不占系统 VPN 槽、不需要 root）；长连接只往外连，手机上不开放任何入站端口。
- **个人服务器 = 控制面 + 最终存储**；**ECS 只做流量中转**（单条 TCP 9444）。
- **MCP 客户端**只连服务器的 `9500`，不直接碰手机。

文档地图（`docs/` 下目前均为中文）：

| 文档 | 内容 |
|---|---|
| [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) | 拓扑、组件职责、端口、数据面、失败语义 |
| [docs/INTERFACE.md](docs/INTERFACE.md) | MCP 工具目录、`device_action` 动作表、socket 协议、中枢数据模型 |
| [docs/OPERATIONS.md](docs/OPERATIONS.md) | 构建/安装、配置键、三节点部署、故障矩阵、备份 |
| [docs/CHANNELS.md](docs/CHANNELS.md) | 三条执行通道的前提、坑、诊断 |
| [docs/NOTIFICATION_HUB.md](docs/NOTIFICATION_HUB.md) | 通知中枢链路与 v2 通用消息模型 |
| [docs/WG_DIRECT_MODE.md](docs/WG_DIRECT_MODE.md) | WireGuard 直连拨号模式（桥主动拨手机） |

## 网络方案

手机永远**主动外连**桥的 TLS 9444，手机上不监听任何端口。WireGuard 是**可选项**：从下面几种传输里挑一种即可。

> 本仓库出现的所有 IP 都是占位符（`192.0.2.0/24` = RFC 5737 TEST-NET-1），请换成你自己的地址。

### WireGuard 用在哪些地方

| 位置 | 作用 | 必须用 WG？ |
|---|---|---|
| `TunnelManager` + `libwgproxy.so` / `libwgtcp.so`（手机） | 内置用户态隧道；出站 TLS 9444 走它（不占系统 VPN 槽、不需要 root） | 可选 |
| `MCP_HOST`（桥的监听地址） | 手机拨入的地址 | 只有把它绑到 WG 地址时才需要 |
| `deploy/phone-relay.service`（socat） | 桥前面的 L4 中转 | 只有走中转拓扑时才需要 |
| `PHONE_DIAL_HOST`（WG 直连模式，见 [docs/WG_DIRECT_MODE.md](docs/WG_DIRECT_MODE.md)） | 由桥主动拨手机，而不是等手机连进来 | 需要 |

两个隧道二进制**不随仓库发布**：它们是预编译的 Go 产物，源码不在本仓库内。想保留内置隧道就得自己提供——例如把打过补丁的 `wireproxy` 编成 `libwgproxy.so`，再配上 TCP 转发壳 `libwgtcp.so`，放进 `AiAgentForAndroid/app/src/main/jniLibs/`。更省事的做法是在手机上装官方 WireGuard App、放弃内置隧道：桥并不关心数据包是怎么到达的。

### 方案 A —— 同一局域网或热点（不用 WG）

```bash
# 桥：监听所有网卡
export MCP_HOST=0.0.0.0
export BRIDGE_TRANSPORT=streamable-http
.venv/bin/python bridge_mcp.py
```

手机端配置：`server_host=<桥的局域网 IP>`、`server_socket_port=9444`。在主机防火墙上放开 TCP 9444，并确保服务器证书的 SAN 里包含该 IP（或主机名）。

2026-11-13 已端到端实测（手机在 Wi-Fi 192.0.2.24 → 桥在 192.0.2.83，不走 WireGuard、不插 USB）：TLS 握手通过，随后 `get_ui_state`（source=ax）、`screenshot`、`click`（verified=true）、`swipe`、`scroll_probe` 全部在这条通路上跑通。

### 方案 B —— USB 线（不用 WG，也不需要网络）

```bash
adb reverse tcp:9444 tcp:9444      # 手机 127.0.0.1:9444 -> 主机 127.0.0.1:9444
```

手机端配置：`server_host=127.0.0.1`、`server_socket_port=9444`；桥可以继续用 `MCP_HOST=127.0.0.1`。包括 adb 动作在内的一切都走 USB 线。

### 方案 C —— 公网 VPS 端口转发（不用 WG）

把桥跑在有公网 IP 的 VPS 上，放行入站 TCP 9444，手机端 `server_host` 填该 IP。TLS 是失败即拒（fail-closed），且 `MCP_DEVICE_TOKEN` **必须**非空——空令牌等于关掉认证，千万别在没有令牌的情况下把 9444 暴露到公网。

现状：中转侧已准备好并验证过（`socat TCP-LISTEN:9445,fork,reuseaddr TCP:<bridge>:9444` 能正常监听），但手机侧的端到端测试仍卡在云安全组——手机用移动数据连该公网端口会超时（是被丢弃，不是被拒绝）。在控制台放开端口后，按方案 A 的步骤重跑一遍即可。

## 功能

- 🔌 **MCP 桥**：streamable-http（FastMCP），35 个工具（操作 / 屏幕 / 通知 / 消息中枢 / 脚本 / TTS）
- 🔐 **TLS + 设备令牌**保护手机侧 socket（自签 CA；失败即拒）
- 🕳️ **应用内置用户态 WireGuard 隧道**：出站 TLS 走隧道，不占系统 VPN 槽、不需要 root
- 🚪 **三条可互换执行通道**：本地 `adb` 5555 / 无线调试 TLS / Shizuku（自动降级）
- 🔔 **消息中枢 v2**：任意来源的「带标签文本消息 + 附件」统一入库（手机通知只是其中一种来源，`source=phone`）；服务器是最终存储，桌面 info-hub 只做镜像
- 🤖 **手机上是纯后台服务**：没有界面。两条可互换的路径——**进程内无障碍树**（需要开启无障碍服务，完全不依赖 adb）或**本地 adb**（input / screencap / dumpsys / monkey）
- ⚡ **无障碍树**：`get_ui_state` 返回 `source=ax`，延迟约 12 ms（回退到 uiautomator dump 时约 2.8 s）
- 🎯 **确定性点击契约**：每次 `click` 都回报 `found` / `actionable` / `tapped` / `verified` 以及一句 `note`——点了没变化的会如实返回 `verified=false`，不假装成功
- 🖼️ **[实验性] 视觉元素匹配**：为任意图标或 Canvas 按钮登记一张模板图，之后用 `element_register` / `element_find` / `element_click` 按像素找到并点击它（NCC 金字塔匹配 + 歧义保护）。默认关闭——桥必须以 `MCP_EXPERIMENTAL=1` 启动，才会在 35 个工具之上多出这 3 个
- 🕐 **手机侧脚本网关**：`script_create` / `script_list` / `script_run` / `script_delete` / `script_schedule`——按天的 AlarmManager 触发，步骤支持 shell / speak / notify_pc
- 🗣️ **TTS**：SiliconFlow CosyVoice2（手机用 MediaPlayer 播放 wav，失败回退系统 TTS）
- 💻 **命令行工具**（`cli/mcp4android.ps1`）：中英双语引导式 install / pair / tcpip / config / restart / status / notify
- 🧩 **可选的应用学习模块**（`ai_mcp_server/app_learn/`）：延迟导入；把该目录删掉服务照样能启动，只是学习类工具会回报不可用

## 预编译 APK

每个 Release 都附带两个由对应 tag 构建、已签名的变体：

| 产物 | 包含 | 适用场景 |
|---|---|---|
| `mcp-for-android-0.2.2-lite.apk` | 不含隧道二进制 | 你已经装了 WireGuard（官方 App），或走局域网/USB 连接 |
| `mcp-for-android-0.2.2-full.apk` | `lib/arm64-v8a/libwgproxy.so` + `libwgtcp.so` | 你想直接用内置用户态隧道 |

两者用同一把自签发布密钥签名——用
`apksigner verify --print-certs <apk>` 校验（SHA-256 `202ce14c9d9a4ab93d2222fb12a5cf25a810f13596ff03e64cc63d582221f009`）。
这两个隧道二进制**不在源码树里**，详见[网络方案](#网络方案)。

## 环境要求

- 桥与工具链：Python 3.10+（实测 3.12 / 3.14）
- 应用：JDK 17 + Android SDK 34（compileSdk 34、build-tools 34）
- WireGuard 可选：手机也可以走局域网/热点接入

## 快速开始

### 1. 生成你自己的证书

仓库里**不带**任何证书。先生成一套两级 CA + 服务器证书：

```bash
cd ai_mcp_server/scripts
python3 gen_cert.py            # 写出 ../certs/{ca.crt,ca.key,server.crt,server.key}
```

- `ca.key` / `server.key` 要保密——它们已被 gitignore 排除。
- 构建应用前，必须把 CA 公钥放到 `AiAgentForAndroid/app/src/main/res/raw/server_ca.pem`（手机端会钉住这把 CA）。
- SAN 必须包含你实际会连的每一个主机（`IP:192.0.2.1`、你的域名等）。改 `gen_cert.py` 或传 `--san`。

### 2. 构建并安装手机应用

```bash
# 安卓侧：需要 JDK 17 + Android SDK（compileSdk 34、build-tools 34）
cd AiAgentForAndroid
gradlew assembleDebug
# 用 USB 或无线调试安装
adb install -r app/build/outputs/apk/debug/app-debug.apk
# 打开手机侧 adbd 的 TCP 端口（手机端 adb 执行依赖它；手机重启后要重跑）
adb tcpip 5555
```

> **WireGuard 说明**：内置隧道需要两个不随仓库发布的预编译二进制——
> 受影响的范围、以及两种不用 WG 的连接方式见[网络方案](#网络方案)。
>
> **预编译 APK**：每个 Release 附带两个已签名变体——`-lite`（不含隧道二进制）与
> `-full`（打包了预编译的 `libwgproxy.so` / `libwgtcp.so`，内置隧道开箱可用）。

### 3. 运行桥（在个人服务器上）

```bash
cd ai_mcp_server
python3 -m venv .venv && .venv/bin/pip install -r requirements.txt   # 钉住 mcp==1.29.0
export MCP_DEVICE_TOKEN=$(openssl rand -hex 16)      # 必须与手机端配置一致；空 = 不校验
export MCP_HOST=192.0.2.10                           # 手机拨入的地址（默认 127.0.0.1，只允许本机连）
export MCP_CERT_FILE=certs/server.crt
export MCP_KEY_FILE=certs/server.key
# 默认传输是 stdio；要让 MCP 客户端走网络，必须显式开 streamable-http
export BRIDGE_TRANSPORT=streamable-http
export BRIDGE_HOST=0.0.0.0
export BRIDGE_PORT=9500
.venv/bin/python bridge_mcp.py
# 手机 TLS 端点：<MCP_HOST>:9444 ；MCP 客户端：http://<BRIDGE_HOST>:9500/mcp
```

ECS 侧只跑一条 L4 转发（见 `docs/OPERATIONS.md` §4.1），不跑桥。

### 4. 配置手机

```bash
powershell -File cli/mcp4android.ps1 config <bridge-host> <device-token>
powershell -File cli/mcp4android.ps1 restart
```

### 5. 接入 MCP 客户端

```bash
hermes mcp add phone --url http://<bridge>:9500/mcp
# 或任意 MCP 客户端——工具举例：launch_app、click、script_run、tts_speak…
```

## 安全说明

- **仓库里没有任何证书。** 先跑 `gen_cert.py`，并确保 `*.key` 不进 git。
- 手机侧 socket 需要**设备令牌**（`MCP_DEVICE_TOKEN`）；电脑端通知接口同样需要令牌。
- `adbkey`（私钥）、`adbkey_wifi*` 与 TTS 密钥都被 gitignore 排除——永远不要提交密钥。
- 隧道的 WireGuard 私钥存在 SharedPreferences（`tunnel_private_key`），不会写进 APK。
- 手机 TTS 密钥：`app/src/main/assets/tts_key.txt`（已 gitignore），或直接依赖系统 TTS 回退。
- 代码里的默认端点都是占位符（`203.0.113.10` = RFC 5737 TEST-NET-3），请换成你自己的。

## 仓库结构

```
ai_mcp_server/        MCP 桥（FastMCP，stdio + HTTP，手机侧 TLS socket）
AiAgentForAndroid/    安卓应用（后台服务、adb 执行器、脚本网关、TTS）
cli/                  mcp4android.ps1（中英双语安装/配置命令行）
pc_notify/            Windows 气泡通知轮询端
deploy/               ECS systemd 单元 + 配置示例
docs/                 ARCHITECTURE.md、INTERFACE.md、OPERATIONS.md、CHANNELS.md、NOTIFICATION_HUB.md、WG_DIRECT_MODE.md
```

## 许可

本仓库以 **MIT** 许可发布（见 [LICENSE](LICENSE)）。
`AiAgentForAndroid/` 及其服务端原型来自 [lmee/mcp_for_android](https://github.com/lmee/mcp_for_android)，该仓库 README 声明 MIT（但仓库内没有 LICENSE 文件）——来源与第三方依赖详见 [NOTICE](NOTICE)。

**`full` 版 APK 额外打包了隧道二进制**：`libwgtcp.so`（本项目原创，MIT）与 `libwgproxy.so`（由 [wireproxy](https://github.com/windtf/wireproxy) v1.1.3 构建，**ISC** 许可，Copyright (c) 2026 Tsz Fung Wong）。ISC 要求随二进制保留版权与许可声明，因此 full APK 内带 `assets/THIRD_PARTY_LICENSES.txt`（wireproxy 及其 14 个 Go 依赖的许可全文）。仓库本身不含 `.so`，只有 Release 的 full 产物带；lite 版不含任何隧道二进制。

*原始原型：[lmee/mcp_for_android](https://github.com/lmee/mcp_for_android)。本项目在其之上增加了 TLS/令牌安全、MCP 桥、手机侧网关引擎、用户态隧道集成与远程控制能力。*
