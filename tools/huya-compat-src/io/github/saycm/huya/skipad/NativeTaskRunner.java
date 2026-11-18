package io.github.saycm.huya.skipad;

import java.util.ArrayDeque;
import java.util.List;

/** Serial, receipt-driven execution. The host supplies time, session and native transport. */
final class NativeTaskRunner {
    interface Reply { void complete(TaskWire.Struct body, int code); }
    interface ActionReply { void complete(boolean accepted, String detail); }
    interface Host {
        boolean enabled();
        long uid();
        boolean claim(String key);
        boolean taskEnabled(NativeTaskCatalog.Task task);
        void publish(List<NativeTaskCatalog.Task> tasks);
        void log(String title, String state, String detail);
        void later(long millis, Runnable run);
        void perform(NativeTaskCatalog.Task task, ActionReply reply);
        void send(String servant, String function, TaskWire.Struct body, Reply reply);
    }
    private final Host host;
    private final long interval;
    private final ArrayDeque<NativeTaskCatalog.Task> tasks = new ArrayDeque<>();
    private long account;
    private String day;
    private boolean stopped, waiting, actionWaiting, started;
    private int requestId, actionId;

    NativeTaskRunner(Host host, long interval) {
        if (interval < 5000 || interval > 600000) throw new IllegalArgumentException("Invalid task interval");
        this.host = host; this.interval = interval;
    }

    void start(long uid, String date) {
        if (started) return;
        started = true; account = uid; day = date;
        if (!active()) return;
        host.log("任务中心", "开始", "读取虎牙任务列表，仅执行已开启且具备静默能力的任务");
        send("任务列表", "wupui", "getLiveBoxUserGrowthPage",
                query(), (body, code) -> {
            if (code != 0) { stop("任务查询被拒绝，返回代码 " + code); return; }
            try {
                List<NativeTaskCatalog.Task> catalog = NativeTaskCatalog.read(body);
                host.publish(catalog);
                host.log("任务列表", "已读取", "读取到 " + catalog.size() + " 条任务或任务组记录");
                int available = 0;
                for (NativeTaskCatalog.Task task : catalog) {
                    String reason = task.skipReason();
                    if (!reason.isEmpty()) {
                        host.log(task.title, task.received != 0 ? "已领取" : "跳过", reason);
                    } else if (!host.taskEnabled(task)) {
                        host.log(task.title, "已关闭", "任务开关已关闭，不执行动作或领奖");
                    } else {
                        available++;
                        tasks.add(task);
                    }
                }
                host.log("任务中心", "筛选", "本轮执行 " + available + " 条已开启任务");
                next();
            } catch (RuntimeException failure) {
                stop("任务结构未匹配，未提交任何完成事件或领奖请求");
            }
        });
    }

    private boolean active() {
        if (stopped) return false;
        if (!host.enabled() || account <= 0 || host.uid() != account) {
            stop("开关已关闭或账号发生变化，停止本轮"); return false;
        }
        return true;
    }

    private void next() {
        if (!active()) return;
        NativeTaskCatalog.Task task = tasks.poll();
        if (task == null) { stopped = true; host.log("任务中心", "结束", "本轮已结束，请按任务记录核对结果"); return; }
        host.log(task.title, "等待", "等待 " + interval / 1000 + " 秒后检查账号并领取已完成奖励");
        host.later(interval, () -> {
            if (!active()) { host.log(task.title, "未执行", "本轮已停止"); return; }
            if (task.automationAction() == NativeTaskCatalog.AutomationAction.NONE
                    || task.progress >= task.target) {
                claimReward(task);
            } else {
                performAction(task);
            }
        });
    }

    private void performAction(NativeTaskCatalog.Task task) {
        if (!host.taskEnabled(task)) {
            host.log(task.title, "已关闭", "任务开关已关闭，不执行动作或领奖");
            next();
            return;
        }
        String key = "task-action/" + account + "/" + day + "/" + task.key();
        if (!host.claim(key)) {
            host.log(task.title, "跳过", "今日已执行过，服务端未确认时不重复提交");
            next();
            return;
        }
        host.log(task.title, "执行", task.actionDescription());
        int id = ++requestId;
        actionId = id;
        actionWaiting = true;
        host.later(20000, () -> {
            if (!actionWaiting || actionId != id) return;
            actionWaiting = false;
            host.log(task.title, "结果未确认", "原生动作超时，停止本轮且不自动重试");
            stop("原生动作结果未确认");
        });
        try {
            host.perform(task, (accepted, detail) -> {
                if (!actionWaiting || actionId != id) return;
                actionWaiting = false;
                if (!active()) {
                    host.log(task.title, "结果未确认", "动作回执到达时开关关闭或账号改变");
                    return;
                }
                if (!accepted) {
                    host.log(task.title, "未执行", detail);
                    next();
                    return;
                }
                host.log(task.title, "已提交",
                        detail + "；等待 " + interval / 1000 + " 秒后复核服务端进度");
                host.later(interval, () -> refreshAfterAction(task, 0));
            });
        } catch (RuntimeException failure) {
            actionWaiting = false;
            stop("原生动作接口不可用，未确认成功");
        }
    }

