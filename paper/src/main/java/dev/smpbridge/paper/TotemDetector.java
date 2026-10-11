package dev.smpbridge.paper;

import dev.smpbridge.common.ClientInfo;
import dev.smpbridge.common.TotemAnalyzer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityResurrectEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.ItemStack;

/**
 * Built-in AutoTotem detector. Measures, for every totem pop, how long it takes until a new totem is back in the off
 * hand, removes the player's ping, and hands the reaction time to TotemAnalyzer (checks A, B, C).
 * Alerts go to the Discord bot (and to staff in game); banning is the bot's job (auto-ban at the alert limit).
 */
final class TotemDetector implements Listener {
    private static final double POP_WINDOW_NANOS = 2.5e9;

    private final SmpBridgePaper plugin;
    private final TotemAnalyzer.Config cfg = new TotemAnalyzer.Config();
    private final Map<UUID, State> states = new ConcurrentHashMap<>();

    private boolean enabled = true;
    private boolean inGameAlerts = true;
    private double minTps = 18.0;
    private int maxPing = 350;
    private long cooldownMs = 3000;
    private String bypass = "smpbridge.bypass";

    private static final class State {
        final TotemAnalyzer analyzer = new TotemAnalyzer();
        final Deque<Integer> pings = new ArrayDeque<>();
        final Map<String, Long> lastAlert = new HashMap<>();
        volatile boolean pending;
        volatile long popNanos;
        volatile long actionNanos;
    }

    TotemDetector(SmpBridgePaper plugin) {
        this.plugin = plugin;
    }

    void load(FileConfiguration c) {
        enabled = c.getBoolean("autototem.enabled", true);
        inGameAlerts = c.getBoolean("autototem.in-game-alerts", true);
        minTps = c.getDouble("autototem.min-tps", 18.0);
        maxPing = c.getInt("autototem.max-ping", 350);
        cooldownMs = c.getLong("autototem.alert-cooldown-ms", 3000);
        bypass = c.getString("autototem.bypass-permission", "smpbridge.bypass");
        cfg.minReactionMs = c.getDouble("autototem.min-reaction-ms", 60);
        cfg.chainReactionMs = c.getDouble("autototem.chain-reaction-ms", 100);
        cfg.chainCount = c.getInt("autototem.chain-count", 3);
        cfg.samples = Math.max(4, c.getInt("autototem.samples", 8));
        cfg.maxStdDevMs = c.getDouble("autototem.max-std-dev-ms", 12);
        cfg.maxRangeMs = c.getDouble("autototem.max-range-ms", 40);
        cfg.maxMeanMs = c.getDouble("autototem.max-mean-ms", 600);
        cfg.windowMs = c.getLong("autototem.window-seconds", 600) * 1000L;
    }

    /** called every few seconds on the main thread so we always know the player's lowest recent ping */
    void samplePings() {
        for (Player p : Bukkit.getOnlinePlayers()) record(state(p), p.getPing());
    }

    private State state(Player p) {
        return states.computeIfAbsent(p.getUniqueId(), k -> new State());
    }

    private static void record(State st, int ping) {
        synchronized (st.pings) {
            st.pings.addLast(Math.max(0, ping));
            while (st.pings.size() > 6) st.pings.removeFirst();
        }
    }

    /** the smallest recent reading: when in doubt the reaction comes out longer, never shorter (fewer false alerts) */
    private static int lowestPing(State st) {
        synchronized (st.pings) {
            int lo = Integer.MAX_VALUE;
            for (int v : st.pings) lo = Math.min(lo, v);
            return lo == Integer.MAX_VALUE ? 0 : lo;
        }
    }

