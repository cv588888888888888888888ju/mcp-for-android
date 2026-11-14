# 执行通道

> 更新：2026-10-26。三条通道都是「取到 `uid=2000(shell)`」的能力来源，都不需要 root；按固定顺序回退。

```
AdbLocalClient.execShell(cmd)
  ├─ 1) adb 5555           本机 adbd（需 `adb tcpip 5555`，重启即失效）
  ├─ 2) 无线调试 TLS        adbd 无线调试端口（重启后需手点一次开关）
  └─ 3) Shizuku            Shizuku 在跑且已授权（无需 adb 配对）
```

代码：`app/src/main/java/com/jxev/ai/client/` — `AdbLocalClient.java`（路由）、`WifiDebugChannel.java`（通道 2）、`ShizukuBridge.java`（通道 3）。

## 优先级与判据

| # | 通道 | 前置条件 | 失效判据 | 恢复动作 |
|---|---|---|---|---|
| 1 | adb 5555 | `adb tcpip 5555`；`127.0.0.1:5555` 有监听 | 端口不通（400ms 探针） | 插线跑一次 `adb tcpip 5555` |
| 2 | 无线调试 TLS | 设置里「无线调试」已开；`adbkey_wifi` 已配对过 | 端口发现失败 / TLS 握手失败 | 手点一次无线调试开关 |
| 3 | Shizuku | Shizuku 进程在 + 已授权本 app | binder 不通 / 权限未授 | 手动启动 Shizuku 并在弹窗点「始终允许」 |

前一条返回 `CHANNEL_DOWN:` 前缀的 IO 错误时，自动落到下一条。

## 通道 1：本机 adb 5555

```bash
adb tcpip 5555          # 手机插线时执行一次
adb shell ss -tln | grep 5555
```

重启手机后 `5555` 不会自动回来（`persist.adb.tcp.port` 写不进 shell 权限）。

## 通道 2：无线调试 TLS

三个坑，缺一个都连不上：

1. **三段握手**：明文发 `CNXN` → adbd 回 `STLS` → 客户端必须回一个 `STLS`（arg0=1）→ **在同一个 socket 上升 TLS1.3**；升级后服务端主动发 `CNXN`，客户端**不能再发** CNXN（多发一次会再收到 `STLS`）。
2. **客户端证书**：`KeyStore + KeyManagerFactory` 在 Android 上不会真发证书，必须自定义 `X509ExtendedKeyManager`。
3. **端口发现**：缓存端点 → mDNS `_adb-tls-connect._tcp`（有 VPN 时常常为空）→ 回环 `30000-65535` 扫描（约 6 秒）。端口每次开关/重启都变。

密钥材料（`app/src/main/assets/`）：`adbkey_wifi`（PKCS8 私钥）、`adbkey_wifi_cert.pem`（自签，CN=adbkey@device）、`adbkey_wifi.pub`。
由 PC 侧 `adb pair` 配对产生；配一次之后手机自己就能连，**不再需要 PC**。

**重启实测（2026-10-24）**：锁屏 110 秒后端口不存在，解锁后也不存在——HyperOS 不会自动恢复无线调试监听。用户手点一次开关后 app 自动发现并恢复。

## 通道 3：Shizuku

- 本机 Shizuku 13.5.x；启动方式「通过无线调试启动」需要先配对（配对码输入受 MIUI 通知样式影响，需改成原生样式）。
- 授权：`Shizuku.checkSelfPermission()` 只认 Shizuku 自己的弹窗；`pm grant moe.shizuku.manager.permission.API_V23` **不够**（dumpsys 显示 granted 但 check 仍返回 -1）。
- 重启后 Shizuku 自己也需要人拉起来。

## 诊断

MCP 调用 `device_action(action_type="channel_status")`，返回：

| 字段 | 含义 |
|---|---|
| `adb_port_open` | 通道 1 端口探针 |
| `shizuku_available` | 通道 3 是否可用 |
| `channel_used` | 本次实际用的通道（`adb` / `shizuku`） |
| `probe_success` / `probe_output` | 通道 1 探针执行结果 |
| `shizuku_probe_success` / `shizuku_probe_output` | 通道 3 探针执行结果 |

正常输出应含 `uid=2000(shell) context=u:r:shell:s0`。

## 已验证

| 场景 | 5555 | 无线调试 | Shizuku | 结果 |
|---|---|---|---|---|
| 全关 | 关 | 开 | 未运行 | `adb_shell` → `{"output":"2000\n<device-serial>\n","exitCode":0}` |
| 全关（探针） | 关 | 开 | 未运行 | `uid=2000(shell) context=u:r:shell:s0` |

设备：Redmi（真机序列号已隐去） / Android 16 / HyperOS OS3.0.308.0。

## 已知限制

- 重启后必须手点一次「无线调试」（已接受）。
- `adb usb` / `adb tcpip` 会重启 adbd，连带杀掉由它启动的 `shizuku_server`。
- 多设备时 adb 命令必须 `-s <serial>`。
- 无线调试在 VPN 存在时仍可用，但 mDNS 常为空，实际走端口扫描。
