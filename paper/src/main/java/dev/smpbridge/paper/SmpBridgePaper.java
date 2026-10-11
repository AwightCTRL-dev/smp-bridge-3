package dev.smpbridge.paper;

import dev.smpbridge.common.BridgeClient;
import dev.smpbridge.common.BridgeCommands;
import dev.smpbridge.common.ClientInfo;
import dev.smpbridge.common.LogForwarder;
import dev.smpbridge.common.Pretty;
import dev.smpbridge.common.Watch;
import io.papermc.paper.event.player.AsyncChatEvent;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Keyed;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.server.ServerCommandEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

public final class SmpBridgePaper extends JavaPlugin implements Listener {
    private BridgeClient client;
    private LogForwarder logs;
    private BukkitTask snapshotTask;
    private DeathVault vault;
    private TotemDetector totem;
    private volatile BackupJob job;

    private boolean forwardChat;
    private boolean forwardJoins;
    private boolean forwardPlayerCommands;
    private boolean forwardDeaths;
    private boolean reportPing;
    private boolean backupEnabled;
    private List<String> backupExclude = new ArrayList<>();
    private String requirePermission = "smpbridge.staff";
    private Set<String> watch = new HashSet<>(Watch.DEFAULT);
    private String id = "smp";

    @Override
    public void onEnable() {
        saveDefaultConfig();
        vault = new DeathVault(getDataFolder());
        totem = new TotemDetector(this);
        getServer().getPluginManager().registerEvents(this, this);
        getServer().getPluginManager().registerEvents(totem, this);
        if (getCommand("smpbridge") != null) {
            getCommand("smpbridge").setExecutor(this::onBridgeCommand);
        }
        startBridge();
    }

    @Override
    public void onDisable() {
        stopBridge();
    }

    /* ------------------------------ lifecycle ------------------------------ */

    private void startBridge() {
        reloadConfig();
        totem.load(getConfig());
        String url = getConfig().getString("bot-url", "").trim();
        String token = getConfig().getString("token", "").trim();
        id = getConfig().getString("id", "smp").trim().toLowerCase(Locale.ROOT);
        boolean forwardConsole = getConfig().getBoolean("forward-console", true);
        forwardChat = getConfig().getBoolean("forward-chat", true);
        forwardJoins = getConfig().getBoolean("forward-joins", true);
        forwardPlayerCommands = getConfig().getBoolean("forward-player-commands", false);
        forwardDeaths = getConfig().getBoolean("forward-deaths", true);
        reportPing = getConfig().getBoolean("report-ping", true);
        backupEnabled = getConfig().getBoolean("backup-enabled", true);
        backupExclude = new ArrayList<>(getConfig().getStringList("backup-exclude"));
        requirePermission = getConfig().getString("watch-require-permission", "smpbridge.staff").trim();
        watch = new HashSet<>(Watch.parseList(String.join(",", getConfig().getStringList("watch-commands"))));

        if (url.isEmpty() || token.isEmpty() || token.startsWith("PASTE")) {
            getLogger().warning("SMPBridge is not configured yet: set bot-url and token in plugins/SMPBridge/config.yml, then /smpbridge reload. (The AutoTotem detector already works.)");
            return;
        }
        List<String> features = new ArrayList<>();
        if (forwardConsole) features.add("console");
        if (forwardChat) features.add("chat");
        if (forwardJoins) features.add("join");
        if (forwardDeaths) features.add("death");
        if (backupEnabled) features.add("backup");

        client = new BridgeClient(url, token, id, "paper", getPluginMeta().getVersion(), features, new Handler(), new BridgeClient.Log() {
            @Override public void info(String message) { getLogger().info(message); }
            @Override public void warn(String message) { getLogger().warning(message); }
        });
        if (forwardConsole) {
            logs = new LogForwarder(client);
            if (!logs.install()) {
                getLogger().warning("Console forwarding is unavailable (log4j not accessible); everything else still works.");
                logs = null;
            }
        }
        client.start();
        snapshotTask = Bukkit.getScheduler().runTaskTimer(this, this::sendPlayers, 20L * 5, 20L * 10);
    }

    private void stopBridge() {
        BackupJob j = job;
        if (j != null) j.cancel();
        if (snapshotTask != null) { snapshotTask.cancel(); snapshotTask = null; }
        if (logs != null) { logs.uninstall(); logs = null; }
        if (client != null) { client.stop(); client = null; }
    }

