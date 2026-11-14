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

import io.github.libxposed.api.XposedModule;

public final class HuyaCompatEntry extends XposedModule {
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
    private boolean activityLoaderHookInstalled;

    @Override
    public void onModuleLoaded(ModuleLoadedParam param) {
        logInfo("loaded process=" + param.getProcessName() + " api=" + getApiVersion());
    }

    @Override
    public void onPackageReady(PackageReadyParam param) {
        if (param == null
                || !TARGET_PACKAGE.equals(param.getPackageName())
                || !param.isFirstPackage()) {
            return;
        }

        logInfo("package ready " + param.getPackageName());
        installActivityLoaderHook(param.getClassLoader());
    }

    @Override
    public boolean onHotReloading(HotReloadingParam param) {
        logInfo("hot reload rejected");
        return false;
    }

    private synchronized void installActivityLoaderHook(ClassLoader classLoader) {
        if (activityLoaderHookInstalled) {
            return;
        }
        try {
            Class<?> type = classLoader.loadClass("android.app.Activity");
            Method onCreate = type.getDeclaredMethod("onCreate", android.os.Bundle.class);
            hook(onCreate).setPriority(PRIORITY_HIGHEST).intercept(chain -> {
                Object activity = chain.getThisObject();
                ClassLoader activityLoader = activity == null
                        ? null : activity.getClass().getClassLoader();
                initializeHooks(activityLoader);
                return chain.proceed();
            });
            activityLoaderHookInstalled = true;
            logInfo("hooked activity ClassLoader initialization");
        } catch (Throwable throwable) {
            logFailure(throwable);
        }
    }

    private synchronized void initializeHooks(ClassLoader classLoader) {
        if (hooksInitialized || classLoader == null) {
            return;
        }
        hooksInitialized = true;
        logInfo("initializing with activity loader " + classLoader);
        hookMiniappReward(classLoader);
        hookAmsReward(classLoader);
        hookAwardRequests(classLoader);
        hookAdRequestDiagnostics(classLoader);
        hookDisplayAds(classLoader);
        hookImmerseFeedAds(classLoader);
        hookListAds(classLoader);
        hookSplash(classLoader);
    }

    private void hookImmerseFeedAds(ClassLoader classLoader) {
        try {
            Class<?> type = classLoader.loadClass(
                    "com.duowan.kiwi.immersepage.impl.data.ImmerseDataManagerImpl");
            Method filterList = findMethod(type, "filterList", 3);
            if (filterList == null) {
                return;
            }
            hook(filterList).setPriority(PRIORITY_HIGHEST).intercept(chain -> {
                Object result = chain.proceed();
                if (!(result instanceof List)) {
                    return result;
                }
                List<?> original = (List<?>) result;
                List<Object> filtered = new ArrayList<>(original.size());
                for (Object item : original) {
                    if (!isImmerseAdItem(item)) {
                        filtered.add(item);
                    }
                }
                int removed = original.size() - filtered.size();
                if (removed > 0) {
                    logInfo("filtered " + removed + " immersive feed ads");
                    return filtered;
                }
                return result;
            });
            logInfo("hooked immersive recommendation feed ads");
        } catch (Throwable throwable) {
            logFailure(throwable);
        }
        try {
            Class<?> type = classLoader.loadClass(
                    "com.duowan.kiwi.list.homepage.tab.recommend.BaseRecommendPresenter");
            Method gridItems = findMethod(type, "getGridGameCardItemList", 3);
            if (gridItems != null) {
                hook(gridItems).setPriority(PRIORITY_HIGHEST).intercept(chain -> {
                    Object result = chain.proceed();
                    if (!(result instanceof List)) {
                        return result;
                    }
                    List<?> original = (List<?>) result;
                    List<Object> filtered = new ArrayList<>(original.size());
                    for (Object item : original) {
                        if (!isAdLineItem(item)) {
                            filtered.add(item);
                        }
                    }
                    int removed = original.size() - filtered.size();
                    if (removed > 0) {
                        logInfo("filtered " + removed + " recommendation grid ads");
                        return filtered;
                    }
                    return result;
                });
                logInfo("hooked recommendation grid ads");
            }
        } catch (Throwable throwable) {
            logFailure(throwable);
        }
    }

