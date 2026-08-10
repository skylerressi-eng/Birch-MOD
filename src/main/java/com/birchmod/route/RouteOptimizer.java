package com.birchmod.route;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Compiles every recorded route into a single optimised loop.
 *
 * <h2>What "based on all your choices" means here</h2>
 * Each recorded route is evidence about which trees are worth visiting. A tree
 * you included in four of five loops is one you clearly rate; a tree you took
 * once may have been a detour. So every distinct tree across all recordings is
 * scored by how many of those recordings contain it, and that frequency decides
 * which trees make the cut.
 *
 * <h2>Two questions, answered separately</h2>
 * <ol>
 *   <li><b>Which trees?</b> Take the most-favoured k of them.</li>
 *   <li><b>In what order?</b> Nearest-neighbour for a first pass, then 2-opt,
 *       which repeatedly reverses a segment whenever doing so shortens the
 *       loop. That removes the crossings nearest-neighbour leaves behind, and
 *       crossings are exactly what makes a hand-walked route wasteful.</li>
 * </ol>
 *
 * <h2>Choosing k</h2>
 * Throughput is stops per cycle, and the cycle cannot be shorter than the regen
 * time — arriving early just means standing at a stump. Adding trees therefore
 * helps until the lap covers the regen and stops helping after, so rather than
 * guessing, every k from the minimum upward is built and scored, and the best
 * one wins.
 */
public final class RouteOptimizer {

    /** Points closer than this are treated as the same tree. */
    private static final double MERGE_DISTANCE = 2.0;

    /** Cap the search so a huge library cannot stall the command. */
    private static final int MAX_CANDIDATES = 64;

    /** 2-opt passes; the tour converges well before this in practice. */
    private static final int MAX_TWO_OPT_PASSES = 60;

    /** Outcome of a compile, including the numbers behind the choice. */
    public record Result(RecordedRoute route,
                         int sourceRoutes,
                         int uniqueTrees,
                         int consensusTrees,
                         int droppedOneOffs,
                         int chosen,
                         double lapSeconds,
                         double cycleSeconds,
                         double treesPerMinute) {
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

    /** A distinct tree, and how many recorded routes included it. */
    private static final class Candidate {
        final RecordedRoute.Point point;
        int frequency = 1;

        Candidate(RecordedRoute.Point point) {
            this.point = point;
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
        int uniqueTrees = all.size();

        int required = requiredFrequency(routes.size());
        List<Candidate> candidates = new ArrayList<>();
        for (Candidate candidate : all) {
            if (candidate.frequency >= required) {
                candidates.add(candidate);
            }
        }
        // If being strict leaves too little to work with, fall back to the lot
        // rather than refusing to compile anything.
        if (candidates.size() < RouteLibrary.MIN_STOPS) {
            candidates = all;
        }
        int consensusTrees = candidates.size();
        int dropped = uniqueTrees - consensusTrees;

        if (candidates.size() < RouteLibrary.MIN_STOPS) {
            return null;
        }

        // Most-used trees first; ties broken by position so the result is
        // deterministic rather than dependent on map ordering.
        candidates.sort(Comparator
                .comparingInt((Candidate c) -> -c.frequency)
                .thenComparingInt(c -> c.point.x)
                .thenComparingInt(c -> c.point.z)
                .thenComparingInt(c -> c.point.y));

        if (candidates.size() > MAX_CANDIDATES) {
            candidates = new ArrayList<>(candidates.subList(0, MAX_CANDIDATES));
        }

        List<RecordedRoute.Point> bestTour = null;
        double bestScore = -1.0;
        double bestLap = 0.0;
        double bestCycle = 0.0;

        for (int k = RouteLibrary.MIN_STOPS; k <= candidates.size(); k++) {
            List<RecordedRoute.Point> subset = new ArrayList<>(k);
            for (int i = 0; i < k; i++) {
                subset.add(candidates.get(i).point);
            }

            List<RecordedRoute.Point> tour = twoOpt(nearestNeighbourTour(subset));
            double lap = tourLength(tour) / RouteLibrary.walkSpeed();
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
        compiled.points = new ArrayList<>(bestTour);

        return new Result(compiled, routes.size(), uniqueTrees, consensusTrees, dropped,
                bestTour.size(), bestLap, bestCycle, bestScore);
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

    // ---- Tour construction ----

    /** Greedy first pass: always hop to the closest tree not yet visited. */
    private static List<RecordedRoute.Point> nearestNeighbourTour(List<RecordedRoute.Point> points) {
        List<RecordedRoute.Point> remaining = new ArrayList<>(points);
        List<RecordedRoute.Point> tour = new ArrayList<>(points.size());

        RecordedRoute.Point current = remaining.remove(0);
        tour.add(current);

        while (!remaining.isEmpty()) {
            int bestIndex = 0;
            double bestDistance = Double.MAX_VALUE;
            for (int i = 0; i < remaining.size(); i++) {
                double distance = current.distanceTo(remaining.get(i));
                if (distance < bestDistance) {
                    bestDistance = distance;
                    bestIndex = i;
                }
            }
            current = remaining.remove(bestIndex);
            tour.add(current);
        }
        return tour;
    }

    /**
     * Improve a tour by reversing any segment that shortens the loop.
     *
     * Nearest-neighbour tends to strand a far tree and then double back for it,
     * leaving the path crossing itself. Each crossing is walked distance you
     * pay for twice, and reversing the segment between the two crossing edges
     * removes it.
     */
    private static List<RecordedRoute.Point> twoOpt(List<RecordedRoute.Point> tour) {
        int n = tour.size();
        if (n < 4) {
            return tour;
        }
        List<RecordedRoute.Point> best = new ArrayList<>(tour);

        for (int pass = 0; pass < MAX_TWO_OPT_PASSES; pass++) {
            boolean improved = false;

            for (int i = 0; i < n - 1; i++) {
                for (int j = i + 2; j < n; j++) {
                    // Skip the pair that would reverse the whole loop.
                    if (i == 0 && j == n - 1) {
                        continue;
                    }
                    RecordedRoute.Point a = best.get(i);
                    RecordedRoute.Point b = best.get(i + 1);
                    RecordedRoute.Point c = best.get(j);
                    RecordedRoute.Point d = best.get((j + 1) % n);

                    double before = a.distanceTo(b) + c.distanceTo(d);
                    double after = a.distanceTo(c) + b.distanceTo(d);

                    if (after + 1.0e-9 < before) {
                        reverse(best, i + 1, j);
                        improved = true;
                    }
                }
            }
            if (!improved) {
                break;
            }
        }
        return best;
    }

    private static void reverse(List<RecordedRoute.Point> list, int from, int to) {
        while (from < to) {
            RecordedRoute.Point tmp = list.get(from);
            list.set(from, list.get(to));
            list.set(to, tmp);
            from++;
            to--;
        }
    }

    /** Loop length, including the return leg from the last tree to the first. */
    private static double tourLength(List<RecordedRoute.Point> tour) {
        if (tour.size() < 2) {
            return 0.0;
        }
        double total = 0.0;
        for (int i = 0; i < tour.size(); i++) {
            total += tour.get(i).distanceTo(tour.get((i + 1) % tour.size()));
        }
        return total;
    }
}
