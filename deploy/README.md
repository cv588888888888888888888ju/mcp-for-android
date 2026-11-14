# mcp_for_android ECS 部署指南


## 1. 准备

- ECS 实例：WireGuard 内网地址 `192.0.2.1`（手机 `192.0.2.4` / PC `192.0.2.3` 经 wg 隧道接入）
- Python 3.10+（系统自带即可）
- **无需**在阿里云控制台对 9444 / 18999 放行公网（它们只绑 `192.0.2.1`，公网放行也无用且应撤销）
- 已 clone 仓库到 `/opt/mcp_for_android`

## 2. 生成证书（一次性）

```bash
cd /opt/mcp_for_android/ai_mcp_server
python3 scripts/gen_cert.py        # 幂等；文件已存在则跳过
ls -la certs/
# 应有：ca.crt / ca.key / server.crt / server.key
```

证书包含 SAN：
- `IP:192.0.2.1`（ECS WireGuard 内网地址 ← 手机/PC 默认连接目标）
- 旧公网 IP 的 SAN 一条（过渡期保留，旧客户端不炸；不再硬编码在脚本里，
  需要时用 `MCP_LEGACY_SAN="IP:x.x.x.x"` 注入）
- `DNS:mcp.example.com`（用户域名，可选）
- `DNS:localhost` / `IP:127.0.0.1`（本地调试）
- `IP:192.0.2.1`（本机 Windows 移动热点网关，历史条目）

有效期：825 天（公网证书惯例）。

> 注意：`server_ca.pem`（手机端 pin 的 CA）需要手动拷贝到 `AiAgentForAndroid/app/src/main/res/raw/server_ca.pem`——
> 仓库不包含任何证书文件。重新生成证书后必须重打 APK 并安装。

## 3. 安装依赖

推荐 `uv`：

```bash
cd /opt/mcp_for_android/ai_mcp_server
uv venv .venv
source .venv/bin/activate
uv pip install -r requirements.txt
```

或 `pip`：

```bash
python3 -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt
```

## 4. 设置 token（强烈建议）

```bash
# 生成一个强随机 token
export MCP_DEVICE_TOKEN="$(python3 -c 'import secrets;print(secrets.token_urlsafe(32))')"
echo "MCP_DEVICE_TOKEN=$MCP_DEVICE_TOKEN" | sudo tee /etc/mcp_for_android.env
sudo chmod 600 /etc/mcp_for_android.env
```

把同样的 token 填到手机端 App 的「服务器配置 → token」输入框（与 `server_host` / `server_port` 一起保存到 `mcp_prefs`）。

## 5. systemd 单元

把 `deploy/mcp-server.service` 拷到 `/etc/systemd/system/`，然后：

```bash
sudo cp deploy/mcp-server.service /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable --now mcp-server.service
sudo systemctl status mcp-server.service
```

> `mcp-server.service` 默认从 `/etc/mcp_for_android.env` 读 `MCP_DEVICE_TOKEN`。可用 `EnvironmentFile=` 注入。

## 6. 验证

```bash
# TLS 握手（应看到 "Verify return code: 0 (ok)"）
openssl s_client -connect 192.0.2.1:9444 -servername mcp.example.com \
    -CAfile /opt/mcp_for_android/ai_mcp_server/certs/ca.crt </dev/null

# 纯 IP 连接（手机端用法，无 SNI）
openssl s_client -connect 192.0.2.1:9444 \
    -CAfile /opt/mcp_for_android/ai_mcp_server/certs/ca.crt </dev/null

# 一键自检（脚本）
bash deploy/check.sh 192.0.2.1 9443 9444 "$MCP_DEVICE_TOKEN"
```

## 7. 与 caddy(80/443) 共存

- caddy/nginx 继续监听 80/443（已有 HTTPS 站点）
- 新端口 9443/9444 与它们完全独立，不影响现有站点
- DNS 解析 `mcp.example.com` 时如要走 caddy，**不要**给该域名加指向 ECS 公网 IP 的 `A` 记录，保持原 CDN/代理指向不变

## 8. 防火墙 / 安全组（**用户在控制台操作**）

当前架构下 9444 / 18999 **只监听 WireGuard 内网 `192.0.2.1`**，公网无法访问，因此安全组**不需要**为它们放开入方向。

| 端口 | 协议 | 来源 | 备注 |
|------|------|------|------|
| 9444 | TCP | — | **应撤销** 0.0.0.0/0 放行规则（改绑内网后已无用） |
| 18999 | TCP | — | **应撤销** 0.0.0.0/0 放行规则（同上） |
| 9443 | TCP | — | 该服务已下线，可一并撤销 |

**不要**改 22/80/443 已有规则。

## 9. 客户端

- 把自己生成的 `ca.crt` 拷成 `AiAgentForAndroid/app/src/main/res/raw/server_ca.pem` 后打进 APK
- 启动 App，填 `192.0.2.1:9444` + token → 「保存并重连」
  （或用 CLI：`.\cli\mcp4android.ps1 config 192.0.2.1 <token>`）
