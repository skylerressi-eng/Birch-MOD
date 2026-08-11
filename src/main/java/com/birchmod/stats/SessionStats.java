package com.birchmod.stats;

import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import net.fabricmc.loader.api.FabricLoader;

/**
 * Session and lifetime statistics.
 *
 * Session values reset when the mod loads or on {@code /birch reset}; lifetime
 * values persist to {@code config/birchoptimizer-stats.json} so totals survive
 * restarts. Saving is throttled — stats change constantly and the disk should
 * not.
 */
public final class SessionStats {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String FILE_NAME = "birchoptimizer-stats.json";
    private static final long SAVE_INTERVAL_MS = 30_000L;

    /** Persisted lifetime totals. */
    public static final class Lifetime {
        public long birchCollected = 0L;
        public long treesChopped = 0L;
        public double coinsEarned = 0.0;
        public long playtimeMs = 0L;
        public double bestBirchPerHour = 0.0;

        /**
         * What birch regrowth has actually been measured at, carried between
         * sessions. Without this the timer relearns from a guess on every
         * login, and every countdown is an estimate until enough trees have
         * been watched all over again.
         */
        public double measuredRegenSeconds = -1.0;
        public long regenSamples = 0L;

        /**
         * How fast the player actually travels, in blocks per second. Every
         * arrival estimate and route score depends on this; assuming a constant
         * made all of them wrong for anyone with a Skyblock speed stat.
         */
        public double measuredWalkSpeed = -1.0;
        public long walkSamples = 0L;
    }

    private static Lifetime lifetime = new Lifetime();
    private static long lastSave = 0L;

