package com.birchmod.route;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.birchmod.config.BirchConfig;
import com.birchmod.tracking.TreeRegenTracker;

import net.minecraft.core.BlockPos;
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

    /** Rough Skyblock travel speed in blocks/second, used to compare with regen. */
    private static final double TRAVEL_BLOCKS_PER_SECOND = RouteLibrary.WALK_BLOCKS_PER_SECOND;

    private static final long RECOMPUTE_INTERVAL_MS = 400L;

    /**
     * Skip a recorded stop when waiting for it costs more than this much longer
     * than simply carrying on round the loop.
     */
    private static final double SKIP_WAIT_SECONDS = 8.0;

    /** A committed stop is dropped only once the player is this close to it. */
    private static final double REACHED_DISTANCE = 3.5;

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

        List<Stop> result = new ArrayList<>();
        int size = recorded.size();
        int start = recorded.nearestIndex(playerPos.x, playerPos.y, playerPos.z);

        Vec3 cursor = playerPos;
        double clock = 0.0;
        int order = 1;

        // One full lap at most, so a loop where everything is regrowing still
        // terminates instead of spinning.
        for (int step = 0; step < size && result.size() < config.routeLength; step++) {
            RecordedRoute.Point point = recorded.points.get((start + step) % size);
            BlockPos base = new BlockPos(point.x, point.y, point.z);
            TreeRegenTracker.Tree tree = byBase.get(base);

            BlockPos center = tree != null
                    ? tree.getTarget()
                    : base.above(config.treeCenterHeight);

            double travel = cursor.distanceTo(Vec3.atCenterOf(center)) / TRAVEL_BLOCKS_PER_SECOND;
            double readyAt = readySecondsFromNow(tree);
            double arrival = Math.max(clock + travel, readyAt);

            // Standing around for a stop that is far from ready wastes more time
            // than carrying on and catching it next lap.
            boolean wouldWait = arrival - (clock + travel) > SKIP_WAIT_SECONDS;
            if (wouldWait && result.size() + (size - step - 1) >= 1) {
                continue;
            }

            result.add(new Stop(tree, center, Math.max(0.0, arrival), order++));
            cursor = Vec3.atCenterOf(center);
            clock = arrival;
        }

        // Everything is regrowing: show the loop in order anyway so the path is
        // still visible while waiting.
        if (result.isEmpty()) {
            for (int step = 0; step < size && result.size() < config.routeLength; step++) {
                RecordedRoute.Point point = recorded.points.get((start + step) % size);
                BlockPos base = new BlockPos(point.x, point.y, point.z);
                TreeRegenTracker.Tree tree = byBase.get(base);
                BlockPos center = tree != null ? tree.getTarget() : base.above(config.treeCenterHeight);
                result.add(new Stop(tree, center, readySecondsFromNow(tree), result.size() + 1));
            }
        }
        return result;
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
            // Its wood is gone, so there is nothing left to chop there. Release
            // it immediately rather than holding the player at an empty stump:
            // the tree may well be felled from several blocks away, and waiting
            // to be stood next to it is what made the route stall.
            if (tree.isDowned()) {
                continue;
            }
            if (i == 0 && playerPos.distanceTo(Vec3.atCenterOf(tree.getTarget())) < REACHED_DISTANCE) {
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
                        / TRAVEL_BLOCKS_PER_SECOND;
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
            double travel = walker.distanceTo(Vec3.atCenterOf(center)) / TRAVEL_BLOCKS_PER_SECOND;
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
        lastComputed = 0L;
    }
}
