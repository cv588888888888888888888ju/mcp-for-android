#!/usr/bin/env python3
"""[experimental] 长截图 / 滚动定位客户端工具（S2 + S3）。

设计约束（见 tasks/active/AND_TASK_long-screenshot.md）：
- 只在客户端驱动，不改 bridge 协议：全部通过 MCP 的 device_action 调用
  screenshot / scroll_probe / get_ui_state / click。
- 到底判据只用行为判据：scroll_probe 的 moved=false，结构信号（canScrollForward）仅作参考。
- 拼接靠逐行签名求重叠；置信度不足时只返回原始帧 + 原因，不产出垃圾图。

用法：
  python3 tools/long_screenshot.py capture [--max-scrolls 8] [--out /tmp/long.png]
  python3 tools/long_screenshot.py scroll-to-and-click --text "关于手机" [--max-scrolls 10]

输出：单行 JSON（含 "experimental": true）。
"""
import argparse
import base64
import io
import json
import sys
import time
import urllib.error
import urllib.request

DEFAULT_BASE = "http://127.0.0.1:9500/mcp"

# ---- 拼接判据常量（改这里就是改行为，别散落在函数里） ----
MIN_CONFIDENCE = 0.85      # 低于此值不拼接，只返回原始帧 + 原因
ROW_TOL = 4.0              # 行签名（分块灰度均值）逐行"平均绝对差"允许的上限
SEARCH_LOW = 0.04          # 重叠搜索下界（占帧高比例）：一次滚太远时重叠可能只剩 100~200 行
SMALL_OVERLAP_RATIO = 0.15 # 重叠低于帧高这个比例时算"小重叠"，要求更严的置信度
SMALL_OVERLAP_CONF = 0.95  # 小重叠允许通过的最低置信度（少样本 ⇒ 更容易假配）
SEARCH_STEP = 2            # 粗搜步长（行），命中后再 ±3 精修
MIN_OVERLAP_ROWS = 48      # 重叠区（去掉顶部 chrome 后）至少这么高，否则不认
CHROME_TOP_RATIO = 0.12    # 每帧顶部这一条是状态栏/折叠标题栏（半透明），打分时忽略
TEXTURE_MIN = 0.15         # 重叠区里"有纹理"的行占比低于此值 ⇒ 纯色区误配，置信度打折
TEXTURE_STD = 3.0          # 行内分块灰度标准差超过此值算"有纹理"
STICKY_TOP_RATIO = 0.08    # 判定"粘性头部"时看顶部这么高的一条
STICKY_MATCH_RATIO = 0.90  # 顶部条完全一致的比例阈值
N_BLOCKS = 32              # 行签名分块数


# ================= MCP streamable-http 客户端（纯标准库） =================
class McpClient:
    def __init__(self, base=DEFAULT_BASE, timeout=120):
        self.base = base
        self.timeout = timeout
        self.session = None

    def _post(self, payload):
        data = json.dumps(payload).encode()
        req = urllib.request.Request(self.base, data=data, method="POST")
        req.add_header("Content-Type", "application/json")
        req.add_header("Accept", "application/json, text/event-stream")
        if self.session:
            req.add_header("Mcp-Session-Id", self.session)
        try:
            with urllib.request.urlopen(req, timeout=self.timeout) as r:
                body = r.read().decode()
                sid = r.headers.get("Mcp-Session-Id")
                return body, sid, r.status
        except urllib.error.HTTPError as e:
            return e.read().decode(), None, e.code

    @staticmethod
    def _parse(body):
        out = []
        for line in body.splitlines():
            if line.startswith("data: "):
                out.append(line[6:])
        if not out:
            try:
                return [json.loads(body)]
            except Exception:
                return []
        return [json.loads(x) for x in out if x.strip()]

    def connect(self):
        init = {"jsonrpc": "2.0", "id": 1, "method": "initialize",
                "params": {"protocolVersion": "2025-06-18", "capabilities": {},
                           "clientInfo": {"name": "long_screenshot", "version": "1"}}}
        body, sid, status = self._post(init)
        msgs = self._parse(body)
        if not msgs or "error" in msgs[0]:
            raise RuntimeError("initialize 失败 status=%s body=%s" % (status, body[:300]))
        self.session = sid or msgs[0].get("result", {}).get("sessionId")
        self._post({"jsonrpc": "2.0", "method": "notifications/initialized"})
        return self

    def call(self, tool, args=None):
        call = {"jsonrpc": "2.0", "id": 2, "method": "tools/call",
                "params": {"name": tool, "arguments": args or {}}}
        body, _, status = self._post(call)
        msgs = self._parse(body)
        if not msgs:
            raise RuntimeError("tools/call 失败 status=%s body=%s" % (status, body[:400]))
        for m in msgs:
            if "error" in m:
                raise RuntimeError("MCP 错误: %s" % json.dumps(m["error"], ensure_ascii=False))
            if "result" in m:
                return self._payload(m["result"])
        raise RuntimeError("空响应")

    @staticmethod
    def _payload(res):
        """MCP 结果 -> Python dict：优先 structuredContent，否则解析 text 里的 JSON。"""
        sc = res.get("structuredContent")
        if isinstance(sc, dict) and sc:
            return sc
        for c in res.get("content") or []:
            if c.get("type") == "text":
                txt = c.get("text") or ""
                try:
                    return json.loads(txt)
                except Exception:
                    return {"raw": txt}
        return {}


