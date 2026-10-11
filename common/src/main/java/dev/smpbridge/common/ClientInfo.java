package dev.smpbridge.common;

import java.util.Collection;
import java.util.Locale;

/** Works out which client / mod loader a player uses from the brand string (and registered plugin channels). */
public final class ClientInfo {
    private ClientInfo() {}

    public record Info(String client, String loader) {}

    public static Info detect(String brand, Collection<String> channels) {
        String b = brand == null ? "" : brand.trim();
        String l = b.toLowerCase(Locale.ROOT);

        boolean fabricChannel = false;
        boolean forgeChannel = false;
        boolean neoChannel = false;
        boolean quiltChannel = false;
        if (channels != null) {
            for (String c : channels) {
                String x = c == null ? "" : c.toLowerCase(Locale.ROOT);
                if (x.startsWith("fabric")) fabricChannel = true;
                else if (x.startsWith("neoforge")) neoChannel = true;
                else if (x.startsWith("forge") || x.startsWith("fml")) forgeChannel = true;
                else if (x.startsWith("quilt")) quiltChannel = true;
            }
        }

        String client;
        if (l.isEmpty()) client = "Unknown";
        else if (l.contains("lunar")) client = "Lunar Client";
        else if (l.contains("badlion")) client = "Badlion Client";
        else if (l.contains("feather")) client = "Feather Client";
        else if (l.contains("labymod")) client = "LabyMod";
        else if (l.contains("optifine")) client = "OptiFine";
        else if (l.contains("neoforge")) client = "NeoForge";
        else if (l.contains("forge") || l.contains("fml")) client = "Forge";
        else if (l.contains("quilt")) client = "Quilt";
        else if (l.contains("fabric")) client = "Fabric";
        else if (l.equals("vanilla")) client = "Vanilla";
        else client = b.length() > 32 ? b.substring(0, 32) : b;

        String loader;
        if (l.contains("neoforge") || neoChannel) loader = "NeoForge";
        else if (l.contains("forge") || l.contains("fml") || forgeChannel) loader = "Forge";
        else if (l.contains("quilt") || quiltChannel) loader = "Quilt Loader";
        else if (l.contains("fabric") || fabricChannel) loader = "Fabric Loader";
        else if (l.isEmpty()) loader = "Unknown";
        else loader = "None (vanilla)";

        return new Info(client, loader);
    }
}
