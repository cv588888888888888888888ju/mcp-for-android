# =============================================================================
# mcp4android.ps1 — 手机 MCP 客户端 安装/配置 CLI（中英双语引导）
# =============================================================================
# 用法:
#   .\mcp4android.ps1 help               查看帮助（中文）
#   .\mcp4android.ps1 install            安装/升级 APK 到手机
#   .\mcp4android.ps1 pair               无线调试配对（输入配对码）
#   .\mcp4android.ps1 tcpip [port]       开启 adbd TCP 监听（默认 5555，重启后需重开）
#   .\mcp4android.ps1 config <host> <token>   写服务器配置（host:port / token）
#   .\mcp4android.ps1 restart            重启 App（不重置权限，am start 唤醒）
#   .\mcp4android.ps1 status             查看手机/连接状态
#   .\mcp4android.ps1 notify [--token X] 前台运行电脑通知轮询服务（Ctrl+C 停止）
# =============================================================================
param(
    [Parameter(Position = 0)][string]$Command = "help",
    [Parameter(Position = 1)][string]$Arg1 = "",
    [Parameter(Position = 2)][string]$Arg2 = ""
)

$ErrorActionPreference = "Stop"
$ADB = "C:\platform-tools\platform-tools\adb.exe"
if (-not (Test-Path $ADB)) { $ADB = "adb" }  # 若在 PATH 中
$APK = Join-Path $PSScriptRoot "..\AiAgentForAndroid\app\build\outputs\apk\debug\app-debug.apk"
$PREFS_TEMPLATE = Join-Path $PSScriptRoot "..\mcp_prefs_cloud.xml"

function Show-Help {
    Write-Host @"

==============================================
 手机 MCP 客户端 安装/配置工具 (mcp4android)
==============================================

 用法 / Usage:
   .\mcp4android.ps1 <command> [args]

 命令 / Commands:
   install             安装/升级 APK 到手机 (Install/upgrade APK to phone)
   pair                无线调试配对 (Wireless debugging pairing)
   tcpip [port]        开启 adbd TCP (default 5555; 重启后需重开)
   config <host> <token>   写服务器配置 (Set server host & token)
   restart             重启 App 服务 (Restart app, no permission reset)
   status              查看手机状态 (Show phone/adb/connection status)
   notify [--token X]  电脑通知轮询服务 (PC notification poller)

 示例 / Examples:
   .\mcp4android.ps1 install
   .\mcp4android.ps1 tcpip 5555
   .\mcp4android.ps1 config 192.0.2.1 <your-device-token>
   .\mcp4android.ps1 notify

 说明 / Notes:
   - 安装前请先用 USB 或无线调试连接手机 (Connect phone first)
   - 手机重启后 adbd TCP 会失效，需重新执行 tcpip (Run tcpip after reboot)
"@
}

function Get-DeviceSerial {
    $devices = & $ADB devices 2>$null | Select-String "\tdevice$"
    if (-not $devices) {
        Write-Host "`n[!] 未检测到已连接的手机 / No device detected." -ForegroundColor Yellow
        Write-Host "    请检查: 1) USB 调试已开启  2) 无线调试已连接 (adb connect <ip>:<port>)" -ForegroundColor Yellow
        Write-Host "    提示: 手机 设置→开发者选项→无线调试→使用配对码配对设备 / Pair via code first." -ForegroundColor Yellow
        return ""
    }
    return ($devices[0].ToString().Split("`t")[0]).Trim()
}

# 注意（PS 5.1 兼容）：Windows PowerShell 5.1 会把原生命令(adb)写入 stderr 的内容
# 包装成 NativeCommandError；在脚本级 $ErrorActionPreference="Stop" 下这会**终止脚本**，
# 即使命令本身成功 —— adb push / am start 的进度信息就是走 stderr 的。
# 因此每个调用 adb 的函数内部都局部把 $ErrorActionPreference 降为 "Continue"，
# 失败改由显式 $LASTEXITCODE / 返回值判断（2026-09-14 切内网时修正）。
function Invoke-Install {
    $ErrorActionPreference = "Continue"
    $serial = Get-DeviceSerial
    if (-not $serial) { return }
    if (-not (Test-Path $APK)) {
        Write-Host "[!] APK 不存在: $APK" -ForegroundColor Red
        Write-Host "    请先构建: cd AiAgentForAndroid; .\gradlew.bat assembleDebug (需 JDK11 + Android SDK)" -ForegroundColor Yellow
        return
    }
    Write-Host "[*] 安装 APK 到 $serial ... / Installing APK..."
    & $ADB -s $serial install -r $APK
    if ($LASTEXITCODE -eq 0) {
        Write-Host "[√] 安装成功 / Install OK. 启动服务..." -ForegroundColor Green
        & $ADB -s $serial shell am start -n com.jxev.ai/.MainActivity 2>$null
    } else {
        Write-Host "[!] 安装失败 / Install failed" -ForegroundColor Red
    }
}