    /**
     * Stats are written off the client thread. The periodic save fired from the
     * tracker every thirty seconds, and a synchronous disk write there is a
     * stutter in the middle of play for something nobody is waiting on.
     */
    private static final ExecutorService IO = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "BirchOptimizer-Stats");
        t.setDaemon(true);
        return t;
    });

    // ---- Session-only counters ----
    private static volatile long sessionBirch = 0L;
    private static volatile long sessionTrees = 0L;
    private static volatile double sessionCoins = 0.0;
    private static volatile double sessionBestRate = 0.0;
    private static volatile long sessionStart = System.currentTimeMillis();

    /** Wall-clock time credited only while actively gathering. */
    private static volatile long activeMs = 0L;
    private static volatile long lastActivityAt = 0L;

    /** A gap longer than this counts as idle, not gathering. */
    private static final long ACTIVITY_TIMEOUT_MS = 60_000L;

    /** Window for the live trees-per-minute figure. */
    private static final long RATE_WINDOW_MS = 5L * 60L * 1000L;

    /**
     * Shortest span the live rate is extrapolated from.
     *
     * Three trees felled in the first few seconds is not three hundred an hour.
     * Without a floor the figure spikes absurdly at the start of a run and then
     * collapses, which reads as a broken counter and makes the comparison
     * against a plan meaningless.
     */
    private static final long MIN_RATE_SPAN_MS = 60_000L;

    /** Timestamps of recent chops, for comparing real output against a plan. */
    private static final Deque<Long> recentChops = new ArrayDeque<>();

    private SessionStats() {
    }

    // ---- Recording ----

    public static void recordBirch(long amount, double unitPrice) {
        if (amount <= 0) {
            return;
        }
        sessionBirch += amount;
        lifetime.birchCollected += amount;

        if (unitPrice > 0.0) {
            double value = amount * unitPrice;
            sessionCoins += value;
            lifetime.coinsEarned += value;
        }
        markActive();
    }

    public static void recordTreeChopped() {
        sessionTrees++;
        lifetime.treesChopped++;
        long now = System.currentTimeMillis();
        synchronized (recentChops) {
            // Trim on write as well as on read. Trimming only in the getter
            // meant the window grew without bound whenever nothing asked for
            // the rate, which is most of the time.
            trimLocked(now);
            recentChops.addLast(now);
        }
        markActive();
    }

    /**
     * Fold a measured regrowth into the persisted calibration.
     *
     * Weighted toward recent observations, matching how the tracker averages
     * within a session, so a rate that shifts is followed rather than diluted
     * by everything ever seen.
     */
    public static void recordRegenMeasurement(double seconds) {
        if (seconds <= 0.0) {
            return;
        }
        double current = lifetime.measuredRegenSeconds;
        lifetime.measuredRegenSeconds = current <= 0.0 ? seconds : (current * 0.8) + (seconds * 0.2);
        lifetime.regenSamples++;
    }

    /** Drop chops that have aged out. Caller must hold the deque's lock. */
    private static void trimLocked(long now) {
        while (!recentChops.isEmpty() && now - recentChops.peekFirst() > RATE_WINDOW_MS) {
            recentChops.pollFirst();
        }
    }

    /**
     * Fold one travel-speed observation into the persisted average.
     *
     * Weighted gently, because individual samples are noisy — a corner taken
     * tight, a moment spent lining up a swing — while the underlying speed
     * changes only when gear does.
     */
    public static void recordWalkSample(double blocksPerSecond) {
        if (blocksPerSecond <= 0.0 || !Double.isFinite(blocksPerSecond)) {
            return;
        }
        double current = lifetime.measuredWalkSpeed;
        lifetime.measuredWalkSpeed = current <= 0.0
                ? blocksPerSecond
                : (current * 0.95) + (blocksPerSecond * 0.05);
        lifetime.walkSamples++;
    }

    public static double getMeasuredWalkSpeed() {
        return lifetime.measuredWalkSpeed;
    }

    public static long getWalkSamples() {
        return lifetime.walkSamples;
    }

    public static double getPersistedRegenSeconds() {
        return lifetime.measuredRegenSeconds;
    }

    public static long getRegenSamples() {
        return lifetime.regenSamples;
    }

    /**
     * Trees felled per minute over the last few minutes.
     *
     * This is the same unit the route planner predicts in, so the two can be
     * compared directly: a route promising nine trees a minute that delivers
     * six is telling you something the plan alone cannot.
     */
    public static double getRecentTreesPerMinute() {
        long now = System.currentTimeMillis();
        int count;
        long oldest;

        synchronized (recentChops) {
            trimLocked(now);
            count = recentChops.size();
            oldest = recentChops.isEmpty() ? now : recentChops.peekFirst();
        }

        if (count < 2) {
            return 0.0;
        }
        // Measure over the span actually observed, floored so a burst of chops
        // in the first seconds cannot read as a colossal rate.
        double spanMinutes = Math.max(now - oldest, MIN_RATE_SPAN_MS) / 60_000.0;
        return count / spanMinutes;
    }

    /**
     * Take back a chop that turned out not to be one.
     *
     * A chunk reloading reads as air and looks exactly like a felled tree. The
     * tracker rejects the regrow that follows, and this rejects the chop, so a
     * glitch cannot leave half a cycle counted.
     */
    public static void undoTreeChopped() {
        if (sessionTrees > 0) {
            sessionTrees--;
        }
        if (lifetime.treesChopped > 0) {
            lifetime.treesChopped--;
        }
        // The rate window has to forget it too, or a rejected chop still shows
        // up as output that never happened.
        synchronized (recentChops) {
            recentChops.pollLast();
        }
    }

    public static void recordRate(double birchPerHour) {
        if (birchPerHour > sessionBestRate) {
            sessionBestRate = birchPerHour;
        }
        if (birchPerHour > lifetime.bestBirchPerHour) {
            lifetime.bestBirchPerHour = birchPerHour;
        }
    }

    /** Credit elapsed time toward "active" only if gathering recently. */
    private static void markActive() {
        long now = System.currentTimeMillis();
        if (lastActivityAt > 0L) {
            long gap = now - lastActivityAt;
            if (gap <= ACTIVITY_TIMEOUT_MS) {
                activeMs += gap;
                lifetime.playtimeMs += gap;
            }
        }
        lastActivityAt = now;
    }

    // ---- Queries ----

    public static long getSessionBirch() {
        return sessionBirch;
    }

    public static long getSessionTrees() {
        return sessionTrees;
    }

    public static double getSessionCoins() {
        return sessionCoins;
    }

    public static double getSessionBestRate() {
        return sessionBestRate;
    }

    public static long getSessionElapsedMs() {
        return System.currentTimeMillis() - sessionStart;
    }

    public static long getActiveMs() {
        return activeMs;
    }

    public static Lifetime getLifetime() {
        return lifetime;
    }

    /** Average birch yielded per fully chopped tree. */
    public static double getBirchPerTree() {
        return sessionTrees > 0 ? (double) sessionBirch / (double) sessionTrees : 0.0;
    }

    // ---- Lifecycle ----

    public static void resetSession() {
        sessionBirch = 0L;
        sessionTrees = 0L;
        sessionCoins = 0.0;
        sessionBestRate = 0.0;
        sessionStart = System.currentTimeMillis();
        activeMs = 0L;
        lastActivityAt = 0L;
        synchronized (recentChops) {
            recentChops.clear();
        }
    }

    private static Path path() {
        return FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME);
    }

    public static void load() {
        try {
            Path file = path();
            if (Files.exists(file)) {
                try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                    Lifetime loaded = GSON.fromJson(reader, Lifetime.class);
                    if (loaded != null) {
                        lifetime = loaded;
                    }
                }
            }
        } catch (Exception e) {
            lifetime = new Lifetime();
        }
        resetSession();
    }

    /** Save at most once per {@link #SAVE_INTERVAL_MS}, off the client thread. */
    public static void saveThrottled() {
        long now = System.currentTimeMillis();
        if (now - lastSave < SAVE_INTERVAL_MS) {
            return;
        }
        lastSave = now;
        // Snapshot here, serialise there: the writer must not read fields the
        // client thread is still updating.
        Lifetime snapshot = snapshot();
        IO.execute(() -> write(snapshot));
    }

    /** Save immediately. Used on shutdown, where the write has to finish. */
    public static void save() {
        write(snapshot());
    }

    private static Lifetime snapshot() {
        Lifetime copy = new Lifetime();
        Lifetime source = lifetime;
        copy.birchCollected = source.birchCollected;
        copy.treesChopped = source.treesChopped;
        copy.coinsEarned = source.coinsEarned;
        copy.playtimeMs = source.playtimeMs;
        copy.bestBirchPerHour = source.bestBirchPerHour;
        copy.measuredRegenSeconds = source.measuredRegenSeconds;
        copy.regenSamples = source.regenSamples;
        copy.measuredWalkSpeed = source.measuredWalkSpeed;
        copy.walkSamples = source.walkSamples;
        return copy;
    }

    private static void write(Lifetime data) {
        try {
            Path file = path();
            Files.createDirectories(file.getParent());
            try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                GSON.toJson(data, writer);
            }
        } catch (Exception ignored) {
            // Stats are not worth crashing the client over.
        }
    }

    /** Format a duration as "2h 14m" / "14m 03s" / "43s". */
    public static String formatDuration(long ms) {
        long totalSeconds = ms / 1000L;
        long hours = totalSeconds / 3600L;
        long minutes = (totalSeconds % 3600L) / 60L;
        long seconds = totalSeconds % 60L;

        if (hours > 0) {
            return String.format("%dh %02dm", hours, minutes);
        }
        if (minutes > 0) {
            return String.format("%dm %02ds", minutes, seconds);
        }
        return seconds + "s";
    }
}
