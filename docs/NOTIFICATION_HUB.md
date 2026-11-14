# 通知中枢（2026-10-24，2026-10-26 改存储位置）

手机上的通知（QQ、微信、其它 App）统一收进一处。

**最终存储在你的个人服务器（家庭机 `192.0.2.10`）**；阿里云 ECS 只做流量中转，不落库：

```
手机 ──(本地先攒，可离线)──▶ ECS 192.0.2.1:9444 ──(phone-relay 纯 L4 转发)──▶ 家庭机 192.0.2.10:9444（最终存储）
                                                                                └─▶ 本机 Fedora（镜像，每 5 分钟）
```

```
手机 NotificationListener（com.jxev.ai.hub.HubNotificationListener）
   └─ 落本地 databases/hub.db（msg 表，UNIQUE(nkey,ts) 去重）
        └─ HubUploader 每 20s 取未上报的 200 条，封 hub_batch
             └─ 走既有 9444 加密 socket → ECS
                  └─ ECS `phone-relay.service`：socat L4 转发到 192.0.2.10:9444，不解密、不落库
                       └─ 家庭机 hub_server.py 入库（**最终存储**）→ 本机 info-hub 采集器（镜像）
```

## 手机侧

- 监听器：`com.jxev.ai.hub.HubNotificationListener`，库 `databases/hub.db`（表 `msg`：`ts/pkg/app/title/body/sub/cat/nkey/raw`，v2 起多一列 `synced`）。
- 授权：只能走 `adb shell cmd notification allow_listener com.jxev.ai/com.jxev.ai.hub.HubNotificationListener`；`settings put secure enabled_notification_listeners` 会被 MIUI 回退。
- 上报：`HubUploader`（20 秒一轮、200 条/批、`MCPSocketClient.sendJson`，走的是已建好的隧道 socket，不新开连接）；`MCPService.startHubPump` 负责拉起。
- 本地查询（不依赖服务器）：`hub_query` / `hub_stats` 两个 action。

## ECS（纯中转，不承担存储）

- `phone-relay.service`（systemd）：`socat TCP-LISTEN:9444,bind=192.0.2.1,fork,reuseaddr TCP:192.0.2.10:9444`，TLS 端到端，ECS 看不到明文。
- `mcp-bridge` 在 ECS 上 `disabled`，旧库已归档到 `archive/hub.db.20261026`（10322 行，留档不删）。
- 入库入口在 `mcp/mcp_interface.py` 的 `hub_batch` 分支：收到即入库并回 `hub_ack`，不加端口、不加服务。
- `bridge_mcp.py` 的 `hub_query` / `hub_stats` 读服务器库，返回值带 `"source": "server"`。

## 个人服务器 · 家庭机（最终存储）

- **长期库就在这里**：`~/.dsh/info-hub/hub.db`（`events` 表，`source='phone-hub'`），保留期由 `retention_days` 控制（默认 180 天，比服务器中转库长得多）。
- 采集器 `info-hub/src/infohub/collectors/phone_hub.py`（source=`phone-hub`）：经 SSH + wireproxy SOCKS 调 ECS 的 `hub_dump.py`，按 `ts` 升序取批，游标 `last_ts` 单调递增；只有取到才推进游标，取不到就下一轮重试。
- `hub_dump.py` 的 SQL 必须是 `WHERE ts > ? ORDER BY ts ASC LIMIT ?`；按最新取批会让游标一步跳过中间历史（踩过：第一轮只拿到 400 条）。
- 周期：`info-hub-collect.timer`（5 分钟一轮）。

## 验收判据

| 环 | 判据 |
|---|---|
| 手机 → ECS（中转） | ECS `hub.db` 里出现该条：`SELECT * FROM msg WHERE ts > (now-10min)` |
| ECS → 个人电脑（最终） | 本机 `~/.dsh/info-hub/hub.db` 的 `events` 出现 `source='phone-hub'` 且 payload 含同一条（这是"存住了"的判据） |
| 反向排查 | ECS 没有 → 看 `adb logcat -s HubUploader` 与 `synced` 标记；ECS 有而 info-hub 没有 → 看 SSH/wireproxy 与 timer 状态 |

## 现状数字（2026-10-24）

- ECS 中转库 3601 行（QQ 974 / bilibili 639 / 起点 286 / 支付宝 238）—— 可以清，清了不丢。
- 个人电脑 `phone-hub` 事件 4401 条（历史已回填，含真实 QQ 群消息）—— 这份是最终存储。

## v2 通用消息模型（2026-10-26）

从「只存手机通知」扩成「通用消息库」：**任何来源的文本消息 + 标签 + 附件**都能存，手机通知只是 `source='phone'` 的一种。

### 表

```sql
message(id, ts, source, kind, title, text, extra, nkey, recv_ts, UNIQUE(source,nkey,ts))
tag(msg_id, tag)                      -- 多标签，PRIMARY KEY(msg_id,tag)
attachment(msg_id, sha256, name, mime, size, path)   -- 内容寻址，落在 attachments/<sha[:2]>/<sha>
```

- `source`：`phone` / `wechat` / `qq` / `behavior` / `note` / `agent` …（自由取）
- `kind`：`notify` / `chat` / `report` / `event` / `note` …
- `extra`：JSON，手机通知放 `{pkg,app,sub,cat}`
- 标签约定：手机通知自动打 `pkg:<包名>` 和 `app:<应用名>`
- 附件按内容 sha256 去重，不重复占盘

### 工具（ECS/家庭机 MCP）

| 工具 | 说明 |
|---|---|
| `hub_put(text, title?, tags?, source=note, kind=note, ts_ms?, attachment_*)` | 写任意消息；`tags` 逗号分隔；附件传 base64 |
| `hub_query(since_hours, source?, tag?, kind?, pkg?, limit)` | 按来源/标签/类型/包名过滤，返回 tags + attachments |
| `hub_stats()` | 总量 + 按来源 + 按标签 Top30 + 附件数与字节 |

### 迁移

首次启动 `hub_server.py` 会：
1. 建三张表；
2. 把旧 `msg` 表（10367 行）整批搬进 `message`（source=phone、kind=notify、extra 收 pkg/app/sub/cat）；
3. 给没有标签的历史消息补 `pkg:` / `app:` 标签（幂等，只在 tag 表为空时跑）。

旧 `msg` 表保留原地不删。
