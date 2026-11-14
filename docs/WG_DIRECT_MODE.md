# WG 直连模式（2026-09-29）

> ⚠️ **结构已变（2026-10-26）**：ECS 不再跑 bridge，只做 `phone-relay` 纯 L4 转发；bridge 与最终存储都在个人服务器 `192.0.2.10`。本文保留为「wg 监听兜底模式」的历史说明，当前拓扑见 [ARCHITECTURE.md](ARCHITECTURE.md)。

> 通道总览（本地 adb 5555 / 无线调试 TLS / Shizuku）见 [CHANNELS.md](CHANNELS.md)；本文只讲 wg 内网直连这条。

手机 MCP 解除对 ECS 的单向绑定：手机在 WireGuard 内网上开 TLS 监听，任何 wg peer
可按需直连；后台可达性通过看门狗 + wakelock + 白名单保障。

## 架构（双通道并存）

```
通道 A（出站，保留）：手机 MCPSocketClient ──TLS──> ECS bridge 192.0.2.1:9444（accept 模式）
通道 B（直连，新增）：任意 wg peer（bridge/脚本）──TLS──> 手机 MCPListenServer 0.0.0.0:9444
                      └─ 应用层过滤：仅接受 192.0.2.0/24 来源；token 握手 + CA pin
```

- 手机 wg IP 固定 `192.0.2.4`；证书 SAN 含 `192.0.2.1 / 192.0.2.4 / 127.0.0.1 / phone`（有效期至 2028-12）。
- peer→peer 流量经 hub（ECS）转发：`wg-relay.service` 持久化两条 iptables 规则
  （FORWARD wg0→wg0 ACCEPT；POSTROUTING SNAT 192.0.2.0/24→192.0.2.4 改写源为 192.0.2.1，
  绕过手机端 wg AllowedIPs 只含 192.0.2.1 的限制）。
- PC 直连手机：`192.0.2.4:9444`（经 hub SNAT 中继，源被改写为 192.0.2.1）。

## Android 端改动（com.jxev.ai）

| 文件 | 内容 |
|---|---|
| `server/MCPListenServer.java` | TLS 服务端（PKCS12=raw/phone_keystore.p12, 密码 mcpphone）；0.0.0.0 绑定 + wg 来源过滤；同协议 handshake/request/heartbeat；pc_notify 广播回执 |
| `server/WatchdogReceiver.java` | 10 分钟精确闹钟看门狗：进程被杀→重新拉起 MCPService，自续订 |
| `client/ActionDispatcher.java` | 出站/监听两会话共用的动作分发（script_* → ScriptEngine，其余 → AdbActionExecutor） |
| `server/KeepAliveOverlay.java` | 2px 透明悬浮窗（进程升"可感知"，需 SAW 授权） |
| `server/KeepAliveActivity.java` | 1px 透明 Activity：灭屏拉起/亮屏自毁（TOP_SLEEPING） |
| `server/KeepAliveAccessibilityService.java` | **零功能无障碍服务（灭屏防冻结的关键，唯一有效豁免）** |
| `server/MCPService.java` | 启动监听 + PARTIAL_WAKE_LOCK(`MCP:listen`) + 悬浮窗/1pxActivity/灭屏监听 + 组合式 pc_notify 通道 + 看门狗排定 |
| `ConfigManager.java` | `listen_enabled`(默认 true) / `listen_port`(默认 9444) prefs 键 |
| `AppBase.java` | `java.net.preferIPv4Stack=true`（见坑 1） |
| Manifest | +WAKE_LOCK 权限、+WatchdogReceiver |

prefs（`run-as com.jxev.ai` 写 shared_prefs/mcp_prefs.xml，需重建进程生效）：
`listen_enabled` / `listen_port` / `server_host` / `server_socket_port` / `device_token`。

## 使用方式（任意 wg peer）

```bash
# 独立验收探针（TLS+token+adb_shell+get_ui_state，纯标准库）
python scripts/test_direct_client.py --host 192.0.2.4 \
    --token <MCP_DEVICE_TOKEN> [--screenshot out.png]

# 完整 MCP：bridge 拨号模式（不依赖手机出站、不绑定 ECS）
PHONE_DIAL_HOST=192.0.2.4 PHONE_CA_FILE=certs/ca.crt MCP_DEVICE_TOKEN=<token> \
    BRIDGE_TRANSPORT=streamable-http BRIDGE_HOST=0.0.0.0 BRIDGE_PORT=9500 \
    python ai_mcp_server/bridge_mcp.py
```

## 🔴 关键坑（全部实测）

1. **Java 指定-IP 监听收不到 wg 入站**：`createServerSocket(port, 8, wgIp)` 在 Android
   落成 AF_INET6-mapped（ss 显示 `[::ffff:192.0.2.4]`），本机可连、经 wg tun 全部超时。
   必须绑 `0.0.0.0`（同 adbd/Termux），wg-only 语义用 accept 后的来源过滤实现。