class Phone:
    def __init__(self, base=DEFAULT_BASE):
        self.mcp = McpClient(base).connect()

    def act(self, action_type, params=None):
        r = self.mcp.call("device_action", {"action_type": action_type, "parameters": params or {}})
        data = r.get("data") if isinstance(r.get("data"), dict) else {}
        out = dict(data)
        out["_status"] = r.get("status")
        out["_error"] = r.get("error")
        if isinstance(r.get("ui_state"), dict):
            out["ui_state"] = r["ui_state"]
        return out

    def screenshot(self):
        d = self.act("screenshot")
        b64 = d.get("base64")
        if not b64:
            raise RuntimeError("截图失败: %s" % (d.get("error") or d.get("_error") or d))
        return base64.b64decode(b64)

    def scroll_probe(self, forward=True):
        return self.act("scroll_probe", {"forward": bool(forward)})

    def screen_size(self):
        d = self.act("get_screen_size")
        return int(d.get("width") or 0), int(d.get("height") or 0)

    def ui_state(self):
        d = self.act("get_ui_state")
        st = d.get("ui_state")
        if isinstance(st, dict):
            return st
        msg = d.get("message")
        if isinstance(msg, str):
            try:
                return json.loads(msg)
            except Exception:
                pass
        return {}


# ================= 图像：行签名与重叠搜索 =================
def _np():
    import numpy as np
    return np


def load_gray(png_bytes):
    from PIL import Image
    img = Image.open(io.BytesIO(png_bytes)).convert("L")
    return _np().asarray(img)


def row_signature(gray, n_blocks=N_BLOCKS):
    """每行压成 n_blocks 个分块灰度均值；返回 (h, n_blocks) uint8。"""
    np = _np()
    h, w = gray.shape
    edges = np.linspace(0, w, n_blocks + 1).astype(int)
    sig = np.zeros((h, n_blocks), dtype=np.uint8)
    for i in range(n_blocks):
        a, b = edges[i], max(edges[i] + 1, edges[i + 1])
        sig[:, i] = gray[:, a:b].mean(axis=1).astype(np.uint8)
    return sig


def _ratio(sig_a, sig_b, tol=ROW_TOL):
    """两段等高签名的"逐行一致率"：行内各块平均绝对差 <= tol 的行算一致。"""
    np = _np()
    d = np.abs(sig_a.astype(np.int16) - sig_b.astype(np.int16))
    ok = (d.mean(axis=1) <= tol)
    return float(ok.mean()) if ok.size else 0.0


def _textured_ratio(sig, std_threshold=TEXTURE_STD):
    """行内分块灰度标准差 > 阈值的行占比：识别"纯色区任意对齐都像"的假匹配。"""
    np = _np()
    if sig.size == 0:
        return 0.0
    return float((sig.astype(np.float32).std(axis=1) > std_threshold).mean())


