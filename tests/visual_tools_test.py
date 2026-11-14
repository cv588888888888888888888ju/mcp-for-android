#!/usr/bin/env python3
"""视觉元素工具单元测试（不需要真机、不需要 FastMCP 服务）。

覆盖：三工具注册、[experimental] 描述前缀、参数校验、转发到手机动作的 action/params 形状。
用法：python3 tests/visual_tools_test.py
退出码：0 全过；1 有 FAIL。
"""
import os
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, os.path.join(ROOT, "ai_mcp_server"))

from visual.tools import register_visual_tools  # noqa: E402

FAILS = []


def check(cond, msg):
    if cond:
        print(f"  OK   {msg}")
    else:
        FAILS.append(msg)
        print(f"  FAIL {msg}")


class FakeMCP:
    """最小 FastMCP 替身：只记录 @mcp.tool() 注册的函数。"""

    def __init__(self):
        self.tools = {}

    def tool(self):
        def decorator(fn):
            self.tools[fn.__name__] = fn
            return fn

        return decorator


def main():
    calls = []

    def fake_send_action(action_type, params=None, timeout=None):
        calls.append((action_type, params))
        return {"success": True, "source": "visual", "echo": action_type}

    mcp = FakeMCP()
    register_visual_tools(mcp, fake_send_action)

    print("[1] 注册与描述")
    check(set(mcp.tools) == {"element_register", "element_find", "element_click"},
          f"注册了三个工具：{sorted(mcp.tools)}")
    for name, fn in mcp.tools.items():
        check((fn.__doc__ or "").startswith("[experimental] "),
              f"{name} 描述以 [experimental] 开头")

    print("[2] element_register 参数校验")
    reg = mcp.tools["element_register"]
    calls.clear()
    r = reg(name="")
    check(r.get("success") is False and "name" in r.get("error", ""), "空 name 被拒")
    check(calls == [], "校验失败不转发到手机")

    r = reg(name="bad name")
    check(r.get("success") is False, "含空格 name 被拒")

    r = reg(name="bt", threshold=0)
    check(r.get("success") is False, "threshold=0 被拒")

    r = reg(name="bt")
    check(r.get("success") is False and "bbox" in r.get("error", ""), "既无 bbox 也无 image_base64 被拒")

    calls.clear()
    r = reg(name="bt", package="com.android.settings", threshold=70, bbox={"x": 70, "y": 1395, "w": 88, "h": 88})
    check(r.get("success") is True, "合法 bbox 注册成功")
    check(calls and calls[0][0] == "element_register", "转发 action=element_register")
    check(calls[0][1] == {"name": "bt", "threshold": 70, "package": "com.android.settings",
                          "bbox": {"x": 70, "y": 1395, "w": 88, "h": 88}}, f"转发 params 正确：{calls[0][1]}")

    calls.clear()
    r = reg(name="bt2", bbox={"x": -1, "y": 0, "w": 88, "h": 88})
    check(r.get("success") is False, "bbox x<0 被拒")

    calls.clear()
    r = reg(name="bt3", image_base64="iVBORw0KGgo=")
    check(r.get("success") is True and "bbox" not in calls[0][1], "image_base64 路径不带 bbox")

    calls.clear()
    r = reg(name="bt4", threshold=999, image_base64="iVBORw0KGgo=")
    check(r.get("success") is True and calls[0][1]["threshold"] == 100, "threshold 上限夹到 100")

    print("[3] element_find / element_click")
    calls.clear()
    r = mcp.tools["element_find"](name="bt")
    check(calls == [("element_find", {"name": "bt"})], "element_find 转发形状正确")
    check(r.get("success") is True, "element_find 透传手机结果")

    calls.clear()
    mcp.tools["element_click"](name="bt")
    check(calls == [("element_click", {"name": "bt"})], "element_click 转发形状正确")

    r = mcp.tools["element_find"](name="a/b")
    check(r.get("success") is False, "非法 name 被拒")

    print()
    if FAILS:
        print(f"FAIL {len(FAILS)} 项")
        return 1
    print("全部通过")
    return 0


if __name__ == "__main__":
    sys.exit(main())
