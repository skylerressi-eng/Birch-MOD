package com.birchmod.route;

import java.util.ArrayList;
import java.util.List;

import com.birchmod.config.BirchConfig;
import com.birchmod.tracking.TreeRegenTracker;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/**
 * Decides which trees to visit next, in what order.
 *
 * This is only the switch between the two ways that question gets answered:
 * {@link RouteFollower} walks a route you recorded, exactly as you recorded it,
 * and {@link FreePlanner} works one out from whatever is standing nearby when
 * you have not recorded anything. The rules that matter live in those two.
 *
 * It also tells the tracker which trees the route is pointing at, so those get
 * probed on every pass. That is what makes the highlight follow the wood as you
 * break it rather than catching up a beat later.
 */
public final class RouteBuilder {

    private static final long RECOMPUTE_INTERVAL_MS = 400L;

    /** How many stops ahead the tracker watches closely. */
    private static final int FOCUS_STOPS = 3;

    /**
     * Extra stops asked of the planner before duplicates are removed.
     *
     * Ask for exactly ten and you get fewer than ten whenever two of them turn
     * out to be the same tree, which is the case the merge exists to handle.
     * Planning a few spare costs nothing and means the number you asked for is
     * the number you get.
     */
    private static final int DEDUP_MARGIN = 4;

    private final TreeRegenTracker regenTracker;
    private final RouteFollower follower;
    private final FreePlanner planner;

    /** Built on the client thread, read by the render thread. */
    private volatile List<Stop> route = List.of();
    private long lastComputed = 0L;
    private volatile boolean followingRecorded = false;

    public RouteBuilder(TreeRegenTracker regenTracker) {
        this.regenTracker = regenTracker;
        this.follower = new RouteFollower(regenTracker);
        this.planner = new FreePlanner(regenTracker);
    }

    /** Recompute the route if it is stale. Cheap to call every tick. */
    public void update(Vec3 playerPos) {
        long now = System.currentTimeMillis();
        if (now - lastComputed < RECOMPUTE_INTERVAL_MS) {
            return;
        }
        lastComputed = now;

        RecordedRoute active = RouteLibrary.getActive();
        boolean recorded = active != null && active.size() >= RouteLibrary.MIN_STOPS;
        followingRecorded = recorded;

        BirchConfig config = BirchConfig.get();
        int target = targetStops(config.showFullPath, config.routeLength,
                recorded ? active.size() : 0);

        List<Stop> next = trim(distinct(recorded
                ? follower.plan(active, playerPos, target + DEDUP_MARGIN)
                : planner.plan(playerPos, target + DEDUP_MARGIN)), target);

        route = List.copyOf(next);
        regenTracker.setFocus(focusBases(next));
    }

    /**
     * How many stops the route should contain.
     *
     * One place decides this. It used to be decided twice — the planner worked
     * to {@code /route length} and the renderer drew a fixed two regardless —
     * so turning the length up planned further ahead and changed nothing you
     * could see. Whatever this returns is what gets built and what gets drawn.
     *
     * @param loopSize stops on the recorded route being followed, or 0 when
     *                 there is none
     */
    static int targetStops(boolean showFullPath, int routeLength, int loopSize) {
        int wanted = Math.max(1, routeLength);
        if (!showFullPath) {
            return wanted;
        }
        // The whole loop, when there is a loop to show.
        return loopSize > 0 ? loopSize : wanted;
    }

    private static List<Stop> trim(List<Stop> stops, int target) {
        return stops.size() <= target ? stops : stops.subList(0, target);
    }

    /**
     * One tree, one marker.
     *
     * Both planners can hand back two stops standing on the same trunk, for
     * different reasons: a recorded route can list a tree twice because it
     * regrew a block over between recordings, and the tracker can register one
     * physical tree more than once when part of it sits outside the footprint
     * its base claimed. Either way the result on screen is a cluster of boxes
     * on a single tree joined by lines that go nowhere, which reads as several
     * stops when it is one.
     *
     * Filtering here rather than in each planner means it holds however a stop
     * arrived, including from whatever gets written next.
     */
    static List<Stop> distinct(List<Stop> stops) {
        List<Stop> kept = new ArrayList<>(stops.size());

        for (Stop candidate : stops) {
            boolean duplicate = false;
            for (Stop existing : kept) {
                if (isSameTree(existing, candidate)) {
                    duplicate = true;
                    break;
                }
            }
            if (duplicate) {
                continue;
            }
            // Renumber, so the labels stay 1, 2, 3 with no gaps where a
            // duplicate was dropped.
            kept.add(new Stop(candidate.tree(), candidate.base(), candidate.center(),
                    candidate.etaSeconds(), kept.size() + 1,
                    candidate.woodLeft(), candidate.unfinished()));
        }
        return kept;
    }

    private static boolean isSameTree(Stop a, Stop b) {
        // The tracker's own identity is the strongest evidence there is.
        if (a.tree() != null && a.tree() == b.tree()) {
            return true;
        }
        // Otherwise go by where the marker actually lands, since that is what
        // the player sees. Two markers this close are on one tree whatever the
        // bookkeeping says about them.
        return within(a.base(), b.base()) || within(a.center(), b.center());
    }

    /**
     * Whether two positions are on the same tree.
     *
     * Measured across the ground, not through the air. A tree is a vertical
     * thing: the base of a trunk and a log six blocks up it are the same tree,
     * but they are six blocks apart, so a straight-line test called them
     * different stops and drew a box on each — with a line running in and out
     * of both, which is where four lines on one trunk came from.
     *
     * Horizontally, the same tolerance the rest of the mod uses for tree
     * identity. Vertically, anything within a trunk's height, since two trees
     * cannot be stacked on each other.
     */
    private static boolean within(BlockPos a, BlockPos b) {
        if (a == null || b == null) {
            return false;
        }
        double dx = a.getX() - b.getX();
        double dz = a.getZ() - b.getZ();
        if (dx * dx + dz * dz
                > TreeRegenTracker.SAME_TREE_RADIUS * TreeRegenTracker.SAME_TREE_RADIUS) {
            return false;
        }
        return Math.abs(a.getY() - b.getY()) <= TreeRegenTracker.TRUNK_SPAN;
    }

    private static List<BlockPos> focusBases(List<Stop> stops) {
        int count = Math.min(FOCUS_STOPS, stops.size());
        List<BlockPos> bases = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            BlockPos base = stops.get(i).base();
            // The focus set rejects nulls outright, and losing route building
            // to one is a poor trade for a field that should never be null.
            if (base != null) {
                bases.add(base);
            }
        }
        return bases;
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

    /** True while a recorded route is being followed rather than planned. */
    public boolean isFollowingRecorded() {
        return followingRecorded;
    }

    /** Which recorded stop is being worked, or -1 when not following one. */
    public int getRecordedIndex() {
        return followingRecorded ? follower.getIndex() : -1;
    }

    /** Forget where we are, e.g. after switching routes. */
    public void resetCommitment() {
        follower.reset();
        planner.reset();
        lastComputed = 0L;
    }
}
