package dev.smpbridge.common;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;

/**
 * Timing analysis for "AutoTotem" hacks: how long after a totem pop does the player put the next totem in the
 * off hand? The server sees (time) = ping + reaction. Removing the player's ping leaves the reaction, which for a
 * human is slow and varies a lot, and for a hack is fast and almost constant.
 *
 *  A  impossible reaction     reaction below what any human can do
 *  B  robotic consistency     the last N reactions are nearly identical (tiny spread)
 *  C  rapid chain             several re-totems in a row, all faster than a human could repeat
 */
public final class TotemAnalyzer {

    public static final class Config {
        public double minReactionMs = 60;
        public double chainReactionMs = 100;
        public int chainCount = 3;
        public int samples = 8;
        public double maxStdDevMs = 12;
        public double maxRangeMs = 40;
        public double maxMeanMs = 600;
        public long windowMs = 600_000;
        public long chainWindowMs = 12_000;
    }

    /** totalMs = pop -> re-totem as the server saw it, pingMs = the player's ping (smallest recent reading). */
    public record Sample(long atMs, double totalMs, double pingMs) {
        public double reactionMs() {
            return totalMs - pingMs;
        }
    }

    public record Flag(String check, String reason, List<String> details) {}

    private final Deque<Sample> samples = new ArrayDeque<>();

    public synchronized List<Sample> recent() {
        return new ArrayList<>(samples);
    }

    public static double mean(List<Sample> l) {
        double s = 0;
        for (Sample x : l) s += x.reactionMs();
        return l.isEmpty() ? 0 : s / l.size();
    }

    /** sample standard deviation (n-1) of the reaction times */
    public static double stdDev(List<Sample> l) {
        if (l.size() < 2) return 0;
        double m = mean(l);
        double s = 0;
        for (Sample x : l) s += (x.reactionMs() - m) * (x.reactionMs() - m);
        return Math.sqrt(s / (l.size() - 1));
    }

    public static double range(List<Sample> l) {
        double lo = Double.MAX_VALUE;
        double hi = -Double.MAX_VALUE;
        for (Sample x : l) {
            lo = Math.min(lo, x.reactionMs());
            hi = Math.max(hi, x.reactionMs());
        }
        return l.isEmpty() ? 0 : hi - lo;
    }

    private static String f(String fmt, Object... a) {
        return String.format(Locale.ROOT, fmt, a);
    }

    /** Adds a sample and returns the checks it raises (possibly none). */
    public synchronized List<Flag> add(Sample s, double tps, Config c) {
        samples.addLast(s);
        while (!samples.isEmpty() && s.atMs() - samples.peekFirst().atMs() > c.windowMs) samples.removeFirst();
        while (samples.size() > 20) samples.removeFirst();

        List<Flag> flags = new ArrayList<>();
        double reaction = s.reactionMs();
        String head = f("Switch: %.0f ms (total %.0f ms - ping %.0f ms)", reaction, s.totalMs(), s.pingMs());
        String tpsLine = f("TPS: %.2f", tps);

        if (reaction < c.minReactionMs) {
            flags.add(new Flag("A", "impossible reaction",
                List.of(head, f("Human minimum is about %.0f ms", c.minReactionMs), tpsLine)));
        } else if (reaction < c.chainReactionMs) {
            long fast = samples.stream()
                .filter(x -> s.atMs() - x.atMs() <= c.chainWindowMs && x.reactionMs() < c.chainReactionMs)
                .count();
            if (fast >= c.chainCount) {
                flags.add(new Flag("C", "rapid chain",
                    List.of(head, f("%d re-totems under %.0f ms within %d s", fast, c.chainReactionMs, c.chainWindowMs / 1000), tpsLine)));
            }
        }

        if (samples.size() >= c.samples) {
            List<Sample> all = new ArrayList<>(samples);
            List<Sample> last = all.subList(all.size() - c.samples, all.size());
            double mean = mean(last);
            double sd = stdDev(last);
            double rg = range(last);
            if (sd < c.maxStdDevMs && rg < c.maxRangeMs && mean < c.maxMeanMs) {
                flags.add(new Flag("B", "robotic consistency", List.of(
                    head,
                    f("Mean: %.1f ms | StdDev: %.1f ms (n=%d)", mean, sd, last.size()),
                    f("Spread: %.0f ms (a human varies far more)", rg),
                    tpsLine)));
            }
        }
        return flags;
    }
}
