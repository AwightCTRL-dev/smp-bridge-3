package dev.smpbridge.paper;

import dev.smpbridge.common.BackupStreamer;
import dev.smpbridge.common.BridgeClient;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Backs up the WHOLE server folder: saves the worlds, pauses auto-save, streams everything as .tar.gz straight to the
 * bot (part by part, waiting for each part to reach Discord), then switches auto-save back on. Nothing is written to disk.
 */
final class BackupJob implements Runnable {
    private static final int CHUNK = 512 * 1024;

    private final JavaPlugin plugin;
    private final BridgeClient client;
    private final String id;
    private final int partBytes;
    private final long maxBytes;
    private final List<String> exclude;
    private final Logger log;
    private final ConcurrentHashMap<Integer, CompletableFuture<String>> acks = new ConcurrentHashMap<>();
    private volatile boolean cancelled;

    BackupJob(JavaPlugin plugin, BridgeClient client, String id, int partBytes, long maxBytes, List<String> exclude) {
        this.plugin = plugin;
        this.client = client;
        this.id = id;
        this.partBytes = partBytes;
        this.maxBytes = maxBytes;
        this.exclude = exclude;
        this.log = plugin.getLogger();
    }

    String id() {
        return id;
    }

    void onAck(int n, boolean ok, String error) {
        CompletableFuture<String> f = acks.get(n);
        if (f != null) f.complete(ok ? null : (error == null ? "the bot rejected the part" : error));
    }

    void cancel() {
        cancelled = true;
        for (CompletableFuture<String> f : acks.values()) f.complete("backup cancelled");
    }

    private void onMain(Runnable r) throws Exception {
        CompletableFuture<Void> f = new CompletableFuture<>();
        Bukkit.getScheduler().runTask(plugin, () -> {
            try {
                r.run();
                f.complete(null);
            } catch (Throwable t) {
                f.completeExceptionally(t);
            }
        });
        f.get(180, TimeUnit.SECONDS);
    }

    @Override
    public void run() {
        Map<World, Boolean> autoSave = new HashMap<>();
        try {
            // a consistent snapshot: flush everything to disk, then stop the world from writing while we read it
            onMain(() -> {
                Bukkit.savePlayers();
                for (World w : Bukkit.getWorlds()) {
                    autoSave.put(w, w.isAutoSave());
                    w.save();
                    w.setAutoSave(false);
                }
            });
            Path root = Paths.get("").toAbsolutePath().normalize();
            BackupStreamer.Stats scan = BackupStreamer.scan(root, exclude);
            Map<String, Object> start = new LinkedHashMap<>();
            start.put("t", "backup_start");
            start.put("id", id);
            start.put("files", scan.files);
            start.put("bytes", scan.uncompressed);
            if (!client.send(start)) throw new IOException("the connection to the bot was lost");
            long t0 = System.currentTimeMillis();
            log.info("Backup started: " + scan.files + " files, " + (scan.uncompressed / (1024 * 1024)) + " MB before compression.");

            BackupStreamer.Stats st = BackupStreamer.stream(root, exclude, partBytes, maxBytes, this::sendPart);

            Map<String, Object> end = new LinkedHashMap<>();
            end.put("t", "backup_end");
            end.put("id", id);
            end.put("parts", st.parts);
            end.put("bytes", st.compressed);
            end.put("skipped", st.skipped);
            end.put("seconds", (System.currentTimeMillis() - t0) / 1000);
            client.send(end);
            log.info("Backup finished: " + st.parts + " parts, " + (st.compressed / (1024 * 1024)) + " MB compressed.");
        } catch (Throwable t) {
            log.warning("Backup failed: " + t.getMessage());
            if (!cancelled) {
                Map<String, Object> err = new LinkedHashMap<>();
                err.put("t", "backup_error");
                err.put("id", id);
                err.put("message", String.valueOf(t.getMessage()));
                client.send(err);
            }
        } finally {
            restoreAutoSave(autoSave);
        }
    }

    private void restoreAutoSave(Map<World, Boolean> autoSave) {
        Runnable restore = () -> {
            for (Map.Entry<World, Boolean> e : autoSave.entrySet()) e.getKey().setAutoSave(e.getValue());
        };
        try {
            onMain(restore);
        } catch (Throwable t) {
            restore.run(); // the plugin is being disabled: do it directly
        }
    }

    private void sendPart(int n, byte[] data, int len) throws IOException {
        if (cancelled) throw new IOException("backup cancelled");
        CompletableFuture<String> ack = new CompletableFuture<>();
        acks.put(n, ack);
        Map<String, Object> head = new LinkedHashMap<>();
        head.put("t", "bpart");
        head.put("id", id);
        head.put("n", n);
        head.put("size", len);
        if (!client.send(head)) throw new IOException("the connection to the bot was lost");
        for (int off = 0; off < len; off += CHUNK) {
            if (!client.sendBinaryBlocking(data, off, Math.min(CHUNK, len - off), 120_000)) {
                throw new IOException("sending part " + n + " to the bot failed");
            }
        }
        Map<String, Object> end = new LinkedHashMap<>();
        end.put("t", "bpart_end");
        end.put("id", id);
        end.put("n", n);
        if (!client.send(end)) throw new IOException("the connection to the bot was lost");
        try {
            String error = ack.get(600, TimeUnit.SECONDS);
            if (error != null) throw new IOException(error);
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("no answer from the bot for part " + n);
        } finally {
            acks.remove(n);
        }
        log.info("Backup part " + n + " delivered (" + (len / 1024) + " KB).");
    }
}
