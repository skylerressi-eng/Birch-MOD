package com.birchmod.route;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Compiles every recorded route into a single optimised loop.
 *
 * <h2>What "based on what I actually do" means here</h2>
 * Two different kinds of evidence get used, and they answer different
 * questions.
 *
 * <ul>
 *   <li><b>Which trees are worth visiting</b> comes from your recordings and
 *       from how often you have really felled each tree. A tree you included in
 *       four of five loops is one you rate; a tree you took once was probably a
 *       detour. But a tree you have chopped thirty times is one you rate too,
 *       whatever a single recording happened to contain, so a strong chop
 *       record earns a place on its own.</li>
 *   <li><b>What the order costs</b> comes from {@link TravelGraph}: the time
 *       actually taken between each pair of trees, measured while foraging.
 *       Straight-line distance over a nominal walking speed cannot see the hill
 *       between two trees or the tree that takes four swings, and ordering
 *       built on it optimises a park that does not exist.</li>
 * </ul>
 *
 * <h2>Ordering</h2>
 * Nearest-neighbour from several different starting trees, because the greedy
 * walk is only as good as where it begins; then 2-opt, which reverses any
 * segment that shortens the loop and so removes the crossings greedy leaves
 * behind; then Or-opt, which lifts a single tree out and reinserts it wherever
 * it fits best. The two repair different mistakes — 2-opt untangles, Or-opt
 * relocates the one stop that is simply in the wrong place — and running both
 * to convergence beats either alone.
 *
 * <h2>Choosing how many stops</h2>
 * Throughput is stops per cycle, and the cycle cannot be shorter than the regen
 * time — arriving early just means standing at a stump. Adding trees therefore
 * helps until the lap covers the regen and stops helping after, so rather than
 * guessing, every size from the minimum upward is built and scored, and the
 * best one wins.
 */
public final class RouteOptimizer {

    /** Points closer than this are treated as the same tree. */
    private static final double MERGE_DISTANCE = 2.0;

    /** Cap the search so a huge library cannot stall the command. */
    private static final int MAX_CANDIDATES = 64;

    /** Greedy starting trees tried before improvement. */
    private static final int MAX_STARTS = 12;

    /** Improvement passes; the tour converges well before this in practice. */
    private static final int MAX_IMPROVE_PASSES = 60;

    /**
     * Chops that earn a tree its place regardless of how many recordings list
     * it. Turning up at the same trunk this many times is a clearer statement
     * of intent than any single recording.
     */
    private static final int STRONG_CHOP_COUNT = 8;

    /** Outcome of a compile, including the numbers behind the choice. */
    public record Result(RecordedRoute route,
                         int sourceRoutes,
                         int uniqueTrees,
                         int consensusTrees,
                         int droppedOneOffs,
                         int rescuedByUse,
                         int chosen,
                         double lapSeconds,
                         double cycleSeconds,
                         double treesPerMinute,
                         int measuredLegs,
                         int totalLegs) {

        /** How much of the plan rests on measurement rather than estimate. */
        public double measuredFraction() {
            return totalLegs <= 0 ? 0.0 : (double) measuredLegs / totalLegs;
        }
    }

    /**
     * How many recorded routes must contain a tree before it is trusted.
     *
     * Throughput alone would keep every tree: while the lap is shorter than the
     * regen there is slack in the cycle, so bolting on a distant one-off looks
     * free. It is not what was asked for. A tree taken once out of five loops
     * was a detour, not a choice, and consensus across the recordings is the
     * whole point of compiling them. With only one or two recordings there is
     * nothing to take consensus over, so everything is kept.
     */
    private static int requiredFrequency(int routeCount) {
        return routeCount >= 3 ? 2 : 1;
    }

    private RouteOptimizer() {
    }

    /** A distinct tree, with everything known about how much you use it. */
    private static final class Candidate {
        final RecordedRoute.Point point;
        int frequency = 1;
        String nodeId;
        int chops;

        Candidate(RecordedRoute.Point point) {
            this.point = point;
        }

