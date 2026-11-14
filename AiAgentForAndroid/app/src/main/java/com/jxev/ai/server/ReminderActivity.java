package com.jxev.ai.server;

import android.app.Activity;
import android.app.KeyguardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 锁屏/全屏强提醒（2026-11-10）。
 *
 * 悬浮条（ReminderOverlay）盖不住锁屏，系统层永远在它上面；这个 Activity 用
 * showWhenLocked + turnScreenOn 直接压在 keyguard 上，是「全屏窗口强提醒」那一条。
 *
 * 与悬浮条的分工：亮屏时用顶部条（不打断）；灭屏/锁屏时用本页（叫醒 + 全屏）。
 * 用户点「知道了」⇒ 收起悬浮条 + 落一条 ack（todo_id + 时间），供 PC 侧
 * reminder_ack_drain 取走并回写 info-hub 待办状态。
 */
public class ReminderActivity extends Activity {
    public static final String EXTRA_TITLE = "title";
    public static final String EXTRA_TEXT = "text";
    public static final String EXTRA_TODO_ID = "todo_id";

    private static final String PREF = "reminder_state";
    private static final String K_ACKS = "acks";

    private String todoId = "";

    /** 从任意 Context 拉起全屏提醒（Service 上下文需要 NEW_TASK）。 */
    public static void show(Context ctx, String title, String text, String todoId) {
        Intent i = new Intent(ctx, ReminderActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        i.putExtra(EXTRA_TITLE, title);
        i.putExtra(EXTRA_TEXT, text);
        i.putExtra(EXTRA_TODO_ID, todoId == null ? "" : todoId);
        try {
            ctx.startActivity(i);
        } catch (Exception ignored) {
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        }
        // 不挂 FLAG_KEEP_SCREEN_ON：叫醒屏幕后按系统超时自动熄灭，
        // 页面留在栈里，用户再亮屏时还在；否则夜里会一直亮着耗电。
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD);
        if (Build.VERSION.SDK_INT >= 21) {
            getWindow().setStatusBarColor(Color.parseColor("#7F0000"));
            getWindow().setNavigationBarColor(Color.parseColor("#7F0000"));
        }
        render(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        render(intent);
    }

    private void render(Intent intent) {
        String title = intent == null ? "" : intent.getStringExtra(EXTRA_TITLE);
        String text = intent == null ? "" : intent.getStringExtra(EXTRA_TEXT);
        todoId = intent == null ? "" : intent.getStringExtra(EXTRA_TODO_ID);
        if (TextUtils.isEmpty(title)) title = "强提醒";
        if (text == null) text = "";

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{Color.parseColor("#8E0000"), Color.parseColor("#4A0000"), Color.parseColor("#1A1A1A")});
        root.setBackground(bg);
        int pad = dp(24);
        root.setPadding(pad, dp(72), pad, dp(32));

        TextView label = new TextView(this);
        label.setText("INFO-HUB 强提醒");
        label.setTextColor(Color.parseColor("#FFCDD2"));
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        label.setLetterSpacing(0.2f);
        root.addView(label);

        TextView tvTitle = new TextView(this);
        tvTitle.setText(title);
        tvTitle.setTextColor(Color.WHITE);
        tvTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 32);
        tvTitle.setTypeface(Typeface.DEFAULT_BOLD);
        tvTitle.setPadding(0, dp(20), 0, dp(16));
        root.addView(tvTitle);

        TextView tvBody = new TextView(this);
        tvBody.setText(text);
        tvBody.setTextColor(Color.parseColor("#FFEBEE"));
        tvBody.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        tvBody.setLineSpacing(dp(6), 1f);
        root.addView(tvBody);

        View spacer = new View(this);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        root.addView(spacer, sp);

        TextView hint = new TextView(this);
        hint.setText("MCP for Android · 点「知道了」收起并回写 info-hub");
        hint.setTextColor(Color.parseColor("#B39DDB"));
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        hint.setGravity(Gravity.CENTER_HORIZONTAL);
        hint.setPadding(0, 0, 0, dp(16));
        root.addView(hint);

        Button ok = new Button(this);
        ok.setText("知道了");
        ok.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        ok.setTextColor(Color.parseColor("#8E0000"));
        ok.setBackgroundColor(Color.WHITE);
        ok.setOnClickListener(v -> acknowledge());
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(64));
        root.addView(ok, bp);

        setContentView(root);
    }

    private void acknowledge() {
        recordAck();
        ReminderOverlay.hide();
        finish();
    }

    /** 把 ack 落盘：PC 侧 reminder_ack_drain 取走后回写待办状态。 */
    private void recordAck() {
        try {
            android.content.SharedPreferences sp = getSharedPreferences(PREF, Context.MODE_PRIVATE);
            String raw = sp.getString(K_ACKS, "[]");
            JSONArray arr = new JSONArray(TextUtils.isEmpty(raw) ? "[]" : raw);
            JSONObject item = new JSONObject();
            item.put("todo_id", todoId == null ? "" : todoId);
            item.put("title", getIntent().getStringExtra(EXTRA_TITLE));
            item.put("at", System.currentTimeMillis());
            arr.put(item);
            sp.edit().putString(K_ACKS, arr.toString()).apply();
        } catch (Exception ignored) {
        }
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }
}
