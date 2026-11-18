package io.github.saycm.huya.skipad;

import java.util.concurrent.atomic.AtomicBoolean;

final class WelfareSignRunner {
    interface Host {
        boolean active();
        boolean claim();
        void log(String state, String detail);
        void send(TaskWire.Struct request, NativeTaskRunner.Reply reply);
    }

    private final Host host;
    private final Runnable finished;
    private final AtomicBoolean done = new AtomicBoolean();

    WelfareSignRunner(Host host, Runnable finished) {
        this.host = host;
        this.finished = finished;
    }

    void start() {
        if (!host.active() || !host.claim()) {
            finish();
            return;
        }
        host.send(new TaskWire.Struct().field(1, 2).field(2, "frontpage"), (body, code) -> {
            if (!done.compareAndSet(false, true)) return;
            Object result = body.get(5);
            boolean success = host.active() && code == 0 && body.number(1) == 0
                    && result instanceof TaskWire.Struct
                    && ((TaskWire.Struct) result).number(1) == 2;
            host.log(success ? "成功" : "未确认",
                    success ? "服务端确认任务中心签到成功" : "签到未获服务端确认，不重复提交");
            finished.run();
        });
    }

    private void finish() {
        if (done.compareAndSet(false, true)) finished.run();
    }
}