        /**
         * Recordings count for a lot; a long chop history counts for something.
         * Capped so one heavily-worked tree cannot swamp the ordering.
         */
        double weight() {
            return frequency + Math.min(chops, 40) / 10.0;
        }
    }

    /**
     * Merge every recorded route into one optimised loop.
     *
     * @return the compiled result, or null if there was nothing usable to merge
     */
    public static Result compile(List<RecordedRoute> routes, double regenSeconds, String name) {
        if (routes == null || routes.isEmpty()) {
            return null;
        }

        List<Candidate> all = gather(routes);
        if (all.isEmpty()) {
            return null;
        }
        int uniqueTrees = all.size();

        for (Candidate candidate : all) {
            candidate.nodeId = TravelGraph.canonical(
                    candidate.point.x, candidate.point.y, candidate.point.z);
            candidate.chops = TravelGraph.chopsById(candidate.nodeId);
        }

        int required = requiredFrequency(routes.size());
        int rescued = 0;
        List<Candidate> candidates = new ArrayList<>();

        for (Candidate candidate : all) {
            if (candidate.frequency >= required) {
                candidates.add(candidate);
            } else if (candidate.chops >= STRONG_CHOP_COUNT) {
                // One recording missed it, but you keep going back to it.
                candidates.add(candidate);
                rescued++;
            }
        }
        // If being strict leaves too little to work with, fall back to the lot
        // rather than refusing to compile anything.
        if (candidates.size() < RouteLibrary.MIN_STOPS) {
            candidates = all;
            rescued = 0;
        }
        int consensusTrees = candidates.size();
        int dropped = uniqueTrees - consensusTrees;

        if (candidates.size() < RouteLibrary.MIN_STOPS) {
            return null;
        }

        // Most-used trees first; ties broken by position so the result is
        // deterministic rather than dependent on map ordering.
        candidates.sort(Comparator
                .comparingDouble((Candidate c) -> -c.weight())
                .thenComparingInt(c -> c.point.x)
                .thenComparingInt(c -> c.point.z)
                .thenComparingInt(c -> c.point.y));

        if (candidates.size() > MAX_CANDIDATES) {
            candidates = new ArrayList<>(candidates.subList(0, MAX_CANDIDATES));
        }

        double[][] cost = costMatrix(candidates);

        int[] bestTour = null;
        double bestScore = -1.0;
        double bestLap = 0.0;
        double bestCycle = 0.0;

        for (int k = RouteLibrary.MIN_STOPS; k <= candidates.size(); k++) {
            int[] tour = solve(cost, k);
            double lap = tourCost(tour, cost);
            double cycle = Math.max(lap, Math.max(regenSeconds, 0.001));
            double score = k / cycle * 60.0;

            if (score > bestScore) {
                bestScore = score;
                bestTour = tour;
                bestLap = lap;
                bestCycle = cycle;
            }

            // Once the lap runs well past the regen, every further tree adds
            // more walking than it can pay back, so there is nothing left to
            // find. Stopping here keeps a large library from hitching the
            // client thread while the command runs.
            if (lap > regenSeconds * 1.5 && lap > 0.0) {
                break;
            }
        }

        if (bestTour == null) {
            return null;
        }

        RecordedRoute compiled = new RecordedRoute(name);
        compiled.points = new ArrayList<>(bestTour.length);
        for (int index : bestTour) {
            compiled.points.add(candidates.get(index).point);
        }

        int measured = countMeasuredLegs(bestTour, candidates);

        return new Result(compiled, routes.size(), uniqueTrees, consensusTrees, dropped, rescued,
                bestTour.length, bestLap, bestCycle, bestScore, measured, bestTour.length);
    }

