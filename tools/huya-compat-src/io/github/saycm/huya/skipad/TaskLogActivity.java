package io.github.saycm.huya.skipad;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;

public final class TaskLogActivity extends Activity {
    private LinearLayout logs;
    private Button all;
    private Button issues;
    private boolean onlyIssues;
    private TextView syncStatus;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable afterSync = this::renderLogs;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundResource(R.drawable.dashboard_background);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = Math.round(20 * getResources().getDisplayMetrics().density);
        root.setPadding(pad, pad, pad, pad);
        scroll.addView(root);
        setContentView(scroll);
        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView back = text("‹ 返回", 16, R.color.huya_accent);
        back.setGravity(Gravity.CENTER_VERTICAL);
        back.setMinHeight(dp(48));
        back.setOnClickListener(view -> finish());
        header.addView(back);
        TextView title = text("任务日志", 22, R.color.glass_text);
        title.setTypeface(null, Typeface.BOLD);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(-2, -2);
        titleParams.leftMargin = dp(18);
        header.addView(title, titleParams);
        root.addView(header);

        LinearLayout guide = card(root);
        TextView caption = text("每一步，都有记录", 17, R.color.glass_text);
        caption.setTypeface(null, Typeface.BOLD);
        guide.addView(caption);
        TextView note = text("日志仅保存在本机。可查看任务是否触发，以及每个粉丝牌、直播间的执行结果。", 13, R.color.glass_secondary);
        note.setPadding(0, dp(8), 0, dp(8));
        guide.addView(note);
        guide.addView(text("“请求已发出”仍需等待回执，没有后续记录表示结果尚未确认。", 13, R.color.glass_secondary));
        syncStatus = text("打开虎牙后，可在这里同步最新日志。", 12, R.color.huya_accent);
        syncStatus.setPadding(0, dp(10), 0, 0);
        guide.addView(syncStatus);

