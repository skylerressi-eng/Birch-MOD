package com.birchmod.route;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.birchmod.config.BirchConfig;
import com.birchmod.tracking.TreeRegenTracker;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Decides which trees to visit next, in what order.
 *
 * <h2>Two modes</h2>
 * With a recorded loop active, the route <em>is</em> that loop: it picks up at
 * whichever stop you are nearest and walks forward in the order you recorded,
 * so the path stays the one you chose. Regen still matters — a stop you would
 * arrive at long before it regrows is stepped over and picked up next lap,
 * rather than making you stand and wait.
 *
 * With no recorded loop, it falls back to planning one: a greedy walk where
 * "nearest" is measured in <em>time</em> rather than distance, since a tree two
 * seconds further away that is ready now beats a closer one with twenty seconds
 * left on its clock.
 *
 * <h2>Stability</h2>
 * The route is rebuilt several times a second, and an order recomputed from
 * scratch each time reshuffles whenever two trees are near-equal — which is
 * what made the tracers flick around. Stops already committed to are therefore
 * kept in place and only the remainder is planned, so the path changes when the
 * grove changes rather than on every tick.
 */
public final class RouteBuilder {


    private static final long RECOMPUTE_INTERVAL_MS = 400L;

    /**
     * Skip a recorded stop when waiting for it costs more than this much longer
     * than simply carrying on round the loop.
     */
    private static final double SKIP_WAIT_SECONDS = 8.0;

    /** How far up a trunk to look for real wood. */
    private static final int TRUNK_PROBE_HEIGHT = 12;

    /**
     * Beyond this from the held target, the player has gone somewhere else
     * entirely — warped, or walked off the island — and picking the loop back
     * up where they are beats marching them back to where they left off.
     */
    private static final double REJOIN_DISTANCE = 96.0;

    /** One stop on the route. The tree is null for a recorded stop not yet seen. */
    public record Stop(TreeRegenTracker.Tree tree, BlockPos center, double etaSeconds, int order) {
        public boolean isWaiting() {
            return tree != null && tree.isDowned() && etaSeconds > 0.5;
        }
    }

    private final TreeRegenTracker regenTracker;

    /** Built on the client thread, read by the render thread. */
    private volatile List<Stop> route = List.of();
    private long lastComputed = 0L;

    /** Bases of the stops already committed to, in order. */
    private final List<BlockPos> committed = new ArrayList<>();

    /** Where round the recorded loop we are, held until there is cause to move. */
    private int loopIndex = -1;
    private String loopRouteName = null;

    public RouteBuilder(TreeRegenTracker regenTracker) {
        this.regenTracker = regenTracker;
    }

    /** Recompute the route if it is stale. Cheap to call every tick. */
    public void update(Vec3 playerPos) {
        long now = System.currentTimeMillis();
        if (now - lastComputed < RECOMPUTE_INTERVAL_MS) {
            return;
        }
        lastComputed = now;

        RecordedRoute active = RouteLibrary.getActive();
        List<Stop> next = (active != null && active.size() >= RouteLibrary.MIN_STOPS)
                ? followRecorded(active, playerPos)
                : planFresh(playerPos);

        route = List.copyOf(next);
    }

    // ---- Recorded loop ----