    /** Collect distinct trees across every route, counting how often each appears. */
    private static List<Candidate> gather(List<RecordedRoute> routes) {
        Map<String, Candidate> merged = new LinkedHashMap<>();

        for (RecordedRoute route : routes) {
            if (route == null || route.points == null) {
                continue;
            }
            // Count each tree once per route, so revisiting it within a single
            // loop does not inflate how favoured it looks.
            List<Candidate> seenHere = new ArrayList<>();

            for (RecordedRoute.Point point : route.points) {
                if (point == null) {
                    continue;
                }
                Candidate existing = findNear(merged.values(), point);
                if (existing == null) {
                    Candidate fresh = new Candidate(point);
                    merged.put(key(point), fresh);
                    seenHere.add(fresh);
                } else if (!seenHere.contains(existing)) {
                    existing.frequency++;
                    seenHere.add(existing);
                }
            }
        }
        return new ArrayList<>(merged.values());
    }

    private static Candidate findNear(Iterable<Candidate> existing, RecordedRoute.Point point) {
        for (Candidate candidate : existing) {
            if (candidate.point.distanceTo(point) <= MERGE_DISTANCE) {
                return candidate;
            }
        }
        return null;
    }

    private static String key(RecordedRoute.Point p) {
        return p.x + ":" + p.y + ":" + p.z;
    }

    // ---- Cost ----

