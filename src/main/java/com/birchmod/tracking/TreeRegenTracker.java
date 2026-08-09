package com.birchmod.tracking;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import com.birchmod.config.BirchConfig;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

/**
 * Tracks individual birch trees and times how long each takes to regenerate.
 *
 * A tree is keyed by its base log (the lowest log of the trunk). The timer only
 * starts once the tree is <em>fully</em> downed — every log in its trunk volume
 * is gone — not when the first log is broken.
 *
 * The regen duration is measured rather than assumed: when logs reappear at a
 * downed tree, the elapsed time is folded into a running average, so the
 * countdown calibrates itself to Hypixel's real rate.
 */
public class TreeRegenTracker {

    private static final int MAX_TREES = 32;
    private static final int SCAN_INTERVAL_TICKS = 5; // 4x per second

    /** Trunk search volume around the base log. */
    private static final int TRUNK_RADIUS = 2;
    private static final int TRUNK_HEIGHT = 12;

    /** Walking down from a hit log to find the base cannot exceed this. */
    private static final int MAX_BASE_WALK = 24;

    /** Ignore implausible measurements (block replaced by something else). */
    private static final double MAX_PLAUSIBLE_REGEN_SECONDS = 900.0;

    /** Stop tracking trees further away than this (squared). */
    private static final double FORGET_DISTANCE_SQ = 96.0 * 96.0;

    /** One tracked tree. */
    public static final class Tree {
        public final BlockPos base;
        int logCount;
        boolean downed;
        long downedAt;

        Tree(BlockPos base, int logCount) {
            this.base = base;
            this.logCount = logCount;
        }

        public boolean isDowned() {
            return downed;
        }

        public long getDownedAt() {
            return downedAt;
        }
    }

    private final Map<BlockPos, Tree> trees = new HashMap<>();

    private double averageRegenSeconds = -1.0;
    private int measurementCount = 0;
    private double lastMeasurementSeconds = -1.0;

    private int tickCounter = 0;

    public void tick(Minecraft client) {
        if (!BirchConfig.get().regenTimerEnabled) {
            return;
        }
        if (client == null || client.player == null || client.level == null) {
            trees.clear();
            return;
        }

        if (++tickCounter < SCAN_INTERVAL_TICKS) {
            return;
        }
        tickCounter = 0;

        discoverLookedAtTree(client);
        updateTrees(client);
    }

    /** Register the tree the player is aiming at, so it can be watched. */
    private void discoverLookedAtTree(Minecraft client) {
        HitResult hit = client.hitResult;
        if (hit == null || hit.getType() != HitResult.Type.BLOCK) {
            return;
        }
        BlockPos pos = ((BlockHitResult) hit).getBlockPos();
        if (!isBirchAt(client, pos)) {
            return;
        }

        BlockPos base = findBase(client, pos);
        if (trees.containsKey(base) || trees.size() >= MAX_TREES) {
            return;
        }
        trees.put(base, new Tree(base, countLogs(client, base)));
    }

    /** Walk down the trunk to the lowest connected birch log. */
    private BlockPos findBase(Minecraft client, BlockPos from) {
        BlockPos base = from;
        for (int i = 0; i < MAX_BASE_WALK; i++) {
            BlockPos below = base.below();
            if (!isBirchAt(client, below)) {
                break;
            }
            base = below;
        }
        return base.immutable();
    }

    /** Detect full-chop (count -> 0) and regrowth (0 -> count) transitions. */
    private void updateTrees(Minecraft client) {
        long now = System.currentTimeMillis();
        BlockPos playerPos = client.player.blockPosition();

        for (Iterator<Map.Entry<BlockPos, Tree>> it = trees.entrySet().iterator(); it.hasNext(); ) {
            Tree tree = it.next().getValue();

            if (playerPos.distSqr(tree.base) > FORGET_DISTANCE_SQ) {
                it.remove();
                continue;
            }

            int count = countLogs(client, tree.base);

            if (!tree.downed && tree.logCount > 0 && count == 0) {
                // Fully downed: this is when the clock starts.
                tree.downed = true;
                tree.downedAt = now;
            } else if (tree.downed && count > 0) {
                // Regrown — a real, measured cycle.
                double seconds = (now - tree.downedAt) / 1000.0;
                if (seconds > 0.0 && seconds <= MAX_PLAUSIBLE_REGEN_SECONDS) {
                    recordMeasurement(seconds);
                }
                tree.downed = false;
                tree.downedAt = 0L;
            }

            tree.logCount = count;
        }
    }

    private void recordMeasurement(double seconds) {
        lastMeasurementSeconds = seconds;
        if (averageRegenSeconds < 0.0) {
            averageRegenSeconds = seconds;
        } else {
            // Running average weighted toward recent observations.
            averageRegenSeconds = (averageRegenSeconds * 0.7) + (seconds * 0.3);
        }
        measurementCount++;
    }

    /** Count birch logs in the trunk volume above a base position. */
    private int countLogs(Minecraft client, BlockPos base) {
        int count = 0;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int dy = 0; dy < TRUNK_HEIGHT; dy++) {
            for (int dx = -TRUNK_RADIUS; dx <= TRUNK_RADIUS; dx++) {
                for (int dz = -TRUNK_RADIUS; dz <= TRUNK_RADIUS; dz++) {
                    cursor.set(base.getX() + dx, base.getY() + dy, base.getZ() + dz);
                    if (isBirchAt(client, cursor)) {
                        count++;
                    }
                }
            }
        }
        return count;
    }

    private boolean isBirchAt(Minecraft client, BlockPos pos) {
        BlockState state = client.level.getBlockState(pos);
        return state.is(Blocks.BIRCH_LOG) || state.is(Blocks.BIRCH_WOOD);
    }

    // ---- Queries used by the HUD and the world renderer ----

    /** The regen duration in use: measured if known, else the configured guess. */
    public double getRegenSeconds() {
        return averageRegenSeconds > 0.0 ? averageRegenSeconds : BirchConfig.get().defaultRegenSeconds;
    }

    public boolean isCalibrated() {
        return measurementCount > 0;
    }

    public int getMeasurementCount() {
        return measurementCount;
    }

    public double getLastMeasurementSeconds() {
        return lastMeasurementSeconds;
    }

    /** Seconds until a specific tree should regrow (0 = due now). */
    public double getSecondsUntilRegen(Tree tree) {
        if (!tree.downed) {
            return -1.0;
        }
        double elapsed = (System.currentTimeMillis() - tree.downedAt) / 1000.0;
        return Math.max(0.0, getRegenSeconds() - elapsed);
    }

    /** All trees currently chopped and regrowing. */
    public List<Tree> getDownedTrees() {
        List<Tree> downed = new ArrayList<>();
        for (Tree tree : trees.values()) {
            if (tree.downed) {
                downed.add(tree);
            }
        }
        return downed;
    }

    public int getTrackedCount() {
        return trees.size();
    }

    /** Soonest regen across all downed trees, or -1 if none are pending. */
    public double getSoonestRegen() {
        double soonest = -1.0;
        for (Tree tree : trees.values()) {
            if (!tree.downed) {
                continue;
            }
            double remaining = getSecondsUntilRegen(tree);
            if (soonest < 0.0 || remaining < soonest) {
                soonest = remaining;
            }
        }
        return soonest;
    }

    /** Forget all trees and measurements. */
    public void reset() {
        trees.clear();
        averageRegenSeconds = -1.0;
        lastMeasurementSeconds = -1.0;
        measurementCount = 0;
    }
}