    private static boolean isImmerseAdItem(Object item) {
        if (item == null) {
            return false;
        }
        try {
            Object itemType = item.getClass().getMethod("getItemType").invoke(item);
            String name = String.valueOf(itemType);
            return "AD_VIDEO".equals(name)
                    || "HOME_NATIVE_AD".equals(name)
                    || "HOT_LIVE_AD_VIDEO".equals(name)
                    || "LIVE_LIST_AD_VIDEO".equals(name);
        } catch (Throwable throwable) {
            return false;
        }
    }

    private void hookDisplayAds(ClassLoader classLoader) {
        try {
            Class<?> type = classLoader.loadClass(
                    "com.duowan.kiwi.adsplash.controller.AdRouter");
            Method route = findMethod(type, "isNeedShowAdSplash", 4);
            if (route != null) {
                hook(route).setPriority(PRIORITY_HIGHEST).intercept(chain -> {
                    // 6 is Huya's own "splash disabled" result. The caller still
                    // completes its normal NO_AD callbacks and opens the homepage.
                    logInfo("blocked splash before display");
                    return 6;
                });
                logInfo("hooked splash display decision");
            }
        } catch (Throwable throwable) {
            logFailure(throwable);
        }
        try {
            Class<?> type = classLoader.loadClass("com.duowan.kiwi.ad.impl.HyAdModule");
            Method shield = findMethod(type, "isNeedShieldAdOutLivingRoom", 0);
            if (shield != null) {
                hook(shield).setPriority(PRIORITY_HIGHEST).intercept(chain -> true);
                // The in-room shield also guards rewarded-ad requests, so leave it alone.
                logInfo("enabled ordinary ad request shield");
            }
        } catch (Throwable throwable) {
            logFailure(throwable);
        }
        try {
            Class<?> type = classLoader.loadClass(
                    "com.duowan.kiwi.list.patface.chain.PatFaceInterceptorChain");
            Method proceed = type.getDeclaredMethod("proceed", int.class);
            hook(proceed).setPriority(PRIORITY_HIGHEST).intercept(chain -> {
                // Stop before constructing or enqueuing the promotional dialog.
                logInfo("blocked automatic promotion location=" + chain.getArg(0));
                return false;
            });
            logInfo("hooked automatic promotion decision");
        } catch (Throwable throwable) {
            logFailure(throwable);
        }
        try {
            Class<?> type = classLoader.loadClass(
                    "com.duowan.kiwi.list.homepage.tab.recommend.BaseRecommendPresenter");
            Method callback = findMethod(type, "onLiveListAdBack", 4);
            if (callback != null) {
                hook(callback).setPriority(PRIORITY_HIGHEST).intercept(chain -> {
                    logInfo("blocked recommendation ad injection ad="
                            + field(chain.getArg(0), "iId"));
                    return null;
                });
                logInfo("hooked recommendation ad injection");
            }
            Method insertGrid = findMethod(type, "l0", 3);
            if (insertGrid != null) {
                hook(insertGrid).setPriority(PRIORITY_HIGHEST).intercept(chain -> {
                    Object result = chain.proceed();
                    Object output = chain.getArg(0);
                    if (!(output instanceof List)) {
                        return result;
                    }
                    List<?> items = (List<?>) output;
                    int removed = 0;
                    for (int index = items.size() - 1; index >= 0; index--) {
                        if (isAdLineItem(items.get(index))) {
                            items.remove(index);
                            removed++;
                        }
                    }
                    if (removed > 0) {
                        logInfo("filtered " + removed
                                + " ads after recommendation grid insertion");
                    }
                    return result;
                });
                logInfo("hooked recommendation grid insertion");
            }
        } catch (Throwable throwable) {
            logFailure(throwable);
        }
    }

