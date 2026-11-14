"""消息中枢导出器：按 ts 升序取增量，供 info-hub 的 phone-hub 采集器使用。

用法: hub_dump.py <since_ms> <limit> [source]
语义: 返回 ts > since_ms 的最早 limit 条（升序），默认只看 source='phone'。
"""
import json
import sqlite3
import sys

DB = "/opt/mcp_for_android/ai_mcp_server/hub.db"

since_ms = int(sys.argv[1]) if len(sys.argv) > 1 else 0
limit = int(sys.argv[2]) if len(sys.argv) > 2 else 200
source = sys.argv[3] if len(sys.argv) > 3 else "phone"

conn = sqlite3.connect(DB, timeout=15)
try:
    conn.execute("PRAGMA journal_mode=WAL")
    rows = conn.execute(
        "SELECT m.ts,m.source,m.kind,m.title,m.text,m.extra,m.nkey FROM message m"
        " WHERE m.ts > ? AND m.source = ? ORDER BY m.ts ASC LIMIT ?",
        (since_ms, source, limit),
    ).fetchall()
    out = []
    for r in rows:
        extra = json.loads(r[5]) if r[5] else {}
        tags = [t[0] for t in conn.execute(
            "SELECT tag FROM tag WHERE msg_id=(SELECT id FROM message WHERE ts=? AND source=? LIMIT 1)"
            " ORDER BY tag", (r[0], r[1]))]
        out.append({
            "ts": r[0], "source": r[1], "kind": r[2], "title": r[3], "text": r[4],
            "pkg": extra.get("pkg"), "app": extra.get("app"), "sub": extra.get("sub"),
            "key": r[6], "tags": tags,
        })
finally:
    conn.close()

print(json.dumps(out, ensure_ascii=False))
