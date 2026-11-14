# 接口参考

> 更新：2026-11-12。分三层：**MCP 工具面**（给 agent）、**手机 socket 协议**（桥 ↔ 手机）、**消息中枢数据模型**（hub）。

## 1. MCP 接入

```jsonc
// MCP 客户端（Hermes / DSH / 任何支持 streamable-http 的）
{
  "url": "http://192.0.2.10:9500/mcp",   // 仅 wg 内网可达
  "transport": "streamable-http"        // 不支持 stdio：会被客户端 spawn 秒退
}
```

- 服务：`bridge_mcp.py`，FastMCP；依赖 `mcp==1.29.0`（版本必须 pin）。
- 绑定：`192.0.2.10:9500`（不是 127.0.0.1）。
- 健康检查：`systemctl is-active mcp-bridge`。

## 2. MCP 工具目录（35 个）

> 另有 3 个**实验性视觉元素工具**（`element_register` / `element_find` / `element_click`），
> 只在 bridge 以 `MCP_EXPERIMENTAL=1` 启动时注册（默认不出现在 `tools/list`），见 §8。

### 2.1 应用与系统

| 工具 | 参数 | 说明 |
|---|---|---|
| `launch_app` | `package_name` | 按包名启动 |
| `get_installed_apps` | — | 已安装应用（含启动入口） |
| `get_current_app` | — | 当前前台 package/activity |
| `open_url` | `url` | VIEW intent 打开链接/深链 |
| `battery_info` | — | 电量/充电/温度 |
| `wifi_toggle` | `enable: bool` | 开关 Wi-Fi |
| `brightness` | `level: 0-255` | 设亮度（会先关自动亮度） |
| `media_control` | `action` | play/pause/toggle/next/prev/stop |
| `volume_control` | `action, times=1` | up/down/mute/unmute |
| `clear_app_data` | `package_name` | `pm clear`，等同重置该应用（慎用） |

### 2.2 界面操作

| 工具 | 参数 | 说明 |
|---|---|---|
| `click` | `selector` / `id` / `text` / `desc` / `(x,y)` | 五选一；selector 支持 `id=xxx`、`text=xxx`、`class=xxx`、`desc=xxx` 或 JSON |
| `long_click` | 同 `click` | 长按 |
| `type_text` | `text, selector?, id?` | 先定位输入框再写入。**无 adbkey 时走无障碍 `ACTION_SET_TEXT`**：要求已有聚焦的可编辑节点（先 `click` 输入框），否则如实返回「没有聚焦的可编辑节点」；实测落在备忘录标题栏 |
| `swipe` | `x1,y1,x2,y2,duration=300` | 滑动 |
| `scroll` | `direction: down/up` | 翻页 |
| `press_back` / `press_home` / `press_recents` | — | 三大金刚键 |
| `launch_app` | `packageName` | 包名参数是 **packageName**（传 `package` 会报「缺少 packageName/package_name」）；无 adbkey 时走 PackageManager + Intent，返回 `source=system` |
| `open_url` | `url` | 无 adbkey 时走 Intent（`source=system`） |
| `get_ui_state` | — | 结构化界面树（currentPackage/Activity/可见文本/设备信息），结果在 `ui_state` 字段 |
| `screenshot` | — | 返回 PNG；失败返回文本错误 |

### 2.3 通知

| 工具 | 参数 | 说明 |
|---|---|---|
| `get_notifications` | — | 当前通知列表 |
| `dismiss_notification` | `key` | 清除指定通知 |

### 2.4 消息中枢（v2）

| 工具 | 参数 | 说明 |
|---|---|---|
| `hub_put` | `text, title?, tags?, source=note, kind=note, ts_ms?, attachment_b64?, attachment_name?, attachment_mime?` | 写任意来源消息（可带标签 + 一个附件） |
| `hub_query` | `since_hours=24, source?, tag?, kind?, pkg?, limit=50` | 按来源/标签/类型/包名过滤，返回 `tags` + `attachments` |
| `hub_stats` | — | 总量、按来源、标签 Top30、附件数与字节 |