    private void hookListAds(ClassLoader classLoader) {
        List<String> mutations = Arrays.asList(
                "replace", "replaceAndNotify", "replaceData", "add", "addAndNotify",
                "insert", "insertAndNotify", "insertList", "insertListAndNotify",
                "insertItemAndNotify", "insertItemDataOnly",
                "insertItemListAndNotify", "insertItemListDataOnly");
        for (String name : new String[]{
                "com.duowan.kiwi.listframe.adapter.ListLineRecyclerViewAdapter",
                "com.duowan.kiwi.listframe.adapter.HeaderFooterListLineAdapter",
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
                    hook(method).setPriority(PRIORITY_HIGHEST).intercept(chain -> {
                        Object input = chain.getArg(0);
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
                                Object[] args = chain.getArgs().toArray();
                                args[0] = filtered;
                                logInfo("filtered " + removed
                                        + " list ads via " + chain.getExecutable().getName());
                                return chain.proceed(args);
                            }
                        } else if (isAdLineItem(input)) {
                            // Skip the notification as well as the insertion.
                            logInfo("blocked single list ad");
                            return null;
                        }
                        return chain.proceed();
                    });
                    count++;
                }
                logInfo("hooked " + name + " mutations=" + count);
            } catch (Throwable throwable) {
                logFailure(throwable);
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

    private void hookAdRequestDiagnostics(ClassLoader classLoader) {
        try {
            Class<?> type = classLoader.loadClass("com.duowan.biz.videoad.impl.HyVideoAdModule");
            for (Method method : type.getDeclaredMethods()) {
                if ("goVideoAdWithCallback".equals(method.getName())
                        || "lambda$onGetAdFail$2".equals(method.getName())) {
                    hook(method).setPriority(PRIORITY_HIGHEST).intercept(chain -> {
                        logInfo("request entry " + chain.getExecutable().getName());
                        return chain.proceed();
                    });
                }
            }
            Method failed = findMethod(type, "onGetAdFail", 8);
            if (failed != null) {
                hook(failed).setPriority(PRIORITY_HIGHEST).intercept(chain -> {
                    logInfo("ad request failed: code=" + chain.getArg(5)
                            + " reason=" + shortString(chain.getArg(6))
                            + " scene=" + shortString(chain.getArg(2))
                            + " " + mapSummary(chain.getArg(7)));
                    return chain.proceed();
                });
            }
            Method parsed = findMethod(type, "parseAdResult", 12);
            if (parsed != null) {
                hook(parsed).setPriority(PRIORITY_HIGHEST).intercept(chain -> {
                    logInfo("parseAdResult: ad="
                            + field(chain.getArg(3), "id")
                            + " taskResult=" + field(chain.getArg(4), "iRet")
                            + " " + mapSummary(chain.getArg(11)));
                    return chain.proceed();
                });
            }
            logInfo("ad request diagnostics installed");
        } catch (Throwable throwable) {
            logFailure(throwable);
        }
    }

    private void hookMiniappReward(ClassLoader classLoader) {
        try {
            Class<?> type = classLoader.loadClass(TARGET_CLASS);
            Method show = findMethod(type, "showRewardedVideoAd", 2);
            if (show == null) {
                Log.w(TAG, "showRewardedVideoAd not found");
                return;
            }

            hook(show).setPriority(PRIORITY_HIGHEST).intercept(chain -> {
                String adId = readAdId(chain.getArg(0));
                if (adId == null || adId.isEmpty()) {
                    return chain.proceed();
                }

                Object callback = chain.getArg(1);
                Object module = chain.getThisObject();
                callSuccess(callback);
                getMainHandler().postDelayed(
                        () -> emitRewardClosed(module, adId), 100L);
                Log.i(TAG, "skipped rewarded video " + adId);
                return null;
            });
            Log.i(TAG, "hooked " + TARGET_CLASS + ".showRewardedVideoAd/2");
        } catch (Throwable throwable) {
            Log.w(TAG, "init failed", throwable);
            logFailure(throwable);
        }
    }

    private void hookAmsReward(ClassLoader classLoader) {
        try {
            Class<?> type = classLoader.loadClass(AMS_HELPER_CLASS);
            Method request = findMethod(type, "onCallAmsRewardAd", 6);
            if (request == null) {
                Log.w(TAG, "onCallAmsRewardAd not found");
                return;
            }

            hook(request).setPriority(PRIORITY_HIGHEST).intercept(chain -> {
                Object[] args = chain.getArgs().toArray();
                if (args.length < 6 || args[4] == null) {
                    return chain.proceed();
                }

                Object listener = args[4];
                int requestId = AMS_REQUESTS.incrementAndGet();
                logInfo("intercepted AMS reward request #" + requestId);
                Log.i(TAG, "AMS request #" + requestId + " " + summarizeAmsArgs(args));
                finishAmsReward(listener, requestId);
                Log.i(TAG, "skipped AMS rewarded video on user click #" + requestId);
                return null;
            });
            logInfo("hooked " + AMS_HELPER_CLASS + ".onCallAmsRewardAd/6");
        } catch (Throwable throwable) {
            Log.w(TAG, "AMS init failed", throwable);
            logFailure(throwable);
        }
    }

    private void hookAwardRequests(ClassLoader classLoader) {
        try {
            Class<?> type = classLoader.loadClass(AWARD_MANAGER_CLASS);
            Method award = findMethod(type, "awardAdTaskIfNeed", 6);
            if (award != null) {
                hook(award).setPriority(PRIORITY_HIGHEST).intercept(chain -> {
                    Log.i(TAG, "awardAdTaskIfNeed #" + AWARD_CALLS.incrementAndGet()
                            + " " + summarizeAwardArgs(chain.getArgs().toArray()));
                    return chain.proceed();
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
            logFailure(throwable);
        }
    }

    private void hookAwardMethod(Class<?> type, String name, int parameterCount) {
        Method method = findMethod(type, name, parameterCount);
        if (method == null) {
            Log.w(TAG, "award method not found " + name + "/" + parameterCount);
            return;
        }
        hook(method).setPriority(PRIORITY_HIGHEST).intercept(chain -> {
            Log.i(TAG, name + " #" + AWARD_CALLS.incrementAndGet()
                    + " " + summarizeAwardArgs(chain.getArgs().toArray()));
            return chain.proceed();
        });
    }

    private void hookSplash(ClassLoader classLoader) {
        try {
            Class<?> type = classLoader.loadClass(AD_SPLASH_FRAGMENT_CLASS);
            Method visible = findMethod(type, "onVisibleToUser", 0);
            if (visible == null) {
                Log.w(TAG, "AdSplashFragment.onVisibleToUser not found");
                return;
            }

            hook(visible).setPriority(PRIORITY_HIGHEST).intercept(chain -> {
                Object result = chain.proceed();
                Object fragment = chain.getThisObject();
                if (fragment == null) {
                    return result;
                }
                try {
                    Method dismiss = fragment.getClass().getMethod(
                            "dismissWhenError", String.class);
                    dismiss.invoke(fragment, "module_skip_splash");
                    Log.i(TAG, "skipped splash ad");
                } catch (Throwable throwable) {
                    Log.w(TAG, "splash dismiss failed", throwable);
                }
                return result;
            });
            Log.i(TAG, "hooked " + AD_SPLASH_FRAGMENT_CLASS + ".onVisibleToUser/0");
        } catch (Throwable throwable) {
            Log.w(TAG, "splash init failed", throwable);
            logFailure(throwable);
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

    private void logInfo(String message) {
        log(Log.INFO, TAG, message);
    }

    private void logFailure(Throwable throwable) {
        log(Log.ERROR, TAG, "hook failure", throwable);
    }

    private static synchronized Handler getMainHandler() {
        if (mainHandler == null) {
            mainHandler = new Handler(Looper.getMainLooper());
        }
        return mainHandler;
    }
}
