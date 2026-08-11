package com.birchmod.tracking;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
 * regenerate. Tracking is entirely automatic — there is nothing to start.
 *
 * <h2>A tree is a footprint, not a column</h2>
 * The earlier version defined a tree as the single {@code (x, z)} column its
 * base sat in. Park birches are not shaped like that: trunks come in pairs and
 * branches step sideways, so clearing the tracked column left wood standing
 * beside it while the tracker reported the tree fully felled. The route then
 * moved on and abandoned the leftovers.
 *
 * A tree is now the block of columns around its base. Each column is
 * <em>owned</em> by exactly one tree, claimed on discovery, so two trunks
 * standing next to each other cannot count each other's wood — the tree stays
 * felled once its own columns are empty, and not before.
 *
 * <h2>Cost</h2>
 * This runs on the client thread inside someone else's frame budget, so the
 * probe is bounded three ways:
 *
 * <ul>
 *   <li>Only columns that have <em>ever</em> held wood are probed; the empty
 *       corners of a footprint are visited on the slow full sweep only.</li>
 *   <li>Each tree carries its own due time, and at most
 *       {@link #PROBE_BUDGET_PER_PASS} of them are probed per pass.</li>
 *   <li>The trees the route is actually pointing at are exempt from that budget
 *       and probed every pass, so the block you are mining reacts instantly
 *       while the rest of the grove ticks over in the background.</li>
 * </ul>
 *
 * All probing reuses one {@link BlockPos.MutableBlockPos} and one scratch array,
 * so the steady state allocates nothing.
 */
public class TreeRegenTracker {

    private static final int MAX_TREES = 48;

    /** Fast pass: detect chop/regrow transitions. */
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

    /** Upper bound on the configurable footprint, so the cell mask fits an int. */
    private static final int MAX_FOOTPRINT_RADIUS = 2;

    /** Leftover logs remembered per tree, for the renderer to outline. */
    private static final int MAX_WOOD_MARKS = 24;

    private static final long[] NO_WOOD = new long[0];

    /**
     * Unfocused trees probed per pass.
     *
     * Deliberately above what a full grove asks for: {@link #MAX_TREES} trees
     * wanting a look every {@link #NORMAL_PROBE_INTERVAL_MS} works out at
     * sixteen per pass, so a budget of twenty is never the binding constraint.
     * A budget that bites would be served in map-iteration order, and the trees
     * at the back of that order would go unwatched for as long as the order
     * held.
     */
    private static final int PROBE_BUDGET_PER_PASS = 20;

    /** How often an unfocused tree is re-probed. */
    private static final long NORMAL_PROBE_INTERVAL_MS = 600L;

    /** How often every owned column is swept, not just the live ones. */
    private static final long FULL_PROBE_INTERVAL_MS = 8_000L;

    /** Ignore implausible measurements (block replaced by something else). */
    private static final double MAX_PLAUSIBLE_REGEN_SECONDS = 900.0;

    /**
     * A tree must stay down at least this long before regrowth is believed.
     * Chunks briefly read as air while they reload, which otherwise registers
     * as an instant chop-and-regrow and inflates the count.
     */
    private static final long MIN_DOWNED_MS = 1_500L;

    /** Stop tracking trees further away than this (squared). */
    private static final double FORGET_DISTANCE_SQ = 64.0 * 64.0;

    /**
     * How far a recorded coordinate may sit from a tracked base and still mean
     * the same tree. A trunk's base is re-detected a block or two off after it
     * regrows, so recorded routes never line up exactly.
     */
    public static final double SAME_TREE_RADIUS = 3.0;

    /** One tracked tree: a base, and the columns around it that belong to it. */
    public static final class Tree {
        public final BlockPos base;

        /** Fixed at creation so the cell masks stay meaningful for its lifetime. */
        final int radius;
        final int width;

        /** Cells this tree is allowed to probe; the rest belong to neighbours. */
        final int ownedMask;

        /** Cells that have ever held wood — the fast probe's working set. */
        int liveMask;

        long nextProbeAt;
        long lastFullProbeAt;
        boolean probedOnce;

        boolean standing;
        boolean downed;
        long downedAt;

        /** Per-tree history, so individual trees can be compared. */
        int regenCount;
        double lastRegenSeconds = -1.0;

        /** Wood anywhere in the footprint, and the most ever seen there. */
        volatile int woodCount;
        int fullWoodCount;

        /**
         * Packed positions of the logs still standing, so leftovers can be
         * outlined without re-scanning on the render thread.
         */
        volatile long[] wood = NO_WOOD;

        /**
         * The block to highlight: an actual log of this tree, not an offset from
         * the base. Written on the client thread, read by renderers.
         */
        volatile BlockPos target;

        /**
         * True when this tree has been chopped into and left unfinished: wood is
         * missing compared with its own full size, but some remains.
         */
        volatile boolean partiallyChopped;

        Tree(BlockPos base, int radius, int ownedMask) {
            this.base = base;
            this.radius = radius;
            this.width = radius * 2 + 1;
            this.ownedMask = ownedMask;
            this.liveMask = ownedMask;
            this.target = base;
            this.standing = true;
        }

        /** Always a real block position; falls back to the base when felled. */
        public BlockPos getTarget() {
            BlockPos current = target;
            return current != null ? current : base;
        }

        public boolean isDowned() {
            return downed;
        }

        /** True while any wood remains — the only reason to walk to this tree. */
        public boolean hasWood() {
            return woodCount > 0;
        }

        public boolean isStanding() {
            return standing;
        }

        public int getWoodCount() {
            return woodCount;
        }

        /** Packed positions of the remaining logs. Never null, never mutated. */
        public long[] getWoodPositions() {
            long[] snapshot = wood;
            return snapshot != null ? snapshot : NO_WOOD;
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

        public boolean isPartiallyChopped() {
            return partiallyChopped;
        }
    }

    /**
     * Mutated on the client thread, iterated by the HUD and world renderers on
     * the render thread, so iteration must be weakly consistent rather than
     * fail-fast.
     */
    private final Map<BlockPos, Tree> trees = new ConcurrentHashMap<>();

    /**
     * Which tree owns each column, so neighbouring trunks cannot be credited
     * with each other's wood. Client thread only.
     */
    private final Map<Long, BlockPos> columnOwner = new java.util.HashMap<>();

    /** Reused for every block probe; the hot path allocates nothing. */
    private final BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();

    /** Reused when collecting a tree's remaining logs. */
    private final long[] scratch = new long[MAX_WOOD_MARKS];

    /**
     * Bases the route is pointing at. These are probed every pass regardless of
     * the budget, so the highlighted block tracks the wood as you break it.
     */
    private volatile Set<BlockPos> focus = Set.of();

    // ---- Aggregate measurements, accumulated automatically ----
    private double averageRegenSeconds = -1.0;
    private double fastestRegenSeconds = -1.0;
    private double slowestRegenSeconds = -1.0;
    private double totalRegenSeconds = 0.0;
    private int measurementCount = 0;
    private double lastMeasurementSeconds = -1.0;
    private int regeneratedCount = 0;

    /** Notified when a tree is fully felled, so routes can be recorded. */
    private java.util.function.Consumer<BlockPos> chopListener = null;

    public void setChopListener(java.util.function.Consumer<BlockPos> listener) {
        this.chopListener = listener;
    }

    /**
     * Tell the tracker which trees matter right now. Anything named here is
     * probed on every pass; everything else waits its turn.
     */
    public void setFocus(Collection<BlockPos> bases) {
        if (bases == null || bases.isEmpty()) {
            focus = Set.of();
            return;
        }
        focus = Set.copyOf(new HashSet<>(bases));
    }

    private int updateCounter = 0;
    private int discoverCounter = 0;
    private long lastSweepAt = 0L;
    private double lastSweepX = Double.NaN;
    private double lastSweepY = Double.NaN;
    private double lastSweepZ = Double.NaN;

    public void tick(Minecraft client) {
        if (client == null || client.player == null || client.level == null) {
            if (!trees.isEmpty()) {
                trees.clear();
                columnOwner.clear();
            }
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

    // ---- Discovery ----

    private static int footprintRadius() {
        return Math.max(0, Math.min(MAX_FOOTPRINT_RADIUS, BirchConfig.get().treeFootprint));
    }

    private static long columnKey(int x, int z) {
        return ((long) x << 32) ^ (z & 0xFFFFFFFFL);
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
        int radius = footprintRadius();
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

                    // The base column already belongs to a tree, so this is a
                    // second trunk of that same tree rather than a new one.
                    if (columnOwner.containsKey(columnKey(x, z))) {
                        continue;
                    }

                    BlockPos base = new BlockPos(x, y, z);
                    if (trees.containsKey(base)) {
                        continue;
                    }
                    trees.put(base, new Tree(base, radius, claimColumns(base, radius)));

                    if (++added >= MAX_NEW_PER_SWEEP || trees.size() >= MAX_TREES) {
                        return;
                    }
                }
            }
        }
    }

    /**
     * Claim every free column in this tree's footprint.
     *
     * Columns already spoken for stay with their first owner, so a pair of
     * trunks two blocks apart splits the ground between them instead of each
     * seeing the other still standing. The base column is always claimed —
     * discovery refuses to register a base whose column is taken.
     */
    private int claimColumns(BlockPos base, int radius) {
        int width = radius * 2 + 1;
        int owned = 0;

        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                long key = columnKey(base.getX() + dx, base.getZ() + dz);
                BlockPos owner = columnOwner.get(key);
                if (owner != null && !owner.equals(base)) {
                    continue;
                }
                columnOwner.put(key, base);
                owned |= 1 << cellIndex(dx, dz, radius, width);
            }
        }
        return owned;
    }

    private void releaseColumns(Tree tree) {
        for (int dx = -tree.radius; dx <= tree.radius; dx++) {
            for (int dz = -tree.radius; dz <= tree.radius; dz++) {
                long key = columnKey(tree.base.getX() + dx, tree.base.getZ() + dz);
                if (tree.base.equals(columnOwner.get(key))) {
                    columnOwner.remove(key);
                }
            }
        }
    }

    private static int cellIndex(int dx, int dz, int radius, int width) {
        return (dx + radius) * width + (dz + radius);
    }

    // ---- Transitions ----

    /** Detect full-fell and regrowth transitions on the trees due a probe. */
    private void updateTrees(Minecraft client) {
        long now = System.currentTimeMillis();
        BlockPos playerPos = client.player.blockPosition();
        Set<BlockPos> focused = focus;
        int regrewThisPass = 0;
        int budget = PROBE_BUDGET_PER_PASS;

        for (Iterator<Map.Entry<BlockPos, Tree>> it = trees.entrySet().iterator(); it.hasNext(); ) {
            Tree tree = it.next().getValue();

            if (playerPos.distSqr(tree.base) > FORGET_DISTANCE_SQ) {
                releaseColumns(tree);
                it.remove();
                continue;
            }

            // An unloaded chunk reads as air. Believing that would register a
            // phantom chop, then a phantom regrow the moment it loads again.
            if (!client.level.hasChunk(tree.base.getX() >> 4, tree.base.getZ() >> 4)) {
                continue;
            }

            boolean urgent = focused.contains(tree.base);
            if (!urgent) {
                if (now < tree.nextProbeAt) {
                    continue;
                }
                if (budget <= 0) {
                    continue;
                }
                budget--;
            }
            tree.nextProbeAt = now + NORMAL_PROBE_INTERVAL_MS;

            boolean standing = probeTree(client, tree, now);

            if (!tree.downed && tree.standing && !standing) {
                // Every column of this tree is empty: the clock starts now.
                tree.downed = true;
                tree.downedAt = now;
                SessionStats.recordTreeChopped();

                java.util.function.Consumer<BlockPos> listener = chopListener;
                if (listener != null) {
                    listener.accept(tree.base);
                }
            } else if (tree.downed && standing) {
                long downFor = now - tree.downedAt;
                if (downFor < MIN_DOWNED_MS) {
                    // Too quick to be a real regen cycle. The chop that opened
                    // it was counted, so rejecting the regrow without also
                    // taking that back would leave a phantom chop inflating the
                    // totals for good. Undo the pair together.
                    SessionStats.undoTreeChopped();
                    tree.downed = false;
                    tree.downedAt = 0L;
                    tree.standing = standing;
                    continue;
                }

                double seconds = downFor / 1000.0;
                if (seconds <= MAX_PLAUSIBLE_REGEN_SECONDS) {
                    recordMeasurement(tree, seconds);
                }
                // Only a tree that actually came back counts as regenerated.
                regeneratedCount++;
                regrewThisPass++;

                tree.downed = false;
                tree.downedAt = 0L;
                tree.fullWoodCount = tree.woodCount;
                // A regrown tree may come back a different shape, so re-learn
                // which of its columns hold wood on the next probe.
                tree.lastFullProbeAt = 0L;
            }

            tree.standing = standing;
        }

        // One notification per pass, however many trees returned at once.
        if (regrewThisPass > 0) {
            Notifier.treeReady(regrewThisPass);
        }
    }

    /**
     * Scan this tree's owned columns, recording how much wood is left, where it
     * is, and which block the marker should sit on.
     *
     * The marker is always an actual log — the nearest one to the configured
     * centre height, preferring the base column so it lands on the trunk rather
     * than a branch. It can never float in the air or settle on a leaf, because
     * only positions confirmed to hold birch are ever considered.
     *
     * @return true while any wood remains
     */
    private boolean probeTree(Minecraft client, Tree tree, long now) {
        boolean full = !tree.probedOnce || (now - tree.lastFullProbeAt) >= FULL_PROBE_INTERVAL_MS;
        int mask = full ? tree.ownedMask : tree.liveMask;
        if (mask == 0) {
            mask = tree.ownedMask;
        }

        BlockPos base = tree.base;
        int desired = base.getY() + BirchConfig.get().treeCenterHeight;
        int radius = tree.radius;
        int width = tree.width;

        int count = 0;
        int marks = 0;
        int liveCells = 0;
        int bestScore = Integer.MAX_VALUE;
        int bestX = base.getX();
        int bestY = base.getY();
        int bestZ = base.getZ();

        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                int cell = cellIndex(dx, dz, radius, width);
                if ((mask & (1 << cell)) == 0) {
                    continue;
                }
                int x = base.getX() + dx;
                int z = base.getZ() + dz;
                boolean cellHasWood = false;

                for (int dy = 0; dy < TRUNK_HEIGHT; dy++) {
                    int y = base.getY() + dy;
                    cursor.set(x, y, z);
                    if (!isBirchAt(client, cursor)) {
                        continue;
                    }
                    count++;
                    cellHasWood = true;
                    if (marks < MAX_WOOD_MARKS) {
                        scratch[marks++] = BlockPos.asLong(x, y, z);
                    }
                    // Vertical closeness to the wanted height decides, with a
                    // strong penalty for stepping off the trunk, so the marker
                    // sits on the trunk whenever the trunk has wood at all.
                    int score = Math.abs(y - desired) + (Math.abs(dx) + Math.abs(dz)) * 8;
                    if (score < bestScore) {
                        bestScore = score;
                        bestX = x;
                        bestY = y;
                        bestZ = z;
                    }
                }
                if (cellHasWood) {
                    liveCells |= 1 << cell;
                }
            }
        }

        if (full) {
            tree.lastFullProbeAt = now;
            // Keep the base cell in the working set even when it is empty, so a
            // felled tree is still watched where it will grow back.
            int baseCell = 1 << cellIndex(0, 0, radius, width);
            tree.liveMask = (liveCells | baseCell) & tree.ownedMask;
        } else if (liveCells != 0) {
            tree.liveMask |= liveCells;
        }
        tree.probedOnce = true;

        tree.woodCount = count;
        publishWood(tree, marks);

        if (count == 0) {
            tree.target = base;
            tree.partiallyChopped = false;
            return false;
        }

        // A tree only counts as partially chopped once it has lost wood it was
        // previously seen to have. A tree discovered already short establishes
        // that shorter size as its own full size, so untouched trees and
        // naturally stubby ones are never flagged.
        if (count > tree.fullWoodCount) {
            tree.fullWoodCount = count;
        }
        tree.partiallyChopped = !tree.downed && count < tree.fullWoodCount;

        BlockPos current = tree.target;
        if (current == null || current.getX() != bestX
                || current.getY() != bestY || current.getZ() != bestZ) {
            tree.target = new BlockPos(bestX, bestY, bestZ);
        }
        return true;
    }

    /**
     * Hand the renderers the remaining logs, reusing the previous array when
     * nothing moved. A standing tree is probed several times a second and its
     * wood rarely changes, so this keeps the steady state allocation-free.
     */
    private void publishWood(Tree tree, int marks) {
        long[] previous = tree.wood;
        if (previous != null && previous.length == marks) {
            boolean same = true;
            for (int i = 0; i < marks; i++) {
                if (previous[i] != scratch[i]) {
                    same = false;
                    break;
                }
            }
            if (same) {
                return;
            }
        }
        if (marks == 0) {
            tree.wood = NO_WOOD;
            return;
        }
        long[] fresh = new long[marks];
        System.arraycopy(scratch, 0, fresh, 0, marks);
        tree.wood = fresh;
    }

    private void recordMeasurement(Tree tree, double seconds) {
        // Remembered across sessions so the next login starts calibrated.
        SessionStats.recordRegenMeasurement(seconds);

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

    // ---- Lookup ----

    /**
     * The tracked tree nearest to a coordinate, within {@code radius}.
     *
     * Routes are recorded as the coordinates trees had when they were felled,
     * and a trunk's base is re-detected a block or two off after it regrows.
     * Matching those by exact position — as the route planner used to — fails
     * silently and leaves every recorded stop looking untracked, with no regen
     * clock, no leftover wood and no real block to mark. Matching by proximity
     * is the only thing that holds a recorded route to the ground.
     */
    public Tree findNear(int x, int y, int z, double radius) {
        double bestDistSq = radius * radius;
        Tree best = null;

        for (Tree tree : trees.values()) {
            double dx = tree.base.getX() - x;
            double dy = tree.base.getY() - y;
            double dz = tree.base.getZ() - z;
            double distSq = dx * dx + dy * dy + dz * dz;
            if (distSq <= bestDistSq) {
                bestDistSq = distSq;
                best = tree;
            }
        }
        return best;
    }

    public Tree findNear(BlockPos pos, double radius) {
        return pos == null ? null : findNear(pos.getX(), pos.getY(), pos.getZ(), radius);
    }

    // ---- Queries used by the HUD, world renderer and commands ----

    /**
     * The regen duration in use.
     *
     * This session's own measurements first, then what has been measured in
     * past sessions, and only then the configured guess. Falling straight back
     * to the guess on every login meant the timer relearned from scratch each
     * time and every countdown was an estimate until it had.
     */
    public double getRegenSeconds() {
        if (averageRegenSeconds > 0.0) {
            return averageRegenSeconds;
        }
        double remembered = SessionStats.getPersistedRegenSeconds();
        if (remembered > 0.0) {
            return remembered;
        }
        return BirchConfig.get().defaultRegenSeconds;
    }

    /** True when the figure comes from measurement rather than the default. */
    public boolean isCalibrated() {
        return measurementCount > 0 || SessionStats.getPersistedRegenSeconds() > 0.0;
    }

    /** Cycles measured across every session, not just this one. */
    public long getLifetimeMeasurementCount() {
        return SessionStats.getRegenSamples();
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

    /**
     * How long this particular tree is expected to take. A tree that has been
     * measured before is timed by its own history rather than the global
     * average, which keeps each label attached to the truth of its own tree.
     */
    public double getExpectedRegenSeconds(Tree tree) {
        if (tree != null && tree.lastRegenSeconds > 0.0) {
            return tree.lastRegenSeconds;
        }
        return getRegenSeconds();
    }

    /** Mean across every cycle this session, or the remembered figure. */
    public double getEffectiveMeanRegen() {
        double sessionMean = getMeanRegenSeconds();
        if (sessionMean > 0.0) {
            return sessionMean;
        }
        return SessionStats.getPersistedRegenSeconds();
    }

    /**
     * Seconds left for this tree, negative once it is overdue. Renderers want
     * the raw value; the route wants it clamped, so both exist.
     */
    public double getRemainingSeconds(Tree tree) {
        if (tree == null || !tree.downed) {
            return Double.NaN;
        }
        double elapsed = (System.currentTimeMillis() - tree.downedAt) / 1000.0;
        return getExpectedRegenSeconds(tree) - elapsed;
    }

    /** How many trees have actually come back this session. */
    public int getRegeneratedCount() {
        return regeneratedCount;
    }

    /** Seconds until a specific tree should regrow (0 = due now). */
    public double getSecondsUntilRegen(Tree tree) {
        if (tree == null || !tree.downed) {
            return -1.0;
        }
        double elapsed = (System.currentTimeMillis() - tree.downedAt) / 1000.0;
        return Math.max(0.0, getExpectedRegenSeconds(tree) - elapsed);
    }

    /** All trees currently felled and regrowing. */
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

    /** Soonest regen across all felled trees, or -1 if none are pending. */
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

    /** How many tracked trees still have wood on them right now. */
    public int getReadyCount() {
        int ready = 0;
        for (Tree tree : trees.values()) {
            if (!tree.downed && tree.hasWood()) {
                ready++;
            }
        }
        return ready;
    }

    /** How many trees have been chopped into but not finished. */
    public int getUnfinishedCount() {
        int unfinished = 0;
        for (Tree tree : trees.values()) {
            if (tree.partiallyChopped) {
                unfinished++;
            }
        }
        return unfinished;
    }

    /** Forget tracked trees and all measurements. */
    public void reset() {
        trees.clear();
        columnOwner.clear();
        focus = Set.of();
        averageRegenSeconds = -1.0;
        fastestRegenSeconds = -1.0;
        slowestRegenSeconds = -1.0;
        totalRegenSeconds = 0.0;
        lastMeasurementSeconds = -1.0;
        measurementCount = 0;
        regeneratedCount = 0;
        lastSweepX = Double.NaN;
    }
}