    private void refreshAfterAction(NativeTaskCatalog.Task expected, int attempt) {
        if (!active()) return;
        host.log(expected.title, "复核", "重新查询任务中心，确认服务端进度");
        send(expected.title, "wupui", "getLiveBoxUserGrowthPage", query(), (body, code) -> {
            if (code != 0) {
                host.log(expected.title, "结果未确认", "任务复核请求失败，未领奖");
                next();
                return;
            }
            NativeTaskCatalog.Task current = null;
            try {
                for (NativeTaskCatalog.Task task : NativeTaskCatalog.read(body)) {
                    if (expected.key().equals(task.key())) { current = task; break; }
                }
            } catch (RuntimeException failure) {
                host.log(expected.title, "结果未确认", "任务复核结构不匹配，未领奖");
                next();
                return;
            }
            if (current == null) {
                host.log(expected.title, "服务端未确认", "动作已提交，但任务列表中尚无该任务");
                next();
                return;
            }
            if (current.received != 0) {
                host.log(expected.title, "已领取", "服务端已标记奖励领取，不重复提交");
                next();
                return;
            }
            if (!host.taskEnabled(current)) {
                host.log(current.title, "已关闭", "任务开关已关闭，不领取奖励");
                next();
                return;
            }
            if (current.target <= 0 || current.progress < current.target) {
                int limit = expected.automationAction()
                        == NativeTaskCatalog.AutomationAction.FOLLOW
                        ? Math.max(12, (int) Math.ceil(360000d / interval)) : 1;
                if (attempt + 1 < limit) {
                    host.log(expected.title, "复核",
                            "当前进度 " + current.progress + "/" + current.target
                                    + "，等待 " + interval / 1000 + " 秒后再次复核");
                    host.later(interval, () -> refreshAfterAction(expected, attempt + 1));
                    return;
                }
                host.log(expected.title, "服务端未确认",
                        "当前进度 " + current.progress + "/" + current.target + "，未领取奖励");
                next();
                return;
            }
            claimReward(current);
        });
    }

    private void claimReward(NativeTaskCatalog.Task task) {
        if (!host.taskEnabled(task)) {
            host.log(task.title, "已关闭", "任务开关已关闭，不领取奖励");
            next();
            return;
        }
        String key = "task-reward/" + account + "/" + day + "/" + task.key();
        if (!host.claim(key)) {
            host.log(task.title, "跳过", "今日已提交过，结果不明时不重复领取");
            next();
            return;
        }
        send(task.title, task.servant(), task.function(), task.request(), (body, code) -> {
            if (code != 0 || body.number(3) != 0) {
                host.log(task.title, "失败", "返回异常或安全校验，未确认领奖成功");
                stop("领奖返回异常或安全校验，本轮停止，未自动重试"); return;
            }
            if (body.number(0) != 1) {
                host.log(task.title, "失败", "服务端未确认领奖成功");
                stop("服务端未确认领奖成功，本轮停止，未自动重试"); return;
            }
            host.log(task.title, "成功", "服务端确认领奖成功");
            next();
        });
    }

    private static TaskWire.Struct query() {
        return new TaskWire.Struct().field(1, 11).field(2, 0).field(3, "")
                .field(4, new java.util.LinkedHashMap<>());
    }

    private void send(String title, String servant, String function, TaskWire.Struct body, Reply reply) {
        if (!active()) return;
        int id = ++requestId;
        waiting = true;
        host.log(title, "请求已发出", "等待服务端回执，尚未确认成功");
        host.later(20000, () -> {
            if (!waiting || id != requestId) return;
            waiting = false;
            host.log(title, "结果未确认", "请求超时，本轮停止且不自动重试");
            stop("请求结果未确认");
        });
        try {
            host.send(servant, function, body, (result, code) -> {
                if (!waiting || id != requestId) return;
                waiting = false;
                if (!active()) {
                    host.log(title, "结果未确认", "回执到达时开关关闭或账号改变，未将原请求记为成功");
                    return;
                }
                try { reply.complete(result, code); }
                catch (RuntimeException failure) { stop("回执结构不匹配，未确认成功"); }
            });
        } catch (RuntimeException failure) {
            waiting = false; stop("请求接口不可用，未确认成功");
        }
    }

    private void stop(String reason) {
        if (stopped) return;
        stopped = true; waiting = false; actionWaiting = false;
        host.log("任务中心", "停止", reason);
        while (!tasks.isEmpty()) host.log(tasks.poll().title, "未执行", reason);
    }
}