2. **MIUI 灭屏冻结进程**：灭屏 ~30-60s 后进程冻住（进程在、无日志、无心跳、listener
   静默）。诊断金标准：`cat /sys/fs/cgroup/apps/uid_10442/pid_<pid>/cgroup.freeze`
   （1=冻结 0=正常，对照 `dumpsys power | grep mWakefulness`）。
   ✅ **最终解法（2026-09-29 实测灭屏探针 3/3 通过）——三件套保活**：
   a) `KeepAliveAccessibilityService`：零功能无障碍服务（canRetrieveWindowContent=false、
      不消费事件），被系统永久绑定 → HyperOS 不冻结 a11y 进程。**这是唯一有效的灭屏豁免**。
      adb 启用：`settings put secure enabled_accessibility_services com.jxev.ai/com.jxev.ai.server.KeepAliveAccessibilityService`
      + `settings put secure accessibility_enabled 1`（appops/install -r 会重置 SAW，
      a11y 的 secure settings 授权不受 reinstall 影响）。
   b) `KeepAliveOverlay`：2px 透明 TYPE_APPLICATION_OVERLAY（进程升"可感知"级），
      权限 `appops set com.jxev.ai SYSTEM_ALERT_WINDOW allow`（install -r 后需重授）。
   c) `KeepAliveActivity`：1px 透明 Activity，灭屏时由服务拉起、亮屏自毁（TOP_SLEEPING）。
   实测 a) 单项即足以解冻（cgroup.freeze=0），b)/c) 加固。注意：HyperOS 对"可感知"级
   进程照样冻结——b)/c) 单独无效，仅 a) 有效。
   🔴 **已实测死胡同（勿重试）**：appops 全 allow、forced_app_standby_enabled=0、
   cloud_memory_freeze_whitelist、cloud_memFreeze_control、cached_apps_freezer（本就
   disabled）、无 root 动不了 PowerKeeper DB、安全锁屏挡死 adb UI 代点。
   **有效兜底**：`settings put global stay_on_while_plugged_in 7`（充电不灭屏，重启生效）。
3. **MIUI 拦 START_STICKY 重启**：run-as kill 后进程不会自动复活 → 看门狗闹钟兜底。
4. **hub 转发**：ECS FORWARD 默认 DROP（只有 docker 规则），peer→peer 必须
   `iptables -I FORWARD 1 -i wg0 -o wg0 -j ACCEPT`；手机 wg AllowedIPs 只含 192.0.2.1
   时，转发包（src=对端 IP）会被手机 cryptokey 路由丢弃 → hub SNAT 改写源为 192.0.2.1。
5. **/proc/net/tcp 误判**：app 的 socket 在 shell 视角可能完全不可见（对 adbd 可见），
   不能用它判断 app 连接状态；用 app 日志/通知文本/ECS `ss -tn`。
6. **toybox nc 监听**：`nc -l`/`-L -s IP -p PORT` 需先 `ss` 确认真的起来了再做对端测试
   （语法错会静默退出，造成假阴性）。

## 功能集（2026-09-29 对标开源 mobile-mcp，全部 adb shell 实现，零无障碍依赖）

AdbActionExecutor 新增 18 个 action（灭屏经 wg 直连全量验收 19/19 通过）：

| action | 参数 | 说明 |
|---|---|---|
| get_screen_size | {} | 分辨率+密度 |
| open_url | {url} | VIEW intent 打开链接/深链 |
| key_event | {code} | 任意 keycode |
| volume | {action: up/down/mute/unmute, times} | 音量 |
| media_control | {action: play/pause/toggle/next/prev/stop} | 系统媒体键 |
| wifi / bluetooth | {enable} | svc 开关 |
| airplane_mode | {enable} | settings+广播 |
| brightness | {level: 0-255} | 自动关自动亮度 |
| get_current_app | {} | mCurrentFocus 解析（灭屏可能解析不到） |
| battery_info | {} | 电量/充电/温度 |
| clear_app_data / uninstall_app | {packageName} | pm clear / pm uninstall |
| install_app | {apkPath} | pm install -r（apk 先到设备） |
| file_read / file_write | {path[, content]} | write 走 base64 管道规避转义，≤20KB |
| wait_for_element | {text/id/desc/selector, timeout} | 轮询 uiautomator dump（默认 15s） |
| set_rotation | {lock, orientation} | 0=竖 1=横；lock=false 恢复自动 |
| open_app_settings | {packageName} | 应用详情页 |

- 剪贴板未实现：Android 10+ 后台应用系统级禁止读写剪贴板，本机 `cmd clipboard` 不存在。
- bridge 新增 MCP tools：`device_action(action_type, parameters)` 通用执行器（全量动作）+
  open_url / media_control / volume_control / battery_info / get_current_app / wifi_toggle /
  brightness / clear_app_data 专用工具。
- 验收脚本：`scripts/test_new_features.py`（灭屏经 wg 直连跑全量）。

## 部署状态（2026-09-29）

- APK v4 已装机（监听+wakelock+看门狗）；ECS hub 转发/SNAT 已生效并持久化（wg-relay.service）。
- 已设置 `settings put global stay_on_while_plugged_in 7`（充电时屏幕不休眠，重启生效）
  → 插电场景（手机长期 USB 连电脑充电）后台访问持续可用，实测后台 200s/15min 探针通过。
- 手机带安全锁屏，adb 无法代做 MIUI 白名单。**待用户完成（拔电场景唯一解）**：
  1. 解锁手机 → 运行 `scripts\open_mcp_appinfo.bat` → 点「省电策略」→「无限制」
  2. 运行 `scripts\open_autostart.bat` → 允许 MCP客户端 自启动
  3. 灭屏后运行 `scripts\verify_screenoff.bat`，3 行 [OK] 即完成验收。
