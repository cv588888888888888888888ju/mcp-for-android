"""消息中枢（服务器侧，家庭机 192.0.2.10）——**通用消息存储**。

存的不是"通知"，而是**带标签的文本消息 + 附件**；手机通知只是其中一种 source。

  表 message(msg_id, ts, source, kind, title, text, extra, nkey, recv_ts)
  表 tag(msg_id, tag)                 —— 多标签，可检索
  表 attachment(msg_id, sha256, name, mime, size, path) —— 附件按内容寻址

source 例子: phone（手机通知）/ wechat / qq / secwatch / behavior / note / agent
kind   例子: notify / chat / report / event / note

兼容：旧表 `msg` 保留为归档，首次启动会把行复制进 message。
"""
import base64
import hashlib
import json
import os
import sqlite3
import threading
import time

DB_PATH = os.path.join(os.path.dirname(os.path.abspath(__file__)), "hub.db")
ATTACH_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "attachments")
_lock = threading.Lock()

SCHEMA = """
CREATE TABLE IF NOT EXISTS message(
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  ts INTEGER NOT NULL,
  source TEXT NOT NULL,
  kind TEXT,
  title TEXT,
  text TEXT,
  extra TEXT,
  nkey TEXT,
  recv_ts INTEGER,
  UNIQUE(source, nkey, ts)
);
CREATE INDEX IF NOT EXISTS idx_message_ts ON message(ts);
CREATE INDEX IF NOT EXISTS idx_message_source ON message(source);
CREATE TABLE IF NOT EXISTS tag(
  msg_id INTEGER NOT NULL,
  tag TEXT NOT NULL,
  PRIMARY KEY(msg_id, tag)
);
CREATE INDEX IF NOT EXISTS idx_tag_tag ON tag(tag);
CREATE TABLE IF NOT EXISTS attachment(
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  msg_id INTEGER NOT NULL,
  sha256 TEXT NOT NULL,
  name TEXT,
  mime TEXT,
  size INTEGER,
  path TEXT,
  UNIQUE(msg_id, sha256)
);
CREATE INDEX IF NOT EXISTS idx_att_sha ON attachment(sha256);
"""


def _connect():
    conn = sqlite3.connect(DB_PATH, timeout=15)
    conn.execute("PRAGMA journal_mode=WAL")
    conn.execute("PRAGMA foreign_keys=ON")
    conn.executescript(SCHEMA)
    _migrate_legacy(conn)
    _backfill_tags(conn)
    return conn


def _migrate_legacy(conn):
    """把旧 msg 表（手机通知）搬进 message，只做一次。"""
    try:
        has_msg = conn.execute(
            "SELECT 1 FROM sqlite_master WHERE type='table' AND name='msg'").fetchone()
        if not has_msg:
            return
        if conn.execute("SELECT 1 FROM message LIMIT 1").fetchone():
            return
        cur = conn.execute(
            "INSERT OR IGNORE INTO message(ts,source,kind,title,text,extra,nkey,recv_ts)"
            " SELECT ts, COALESCE(source,'phone'), 'notify', title, body,"
            "        json_object('pkg',pkg,'app',app,'sub',sub,'cat',cat), nkey, recv_ts"
            " FROM msg")
        conn.commit()
        if cur.rowcount:
            print(f"[hub] 迁移旧 msg 表 {cur.rowcount} 行进 message")
    except Exception as e:
        print(f"[hub] 迁移失败（忽略）: {e}")


def _backfill_tags(conn):
    """给还没有标签的历史消息补 pkg:/app: 标签（幂等，仅在 tag 表为空时跑）。"""
    try:
        if conn.execute("SELECT 1 FROM tag LIMIT 1").fetchone():
            return
        conn.execute("INSERT OR IGNORE INTO tag(msg_id,tag)"
                     " SELECT id,'pkg:'||json_extract(extra,'$.pkg') FROM message"
                     " WHERE json_extract(extra,'$.pkg') IS NOT NULL")
        conn.execute("INSERT OR IGNORE INTO tag(msg_id,tag)"
                     " SELECT id,'app:'||json_extract(extra,'$.app') FROM message"
                     " WHERE json_extract(extra,'$.app') IS NOT NULL")
        conn.commit()
        n = conn.execute("SELECT COUNT(*) FROM tag").fetchone()[0]
        if n:
            print(f"[hub] 历史消息补标签 {n} 个")
    except Exception as e:
        print(f"[hub] 补标签失败（忽略）: {e}")


def _save_attachment(sha256, name, mime, data):
    sub = os.path.join(ATTACH_DIR, sha256[:2])
    os.makedirs(sub, exist_ok=True)
    path = os.path.join(sub, sha256)
    if not os.path.exists(path):
        with open(path, "wb") as f:
            f.write(data)
    return path


