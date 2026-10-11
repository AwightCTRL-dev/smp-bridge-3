package dev.smpbridge.common;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Which commands are forwarded to Discord. Everything else (/login, /msg ...) is never sent. */
public final class Watch {
    private Watch() {}

    public static final List<String> DEFAULT = List.of(
        "ban", "tempban", "ipban", "banip", "ban-ip", "tban",
        "kick", "mute", "tempmute", "tmute", "unban", "pardon", "pardon-ip", "unbanip", "unban-ip",
        "unmute", "warn", "tempwarn");

    public static List<String> parseList(String csv) {
        List<String> out = new ArrayList<>();
        if (csv != null) {
            for (String s : csv.split(",")) {
                String t = s.trim().toLowerCase(Locale.ROOT);
                if (!t.isEmpty()) out.add(t);
            }
        }
        return out.isEmpty() ? new ArrayList<>(DEFAULT) : out;
    }

    /** true if the first word of the command line (namespace stripped) is on the list. */
    public static boolean watched(Set<String> list, String commandLine) {
        if (commandLine == null) return false;
        String c = commandLine.trim();
        if (c.startsWith("/")) c = c.substring(1);
        int sp = c.indexOf(' ');
        String first = (sp < 0 ? c : c.substring(0, sp)).toLowerCase(Locale.ROOT);
        int colon = first.indexOf(':');
        if (colon >= 0) first = first.substring(colon + 1);
        return list.contains(first);
    }
}