    boolean sendToBot(Map<String, Object> m) {
        BridgeClient c = client;
        return c != null && c.send(m);
    }

    /* ------------------------------- bot -> server ------------------------------- */

    private static TextColor colorOf(Object name) {
        String n = String.valueOf(name).toLowerCase(Locale.ROOT);
        return switch (n) {
            case "red" -> NamedTextColor.RED;
            case "green" -> NamedTextColor.GREEN;
            case "yellow" -> NamedTextColor.YELLOW;
            case "gold" -> NamedTextColor.GOLD;
            case "light_purple" -> NamedTextColor.LIGHT_PURPLE;
            case "white" -> NamedTextColor.WHITE;
            case "blue" -> NamedTextColor.BLUE;
            default -> NamedTextColor.AQUA;
        };
    }

    private static int intOf(Object o) {
        return o instanceof Number n ? n.intValue() : -1;
    }

    private final class Handler implements BridgeClient.Handler {
        @Override
        public void onMessage(Map<String, Object> msg) {
            Object t = msg.get("t");
            if ("chat".equals(t)) {
                TextColor color = colorOf(msg.get("color"));
                Component line = Component.text("[Discord] ", color)
                    .append(Component.text(String.valueOf(msg.get("name")), color))
                    .append(Component.text(" » ", NamedTextColor.GRAY))
                    .append(Component.text(String.valueOf(msg.get("message")), color));
                Bukkit.getScheduler().runTask(SmpBridgePaper.this, () -> {
                    for (Player p : Bukkit.getOnlinePlayers()) p.sendMessage(line);
                });
            } else if ("command".equals(t)) {
                String cmd = String.valueOf(msg.get("cmd"));
                Bukkit.getScheduler().runTask(SmpBridgePaper.this, () -> {
                    getLogger().info("Console command from Discord (" + msg.get("by") + "): " + cmd);
                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd);
                });
            } else if ("backup_request".equals(t)) {
                startBackup(msg);
            } else if ("part_ack".equals(t)) {
                BackupJob j = job;
                if (j != null && j.id().equals(msg.get("id"))) {
                    j.onAck(intOf(msg.get("n")), Boolean.TRUE.equals(msg.get("ok")), msg.get("error") == null ? null : String.valueOf(msg.get("error")));
                }
            } else if ("backup_cancel".equals(t)) {
                BackupJob j = job;
                if (j != null && j.id().equals(msg.get("id"))) j.cancel();
            }
        }

        @Override
        public void onConnected() {
            Bukkit.getScheduler().runTask(SmpBridgePaper.this, SmpBridgePaper.this::sendPlayers);
        }

