package com.jxev.ai.hub;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 信息中枢本地库（2026-10-24）：手机收到的通知统一落这里，PC/agent 通过 hub_query 查。
 * 只做本地存储，不往外发；是否上报由调用方决定。
 */
public final class HubStore extends SQLiteOpenHelper {
    private static final String DB_NAME = "hub.db";
    private static final int VERSION = 2;
    private static final long KEEP_MS = 30L * 24 * 3600 * 1000;

    private static HubStore instance;

    public static synchronized HubStore get(Context ctx) {
        if (instance == null) {
            instance = new HubStore(ctx.getApplicationContext());
        }
        return instance;
    }

    private HubStore(Context ctx) {
        super(ctx, DB_NAME, null, VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS msg(" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "ts INTEGER NOT NULL," +
                "pkg TEXT," +
                "app TEXT," +
                "title TEXT," +
                "body TEXT," +
                "sub TEXT," +
                "cat TEXT," +
                "nkey TEXT," +
                "synced INTEGER DEFAULT 0," +
                "raw TEXT)");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_msg_ts ON msg(ts)");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_msg_pkg ON msg(pkg)");
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS idx_msg_key ON msg(nkey, ts)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        // v2：加 synced 标记（0=未上报服务器）
        if (oldVersion < 2) {
            try {
                db.execSQL("ALTER TABLE msg ADD COLUMN synced INTEGER DEFAULT 0");
            } catch (Exception e) {
                Log.w("HubStore", "加 synced 列失败（可能已存在）：" + e.getMessage());
            }
        }
    }

    /** 取一批还没上报服务器的消息（含 id），供上传器发给 ECS。 */
    public synchronized JSONArray drainUnsynced(int limit) {
        JSONArray arr = new JSONArray();
        Cursor c = null;
        try {
            c = getReadableDatabase().rawQuery(
                    "SELECT id,ts,pkg,app,title,body,sub,cat,nkey FROM msg WHERE synced=0 ORDER BY ts ASC LIMIT ?",
                    new String[]{String.valueOf(limit)});
            while (c.moveToNext()) {
                JSONObject o = new JSONObject();
                o.put("id", c.getLong(0));
                o.put("ts", c.getLong(1));
                o.put("pkg", c.getString(2));
                o.put("app", c.getString(3));
                o.put("title", c.getString(4));
                o.put("text", c.getString(5));
                o.put("sub", c.getString(6));
                o.put("cat", c.getString(7));
                o.put("key", c.getString(8));
                arr.put(o);
            }
        } catch (Exception e) {
            Log.e("HubStore", "drainUnsynced failed", e);
        } finally {
            if (c != null) c.close();
        }
        return arr;
    }

    /** 标记已上报（按 id）。 */
    public synchronized void markSynced(java.util.List<Integer> ids) {
        if (ids == null || ids.isEmpty()) return;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < ids.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(ids.get(i).intValue());
        }
        try {
            getWritableDatabase().execSQL("UPDATE msg SET synced=1 WHERE id IN (" + sb + ")");
        } catch (Exception e) {
            Log.e("HubStore", "markSynced failed", e);
        }
    }

    /** 返回 true 表示新插入（去重后）。 */
    public synchronized boolean insert(long ts, String pkg, String app, String title,
                                       String body, String sub, String cat, String key, String raw) {
        ContentValues v = new ContentValues();
        v.put("ts", ts);
        v.put("pkg", pkg);
        v.put("app", app);
        v.put("title", title);
        v.put("body", body);
        v.put("sub", sub);
        v.put("cat", cat);
        v.put("nkey", key);
        v.put("raw", raw);
        long id = getWritableDatabase().insertWithOnConflict("msg", null, v, SQLiteDatabase.CONFLICT_IGNORE);
        if (id > 0) {
            maybeTrim();
            return true;
        }
        return false;
    }

    private void maybeTrim() {
        try {
            getWritableDatabase().execSQL("DELETE FROM msg WHERE ts < ?",
                    new Object[]{System.currentTimeMillis() - KEEP_MS});
        } catch (Exception ignored) {
        }
    }

    public synchronized JSONArray query(long sinceTs, String pkg, int limit) {
        JSONArray arr = new JSONArray();
        String sql = "SELECT ts,pkg,app,title,body,sub,cat FROM msg WHERE ts >= ?";
        if (pkg != null && !pkg.isEmpty()) {
            sql += " AND pkg = ?";
        }
        sql += " ORDER BY ts DESC LIMIT ?";
        Cursor c = null;
        try {
            if (pkg != null && !pkg.isEmpty()) {
                c = getReadableDatabase().rawQuery(sql, new String[]{String.valueOf(sinceTs), pkg, String.valueOf(limit)});
            } else {
                c = getReadableDatabase().rawQuery(sql, new String[]{String.valueOf(sinceTs), String.valueOf(limit)});
            }
            while (c.moveToNext()) {
                JSONObject o = new JSONObject();
                o.put("ts", c.getLong(0));
                o.put("pkg", c.getString(1));
                o.put("app", c.getString(2));
                o.put("title", c.getString(3));
                o.put("text", c.getString(4));
                o.put("sub", c.getString(5));
                o.put("cat", c.getString(6));
                arr.put(o);
            }
        } catch (Exception e) {
            Log.e("HubStore", "query failed", e);
        } finally {
            if (c != null) c.close();
        }
        return arr;
    }

    public synchronized JSONArray stats() {
        JSONArray arr = new JSONArray();
        Cursor c = null;
        try {
            c = getReadableDatabase().rawQuery(
                    "SELECT pkg, COUNT(*), MAX(ts) FROM msg GROUP BY pkg ORDER BY COUNT(*) DESC", null);
            while (c.moveToNext()) {
                JSONObject o = new JSONObject();
                o.put("pkg", c.getString(0));
                o.put("count", c.getInt(1));
                o.put("lastTs", c.getLong(2));
                arr.put(o);
            }
        } catch (Exception e) {
            Log.e("HubStore", "stats failed", e);
        } finally {
            if (c != null) c.close();
        }
        return arr;
    }
}