`hub_query` 优先读服务器库，返回带 `source_store: "server"`；读失败才回落手机侧。

### 2.5 执行与脚本

| 工具 | 参数 | 说明 |
|---|---|---|
| `adb_shell` | `command` | 经手机三条通道之一执行 shell，返回 `{output, exitCode}` |
| `device_action` | `action_type, parameters={}` | 通用执行器，见 §3 |
| `script_list` | — | 手机端脚本列表（数据以手机为准） |
| `script_create` | `name, description?, steps?, schedule?` | steps 项：`{"shell": ...}` / `{"speak": ...}` / `{"notify_pc": {...}}` |
| `script_run` | `name` | 立即执行（手机离线返回「手机未连接」） |
| `script_delete` | `name` | 删除 |
| `script_schedule` | `name, time="HH:MM", enabled=true` | 每日定时（AlarmManager） |
| `script_enable` / `script_disable` | `name` | 启停定时 |

### 2.6 TTS

| 工具 | 参数 | 说明 |
|---|---|---|
| `tts_speak` | `text, voice?` | 生成语音并下发手机播放；系统 TTS 兜底 |

### 2.7 外部配置工具（可选）

除内置 35 个工具外，`bridge_mcp.py` 会读环境变量 `PHONE_TOOLS_JSON` 指向的 JSON，把其中的 `tools`
注册成额外 MCP 工具（不重名才注册；格式见 `deploy/phone_tools.example.json`）。
**当前部署挂了一个 `set_reminder`**（打开系统闹钟新建界面），不属于内置工具，也不计入 35。

## 3. `device_action` 动作表

`action_type` 全部由手机端 `AdbActionExecutor` 实现；`script_*` 由 `ScriptEngine` 接管；`volume` / `media_control` 的值（up/down/mute/play…）是**参数**不是 action_type。

| 类别 | action_type |
|---|---|
| 交互 | `click` `long_click` `swipe` `type_text` `scroll` `key_event` `wait_for_element` `scroll_probe`（[experimental]，见 §10.1） |
| 按键 | `press_back` `press_home` `press_recents` |
| 应用 | `launch_app` `get_installed_apps` `clear_app_data` `uninstall_app` `install_app` `open_app_settings` |
| 界面 | `get_ui_state` `screenshot` `get_screen_size` `set_rotation` `get_current_app` |
| 通知 | `get_notifications` `dismiss_notification` |
| 系统 | `adb_shell` `battery_info` `wifi` `bluetooth` `airplane_mode` `brightness` `volume` `media_control` `open_url` |
| 文件 | `file_read` `file_write` |
| 中枢 | `hub_query` `hub_stats` |
| 语音 | `tts_play` |
| 诊断 | `channel_status`（返回三条通道可用性 + 探针结果） |

## 4. 手机 socket 协议

传输：TCP + TLS1.3（手机侧 pin CA）；帧 = **一行一个 JSON**。
连接方向：**手机 → ECS:9444 →（L4）→ 服务器:9444**，服务器是 TLS 服务端。

### 4.1 手机 → 服务器

```jsonc
{"type":"handshake","deviceId":"...","timestamp":1793...,"token":"<device token>",
 "deviceInfo":{"model":"","manufacturer":"","osVersion":"","sdkVersion":34},
 "capabilities":["click","screenshot","hub_query", ...]}

{"type":"heartbeat","deviceId":"...","timestamp":1793...}

{"type":"response","requestId":"...","data":{"status":"success","success":true,"message":"..."}}

{"type":"hub_batch","source":"phone","items":[{"id":1,"ts":1793...,"pkg":"...","app":"...",
  "title":"...","text":"...","sub":"","cat":null,"key":"..."}]}
```

### 4.2 服务器 → 手机

```jsonc
{"type":"handshake_response","status":"ok"}          // 不 ok 则手机抛错、不重试成功态
{"type":"welcome","message":"...","device_id":"..."}
{"type":"heartbeat_response","timestamp":...}
{"type":"request","requestId":"...","actionType":"click","parameters":{...}}
{"type":"hub_ack","count":200,"inserted":198,"timestamp":...}
```

