package io.github.saycm.huya.skipad;

import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** Uses Huya's own transport. Session fields remain inside the target process. */
final class HuyaTaskClient {
    private final ClassLoader loader;
    private final AtomicInteger ids = new AtomicInteger(1);
    HuyaTaskClient(ClassLoader loader) { this.loader = loader; }

    long loggedInUid() throws ReflectiveOperationException {
        Class<?> component = loader.loadClass("com.duowan.kiwi.base.login.api.ILoginComponent");
        Object instance = loader.loadClass("ryxq.xxe").getMethod("i", Class.class).invoke(null, component);
        Object login = component.getMethod("getLoginModule").invoke(instance);
        Class<?> type = loader.loadClass("com.duowan.kiwi.base.login.api.ILoginModule");
        if (!Boolean.TRUE.equals(type.getMethod("isLogin").invoke(login))) return 0;
        return ((Number) type.getMethod("getUid").invoke(login)).longValue();
    }

    boolean notificationEnabled(Object context) throws ReflectiveOperationException {
        Class<?> contextType = loader.loadClass("android.content.Context");
        Class<?> type = loader.loadClass("com.duowan.kiwi.push.IPushModule");
        Object module = loader.loadClass("ryxq.xxe").getMethod("i", Class.class).invoke(null, type);
        return Boolean.TRUE.equals(type.getMethod("isNotificationEnabled", contextType)
                .invoke(module, context));
    }

