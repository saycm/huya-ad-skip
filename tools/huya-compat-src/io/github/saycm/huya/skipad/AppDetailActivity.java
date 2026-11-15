package io.github.saycm.huya.skipad;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.HashMap;
import java.util.Map;

public final class AppDetailActivity extends Activity {
    private SharedPreferences prefs;
    private AutomationPanel automation;
    private boolean serviceConnected;
    private final Map<String, Boolean> pending = new HashMap<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_app_detail);

        findViewById(R.id.detail_back).setOnClickListener(view -> finish());
        automation = new AutomationPanel(this, (LinearLayout) findViewById(R.id.automation_settings));

        boolean installed = packageInstalled("com.duowan.kiwi");
        TextView appStatus = (TextView) findViewById(R.id.app_status);
        Button openApp = (Button) findViewById(R.id.action_open_app);
        if (installed) {
            appStatus.setText(R.string.app_installed);
            openApp.setText(R.string.action_open);
            openApp.setOnClickListener(view -> startActivity(launcherIntent()));
        } else {
            appStatus.setText(R.string.app_missing);
            openApp.setText(R.string.app_missing);
            openApp.setEnabled(false);
        }

        Button copy = (Button) findViewById(R.id.copy_diagnostics_detail);
        copy.setOnClickListener(view -> copyDiagnostics(installed));

        prefs = getSharedPreferences(GlassSettings.PREFS, MODE_PRIVATE);
        bindSwitch(R.id.switch_ads_value, GlassSettings.KEY_ADS);
        bindSwitch(R.id.switch_live_cards_value, GlassSettings.KEY_LIVE_CARDS);
        bindSwitch(R.id.switch_rewards_value, GlassSettings.KEY_REWARDS);
        bindSwitch(R.id.switch_vip_ads_value, GlassSettings.KEY_VIP_ADS);
        bindSwitch(R.id.switch_vip_4k_value, GlassSettings.KEY_VIP_4K);
        GlassSettingsBridge.connect(remote ->
                runOnUiThread(() -> onRemoteConnected(remote)));
    }

    private boolean packageInstalled(String packageName) {
        try {
            getPackageManager().getApplicationInfo(packageName, 0);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private Intent launcherIntent() {
        Intent intent = getPackageManager().getLaunchIntentForPackage("com.duowan.kiwi");
        if (intent != null) {
            return intent;
        }
        intent = new Intent(Intent.ACTION_MAIN);
        intent.setClassName(
                "com.duowan.kiwi", "com.duowan.kiwi.simpleactivity.SplashActivity");
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return intent;
    }

    private void bindSwitch(int id, String key) {
        Switch toggle = (Switch) findViewById(id);
        toggle.setChecked(prefs.getBoolean(key, true));
        toggle.setOnCheckedChangeListener((button, checked) -> {
            prefs.edit().putBoolean(key, checked).apply();
            if (!serviceConnected) {
                pending.put(key, checked);
            }
        });
    }

    private void onRemoteConnected(SharedPreferences remote) {
        prefs = remote;
        automation.onRemoteConnected(remote);
        serviceConnected = true;
        for (Map.Entry<String, Boolean> entry : pending.entrySet()) {
            remote.edit().putBoolean(entry.getKey(), entry.getValue()).apply();
        }
        pending.clear();
        syncSwitch(R.id.switch_ads_value, GlassSettings.KEY_ADS);
        syncSwitch(R.id.switch_live_cards_value, GlassSettings.KEY_LIVE_CARDS);
        syncSwitch(R.id.switch_rewards_value, GlassSettings.KEY_REWARDS);
        syncSwitch(R.id.switch_vip_ads_value, GlassSettings.KEY_VIP_ADS);
        syncSwitch(R.id.switch_vip_4k_value, GlassSettings.KEY_VIP_4K);
    }

    private void syncSwitch(int id, String key) {
        Switch toggle = (Switch) findViewById(id);
        toggle.setChecked(prefs.getBoolean(key, true));
    }

    private void copyDiagnostics(boolean installed) {
        String text = "虎牙直播 · com.duowan.kiwi\n"
                + (installed ? getString(R.string.app_installed)
                             : getString(R.string.app_missing)) + "\n"
                + "ads=" + switchValue(R.id.switch_ads_value) + "\n"
                + "live=" + switchValue(R.id.switch_live_cards_value) + "\n"
                + "rewards=" + switchValue(R.id.switch_rewards_value) + "\n"
                + "vipAds=" + switchValue(R.id.switch_vip_ads_value) + "\n"
                + "vip4k=" + switchValue(R.id.switch_vip_4k_value);
        ClipboardManager clipboard =
                (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null) {
            clipboard.setPrimaryClip(ClipData.newPlainText(
                    getString(R.string.app_name), text));
        }
        Toast.makeText(this, R.string.copied_diagnostics, Toast.LENGTH_SHORT).show();
    }

    private boolean switchValue(int id) {
        return ((Switch) findViewById(id)).isChecked();
    }
}
