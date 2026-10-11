package dev.smpbridge.paper;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern;
import org.bukkit.inventory.ItemStack;

/**
 * Keeps the exact items of every death on the server's disk (enchants, names, shulker contents - everything), so
 * "/smpbridge refund <id>" can give them back unchanged. Newest 300 deaths are kept.
 */
final class DeathVault {
    private static final int MAGIC = 0x53444854;
    private static final int KEEP = 300;
    private static final Pattern ID = Pattern.compile("d-[0-9a-z]{3,8}");

    private final File dir;
    private final File pending;

    DeathVault(File dataFolder) {
        this.dir = new File(dataFolder, "deaths");
        this.pending = new File(dataFolder, "pending");
        dir.mkdirs();
        pending.mkdirs();
    }

    static final class Record {
        UUID owner;
        String name;
        long time;
        List<ItemStack> items = new ArrayList<>();
    }

    static boolean validId(String id) {
        return id != null && ID.matcher(id).matches();
    }

    String save(UUID owner, String name, List<ItemStack> items) throws IOException {
        String id;
        do {
            id = "d-" + Integer.toString(ThreadLocalRandom.current().nextInt(1_679_616, 60_466_176), 36);
        } while (new File(dir, id + ".dat").exists());
        try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(new File(dir, id + ".dat"))))) {
            out.writeInt(MAGIC);
            out.writeInt(1);
            out.writeUTF(owner.toString());
            out.writeUTF(name);
            out.writeLong(System.currentTimeMillis());
            out.writeInt(items.size());
            for (ItemStack it : items) {
                byte[] b = it.serializeAsBytes();
                out.writeInt(b.length);
                out.write(b);
            }
        }
        prune();
        return id;
    }

    Record load(String id) throws IOException {
        if (!validId(id)) throw new IOException("bad id");
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(new File(dir, id + ".dat"))))) {
            if (in.readInt() != MAGIC) throw new IOException("not a death record");
            in.readInt();
            Record r = new Record();
            r.owner = UUID.fromString(in.readUTF());
            r.name = in.readUTF();
            r.time = in.readLong();
            int n = in.readInt();
            for (int i = 0; i < n; i++) {
                byte[] b = new byte[in.readInt()];
                in.readFully(b);
                r.items.add(ItemStack.deserializeBytes(b));
            }
            return r;
        }
    }

    boolean isRefunded(String id) {
        return new File(dir, id + ".done").exists();
    }

    void markRefunded(String id) {
        try {
            new File(dir, id + ".done").createNewFile();
        } catch (IOException ignored) {
            // best effort
        }
    }

    /** the player is offline: hand the items over when they next join */
    void queue(UUID owner, String id) {
        File d = new File(pending, owner.toString());
        d.mkdirs();
        try {
            new File(d, id).createNewFile();
        } catch (IOException ignored) {
            // best effort
        }
    }

    List<String> takePending(UUID owner) {
        List<String> ids = new ArrayList<>();
        File[] files = new File(pending, owner.toString()).listFiles();
        if (files == null) return ids;
        for (File f : files) {
            if (validId(f.getName())) ids.add(f.getName());
            f.delete();
        }
        return ids;
    }

    private void prune() {
        File[] files = dir.listFiles((d, n) -> n.endsWith(".dat"));
        if (files == null || files.length <= KEEP) return;
        Arrays.sort(files, Comparator.comparingLong(File::lastModified));
        for (int i = 0; i < files.length - KEEP; i++) {
            files[i].delete();
            new File(dir, files[i].getName().replace(".dat", ".done")).delete();
        }
    }
}