    /* ------------------------------ events ------------------------------ */

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPop(EntityResurrectEvent event) {
        if (!enabled || !(event.getEntity() instanceof Player p)) return;
        // only when the totem comes from the off hand do we expect a re-totem into that hand
        if (p.getInventory().getItemInOffHand().getType() != Material.TOTEM_OF_UNDYING) return;
        State st = state(p);
        record(st, p.getPing());
        st.popNanos = System.nanoTime();
        st.pending = true;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onClick(InventoryClickEvent event) {
        if (event.getWhoClicked() instanceof Player p) stamp(p);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDrag(InventoryDragEvent event) {
        if (event.getWhoClicked() instanceof Player p) stamp(p);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSwap(PlayerSwapHandItemsEvent event) {
        stamp(event.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        states.remove(event.getPlayer().getUniqueId());
    }

    /** remember WHEN the player did something with their inventory; the next tick tells us whether it put a totem in */
    private void stamp(Player p) {
        if (!enabled) return;
        State st = states.get(p.getUniqueId());
        if (st == null || !st.pending) return;
        long now = System.nanoTime();
        if (now - st.popNanos > POP_WINDOW_NANOS) {
            st.pending = false;
            return;
        }
        st.actionNanos = now; // the LAST action before the check is the one that completed the swap
        Bukkit.getScheduler().runTask(plugin, () -> verify(p, st));
    }

    private void verify(Player p, State st) {
        if (!st.pending) return;
        ItemStack off = p.getInventory().getItemInOffHand();
        if (off.getType() != Material.TOTEM_OF_UNDYING) return; // not done yet, wait for the next action
        st.pending = false;

        if (!enabled || p.hasPermission(bypass)) return;
        if (p.getGameMode() == GameMode.CREATIVE || p.getGameMode() == GameMode.SPECTATOR) return;
        double[] tpsArr = Bukkit.getTPS();
        double tps = tpsArr.length > 0 ? Math.min(20.0, tpsArr[0]) : 20.0;
        record(st, p.getPing());
        int ping = lowestPing(st);
        if (tps < minTps || ping > maxPing) return; // lag makes timing meaningless

        double totalMs = (st.actionNanos - st.popNanos) / 1_000_000.0;
        List<TotemAnalyzer.Flag> flags = st.analyzer.add(new TotemAnalyzer.Sample(System.currentTimeMillis(), totalMs, ping), tps, cfg);
        for (TotemAnalyzer.Flag flag : flags) alert(p, st, flag, ping);
    }

    private void alert(Player p, State st, TotemAnalyzer.Flag flag, int ping) {
        String check = "AutoTotem" + flag.check();
        long now = System.currentTimeMillis();
        Long last = st.lastAlert.get(check);
        if (last != null && now - last < cooldownMs) return;
        st.lastAlert.put(check, now);

        ClientInfo.Info info = ClientInfo.detect(p.getClientBrandName(), p.getListeningPluginChannels());
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("t", "alert");
        m.put("player", p.getName());
        m.put("check", check);
        m.put("by", "AutoTotem");
        m.put("client", info.client());
        m.put("loader", info.loader());
        m.put("ping", ping);
        m.put("details", flag.details());
        plugin.sendToBot(m);

        if (inGameAlerts) {
            Component line = Component.text("[AutoTotem] ", NamedTextColor.RED)
                .append(Component.text(p.getName() + " flagged " + check + " (" + flag.reason() + ") ", NamedTextColor.WHITE))
                .append(Component.text(flag.details().get(0), NamedTextColor.GRAY));
            for (Player s : Bukkit.getOnlinePlayers()) {
                if (s.hasPermission("smpbridge.alerts")) s.sendMessage(line);
            }
        }
    }

    /** /smpbridge totem <player> */
    String describe(String name) {
        Player p = Bukkit.getPlayerExact(name);
        if (p == null) return "Player " + name + " is not online.";
        State st = states.get(p.getUniqueId());
        List<TotemAnalyzer.Sample> s = st == null ? new ArrayList<>() : st.analyzer.recent();
        if (s.isEmpty()) return p.getName() + ": no totem re-equips measured yet (ping now " + p.getPing() + " ms).";
        List<TotemAnalyzer.Sample> last = s.subList(Math.max(0, s.size() - cfg.samples), s.size());
        StringBuilder sb = new StringBuilder();
        sb.append(String.format(Locale.ROOT, "%s: %d samples | ping %d ms | mean %.1f ms | stddev %.1f ms | spread %.0f ms%n",
            p.getName(), s.size(), p.getPing(), TotemAnalyzer.mean(last), TotemAnalyzer.stdDev(last), TotemAnalyzer.range(last)));
        for (TotemAnalyzer.Sample x : last) {
            sb.append(String.format(Locale.ROOT, "  switch %.0f ms = total %.0f - ping %.0f%n", x.reactionMs(), x.totalMs(), x.pingMs()));
        }
        return sb.toString().trim();
    }
}
