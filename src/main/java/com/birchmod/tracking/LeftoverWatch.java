package com.birchmod.tracking;

import java.util.HashSet;
import java.util.Set;

import com.birchmod.config.BirchConfig;
import com.birchmod.util.Notifier;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/**
 * Says something when you walk away from a trunk you did not finish.
 *
 * Leftover logs are already outlined in red, but an outline only helps if you
 * happen to look back at it, and by the time you have noticed you are usually
 * three trees further on. Half-chopped trunks are the cheapest birch on the
 * island — the walk is paid for and a swing or two finishes them — so leaving
 * one is worth a single line on the action bar.
 *
 * <h2>Not being annoying about it</h2>
 * Each trunk is mentioned once. It is only mentioned once you have genuinely
 * left it, not while you are standing at it lining up a swing, and not at all
 * while it is still close enough that you were probably coming back. A trunk
 * you then finish is forgotten, so finishing it and chopping it again later
 * does not earn a second telling-off.
 */
public final class LeftoverWatch {

    private static final int CHECK_INTERVAL_TICKS = 10; // twice a second

    /** Past this from an unfinished trunk, you have left it. */
    private static final double LEFT_IT_DISTANCE = 14.0;

    /** Beyond this it is out of the working area and no longer worth a nudge. */
    private static final double OUT_OF_RANGE_DISTANCE = 56.0;

    /** Never say anything twice inside this. */
    private static final long QUIET_MS = 6_000L;

    private final TreeRegenTracker tracker;

    /** Trunks already mentioned, so each is mentioned once. */
    private final Set<BlockPos> mentioned = new HashSet<>();

    private int tickCounter = 0;
    private long lastNudgeAt = 0L;

    public LeftoverWatch(TreeRegenTracker tracker) {
        this.tracker = tracker;
    }

    public void tick(Minecraft client) {
        if (!BirchConfig.get().notifyLeftovers) {
            return;
        }
        if (client == null || client.player == null || client.level == null) {
            mentioned.clear();
            return;
        }
        if (++tickCounter < CHECK_INTERVAL_TICKS) {
            return;
        }
        tickCounter = 0;

        Vec3 player = client.player.position();
        TreeRegenTracker.Tree nearest = null;
        double nearestDistance = Double.MAX_VALUE;
        int stillUnfinished = 0;

        Set<BlockPos> live = new HashSet<>();

        for (TreeRegenTracker.Tree tree : tracker.getAllTrees()) {
            if (!tree.isPartiallyChopped() || !tree.hasWood()) {
                continue;
            }
            live.add(tree.base);

            double distance = player.distanceTo(Vec3.atCenterOf(tree.base));
            if (distance < LEFT_IT_DISTANCE || distance > OUT_OF_RANGE_DISTANCE) {
                continue;
            }
            stillUnfinished++;

            if (!mentioned.contains(tree.base) && distance < nearestDistance) {
                nearestDistance = distance;
                nearest = tree;
            }
        }

        // A trunk that has been finished, regrown or forgotten is off the hook.
        mentioned.retainAll(live);

        if (nearest == null) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastNudgeAt < QUIET_MS) {
            return;   // it will still be there in six seconds
        }
        lastNudgeAt = now;
        mentioned.add(nearest.base);

        String others = stillUnfinished > 1
                ? " §8(+" + (stillUnfinished - 1) + " more)"
                : "";
        Notifier.actionBar("§c" + nearest.getWoodCount() + " log(s) left behind §7"
                + Math.round(nearestDistance) + "m back" + others);
    }

    public void reset() {
        mentioned.clear();
        lastNudgeAt = 0L;
    }
}