    /**
     * Walk the recorded loop from wherever the player is, stepping over stops
     * that will not have regrown by the time they are reached.
     */
    private List<Stop> followRecorded(RecordedRoute recorded, Vec3 playerPos) {
        BirchConfig config = BirchConfig.get();
        Map<BlockPos, TreeRegenTracker.Tree> byBase = indexTrees();

        int size = recorded.size();

        // Pick up at the nearest stop only when starting out or after switching
        // routes. Re-deriving it from proximity on every recompute is what made
        // the route shuffle under the player: walking between two stops flipped
        // which one was "nearest" and the whole loop re-based around it.
        boolean needsRejoin = loopIndex < 0
                || loopIndex >= size
                || !recorded.name.equals(loopRouteName)
                || playerPos.distanceTo(Vec3.atCenterOf(baseOf(recorded, loopIndex))) > REJOIN_DISTANCE;

        if (needsRejoin) {
            loopIndex = recorded.nearestIndex(playerPos.x, playerPos.y, playerPos.z);
            loopRouteName = recorded.name;
        }

        // Advance only for a reason: the stop was felled, or it will not be
        // back soon enough to be worth standing there. Position never advances
        // the loop on its own.
        for (int guard = 0; guard < size; guard++) {
            TreeRegenTracker.Tree tree = byBase.get(baseOf(recorded, loopIndex));
            if (tree == null || !tree.isDowned()) {
                break;
            }
            if (regenTracker.getSecondsUntilRegen(tree) <= SKIP_WAIT_SECONDS) {
                break;
            }
            loopIndex = (loopIndex + 1) % size;
        }

        List<Stop> result = new ArrayList<>();
        Vec3 cursor = playerPos;
        double clock = 0.0;
        int order = 1;

        for (int step = 0; step < size && result.size() < config.routeLength; step++) {
            int index = (loopIndex + step) % size;
            BlockPos base = baseOf(recorded, index);
            TreeRegenTracker.Tree tree = byBase.get(base);
            BlockPos center = resolveCenter(base, tree);

            double travel = cursor.distanceTo(Vec3.atCenterOf(center)) / RouteLibrary.walkSpeed();
            double readyAt = readySecondsFromNow(tree);
            double arrival = Math.max(clock + travel, readyAt);

            result.add(new Stop(tree, center, Math.max(0.0, arrival), order++));
            cursor = Vec3.atCenterOf(center);
            clock = arrival;
        }
        return result;
    }

    private BlockPos baseOf(RecordedRoute recorded, int index) {
        RecordedRoute.Point point = recorded.points.get(index % recorded.size());
        return new BlockPos(point.x, point.y, point.z);
    }

    /**
     * Where to put the marker for a stop.
     *
     * A tracked tree already knows which of its blocks is wood. An untracked
     * one — a stop further round the loop than the tracker sweeps — used to get
     * a blind offset above its base, which lands in the canopy as often as not
     * and is why markers kept settling on leaves. Probing the trunk for a real
     * log costs a handful of block lookups for the few stops actually drawn.
     */
    private BlockPos resolveCenter(BlockPos base, TreeRegenTracker.Tree tree) {
        if (tree != null) {
            return tree.getTarget();
        }

        Minecraft client = Minecraft.getInstance();
        if (client == null || client.level == null
                || !client.level.hasChunk(base.getX() >> 4, base.getZ() >> 4)) {
            // Cannot check, so stay on the trunk base rather than guess upward.
            return base;
        }

        int desired = base.getY() + BirchConfig.get().treeCenterHeight;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        int bestY = Integer.MIN_VALUE;
        int bestDistance = Integer.MAX_VALUE;

        for (int dy = 0; dy < TRUNK_PROBE_HEIGHT; dy++) {
            int y = base.getY() + dy;
            cursor.set(base.getX(), y, base.getZ());
            BlockState state = client.level.getBlockState(cursor);
            if (!state.is(Blocks.BIRCH_LOG) && !state.is(Blocks.BIRCH_WOOD)) {
                continue;
            }
            int distance = Math.abs(y - desired);
            if (distance < bestDistance) {
                bestDistance = distance;
                bestY = y;
            }
        }

        return bestY == Integer.MIN_VALUE
                ? base
                : new BlockPos(base.getX(), bestY, base.getZ());
    }

    private Map<BlockPos, TreeRegenTracker.Tree> indexTrees() {
        Map<BlockPos, TreeRegenTracker.Tree> byBase = new HashMap<>();
        for (TreeRegenTracker.Tree tree : regenTracker.getAllTrees()) {
            byBase.put(tree.base, tree);
        }
        return byBase;
    }

