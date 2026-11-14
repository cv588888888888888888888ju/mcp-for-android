package com.jxev.ai.server;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * info-hub 待办全量列表（2026-11-10）。
 *
 * 入口：点顶部常驻条「INFO-HUB · N 条待处理」。
 * 数据：HubSummary（PC 侧 hub-phone-remind sync 推来的 open/doing 待办），按类别分组显示。
 * 只读：这里不写库、不改状态；状态回写走 PC 侧。
 */
public class HubListActivity extends Activity {

    private static final class Row {
        final boolean header;
        final String text;
        final String sub;

        Row(boolean header, String text, String sub) {
            this.header = header;
            this.text = text;
            this.sub = sub;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF121212);
        root.setPadding(dp(20), dp(56), dp(20), 0);

        int total = HubSummary.count(this);
        TextView title = new TextView(this);
        title.setText("info-hub · " + total + " 条待处理");
        title.setTextColor(Color.WHITE);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title);

        long syncedAt = HubSummary.syncedAt(this);
        TextView sub = new TextView(this);
        sub.setText(syncedAt > 0
                ? "同步于 " + new SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(syncedAt)
                : "尚未同步（PC 侧跑 hub-phone-remind sync）");
        sub.setTextColor(0xFFB0BEC5);
        sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        sub.setPadding(0, dp(6), 0, dp(12));
        root.addView(sub);

        List<Row> rows = buildRows(HubSummary.items(this));
        ListView list = new ListView(this);
        list.setDivider(new ColorDrawable(0xFF222222));
        list.setDividerHeight(1);
        list.setAdapter(new RowAdapter(rows));
        root.addView(list, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);
    }

    private List<Row> buildRows(JSONArray items) {
        Map<String, List<JSONObject>> byKind = new LinkedHashMap<>();
        for (int i = 0; i < items.length(); i++) {
            JSONObject o = items.optJSONObject(i);
            if (o == null) continue;
            String kind = o.optString("kind", "other");
            if ("".equals(kind)) kind = "other";
            byKind.computeIfAbsent(kind, k -> new ArrayList<>()).add(o);
        }
        List<Row> rows = new ArrayList<>();
        for (Map.Entry<String, List<JSONObject>> e : byKind.entrySet()) {
            rows.add(new Row(true, e.getKey() + "  ·  " + e.getValue().size(), null));
            for (JSONObject o : e.getValue()) {
                String t = o.optString("title", "");
                String w = o.optString("why", "");
                String status = o.optString("status", "");
                String prefix = "doing".equals(status) ? "● " : "○ ";
                rows.add(new Row(false, prefix + t, TextUtils.isEmpty(w) ? null : w));
            }
        }
        return rows;
    }

    private final class RowAdapter extends BaseAdapter {
        private final List<Row> rows;

        RowAdapter(List<Row> rows) {
            this.rows = rows;
        }

        @Override
        public int getCount() {
            return rows.size();
        }

        @Override
        public Object getItem(int position) {
            return rows.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public boolean isEnabled(int position) {
            return !rows.get(position).header;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            Row row = rows.get(position);
            LinearLayout box = new LinearLayout(HubListActivity.this);
            box.setOrientation(LinearLayout.VERTICAL);
            box.setPadding(dp(4), dp(row.header ? 16 : 10), dp(4), dp(row.header ? 6 : 10));

            TextView tv = new TextView(HubListActivity.this);
            tv.setText(row.text);
            tv.setTextColor(row.header ? 0xFFFF8A80 : 0xFFECEFF1);
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, row.header ? 14 : 15);
            if (row.header) {
                tv.setTypeface(Typeface.DEFAULT_BOLD);
            }
            box.addView(tv);

            if (row.sub != null) {
                TextView tvSub = new TextView(HubListActivity.this);
                tvSub.setText(row.sub);
                tvSub.setTextColor(0xFF90A4AE);
                tvSub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
                tvSub.setPadding(0, dp(2), 0, 0);
                box.addView(tvSub);
            }
            return box;
        }
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }
}