    void claimDiceVideo(long account, String action, java.util.function.BooleanSupplier enabled,
                        NativeTaskRunner.ActionReply reply) {
        AtomicBoolean delivered = new AtomicBoolean();
        long deadline = System.nanoTime() + 60000000000L;
        java.util.function.BooleanSupplier active = () -> {
            try { return System.nanoTime() < deadline && enabled.getAsBoolean() && loggedInUid() == account; }
            catch (ReflectiveOperationException | RuntimeException failure) { return false; }
        };
        NativeTaskRunner.ActionReply once = (accepted, detail) -> {
            if (delivered.compareAndSet(false, true)) reply.complete(accepted, detail);
        };
        try {
            java.util.Map<String, String> args = new java.util.LinkedHashMap<>();
            String query = new java.net.URI(action).getRawQuery();
            if (query == null) throw new IllegalArgumentException("Missing ad action");
            for (String part : query.split("&")) {
                String[] pair = part.split("=", 2);
                if (pair.length == 2) args.put(java.net.URLDecoder.decode(pair[0], "UTF-8"),
                        java.net.URLDecoder.decode(pair[1], "UTF-8"));
            }
            if (!"lotteryad".equals(args.get("hyaction")) || !"1".equals(args.get("commonpanelreq"))
                    || !active.getAsBoolean()) {
                once.complete(false, "广告任务参数未适配或任务已关闭，未打开广告");
                return;
            }
            java.util.Map<String, String> params = new java.util.LinkedHashMap<>();
            params.put("iTargetAdType", "2");
            if (args.containsKey("rso")) params.put("rso", args.get("rso"));
            String reqId = args.get("rewardreqid");
            if (reqId == null || reqId.isEmpty()) reqId = java.util.UUID.randomUUID().toString();
            final String requestId = reqId;
            int requestType = Integer.parseInt(args.getOrDefault("rewardreqtype", "2"));
            TaskWire.Struct request = new TaskWire.Struct()
                    .field(1, Integer.parseInt(args.get("rewardappid")))
                    .field(2, new java.util.LinkedHashMap<>())
                    .field(3, Integer.parseInt(args.getOrDefault("commonpanelitemid", "0")))
                    .field(4, 0L)
                    .field(5, Integer.parseInt(args.getOrDefault("commonpanelsource", "0")))
                    .field(6, requestId).field(7, new java.util.LinkedHashMap<>()).field(8, params);
            send(account, "wupui", "getCommAdPannel", request, (body, code) -> {
                if (!active.getAsBoolean()) { once.complete(false, "任务已关闭或账号变化，未继续广告领奖"); return; }
                if (code != 0 || !(body.get(2) instanceof TaskWire.Struct)) {
                    once.complete(false, "未取得服务端广告任务，未提交领奖");
                    return;
                }
                TaskWire.Struct ad = (TaskWire.Struct) body.get(2);
                if (ad.text(0).isEmpty()) { once.complete(false, "服务端广告任务 ID 缺失"); return; }
                try {
                    Class<?> requestTypes = loader.loadClass(
                            "com.duowan.kiwi.ad.api.IHyLotteryAdHelper$HYRewardAdReqType");
                    Object types = requestTypes.getField("Companion").get(null);
                    int common = ((Number) types.getClass().getMethod("getAwardCommAdTaskType").invoke(types)).intValue();
                    int reward = ((Number) types.getClass().getMethod("getAwardCommRewardTaskType").invoke(types)).intValue();
                    if (requestType != common && requestType != reward) {
                        once.complete(false, "广告领奖类型未适配"); return;
                    }
                    Class<?> managerType = loader.loadClass("com.duowan.biz.videoad.impl.HyLotteryAdAwardManager");
                    Object companion = managerType.getField("Companion").get(null);
                    Object manager = companion.getClass().getMethod("getInstance").invoke(companion);
                    Class<?> listener = loader.loadClass(
                            "com.duowan.kiwi.ad.api.IHyLotteryAdHelper$HyRewardAdListener");
                    Object callback = Proxy.newProxyInstance(loader, new Class<?>[]{listener}, (proxy, method, values) -> {
                        if (method.getDeclaringClass() == Object.class) {
                            if ("hashCode".equals(method.getName())) return System.identityHashCode(proxy);
                            if ("equals".equals(method.getName())) return values != null && proxy == values[0];
                            return "FuckADDiceVideoCallback";
                        }
                        if ("onRewarded".equals(method.getName())) {
                            boolean success = active.getAsBoolean() && values != null
                                    && values.length > 0 && Boolean.TRUE.equals(values[0]);
                            once.complete(success, success ? "广告奖励回执已确认，等待骰子任务服务端复核"
                                    : "广告奖励未获确认，未重复请求");
                        } else if ("onError".equals(method.getName())) {
                            once.complete(false, "广告奖励接口返回错误，不重复请求");
                        }
                        return null;
                    });
                    Object ext = ad.get(5);
                    Class<?> gsonType = loader.loadClass("com.google.gson.Gson");
                    Object gson = gsonType.getConstructor().newInstance();
                    String json = (String) gsonType.getMethod("toJson", Object.class).invoke(gson,
                            ext instanceof java.util.Map ? ext : new java.util.LinkedHashMap<>());
                    if (!active.getAsBoolean()) { once.complete(false, "任务已关闭，未提交广告领奖"); return; }
                    managerType.getMethod(requestType == reward ? "onAwardCommonRewardAdTask" : "onAwardCommonAdTask",
                            String.class, java.util.Map.class, int.class, String.class, String.class, listener)
                            .invoke(manager, ad.text(0), new java.util.LinkedHashMap<>(), 0, json, requestId, callback);
                } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
                    once.complete(false, "原生广告领奖接口不匹配，未确认成功");
                }
            });
        } catch (java.net.URISyntaxException | java.io.UnsupportedEncodingException
                 | RuntimeException | LinkageError failure) {
            once.complete(false, "广告任务参数或接口未匹配，未打开广告");
        }
    }

    void subscribeConfiguredPresenter(long account, String configured, Object context,
                                     NativeTaskRunner.ActionReply reply) {
        long presenter;
        try {
            if (configured == null || !configured.matches("[0-9]{1,19}")) {
                throw new IllegalArgumentException();
            }
            presenter = Long.parseLong(configured);
            if (presenter <= 0) throw new IllegalArgumentException();
        } catch (IllegalArgumentException failure) {
            reply.complete(false, "未配置有效的指定主播 UID，未关注任何主播");
            return;
        }
        subscribePresenter(account, presenter, context, reply);
    }

    void subscribePresenter(long account, long presenter, Object context,
                            NativeTaskRunner.ActionReply reply) {
        AtomicBoolean delivered = new AtomicBoolean();
        try {
            if (account <= 0 || presenter <= 0) throw new IllegalStateException("Invalid account");
            verifySession(account);
            Class<?> contextType = loader.loadClass("android.content.Context");
            Object activity = loader.loadClass("ryxq.v40")
                    .getMethod("getActivity", contextType).invoke(null, context);
            if (activity == null) {
                throw new IllegalStateException("当前没有可用的虎牙页面");
            }
            Class<?> componentType = loader.loadClass(
                    "com.duowan.kiwi.basesubscribe.api.ISubscribeComponent");
            Object component = loader.loadClass("ryxq.xxe")
                    .getMethod("i", Class.class).invoke(null, componentType);
            Object actions = componentType.getMethod("getSubscribeActionModule").invoke(component);
            Class<?> callbackType = loader.loadClass(
                    "com.duowan.kiwi.basesubscribe.api.callback.SubscribeCallback$ISubscribeCallBack");
            Object callback = Proxy.newProxyInstance(loader, new Class<?>[]{callbackType},
                    (proxy, method, args) -> {
                        if (method.getDeclaringClass() == Object.class) {
                            if ("hashCode".equals(method.getName())) return System.identityHashCode(proxy);
                            if ("equals".equals(method.getName())) return args != null && proxy == args[0];
                            return "FuckADSubscribeCallback";
                        }
                        if ("onResponse".equals(method.getName())
                                && delivered.compareAndSet(false, true)) {
                            boolean success = args != null && args.length >= 3
                                    && Boolean.TRUE.equals(args[0]);
                            long presenterId = args != null && args.length >= 3
                                    && args[2] instanceof Number
                                    ? ((Number) args[2]).longValue() : 0;
                            if (success && presenterId == presenter) {
                                reply.complete(true, "服务端已确认关注主播");
                            } else {
                                String message = args != null && args.length >= 4
                                        && args[3] != null
                                        ? String.valueOf(field(args[3], "mErrMsg")) : "";
                                reply.complete(false, message.isEmpty()
                                        ? "虎牙未确认关注成功" : "关注失败：" + message);
                            }
                        }
                        return null;
                    });
            Class<?> actionType = loader.loadClass(
                    "com.duowan.kiwi.basesubscribe.api.ISubscribeActionModule");
            Class<?> activityType = loader.loadClass("android.app.Activity");
            actionType.getMethod("subscribeUser", activityType, long.class, boolean.class,
                            boolean.class, callbackType)
                    .invoke(actions, activity, presenter, true, true, callback);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            if (delivered.compareAndSet(false, true)) {
                String message = failure.getMessage();
                reply.complete(false, message == null || message.isEmpty()
                        ? "静默关注接口不可用" : "静默关注失败：" + message);
            }
        }
    }

    void send(long account, String servant, String function, TaskWire.Struct body, NativeTaskRunner.Reply reply) {
        AtomicBoolean delivered = new AtomicBoolean();
        try {
            boolean query = "wupui".equals(servant) && "getLiveBoxUserGrowthPage".equals(function);
            boolean reward = "wupui".equals(servant) && "receiveRewardTaskPrize".equals(function);
            boolean userReward = "commui".equals(servant) && "receiveUserLevelTaskPrize".equals(function);
            boolean progress = "commui".equals(servant)
                    && "updateUserLevelTaskProgress".equals(function);
            boolean coop = "wupui".equals(servant)
                    && "reportCoopUserLevelTask".equals(function);
            boolean market = "wupui".equals(servant)
                    && "reportUserTaskClickEvent".equals(function);
            boolean dice = "wupui".equals(servant)
                    && ("getJumpGameModule".equals(function)
                    || "getCommAdPannel".equals(function)
                    || "getJumpGameTaskList".equals(function)
                    || "claimJumpGameTaskReward".equals(function)
                    || "rollJumpGameDice".equals(function));
            if (!query && !reward && !userReward && !progress && !coop && !market && !dice) {
                throw new IllegalArgumentException("Unsupported task endpoint");
            }
            verifySession(account);
            Object user = loader.loadClass("com.duowan.biz.wup.WupHelper")
                    .getMethod("getUserId").invoke(null);
            Class<?> outputType = loader.loadClass("com.duowan.taf.jce.JceOutputStream");
            Object output = outputType.getConstructor().newInstance();
            outputType.getMethod("setServerEncoding", String.class).invoke(output, "UTF-8");
            user.getClass().getMethod("writeTo", outputType).invoke(user, output);
            byte[] identity = (byte[]) outputType.getMethod("toByteArray").invoke(output);
            TaskWire.Struct request = new TaskWire.Struct();
            request.putAll(body); request.put(0, TaskWire.decode(identity));
            Class<?> packetType = loader.loadClass("com.duowan.jce.wup.UniPacket");
            Object packet = packetType.getConstructor().newInstance();
            packetType.getMethod("decode", byte[].class).invoke(packet,
                    (Object) TaskWire.packet(servant, function, request, ids.getAndIncrement()));
            Class<?> callbackType = loader.loadClass("com.huya.kiwi.crossplatform.common.wup.HybridJceCallback");
            Object callback = Proxy.newProxyInstance(loader, new Class<?>[]{callbackType}, (proxy, method, args) -> {
                if (method.getDeclaringClass() == Object.class) {
                    if ("hashCode".equals(method.getName())) return System.identityHashCode(proxy);
                    if ("equals".equals(method.getName())) return args != null && proxy == args[0];
                    return "FuckADTaskCallback";
                }
                if ("onResponse".equals(method.getName()) && delivered.compareAndSet(false, true)) {
                    TaskWire.Reply result;
                    try { result = TaskWire.reply((byte[]) args[0], "tRsp"); }
                    catch (RuntimeException failure) { result = new TaskWire.Reply(new TaskWire.Struct(), -1); }
                    reply.complete(result.body, result.code);
                } else if ("a".equals(method.getName()) && delivered.compareAndSet(false, true)) {
                    reply.complete(new TaskWire.Struct(), -1);
                }
                return null;
            });
            Object nativeRequest = loader.loadClass("ryxq.ev6")
                    .getMethod("a", packetType, int.class, int.class, Number.class, callbackType)
                    .invoke(null, packet, 0, 15000, null, callback);
            nativeRequest.getClass().getMethod("execute").invoke(nativeRequest);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            if (delivered.compareAndSet(false, true)) reply.complete(new TaskWire.Struct(), -1);
        }
    }

    private void verifySession(long account) throws ReflectiveOperationException {
        if (account <= 0 || loggedInUid() != account) throw new IllegalStateException("Session changed");
        Object user = loader.loadClass("com.duowan.biz.wup.WupHelper")
                .getMethod("getUserId").invoke(null);
        if (user == null || user.getClass().getField("lUid").getLong(user) != account) {
            throw new IllegalStateException("Session changed");
        }
    }

    private static Object field(Object target, String name) {
        Class<?> type = target.getClass();
        while (type != null) {
            try {
                java.lang.reflect.Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(target);
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                type = type.getSuperclass();
            }
        }
        return null;
    }
}