### 4.3 语义约定

- 握手失败（token 不匹配）：`handshake_response.status != ok`，手机关闭连接并按退避重连。
- 心跳：手机每 30s 发；**90s 收不到任何入站帧**即判半开，主动断开重连（1s→60s 退避）。
- `hub_batch`：手机每 20s、每批 ≤200 条，`synced=0` 的重试；`hub_ack.inserted` 只用于观测，不参与确认。
- 服务器侧 `_pick_device()` 取「第一个 connected 设备」，多设备时按连接顺序。

## 5. 消息中枢数据模型

```sql
message(id, ts, source, kind, title, text, extra, nkey, recv_ts, UNIQUE(source,nkey,ts))
tag(msg_id, tag)                                     -- 多标签
attachment(msg_id, sha256, name, mime, size, path)   -- 内容寻址，attachments/<sha[:2]>/<sha>
```

| 字段 | 说明 |
|---|---|
| `source` | `phone` / `wechat` / `qq` / `behavior` / `note` / `agent` …（自由取） |
| `kind` | `notify` / `chat` / `report` / `event` / `note` … |
| `extra` | JSON；手机通知放 `{pkg, app, sub, cat}` |
| 自动标签 | 手机通知会打 `pkg:<包名>`、`app:<应用名>` |

`hub_query` 返回结构：

```jsonc
{"id":10375,"ts":1793008240184,"source":"agent","kind":"note","title":"MCP 冒烟",
 "text":"来自 MCP 的通用消息","extra":null,"key":null,
 "tags":["agent","mcp-test"],
 "attachments":[{"name":"note.txt","mime":"text/plain","size":18,"sha256":"8f38..."}]}
```

## 6. 错误语义

| 情况 | 返回 |
|---|---|
| 手机离线 | `{"status":"error","error":"...手机未连接..."}` |
| 动作失败 | `data.status = "error"` + message |
| 点击定位不到元素 | 错误文本，不猜坐标 |
| 隧道未就绪 | 控制通道根本不启动（不是超时错误） |
| MCP 工具参数非法 | `{"status":"error","error":"click: 必须提供 ... 之一"}` |

## 7. 元素定位与点击确定性（2026-11-12）

`click` / `long_click` / `wait_for_element` / `get_ui_state` 的定位顺序：

1. **坐标** `(x, y)` —— 直接注入
2. **无障碍树**（进程内，几十毫秒）—— 需要 `KeepAliveAccessibilityService` 已启用且 `canRetrieveWindowContent=true`
3. **uiautomator dump**（回退，实测 2.77s/次）—— 无障碍关闭或树里没有该节点时

匹配语义：**精确 → 包含**；多命中时优先可点击（节点自身或祖先）的，其次取面积最小的；**未命中返回错误，不猜坐标**。

点击注入顺序：**无障碍 `ACTION_CLICK`**（不注入手势坐标、不需要 adb 通道）→ 被拒才回退 `input tap`（需要 adb）。
因此 `get_ui_state` / `click` / `long_click` / `wait_for_element` 在 `assets/adbkey` 缺失时仍可用；
其余动作（`adb_shell` / `screenshot` / `type_text` 等）仍依赖 adb 通道。

响应字段（把「确定性」摊开，别让调用方以为"找到文字=点得动"）：

| 字段 | 取值 | 含义 |
|---|---|---|
| `found` | true / false | 元素是否命中 |
| `source` | `coord` / `ax` / `dump` | 命中来源（`get_ui_state` 也返回此字段） |
| `actionable` | true / false | `ax`：节点自身或最近 3 层祖先 `isClickable()`（列表项的文字节点常常不可点、真正接手势的是父容器）；`dump`：XML `clickable` 属性；`coord`：恒 true（按坐标注入） |
| `tapped` | true / false | 事件是否成功注入 |
| `verified` | true / false / "unknown" | 点击后 350ms 对比界面文本指纹：变了=true，没变=false，取不到树="unknown" |
| `scrollable` | true / false | 命中元素自身或 3 层内祖先可滚动（它在不在一个能滚的列表/容器里） |
| `pageScrollable` | true / false | 当前页面有没有可滚动容器 |
| `pageCanScrollForward` / `pageCanScrollBackward` | true / false | 还能不能继续往下 / 往上滚（取自无障碍 action 列表：方向可用 = 还能滚） |
| `note` | string | 只在"点不动"时出现：说明是节点拒绝动作，还是根本没有可点击祖先 |
| `target` / `x` / `y` | — | 实际命中的文本与注入坐标 |

