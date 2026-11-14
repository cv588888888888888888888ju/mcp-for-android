"""实验性视觉元素 MCP 工具：element_register / element_find / element_click。

手机端实现：AiAgentForAndroid/app/src/main/java/com/jxev/ai/client/VisualElementActions.java
（动作 element_register / element_find / element_click / element_list）。

默认关闭：只有 bridge_mcp.py 在 MCP_EXPERIMENTAL=1 时调用 register_visual_tools()，
因此未设置该环境变量时这三个工具不会出现在 tools/list 里。
"""

from __future__ import annotations

import re
from typing import Any, Callable, Optional

# _send_action(action_type, params, timeout=...) -> dict
SendAction = Callable[..., dict]

_NAME_RE = re.compile(r"^[A-Za-z0-9_.-]{1,32}$")
DEFAULT_THRESHOLD = 70


def _error(message: str) -> dict:
    return {"success": False, "found": False, "source": "visual", "error": message}


def _check_name(name: Any, tool: str) -> Optional[str]:
    if not isinstance(name, str) or not _NAME_RE.match(name.strip()):
        return None
    return name.strip()


def _clamp_threshold(value: Any) -> Optional[int]:
    if isinstance(value, bool):
        return None
    try:
        threshold = int(value)
    except (TypeError, ValueError):
        return None
    return max(1, min(100, threshold))


def register_visual_tools(mcp: Any, send_action: SendAction) -> None:
    """把三个 [experimental] 视觉元素工具注册到 FastMCP 实例。

    只应在 MCP_EXPERIMENTAL=1 时调用（见 bridge_mcp.py 的注册点）。
    """

    @mcp.tool()
    def element_register(
        name: str,
        package: str = "",
        threshold: int = DEFAULT_THRESHOLD,
        bbox: Optional[dict] = None,
        image_base64: str = "",
    ) -> dict:
        """[experimental] 注册视觉元素模板（名字 + 模板图 + 阈值），之后用 element_find / element_click 识别。

        模板来源二选一：bbox 从当前屏幕裁剪（bbox={x,y,w,h}，单位 px），
        或 image_base64（PNG/JPEG 的 base64，可带 data: 前缀）。
        package 为空时自动取当前前台应用包名；注册后只在同名应用前台时命中。
        落盘位置：手机 files/visual_elements/<name>.png + <name>.json（重启 App 后仍在）。
        返回 success/name/package/threshold/width/height/mean/std/templateSource/dir。
        """
        normalized_name = _check_name(name, "element_register")
        if normalized_name is None:
            return _error("element_register: name 非法（只允许 [A-Za-z0-9_.-]，1..32 字符）")
        normalized_threshold = _clamp_threshold(threshold)
        if normalized_threshold is None:
            return _error("element_register: threshold 必须是 1..100 的整数")

        params: dict = {"name": normalized_name, "threshold": normalized_threshold}
        if isinstance(package, str) and package.strip():
            params["package"] = package.strip()
        if isinstance(image_base64, str) and image_base64.strip():
            params["image_base64"] = image_base64.strip()
        elif isinstance(bbox, dict) and bbox:
            try:
                box = {key: int(bbox[key]) for key in ("x", "y", "w", "h")}
            except (KeyError, TypeError, ValueError):
                return _error("element_register: bbox 需要整数 x,y,w,h")
            if box["x"] < 0 or box["y"] < 0 or box["w"] <= 0 or box["h"] <= 0:
                return _error("element_register: bbox 需要 x,y>=0 且 w,h>0")
            params["bbox"] = box
        else:
            return _error("element_register: 需要 bbox={x,y,w,h} 或 image_base64")
        return send_action("element_register", params)

    @mcp.tool()
    def element_find(name: str) -> dict:
        """[experimental] 在当前屏幕查找已注册的视觉元素，返回 found / score / bbox / candidates。

        found 需要同时满足：匹配分数 >= 注册阈值，且当前前台应用等于注册时的包名。
        返回字段：found/score/threshold/x/y/width/height/centerX/centerY/candidates/package/
        registeredPackage/packageMatch/coarseFactor/matchMs/screenshotMs/totalMs/note。
        """
        normalized_name = _check_name(name, "element_find")
        if normalized_name is None:
            return _error("element_find: name 非法（只允许 [A-Za-z0-9_.-]，1..32 字符）")
        return send_action("element_find", {"name": normalized_name})

    @mcp.tool()
    def element_click(name: str) -> dict:
        """[experimental] 查找并点击已注册的视觉元素，返回与 click 同形的确定性字段。

        字段：found/actionable/tapped/verified/note/source(=visual)/x/y/target。
        未命中时 tapped=false、actionable=false、note 说明原因（不会乱点）。
        verified 为点击后 350ms 界面文本指纹比对结果，只可能是 true / false。
        """
        normalized_name = _check_name(name, "element_click")
        if normalized_name is None:
            return _error("element_click: name 非法（只允许 [A-Za-z0-9_.-]，1..32 字符）")
        return send_action("element_click", {"name": normalized_name})
