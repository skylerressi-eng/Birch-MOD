package com.birchmod.tracking;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

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
 * Tracking is entirely automatic — there is nothing to start or stop.
 *
 * <h2>Cost</h2>
 * This runs on the client thread inside someone else's frame budget, next to a
 * dozen other mods, so the hot path is written to allocate nothing and to look
 * up as few blocks as possible:
 *
 * <ul>
 *   <li>Liveness is a <em>vertical column probe</em> that exits on the first
 *       log found, so a standing tree costs a single block lookup. An earlier
 *       version flood-filled every tree five times a second, which allocated
 *       roughly a million objects per second once a grove filled the map and
 *       froze the client.</li>
 *   <li>All probing reuses one {@link BlockPos.MutableBlockPos}.</li>
 *   <li>Discovery only sweeps after the player has actually moved.</li>
 * </ul>
 */
public class TreeRegenTracker {

    private static final int MAX_TREES = 48;

    /** Fast pass: detect chop/regrow transitions on known trees. */
    private static final int UPDATE_INTERVAL_TICKS = 4; // 5x per second

    /** Slow pass: sweep for trees we have not seen yet. */
    private static final int DISCOVER_INTERVAL_TICKS = 40; // every 2s

    /** Skip the sweep entirely until the player has moved this far. */
    private static final double RESCAN_DISTANCE_SQ = 4.0 * 4.0;

    /** Forced sweep interval even when standing still. */
    private static final long FORCED_RESCAN_MS = 10_000L;

    /** Discovery sweep volume. */
    private static final int DISCOVER_RADIUS = 12;
    private static final int DISCOVER_BELOW = 4;
    private static final int DISCOVER_ABOVE = 8;

    /** New trees admitted per sweep, so one sweep cannot stall a frame. */
    private static final int MAX_NEW_PER_SWEEP = 8;

    /** How tall a trunk can be. */
    private static final int TRUNK_HEIGHT = 12;

    /** Ignore implausible measurements (block replaced by something else). */
    private static final double MAX_PLAUSIBLE_REGEN_SECONDS = 900.0;

    /** Stop tracking trees further away than this (squared). */
    private static final double FORGET_DISTANCE_SQ = 64.0 * 64.0;

    /** One tracked tree. */
    public static final class Tree {
        public final BlockPos base;
        boolean standing;
        boolean downed;
        long downedAt;
        boolean alerted;

        /** Per-tree history, so individual trees can be compared. */
        int regenCount;
        double lastRegenSeconds = -1.0;

        Tree(BlockPos base, boolean standing) {
            this.base = base;
            this.standing = standing;
        }

        public boolean isDowned() {
            return downed;
        }