`get_ui_state` 返回 `ui_state`（JSON）：`{source, package, scrollable, canScrollForward, canScrollBackward, nodes:[{t,d,c,id,b,clk,scr,fwd,bwd,edit,dis,invis,kids}], latencyMs, truncated}`，
`scr` = 可滚动，`fwd`/`bwd` = 该节点还能否向前/向后滚；
`b` = `[left, top, right, bottom]`。无障碍关闭时回退成 uiautomator XML（`xml` 字段）。

实测延迟（小米真机，序列号已隐去 / Android 16，设置页 22 个文本节点）：`source=ax` 时 `latencyMs=12`，
回退 `uiautomator dump` 时 2.77s/次 —— 相差约 230 倍。

**已知点不动的类别**（明确不保证）：

- `FLAG_SECURE` 窗口（银行 / 密码框）：树里根本没有文本 → `found=false`
- 系统弹窗 / 权限框：树可能来自 SystemUI，点击会落到系统窗口而非目标 App
- WebView 里没有点击区域的纯文本：`tapped=true` 但 `verified=false`
- 游戏 / Canvas / 视频覆盖层：无节点，只能给坐标，`actionable` 不可知
- 元素在可视区外或被子窗口遮挡：坐标不可点，需先 `scroll`
- 键盘弹出遮挡目标：坐标可能落在键盘上

## 8. 实验性视觉元素（[experimental]，2026-11-12）

给「无障碍树里没有节点」的图标 / Canvas / 纯图片按钮用：注册一张模板图，之后按图找元素并点击。

**开关**：默认关闭。bridge 进程必须带 `MCP_EXPERIMENTAL=1` 启动才会注册这三个工具；未设置时 `tools/list` 里没有它们。注册点：`ai_mcp_server/visual/`（`register_visual_tools(mcp, send_action)`），由 `bridge_mcp.py` 在工具区末尾按 env 条件调用一次。

| 工具 | 参数 | 说明 |
|---|---|---|
| `element_register` | `name`, `package=""`, `threshold=70`, `bbox={x,y,w,h}`, `image_base64=""` | 注册模板：`bbox` 从当前屏幕裁剪，或 `image_base64`（PNG/JPEG，可带 `data:` 前缀）；`package` 为空取当前前台包名；`threshold` 1–100 |
| `element_find` | `name` | 当前屏幕找该元素；`found` 需同时满足 分数 ≥ 阈值、前台包名 == 注册包名、且第二候选分数落后 ≥ 5 分（见下方歧义规则） |
| `element_click` | `name` | 找到并点击；未命中不乱点 |

模板落盘：手机 `files/visual_elements/<name>.png`（灰度）+ `<name>.json`（元数据 package/threshold/width/height/mean/std/createdAt/matchCount），重启 App 后仍在；同名覆盖。

`element_find` / `element_click` 返回字段：`found/score/score2/ambiguous/threshold/x/y/width/height/centerX/centerY/candidates/package/registeredPackage/packageMatch/coarseFactor/matchMs/screenshotMs/totalMs/note`（`score2` = 空间上远离最佳位置的第二候选分数，0 表示没有），`source` 恒为 `visual`。`element_click` 额外返回 `actionable/tapped/verified/target`，与 §7 的 `click` 同形；`verified` 只可能是 true / false（点击后 350ms 文本指纹比对，取不到树时为 false 并写 note），不会出现 unknown。未命中时 `actionable=false, tapped=false`。

动作层：手机端 `ActionDispatcher` 按 `element_` 前缀分派，所以也可以走通用通道 `device_action(action_type="element_register"|"element_find"|"element_click"|"element_list", parameters={...})`（`element_list` 只在这条通道可用，不单独暴露成 MCP 工具）。

