package dev.smpbridge.common;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * WebSocket link to the Discord bot. Dials OUT to the bot (ws:// or wss://), authenticates with a token,
 * and reconnects forever with back-off. Uses only the JDK (java.net.http), no extra libraries.
 */
public final class BridgeClient implements WebSocket.Listener {

    public interface Handler {
        void onMessage(Map<String, Object> msg);
        void onConnected();
        void onDisconnected();
    }

    public interface Log {
        void info(String message);
        void warn(String message);
    }

    private final String url;
    private final String token;
    private final String id;
    private final String kind;
    private final String version;
    private final List<String> features;
    private final Handler handler;
    private final Log log;

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final ScheduledExecutorService exec = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "SMPBridge-client");
        t.setDaemon(true);
        return t;
    });
    private final Object lock = new Object();
    private final AtomicBoolean reconnectQueued = new AtomicBoolean();
    private final AtomicInteger pending = new AtomicInteger();
    private final StringBuilder frame = new StringBuilder();

    private volatile WebSocket ws;
    private volatile boolean ready;
    private volatile boolean stopped;
    private volatile boolean warnedDown;
    private int retry;
    private CompletableFuture<?> tail = CompletableFuture.completedFuture(null);

    public BridgeClient(String url, String token, String id, String kind, String version,
                        List<String> features, Handler handler, Log log) {
        this.url = url;
        this.token = token;
        this.id = id;
        this.kind = kind;
        this.version = version;
        this.features = features;
        this.handler = handler;
        this.log = log;
    }

    public boolean isReady() { return ready; }

    public void start() {
        try {
            exec.execute(this::connect);
        } catch (RejectedExecutionException ignored) {
            // already stopped
        }
    }

    /**
     * Sends one binary frame and waits until it was handed to the network (backpressure for big uploads).
     * Ordered with send(): control messages and binary frames reach the bot in the order they were queued.
     */
    public boolean sendBinaryBlocking(byte[] data, int off, int len, long timeoutMs) {
        WebSocket w = ws;
        if (w == null || !ready) return false;
        CompletableFuture<?> f;
        synchronized (lock) {
            tail = tail.handle((r, e) -> null).thenCompose(x -> w.sendBinary(ByteBuffer.wrap(data, off, len), true));
            f = tail;
        }
        try {
            f.get(timeoutMs, TimeUnit.MILLISECONDS);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** Sends a message if connected. Never blocks. */
    public boolean send(Map<String, Object> message) {
        if (!ready) return false;
        post(Json.write(message));
        return true;
    }

    public void stop() {
        stopped = true;
        WebSocket w = ws;
        if (w != null && ready) {
            Map<String, Object> bye = new LinkedHashMap<>();
            bye.put("t", "goodbye");
            post(Json.write(bye));
            try {
                CompletableFuture<?> t;
                synchronized (lock) { t = tail; }
                t.get(1500, TimeUnit.MILLISECONDS);
            } catch (Exception ignored) {
                // best effort
            }
            try {
                w.sendClose(WebSocket.NORMAL_CLOSURE, "bye");
            } catch (Exception ignored) {
                // best effort
            }
        }
        ready = false;
        exec.shutdownNow();
    }

    /* ----------------------------- connecting ----------------------------- */

    private void connect() {
        if (stopped) return;
        reconnectQueued.set(false);
        try {
            http.newWebSocketBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .buildAsync(URI.create(url), this)
                .whenComplete((w, err) -> {
                    if (err != null) connectFailed(err);
                });
        } catch (Exception e) {
            connectFailed(e);
        }
    }

    private void connectFailed(Throwable err) {
        if (!warnedDown) {
            warnedDown = true;
            Throwable root = err;
            while (root.getCause() != null) root = root.getCause();
            log.warn("Cannot reach the Discord bot at " + url + " (" + root.getClass().getSimpleName()
                + (root.getMessage() != null ? ": " + root.getMessage() : "") + "). Retrying in the background.");
        }
        scheduleReconnect();
    }

    private void scheduleReconnect() {
        if (stopped || !reconnectQueued.compareAndSet(false, true)) return;
        long delay = Math.min(48L, 3L << Math.min(retry++, 4));
        try {
            exec.schedule(this::connect, delay, TimeUnit.SECONDS);
        } catch (RejectedExecutionException ignored) {
            // stopped
        }
    }

    /* ------------------------- WebSocket.Listener ------------------------- */

    @Override
    public void onOpen(WebSocket webSocket) {
        synchronized (lock) {
            this.ws = webSocket;
            this.tail = CompletableFuture.completedFuture(null);
        }
        pending.set(0);
        Map<String, Object> hello = new LinkedHashMap<>();
        hello.put("t", "hello");
        hello.put("token", token);
        hello.put("id", id);
        hello.put("kind", kind);
        hello.put("version", version);
        hello.put("feat", features);
        post(Json.write(hello));
        webSocket.request(1);
    }

    @Override
    public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
        frame.append(data);
        if (last) {
            String text = frame.toString();
            frame.setLength(0);
            dispatch(text);
        }
        webSocket.request(1);
        return null;
    }

    @Override
    public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
        closed();
        return null;
    }

    @Override
    public void onError(WebSocket webSocket, Throwable error) {
        closed();
    }

    private void closed() {
        boolean was = ready;
        ready = false;
        ws = null;
        frame.setLength(0);
        if (stopped) return;
        if (was) {
            log.warn("Lost the connection to the Discord bot - reconnecting.");
            try {
                handler.onDisconnected();
            } catch (RuntimeException ignored) {
                // never let a handler break the socket
            }
        }
        scheduleReconnect();
    }

    private void dispatch(String text) {
        try {
            Map<String, Object> msg = Json.parseObject(text);
            Object t = msg.get("t");
            if ("ready".equals(t)) {
                ready = true;
                retry = 0;
                warnedDown = false;
                log.info("Connected to the Discord bot as '" + id + "'.");
                handler.onConnected();
            } else if ("error".equals(t)) {
                log.warn("The Discord bot refused the connection: " + msg.get("message") + " (check bot-url and token).");
            } else if (!"ping".equals(t)) {
                handler.onMessage(msg);
            }
        } catch (RuntimeException e) {
            log.warn("Ignored a bad message from the bot: " + e.getMessage());
        }
    }

    /* ------------------------------ sending ------------------------------ */

    /** WebSocket.sendText must not overlap, so sends are chained. Drops messages if the socket stalls. */
    private void post(String text) {
        WebSocket w = ws;
        if (w == null || pending.get() > 300) return;
        pending.incrementAndGet();
        synchronized (lock) {
            tail = tail
                .handle((r, e) -> null)
                .thenCompose(x -> w.sendText(text, true))
                .handle((r, e) -> {
                    pending.decrementAndGet();
                    return null;
                });
        }
    }
}
