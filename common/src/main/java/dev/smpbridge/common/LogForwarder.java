package dev.smpbridge.common;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Streams the server console to the bot in small batches. Keeps the newest 400 lines while offline. */
public final class LogForwarder {
    private static final int MAX_BUFFER = 400;
    private static final int BATCH = 100;

    private final BridgeClient client;
    private final ConcurrentLinkedDeque<String> queue = new ConcurrentLinkedDeque<>();
    private final AtomicInteger size = new AtomicInteger();
    private final ScheduledExecutorService exec = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "SMPBridge-logs");
        t.setDaemon(true);
        return t;
    });
    private volatile Object handle;

    public LogForwarder(BridgeClient client) {
        this.client = client;
    }

    /** @return false when log4j is not reachable (console forwarding is then simply off). */
    public boolean install() {
        try {
            handle = Log4jHook.install(this::offer);
        } catch (Throwable t) {
            return false;
        }
        exec.scheduleWithFixedDelay(this::flush, 500, 500, TimeUnit.MILLISECONDS);
        return handle != null;
    }

    public void uninstall() {
        try {
            if (handle != null) Log4jHook.uninstall(handle);
        } catch (Throwable ignored) {
            // best effort
        }
        flush();
        exec.shutdownNow();
    }

    private void offer(String line) {
        queue.addLast(line);
        if (size.incrementAndGet() > MAX_BUFFER && queue.pollFirst() != null) size.decrementAndGet();
    }

    private void flush() {
        try {
            if (!client.isReady() || queue.isEmpty()) return;
            List<String> batch = new ArrayList<>();
            String line;
            while (batch.size() < BATCH && (line = queue.pollFirst()) != null) {
                size.decrementAndGet();
                batch.add(line.length() > 1500 ? line.substring(0, 1500) : line);
            }
            if (batch.isEmpty()) return;
            Map<String, Object> msg = new LinkedHashMap<>();
            msg.put("t", "console");
            msg.put("lines", batch);
            client.send(msg);
        } catch (Throwable ignored) {
            // a failing flush must never kill the scheduler thread
        }
    }
}