        @Override
        public void onDisconnected() {
            BackupJob j = job;
            if (j != null) j.cancel(); // the bot is gone: stop streaming
        }
    }

    private void startBackup(Map<String, Object> msg) {
        BridgeClient c = client;
        String backupId = String.valueOf(msg.get("id"));
        if (c == null) return;
        if (!backupEnabled || job != null) {
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("t", "backup_error");
            err.put("id", backupId);
            err.put("message", job != null ? "a backup is already running on the server" : "backups are disabled in the plugin config (backup-enabled: false)");
            c.send(err);
            return;
        }
        int partBytes = (int) Math.max(256 * 1024L, Math.min(100L * 1024 * 1024, ((Number) msg.getOrDefault("partBytes", 9_000_000)).longValue()));
        long maxBytes = ((Number) msg.getOrDefault("maxBytes", 3_000L * 1024 * 1024)).longValue();
        BackupJob j = new BackupJob(this, c, backupId, partBytes, maxBytes, backupExclude);
        job = j;
        Thread th = new Thread(() -> {
            try {
                j.run();
            } finally {
                job = null;
            }
        }, "SMPBridge-backup");
        th.setDaemon(true);
        th.setPriority(Thread.MIN_PRIORITY);
        th.start();
    }

    /* ------------------------------- server -> bot ------------------------------- */

    /** Runs on the main thread (scheduler) because it reads player data. */
    private void sendPlayers() {
        totem.samplePings();
        BridgeClient c = client;
        if (c == null || !c.isReady()) return;
        List<Object> list = new ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            ClientInfo.Info info = ClientInfo.detect(p.getClientBrandName(), p.getListeningPluginChannels());
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", p.getName());
            if (reportPing) m.put("ping", p.getPing());
            m.put("client", info.client());
            m.put("loader", info.loader());
            list.add(m);
        }
        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("t", "players");
        msg.put("online", list.size());
        msg.put("max", Bukkit.getMaxPlayers());
        double[] tps = Bukkit.getTPS();
        if (tps.length > 0) msg.put("tps", Math.round(Math.min(tps[0], 20.0) * 10.0) / 10.0);
        msg.put("list", list);
        c.send(msg);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        BridgeClient c = client;
        if (c == null || !forwardChat) return;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("t", "chat");
        m.put("player", event.getPlayer().getName());
        m.put("message", PlainTextComponentSerializer.plainText().serialize(event.message()));
        m.put("server", id);
        c.send(m);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player p = event.getPlayer();
        BridgeClient c = client;
        if (c != null) {
            if (forwardJoins) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("t", "join");
                m.put("player", p.getName());
                c.send(m);
            }
            // the client brand arrives a moment after join
            Bukkit.getScheduler().runTaskLater(this, this::sendPlayers, 60L);
        }
        // refunds that were waiting for this player
        if (vault != null) {
            List<String> due = vault.takePending(p.getUniqueId());
            if (!due.isEmpty()) {
                Bukkit.getScheduler().runTaskLater(this, () -> {
                    for (String deathId : due) {
                        try {
                            give(p, vault.load(deathId).items);
                        } catch (IOException e) {
                            getLogger().warning("Could not deliver refund " + deathId + ": " + e.getMessage());
                        }
                    }
                }, 40L);
            }
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        BridgeClient c = client;
        if (c == null || !forwardJoins) return;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("t", "leave");
        m.put("player", event.getPlayer().getName());
        c.send(m);
    }

    /** Death log: exact coordinates, biome ("Void" when they fell out of the world), every lost item + a refund id. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent event) {
        BridgeClient c = client;
        if (c == null || !forwardDeaths) return;
        Player p = event.getEntity();
        Location loc = p.getLocation();
        World world = loc.getWorld();
        EntityDamageEvent last = p.getLastDamageCause();

        boolean inVoid = last != null && last.getCause() == EntityDamageEvent.DamageCause.VOID;
        String biome = "Unknown";
        String dimension = "Unknown";
        String worldName = "Unknown";
        if (world != null) {
            worldName = world.getName();
            if (loc.getY() < world.getMinHeight()) inVoid = true;
            dimension = switch (world.getEnvironment()) {
                case NORMAL -> "Overworld";
                case NETHER -> "The Nether";
                case THE_END -> "The End";
                default -> world.getName();
            };
            if (!inVoid) {
                try {
                    // cast to Keyed so this works whether Biome is an enum (older 1.21) or an interface (newer)
                    Object b = world.getBiome(loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());
                    if (b instanceof Keyed keyed) {
                        String key = keyed.getKey().getKey();
                        if (key.equals("the_void")) inVoid = true;
                        else biome = Pretty.biome(keyed.getKey().getNamespace() + ":" + key);
                    }
                } catch (Throwable ignored) {
                    // biome stays "Unknown"
                }
            }
        }

        Component dm = event.deathMessage();
        String text = dm != null ? PlainTextComponentSerializer.plainText().serialize(dm) : p.getName() + " died";
        Player killer = p.getKiller();

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("t", "death");
        m.put("player", p.getName());
        m.put("message", text);
        m.put("cause", last != null ? Pretty.title(last.getCause().name()) : "Unknown");
        if (killer != null) m.put("killer", killer.getName());
        m.put("world", worldName);
        m.put("dimension", dimension);
        m.put("x", Math.round(loc.getX() * 100.0) / 100.0);
        m.put("y", Math.round(loc.getY() * 100.0) / 100.0);
        m.put("z", Math.round(loc.getZ() * 100.0) / 100.0);
        m.put("biome", inVoid ? "Void" : biome);
        m.put("void", inVoid);
        m.put("keepInventory", event.getKeepInventory());

        if (!event.getKeepInventory()) {
            try {
                List<ItemStack> lost = new ArrayList<>();
                for (ItemStack it : event.getDrops()) {
                    if (it != null && !it.getType().isAir()) lost.add(it.clone());
                }
                if (!lost.isEmpty()) {
                    ItemSummary.Result sum = ItemSummary.summarize(lost);
                    m.put("items", sum.items.size() > 100 ? sum.items.subList(0, 100) : sum.items);
                    m.put("totalItems", sum.totalItems);
                    m.put("totalStacks", sum.totalStacks);
                    m.put("refundId", vault.save(p.getUniqueId(), p.getName(), lost));
                }
            } catch (Throwable t) {
                getLogger().warning("Could not record the lost items of " + p.getName() + ": " + t.getMessage());
            }
        }
        c.send(m);
    }

    /** Commands typed in the Paper console (always trusted). */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onConsoleCommand(ServerCommandEvent event) {
        BridgeClient c = client;
        if (c == null || !(event.getSender() instanceof ConsoleCommandSender)) return;
        if (!Watch.watched(watch, event.getCommand())) return;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("t", "command");
        m.put("by", "Console");
        m.put("cmd", event.getCommand());
        c.send(m);
    }

    /** Optional: staff commands typed in game. A player's command is only sent if they hold watch-require-permission. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerCommand(PlayerCommandPreprocessEvent event) {
        BridgeClient c = client;
        if (c == null || !forwardPlayerCommands) return;
        Player p = event.getPlayer();
        if (!Watch.watched(watch, event.getMessage())) return;
        if (!requirePermission.isEmpty() && !p.hasPermission(requirePermission)) return;
        String cmd = event.getMessage().startsWith("/") ? event.getMessage().substring(1) : event.getMessage();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("t", "command");
        m.put("by", p.getName());
        m.put("cmd", cmd);
        c.send(m);
    }

    /* ------------------------------ refunds ------------------------------ */

    private int give(Player p, List<ItemStack> items) {
        int n = 0;
        for (ItemStack it : items) {
            Map<Integer, ItemStack> left = p.getInventory().addItem(it);
            for (ItemStack l : left.values()) p.getWorld().dropItemNaturally(p.getLocation(), l);
            n++;
        }
        p.sendMessage(Component.text("Your lost items were returned (" + n + " stacks).", NamedTextColor.GREEN));
        return n;
    }

    private String refund(String[] args) {
        if (args.length < 2) return "Usage: /smpbridge refund <deathId> [force]";
        String deathId = args[1].toLowerCase(Locale.ROOT);
        boolean force = args.length > 2 && args[2].equalsIgnoreCase("force");
        DeathVault.Record rec;
        try {
            rec = vault.load(deathId);
        } catch (IOException | RuntimeException e) {
            return "Unknown or unreadable death id: " + deathId;
        }
        if (vault.isRefunded(deathId) && !force) return "Death " + deathId + " was already refunded. Add 'force' to give the items again.";
        Player p = Bukkit.getPlayer(rec.owner);
        if (p == null) {
            vault.queue(rec.owner, deathId);
            vault.markRefunded(deathId);
            return rec.name + " is offline - the items will be handed over when they next join.";
        }
        int n = give(p, rec.items);
        vault.markRefunded(deathId);
        return "Gave " + p.getName() + " back " + n + " stacks from death " + deathId + ".";
    }

    /* ------------------------------ /smpbridge ------------------------------ */

    private boolean onBridgeCommand(CommandSender sender, Command command, String label, String[] args) {
        String reply;
        if (args.length == 0) {
            reply = "SMPBridge: /smpbridge <status|reload|test|alert|punish|refund|totem>";
        } else if (args[0].equalsIgnoreCase("status")) {
            reply = "SMPBridge " + getPluginMeta().getVersion() + " id=" + id + " connected=" + (client != null && client.isReady())
                + " backup=" + (job != null ? "running" : "idle");
        } else if (args[0].equalsIgnoreCase("reload")) {
            stopBridge();
            startBridge();
            reply = "SMPBridge reloaded.";
        } else if (args[0].equalsIgnoreCase("refund")) {
            reply = refund(args);
        } else if (args[0].equalsIgnoreCase("totem")) {
            reply = args.length < 2 ? "Usage: /smpbridge totem <player>" : totem.describe(args[1]);
        } else {
            BridgeCommands.Result r = BridgeCommands.handle(args);
            if (r == null) reply = "Unknown subcommand. Use status, reload, test, alert, punish, refund or totem.";
            else if (r.message == null) reply = r.reply;
            else if (client == null || !client.send(r.message)) reply = "Not connected to the Discord bot right now.";
            else reply = r.reply;
        }
        sender.sendMessage(Component.text(reply, NamedTextColor.YELLOW));
        return true;
    }
}
