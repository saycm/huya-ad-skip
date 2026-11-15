package io.github.saycm.huya.skipad;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.content.Intent;
import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.LinkedHashMap;
import java.util.Map;

final class AutomationPanel {
    private final Activity activity;
    private SharedPreferences prefs;
    private LinearLayout roomList;
    private Switch sign;
    private Switch gift;
    private Button add;
    private boolean syncing;

    AutomationPanel(Activity activity, LinearLayout root) {
        this.activity = activity;
        label(root, "每日自动任务", 19);
        label(root, "每天首次打开虎牙、登录就绪后在后台执行，不切换直播间。", 14);
        sign = toggle(root, "已有粉丝牌自动打卡", GlassSettings.KEY_AUTO_SIGN);
        gift = toggle(root, "指定直播间赠送免费虎粮", GlassSettings.KEY_AUTO_GIFT);
        label(root, "只使用免费库存；不足时跳过，不购买。修改后请强制停止虎牙再打开。", 13);
        roomList = column();
        root.addView(roomList);
        add = button(root, "添加直播间", view -> editRoom(null, null));
        add.setEnabled(false);
        button(root, "查看任务日志", view ->
                activity.startActivity(new Intent(activity, TaskLogActivity.class)));
    }

    void onRemoteConnected(SharedPreferences remote) {
            prefs = remote;
            syncing = true;
            sign.setChecked(remote.getBoolean(GlassSettings.KEY_AUTO_SIGN, false));
            gift.setChecked(remote.getBoolean(GlassSettings.KEY_AUTO_GIFT, false));
            syncing = false;
            sign.setEnabled(true);
            gift.setEnabled(true);
            add.setEnabled(true);
            renderRooms();
    }

    private Switch toggle(LinearLayout root, String title, String key) {
        Switch view = new Switch(activity);
        view.setText(title);
        view.setTextColor(activity.getColor(R.color.glass_text));
        view.setTextSize(15);
        view.setPadding(0, dp(16), 0, dp(16));
        view.setEnabled(false);
        view.setOnCheckedChangeListener((button, enabled) -> {
            if (!syncing && prefs != null) prefs.edit().putBoolean(key, enabled).apply();
        });
        root.addView(view);
        return view;
    }

    private Map<Long, Integer> rooms() {
        return new LinkedHashMap<>(AutomationRules.parse(
                prefs.getString(GlassSettings.KEY_GIFT_ROOMS, "")));
    }

    private void renderRooms() {
        roomList.removeAllViews();
        for (Map.Entry<Long, Integer> room : rooms().entrySet()) {
            LinearLayout row = column();
            label(row, "直播间 " + room.getKey() + " · 每天 " + room.getValue() + " 个免费虎粮", 15);
            button(row, "修改", view -> editRoom(room.getKey(), room.getValue()));
            button(row, "删除", view -> {
                Map<Long, Integer> values = rooms();
                values.remove(room.getKey());
                saveRooms(values);
            });
            roomList.addView(row);
        }
    }

    private void editRoom(Long oldRoom, Integer oldCount) {
        LinearLayout fields = column();
        fields.setPadding(dp(24), dp(12), dp(24), 0);
        EditText room = new EditText(activity);
        room.setHint("直播间房间号");
        room.setTextColor(activity.getColor(R.color.glass_text));
        room.setHintTextColor(activity.getColor(R.color.glass_secondary));
        room.setInputType(InputType.TYPE_CLASS_NUMBER);
        EditText count = new EditText(activity);
        count.setHint("每天赠送数量");
        count.setTextColor(activity.getColor(R.color.glass_text));
        count.setHintTextColor(activity.getColor(R.color.glass_secondary));
        count.setInputType(InputType.TYPE_CLASS_NUMBER);
        if (oldRoom != null) room.setText(oldRoom.toString());
        if (oldCount != null) count.setText(oldCount.toString());
        fields.addView(room);
        fields.addView(count);
        AlertDialog dialog = new AlertDialog.Builder(activity,
                android.R.style.Theme_Material_Light_Dialog_Alert).setTitle("免费虎粮赠送")
                .setView(fields).setNegativeButton("取消", null)
                .setPositiveButton("保存", null).create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(view -> {
                    try {
                        Map<Long, Integer> values = rooms();
                        Map<Long, Integer> single = AutomationRules.parse(
                                room.getText().toString() + "=" + count.getText().toString());
                        long id = single.keySet().iterator().next();
                        if (values.containsKey(id) && !Long.valueOf(id).equals(oldRoom)) {
                            throw new IllegalArgumentException("该直播间已添加");
                        }
                        if (oldRoom != null) values.remove(oldRoom);
                        values.putAll(single);
                        saveRooms(values);
                        dialog.dismiss();
                    } catch (IllegalArgumentException failure) {
                        Toast.makeText(activity, "请填写有效且不重复的房间号和正整数数量", Toast.LENGTH_SHORT).show();
                    }
                }));
        dialog.show();
    }

    private void saveRooms(Map<Long, Integer> values) {
        StringBuilder text = new StringBuilder();
        for (Map.Entry<Long, Integer> room : values.entrySet()) {
            text.append(room.getKey()).append('=').append(room.getValue()).append('\n');
        }
        prefs.edit().putString(GlassSettings.KEY_GIFT_ROOMS, text.toString()).apply();
        renderRooms();
    }

    private LinearLayout column() {
        LinearLayout view = new LinearLayout(activity);
        view.setOrientation(LinearLayout.VERTICAL);
        return view;
    }

    private TextView label(LinearLayout parent, String text, int size) {
        TextView view = new TextView(activity);
        view.setText(text);
        view.setTextSize(size);
        view.setTextColor(activity.getColor(R.color.glass_text));
        view.setPadding(0, dp(10), 0, dp(10));
        parent.addView(view);
        return view;
    }

    private Button button(LinearLayout parent, String text, View.OnClickListener listener) {
        Button view = new Button(activity);
        view.setText(text);
        view.setTextSize(14);
        view.setAllCaps(false);
        boolean delete = "删除".equals(text);
        view.setBackgroundResource(delete ? R.drawable.glass_card : R.drawable.accent_button);
        view.setTextColor(activity.getColor(delete ? R.color.glass_secondary : R.color.hero_text));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, dp(46));
        params.topMargin = dp(10);
        view.setLayoutParams(params);
        view.setOnClickListener(listener);
        parent.addView(view);
        return view;
    }

    private int dp(int value) { return Math.round(value * activity.getResources().getDisplayMetrics().density); }
}
