package com.birchmod.tracking;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import com.birchmod.config.BirchConfig;
import com.birchmod.stats.SessionStats;
import com.birchmod.util.Notifier;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Tracks every birch tree around the player and times how long each takes to
 * regenerate.
 *
 * Tracking is entirely automatic — there is nothing to start or stop. Trees in
 * range are discovered by periodic proximity scan, keyed by their base log (the
 * lowest log of the trunk). The clock for a tree starts the moment it is
 * <em>fully</em> downed, and stops the moment logs reappear, which yields a
 * real measured regen duration rather than an assumed constant.
 *
 * Config flags only ever affect <em>display</em>. Measurement always runs.
 */
public class TreeRegenTracker {

    private static final int MAX_TREES = 128;

    /** Fast pass: detect chop/regrow transitions on known trees. */
    private static final int UPDATE_INTERVAL_TICKS = 4; // 5x per second

    /** Slow pass: sweep the area for trees we have not seen yet. */
    private static final int DISCOVER_INTERVAL_TICKS = 40; // every 2s

    /** Horizontal radius of the discovery sweep, in blocks. */
    private static final int DISCOVER_RADIUS = 16;
    private static final int DISCOVER_BELOW = 6;
    private static final int DISCOVER_ABOVE = 12;

    /** Trunk search volume above a base log. */
    private static final int TRUNK_RADIUS = 2;
    private static final int TRUNK_HEIGHT = 12;

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
        boolean alerted;

        /** Per-tree history, so individual trees can be compared. */
        int regenCount;
        double lastRegenSeconds = -1.0;

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

        public int getRegenCount() {
            return regenCount;
        }

        public double getLastRegenSeconds() {
            return lastRegenSeconds;
        }
    }

    private final Map<BlockPos, Tree> trees = new HashMap<>();

    // ---- Aggregate measurements, accumulated automatically ----
    private double averageRegenSeconds = -1.0;
    private double fastestRegenSeconds = -1.0;
    private double slowestRegenSeconds = -1.0;
    private double totalRegenSeconds = 0.0;
    private int measurementCount = 0;
    private double lastMeasurementSeconds = -1.0;

    private int updateCounter = 0;
    private int discoverCounter = 0;

    public void tick(Minecraft client) {
        if (client == null || client.player == null || client.level == null) {
            trees.clear();
            return;
        }

        // Discovery sweep — finds trees without the player aiming at anything.
        if (++discoverCounter >= DISCOVER_INTERVAL_TICKS) {
            discoverCounter = 0;
            discoverNearbyTrees(client);
        }

        if (++updateCounter < UPDATE_INTERVAL_TICKS) {
            return;
        }
        updateCounter = 0;

        updateTrees(client);
    }

    /**
     * Sweep the area around the player for birch trunk bases. A base is a birch
     * log with a non-birch block beneath it, which is cheap to test and unique
     * per trunk.
     */
    private void discoverNearbyTrees(Minecraft client) {
        if (trees.size() >= MAX_TREES) {
            return;
        }
        BlockPos origin = client.player.blockPosition();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        BlockPos.MutableBlockPos below = new BlockPos.MutableBlockPos();

        for (int dy = -DISCOVER_BELOW; dy <= DISCOVER_ABOVE; dy++) {
            for (int dx = -DISCOVER_RADIUS; dx <= DISCOVER_RADIUS; dx++) {
                for (int dz = -DISCOVER_RADIUS; dz <= DISCOVER_RADIUS; dz++) {
                    int x = origin.getX() + dx;
                    int y = origin.getY() + dy;
                    int z = origin.getZ() + dz;

                    cursor.set(x, y, z);
                    if (!isBirchAt(client, cursor)) {
                        continue;
                    }
                    below.set(x, y - 1, z);
                    if (isBirchAt(client, below)) {
                        continue; // not the base of this trunk
                    }

                    BlockPos base = cursor.immutable();
                    if (!trees.containsKey(base)) {
                        if (trees.size() >= MAX_TREES) {
                            return;
                        }
                        trees.put(base, new Tree(base, countLogs(client, base)));
                    }
                }
            }
        }
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
                tree.alerted = false;
                SessionStats.recordTreeChopped();
            } else if (tree.downed && count > 0) {
                // Regrown — a real, measured cycle.
                double seconds = (now - tree.downedAt) / 1000.0;
                if (seconds > 0.0 && seconds <= MAX_PLAUSIBLE_REGEN_SECONDS) {
                    recordMeasurement(tree, seconds);
                }
                tree.downed = false;
                tree.downedAt = 0L;
            }

            tree.logCount = count;
        }

        alertIfReady();
    }

    /**
     * Announce trees whose countdown has elapsed. Each tree alerts at most once
     * per chop, and {@link Notifier} rate-limits the alerts themselves.
     */
    private void alertIfReady() {
        List<Tree> justReady = new ArrayList<>();

        for (Tree tree : trees.values()) {
            if (tree.downed && !tree.alerted && getSecondsUntilRegen(tree) == 0.0) {
                justReady.add(tree);
            }
        }

        if (justReady.isEmpty()) {
            return;
        }
        // Mark them regardless of whether the alert was throttled, so a
        // suppressed alert does not re-fire every tick.
        for (Tree tree : justReady) {
            tree.alerted = true;
        }
        Notifier.treeReady(justReady.size());
    }

    private void recordMeasurement(Tree tree, double seconds) {
        lastMeasurementSeconds = seconds;
        totalRegenSeconds += seconds;
        measurementCount++;

        tree.regenCount++;
        tree.lastRegenSeconds = seconds;

        if (fastestRegenSeconds < 0.0 || seconds < fastestRegenSeconds) {
            fastestRegenSeconds = seconds;
        }
        if (seconds > slowestRegenSeconds) {
            slowestRegenSeconds = seconds;
        }

        if (averageRegenSeconds < 0.0) {
            averageRegenSeconds = seconds;
        } else {
            // Running average weighted toward recent observations.
            averageRegenSeconds = (averageRegenSeconds * 0.7) + (seconds * 0.3);
        }
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

    // ---- Queries used by the HUD, world renderer and commands ----

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

    public double getFastestRegenSeconds() {
        return fastestRegenSeconds;
    }

    public double getSlowestRegenSeconds() {
        return slowestRegenSeconds;
    }

    /** True mean across every cycle measured, as opposed to the weighted average. */
    public double getMeanRegenSeconds() {
        return measurementCount > 0 ? totalRegenSeconds / measurementCount : -1.0;
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

    /** Every tracked tree, standing or regrowing — the route planner's input. */
    public List<Tree> getAllTrees() {
        return new ArrayList<>(trees.values());
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

    /** How many tracked trees are standing and choppable right now. */
    public int getReadyCount() {
        int ready = 0;
        for (Tree tree : trees.values()) {
            if (!tree.downed && tree.logCount > 0) {
                ready++;
            }
        }
        return ready;
    }

    /** Forget tracked trees and all measurements. */
    public void reset() {
        trees.clear();
        averageRegenSeconds = -1.0;
        fastestRegenSeconds = -1.0;
        slowestRegenSeconds = -1.0;
        totalRegenSeconds = 0.0;
        lastMeasurementSeconds = -1.0;
        measurementCount = 0;
    }
}
