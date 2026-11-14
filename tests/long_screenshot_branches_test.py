#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""tools/long_screenshot.py 的分支判据离线测试（不需要真机）。

覆盖两个机械判据：
  1. moved>=2 且所有 structureCanForward 均为 False ⇒ reason=unsupported，且不产出拼接图
  2. structureCanForward=True 的正常滚动页 ⇒ 走拼接路径（不被 unsupported 误伤）
用法：python3 tests/long_screenshot_branches_test.py
"""
import io
import os
import sys
import tempfile

sys.path.insert(0, os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "tools"))
import long_screenshot as L  # noqa: E402


def _png(width, height, seed):
    from PIL import Image, ImageDraw
    img = Image.new("RGB", (width, height), (255, 255, 255))
    d = ImageDraw.Draw(img)
    # 造可区分纹理：每 37px 一条深色横带 + 偏移文字块，避免纯色区降置信度
    for y in range(0, height, 37):
        d.rectangle([0, y, width, y + 6], fill=(30 + (y + seed) % 60, 60, 90))
    for y in range(10, height, 90):
        d.text((20, y), "row-%d-%d" % (seed, y), fill=(0, 0, 0))
    buf = io.BytesIO()
    img.save(buf, format="PNG")
    return buf.getvalue()


class FakePhone(object):
    def __init__(self, frames, can_forward, moved=True):
        self._frames = list(frames)
        self._i = 0
        self._can_forward = can_forward
        self._moved = moved
        self.probes = 0

    def screenshot(self):
        i = min(self._i, len(self._frames) - 1)
        self._i += 1
        return self._frames[i]

    def scroll_probe(self, forward=True):
        self.probes += 1
        return {"moved": self._moved, "structureCanForward": self._can_forward, "latencyMs": 1}

    def ui_state(self):
        return {"nodes": [{"t": "示例文本", "b": [0, 10, 200, 60]}]}

    def screen_size(self):
        return {"width": 720, "height": 1280}


def main():
    ok = True
    with tempfile.TemporaryDirectory() as td:
        out = os.path.join(td, "out.png")
        # 用例 1：无滚动容器 + 画面在变 ⇒ unsupported，且不落盘
        frames = [_png(720, 1280, s) for s in range(4)]
        res = L.cmd_capture(FakePhone(frames, can_forward=False), max_scrolls=3, out=out)
        c1 = (res.get("reason") == "unsupported" and res.get("success") is False
              and not os.path.exists(out))
        print("[1] unsupported 判据:", "PASS" if c1 else "FAIL", res.get("reason"))
        ok = ok and c1

        # 用例 2：正常可滚页（structureCanForward=True）不得被误判 unsupported
        res2 = L.cmd_capture(FakePhone(frames, can_forward=True), max_scrolls=2, out=out)
        c2 = res2.get("reason") != "unsupported"
        print("[2] 正常页不误伤:", "PASS" if c2 else "FAIL", res2.get("reason") or "stitched")
        ok = ok and c2

        # 用例 3：首探就 moved=false ⇒ page_not_scrollable（与 unsupported 区分）
        res3 = L.cmd_capture(FakePhone(frames, can_forward=False, moved=False), max_scrolls=2, out=out)
        c3 = str(res3.get("reason", "")).startswith("page_not_scrollable")
        print("[3] 单帧判 not_scrollable:", "PASS" if c3 else "FAIL", res3.get("reason"))
        ok = ok and c3

    print("RESULT:", "PASS" if ok else "FAIL")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
