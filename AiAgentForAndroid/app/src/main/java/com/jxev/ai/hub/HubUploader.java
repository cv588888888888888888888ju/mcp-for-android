package com.jxev.ai.hub;

import android.content.Context;
import android.util.Log;

import com.jxev.ai.client.MCPSocketClient;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 把手机侧新落库的通知批量上报给 ECS 的信息中枢（服务器侧 hub.db）。
 * 走既有 9444 加密通道，不新开端口；失败就留着 synced=0，下一轮重试。
 */
public final class HubUploader {
    private static final String TAG = "HubUploader";
    private static final int BATCH = 200;

    private HubUploader() {}

    public static int flush(Context ctx, MCPSocketClient client) {
        if (client == null || !client.isConnected()) return 0;
        try {
            HubStore store = HubStore.get(ctx);
            JSONArray rows = store.drainUnsynced(BATCH);
            if (rows.length() == 0) return 0;
            JSONObject msg = new JSONObject();
            msg.put("type", "hub_batch");
            msg.put("source", "phone");
            msg.put("items", rows);
            if (!client.sendJson(msg)) return 0;
            List<Integer> ids = new ArrayList<>();
            for (int i = 0; i < rows.length(); i++) {
                ids.add(rows.getJSONObject(i).optInt("id"));
            }
            store.markSynced(ids);
            Log.i(TAG, "上报 " + rows.length() + " 条通知到服务器");
            return rows.length();
        } catch (Exception e) {
            Log.e(TAG, "flush failed", e);
            return 0;
        }
    }
}