def find_overlap(sig_prev, sig_cur):
    """返回 (overlap_rows, confidence)。

    打分只看"重叠区去掉当前帧顶部 chrome 之后"的行；纯色区会打折，
    因为那种区域任意 k 都能对上（假峰）。
    """
    hp, hc = sig_prev.shape[0], sig_cur.shape[0]
    hi = min(hp, hc)
    lo = max(MIN_OVERLAP_ROWS, int(SEARCH_LOW * hc))
    if hi < lo:
        return 0, 0.0

    best_k, best_r = 0, 0.0

    def eval_k(k):
        # 顶部 chrome（状态栏 + 折叠标题栏）在每帧里都会被半透明重绘，且高度随重叠变小而受限，
        # 所以按 k 现算：最多吃掉重叠区的一半。
        chrome = min(int(CHROME_TOP_RATIO * hc), max(0, k // 2))
        if k <= chrome:
            return 0.0
        band_prev = sig_prev[hp - k + chrome:]
        band_cur = sig_cur[chrome:k]
        if band_cur.shape[0] < MIN_OVERLAP_ROWS // 2:
            return 0.0
        r = _ratio(band_prev, band_cur)
        if _textured_ratio(band_cur) < TEXTURE_MIN:
            r *= 0.5
        return r

    for k in list(range(lo, hi + 1, SEARCH_STEP)) + [hi]:
        r = eval_k(k)
        if r > best_r or (r == best_r and k > best_k):
            best_k, best_r = k, r
    if best_k:
        for k in range(max(lo, best_k - 3), min(hi, best_k + 3) + 1):
            r = eval_k(k)
            if r > best_r or (r == best_r and k > best_k):
                best_k, best_r = k, r
    # 小重叠样本少，容易和别处的相似行撞上：要求近乎完美一致才认
    if best_k and best_k < SMALL_OVERLAP_RATIO * hc and best_r < SMALL_OVERLAP_CONF:
        return 0, best_r
    return best_k, best_r


def stitch(frames):
    """frames: [(gray, sig)]，返回 (stitched_gray, info)。"""
    np = _np()
    base, _ = frames[0]
    out_parts = [base]
    total = base.shape[0]
    overlaps, confidences = [], []
    sticky = False
    reason = ""

    for idx in range(1, len(frames)):
        prev_gray, prev_sig = frames[idx - 1]
        cur_gray, cur_sig = frames[idx]
        h_prev, h_cur = prev_gray.shape[0], cur_gray.shape[0]
        k, conf = find_overlap(prev_sig, cur_sig)

        # 完全一致（滚不动 / 截图没变）⇒ 不产生重复帧
        if k and k >= min(h_prev, h_cur) and conf >= 0.999:
            reason = "duplicate_frame"
            return None, {"overlaps": overlaps, "confidences": confidences,
                          "confidence": min(confidences) if confidences else 1.0,
                          "stickyHeaderSuspected": sticky, "reason": reason}

        if conf < MIN_CONFIDENCE or k < MIN_OVERLAP_ROWS:
            reason = "overlap_uncertain(conf=%.3f,overlap=%d)" % (conf, k)
            return None, {"overlaps": overlaps, "confidences": confidences,
                          "confidence": conf, "stickyHeaderSuspected": sticky, "reason": reason}

        overlaps.append(k)
        confidences.append(conf)

        # 粘性头部启发式：跳过顶部 chrome 之后的那一条，在两帧里完全一致，但内容确实滚动了
        # （直接比顶部会误判：状态栏本身两帧都长一样。）
        band = max(8, int(STICKY_TOP_RATIO * h_cur))
        top = int(CHROME_TOP_RATIO * h_cur)
        if k < h_prev - band and top + band <= h_cur \
                and _ratio(prev_sig[top:top + band], cur_sig[top:top + band]) >= STICKY_MATCH_RATIO:
            sticky = True

        out_parts.append(cur_gray[k:])
        total += h_cur - k

    stitched = np.concatenate(out_parts, axis=0) if len(out_parts) > 1 else base
    return stitched, {"overlaps": overlaps, "confidences": confidences,
                      "confidence": min(confidences) if confidences else 1.0,
                      "stickyHeaderSuspected": sticky, "reason": ""}


def save_png(gray, path):
    from PIL import Image
    Image.fromarray(gray).save(path)


# ================= 场景实现 =================
def cmd_capture(phone, max_scrolls=8, out="/tmp/long_screenshot.png", forward=True):
    frames = []
    probes = []
    reached_end = False
    result = {"experimental": True, "mode": "capture", "success": False,
              "frames": 0, "maxScrolls": max_scrolls, "reachedEnd": False}

    first = phone.screenshot()
    frames.append(first)

    # 场景 S4：视频 / 游戏 / Canvas / FLAG_SECURE —— 截不到可用内容就直说，不产出垃圾图
    ui = phone.ui_state()
    texts = [n for n in _iter_nodes(ui.get("nodes")) if n.get("t") or n.get("d")]
    gray0 = load_gray(first)
    if not texts or float(gray0.mean()) < 8.0:
        result.update(success=False, frames=1, reachedEnd=False, reason="unsupported",
                      note="页面没有可读文本节点或截图近乎全黑（视频/游戏/Canvas/FLAG_SECURE），"
                           "长截图不适用；未产出任何拼接图")
        return result

    for i in range(max_scrolls):
        p = phone.scroll_probe(forward=forward)
        moved = bool(p.get("moved"))
        probes.append({"i": i, "moved": moved,
                       "structureCanForward": p.get("structureCanForward"),
                       "latencyMs": p.get("latencyMs")})
        if not moved:
            reached_end = True
            break
        frames.append(phone.screenshot())

    result["frames"] = len(frames)
    result["reachedEnd"] = reached_end
    result["probes"] = probes

    if len(frames) < 2:
        result["reason"] = "page_not_scrollable（首帧后 scroll_probe 判定 moved=false）"
        return result

    # 场景 S4：没有可滚动容器，但画面每次都在变 ⇒ 不是滚动，是视频/动画/Canvas。
    # 机械判据：所有探测的 structureCanForward 都是 false，且至少 2 次 moved=true。
    moved_count = sum(1 for x in probes if x["moved"])
    if moved_count >= 2 and all(x["structureCanForward"] is False for x in probes):
        result.update(success=False, reachedEnd=False, reason="unsupported",
                      note="页面没有可滚动容器但画面持续变化（视频/动画/Canvas），"
                           "拼接无意义；未产出拼接图")
        return result

    grays, sigs = [], []
    for f in frames:
        g = load_gray(f)
        grays.append(g)
        sigs.append(row_signature(g))

    stitched, info = stitch(list(zip(grays, sigs)))
    result["confidence"] = round(info["confidence"], 3)
    result["overlaps"] = info["overlaps"]
    result["stickyHeaderSuspected"] = info["stickyHeaderSuspected"]

    if stitched is None:
        # 置信度不足 / 重复帧：只返回原始帧，不产出垃圾图
        paths = []
        for i, f in enumerate(frames):
            p = "%s.frame%d.png" % (out.rsplit(".", 1)[0], i)
            with open(p, "wb") as fh:
                fh.write(f)
            paths.append(p)
        result["success"] = False
        result["reason"] = info["reason"]
        result["rawFrames"] = paths
        result["note"] = "拼接置信度不足或出现重复帧，未生成拼接图（不静默丢帧）"
        return result

    save_png(stitched, out)
    sum_non_overlap = grays[0].shape[0] + sum(g.shape[0] - k for g, k in zip(grays[1:], info["overlaps"]))
    result["success"] = True
    result["stitched"] = out
    result["stitchedHeight"] = int(stitched.shape[0])
    result["frameHeight"] = int(grays[0].shape[0])
    result["sumNonOverlap"] = int(sum_non_overlap)
    result["heightDelta"] = int(stitched.shape[0]) - int(sum_non_overlap)
    return result


def _iter_nodes(nodes):
    for n in nodes or []:
        yield n
        for kid in _iter_nodes(n.get("kids")):
            yield kid


def _find_node(ui, text):
    # 树是嵌套的（kids），必须递归；只看顶层会漏掉几乎所有条目
    nodes = list(_iter_nodes(ui.get("nodes")))
    exact = [n for n in nodes if (n.get("t") == text or n.get("d") == text)]
    if exact:
        return exact[0], True
    part = [n for n in nodes if (text in (n.get("t") or "") or text in (n.get("d") or ""))]
    if part:
        return part[0], False
    return None, False


def _on_screen(node, screen_h, margin=0.06):
    """节点是否真的在可视区里。树里会保留屏外条目（RecyclerView 预加载），
    「找到了」不等于「点得到」——实测 全部参数与信息 的 bounds.top=3114 > 屏高 2400。"""
    b = node.get("b") or []
    if len(b) < 4:
        return False
    return b[3] > screen_h * margin and b[1] < screen_h * (1.0 - margin)


def _direction_to(node, screen_h):
    """目标在屏下方 ⇒ 往前滚；在屏上方 ⇒ 往回滚。"""
    b = node.get("b") or [0, 0, 0, 0]
    return True if b[1] >= screen_h * 0.5 else False


def _settle_locate(phone, text, max_wait=2.5, gap=0.25):
    """反复 dump 直到目标节点的 bounds 连续两次一致，或超时。

    为什么需要：MIUI 的无障碍树比界面慢（实测滚完立刻 dump，目标还带着旧 bounds/invis，
    几百毫秒后才更新）；而且列表到底/到顶有回弹，瞬间的 bounds 是回弹中间态。
    拿中间态去判断「在不在屏上」会得到互相矛盾的结论。
    """
    ui = phone.ui_state()
    node, exact = _find_node(ui, text)
    prev = None if node is None else tuple(node.get("b") or [])
    t0 = time.time()
    while time.time() - t0 < max_wait:
        if node is not None and prev is not None and tuple(node.get("b") or []) == prev:
            return ui, node, exact
        time.sleep(gap)
        ui = phone.ui_state()
        node, exact = _find_node(ui, text)
        prev = None if node is None else tuple(node.get("b") or [])
    return ui, node, exact


def cmd_scroll_to_click(phone, text, max_scrolls=10, forward=True):
    """滚到目标出现再点。

    判据用的是「手机端 click 的返回」而不是本地 dump：
    dump 里的 bounds / invis 会滞后（滚完立刻 dump 还是旧坐标），而手机端 find() 内部用
    AccessibilityNodeInfo.isVisibleToUser() 现算，是权威的「点得到吗」。
    """
    result = {"experimental": True, "mode": "scroll_to_and_click", "success": False,
              "text": text, "found": False, "tapped": False, "verified": False,
              "scrolls": 0, "source": None}

    scrolls = 0
    direction = forward
    stale_moves = 0
    attempts = []

    while True:
        d = phone.act("click", {"text": text})
        attempts.append({"scrolls": scrolls, "found": d.get("found"),
                         "tapped": d.get("tapped"), "actionable": d.get("actionable"),
                         "error": d.get("error") or d.get("_error")})
        result["source"] = d.get("source") or result["source"]

        if d.get("tapped"):
            result.update(success=True, found=True, tapped=True,
                          verified=bool(d.get("verified")), scrolls=scrolls,
                          x=d.get("x"), y=d.get("y"),
                          pageScrollable=d.get("pageScrollable"))
            result["attempts"] = attempts
            return result

        if d.get("found"):
            # 存在但接不了手势：滚动再多也没用，如实返回
            result.update(found=True, tapped=False, verified=False, scrolls=scrolls,
                          actionable=bool(d.get("actionable")),
                          reason="found_but_not_actionable（目标存在但自身与祖先都不可点击）")
            result["attempts"] = attempts
            return result

        if scrolls >= max_scrolls:
            result.update(reason="not_found（滚动 %d 次后仍未出现）" % scrolls, scrolls=scrolls)
            result["attempts"] = attempts
            return result

        p = phone.scroll_probe(forward=direction)
        if not p.get("moved"):
            # 单次「没动」不足以判到底：列表回弹 / 无障碍树滞后都会造成假阴性。连续 2 次才收工。
            stale_moves += 1
            if stale_moves >= 2:
                result.update(reason="reached_end（连续 %d 次滚动无位移）" % stale_moves,
                              scrolls=scrolls)
                result["attempts"] = attempts
                return result
        else:
            stale_moves = 0
            scrolls += 1


def main():
    ap = argparse.ArgumentParser(description="[experimental] 长截图 / 滚动定位（客户端驱动）")
    ap.add_argument("--base", default=DEFAULT_BASE, help="MCP bridge URL")
    sub = ap.add_subparsers(dest="cmd", required=True)

    c = sub.add_parser("capture", help="长截图拼接")
    c.add_argument("--max-scrolls", type=int, default=8)
    c.add_argument("--out", default="/tmp/long_screenshot.png")
    c.add_argument("--backward", action="store_true")

    s = sub.add_parser("scroll-to-and-click", help="滚到目标出现再点击")
    s.add_argument("--text", required=True)
    s.add_argument("--max-scrolls", type=int, default=10)
    s.add_argument("--backward", action="store_true")

    args = ap.parse_args()
    try:
        phone = Phone(args.base)
        if args.cmd == "capture":
            out = cmd_capture(phone, args.max_scrolls, args.out, not args.backward)
        else:
            out = cmd_scroll_to_click(phone, args.text, args.max_scrolls, not args.backward)
    except Exception as e:
        out = {"experimental": True, "success": False, "error": "%s: %s" % (type(e).__name__, e)}
    print(json.dumps(out, ensure_ascii=False))
    return 0 if out.get("success") else 1


if __name__ == "__main__":
    sys.exit(main())
