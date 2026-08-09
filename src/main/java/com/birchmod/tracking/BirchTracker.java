package com.birchmod.tracking;

import java.util.ArrayDeque;
import java.util.Deque;

import com.birchmod.BirchMod;
import com.birchmod.stats.SessionStats;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

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

    private final Deque<Sample> samples = new ArrayDeque<>();
    private long sessionStart = System.currentTimeMillis();
    private long totalCollected = 0L;

    private int lastInventoryCount = -1;
    private int tickCounter = 0;

    public void tick(Minecraft client) {
        if (client == null || client.player == null) {
            // Left the world: force a re-baseline on the next join so the
            // whole inventory is not counted as a single huge gain.
            lastInventoryCount = -1;
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
        lastInventoryCount = current;

        if (delta > 0) {
            long now = System.currentTimeMillis();
            samples.addLast(new Sample(now, delta));
            totalCollected += delta;
            purgeOld(now);

            // Value the haul at the best tax-adjusted payout available.
            double unitPrice = BirchMod.bazaar != null ? BirchMod.bazaar.getBestNetPerLog() : -1.0;
            SessionStats.recordBirch(delta, unitPrice);
            SessionStats.recordRate(getBirchPerHour());
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

    private boolean isBirch(ItemStack stack) {
        if (stack.is(Items.BIRCH_LOG) || stack.is(Items.BIRCH_WOOD)) {
            return true;
        }
        // Skyblock reskins vanilla items, so fall back to the display name.
        try {
            String name = stack.getHoverName().getString();
            return name != null && name.toLowerCase().contains("birch");
        } catch (Exception ignored) {
            return false;
        }
    }

    private void purgeOld(long now) {
        while (!samples.isEmpty() && (now - samples.peekFirst().time()) > WINDOW_MS) {
            samples.pollFirst();
        }
    }

    /** @return birch collected per hour over the rolling window. */
    public double getBirchPerHour() {
        long now = System.currentTimeMillis();
        purgeOld(now);

        long total = 0L;
        for (Sample s : samples) {
            total += s.amount();
        }
        if (total == 0L) {
            return 0.0;
        }

        long span = Math.min(now - sessionStart, WINDOW_MS);
        if (span <= 0L) {
            return 0.0;
        }
        return (double) total / (double) span * (double) WINDOW_MS;
    }

    public long getTotalCollected() {
        return totalCollected;
    }

    public void reset() {
        samples.clear();
        totalCollected = 0L;
        sessionStart = System.currentTimeMillis();
        lastInventoryCount = -1;
    }
}