匹配语义：Rec.601 灰度 + 零均值 NCC（分母积分图），金字塔 8→4→2→1 粗搜 + 原图精修；`candidates` = 超过内部候选下限（NCC 0.40）的峰数，仅作参考。

**歧义规则（2026-11-12 实测补的）**：过阈值还不够，第二候选必须在空间上离最佳位置 ≥ 模板边长、且分数落后 ≥ 5 分（`VisualElementMatcher.AMBIGUITY_MARGIN`）。不满足则 `found=false`、`ambiguous=true`、`note="多个候选分数接近（N vs M），无法确定"`。加这条是因为实测踩到：Settings 首页滚到底部时，88x88 蓝牙图标模板对「辅助功能」图标打出 85 分、第二候选 81 分，旧逻辑直接 `found=true` 并按 85 分点了过去，结果打开的是无障碍设置页。

已知限制（实测）：
- 模板边长 8–200px（超过 200 会被拒绝）；
- 负样本（别的界面）分数地板约 52–60（1080x2400、88x88 模板实测），阈值低于 65 会误报；
- 无障碍 `takeScreenshot` 有约 1s 框架节流，连续调用会失败，手机端已做 3 次 × 550ms 重试；
- 点击用无障碍手势（`dispatchSwipe` 1px/40ms），不依赖 `assets/adbkey` 的 adb 坐标点击。**但要求 `res/xml/keepalive_a11y.xml` 里有 `android:canPerformGestures="true"`**：缺它时 `dispatchGesture` 返回 true 却什么都不做（实测 `capabilities` 从 161 掉到 129），表现是 `swipe/scroll` 报 success 但界面不动、`element_click` 的 `tapped=true` 而 `verified=false`；补上后同一段代码立刻恢复正常（Settings 首页蓝牙图标 → 点击进入蓝牙页，`verified=true`）。
- 判据不能只看 `success`：手势类动作必须以界面变化（UI 树文本指纹 / 截图 / `verified`）为准，返回 success 只说明派发调用没抛异常。

## 9. 版本

- 协议版本：`BRIDGE_PROTOCOL=3`（`/app/version` 可查）。
- 手机 app：`com.jxev.ai`，versionName 与 bridge 的 `APP_VERSION` 对应。
- 兼容原则：**加字段不改语义**；`hub_batch` 与 `request/response` 帧结构向后兼容。

## 10. 长截图与滚动定位（[experimental]，2026-11-12）

给「整页比屏幕长」的场景用：连续截图拼成一张长图；或滚到某个文字出现再点。**默认不启用**，只有显式调用才动。

**入口**：客户端脚本 `tools/long_screenshot.py`（只用标准库 + PIL/numpy，直接调 MCP 的 `device_action`，不改 bridge 协议）：

```bash
python3 tools/long_screenshot.py capture --max-scrolls 8 --out /tmp/long.png
python3 tools/long_screenshot.py scroll-to-and-click --text "问题反馈" [--backward]
```

底层动作：手机端 `device_action(action_type="scroll_probe", parameters={"forward": true})`（`AdbActionExecutor.scrollProbe` → `UiTreeProvider.scrollProbe`）。

### 10.1 `scroll_probe` 返回字段

`success / experimental(true) / source("ax") / direction("forward"|"backward") / moved / canScrollFurther / structureCanForward / structureCanBackward / before / after / how("gesture"|"none") / settleMs / latencyMs`

- `moved` 是**行为判据**：滚一次，比较滚动前后的几何指纹（文本 + 每个节点的 `bounds.top`）。不用 `isScrollable()` 之类结构信号，因为 RecyclerView 到底后常仍保留 forward action。
- 指纹必须带 bounds：只看文本时，列表里所有条目都还在树里（预加载）⇒ 文本集合不变 ⇒ 误判 `moved=false`（实测：我的设备页连滚 4 次，文本指纹每次相同，截图却每次都在动）。
- `settleMs`：滑完到指纹连续两次一致所花时间。手势时长 700ms（400ms 会带出 fling，单次能滚 2000+ px，相邻帧只剩一百多行重叠），滑完先等 300ms，再每 120ms 采样直到连续两次一致，上限 2s。
- `canScrollFurther` = 本次 `moved`（`get_ui_state` 里的同名字段在探测过之后也取这个值，见 `canScrollFurtherSource="probe"`）。

