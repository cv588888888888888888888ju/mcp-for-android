"""清理服务器中场库（ECS hub.db）里已经过时的通知。

语​义：ECS 只是中转，最终存储在你的个人电脑（info-hub 的 events 表）。
本脚本删掉比 keep-days 更老的记录，释放中转空间。

用法:
    python hub_prune.py                # 默认 dry-run，只报会删多少
    python hub_prune.py --apply        # 真删
    python hub_prune.py --keep-days 30 --apply

注意：删之前请确认个人电脑的 info-hub 已经收走（`bin/hub-collect` 跑过、
`source='phone-hub'` 里有这些消息）。
"""

from __future__ import annotations

import argparse
import os
import sqlite3
import time

DB = os.environ.get("HUB_DB", "/opt/mcp_for_android/ai_mcp_server/hub.db")


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--keep-days", type=int, default=30, help="保留最近多少天（默认 30）")
    ap.add_argument("--apply", action="store_true", help="真删；不给则只 dry-run")
    args = ap.parse_args()

    cutoff_ms = int((time.time() - args.keep_days * 86400) * 1000)
    conn = sqlite3.connect(DB, timeout=15)
    try:
        total = conn.execute("SELECT COUNT(*) FROM msg").fetchone()[0]
        old = conn.execute("SELECT COUNT(*) FROM msg WHERE ts < ?", (cutoff_ms,)).fetchone()[0]
        print(f"库 {DB}: 总 {total} 行；早于 {args.keep_days} 天的 {old} 行")
        if not args.apply:
            print("dry-run：没删。确认个人电脑已收走后加 --apply")
            return 0
        cur = conn.execute("DELETE FROM msg WHERE ts < ?", (cutoff_ms,))
        conn.commit()
        print(f"已删 {cur.rowcount} 行；剩余 {conn.execute('SELECT COUNT(*) FROM msg').fetchone()[0]} 行")
    finally:
        conn.close()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
