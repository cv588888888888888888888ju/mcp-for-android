"""实验性视觉元素工具（默认关闭；MCP_EXPERIMENTAL=1 时由 bridge_mcp.py 注册）。

三个工具：element_register / element_find / element_click。
手机端实现见 AiAgentForAndroid/app/src/main/java/com/jxev/ai/client/VisualElementActions.java。
"""

from .tools import register_visual_tools

__all__ = ["register_visual_tools"]
