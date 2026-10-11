package dev.smpbridge.common;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.zip.Deflater;
import java.util.zip.GZIPOutputStream;

/**
 * Streams a whole folder as .tar.gz, cut into parts of at most partBytes, without ever writing a file to disk
 * and without any library. Each finished part is handed to a PartHandler (which uploads it and waits).
 */
public final class BackupStreamer {
    private BackupStreamer() {}

    public interface PartHandler {
        void part(int n, byte[] data, int len) throws IOException;
    }

    public static final class Stats {
        public long files;
        public long dirs;
        public long skipped;
        public long uncompressed;
        public long compressed;
        public int parts;
    }

    /* ------------------------------ helpers ------------------------------ */

    private static String rel(Path root, Path p) {
        return root.relativize(p).toString().replace('\\', '/');
    }

    static boolean excluded(String rel, List<String> exclude) {
        if (exclude == null) return false;
        for (String raw : exclude) {
            String ex = raw == null ? "" : raw.trim().replace('\\', '/');
            while (ex.startsWith("./")) ex = ex.substring(2);
            while (ex.endsWith("/")) ex = ex.substring(0, ex.length() - 1);
            if (ex.isEmpty()) continue;
            if (rel.equals(ex) || rel.startsWith(ex + "/")) return true;
        }
        return false;
    }

    private static long mtime(BasicFileAttributes a) {
        long s = a.lastModifiedTime().to(TimeUnit.SECONDS);
        return Math.max(0, s);
    }