### 10.2 长截图拼接

流程：截首帧 → 循环 { `scroll_probe` → `moved=false` 就停（`reachedEnd=true`）→ 否则再截一帧 } → 逐对求重叠 → 拼接。

拼接：每帧压成行签名（每行 32 个分块灰度均值，`row_signature`），对相邻帧搜索重叠行数 `k`（粗搜步长 2 + ±3 精修），逐行"平均绝对差 ≤ `ROW_TOL`(4)"算一致率，取一致率最高的 `k`。

- 打分时剔除**当前帧顶部 chrome**（`CHROME_TOP_RATIO=0.12`，最多吃掉重叠区一半）：状态栏 + 折叠标题栏每帧都被半透明重绘，直接比会把真重叠打到 0.6 以下（实测 Settings 首页 0-1 帧真重叠 1682 行，不剔除时一致率仅 0.26）。
- 纯色区打折：重叠区里"有纹理"（行内分块标准差 > 3）的行占比 < 15% ⇒ 置信度 ×0.5，避免纯白区任意对齐都像。
- 小重叠更严：`k < 15% 帧高` 时要求置信度 ≥ 0.95（`SMALL_OVERLAP_CONF`），否则不认。
- `MIN_CONFIDENCE=0.85`：低于此值**不拼接**，返回原始帧路径 + `reason`，不产出垃圾图、不静默丢帧。
- 重叠行数 < `MIN_OVERLAP_ROWS`(48) 不认。

`capture` 返回：`experimental/frames/maxScrolls/reachedEnd/probes[]/confidence/overlaps[]/stickyHeaderSuspected/stitched/stitchedHeight/frameHeight/sumNonOverlap/heightDelta`；失败时另有 `reason/rawFrames[]`。

`reason` 只有这几种，按判定顺序：

| reason | 触发条件 | 是否产出图 |
|---|---|---|
| `unsupported` | 首帧无文本节点或整帧灰度均值 < 8；或 `moved>=2` 且**所有**探测的 `structureCanForward` 都是 `false`（画面在变但没有滚动容器：视频/动画/Canvas） | 不产出 |
| `page_not_scrollable` | 首探或循环中 `moved=false`，且不足 2 帧 | 不产出 |
| `overlap_uncertain` | 帧够但最佳重叠置信度 < `MIN_CONFIDENCE`(0.85) | 不产出，只回 `rawFrames[]` |
| `duplicate_frame` | 相邻帧全帧一致（`k>=帧高` 且置信度 1.0） | 丢弃重复帧 |

分支判据有离线回归：`python3 tests/long_screenshot_branches_test.py`（不需要真机，覆盖 unsupported 触发、正常页不误伤、单帧判 not_scrollable）。

### 10.3 `scroll_to_and_click`

"找得到"和"点得到"是两件事，判据用**手机端 `click` 的返回**而不是本地 dump：`dump()` 里的 `bounds/invis` 会滞后（滚完立刻 dump 还是旧坐标），而手机端 `find()` 内部用 `AccessibilityNodeInfo.isVisibleToUser()` 现算。

循环：`click{text}` → `tapped=true` 收工（`scrolls` = 已滚次数，首屏目标为 0）→ 返回 `found=true` 但 `actionable=false` ⇒ 目标存在但接不了手势，立即如实返回 → 否则滚一次再试。**连续 2 次 `moved=false` 才判到底**（单次假阴性来自回弹和树滞后）。

返回：`experimental/text/found/tapped/verified/scrolls/source/x/y/pageScrollable/attempts[]`（`attempts` 记录每轮 found/tapped/error，便于定位失败轮次）。

### 10.4 已知失真源（必须如实声明，不要写成"稳定可用"）

