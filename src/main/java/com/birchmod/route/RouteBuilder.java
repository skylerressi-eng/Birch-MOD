package com.birchmod.route;

import java.util.ArrayList;
import java.util.List;

import com.birchmod.config.BirchConfig;
import com.birchmod.tracking.TreeRegenTracker;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/**
 * Builds a foraging route through the tracked trees.
 *
 * The route is a greedy nearest-first walk, but "nearest" is measured in
 * <em>time</em> rather than distance: a tree two seconds further away that is
 * ready now beats a closer tree with twenty seconds left on its clock. For each
 * candidate the cost is
 *
 * <pre>travel time to the tree, or its remaining regen — whichever is longer</pre>
 *
 * because you gain nothing by arriving early. Walking is assumed to be a flat
 * speed, so the route degrades gracefully into "nearest tree" when everything
 * is ready, and into "tree that comes back soonest" when nothing is.
 *
 * Recomputed on a short interval rather than every frame — the answer only
 * changes as you move or as trees fall and regrow.
 */
public final class RouteBuilder {

    /** Rough Skyblock travel speed in blocks/second, used to compare with regen. */
    private static final double TRAVEL_BLOCKS_PER_SECOND = 7.0;

    private static final long RECOMPUTE_INTERVAL_MS = 400L;

    /** One stop on the route. */
    public record Stop(TreeRegenTracker.Tree tree, BlockPos center, double etaSeconds, int order) {
    }

    private final TreeRegenTracker regenTracker;

    private List<Stop> route = new ArrayList<>();
    private long lastComputed = 0L;

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
        route = compute(playerPos);
    }

    private List<Stop> compute(Vec3 playerPos) {
        BirchConfig config = BirchConfig.get();

        List<TreeRegenTracker.Tree> candidates = new ArrayList<>(regenTracker.getAllTrees());
        List<Stop> result = new ArrayList<>();

        Vec3 cursor = playerPos;
        double clock = 0.0; // seconds into the route
        int order = 1;
        int limit = Math.min(config.routeLength, candidates.size());

        while (result.size() < limit && !candidates.isEmpty()) {
            TreeRegenTracker.Tree best = null;
            double bestCost = Double.MAX_VALUE;
            double bestArrival = 0.0;

            for (TreeRegenTracker.Tree tree : candidates) {
                BlockPos center = centerOf(tree);
                double distance = cursor.distanceTo(Vec3.atCenterOf(center));
                double travel = distance / TRAVEL_BLOCKS_PER_SECOND;

                // When will this tree actually be choppable, relative to now?
                double readyAt = readySecondsFromNow(tree);

                // You cannot chop before you arrive, nor before it regrows.
                double arrival = Math.max(clock + travel, readyAt);
                double cost = arrival - clock;

                if (cost < bestCost) {
                    bestCost = cost;
                    bestArrival = arrival;
                    best = tree;
                }
            }

            if (best == null) {
                break;
            }

            BlockPos center = centerOf(best);
            result.add(new Stop(best, center, Math.max(0.0, bestArrival), order++));
            candidates.remove(best);
            cursor = Vec3.atCenterOf(center);
            clock = bestArrival;
        }

        return result;
    }

    /** Seconds until this tree is choppable, 0 if it is standing right now. */
    private double readySecondsFromNow(TreeRegenTracker.Tree tree) {
        if (!tree.isDowned()) {
            return 0.0;
        }
        double remaining = regenTracker.getSecondsUntilRegen(tree);
        return Math.max(0.0, remaining);
    }

    /**
     * The block a tracer should point at: the middle of the trunk, so the
     * marker sits inside the tree rather than at its feet.
     */
    public static BlockPos centerOf(TreeRegenTracker.Tree tree) {
        return tree.base.above(BirchConfig.get().treeCenterHeight);
    }

    // ---- Queries ----

    public List<Stop> getRoute() {
        return route;
    }

    /** The stop you should head to right now, or null if the route is empty. */
    public Stop getNext() {
        return route.isEmpty() ? null : route.get(0);
    }

    public boolean isEmpty() {
        return route.isEmpty();
    }
}
