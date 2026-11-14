package io.github.saycm.huya.skipad;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONObject;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public final class HuyaCompatEntry implements IXposedHookLoadPackage {
    private static final String TAG = "HuyaMiniappAD";
    private static final String TARGET_PACKAGE = "com.duowan.kiwi";
    private static final String TARGET_CLASS =
            "com.huya.kiwi.hyext.impl.modules.internal.HyExtAd";
    private static final String AMS_HELPER_CLASS =
            "com.duowan.kiwi.ad.helper.AmsAdHelper";
    private static final String AMS_LISTENER_CLASS =
            "com.huya.adbusiness.ams.IAmsAdRewardADListener";
    private static final String REWARD_RESULT_CLASS =
            "com.qq.e.tg.rewardAD.RewardResult";
    private static final String AD_SPLASH_FRAGMENT_CLASS =
            "com.duowan.kiwi.adsplash.view.AdSplashFragment";
    private static final String AWARD_MANAGER_CLASS =
            "com.duowan.biz.videoad.impl.HyLotteryAdAwardManager";

    private static final AtomicInteger AMS_REQUESTS = new AtomicInteger();
    private static final AtomicInteger AMS_SESSIONS = new AtomicInteger();
    private static final AtomicInteger AWARD_CALLS = new AtomicInteger();

    private static Handler mainHandler;
    private boolean hooksInitialized;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam param) {
        if (param == null || !TARGET_PACKAGE.equals(param.packageName)) {
            return;
        }

        XposedBridge.log(TAG + " waiting for application in " + param.processName);
        try {
            Method created = android.app.Instrumentation.class.getDeclaredMethod(
                    "callApplicationOnCreate", android.app.Application.class);
            XposedBridge.hookMethod(created, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam hook) {
                    // Tinker and Qigsaw replace the loader during attachBaseContext.
                    initializeHooks(((android.app.Application) hook.args[0]).getClassLoader());
                }
            });
        } catch (Throwable throwable) {
            XposedBridge.log(throwable);
        }
    }

    private synchronized void initializeHooks(ClassLoader classLoader) {
        if (hooksInitialized) {
            return;
        }
        hooksInitialized = true;
        XposedBridge.log(TAG + " initializing with application loader " + classLoader);
        hookMiniappReward(classLoader);
        hookAmsReward(classLoader);
        hookAwardRequests(classLoader);
        hookAdRequestDiagnostics(classLoader);
        hookDisplayAds(classLoader);
        hookListAds(classLoader);
        hookSplash(classLoader);
    }

    private static void hookDisplayAds(ClassLoader classLoader) {
        try {
            Class<?> type = classLoader.loadClass(
                    "com.duowan.kiwi.adsplash.controller.AdRouter");
            Method route = findMethod(type, "isNeedShowAdSplash", 4);
            if (route != null) {
                XposedBridge.hookMethod(route, new XC_MethodReplacement(10000) {
                    @Override
                    protected Object replaceHookedMethod(MethodHookParam param) {
                        // 6 is Huya's own "splash disabled" result. The caller still
                        // completes its normal NO_AD callbacks and opens the homepage.
                        XposedBridge.log(TAG + " blocked splash before display");
                        return 6;
                    }
                });
                XposedBridge.log(TAG + " hooked splash display decision");
            }
        } catch (Throwable throwable) {
            XposedBridge.log(throwable);
        }
        try {
            Class<?> type = classLoader.loadClass("com.duowan.kiwi.ad.impl.HyAdModule");
            Method shield = findMethod(type, "isNeedShieldAdOutLivingRoom", 0);
            if (shield != null) {
                XposedBridge.hookMethod(shield, new XC_MethodReplacement(10000) {
                    @Override
                    protected Object replaceHookedMethod(MethodHookParam param) {
                        return true;
                    }
                });
                // The in-room shield also guards rewarded-ad requests, so leave it alone.
                XposedBridge.log(TAG + " enabled ordinary ad request shield");
            }
        } catch (Throwable throwable) {
            XposedBridge.log(throwable);
        }
        try {
            Class<?> type = classLoader.loadClass(
                    "com.duowan.kiwi.list.patface.chain.PatFaceInterceptorChain");
            Method proceed = type.getDeclaredMethod("proceed", int.class);
            XposedBridge.hookMethod(proceed, new XC_MethodReplacement(10000) {
                @Override
                protected Object replaceHookedMethod(MethodHookParam param) {
                    // Stop before constructing or enqueuing the promotional dialog.
                    XposedBridge.log(TAG + " blocked automatic promotion location=" + param.args[0]);
                    return false;
                }
            });
            XposedBridge.log(TAG + " hooked automatic promotion decision");
        } catch (Throwable throwable) {
            XposedBridge.log(throwable);
        }
    }

    private static void hookListAds(ClassLoader classLoader) {
        List<String> mutations = Arrays.asList(
                "replace", "replaceAndNotify", "replaceData", "add", "addAndNotify",
                "insert", "insertAndNotify", "insertList", "insertListAndNotify",
                "insertItemAndNotify", "insertItemDataOnly",
                "insertItemListAndNotify", "insertItemListDataOnly");
        for (String name : new String[]{
                "com.duowan.kiwi.listframe.adapter.ListLineRecyclerViewAdapter",
                "com.duowan.kiwi.listline.ListLineAdapter"}) {
            try {
                Class<?> type = classLoader.loadClass(name);
                int count = 0;
                for (Method method : type.getDeclaredMethods()) {
                    if (!mutations.contains(method.getName())
                            || method.getReturnType() != void.class
                            || method.getParameterCount() == 0) {
                        continue;
                    }
                    Class<?> first = method.getParameterTypes()[0];
                    if (first != List.class && !"com.duowan.kiwi.listframe.component.LineItem"
                            .equals(first.getName())) {
                        continue;
                    }
                    XposedBridge.hookMethod(method, new XC_MethodHook(10000) {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            Object input = param.args[0];
                            if (input instanceof List) {
                                List<?> original = (List<?>) input;
                                List<Object> filtered = new ArrayList<>(original.size());
                                for (Object item : original) {
                                    if (!isAdLineItem(item)) {
                                        filtered.add(item);
                                    }
                                }
                                int removed = original.size() - filtered.size();
                                if (removed > 0) {
                                    param.args[0] = filtered;
                                    XposedBridge.log(TAG + " filtered " + removed
                                            + " list ads via " + param.method.getName());
                                }
                            } else if (isAdLineItem(input)) {
                                // Skip the notification as well as the insertion.
                                param.setResult(null);
                                XposedBridge.log(TAG + " blocked single list ad");
                            }
                        }
                    });
                    count++;
                }
                XposedBridge.log(TAG + " hooked " + name + " mutations=" + count);
            } catch (Throwable throwable) {
                XposedBridge.log(throwable);
            }
        }
    }

    private static boolean isAdLineItem(Object item) {
        if (item == null) {
            return false;
        }
        try {
            Object view = item.getClass().getMethod("getLineItem").invoke(item);
            if (view == null) {
                return false;
            }
            String name = view.getClass().getName();
            if ("com.duowan.kiwi.list.component.RecommendAdComponent$ViewObject".equals(name)
                    || "com.duowan.kiwi.ad.ui.BigPicAdComponentViewObject".equals(name)
                    || "com.duowan.kiwi.ad.ui.SmallPicAdComponentViewObject".equals(name)) {
                return true;
            }
            if (field(view, "liveListAdInfo") != null
                    || field(view, "mLiveListAdInfo") != null) {
                return true;
            }
            Object user = "com.duowan.HUYA.UserRecItem".equals(name)
                    ? view : field(view, "userRecItem");
            if (user == null) {
                user = field(view, "mUserRecItem");
            }
            Object card = field(user, "tMCard");
            Object kind = field(card, "iCardType");
            Object ads = field(card, "vAdCard");
            return kind instanceof Number && ((Number) kind).intValue() == 4
                    && ads instanceof List && !((List<?>) ads).isEmpty();
        } catch (Throwable throwable) {
            // Unknown rows keep their original behavior.
            return false;
        }
    }

    private static void hookAdRequestDiagnostics(ClassLoader classLoader) {
        try {
            Class<?> type = classLoader.loadClass("com.duowan.biz.videoad.impl.HyVideoAdModule");
            for (Method method : type.getDeclaredMethods()) {
                if ("goVideoAdWithCallback".equals(method.getName())
                        || "lambda$onGetAdFail$2".equals(method.getName())) {
                    XposedBridge.hookMethod(method, new XC_MethodHook(10000) {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            XposedBridge.log(TAG + " request entry " + param.method.getName());
                        }
                    });
                }
            }
            Method failed = findMethod(type, "onGetAdFail", 8);
            if (failed != null) {
                XposedBridge.hookMethod(failed, new XC_MethodHook(10000) {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        XposedBridge.log(TAG + " ad request failed: code=" + param.args[5]
                                + " reason=" + shortString(param.args[6])
                                + " scene=" + shortString(param.args[2])
                                + " " + mapSummary(param.args[7]));
                    }
                });
            }
            Method parsed = findMethod(type, "parseAdResult", 12);
            if (parsed != null) {
                XposedBridge.hookMethod(parsed, new XC_MethodHook(10000) {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        XposedBridge.log(TAG + " parseAdResult: ad="
                                + field(param.args[3], "id")
                                + " taskResult=" + field(param.args[4], "iRet")
                                + " " + mapSummary(param.args[11]));
                    }
                });
            }
            XposedBridge.log(TAG + " ad request diagnostics installed");
        } catch (Throwable throwable) {
            XposedBridge.log(throwable);
        }
    }

    private static void hookMiniappReward(ClassLoader classLoader) {
        try {
            Class<?> type = classLoader.loadClass(TARGET_CLASS);
            Method show = findMethod(type, "showRewardedVideoAd", 2);
            if (show == null) {
                Log.w(TAG, "showRewardedVideoAd not found");
                return;
            }

            XposedBridge.hookMethod(show, new XC_MethodReplacement(10000) {
                @Override
                protected Object replaceHookedMethod(MethodHookParam hookParam) throws Throwable {
                    if (hookParam.args.length < 2) {
                        return XposedBridge.invokeOriginalMethod(
                                hookParam.method, hookParam.thisObject, hookParam.args);
                    }

                    String adId = readAdId(hookParam.args[0]);
                    if (adId == null || adId.isEmpty()) {
                        return XposedBridge.invokeOriginalMethod(
                                hookParam.method, hookParam.thisObject, hookParam.args);
                    }

                    final Object callback = hookParam.args[1];
                    final Object module = hookParam.thisObject;
                    callSuccess(callback);
                    getMainHandler().postDelayed(
                            () -> emitRewardClosed(module, adId), 100L);
                    Log.i(TAG, "skipped rewarded video " + adId);
                    return null;
                }
            });
            Log.i(TAG, "hooked " + TARGET_CLASS + ".showRewardedVideoAd/2");
        } catch (Throwable throwable) {
            Log.w(TAG, "init failed", throwable);
            XposedBridge.log(throwable);
        }
    }

    private static void hookAmsReward(ClassLoader classLoader) {
        try {
            Class<?> type = classLoader.loadClass(AMS_HELPER_CLASS);
            Method request = findMethod(type, "onCallAmsRewardAd", 6);
            if (request == null) {
                Log.w(TAG, "onCallAmsRewardAd not found");
                return;
            }

            XposedBridge.hookMethod(request, new XC_MethodReplacement(10000) {
                @Override
                protected Object replaceHookedMethod(MethodHookParam hookParam) throws Throwable {
                    if (hookParam.args.length < 6 || hookParam.args[4] == null) {
                        return XposedBridge.invokeOriginalMethod(
                                hookParam.method, hookParam.thisObject, hookParam.args);
                    }

                    Object listener = hookParam.args[4];
                    int requestId = AMS_REQUESTS.incrementAndGet();
                    XposedBridge.log(TAG + " intercepted AMS reward request #" + requestId);
                    Log.i(TAG, "AMS request #" + requestId + " " + summarizeAmsArgs(hookParam.args));
                    finishAmsReward(listener, requestId);
                    Log.i(TAG, "skipped AMS rewarded video on user click #" + requestId);
                    return null;
                }
            });
            XposedBridge.log(TAG + " hooked " + AMS_HELPER_CLASS + ".onCallAmsRewardAd/6");
        } catch (Throwable throwable) {
            Log.w(TAG, "AMS init failed", throwable);
            XposedBridge.log(throwable);
        }
    }

    private static void hookAwardRequests(ClassLoader classLoader) {
        try {
            Class<?> type = classLoader.loadClass(AWARD_MANAGER_CLASS);
            Method award = findMethod(type, "awardAdTaskIfNeed", 6);
            if (award != null) {
                XposedBridge.hookMethod(award, new XC_MethodHook(10000) {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam hookParam) {
                        Log.i(TAG, "awardAdTaskIfNeed #" + AWARD_CALLS.incrementAndGet()
                                + " " + summarizeAwardArgs(hookParam.args));
                    }
                });
            } else {
                Log.w(TAG, "awardAdTaskIfNeed/6 not found");
            }
            hookAwardMethod(type, "onAwardAdTask", 1);
            hookAwardMethod(type, "onAwardCommonAdTask", 6);
            hookAwardMethod(type, "onAwardCommonRewardAdTask", 6);
            Log.i(TAG, "hooked reward accounting in " + AWARD_MANAGER_CLASS);
        } catch (Throwable throwable) {
            Log.w(TAG, "award diagnostics init failed", throwable);
            XposedBridge.log(throwable);
        }
    }

    private static void hookAwardMethod(Class<?> type, String name, int parameterCount) {
        Method method = findMethod(type, name, parameterCount);
        if (method == null) {
            Log.w(TAG, "award method not found " + name + "/" + parameterCount);
            return;
        }
        XposedBridge.hookMethod(method, new XC_MethodHook(10000) {
            @Override
            protected void beforeHookedMethod(MethodHookParam hookParam) {
                Log.i(TAG, name + " #" + AWARD_CALLS.incrementAndGet()
                        + " " + summarizeAwardArgs(hookParam.args));
            }
        });
    }

    private static void hookSplash(ClassLoader classLoader) {
        try {
            Class<?> type = classLoader.loadClass(AD_SPLASH_FRAGMENT_CLASS);
            Method visible = findMethod(type, "onVisibleToUser", 0);
            if (visible == null) {
                Log.w(TAG, "AdSplashFragment.onVisibleToUser not found");
                return;
            }

            XposedBridge.hookMethod(visible, new XC_MethodHook(10000) {
                @Override
                protected void afterHookedMethod(MethodHookParam hookParam) throws Throwable {
                    if (hookParam.thisObject == null || hookParam.getThrowable() != null) {
                        return;
                    }
                    try {
                        Method dismiss = hookParam.thisObject.getClass().getMethod(
                                "dismissWhenError", String.class);
                        dismiss.invoke(hookParam.thisObject, "module_skip_splash");
                        Log.i(TAG, "skipped splash ad");
                    } catch (Throwable throwable) {
                        Log.w(TAG, "splash dismiss failed", throwable);
                    }
                }
            });
            Log.i(TAG, "hooked " + AD_SPLASH_FRAGMENT_CLASS + ".onVisibleToUser/0");
        } catch (Throwable throwable) {
            Log.w(TAG, "splash init failed", throwable);
            XposedBridge.log(throwable);
        }
    }

    private static void finishAmsReward(Object listener, int requestId) {
        int sessionId = AMS_SESSIONS.incrementAndGet();
        Log.i(TAG, "AMS session #" + sessionId + " for request #" + requestId
                + " listener=" + identity(listener));
        invokeListener(listener, "onADShow", new Class<?>[0]);
        invokeListener(listener, "onADExpose", new Class<?>[0]);
        invokeListener(listener, "onADComplete", new Class<?>[0]);

        try {
            Class<?> resultType = listener.getClass().getClassLoader()
                    .loadClass(REWARD_RESULT_CLASS);
            Object rewardResult = resultType.getConstructor().newInstance();
            Method setPassthrough = resultType.getMethod("setPassthrough", String.class);
            setPassthrough.invoke(rewardResult, "");
            invokeListener(listener, "onReward", new Class<?>[]{resultType}, rewardResult);
        } catch (Throwable throwable) {
            Log.w(TAG, "AMS reward callback failed", throwable);
            XposedBridge.log(throwable);
        }

        getMainHandler().postDelayed(() -> {
            Log.i(TAG, "AMS session #" + sessionId + " close");
            invokeListener(listener, "onADClose", new Class<?>[0]);
        }, 100L);
    }

    private static void invokeListener(
            Object listener, String methodName, Class<?>[] parameterTypes, Object... args) {
        Log.i(TAG, "listener " + methodName + " " + identity(listener));
        try {
            Method method = listener.getClass().getMethod(methodName, parameterTypes);
            method.invoke(listener, args);
        } catch (Throwable throwable) {
            Log.w(TAG, "listener " + methodName + " failed", throwable);
            XposedBridge.log(throwable);
        }
    }

    private static Method findMethod(Class<?> type, String name, int parameterCount) {
        for (Method method : type.getDeclaredMethods()) {
            if (name.equals(method.getName())
                    && method.getParameterCount() == parameterCount) {
                return method;
            }
        }
        return null;
    }

    private static String readAdId(Object params) {
        if (params instanceof JSONObject) {
            return ((JSONObject) params).optString("rewardedVideoAdId", "");
        }
        if (params == null) {
            return null;
        }
        for (String name : new String[]{"optString", "getString", "get"}) {
            try {
                Method method = params.getClass().getMethod(name, String.class);
                Object value = method.invoke(params, "rewardedVideoAdId");
                return value == null ? null : value.toString();
            } catch (Throwable ignored) {
                // Try the next Map/JSON-compatible accessor.
            }
        }
        return null;
    }

    private static void callSuccess(Object callback) {
        if (callback == null) {
            return;
        }
        try {
            Method success = callback.getClass().getMethod("onSuccess", Object.class);
            success.invoke(callback, new Object[]{null});
            Log.i(TAG, "show callback completed");
        } catch (Throwable throwable) {
            Log.w(TAG, "show callback failed", throwable);
        }
    }

    private static void emitRewardClosed(Object module, String adId) {
        if (module == null) {
            return;
        }
        Map<String, Object> data = new HashMap<>();
        data.put("rewardedVideoAdId", adId);
        data.put("isEnded", Boolean.TRUE);
        try {
            Method dispatch = module.getClass().getMethod(
                    "dispatchEvent", String.class, Object.class);
            dispatch.invoke(module, "rewardedVideoAdClose", data);
            Log.i(TAG, "emitted rewardedVideoAdClose isEnded=true");
        } catch (Throwable throwable) {
            Log.w(TAG, "reward event dispatch failed", throwable);
        }
    }

    private static String summarizeAmsArgs(Object[] args) {
        StringBuilder value = new StringBuilder();
        value.append("ad=").append(field(args[1], "id"));
        value.append(" map=").append(mapSummary(args[0]));
        value.append(" listener=").append(identity(args[4]));
        if (args[5] != null) {
            value.append(" arg5=").append(shortString(args[5]));
        }
        return value.toString();
    }

    private static String summarizeAwardArgs(Object[] args) {
        StringBuilder value = new StringBuilder();
        value.append("thread=").append(Thread.currentThread().getName());
        if (args.length > 0) {
            value.append(" arg0=").append(shortString(args[0]));
        }
        if (args.length > 1) {
            value.append(" map=").append(mapSummary(args[1]));
        }
        if (args.length > 2) {
            value.append(" ad=").append(field(args[2], "id"));
        }
        if (args.length > 3) {
            value.append(" listener=").append(identity(args[3]));
        }
        if (args.length > 4) {
            value.append(" arg4=").append(shortString(args[4]));
        }
        if (args.length > 5) {
            value.append(" task=").append(shortString(args[5]));
        }
        return value.toString();
    }

    private static String mapSummary(Object value) {
        if (!(value instanceof Map)) {
            return shortString(value);
        }
        Map<?, ?> map = (Map<?, ?>) value;
        StringBuilder summary = new StringBuilder("{size=").append(map.size());
        for (String key : new String[]{"currentTaskCenterId", "rewardtaskid", "taskId",
                "businessId", "scene", "adId", "consecutiveconfig"}) {
            if (map.containsKey(key)) {
                summary.append(", ").append(key).append("=").append(shortString(map.get(key)));
            }
        }
        return summary.append("}").toString();
    }

    private static Object field(Object target, String name) {
        if (target == null) {
            return null;
        }
        Class<?> type = target.getClass();
        while (type != null) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(target);
            } catch (Throwable ignored) {
                type = type.getSuperclass();
            }
        }
        return null;
    }

    private static String identity(Object value) {
        return value == null ? "null" : value.getClass().getSimpleName()
                + "@" + Integer.toHexString(System.identityHashCode(value));
    }

    private static String shortString(Object value) {
        if (value == null) {
            return "null";
        }
        String text = String.valueOf(value);
        return text.length() <= 240 ? text : text.substring(0, 240) + "...";
    }

    private static synchronized Handler getMainHandler() {
        if (mainHandler == null) {
            mainHandler = new Handler(Looper.getMainLooper());
        }
        return mainHandler;
    }
}
