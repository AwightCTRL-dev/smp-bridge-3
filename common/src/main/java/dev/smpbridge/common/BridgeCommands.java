package dev.smpbridge.common;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * /smpbridge alert|punish|test [death]  - lets any anticheat or punishment plugin report to Discord by running a command.
 *
 *   smpbridge alert  %player% AutoTotem %vl%  [client:Lunar_Client] [loader:Fabric_Loader] [ping:45] [by:AutoTotem]
 *   smpbridge punish ban %player% by:AutoTotem dur:perm AutoTotem detected
 *   smpbridge test
 * key:value options can come in any order, underscores become spaces, everything else is the reason.
 */
public final class BridgeCommands {
    private BridgeCommands() {}

    public static final Set<String> ACTIONS = Set.of("ban", "kick", "mute", "warn", "unban", "unmute");
    private static final Pattern KV = Pattern.compile("^(by|dur|duration|check|client|loader|ping|vl):(.+)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern NAME = Pattern.compile("^[A-Za-z0-9_.]{1,24}$");
    private static final Pattern NUMBER = Pattern.compile("^\\d+(\\.\\d+)?$");

    public static final class Result {
        public final Map<String, Object> message; // null when there is nothing to send
        public final String reply;

        private Result(Map<String, Object> message, String reply) {
            this.message = message;
            this.reply = reply;
        }
    }

    /** @return null when the first argument is not one of alert / punish / test */
    public static Result handle(String[] args) {
        if (args.length == 0) return null;
        String sub = args[0].toLowerCase(Locale.ROOT);
        return switch (sub) {
            case "alert" -> alert(args);
            case "punish" -> punish(args);
            case "test" -> args.length > 1 && args[1].equalsIgnoreCase("death") ? testDeath() : test();
            default -> null;
        };
    }

    private static Result error(String m) { return new Result(null, m); }

    private static Result test() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("t", "alert");
        m.put("player", "BridgeTest");
        m.put("check", "TestCheck");
        m.put("vl", 1);
        m.put("client", "Fabric");
        m.put("loader", "Fabric Loader");
        m.put("ping", 42);
        m.put("by", "SMPBridge");
        return new Result(m, "Sent a test alert - check your punishment-logs channel.");
    }

    private static Result testDeath() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("t", "death");
        m.put("player", "BridgeTest");
        m.put("message", "BridgeTest fell out of the world");
        m.put("cause", "Void");
        m.put("world", "world");
        m.put("dimension", "Overworld");
        m.put("x", 123.46);
        m.put("y", -78.2);
        m.put("z", -455.72);
        m.put("biome", "Void");
        m.put("void", Boolean.TRUE);
        m.put("keepInventory", Boolean.FALSE);
        java.util.List<Object> items = new java.util.ArrayList<>();
        items.add(item("Diamond Sword", 1, "Sharpness V, Unbreaking III", null));
        items.add(item("Diamond", 64, null, null));
        items.add(item("Golden Apple", 12, null, null));
        items.add(item("Purple Shulker Box", 1, null, java.util.List.<Object>of(item("Cobblestone", 64, null, null), item("Ender Pearl", 16, null, null))));
        m.put("items", items);
        m.put("totalItems", 158);
        m.put("totalStacks", 4);
        return new Result(m, "Sent a test death (with a sample item list) - check your death-logs channel.");
    }

    private static Map<String, Object> item(String name, int amount, String ench, java.util.List<Object> contents) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("amount", amount);
        if (ench != null) m.put("ench", ench);
        if (contents != null) m.put("contents", contents);
        return m;
    }

    private static Result alert(String[] args) {
        Map<String, String> kv = new LinkedHashMap<>();
        List<String> pos = split(args, 1, kv);
        if (pos.isEmpty() || !NAME.matcher(pos.get(0)).matches()) return error("Usage: /smpbridge alert <player> <check> [vl] [client:..] [loader:..] [ping:..] [by:..]");
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("t", "alert");
        m.put("player", pos.get(0));
        String check = pos.size() > 1 ? pos.get(1) : kv.get("check");
        if (check != null) m.put("check", check);
        String vl = kv.get("vl");
        if (vl == null && pos.size() > 2 && NUMBER.matcher(pos.get(2)).matches()) vl = pos.get(2);
        putNumber(m, "vl", vl);
        putNumber(m, "ping", kv.get("ping"));
        putText(m, "client", kv.get("client"));
        putText(m, "loader", kv.get("loader"));
        putText(m, "by", kv.get("by"));
        return new Result(m, "Alert sent for " + pos.get(0) + ".");
    }

    private static Result punish(String[] args) {
        Map<String, String> kv = new LinkedHashMap<>();
        List<String> pos = split(args, 1, kv);
        String usage = "Usage: /smpbridge punish <ban|kick|mute|warn|unban|unmute> <player> [reason...] [by:..] [dur:..] [client:..] [loader:..] [ping:..]";
        if (pos.size() < 2) return error(usage);
        String action = pos.get(0).toLowerCase(Locale.ROOT);
        if (!ACTIONS.contains(action) || !NAME.matcher(pos.get(1)).matches()) return error(usage);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("t", "punish");
        m.put("action", action);
        m.put("player", pos.get(1));
        if (pos.size() > 2) m.put("reason", String.join(" ", pos.subList(2, pos.size())));
        putText(m, "by", kv.get("by"));
        putText(m, "duration", kv.containsKey("duration") ? kv.get("duration") : kv.get("dur"));
        putText(m, "check", kv.get("check"));
        putText(m, "client", kv.get("client"));
        putText(m, "loader", kv.get("loader"));
        putNumber(m, "ping", kv.get("ping"));
        return new Result(m, "Logged " + action + " for " + pos.get(1) + ".");
    }

    private static List<String> split(String[] args, int from, Map<String, String> kv) {
        List<String> pos = new ArrayList<>();
        for (int i = from; i < args.length; i++) {
            Matcher mt = KV.matcher(args[i]);
            if (mt.matches()) kv.put(mt.group(1).toLowerCase(Locale.ROOT), mt.group(2).replace('_', ' '));
            else pos.add(args[i]);
        }
        return pos;
    }

    private static void putText(Map<String, Object> m, String key, String v) {
        if (v != null && !v.isBlank() && !v.contains("%")) m.put(key, v);
    }

    private static void putNumber(Map<String, Object> m, String key, String v) {
        if (v == null) return;
        String t = v.trim().replaceAll("(?i)ms$", "");
        if (NUMBER.matcher(t).matches()) m.put(key, Double.valueOf(t));
    }
}
