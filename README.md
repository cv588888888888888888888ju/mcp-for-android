# mcp-for-android — Turn Your Android Phone into an MCP Capability Provider

**English** | [简体中文](README.zh-CN.md)

> **AI-assisted project**: This codebase was developed with AI coding agents (Hermes Agent + OpenAI Codex). The phone-side gateway engine, adb protocol client, and MCP bridge were co-authored with AI assistance and verified end-to-end on real devices.

Control your Android phone from any MCP client (Claude, Hermes, custom agents) — launch apps, tap/type/swipe, take screenshots, read notifications, run shell commands, **speak via TTS**, and run **scheduled scripts on the phone itself** (works offline, like a mini-gateway).

> **Version** `0.2.2` · [Releases](https://github.com/cv588888888888888888888ju/mcp-for-android/releases) · MIT licensed

## Architecture

```
┌─────────────────────────────────────────────────────┐
│  MCP client (Hermes / DSH / any MCP client)         │
└──────────────────────────┬──────────────────────────┘
                           │ streamable-http  9500
                           ▼
┌──────────────────────────┴──────────────────────────┐
│  Personal server  192.0.2.10:9500                   │
│  bridge_mcp.py + hub_server.py                      │
│  (control plane + final storage)                    │
└──────────────────────────┬──────────────────────────┘
                           │ TLS 9444 + device token
                           ▼
┌──────────────────────────┴──────────────────────────┐
│  ECS relay  192.0.2.1:9444                          │
│  phone-relay: socat, L4 only                        │
│  (no decryption, no storage)                        │
└──────────────────────────┬──────────────────────────┘
                           │ built-in user-space WireGuard tunnel (UDP 51820)
                           ▼
┌──────────────────────────┴──────────────────────────┐
│  Phone  com.jxev.ai  (background service, no UI)    │
│  - built-in user-space WireGuard tunnel             │
│  - three interchangeable execution paths            │
│  - notification hub + script engine + TTS           │
└─────────────────────────────────────────────────────┘
```

- **Phone = execution and collection side.** It dials out its own user-space WireGuard tunnel (no system VPN slot, no root). The long-lived connection is outbound only — the phone exposes no inbound port at all.
- **Personal server = control plane and final storage.** **The ECS box is only a traffic relay** (a single TCP 9444).
- **MCP clients** talk to the server on `9500` only; they never touch the phone directly.

Documentation map (the `docs/` files are currently written in Chinese):

| Document | Contents |
|---|---|
| [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) | Topology, component responsibilities, ports, data plane, failure semantics |
| [docs/INTERFACE.md](docs/INTERFACE.md) | MCP tool catalog, `device_action` action table, socket protocol, hub data model |
| [docs/OPERATIONS.md](docs/OPERATIONS.md) | Build/install, configuration keys, three-node deployment, failure matrix, backups |
| [docs/CHANNELS.md](docs/CHANNELS.md) | Preconditions, pitfalls and diagnostics for the three execution paths |
| [docs/NOTIFICATION_HUB.md](docs/NOTIFICATION_HUB.md) | Notification hub pipeline and the v2 generic message model |
| [docs/WG_DIRECT_MODE.md](docs/WG_DIRECT_MODE.md) | WireGuard direct-dial mode (the bridge dials the phone) |

## Network options

The phone always dials **out** to the bridge over TLS 9444 — nothing listens on the phone. WireGuard is **optional**: pick whichever transport below fits.

> Every IP in this repository is a placeholder (`192.0.2.0/24` = RFC 5737 TEST-NET-1). Replace them with your own.

### Where WireGuard is used

| Where | What it does | WG required? |
|---|---|---|
| `TunnelManager` + `libwgproxy.so` / `libwgtcp.so` (phone) | built-in user-space tunnel; the outbound TLS 9444 rides it (no system VPN slot, no root) | optional |
| `MCP_HOST` (bridge listen address) | address the phone dials into | only if you bind it to a WG address |
| `deploy/phone-relay.service` (socat) | L4 relay in front of the bridge | only in the relay topology |
| `PHONE_DIAL_HOST` (WG direct mode, see [docs/WG_DIRECT_MODE.md](docs/WG_DIRECT_MODE.md)) | bridge dials the phone instead of waiting for it | yes |

The two tunnel binaries are **not shipped**: they are prebuilt Go binaries and their source is not in this repository. To keep the built-in tunnel you must provide them yourself — e.g. build a patched `wireproxy` as `libwgproxy.so` plus the TCP shim as `libwgtcp.so`, and drop them into `AiAgentForAndroid/app/src/main/jniLibs/`. The simpler path is to install the official WireGuard app on the phone and skip the built-in tunnel: the bridge does not care how the packet reaches it.

### Option A — same LAN or hotspot (no WG)

```bash
# bridge: listen on every interface
export MCP_HOST=0.0.0.0
export BRIDGE_TRANSPORT=streamable-http
.venv/bin/python bridge_mcp.py
```

Phone config: `server_host=<bridge LAN IP>`, `server_socket_port=9444`. Open TCP 9444 on the host firewall, and make sure the server certificate SAN contains that IP (or hostname).

Verified end-to-end on 2026-11-13 (phone on Wi-Fi 192.0.2.24 → bridge on 192.0.2.83, no WireGuard, no USB): TLS handshake, then `get_ui_state` (source=ax), `screenshot`, `click` (verified=true), `swipe` and `scroll_probe` all worked over this path.

### Option B — USB cable (no WG, no network at all)

```bash
adb reverse tcp:9444 tcp:9444      # phone 127.0.0.1:9444 -> host 127.0.0.1:9444
```

Phone config: `server_host=127.0.0.1`, `server_socket_port=9444`; the bridge can keep `MCP_HOST=127.0.0.1`. Everything, including adb actions, runs over the USB cable.

### Option C — public VPS with port forwarding (no WG)

Run the bridge on a VPS with a public IP, allow inbound TCP 9444, and set the phone's `server_host` to that IP. TLS is fail-closed and `MCP_DEVICE_TOKEN` **must** be non-empty — an empty token disables authentication, so never expose 9444 without one.

Status: the relay side is prepared and tested (`socat TCP-LISTEN:9445,fork,reuseaddr TCP:<bridge>:9444` listens), but the end-to-end phone test is still blocked on the cloud security group — the phone's mobile-data connection to the public port times out (dropped, not refused). Open the port in the console, then repeat the LAN steps.

## Features

- 🔌 **MCP bridge**: streamable-http (FastMCP), 35 tools (actions / screen / notifications / message hub / scripts / TTS)
- 🔐 **TLS + device token** on the phone socket (self-signed CA; fail-closed)
- 🕳️ **Built-in user-space WireGuard tunnel** in the app: outbound TLS rides the tunnel, no system VPN slot, no root
- 🚪 **Three interchangeable execution paths**: local `adb` 5555 / wireless-debugging TLS / Shizuku (automatic fallback)
- 🔔 **Message hub v2**: a "tagged text message + attachment" from any source lands in one store (phone notifications are just one source, `source=phone`); the server is the final store and the desktop info-hub is only a mirror
- 🤖 **Pure background service** on the phone: no UI. Two interchangeable paths — an **in-process accessibility tree** (needs the accessibility service enabled, no adb at all) or **local adb** (input / screencap / dumpsys / monkey)
- ⚡ **Accessibility tree**: `get_ui_state` returns `source=ax` with ~12 ms latency (uiautomator dump fallback: ~2.8 s)
- 🎯 **Deterministic click contract**: every `click` reports `found` / `actionable` / `tapped` / `verified` plus a `note` — a tap that changes nothing returns `verified=false` instead of pretending success
- 🖼️ **[experimental] Visual element matching**: register a template image of any icon or canvas button, then `element_register` / `element_find` / `element_click` find and tap it by pixels (NCC pyramid match, ambiguity guard). Off by default — the bridge must run with `MCP_EXPERIMENTAL=1`, which adds these three tools on top of the 35
- 🕐 **Phone-side script gateway**: `script_create` / `script_list` / `script_run` / `script_delete` / `script_schedule` — daily AlarmManager triggers, shell / speak / notify_pc steps
- 🗣️ **TTS**: SiliconFlow CosyVoice2 (the phone plays the wav via MediaPlayer, with system TTS as fallback)
- 💻 **CLI** (`cli/mcp4android.ps1`): bilingual (Chinese / English) guided install / pair / tcpip / config / restart / status / notify
- 🧩 **Optional app-learning module** (`ai_mcp_server/app_learn/`): imported lazily; if the directory is removed the server still starts and only the learning tools report unavailable

## Prebuilt APKs

Each release attaches two signed variants built from the tagged commit:

| Asset | Contains | Use when |
|---|---|---|
| `mcp-for-android-0.2.2-lite.apk` | no tunnel binaries | you already have WireGuard (official app) or connect over LAN/USB |
| `mcp-for-android-0.2.2-full.apk` | `lib/arm64-v8a/libwgproxy.so` + `libwgtcp.so` | you want the built-in user-space tunnel |

Both are signed with the same self-signed release key — verify with
`apksigner verify --print-certs <apk>` (SHA-256 `202ce14c9d9a4ab93d2222fb12a5cf25a810f13596ff03e64cc63d582221f009`).
The two tunnel binaries are **not** part of the source tree; see [Network options](#network-options).

## Requirements

- Python 3.10+ (tested on 3.12 / 3.14) for the bridge and tooling
- JDK 17 + Android SDK 34 (compileSdk 34, build-tools 34) for the app
- WireGuard is optional: the phone can also be reached over LAN/hotspot

## Quick Start

### 1. Generate your own certificates

The repository ships **no** certificates. Generate a two-level CA + server pair:

```bash
cd ai_mcp_server/scripts
python3 gen_cert.py            # writes ../certs/{ca.crt,ca.key,server.crt,server.key}
```

- Keep `ca.key` / `server.key` private — they are gitignored.
- The CA public key must be placed at `AiAgentForAndroid/app/src/main/res/raw/server_ca.pem` before building the app (the phone pins this CA).
- SAN must include every host you actually connect to (`IP:192.0.2.1`, your DNS name, etc.). Edit `gen_cert.py` or pass `--san`.

### 2. Build & install the phone app

```bash
# Android side: needs JDK 17 + Android SDK (compileSdk 34, build-tools 34)
cd AiAgentForAndroid
gradlew assembleDebug
# install via USB or wireless debugging
adb install -r app/build/outputs/apk/debug/app-debug.apk
# enable local adbd TCP (required for phone-side adb execution; re-run after phone reboot)
adb tcpip 5555
```

> **WireGuard note**: the built-in tunnel needs two prebuilt binaries that are not shipped —
> see [Network options](#network-options) for what is affected and for two WG-free ways to connect.
>
> **Prebuilt APKs**: each release attaches two signed variants — `-lite` (no tunnel binaries) and
> `-full` (bundles the prebuilt `libwgproxy.so` / `libwgtcp.so`, so the built-in tunnel works out of the box).

### 3. Run the bridge (on your personal server)

```bash
cd ai_mcp_server
python3 -m venv .venv && .venv/bin/pip install -r requirements.txt   # pins mcp==1.29.0
export MCP_DEVICE_TOKEN=$(openssl rand -hex 16)      # must match the phone config; empty = no auth
export MCP_HOST=192.0.2.10                           # address the phone dials (default 127.0.0.1 = local only)
export MCP_CERT_FILE=certs/server.crt
export MCP_KEY_FILE=certs/server.key
# the default transport is stdio; network clients need streamable-http explicitly
export BRIDGE_TRANSPORT=streamable-http
export BRIDGE_HOST=0.0.0.0
export BRIDGE_PORT=9500
.venv/bin/python bridge_mcp.py
# phone TLS endpoint: <MCP_HOST>:9444 ; MCP client: http://<BRIDGE_HOST>:9500/mcp
```

The ECS side runs a single L4 relay (see `docs/OPERATIONS.md` §4.1); it does not run the bridge.

### 4. Configure the phone

```bash
powershell -File cli/mcp4android.ps1 config <bridge-host> <device-token>
powershell -File cli/mcp4android.ps1 restart
```

### 5. Connect an MCP client

```bash
hermes mcp add phone --url http://<bridge>:9500/mcp
# or any MCP client — list tools: launch_app, click, script_run, tts_speak...
```

## Security notes

- **No certificates are committed.** Run `gen_cert.py` and keep `*.key` out of git.
- The phone socket requires a **device token** (`MCP_DEVICE_TOKEN`); the PC notification endpoint requires a token too.
- `adbkey` (private), `adbkey_wifi*` and TTS keys are **gitignored** — never commit secrets.
- The tunnel's WireGuard private key lives in SharedPreferences (`tunnel_private_key`); it is never written into the APK.
- Phone TTS key: `app/src/main/assets/tts_key.txt` (gitignored) or rely on the system TTS fallback.
- Default endpoints in code are placeholders (`203.0.113.10` = RFC 5737 TEST-NET-3); replace them with your own.

## Repository layout

```
ai_mcp_server/        MCP bridge (FastMCP, stdio + HTTP, TLS phone socket)
AiAgentForAndroid/    Android app (background service, adb executor, script gateway, TTS)
cli/                  mcp4android.ps1 (bilingual install/config CLI)
pc_notify/            Windows toast notification poller
deploy/               ECS systemd units + config examples
docs/                 ARCHITECTURE.md, INTERFACE.md, OPERATIONS.md, CHANNELS.md, NOTIFICATION_HUB.md, WG_DIRECT_MODE.md
```

## License

Released under the **MIT** license (see [LICENSE](LICENSE)).
`AiAgentForAndroid/` and its server-side prototype come from [lmee/mcp_for_android](https://github.com/lmee/mcp_for_android), whose README declares MIT (the repository itself ships no LICENSE file) — see [NOTICE](NOTICE) for provenance and third-party dependencies.

**The `full` APK additionally bundles the tunnel binaries**: `libwgtcp.so` (original to this project, MIT) and `libwgproxy.so` (built from [wireproxy](https://github.com/windtf/wireproxy) v1.1.3, **ISC** license, Copyright (c) 2026 Tsz Fung Wong). ISC requires the copyright and license notice to be retained with the binary, so the full APK ships `assets/THIRD_PARTY_LICENSES.txt` (the full text of the wireproxy license and its 14 Go dependencies). The repository itself contains no `.so` files — only the `full` release asset does; the `lite` variant ships no tunnel binaries.

*Original prototype: [lmee/mcp_for_android](https://github.com/lmee/mcp_for_android). This project adds TLS/token security, the MCP bridge, the phone-side gateway engine, user-space tunnel integration, and remote-control capabilities.*