        LinearLayout actions = row(root);
        Button refresh = button("刷新日志", true);
        refresh.setOnClickListener(view -> refreshLogs());
        actions.addView(refresh, weighted(false));
        Button clear = button("清空记录", false);
        clear.setOnClickListener(view -> new AlertDialog.Builder(this,
                android.R.style.Theme_Material_Light_Dialog_Alert)
                .setMessage("清空日志不会重置今日执行记录，也不会重复打卡或送礼。")
                .setNegativeButton("取消", null)
                .setPositiveButton("清空", (dialog, which) -> {
                    getContentResolver().call(TaskLogProvider.URI, "clear", null, null);
                    refreshLogs();
                }).show());
        actions.addView(clear, weighted(true));
        LinearLayout filters = row(root);
        all = button("全部记录", true);
        issues = button("异常与跳过", false);
        all.setOnClickListener(view -> { onlyIssues = false; refreshLogs(); });
        issues.setOnClickListener(view -> { onlyIssues = true; refreshLogs(); });
        filters.addView(all, weighted(false));
        filters.addView(issues, weighted(true));
        logs = new LinearLayout(this);
        logs.setOrientation(LinearLayout.VERTICAL);
        root.addView(logs);
        refreshLogs();
    }

    private void refreshLogs() {
        sendBroadcast(new Intent(DailyAutomation.SYNC_LOGS).setPackage("com.duowan.kiwi"));
        renderLogs();
        handler.removeCallbacks(afterSync);
        handler.postDelayed(afterSync, 900L);
    }

    @Override protected void onDestroy() {
        handler.removeCallbacks(afterSync);
        super.onDestroy();
    }

    private void renderLogs() {
        Bundle result = getContentResolver().call(TaskLogProvider.URI, "read", null, null);
        ArrayList<Bundle> entries = result == null ? null : result.getParcelableArrayList("events");
        logs.removeAllViews();
        styleButton(all, !onlyIssues);
        styleButton(issues, onlyIssues);
        SimpleDateFormat time = new SimpleDateFormat("MM-dd HH:mm:ss", Locale.CHINA);
        long synced = result == null ? 0 : result.getLong("last_sync", 0);
        syncStatus.setText(synced == 0 ? "等待同步：请先打开虎牙，再刷新日志。"
                : "上次同步 " + time.format(new Date(synced)) + "；虎牙未运行时显示已同步的记录。");
        int visible = 0;
        if (entries != null) for (Bundle entry : entries) {
            String state = entry.getString("state", "");
            boolean issue = isIssue(state);
            if (onlyIssues && !issue) continue;
            visible++;
            LinearLayout item = card(logs);
            LinearLayout heading = new LinearLayout(this);
            heading.setGravity(Gravity.CENTER_VERTICAL);
            TextView task = text(entry.getString("task", ""), 16, R.color.glass_text);
            task.setTypeface(null, Typeface.BOLD);
            heading.addView(task, new LinearLayout.LayoutParams(0, -2, 1));
            int color = ("成功".equals(state) || "已打卡".equals(state)) ? getColor(R.color.success_green)
                    : issue ? getColor(R.color.warning_amber) : getColor(R.color.huya_accent);
            TextView badge = text(state, 12, R.color.glass_text);
            badge.setTextColor(color);
            badge.setPadding(dp(10), dp(5), dp(10), dp(5));
            GradientDrawable fill = new GradientDrawable();
            fill.setColor((color & 0x00ffffff) | 0x18000000);
            fill.setCornerRadius(dp(12));
            badge.setBackground(fill);
            heading.addView(badge);
            item.addView(heading);
            TextView stamp = text(time.format(new Date(entry.getLong("time"))), 12, R.color.glass_secondary);
            stamp.setPadding(0, dp(6), 0, dp(8));
            item.addView(stamp);
            String target = entry.getString("target", "");
            if (!target.isEmpty()) {
                TextView room = text(target, 14, R.color.glass_text);
                room.setPadding(0, 0, 0, dp(6));
                item.addView(room);
            }
            TextView detail = text(entry.getString("detail", ""), 14, R.color.glass_secondary);
            detail.setTextIsSelectable(true);
            item.addView(detail);
        }
        if (visible == 0) {
            LinearLayout empty = card(logs);
            empty.addView(text(onlyIssues ? "暂无异常或跳过记录" : "还没有任务记录", 17, R.color.glass_text));
            TextView hint = text(onlyIssues ? "可切换到全部记录查看执行过程。"
                    : "打开虎牙后，回到这里查看任务触发及执行结果。", 14, R.color.glass_secondary);
            hint.setPadding(0, dp(8), 0, 0);
            empty.addView(hint);
        }
        TextView footnote = text("仅保留本机最近 500 条 · 当前最多显示 300 条", 12, R.color.glass_secondary);
        footnote.setPadding(dp(4), dp(16), 0, dp(12));
        logs.addView(footnote);
    }

    private boolean isIssue(String state) {
        return "失败".equals(state) || "未完成".equals(state) || "结果未确认".equals(state)
                || "未执行".equals(state)
                || "跳过".equals(state) || "阻止发送".equals(state)
                || "取消".equals(state) || "停止".equals(state);
    }

    private TextView text(String value, int size, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(getColor(color));
        view.setLineSpacing(dp(2), 1.12f);
        return view;
    }

    private LinearLayout card(LinearLayout parent) {
        LinearLayout view = new LinearLayout(this);
        view.setOrientation(LinearLayout.VERTICAL);
        view.setPadding(dp(16), dp(16), dp(16), dp(16));
        view.setBackgroundResource(R.drawable.glass_card);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(14);
        parent.addView(view, params);
        return view;
    }

    private LinearLayout row(LinearLayout parent) {
        LinearLayout view = new LinearLayout(this);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(14);
        parent.addView(view, params);
        return view;
    }

    private LinearLayout.LayoutParams weighted(boolean inset) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(46), 1);
        if (inset) params.leftMargin = dp(10);
        return params;
    }

    private Button button(String title, boolean active) {
        Button view = new Button(this);
        view.setText(title);
        view.setTextSize(14);
        view.setAllCaps(false);
        styleButton(view, active);
        return view;
    }

    private void styleButton(Button view, boolean active) {
        view.setBackgroundResource(active ? R.drawable.accent_button : R.drawable.glass_card);
        view.setTextColor(getColor(active ? R.color.hero_text : R.color.huya_accent));
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