    /**
     * Seconds from every tree to every other, measured where the leg has been
     * walked often enough and estimated from distance where it has not.
     *
     * Built once for all candidates. Every subset the search tries is a prefix
     * of the sorted candidate list, so the same matrix serves all of them.
     */
    private static double[][] costMatrix(List<Candidate> candidates) {
        int n = candidates.size();
        double speed = RouteLibrary.walkSpeed();
        double[][] cost = new double[n][n];

        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                double fallback = candidates.get(i).point.distanceTo(candidates.get(j).point) / speed;
                double seconds = TravelGraph.legSecondsById(
                        candidates.get(i).nodeId, candidates.get(j).nodeId, fallback);
                // 2-opt reverses segments, which is only sound when a leg costs
                // the same in both directions, so the graph is read symmetrically.
                cost[i][j] = seconds;
                cost[j][i] = seconds;
            }
        }
        return cost;
    }

    private static int countMeasuredLegs(int[] tour, List<Candidate> candidates) {
        int measured = 0;
        for (int i = 0; i < tour.length; i++) {
            Candidate from = candidates.get(tour[i]);
            Candidate to = candidates.get(tour[(i + 1) % tour.length]);
            if (TravelGraph.isMeasuredById(from.nodeId, to.nodeId)) {
                measured++;
            }
        }
        return measured;
    }

    // ---- Tour construction ----

    /** Best loop over the first {@code k} candidates. */
    private static int[] solve(double[][] cost, int k) {
        int starts = Math.min(MAX_STARTS, k);
        int[] best = null;
        double bestCost = Double.MAX_VALUE;

        for (int s = 0; s < starts; s++) {
            // Spread the starts across the candidates rather than taking the
            // first few, which are all high-weight trees clustered together.
            int start = (int) ((long) s * k / starts);
            int[] tour = nearestNeighbour(cost, k, start);
            improve(tour, cost);

            double total = tourCost(tour, cost);
            if (total < bestCost) {
                bestCost = total;
                best = tour;
            }
        }
        return best;
    }

    /** Greedy first pass: always hop to the cheapest tree not yet visited. */
    private static int[] nearestNeighbour(double[][] cost, int k, int start) {
        boolean[] used = new boolean[k];
        int[] tour = new int[k];

        int current = start;
        used[current] = true;
        tour[0] = current;

        for (int filled = 1; filled < k; filled++) {
            int best = -1;
            double bestCost = Double.MAX_VALUE;
            for (int i = 0; i < k; i++) {
                if (used[i]) {
                    continue;
                }
                if (cost[current][i] < bestCost) {
                    bestCost = cost[current][i];
                    best = i;
                }
            }
            used[best] = true;
            tour[filled] = best;
            current = best;
        }
        return tour;
    }

    /** Run 2-opt and Or-opt alternately until neither can improve the loop. */
    private static void improve(int[] tour, double[][] cost) {
        for (int pass = 0; pass < MAX_IMPROVE_PASSES; pass++) {
            boolean improved = twoOpt(tour, cost);
            improved |= orOpt(tour, cost);
            if (!improved) {
                return;
            }
        }
    }

    /**
     * Reverse any segment that shortens the loop.
     *
     * Nearest-neighbour tends to strand a far tree and then double back for it,
     * leaving the path crossing itself. Each crossing is time you pay twice,
     * and reversing the segment between the two crossing edges removes it.
     */
    private static boolean twoOpt(int[] tour, double[][] cost) {
        int n = tour.length;
        if (n < 4) {
            return false;
        }
        boolean improvedAny = false;

        for (int i = 0; i < n - 1; i++) {
            for (int j = i + 2; j < n; j++) {
                // Skip the pair that would reverse the whole loop.
                if (i == 0 && j == n - 1) {
                    continue;
                }
                int a = tour[i];
                int b = tour[i + 1];
                int c = tour[j];
                int d = tour[(j + 1) % n];

                double before = cost[a][b] + cost[c][d];
                double after = cost[a][c] + cost[b][d];

                if (after + 1.0e-9 < before) {
                    reverse(tour, i + 1, j);
                    improvedAny = true;
                }
            }
        }
        return improvedAny;
    }

    /**
     * Lift one tree out of the loop and put it back wherever it fits best.
     *
     * 2-opt cannot fix a single stop that simply belongs elsewhere: moving it
     * is not a segment reversal, so no reversal makes the loop shorter and the
     * search stops with the detour still in it. Relocating one stop at a time
     * is exactly the move it is missing.
     */
    private static boolean orOpt(int[] tour, double[][] cost) {
        int n = tour.length;
        if (n < 4) {
            return false;
        }
        boolean improvedAny = false;

        for (int from = 0; from < n; from++) {
            int prev = tour[(from - 1 + n) % n];
            int node = tour[from];
            int next = tour[(from + 1) % n];

            // What the loop saves by closing the gap this stop leaves behind.
            double removed = cost[prev][node] + cost[node][next] - cost[prev][next];
            if (removed <= 1.0e-9) {
                continue;
            }

            int bestAt = -1;
            double bestDelta = -1.0e-9;

            for (int at = 0; at < n; at++) {
                if (at == from || at == (from - 1 + n) % n) {
                    continue;
                }
                int left = tour[at];
                int right = tour[(at + 1) % n];
                if (left == node || right == node) {
                    continue;
                }
                double added = cost[left][node] + cost[node][right] - cost[left][right];
                double delta = removed - added;
                if (delta > bestDelta) {
                    bestDelta = delta;
                    bestAt = at;
                }
            }

            if (bestAt >= 0) {
                relocate(tour, from, bestAt);
                improvedAny = true;
            }
        }
        return improvedAny;
    }

    /** Move the stop at {@code from} to sit immediately after the stop at {@code after}. */
    private static void relocate(int[] tour, int from, int after) {
        int node = tour[from];
        int target = tour[after];

        // Close the gap.
        System.arraycopy(tour, from + 1, tour, from, tour.length - from - 1);

        // The target may have shifted down by one when the gap closed.
        int insertAt = 0;
        for (int i = 0; i < tour.length - 1; i++) {
            if (tour[i] == target) {
                insertAt = i + 1;
                break;
            }
        }

        System.arraycopy(tour, insertAt, tour, insertAt + 1, tour.length - insertAt - 1);
        tour[insertAt] = node;
    }

    private static void reverse(int[] tour, int from, int to) {
        while (from < to) {
            int tmp = tour[from];
            tour[from] = tour[to];
            tour[to] = tmp;
            from++;
            to--;
        }
    }

    /** Loop cost, including the return leg from the last tree to the first. */
    private static double tourCost(int[] tour, double[][] cost) {
        if (tour == null || tour.length < 2) {
            return 0.0;
        }
        double total = 0.0;
        for (int i = 0; i < tour.length; i++) {
            total += cost[tour[i]][tour[(i + 1) % tour.length]];
        }
        return total;
    }
}
