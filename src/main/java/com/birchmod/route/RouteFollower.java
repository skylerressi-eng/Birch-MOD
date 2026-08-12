package com.birchmod.route;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import com.birchmod.config.BirchConfig;
import com.birchmod.tracking.TreeRegenTracker;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/**
 * Walks a recorded route in the order it was recorded.
 *
 * <h2>The contract</h2>
 * A recorded route is a decision you made with your feet, and this follows it
 * literally. There is exactly one reason to move on from a stop: <b>it has no
 * wood left on it.</b> Not proximity — walking up to a tree does not chop it.
 * Not the clock — a tree with logs still on it is worth finishing whatever the
 * arithmetic says. Not a better-looking neighbour — you did not ask for a
 * different route.
 *
 * That single rule is what fixes the two things that made the old follower feel
 * broken: it abandoned half-cleared trunks the moment the tracked column
 * emptied, and it re-derived its position in the loop from whichever stop was
 * nearest, so the route reshuffled under the player as they walked.
 *
 * <h2>Where it does decide</h2>
 * Felled stops are stepped over, because there is nothing there to chop and
 * standing at a stump is not following the route either. If every stop is
 * felled — you have out-run the whole grove — it parks on the one that comes
 * back first, so the wait happens somewhere useful.
 *
 * With {@link BirchConfig#strictRoute} off, a felled stop hands over to the
 * cheapest stop to reach rather than the next one in order. Even then, wood is
 * never walked away from.
 */
public final class RouteFollower {

    /**
     * Beyond this from the held stop, the player has gone somewhere else
     * entirely — warped, or walked off the island — and picking the loop up
     * where they are beats marching them back.
     */
    private static final double REJOIN_DISTANCE = 96.0;

    private final TreeRegenTracker tracker;

    /** Where round the loop we are. Held until there is cause to move. */
    private int index = -1;
    private String routeName = null;

    public RouteFollower(TreeRegenTracker tracker) {
        this.tracker = tracker;
    }

    /** Forget our place, e.g. after switching routes. */
    public void reset() {
        index = -1;
        routeName = null;
    }

    /** Which recorded stop is currently being worked, or -1 before we start. */
    public int getIndex() {
        return index;
    }

    public List<Stop> plan(RecordedRoute recorded, Vec3 playerPos, int wanted) {
        int size = recorded.size();
        if (size == 0) {
            return List.of();
        }

        TreeSight.Live[] live = new TreeSight.Live[size];
        for (int i = 0; i < size; i++) {
            RecordedRoute.Point point = recorded.points.get(i);
            live[i] = TreeSight.resolve(tracker, point.x, point.y, point.z);
        }

        rejoinIfLost(recorded, playerPos, live);

        boolean[] hasWood = new boolean[size];
        double[] ready = new double[size];
        for (int i = 0; i < size; i++) {
            hasWood[i] = live[i].hasWood();
            ready[i] = live[i].readySeconds();
        }

        if (BirchConfig.get().strictRoute) {
            index = Advance.inOrder(index, hasWood, ready);
        } else {
            index = Advance.toCheapest(index, hasWood, ready, arrivalCosts(live, playerPos));
        }

        return emit(live, size, playerPos, wanted);
    }

    /** Seconds to be chopping at each stop, waiting for regrowth included. */
    private static double[] arrivalCosts(TreeSight.Live[] live, Vec3 playerPos) {
        double[] cost = new double[live.length];
        for (int i = 0; i < live.length; i++) {
            cost[i] = Math.max(
                    TreeSight.approachSeconds(playerPos, live[i].center()),
                    live[i].readySeconds());
        }
        return cost;
    }

    /**
     * Pick a place in the loop only when we genuinely do not have one.
     *
     * Re-deriving it from proximity on every recompute is what made the route
     * shuffle: walking between two stops flipped which was nearest, and the
     * whole loop re-based around it several times a second.
     */
    private void rejoinIfLost(RecordedRoute recorded, Vec3 playerPos, TreeSight.Live[] live) {
        boolean lost = index < 0
                || index >= live.length
                || !Objects.equals(recorded.name, routeName);

        if (!lost) {
            Vec3 held = Vec3.atCenterOf(live[index].center());
            lost = playerPos.distanceTo(held) > REJOIN_DISTANCE;
        }

        if (lost) {
            index = recorded.nearestIndex(playerPos.x, playerPos.y, playerPos.z);
            routeName = recorded.name;
        }
    }

    /**
     * The stops to show, in recorded order from wherever we are.
     *
     * Arrival times chain: each stop is timed from the previous one, using the
     * leg times measured from your own foraging where they exist.
     */
    private List<Stop> emit(TreeSight.Live[] live, int size, Vec3 playerPos, int wanted) {
        int lookahead = Math.min(size, Math.max(1, wanted));
        List<Stop> stops = new ArrayList<>(lookahead);
        List<BlockPos> shown = new ArrayList<>(lookahead);

        double clock = 0.0;
        int order = 1;
        BlockPos previous = null;

        for (int step = 0; step < size && stops.size() < lookahead; step++) {
            TreeSight.Live current = live[(index + step) % size];

            // Two recorded points can land on one trunk — the tree was felled,
            // regrew a block over, and got recorded again at the new spot.
            // Drawing both puts two markers on one tree with a line running
            // between them, which reads as a step in the route when it is the
            // same stop.
            if (shown.contains(current.base())) {
                continue;
            }
            shown.add(current.base());

            double travel = previous == null
                    ? TreeSight.approachSeconds(playerPos, current.center())
                    : TreeSight.travelSeconds(previous, current.base());

            double arrival = Math.max(clock + travel, current.readySeconds());

            stops.add(new Stop(current.tree(), current.base(), current.center(),
                    Math.max(0.0, arrival), order++, current.woodLeft(), current.unfinished()));
            previous = current.base();
            clock = arrival;
        }
        return stops;
    }
}
