package io.github.saycm.huya.skipad;

import android.content.SharedPreferences;
import android.util.Log;

import io.github.libxposed.service.XposedService;
import io.github.libxposed.service.XposedServiceHelper;

public final class GlassSettingsBridge {
    private static final String TAG = "FuckAD";
    private static volatile SharedPreferences connected;
    private static volatile Connected callback;
    private static boolean registered;

    public interface Connected {
        void onConnected(SharedPreferences prefs);
    }

    private GlassSettingsBridge() {
    }

    public static synchronized void connect(Connected current) {
        callback = current;
        SharedPreferences prefs = connected;
        if (prefs != null) {
            if (current != null) {
                current.onConnected(prefs);
            }
            return;
        }
        if (registered) {
            return;
        }
        registered = true;
        XposedServiceHelper.registerListener(new XposedServiceHelper.OnServiceListener() {
            @Override
            public void onServiceBind(XposedService service) {
                try {
                    connected = service.getRemotePreferences(GlassSettings.PREFS);
                    Connected listener = callback;
                    if (listener != null) {
                        listener.onConnected(connected);
                    }
                } catch (RuntimeException failure) {
                    Log.w(TAG, "remote preferences unavailable", failure);
                }
            }

            @Override
            public void onServiceDied(XposedService service) {
            }
        });
    }

    public static SharedPreferences get() {
        return connected;
    }
}