    /** Quick pass to count what will be archived (shown to staff before the upload starts). */
    public static Stats scan(Path root, List<String> exclude) throws IOException {
        Stats st = new Stats();
        Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes a) {
                if (dir.equals(root)) return FileVisitResult.CONTINUE;
                if (excluded(rel(root, dir), exclude)) return FileVisitResult.SKIP_SUBTREE;
                st.dirs++;
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes a) {
                if (excluded(rel(root, file), exclude)) return FileVisitResult.CONTINUE;
                if (a.isSymbolicLink() || a.isOther()) { st.skipped++; return FileVisitResult.CONTINUE; }
                st.files++;
                st.uncompressed += a.size();
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException e) {
                st.skipped++;
                return FileVisitResult.CONTINUE;
            }
        });
        return st;
    }

    /**
     * Archives everything under root (nothing is ignored unless listed in exclude).
     * Files that are unreadable, vanish or are symlinks are skipped and counted; a file that changes size while
     * it is read is padded/truncated so the archive stays valid.
     */
    public static Stats stream(Path root, List<String> exclude, int partBytes, long maxBytes, PartHandler handler) throws IOException {
        Stats st = new Stats();
        PartOut out = new PartOut(partBytes, maxBytes, handler);
        GZIPOutputStream gz = new GZIPOutputStream(out, 1 << 16) {
            {
                def.setLevel(Deflater.BEST_SPEED); // world data is already compressed; spare the server's CPU
            }
        };
        TarWriter tar = new TarWriter(gz);

        Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes a) throws IOException {
                if (dir.equals(root)) return FileVisitResult.CONTINUE;
                String r = rel(root, dir);
                if (excluded(r, exclude)) return FileVisitResult.SKIP_SUBTREE;
                tar.dir(r, mtime(a));
                st.dirs++;
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes a) throws IOException {
                String r = rel(root, file);
                if (excluded(r, exclude)) return FileVisitResult.CONTINUE;
                if (a.isSymbolicLink() || a.isOther()) { st.skipped++; return FileVisitResult.CONTINUE; }
                InputStream in;
                try {
                    in = Files.newInputStream(file);
                } catch (IOException e) {
                    st.skipped++;
                    return FileVisitResult.CONTINUE;
                }
                try (in) {
                    tar.file(r, in, a.size(), mtime(a));
                }
                st.files++;
                st.uncompressed += a.size();
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException e) {
                st.skipped++;
                return FileVisitResult.CONTINUE;
            }
        });

        tar.finish();
        gz.finish();
        gz.close(); // flushes the last (partial) part
        st.parts = out.parts;
        st.compressed = out.total;
        return st;
    }

    /* ---------------------------- part splitter ---------------------------- */

    private static final class PartOut extends OutputStream {
        private final byte[] buf;
        private final long max;
        private final PartHandler handler;
        private int len;
        int parts;
        long total;

        PartOut(int partBytes, long max, PartHandler handler) {
            this.buf = new byte[partBytes];
            this.max = max;
            this.handler = handler;
        }

        @Override
        public void write(int b) throws IOException {
            write(new byte[] {(byte) b}, 0, 1);
        }

        @Override
        public void write(byte[] b, int off, int l) throws IOException {
            while (l > 0) {
                int c = Math.min(l, buf.length - len);
                System.arraycopy(b, off, buf, len, c);
                len += c;
                off += c;
                l -= c;
                if (len == buf.length) flushPart();
            }
        }

        private void flushPart() throws IOException {
            if (len == 0) return;
            total += len;
            if (total > max) throw new IOException("the backup is bigger than the limit of " + (max / (1024 * 1024)) + " MB (compressed)");
            parts++;
            int n = len;
            len = 0;
            handler.part(parts, buf, n);
        }

        @Override
        public void close() throws IOException {
            flushPart();
        }
    }

    /* ------------------------------- tar writer ------------------------------- */

    /** Minimal ustar writer (GNU long-name records for paths that do not fit). Public so it can be tested. */
    public static final class TarWriter {
        private final OutputStream out;
        private final byte[] hdr = new byte[512];
        private final byte[] buf = new byte[1 << 16];

        public TarWriter(OutputStream out) {
            this.out = out;
        }

        public void dir(String rel, long mtimeSec) throws IOException {
            header(rel + "/", 0, mtimeSec, (byte) '5', 0755);
        }

        /** Writes exactly `size` bytes of content: a file that shrinks is zero-padded, one that grows is cut. */
        public void file(String rel, InputStream in, long size, long mtimeSec) throws IOException {
            header(rel, size, mtimeSec, (byte) '0', rel.endsWith(".sh") ? 0755 : 0644);
            long remaining = size;
            while (remaining > 0) {
                int r;
                try {
                    r = in.read(buf, 0, (int) Math.min(buf.length, remaining));
                } catch (IOException e) {
                    r = -1; // vanished / locked while reading
                }
                if (r < 0) break;
                out.write(buf, 0, r); // errors of the sink must propagate
                remaining -= r;
            }
            while (remaining > 0) {
                int z = (int) Math.min(buf.length, remaining);
                Arrays.fill(buf, 0, z, (byte) 0);
                out.write(buf, 0, z);
                remaining -= z;
            }
            int rem = (int) (size % 512);
            if (rem != 0) out.write(new byte[512 - rem]);
        }

        public void finish() throws IOException {
            out.write(new byte[1024]);
        }

        private void header(String name, long size, long mtime, byte type, int mode) throws IOException {
            byte[] nb = name.getBytes(StandardCharsets.UTF_8);
            String prefix = "";
            String shortName = name;
            if (nb.length > 100) {
                int split = ustarSplit(name);
                if (split > 0) {
                    prefix = name.substring(0, split);
                    shortName = name.substring(split + 1);
                } else {
                    longName(nb, mtime);
                    shortName = new String(nb, 0, 100, StandardCharsets.UTF_8);
                }
            }
            build(shortName, prefix, size, mtime, type, mode);
            out.write(hdr);
        }

        /** Index of a '/' that splits the path into prefix (<=155 bytes) and name (<=100 bytes), or -1. */
        private static int ustarSplit(String name) {
            for (int i = name.lastIndexOf('/'); i > 0; i = name.lastIndexOf('/', i - 1)) {
                if (name.substring(0, i).getBytes(StandardCharsets.UTF_8).length <= 155
                    && name.substring(i + 1).getBytes(StandardCharsets.UTF_8).length <= 100) {
                    return i;
                }
            }
            return -1;
        }

        private void longName(byte[] nameBytes, long mtime) throws IOException {
            build("././@LongLink", "", nameBytes.length + 1L, mtime, (byte) 'L', 0);
            out.write(hdr);
            out.write(nameBytes);
            out.write(0);
            int rem = (int) ((nameBytes.length + 1L) % 512);
            if (rem != 0) out.write(new byte[512 - rem]);
        }

        private void build(String name, String prefix, long size, long mtime, byte type, int mode) {
            Arrays.fill(hdr, (byte) 0);
            putStr(0, 100, name);
            putOct(100, 8, mode);
            putOct(108, 8, 0);
            putOct(116, 8, 0);
            if (size > 077777777777L) {
                hdr[124] = (byte) 0x80; // base-256 size for entries of 8 GiB and more
                for (int i = 0; i < 11; i++) hdr[135 - i] = (byte) (size >> (8 * i));
            } else {
                putOct(124, 12, size);
            }
            putOct(136, 12, mtime);
            Arrays.fill(hdr, 148, 156, (byte) ' ');
            hdr[156] = type;
            putStr(257, 6, "ustar");
            hdr[263] = '0';
            hdr[264] = '0';
            putOct(329, 8, 0);
            putOct(337, 8, 0);
            putStr(345, 155, prefix);
            long sum = 0;
            for (byte b : hdr) sum += b & 0xff;
            putOct(148, 7, sum);
            hdr[155] = ' ';
        }

        private void putStr(int off, int max, String s) {
            byte[] b = s.getBytes(StandardCharsets.UTF_8);
            System.arraycopy(b, 0, hdr, off, Math.min(b.length, max));
        }

        /** Octal, zero padded to len-1 digits, NUL terminated. */
        private void putOct(int off, int len, long v) {
            String o = Long.toOctalString(v);
            int digits = len - 1;
            for (int i = 0; i < digits; i++) {
                int idx = o.length() - 1 - i;
                hdr[off + digits - 1 - i] = (byte) (idx >= 0 ? o.charAt(idx) : '0');
            }
            hdr[off + digits] = 0;
        }
    }
}
