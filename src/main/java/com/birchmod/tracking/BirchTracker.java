package com.birchmod.tracking;

import java.util.ArrayDeque;
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

    /**
     * How long birch that vanished stays remembered as owed back.
     *
     * The empty-inventory guard above only catches a resend that blanked the
     * whole inventory. A warp often leaves a few logs behind, so the count goes
     * 403 to 3 and back to 403, and a 400-log "haul" that was never chopped
     * goes into the rate. What gives it away is not the size of the jump but
     * that the same birch left a moment earlier: a rise is only new birch once
     * it has paid back what just disappeared. Kept short, because selling a
     * stack is also a disappearance, and nobody sells and re-chops four hundred
     * logs inside five seconds.
     */
    private static final long RESEND_WINDOW_MS = 5_000L;

    /** Birch-named items that are not raw birch logs. */
    private static final List<String> NON_LOG_BIRCH =
            List.of("enchanted", "plank", "sapling", "leaves", "slab", "stair",
                    "door", "fence", "button", "sign", "axe", "boat");

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

    /** Birch that left the inventory recently, and when it went. */
    private long pendingLoss = 0L;
    private long lossAt = 0L;

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

        int current = countBirch(client.player);

        if (lastInventoryCount < 0) {
            lastInventoryCount = current;
            return;
        }

        int previous = lastInventoryCount;
        lastInventoryCount = current;

        long now = System.currentTimeMillis();
        int gained = gainFrom(previous, current, now);

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
     * How much of a rise in the inventory count is really birch you just cut.
     *
     * Three things can push the number up, and only one of them is chopping.
     * The server resending the inventory after a warp is the common one, and it
     * is recognised by what preceded it rather than by its size — birch that
     * reappears just after the same amount vanished is the same birch.
     */
    int gainFrom(int previous, int current, long now) {
        // Expire an old loss before recording a new one, or every loss would be
        // stale the moment it happened.
        if (pendingLoss > 0L && now - lossAt > RESEND_WINDOW_MS) {
            pendingLoss = 0L;
        }

        int delta = current - previous;
        if (delta <= 0) {
            if (delta < 0) {
                pendingLoss += -delta;
                lossAt = now;
            }
            return 0;
        }

        // Pay back what just disappeared before calling any of it a haul. Only
        // the surplus is new birch, so chopping through a resend still counts.
        if (pendingLoss > 0L) {
            long repaid = Math.min(delta, pendingLoss);
            pendingLoss -= repaid;
            delta -= (int) repaid;
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
            if (!stack.isEmpty() && isBirch(stack)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    /** Re-baseline on the next sample, optionally adopting a new level. */
    private void invalidateBaseline(Level level) {
        lastInventoryCount = -1;
        lastLevel = level;
        tickCounter = 0;
    }

    private boolean isBirch(ItemStack stack) {
        if (stack.is(Items.BIRCH_LOG) || stack.is(Items.BIRCH_WOOD)) {
            return true;
        }
        // Skyblock reskins vanilla items, so fall back to the display name.
        try {
            String name = stack.getHoverName().getString();
            if (name == null) {
                return false;
            }
            String clean = name.toLowerCase(Locale.ROOT);
            if (!clean.contains("birch")) {
                return false;
            }
            // "Enchanted Birch Wood" is 160 logs and "Birch Planks"/saplings are
            // not logs at all — counting any of them as one log skews the rate.
            for (String excluded : NON_LOG_BIRCH) {
                if (clean.contains(excluded)) {
                    return false;
                }
            }
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    /** @return birch collected per hour of foraging, over the last hour. */
    public double getBirchPerHour() {
        return birchPerHour;
    }

    public long getTotalCollected() {
        return totalCollected;
    }

    public void reset() {
        window.reset();
        birchPerHour = 0.0;
        totalCollected = 0L;
        lastInventoryCount = -1;
        pendingLoss = 0L;
        lossAt = 0L;
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