        public boolean isStanding() {
            return standing;
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

    /**
     * Mutated on the client thread, iterated by the HUD and world renderers on
     * the render thread, so iteration must be weakly consistent rather than
     * fail-fast.
     */
    private final Map<BlockPos, Tree> trees = new ConcurrentHashMap<>();

    /** Reused for every block probe; the hot path allocates nothing. */
    private final BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();

    // ---- Aggregate measurements, accumulated automatically ----
    private double averageRegenSeconds = -1.0;
    private double fastestRegenSeconds = -1.0;
    private double slowestRegenSeconds = -1.0;
    private double totalRegenSeconds = 0.0;
    private int measurementCount = 0;
    private double lastMeasurementSeconds = -1.0;

    private int updateCounter = 0;
    private int discoverCounter = 0;
    private long lastSweepAt = 0L;
    private double lastSweepX = Double.NaN;
    private double lastSweepY = Double.NaN;
    private double lastSweepZ = Double.NaN;

    public void tick(Minecraft client) {
        if (client == null || client.player == null || client.level == null) {
            trees.clear();
            return;
        }

        if (++discoverCounter >= DISCOVER_INTERVAL_TICKS) {
            discoverCounter = 0;
            if (shouldSweep(client)) {
                discoverNearbyTrees(client);
            }
        }

        if (++updateCounter < UPDATE_INTERVAL_TICKS) {
            return;
        }
        updateCounter = 0;

        updateTrees(client);
    }

    /**
     * Sweeping is only worth it after the player has moved; standing in one
     * spot re-scans the same blocks for no new information.
     */
    private boolean shouldSweep(Minecraft client) {
        long now = System.currentTimeMillis();
        double x = client.player.getX();
        double y = client.player.getY();
        double z = client.player.getZ();

        if (Double.isNaN(lastSweepX)) {
            lastSweepX = x;
            lastSweepY = y;
            lastSweepZ = z;
            lastSweepAt = now;
            return true;
        }

        double dx = x - lastSweepX;
        double dy = y - lastSweepY;
        double dz = z - lastSweepZ;
        boolean moved = (dx * dx + dy * dy + dz * dz) >= RESCAN_DISTANCE_SQ;
        boolean stale = (now - lastSweepAt) >= FORCED_RESCAN_MS;

        if (!moved && !stale) {
            return false;
        }
        lastSweepX = x;
        lastSweepY = y;
        lastSweepZ = z;
        lastSweepAt = now;
        return true;
    }

    /**
     * Sweep for birch trunk bases: a birch log with a non-birch block beneath
     * it. Bounded by {@link #MAX_NEW_PER_SWEEP} so a dense grove cannot turn one
     * sweep into a frame stall.
     */
    private void discoverNearbyTrees(Minecraft client) {
        if (trees.size() >= MAX_TREES) {
            return;
        }
        BlockPos origin = client.player.blockPosition();
        int added = 0;

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
                    cursor.set(x, y - 1, z);
                    if (isBirchAt(client, cursor)) {
                        continue; // not the base of this trunk
                    }

                    cursor.set(x, y, z);
                    if (trees.containsKey(cursor) || isPartOfTrackedTrunk(x, y, z)) {
                        continue;
                    }

                    BlockPos base = new BlockPos(x, y, z);
                    trees.put(base, new Tree(base, true));

                    if (++added >= MAX_NEW_PER_SWEEP || trees.size() >= MAX_TREES) {
                        return;
                    }
                }
            }
        }
    }

    /**
     * True if this candidate sits directly above an already-tracked base. A
     * branch whose lowest log happens to have air beneath it would otherwise
     * register the same tree a second time. Walking the column is a handful of
     * O(1) map lookups, unlike scanning every tracked tree.
     */
    private boolean isPartOfTrackedTrunk(int x, int y, int z) {
        for (int dy = 1; dy <= TRUNK_HEIGHT; dy++) {
            cursor.set(x, y - dy, z);
            if (trees.containsKey(cursor)) {
                return true;
            }
        }
        return false;
    }

    /** Detect full-chop and regrowth transitions. */
    private void updateTrees(Minecraft client) {
        long now = System.currentTimeMillis();
        BlockPos playerPos = client.player.blockPosition();

        for (Iterator<Map.Entry<BlockPos, Tree>> it = trees.entrySet().iterator(); it.hasNext(); ) {
            Tree tree = it.next().getValue();

            if (playerPos.distSqr(tree.base) > FORGET_DISTANCE_SQ) {
                it.remove();
                continue;
            }

            boolean standing = isTrunkStanding(client, tree.base);

            if (!tree.downed && tree.standing && !standing) {
                // Fully downed: this is when the clock starts.
                tree.downed = true;
                tree.downedAt = now;
                tree.alerted = false;
                SessionStats.recordTreeChopped();
            } else if (tree.downed && standing) {
                // Regrown — a real, measured cycle.
                double seconds = (now - tree.downedAt) / 1000.0;
                if (seconds > 0.0 && seconds <= MAX_PLAUSIBLE_REGEN_SECONDS) {
                    recordMeasurement(tree, seconds);
                }
                tree.downed = false;
                tree.downedAt = 0L;
            }

            tree.standing = standing;
        }

        alertIfReady();
    }

    /**
     * Whether any log remains in this tree's trunk column.
     *
     * Exits on the first log found, so the common case — a standing tree — is a
     * single block lookup, and a downed tree costs at most {@link #TRUNK_HEIGHT}.
     * Nothing is allocated.
     */
    private boolean isTrunkStanding(Minecraft client, BlockPos base) {
        for (int dy = 0; dy < TRUNK_HEIGHT; dy++) {
            cursor.set(base.getX(), base.getY() + dy, base.getZ());
            if (isBirchAt(client, cursor)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Announce trees whose countdown has elapsed. Each tree alerts at most once
     * per chop, and {@link Notifier} rate-limits the alerts themselves.
     */
    private void alertIfReady() {
        int readyNow = 0;

        for (Tree tree : trees.values()) {
            if (tree.downed && !tree.alerted && getSecondsUntilRegen(tree) == 0.0) {
                // Mark regardless of whether the alert is throttled, so a
                // suppressed alert does not re-fire every tick.
                tree.alerted = true;
                readyNow++;
            }
        }

        if (readyNow > 0) {
            Notifier.treeReady(readyNow);
        }
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

    /** Every tracked tree, standing or regrowing — the route planner's input. */
    public List<Tree> getAllTrees() {
        return new ArrayList<>(trees.values());
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

    /** How many tracked trees are standing and choppable right now. */
    public int getReadyCount() {
        int ready = 0;
        for (Tree tree : trees.values()) {
            if (!tree.downed && tree.standing) {
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
        lastSweepX = Double.NaN;
    }
}