function Invoke-TcpIp {
    $ErrorActionPreference = "Continue"   # 见文件上方 PS 5.1 / adb stderr 说明
    $serial = Get-DeviceSerial
    if (-not $serial) { return }
    $port = if ($Arg1) { $Arg1 } else { "5555" }
    Write-Host "[*] 开启 adbd TCP 端口 $port ... / Enabling adbd TCP on port $port..."
    & $ADB -s $serial tcpip $port
    Write-Host "[√] 完成 / Done. 提示: 手机重启后需重新执行本命令 / Rerun after phone reboot." -ForegroundColor Green
}

function Invoke-Config {
    $ErrorActionPreference = "Continue"   # 见文件上方 PS 5.1 / adb stderr 说明
    $serial = Get-DeviceSerial
    if (-not $serial) { return }
    $serverHost = $Arg1
    $token = $Arg2
    if (-not $serverHost) {
        Write-Host "[!] 用法 / Usage: .\mcp4android.ps1 config <host> <token>" -ForegroundColor Yellow
        return
    }
    Write-Host "[*] 写入服务器配置 host=$serverHost token=$token ... / Writing server config..."
    # 生成 prefs（保留 server_socket_port=9444）
    $xml = "<?xml version='1.0' encoding='utf-8' standalone='yes' ?><map><string name='server_host'>$serverHost</string><int name='server_port' value='9443' /><int name='server_socket_port' value='9444' /><string name='device_token'>$token</string></map>"
    $tmp = Join-Path $env:TEMP "mcp_prefs_$PID.xml"
    [System.IO.File]::WriteAllText($tmp, $xml, [System.Text.Encoding]::UTF8)
    & $ADB -s $serial push $tmp /data/local/tmp/mcp_prefs.xml 2>$null
    & $ADB -s $serial shell "run-as com.jxev.ai sh -c 'mkdir -p shared_prefs && cp /data/local/tmp/mcp_prefs.xml shared_prefs/mcp_prefs.xml'" 2>$null
    Remove-Item $tmp -ErrorAction SilentlyContinue
    Write-Host "[√] 配置已写入 / Config written. 重启 App 生效..." -ForegroundColor Green
    & $ADB -s $serial shell am start -n com.jxev.ai/.MainActivity --activity-clear-task 2>$null
}

function Invoke-Restart {
    $ErrorActionPreference = "Continue"   # 见文件上方 PS 5.1 / adb stderr 说明
    $serial = Get-DeviceSerial
    if (-not $serial) { return }
    Write-Host "[*] 重启 App 服务（不重置权限）/ Restarting app (no permission reset)..."
    & $ADB -s $serial shell am start -n com.jxev.ai/.MainActivity --activity-clear-task 2>$null
    Write-Host "[√] 已唤醒 / Done." -ForegroundColor Green
}

function Invoke-Status {
    $ErrorActionPreference = "Continue"   # 见文件上方 PS 5.1 / adb stderr 说明
    $serial = Get-DeviceSerial
    if (-not $serial) { return }
    Write-Host "`n=== 手机状态 / Phone status ===" -ForegroundColor Cyan
    & $ADB -s $serial shell "pidof com.jxev.ai" | ForEach-Object { Write-Host "App 进程 / PID: $_" }
    Write-Host "--- 最近连接日志 / Recent socket log ---"
    & $ADB -s $serial shell "logcat -d -s MCPSocketClient:* | tail -4" 2>$null
    Write-Host "--- 服务器配置 / Config (run-as) ---"
    & $ADB -s $serial shell "run-as com.jxev.ai cat shared_prefs/mcp_prefs.xml" 2>$null
}

function Invoke-Notify {
    $script = Join-Path $PSScriptRoot "..\pc_notify\pc_notify.py"
    if (-not (Test-Path $script)) { Write-Host "[!] 缺少 $script" -ForegroundColor Red; return }
    $tokenArg = @()
    if ($Arg1 -eq "--token" -and $Arg2) { $tokenArg = @("--token", $Arg2) }
    Write-Host "[*] 启动电脑通知轮询（Ctrl+C 停止）/ Starting PC notification poller..."
    python $script @tokenArg
}

switch ($Command.ToLower()) {
    "install" { Invoke-Install }
    "pair"    { Write-Host "[*] 无线调试配对说明 / Pairing guide:" -ForegroundColor Cyan
                Write-Host "    1) 手机: 设置→开发者选项→无线调试→使用配对码配对设备" -ForegroundColor Yellow
                Write-Host "    2) 记录手机上显示的 IP:端口 和 6 位配对码" -ForegroundColor Yellow
                $ipPort = Read-Host "    输入 IP:端口 (如 192.168.1.5:37123)"
                $code = Read-Host "    输入 6 位配对码"
                & $ADB pair $ipPort $code
                if ($LASTEXITCODE -eq 0) {
                    $ip = ($ipPort -split ":")[0]
                    Write-Host "[√] 配对成功 / Paired. 连接中..." -ForegroundColor Green
                    & $ADB connect ("${ip}:5555") 2>$null
                } else {
                    Write-Host "[!] 配对失败（配对码 2 分钟内有效，请重试）/ Pairing failed (code expires in 2min)" -ForegroundColor Red
                } }
    "tcpip"   { Invoke-TcpIp }
    "config"  { Invoke-Config }
    "restart" { Invoke-Restart }
    "status"  { Invoke-Status }
    "notify"  { Invoke-Notify }
    default   { Show-Help }
}
