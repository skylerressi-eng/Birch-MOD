package com.birchmod.tracking;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;

import com.birchmod.BirchMod;
import com.birchmod.stats.SessionStats;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;

/**
 * Tracks birch collected per hour.
 *
 * Detection works by sampling the player's inventory a few times a second and
 * recording increases in the birch count. This is deliberate: Fabric's block
 * break / pickup events are server-authoritative, so on a remote server like
 * Hypixel they never fire client-side. Inventory deltas always work.
 *
 * Only increases are counted; selling, dropping or stashing birch decreases the
 * count and is ignored, so the rate reflects gathering only.
 *
 * The rate is birch per hour of foraging over the last hour — see
 * {@link RateWindow} for why time is measured rather than assumed.
 */
public class BirchTracker {

    private static final long WINDOW_MS = 60L * 60L * 1000L; // 1 hour
    private static final int SAMPLE_INTERVAL_TICKS = 5;      // 4x per second

    /**
     * A single sample can never plausibly add more than this. Skyblock warps
     * between servers constantly, and during a warp the inventory reads empty
     * for a few ticks before it is re-sent. Without this guard the refill is
     * counted as one enormous haul.
     */
    private static final int MAX_PLAUSIBLE_DELTA = 512;

    /** Shortest window the rate is extrapolated from, to tame early spikes. */
    private static final long MIN_RATE_SPAN_MS = 60_000L;

    /**
     * Birch appearing in an inventory that read as empty a quarter second ago
     * is the server re-sending what you were already carrying, not a haul.
     */
    private static final int REFILL_SUSPICION_THRESHOLD = 64;


    /** Birch-named items that are not birch you chopped. */
    private static final List<String> NON_LOG_BIRCH =
            List.of("plank", "sapling", "leaves", "slab", "stair",
                    "door", "fence", "button", "sign", "axe", "boat");

    /**
     * Raw birch inside one Enchanted Birch Wood.
     *
     * Skyblock's standard enchanted-item ratio, and the reason this matters at
     * all: with a compactor running, most of what you chop spends most of its
     * life in this form.
     */
    static final int ENCHANTED_LOGS = 160;

    /**
     * The rolling window the rate is measured over.
     *
     * Touched only on the client thread. The rate itself is published
     * separately, because the HUD asks for it while rendering: the getter used
     * to walk this structure from the render thread while the client thread was
     * appending to it, which is a plain data race on a collection with no
     * synchronisation at all.
     */
    private final RateWindow window = new RateWindow();

    /** The published rate. Written on the client thread, read while rendering. */
    private volatile double birchPerHour = 0.0;

    private long totalCollected = 0L;

    private int lastInventoryCount = -1;
    private int tickCounter = 0;
    private Level lastLevel = null;

    public void tick(Minecraft client) {
        if (client == null || client.player == null) {
            // Left the world: force a re-baseline on the next join so the
            // whole inventory is not counted as a single huge gain.
            invalidateBaseline(null);
            return;
        }

        // Changing island/server re-sends the inventory; re-baseline instead of
        // treating the resend as a pickup.
        Level level = client.player.level();
        if (level != lastLevel) {
            invalidateBaseline(level);
            return;
        }

        if (++tickCounter < SAMPLE_INTERVAL_TICKS) {
            return;
        }
        tickCounter = 0;

        // What a resend really looks like: not birch going missing, but every
        // slot going missing at once. Nothing you can do while playing empties
        // your armour and your axe as well, so this is safe to read as the
        // server having not sent the inventory yet — and re-baselining means
        // the refill is a new starting point rather than a haul.
        if (countOccupied(client.player) == 0) {
            invalidateBaseline(level);
            return;
        }

        int current = countBirch(client.player);

        if (lastInventoryCount < 0) {
            lastInventoryCount = current;
            return;
        }

        int previous = lastInventoryCount;
        lastInventoryCount = current;

        long now = System.currentTimeMillis();
        int gained = gainFrom(previous, current);

        totalCollected += gained;

        // Fold in every sample, not only the ones that brought birch: the time
        // you spend walking between trees is time spent foraging, and the rate
        // is only honest if the walk is in the denominator.
        window.sample(now, gained);
        birchPerHour = window.perHour(now);

        if (gained > 0) {
            // Value the haul at the best tax-adjusted payout available.
            double unitPrice = BirchMod.bazaar != null ? BirchMod.bazaar.getBestNetPerLog() : -1.0;
            SessionStats.recordBirch(gained, unitPrice);
            SessionStats.recordRate(birchPerHour);
            SessionStats.saveThrottled();
        }
    }

