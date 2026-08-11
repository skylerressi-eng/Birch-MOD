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
 */
public class BirchTracker {

    private static final long WINDOW_MS = 60L * 60L * 1000L; // 1 hour
    private static final int SAMPLE_INTERVAL_TICKS = 5;      // 4x per second

    private record Sample(long time, int amount) {
    }

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

    /** Birch-named items that are not raw birch logs. */
    private static final List<String> NON_LOG_BIRCH =
            List.of("enchanted", "plank", "sapling", "leaves", "slab", "stair",
                    "door", "fence", "button", "sign", "axe", "boat");

    /**
     * Samples inside the rolling window, and their running sum.
     *
     * Both are touched only on the client thread. The rate itself is published
     * separately, because the HUD asks for it while rendering: the getter used
     * to purge and walk this deque from the render thread while the client
     * thread was appending to it, which is a plain data race on a structure
     * with no synchronisation at all. Keeping the total as we go also makes the
     * rate O(1) instead of a walk of every sample in the last hour.
     */
    private final Deque<Sample> samples = new ArrayDeque<>();
    private long windowTotal = 0L;

    /** The published rate. Written on the client thread, read while rendering. */
    private volatile double birchPerHour = 0.0;

    private long sessionStart = System.currentTimeMillis();
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

        int current = countBirch(client.player);

        if (lastInventoryCount < 0) {
            lastInventoryCount = current;
            return;
        }

        int delta = current - lastInventoryCount;
        int previous = lastInventoryCount;
        lastInventoryCount = current;

        long now = System.currentTimeMillis();

        // An implausible jump means the server resent the inventory, not that
        // the player chopped 500 logs in a quarter second. The same is true of
        // a stack appearing out of nothing: Skyblock blanks the inventory for a
        // few ticks around a warp, and the refill that follows is the same
        // birch you were already carrying, not a haul.
        boolean resend = delta > MAX_PLAUSIBLE_DELTA
                || (previous == 0 && delta > REFILL_SUSPICION_THRESHOLD);

        if (delta > 0 && !resend) {
            samples.addLast(new Sample(now, delta));
            windowTotal += delta;
            totalCollected += delta;
        }

        // Recompute every sample, not only when birch arrives, so the rate
        // decays while you are walking rather than freezing at its last value.
        purgeOld(now);
        birchPerHour = computeRate(now);

        if (delta > 0 && !resend) {
            // Value the haul at the best tax-adjusted payout available.
            double unitPrice = BirchMod.bazaar != null ? BirchMod.bazaar.getBestNetPerLog() : -1.0;
            SessionStats.recordBirch(delta, unitPrice);
            SessionStats.recordRate(birchPerHour);
            SessionStats.saveThrottled();
        }
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

    private void purgeOld(long now) {
        while (!samples.isEmpty() && (now - samples.peekFirst().time()) > WINDOW_MS) {
            windowTotal -= samples.pollFirst().amount();
        }
    }

    private double computeRate(long now) {
        if (windowTotal <= 0L) {
            return 0.0;
        }
        // Clamp the low end: extrapolating a few seconds of chopping to a full
        // hour produces a wild figure that settles seconds later, which reads
        // as a broken counter rather than a fast start.
        long span = Math.min(now - sessionStart, WINDOW_MS);
        span = Math.max(span, MIN_RATE_SPAN_MS);
        return (double) windowTotal / (double) span * (double) WINDOW_MS;
    }

    /** @return birch collected per hour over the rolling window. */
    public double getBirchPerHour() {
        return birchPerHour;
    }

    public long getTotalCollected() {
        return totalCollected;
    }

    public void reset() {
        samples.clear();
        windowTotal = 0L;
        birchPerHour = 0.0;
        totalCollected = 0L;
        sessionStart = System.currentTimeMillis();
        lastInventoryCount = -1;
    }
}