- 手机必须先启用 WireGuard 隧道（`tun0 = 192.0.2.4/32`），否则 9444 不可达
- MCPSocketClient 会走指数退避自动重连（断网/WiFi 切换不需手动）


## 10. Phone MCP Bridge（标准 MCP Server 暴露手机能力）

> 让 Hermes 等外部 MCP 客户端通过 stdio JSON-RPC
> 调用手机能力（点击/输入/启动/读界面），而不是只能在 App 上手动输入指令。

### 10.1 架构

```
Hermes (MCP 客户端)
   ↓ 标准 MCP 协议 (stdio JSON-RPC)
bridge_mcp.py (FastMCP stdio server)  ← 本机独立进程
   ↓ 复用 ai_mcp_server.mcp.mcp_interface.MCPServer 逻辑
   ↓ TLS 192.0.2.1:9444 监听 + 设备握手 + token（仅 WireGuard 内网）
手机 App (执行端, 主动出站 TLS 连接)  ← 现有 Java 代码零改动
```

手机 App 的 `MCPSocketClient.handleRequest` 已支持服务器下发动作（`{type:request, actionType, parameters}` → 执行 → 回 `{type:response, data:{...}}`），bridge 只做"MCP tool → DeviceConnection.send_request"的薄映射。

### 10.2 启动

bridge 是一个独立进程，监听 9444（TLS 包裹，使用 `certs/server.{crt,key}`），同时通过 stdio 或 streamable-http 与 MCP 客户端通信。手机 App 已配置 `server_host=本机IP, server_socket_port=9444` 时自动出站连入。

```bash
cd ai_mcp_server

# 必选：与手机端握手 token 一致（空字符串=不校验）
export MCP_DEVICE_TOKEN="test-token-123"

# 可选：覆盖证书路径
# export MCP_CERT_FILE="certs/server.crt"
# export MCP_CERT_KEY="certs/server.key"

# 可选：覆盖端口（默认 9444）
# export PHONE_BRIDGE_PORT=9444

# 启动（前台阻塞，等待 MCP 客户端连 stdin/stdout）
python bridge_mcp.py
```

启动后 FastMCP stdio server（`name=phone-controller`）就绪，向 stderr 打印：

```
[TLS] TLS 已启用 cert=.../server.crt key=.../server.key
[bridge] MCPServer 监听 192.0.2.1:9444，token=启用
[bridge] FastMCP stdio server 启动（name=phone-controller），等待 MCP 客户端调用…
```

### 10.3 暴露的 MCP Tools

| Tool 名 | 作用 | 关键参数 |
|---------|------|---------|
| `launch_app` | 启动应用 | `package_name: str` |
| `click` | 点击 | `selector` / `id` / `text` / `desc` / (`x`,`y`) |
| `long_click` | 长按 | 同 click |
| `type_text` | 输入文本 | `text: str`, `selector` 或 `id` |
| `swipe` | 滑动 | `x1, y1, x2, y2, duration=300` |
| `scroll` | 滚动 | `direction="down"`（"up" / "down"） |
| `press_back` | 返回键 | — |
| `press_home` | Home 键 | — |
| `press_recents` | 最近任务 | — |
| `get_ui_state` | 抓取当前界面 | —（返回 `ui_state` 字段） |
| `get_installed_apps` | 已装应用 | —（返回 `installed_apps` 字段） |

返回结构（统一）：

```json
{
  "status": "success" | "error" | "timeout" | "no_device",
  "action_type": "click",
  "params": {...},
  "request_id": "uuid",
  "data": {...手机原始 data...},
  "error": null,
  "ui_state": {...},         // 仅 get_ui_state 解析后
  "installed_apps": [...]    // 仅 get_installed_apps 解析后
}
```

### 10.4 Hermes 接入（由 Hermes 主会话执行）

在 Hermes 主会话中执行：

```bash
hermes mcp add phone-bridge -- python ai_mcp_server/bridge_mcp.py
```

> 由于 FastMCP stdio 通过子进程 stdin/stdout 通信，必须把 token 等环境变量
> 注入到子进程——推荐在 shell rc 里 `export MCP_DEVICE_TOKEN=...` 后再启 Hermes，
> 或在 Hermes 配置里加 `env: { MCP_DEVICE_TOKEN: test-token-123 }`。

### 10.5 自检脚本（手机必须在线）

```bash
cd ai_mcp_server
python3 scripts/test_bridge.py
```

会依次调 11 个 tool 中的关键几个，并用 `adb shell dumpsys activity activities` 验证 `launch_app` / `press_back` 的物理效果。

### 10.6 单一入口

- 本仓库只保留 bridge 一个进程入口（`ai_mcp_server/bridge_mcp.py`）；历史上的 main.py / Flask / LLM 路由已移除
- 9444 同一时刻只能有一个监听者，重复启动会报端口占用