    /**
     * How much of a rise in the birch count is really birch you just cut.
     *
     * Deliberately almost nothing. An earlier version of this tried to be
     * clever: it remembered birch that had vanished and made the next rise pay
     * it back before counting, on the theory that birch reappearing just after
     * the same amount left is the same birch. That is true of a warp and false
     * of everything else that removes birch — compacting, a sack, dropping a
     * stack, selling — and each of those armed a debt that then ate the next
     * real haul. A guard that silently subtracts what you actually chopped is
     * worse than the problem it was added for, so the warp case is now handled
     * where it can be recognised for certain: {@link #tick} re-baselines when
     * the whole inventory blanks, which is what a resend actually looks like.
     */
    static int gainFrom(int previous, int current) {
        int delta = current - previous;
        if (delta <= 0) {
            return 0;
        }
        // A jump nothing explains: too big to have been chopped in a quarter
        // second, or a stack appearing in an inventory that read as empty.
        if (delta > MAX_PLAUSIBLE_DELTA
                || (previous == 0 && delta > REFILL_SUSPICION_THRESHOLD)) {
            return 0;
        }
        return delta;
    }

    private int countBirch(LocalPlayer player) {
        Inventory inventory = player.getInventory();
        int total = 0;
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (!stack.isEmpty()) {
                total += birchValue(hoverName(stack), stack.getCount(),
                        stack.is(Items.BIRCH_LOG) || stack.is(Items.BIRCH_WOOD));
            }
        }
        return total;
    }

    /** How many slots hold anything at all. Zero means the inventory blanked. */
    private int countOccupied(LocalPlayer player) {
        Inventory inventory = player.getInventory();
        int occupied = 0;
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            if (!inventory.getItem(i).isEmpty()) {
                occupied++;
            }
        }
        return occupied;
    }

    /** Re-baseline on the next sample, optionally adopting a new level. */
    private void invalidateBaseline(Level level) {
        lastInventoryCount = -1;
        lastLevel = level;
        tickCounter = 0;
    }

    /** The stack's display name in lower case, or null if it has none. */
    private static String hoverName(ItemStack stack) {
        try {
            String name = stack.getHoverName().getString();
            return name == null ? null : name.toLowerCase(Locale.ROOT);
        } catch (Exception ignored) {
            return null;
        }
    }

    /**
     * What a stack is worth in raw birch logs.
     *
     * The name is consulted before the item id, which is the whole point.
     * Skyblock reskins vanilla items, so Enchanted Birch Wood <em>is</em> a
     * birch log as far as the game is concerned — and asking the id first
     * answered "yes, birch" and returned before the name was ever examined.
     * The exclusion list below it was unreachable for every reskinned item it
     * existed to catch, so one Enchanted Birch Wood counted as a single log
     * instead of the {@value #ENCHANTED_LOGS} it holds.
     *
     * Valuing it properly also makes compacting invisible, which is what it
     * should always have been: 160 logs become one enchanted worth 160, the
     * count does not move, and nothing has to be explained away afterwards.
     *
     * @param name     lower-case display name, or null
     * @param count    stack size
     * @param vanilla  whether the underlying item is a vanilla birch log
     */
    static int birchValue(String name, int count, boolean vanilla) {
        if (name != null && !name.isEmpty()) {
            for (String excluded : NON_LOG_BIRCH) {
                if (name.contains(excluded)) {
                    return 0;
                }
            }
            if (!name.contains("birch")) {
                // A reskin that is not birch at all, whatever it is made of.
                return 0;
            }
            return name.contains("enchanted") ? count * ENCHANTED_LOGS : count;
        }
        return vanilla ? count : 0;
    }

    /** @return birch collected per hour of foraging, over the last hour. */
    public double getBirchPerHour() {
        return birchPerHour;
    }

    /**
     * What the counter can currently see, in plain words.
     *
     * The rate is one number derived from several things that can each be
     * wrong on their own — which stacks are recognised as birch, what they are
     * valued at, how much time has been counted as foraging. When the answer
     * looks wrong there is no way to tell which of those it is by staring at
     * the number, so this prints the working. Read on demand by a command, on
     * the client thread.
     */
    public List<String> explain(Minecraft client) {
        List<String> out = new ArrayList<>();
        if (client == null || client.player == null) {
            out.add("No player: nothing is being counted.");
            return out;
        }

        Inventory inventory = client.player.getInventory();
        int seen = 0;
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (stack.isEmpty()) {
                continue;
            }
            String name = hoverName(stack);
            int value = birchValue(name, stack.getCount(),
                    stack.is(Items.BIRCH_LOG) || stack.is(Items.BIRCH_WOOD));
            if (value <= 0) {
                continue;
            }
            seen++;
            out.add("  " + (name == null ? "(unnamed birch log)" : name)
                    + " x" + stack.getCount() + " = " + value + " logs");
        }
        if (seen == 0) {
            out.add("  nothing in your inventory is being counted as birch");
        }

        out.add("Inventory total: " + lastInventoryCount + " logs");
        out.add("In the last hour: " + window.collected() + " logs over "
                + (window.activeMs() / 1000L) + "s of foraging");
        out.add("Rate: " + Math.round(birchPerHour) + "/hr");
        return out;
    }

    public long getTotalCollected() {
        return totalCollected;
    }

    public void reset() {
        window.reset();
        birchPerHour = 0.0;
        totalCollected = 0L;
        lastInventoryCount = -1;
    }

    /**
     * Birch collected per hour <em>of foraging</em>, over the last hour.
     *
     * The rate used to divide an hour's worth of birch by the age of the
     * session, capped at an hour. Two things were wrong with that. The same
     * half hour of identical chopping read 10,000/hr if you had just logged in
     * and 5,000/hr if you had been on for three, because the divisor was how
     * long ago you logged in rather than anything you did. And every minute
     * spent at the Bazaar, in a menu, or stood reading chat was divided into
     * your birch as though you had been swinging through it, so the longer a
     * session ran the further the figure drifted below what you were actually
     * pulling.
     *
     * So time is counted, not assumed. Both halves of the fraction are kept in
     * buckets and both expire together, which is what makes the answer a rate
     * over the last hour rather than an average since login. Walking between
     * trees counts — it is part of the loop, and a rate that ignored it would
     * flatter you. Standing still for longer than {@link #IDLE_GRACE_MS} does
     * not, so stepping away holds the number where it was rather than eroding
     * it; it falls, as it should, once the birch itself ages out of the window.
     */
    static final class RateWindow {

        /**
         * Granularity of expiry. Five seconds gives 720 buckets an hour, which
         * is nothing to hold, and no visible step as each one falls off.
         */
        private static final long BUCKET_MS = 5_000L;

        /**
         * How long after your last log you are still considered to be foraging.
         *
         * Long enough to cross a Park grove between trees, short enough that
         * going to the Bazaar stops the clock.
         */
        private static final long IDLE_GRACE_MS = 30_000L;

        /**
         * The most time one sample may contribute.
         *
         * Samples land four times a second. A gap far longer than that is the
         * client lagging, the game paused or the connection stalling, and none
         * of it is chopping — counting it would sink the rate for a stutter.
         */
        private static final long MAX_SAMPLE_GAP_MS = 2_000L;

        private static final class Bucket {
            final long start;
            long collected;
            long activeMs;

            Bucket(long start) {
                this.start = start;
            }
        }

        private final Deque<Bucket> buckets = new ArrayDeque<>();
        private long collectedTotal = 0L;
        private long activeTotal = 0L;
        private long lastSample = 0L;
        private long lastCollection = 0L;

        /**
         * Time since the last log, not yet claimed as foraging.
         *
         * Held back rather than counted as it passes, because at the moment it
         * passes there is no way to tell a walk to the next tree from the start
         * of a break — they look identical until either a log arrives or the
         * grace runs out. Counting it eagerly meant putting down the axe
         * donated the whole grace period to the divisor, so a ten-minute
         * session lost five percent of its rate for stopping.
         */
        private long pending = 0L;

        /** Fold one inventory sample into the window. */
        void sample(long now, int collected) {
            long gap = lastSample == 0L ? 0L : now - lastSample;
            lastSample = now;

            if (gap > 0L && gap <= MAX_SAMPLE_GAP_MS) {
                pending += gap;
            }

            if (collected <= 0) {
                // Nothing came of this time yet. If nothing has for longer than
                // the grace, nothing is going to: that was a break, not a walk.
                long since = lastCollection > 0L ? now - lastCollection : Long.MAX_VALUE;
                if (since > IDLE_GRACE_MS) {
                    pending = 0L;
                }
                purge(now);
                return;
            }

            // A log confirms the wait was part of the work. Credit the time to
            // the same bucket as the birch it produced, so the two halves of
            // the fraction always expire together.
            lastCollection = now;
            Bucket bucket = currentBucket(now);
            bucket.collected += collected;
            bucket.activeMs += pending;
            collectedTotal += collected;
            activeTotal += pending;
            pending = 0L;
            purge(now);
        }

        double perHour(long now) {
            purge(now);
            if (collectedTotal <= 0L) {
                return 0.0;
            }
            // Clamp the low end: extrapolating a few seconds of chopping to a
            // full hour produces a wild figure that settles seconds later,
            // which reads as a broken counter rather than a fast start.
            long span = Math.max(activeTotal, MIN_RATE_SPAN_MS);
            return (double) collectedTotal / (double) span * (double) WINDOW_MS;
        }

        private Bucket currentBucket(long now) {
            Bucket last = buckets.peekLast();
            if (last != null && now - last.start < BUCKET_MS) {
                return last;
            }
            Bucket fresh = new Bucket(now);
            buckets.addLast(fresh);
            return fresh;
        }

        private void purge(long now) {
            while (!buckets.isEmpty() && now - buckets.peekFirst().start > WINDOW_MS) {
                Bucket old = buckets.pollFirst();
                collectedTotal -= old.collected;
                activeTotal -= old.activeMs;
            }
        }

        void reset() {
            buckets.clear();
            collectedTotal = 0L;
            activeTotal = 0L;
            lastSample = 0L;
            lastCollection = 0L;
            pending = 0L;
        }

        long collected() {
            return collectedTotal;
        }

        long activeMs() {
            return activeTotal;
        }
    }
}