| # | 失真源 | 表现 | 当前处理 |
|---|---|---|---|
| 1 | 粘性头部 / 折叠标题栏 | 头部固定不动，拼出来的长图里头部只出现一次但内容有跳变 | 启发式 `stickyHeaderSuspected=true`（跳过顶部 chrome 后那一条两帧完全一致且内容确实滚过） |
| 2 | 滚动动画 / fling | 单次滚动距离不可控（400ms 手势实测滚 1850–2230px），相邻帧重叠不足 | 手势改 700ms 慢滑 + settle 等停稳；仍可能一次滚很远，靠小重叠严判据兜 |
| 3 | 无限加载（信息流） | 永远 `moved=true`，滚不到底 | `maxScrolls` 截断，`reachedEnd=false`，如实返回 |
| 4 | 视频 / 游戏 / Canvas / FLAG_SECURE | 截图黑屏，或内容在变但不在无障碍树里（`moved` 判据看不到画面变化） | 三条兜底：① 首帧无文本/整帧近黑 ⇒ `unsupported`；② `moved>=2` 且全部 `structureCanForward=false` ⇒ `unsupported`；③ 画面确实在变但重叠置信度不足 ⇒ `overlap_uncertain` 不拼接。实测夸克播放器全屏播放中：AX 树静止 ⇒ `frames=1, reason=page_not_scrollable`；同页在画面被判定为变化的那一轮 ⇒ `frames=4, confidence=0.787` 拒绝拼接 |
| 5 | 列表回弹（橡皮筋） | 到底/到顶再滚会回弹，瞬时 bounds 是回弹中间态 | settle 等指纹连续一致；连续 2 次 `moved=false` 才判到底 |
| 6 | 无障碍树滞后 | 滚完立刻 dump 得到旧 bounds / 旧 `invis` | `scroll_probe` 用 settle 采样；`scroll_to_and_click` 以手机端 `click` 返回为准 |
| 7 | 顶部 chrome 重绘 | 状态栏 + 折叠标题栏每帧半透明重绘，行签名不一致 | 打分时剔除顶部 12%；拼接时只保留首帧的 chrome |
| 8 | 重复帧 | 到底后仍取帧会拼出重复内容 | `moved=false` 就不再取帧；全帧一致（`k>=帧高` 且置信度 1.0）判 `duplicate_frame` 并丢弃 |

### 10.5 实测（<device-serial>，1080x2400，HyperOS 3 / Android 16）

- A2 长截图（设置首页）：`frames=4, reachedEnd=true, overlaps=[1711,548,1766], confidence=1.0, stitchedHeight=5575, sumNonOverlap=5575, heightDelta=0`（高度误差 0%）；产物 1080x5575，人工比对无重复/错位。
- A3 滚到再点：`scroll_to_and_click("问题反馈")` → `scrolls=2, found/tapped/verified=true`；`scroll_to_and_click("全部参数与信息")`（我的设备页）→ `scrolls=1`；首屏目标 `"WLAN"` → `scrolls=0`。
- 页底再截：`frames=1, reachedEnd=true, reason=page_not_scrollable`，不产生重复帧。
- S2 回归（同页，unsupported 规则加入后）：`frames=3, reachedEnd=true, probes=[moved×2 后 moved=false], overlaps=[763,1381], confidence=1.0, stitchedHeight=5056, heightDelta=0`。
- 视频页（`/sdcard/Movies/testvideo2.mp4` 2 分钟彩条，夸克 `com.ucpro.video` 全屏播放中，00:31/02:00）：`frames=1, reason=page_not_scrollable`，不产出拼接图——播放器页面无障碍树静止，`moved` 判据看不到画面变化，属失真源 #4 的已知边界。
- 离线分支判据：`tests/long_screenshot_branches_test.py` → 3/3 PASS（unsupported / 正常页不误伤 / 单帧 not_scrollable）。
- 说明：HyperOS 3 的「我的设备 / 全部参数与信息」两页没有「关于手机」这个条目（全页文本已核对），所以 A3 用同形状的等价目标（列表底部条目）验收。