def put(item, source="note"):
    """写一条消息；item 支持 title/text/tags/kind/attachments/extra/nkey/ts。

    attachments 每项: {name, mime, data_b64} 或 {name, path}
    返回 message id（重复返回已有 id）。
    """
    ts = int(item.get("ts") or time.time() * 1000)
    kind = item.get("kind") or "note"
    title = item.get("title")
    text = item.get("text")
    extra = item.get("extra")
    if extra is None and any(item.get(k) for k in ("pkg", "app", "sub", "cat")):
        extra = {"pkg": item.get("pkg"), "app": item.get("app"),
                 "sub": item.get("sub"), "cat": item.get("cat")}
    nkey = item.get("nkey") or item.get("key")
    tags = [str(t) for t in (item.get("tags") or []) if str(t).strip()]
    atts = item.get("attachments") or []
    now_ms = int(time.time() * 1000)

    with _lock:
        conn = _connect()
        try:
            try:
                cur = conn.execute(
                    "INSERT INTO message(ts,source,kind,title,text,extra,nkey,recv_ts)"
                    " VALUES(?,?,?,?,?,?,?,?)",
                    (ts, source, kind, title, text,
                     json.dumps(extra, ensure_ascii=False) if extra is not None else None,
                     nkey, now_ms))
                mid = cur.lastrowid
            except sqlite3.IntegrityError:
                row = conn.execute(
                    "SELECT id FROM message WHERE source=? AND nkey=? AND ts=?",
                    (source, nkey, ts)).fetchone()
                if not row:
                    raise
                mid = row[0]
            for t in tags:
                conn.execute("INSERT OR IGNORE INTO tag(msg_id,tag) VALUES(?,?)", (mid, t))
            if isinstance(extra, dict):
                if extra.get("pkg"):
                    conn.execute("INSERT OR IGNORE INTO tag(msg_id,tag) VALUES(?,?)",
                                 (mid, "pkg:" + str(extra["pkg"])))
                if extra.get("app"):
                    conn.execute("INSERT OR IGNORE INTO tag(msg_id,tag) VALUES(?,?)",
                                 (mid, "app:" + str(extra["app"])))
            for a in atts:
                data = None
                if a.get("data_b64"):
                    try:
                        data = base64.b64decode(a["data_b64"])
                    except Exception:
                        data = None
                if data is not None:
                    sha = hashlib.sha256(data).hexdigest()
                    path = _save_attachment(sha, a.get("name"), a.get("mime"), data)
                else:
                    path = a.get("path") or ""
                    sha = a.get("sha256") or ""
                conn.execute(
                    "INSERT OR IGNORE INTO attachment(msg_id,sha256,name,mime,size,path)"
                    " VALUES(?,?,?,?,?,?)",
                    (mid, sha, a.get("name"), a.get("mime"),
                     len(data) if data is not None else (a.get("size") or 0), path))
            conn.commit()
            return mid
        finally:
            conn.close()


def ingest(items, source="phone"):
    """批量写入（手机 hub_batch 与其它服务共用）；返回新增条数。"""
    n = 0
    for it in items or []:
        try:
            put(it, source=it.get("source") or source)
            n += 1
        except Exception:
            continue
    return n


def query(since_hours=24, source="", tag="", kind="", pkg="", limit=50):
    since_ms = int((time.time() - float(since_hours) * 3600) * 1000)
    sql = ("SELECT m.id,m.ts,m.source,m.kind,m.title,m.text,m.extra,m.nkey"
           " FROM message m WHERE m.ts >= ?")
    args = [since_ms]
    if source:
        sql += " AND m.source = ?"
        args.append(source)
    if kind:
        sql += " AND m.kind = ?"
        args.append(kind)
    if tag:
        sql += " AND EXISTS(SELECT 1 FROM tag t WHERE t.msg_id=m.id AND t.tag=?)"
        args.append(tag)
    if pkg:
        sql += " AND json_extract(m.extra,'$.pkg') = ?"
        args.append(pkg)
    sql += " ORDER BY m.ts DESC LIMIT ?"
    args.append(int(limit))
    with _lock:
        conn = _connect()
        try:
            rows = conn.execute(sql, args).fetchall()
            out = []
            for r in rows:
                mid = r[0]
                tags = [x[0] for x in conn.execute(
                    "SELECT tag FROM tag WHERE msg_id=? ORDER BY tag", (mid,))]
                atts = [{"name": a[1], "mime": a[2], "size": a[3], "sha256": a[4]}
                        for a in conn.execute(
                            "SELECT id,name,mime,size,sha256 FROM attachment WHERE msg_id=?", (mid,))]
                out.append({
                    "id": mid, "ts": r[1], "source": r[2], "kind": r[3],
                    "title": r[4], "text": r[5],
                    "extra": json.loads(r[6]) if r[6] else None,
                    "key": r[7], "tags": tags, "attachments": atts,
                })
        finally:
            conn.close()
    return out


def stats():
    with _lock:
        conn = _connect()
        try:
            total = conn.execute("SELECT COUNT(*) FROM message").fetchone()[0]
            by_source = conn.execute(
                "SELECT source, COUNT(*), MAX(ts) FROM message GROUP BY source"
                " ORDER BY COUNT(*) DESC LIMIT 30").fetchall()
            by_tag = conn.execute(
                "SELECT tag, COUNT(*) FROM tag GROUP BY tag ORDER BY COUNT(*) DESC LIMIT 30").fetchall()
            att = conn.execute("SELECT COUNT(*), COALESCE(SUM(size),0) FROM attachment").fetchone()
        finally:
            conn.close()
    return {
        "total": total,
        "sources": [{"source": r[0], "count": r[1], "lastTs": r[2]} for r in by_source],
        "tags": [{"tag": r[0], "count": r[1]} for r in by_tag],
        "attachments": {"count": att[0], "bytes": att[1]},
    }


def get_attachment(sha256):
    sub = os.path.join(ATTACH_DIR, sha256[:2], sha256)
    if not os.path.exists(sub):
        return None
    with open(sub, "rb") as f:
        return f.read()
