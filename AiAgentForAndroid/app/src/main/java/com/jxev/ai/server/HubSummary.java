package com.jxev.ai.server;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * info-hub 待办摘要（2026-11-10）。
 *
 * PC 侧 hub-phone-remind sync 把 open/doing 待办整体推过来，这里落盘；
 * 顶部常驻条显示「INFO-HUB · N 条待处理」，点开是 HubListActivity 的完整列表。
 * 落盘是为了进程被杀重启后列表还在（与 ReminderOverlay 同一套常驻思路）。
 */
public final class HubSummary {
    private static final String TAG = "HubSummary";
    private static final String PREF = "hub_summary";
    private static final String K_ITEMS = "items";
    private static final String K_COUNT = "count";
    private static final String K_SYNCED_AT = "synced_at";

    private HubSummary() {
    }

    public static void save(Context ctx, JSONArray items, int count) {
        try {
            SharedPreferences sp = ctx.getApplicationContext()
                    .getSharedPreferences(PREF, Context.MODE_PRIVATE);
            sp.edit()
                    .putString(K_ITEMS, items == null ? "[]" : items.toString())
                    .putInt(K_COUNT, count)
                    .putLong(K_SYNCED_AT, System.currentTimeMillis())
                    .apply();
        } catch (Exception e) {
            Log.w(TAG, "保存摘要失败", e);
        }
    }

    /** 返回 items 数组（无数据时给空数组）。 */
    public static JSONArray items(Context ctx) {
        try {
            SharedPreferences sp = ctx.getApplicationContext()
                    .getSharedPreferences(PREF, Context.MODE_PRIVATE);
            return new JSONArray(sp.getString(K_ITEMS, "[]"));
        } catch (Exception e) {
            return new JSONArray();
        }
    }

    public static int count(Context ctx) {
        return ctx.getApplicationContext()
                .getSharedPreferences(PREF, Context.MODE_PRIVATE).getInt(K_COUNT, 0);
    }

    public static long syncedAt(Context ctx) {
        return ctx.getApplicationContext()
                .getSharedPreferences(PREF, Context.MODE_PRIVATE).getLong(K_SYNCED_AT, 0L);
    }
}
