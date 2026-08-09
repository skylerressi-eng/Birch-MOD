package com.birchmod.tracking;

import java.util.ArrayDeque;
import java.util.Deque;

import net.minecraft.client.Minecraft;
import net.minecraft.init.Blocks;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraftforge.event.entity.player.EntityItemPickupEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

/**
 * Tracks how many birch logs the player picks up and derives a birch/hour rate.
 *
 * Detection: we watch item-pickup events for Birch Wood (minecraft:log meta 2,
 * which is what Skyblock's "Birch Wood" item is), plus any item whose display
 * name contains "Birch".
 *
 * Rate: a rolling 1-hour window. Before an hour of play has elapsed the rate is
 * extrapolated from the time played this session.
 */
public class BirchTracker {

    private static final long WINDOW_MS = 60L * 60L * 1000L; // 1 hour

    private static final class Pickup {
        final long time;
        final int amount;

        Pickup(long time, int amount) {
            this.time = time;
            this.amount = amount;
        }
    }

    private final Deque<Pickup> pickups = new ArrayDeque<Pickup>();
    private long sessionStart = System.currentTimeMillis();
    private long totalCollected = 0L;

    @SubscribeEvent
    public void onItemPickup(EntityItemPickupEvent event) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.thePlayer == null) {
            return;
        }
        // Only count the local player's pickups.
        if (event.entityPlayer != mc.thePlayer) {
            return;
        }
        if (event.item == null) {
            return;
        }

        ItemStack stack = event.item.getEntityItem();
        if (stack == null || !isBirch(stack)) {
            return;
        }

        int amount = stack.stackSize;
        long now = System.currentTimeMillis();
        pickups.addLast(new Pickup(now, amount));
        totalCollected += amount;
        purgeOld(now);
    }

    private boolean isBirch(ItemStack stack) {
        Item birchLog = Item.getItemFromBlock(Blocks.log);
        if (stack.getItem() == birchLog && stack.getMetadata() == 2) {
            return true;
        }
        // Fallback: match by (stripped) display name for custom Skyblock items.
        try {
            String name = stack.getDisplayName();
            if (name != null) {
                String clean = name.replaceAll("(?i)\\u00a7[0-9A-FK-OR]", "").toLowerCase();
                return clean.contains("birch");
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    private void purgeOld(long now) {
        while (!pickups.isEmpty() && (now - pickups.peekFirst().time) > WINDOW_MS) {
            pickups.pollFirst();
        }
    }

    /**
     * @return birch collected per hour, based on the rolling window.
     */
    public double getBirchPerHour() {
        long now = System.currentTimeMillis();
        purgeOld(now);

        long total = 0L;
        for (Pickup p : pickups) {
            total += p.amount;
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

    /** Reset the session counters. */
    public void reset() {
        pickups.clear();
        totalCollected = 0L;
        sessionStart = System.currentTimeMillis();
    }
}