    // ---- Planned route ----

    /**
     * Plan a route, keeping stops already committed to so the path stays put
     * between recomputes.
     */
    private List<Stop> planFresh(Vec3 playerPos) {
        BirchConfig config = BirchConfig.get();
        Map<BlockPos, TreeRegenTracker.Tree> byBase = indexTrees();

        List<TreeRegenTracker.Tree> remaining = new ArrayList<>(byBase.values());
        List<TreeRegenTracker.Tree> ordered = new ArrayList<>();

        // Keep previously committed stops that still exist, in their old order.
        for (int i = 0; i < committed.size(); i++) {
            TreeRegenTracker.Tree tree = byBase.get(committed.get(i));
            if (tree == null) {
                continue;
            }
            // Felling it is the only reason to move on. Releasing the target
            // on proximity as well meant the marker jumped to the next tree as
            // soon as the player walked up to this one, before they had chopped
            // it — the route advancing on its own.
            if (tree.isDowned()) {
                continue;
            }
            ordered.add(tree);
            remaining.remove(tree);
        }

        int limit = Math.min(config.routeLength, byBase.size());

        Vec3 cursor = ordered.isEmpty()
                ? playerPos
                : Vec3.atCenterOf(ordered.get(ordered.size() - 1).getTarget());

        // Fill the rest greedily, cheapest in time first.
        while (ordered.size() < limit && !remaining.isEmpty()) {
            TreeRegenTracker.Tree best = null;
            double bestCost = Double.MAX_VALUE;

            for (TreeRegenTracker.Tree tree : remaining) {
                double travel = cursor.distanceTo(Vec3.atCenterOf(tree.getTarget()))
                        / RouteLibrary.walkSpeed();
                double cost = Math.max(travel, readySecondsFromNow(tree));
                if (cost < bestCost) {
                    bestCost = cost;
                    best = tree;
                }
            }
            if (best == null) {
                break;
            }
            ordered.add(best);
            remaining.remove(best);
            cursor = Vec3.atCenterOf(best.getTarget());
        }

        // Re-time the settled order and record it as the new commitment.
        committed.clear();
        List<Stop> result = new ArrayList<>();
        Vec3 walker = playerPos;
        double clock = 0.0;
        int order = 1;

        for (TreeRegenTracker.Tree tree : ordered) {
            BlockPos center = tree.getTarget();
            double travel = walker.distanceTo(Vec3.atCenterOf(center)) / RouteLibrary.walkSpeed();
            double arrival = Math.max(clock + travel, readySecondsFromNow(tree));

            result.add(new Stop(tree, center, Math.max(0.0, arrival), order++));
            committed.add(tree.base);
            walker = Vec3.atCenterOf(center);
            clock = arrival;
        }
        return result;
    }

    /** Seconds until this tree is choppable, 0 if it is standing right now. */
    private double readySecondsFromNow(TreeRegenTracker.Tree tree) {
        if (tree == null || !tree.isDowned()) {
            return 0.0;
        }
        return Math.max(0.0, regenTracker.getSecondsUntilRegen(tree));
    }

    /**
     * The block a tracer points at. The tracker resolves this to an actual log
     * of the trunk, so the marker lands on wood instead of a fixed offset that
     * can float above a short tree or sit below its first log.
     */
    public static BlockPos centerOf(TreeRegenTracker.Tree tree) {
        return tree.getTarget();
    }

    // ---- Queries ----

    public List<Stop> getRoute() {
        return route;
    }

    /** The stop you should head to right now, or null if the route is empty. */
    public Stop getNext() {
        List<Stop> snapshot = route;
        return snapshot.isEmpty() ? null : snapshot.get(0);
    }

    public boolean isEmpty() {
        return route.isEmpty();
    }

    /** Forget the committed order, e.g. after switching routes. */
    public void resetCommitment() {
        committed.clear();
        loopIndex = -1;
        loopRouteName = null;
        lastComputed = 0L;
    }
}
