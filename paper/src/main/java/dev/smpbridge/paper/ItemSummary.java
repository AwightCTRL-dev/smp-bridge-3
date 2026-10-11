package dev.smpbridge.paper;

import dev.smpbridge.common.Pretty;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.bukkit.Keyed;
import org.bukkit.block.BlockState;
import org.bukkit.block.Container;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.BundleMeta;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.ItemMeta;

/** Turns the items a player lost into a compact list (shulker boxes and bundles show their contents). */
final class ItemSummary {
    private ItemSummary() {}

    static final class Result {
        final List<Object> items = new ArrayList<>();
        int totalItems;
        int totalStacks;
    }

    static Result summarize(List<ItemStack> stacks) {
        Result r = new Result();
        Map<String, Map<String, Object>> plain = new LinkedHashMap<>();
        List<Object> containers = new ArrayList<>();

        for (ItemStack st : stacks) {
            if (st == null || st.getType().isAir()) continue;
            r.totalStacks++;
            int amount = st.getAmount();
            r.totalItems += amount;
            String name = Pretty.title(st.getType().name());
            String ench = enchants(st);
            List<Object> inner = contentsOf(st);
            if (inner != null && !inner.isEmpty()) {
                for (Object o : inner) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> m = (Map<String, Object>) o;
                    r.totalItems += (Integer) m.get("amount");
                }
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("name", name);
                m.put("amount", amount);
                if (!ench.isEmpty()) m.put("ench", ench);
                m.put("contents", inner);
                containers.add(m);
            } else {
                merge(plain, name, ench, amount);
            }
        }
        r.items.addAll(plain.values());
        r.items.addAll(containers);
        return r;
    }

    private static void merge(Map<String, Map<String, Object>> into, String name, String ench, int amount) {
        String key = name + "|" + ench;
        Map<String, Object> m = into.get(key);
        if (m == null) {
            m = new LinkedHashMap<>();
            m.put("name", name);
            m.put("amount", amount);
            if (!ench.isEmpty()) m.put("ench", ench);
            into.put(key, m);
        } else {
            m.put("amount", (Integer) m.get("amount") + amount);
        }
    }

    /** Items inside a shulker box / chest-like block item / bundle (one level deep), grouped by name. */
    private static List<Object> contentsOf(ItemStack st) {
        try {
            ItemMeta meta = st.getItemMeta();
            if (meta == null) return null;
            List<ItemStack> inner = null;
            if (meta instanceof BlockStateMeta bsm) {
                BlockState bs = bsm.getBlockState();
                if (bs instanceof Container c) inner = Arrays.asList(c.getInventory().getContents());
            } else if (meta instanceof BundleMeta bm) {
                inner = bm.getItems();
            }
            if (inner == null) return null;
            Map<String, Map<String, Object>> grouped = new LinkedHashMap<>();
            for (ItemStack it : inner) {
                if (it == null || it.getType().isAir()) continue;
                merge(grouped, Pretty.title(it.getType().name()), enchants(it), it.getAmount());
            }
            return new ArrayList<>(grouped.values());
        } catch (Throwable t) {
            return null;
        }
    }

    private static String enchants(ItemStack st) {
        List<String> parts = new ArrayList<>();
        try {
            add(parts, st.getEnchantments());
            ItemMeta meta = st.getItemMeta();
            if (meta instanceof EnchantmentStorageMeta esm) add(parts, esm.getStoredEnchants());
        } catch (Throwable ignored) {
            // no enchantment info
        }
        if (parts.size() > 4) {
            int more = parts.size() - 4;
            parts = new ArrayList<>(parts.subList(0, 4));
            parts.add("+" + more + " more");
        }
        return String.join(", ", parts);
    }

    /** Works for both the old enum-like and the new interface-based Enchantment (read through Keyed). */
    private static void add(List<String> out, Map<?, Integer> map) {
        if (map == null) return;
        for (Map.Entry<?, Integer> e : map.entrySet()) {
            Object k = e.getKey();
            if (k instanceof Keyed kd) out.add(Pretty.title(kd.getKey().getKey()) + " " + Pretty.roman(e.getValue()));
        }
    }
}
