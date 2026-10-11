package dev.smpbridge.common;

import java.util.Locale;

/** Turns Minecraft ids into readable names: "minecraft:dark_forest" -> "Dark Forest". */
public final class Pretty {
    private Pretty() {}

    public static String title(String snake) {
        if (snake == null || snake.isBlank()) return "Unknown";
        StringBuilder sb = new StringBuilder();
        for (String part : snake.trim().toLowerCase(Locale.ROOT).replace('-', '_').split("_+")) {
            if (part.isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
        return sb.length() == 0 ? "Unknown" : sb.toString();
    }

    /** 1 -> I, 5 -> V, 4 -> IV (enchantment levels); anything above 20 stays a number. */
    public static String roman(int n) {
        if (n <= 0 || n > 20) return String.valueOf(n);
        String[] r = {"", "I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X"};
        return n <= 10 ? r[n] : "X" + r[n - 10];
    }

    /** Accepts "minecraft:dark_forest", "dark_forest" or "terralith:alpine_grove" (namespace is dropped). */
    public static String biome(String key) {
        if (key == null) return "Unknown";
        int colon = key.indexOf(':');
        return title(colon >= 0 ? key.substring(colon + 1) : key);
    }
}
