package com.birchmod.route;

import java.util.ArrayList;
import java.util.List;

import com.birchmod.config.BirchConfig;
import com.birchmod.tracking.TreeRegenTracker;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/**
 * Plans a route when there is no recorded one to follow.
 *
 * "Nearest" is measured in <em>time</em> rather than distance, since a tree two
 * seconds further away that is ready now beats a closer one with twenty seconds
 * left on its clock, and the time comes from legs you have actually walked
 * wherever enough of them have been recorded.
 *
 * <h2>Finish what you started</h2>
 * A trunk you have chopped into and left standing is worth more than an
 * untouched one: the walk to it is already paid for and only a swing or two
 * remains. Any half-cleared tree within {@link #FINISH_RADIUS} therefore takes
 * the first slot, which is what stops the planner marching you past the logs
 * you just left lying there.
 *
 * <h2>Stability</h2>
 * The route is rebuilt several times a second, and an order recomputed from
 * scratch each time reshuffles whenever two trees are near-equal — which is
 * what made the tracers flick around. Stops already committed to are kept in
 * place, and released only when their wood is gone.
 */
public final class FreePlanner {

    /** Half-cleared trees this close jump the queue. */
    private static final double FINISH_RADIUS = 32.0;

    private final TreeRegenTracker tracker;

    /** Bases of the stops already committed to, in order. */
    private final List<BlockPos> committed = new ArrayList<>();

    public FreePlanner(TreeRegenTracker tracker) {
        this.tracker = tracker;
    }

    public void reset() {
        committed.clear();
    }

    public List<Stop> plan(Vec3 playerPos) {
        List<TreeRegenTracker.Tree> available = tracker.getAllTrees();
        if (available.isEmpty()) {
            return List.of();
        }

        List<TreeRegenTracker.Tree> ordered = keepCommitted(available);
        fillGreedily(ordered, available, playerPos);
        return emit(ordered, playerPos);
    }

    /**
     * Carry over the stops already committed to, in their old order.
     *
     * Felling is the only reason to drop one. Releasing a stop on proximity as
     * well meant the marker jumped to the next tree the moment the player
     * walked up to this one, before they had chopped anything — the route
     * advancing on its own.
     */
    private List<TreeRegenTracker.Tree> keepCommitted(List<TreeRegenTracker.Tree> available) {
        List<TreeRegenTracker.Tree> ordered = new ArrayList<>();

        for (BlockPos base : committed) {
            for (TreeRegenTracker.Tree tree : available) {
                if (!tree.base.equals(base)) {
                    continue;
                }
                if (tree.hasWood()) {
                    ordered.add(tree);
                }
                break;
            }
        }
        available.removeAll(ordered);
        return ordered;
    }

    private void fillGreedily(List<TreeRegenTracker.Tree> ordered,
                              List<TreeRegenTracker.Tree> remaining,
                              Vec3 playerPos) {
        int limit = Math.min(Math.max(1, BirchConfig.get().routeLength),
                ordered.size() + remaining.size());

        while (ordered.size() < limit && !remaining.isEmpty()) {
            boolean first = ordered.isEmpty();
            BlockPos from = first ? null : ordered.get(ordered.size() - 1).getTarget();

            TreeRegenTracker.Tree best = pick(remaining, playerPos, from, first);
            if (best == null) {
                return;
            }
            ordered.add(best);
            remaining.remove(best);
        }
    }

    /**
     * The cheapest tree to take next, in seconds of waiting or walking —
     * whichever of the two you would actually spend.
     */
    private TreeRegenTracker.Tree pick(List<TreeRegenTracker.Tree> candidates,
                                       Vec3 playerPos,
                                       BlockPos from,
                                       boolean firstSlot) {
        List<TreeRegenTracker.Tree> pool = firstSlot
                ? preferUnfinished(candidates, playerPos)
                : candidates;

        TreeRegenTracker.Tree best = null;
        double bestCost = Double.MAX_VALUE;

        for (TreeRegenTracker.Tree tree : pool) {
            double travel = from == null
                    ? TreeSight.approachSeconds(playerPos, tree.getTarget())
                    : TreeSight.travelSeconds(from, tree.base);
            double cost = Math.max(travel, readySeconds(tree));
            if (cost < bestCost) {
                bestCost = cost;
                best = tree;
            }
        }
        return best;
    }

    /**
     * Half-cleared trunks within reach, or every candidate when there are none.
     * Going back for two logs beats walking past them to a full tree.
     */
    private List<TreeRegenTracker.Tree> preferUnfinished(List<TreeRegenTracker.Tree> candidates,
                                                         Vec3 playerPos) {
        List<TreeRegenTracker.Tree> unfinished = new ArrayList<>();

        for (TreeRegenTracker.Tree tree : candidates) {
            if (!tree.isPartiallyChopped() || !tree.hasWood()) {
                continue;
            }
            if (playerPos.distanceTo(Vec3.atCenterOf(tree.base)) <= FINISH_RADIUS) {
                unfinished.add(tree);
            }
        }
        return unfinished.isEmpty() ? candidates : unfinished;
    }

    private double readySeconds(TreeRegenTracker.Tree tree) {
        if (tree.hasWood()) {
            return 0.0;
        }
        return Math.max(0.0, tracker.getSecondsUntilRegen(tree));
    }

    /** Re-time the settled order and record it as the new commitment. */
    private List<Stop> emit(List<TreeRegenTracker.Tree> ordered, Vec3 playerPos) {
        committed.clear();

        List<Stop> stops = new ArrayList<>(ordered.size());
        BlockPos previous = null;
        double clock = 0.0;
        int order = 1;

        for (TreeRegenTracker.Tree tree : ordered) {
            BlockPos center = tree.hasWood() ? tree.getTarget() : tree.base;
            double travel = previous == null
                    ? TreeSight.approachSeconds(playerPos, center)
                    : TreeSight.travelSeconds(previous, tree.base);
            double arrival = Math.max(clock + travel, readySeconds(tree));

            stops.add(new Stop(tree, tree.base, center, Math.max(0.0, arrival), order++,
                    tree.getWoodCount(), tree.isPartiallyChopped()));

            committed.add(tree.base);
            previous = tree.base;
            clock = arrival;
        }
        return stops;
    }
}
