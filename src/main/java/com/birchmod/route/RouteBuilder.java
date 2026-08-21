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

    /**
     * What the overlay needs to know about the route being followed.
     *
     * Published as one immutable value because the alternative is the HUD
     * reaching into the route library while it is being drawn — and the
     * library is a plain map that commands write to on the client thread. A
     * read of a HashMap racing a write to it does not merely return something
     * stale; it can spin.
     */
    public record Following(String name, int stops, int index, double bestLapSeconds) {
        public boolean isActive() {
            return name != null;
        }
    }

    private static final Following NOTHING = new Following(null, 0, -1, -1.0);

    /** Built on the client thread, read by the render thread. */
    private volatile List<Stop> route = List.of();
    private volatile Following following = NOTHING;
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
        following = recorded
                ? new Following(active.name, active.size(), follower.getIndex(), active.bestLapSeconds)
                : NOTHING;
        regenTracker.setFocus(focusBases(next));
    }

    /** A safe snapshot of the route being followed, for the overlay to read. */
    public Following getFollowing() {
        return following;
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
        List<Stop> kept = new ArrayList<>();

        // Wood accumulates into whichever stop it merged with, because merging
        // has to be transitive. A log pile registered as three bases in a row
        // is A next to B next to C, where A and C are nowhere near each other:
        // comparing only against the stop that survived split it back in two,
        // which is precisely the row of markers this is here to prevent.
        List<List<Long>> woodOf = new ArrayList<>();

        for (Stop candidate : stops) {
            List<Long> wood = woodOf(candidate);
            int group = groupFor(candidate, wood, kept, woodOf);

            if (group >= 0) {
                absorb(woodOf.get(group), wood);
                continue;
            }
            // Renumber, so the labels stay 1, 2, 3 with no gaps where a
            // duplicate was dropped.
            kept.add(new Stop(candidate.tree(), candidate.base(), candidate.center(),
                    candidate.etaSeconds(), kept.size() + 1,
                    candidate.woodLeft(), candidate.unfinished()));
            woodOf.add(new ArrayList<>(wood));
        }
        return kept;
    }

    /** Which kept stop this one belongs to, or -1 if it is a tree of its own. */
    private static int groupFor(Stop candidate, List<Long> wood,
                                List<Stop> kept, List<List<Long>> woodOf) {
        for (int i = 0; i < kept.size(); i++) {
            Stop existing = kept.get(i);

            // The tracker's own identity is the strongest evidence there is.
            if (candidate.tree() != null && candidate.tree() == existing.tree()) {
                return i;
            }
            // Then the wood itself. Distance between bases is a guess about
            // where a tree ends; the logs are the tree. A wide Park birch
            // registered as two bases five blocks apart is still one tree when
            // its wood is joined, and guessing from the bases is how it kept
            // being drawn twice.
            if (!wood.isEmpty() && !woodOf.get(i).isEmpty()) {
                if (woodTouches(woodOf.get(i), wood)) {
                    return i;
                }
                continue;
            }
            // Nothing to compare at one end — a felled tree, or a stop out of
            // range — so fall back to where the marker lands, which is all
            // there is to go on.
            if (within(existing.base(), candidate.base())
                    || within(existing.center(), candidate.center())) {
                return i;
            }
        }
        return -1;
    }

    /** Logs a stop is standing on, or nothing when it is not tracked. */
    private static List<Long> woodOf(Stop stop) {
        if (stop.tree() == null) {
            return List.of();
        }
        long[] wood = stop.tree().getWoodPositions();
        List<Long> list = new ArrayList<>(wood.length);
        for (long packed : wood) {
            list.add(packed);
        }
        return list;
    }

    /** Grow a group by what merged into it, bounded so a grove cannot run away. */
    private static void absorb(List<Long> group, List<Long> extra) {
        for (long packed : extra) {
            if (group.size() >= MAX_GROUP_LOGS) {
                return;
            }
            group.add(packed);
        }
    }

    /** Logs remembered per merged group. Well past what one tree holds. */
    private static final int MAX_GROUP_LOGS = 256;

    /**
     * How far apart two logs can stand and still be the same tree.
     *
     * One block of gap, so a trunk and the branch beside it join, and so does a
     * log lying on the ground against the stump it came off. Two trees standing
     * clear of one another do not.
     */
    private static final int WOOD_TOUCH = 2;

    /** Whether any log of one group stands next to any log of the other. */
    private static boolean woodTouches(List<Long> left, List<Long> right) {
        for (long first : left) {
            int ax = BlockPos.getX(first);
            int ay = BlockPos.getY(first);
            int az = BlockPos.getZ(first);

            for (long second : right) {
                if (Math.abs(ax - BlockPos.getX(second)) <= WOOD_TOUCH
                        && Math.abs(ay - BlockPos.getY(second)) <= WOOD_TOUCH
                        && Math.abs(az - BlockPos.getZ(second)) <= WOOD_TOUCH) {
                    return true;
                }
            }
        }
        return false;
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

    /** Forget where we are, e.g. after switching routes. */
    public void resetCommitment() {
        follower.reset();
        planner.reset();
        lastComputed = 0L;
    }
}
