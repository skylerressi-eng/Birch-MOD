package com.birchmod.route;

import java.util.ArrayList;
import java.util.List;

import com.birchmod.stats.SessionStats;

/**
 * Which part of your route is costing you the time.
 *
 * <h2>Why a lap time is not enough</h2>
 * The lap timer says a loop came in at seventy seconds against a best of
 * sixty-two. That is worth knowing and it is not actionable: eight seconds are
 * missing and it does not say where from. A route of twelve stops has twelve
 * hops in it, and one of them is nearly always the problem — a gap you have to
 * go the long way round, a tree down a drop you have to climb back out of, a
 * stop you keep arriving at before it has regrown.
 *
 * This compares each hop against how long it <em>should</em> take, which the
 * mod already knows two ways: the straight-line distance over your own measured
 * travel speed, and the time that hop has actually taken you across every
 * session. Where the two disagree, the ground does not match the map, and that
 * is the hop to re-record.
 *
 * <h2>What counts as measured</h2>
 * Only hops walked enough times to mean something — {@link TravelGraph} holds
 * the threshold. An unmeasured hop is reported as unmeasured rather than
 * silently falling back to the straight-line figure, because a hop whose
 * estimate equals its prediction looks perfect and is really just unknown, and
 * the one thing a report like this must not do is invent a clean bill of health.
 */
public final class Splits {

    private Splits() {
    }

    /**
     * One hop between consecutive stops.
     *
     * @param from      index of the stop left, 0-based into the recorded route
     * @param to        index of the stop arrived at
     * @param distance  blocks, straight line
     * @param expected  seconds that distance should take at your travel speed
     * @param actual    seconds it has really taken, or {@code expected} if unmeasured
     * @param measured  whether {@code actual} is a measurement or the estimate
     */
    public record Leg(int from, int to, double distance,
                      double expected, double actual, boolean measured) {

        /** Seconds beyond the straight-line prediction. Negative means faster. */
        public double overhead() {
            return actual - expected;
        }

        /**
         * How much slower than predicted, as a multiple.
         *
         * A ratio rather than a difference is what separates a hop worth
         * looking at from one that merely happens to be long: four seconds lost
         * on a forty-second trek across the island is noise, and four seconds
         * lost on a two-second step between neighbours is a wall.
         */
        public double ratio() {
            return expected > 0.0 ? actual / expected : 1.0;
        }
    }

    /**
     * The whole loop, hop by hop.
     *
     * @param legs      every hop, in route order, wrapping from the last stop
     *                  back to the first
     * @param worst     index into {@code legs} of the hop losing the most time,
     *                  or -1 when nothing is measured
     */
    public record Report(List<Leg> legs, int worst) {

        public boolean isEmpty() {
            return legs.isEmpty();
        }

        /** Seconds of travel in one lap, as actually measured where possible. */
        public double totalSeconds() {
            double total = 0.0;
            for (Leg leg : legs) {
                total += leg.actual();
            }
            return total;
        }

        /** Seconds the same lap should take if the ground were flat and clear. */
        public double expectedSeconds() {
            double total = 0.0;
            for (Leg leg : legs) {
                total += leg.expected();
            }
            return total;
        }

        /** How many hops have been walked enough times to be trusted. */
        public int measuredCount() {
            int count = 0;
            for (Leg leg : legs) {
                if (leg.measured()) {
                    count++;
                }
            }
            return count;
        }

        /** The hop losing the most time, or null when nothing is measured. */
        public Leg worstLeg() {
            return worst < 0 || worst >= legs.size() ? null : legs.get(worst);
        }
    }

    /**
     * Not worth naming as the problem hop below this, however bad the ratio.
     *
     * Two stops a stride apart will always read as several times slower than
     * predicted, because the prediction for a two-block hop is a fraction of a
     * second and the real thing includes turning to face the tree. Pointing at
     * that as the route's weak link is noise dressed up as analysis.
     */
    static final double MIN_INTERESTING_OVERHEAD = 1.5;

    /**
     * Build a report for a recorded route.
     *
     * Travel speed comes from {@link SessionStats} where it has been measured,
     * which is the same figure the planner and the ETAs use — so a hop reported
     * here as twice its prediction is twice the prediction the rest of the mod
     * is making, not twice some separate guess.
     */
    public static Report of(RecordedRoute route) {
        if (route == null || route.size() < 2) {
            return new Report(List.of(), -1);
        }
        double speed = RouteLibrary.walkSpeed();
        int size = route.size();

        // Canonicalise once per stop rather than twice per hop: resolving a
        // position to a travel-graph node is a scan of every tree it knows.
        String[] ids = new String[size];
        for (int i = 0; i < size; i++) {
            RecordedRoute.Point point = route.points.get(i);
            ids[i] = TravelGraph.canonical(point.x, point.y, point.z);
        }

        List<Leg> legs = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            int next = (i + 1) % size;
            RecordedRoute.Point a = route.points.get(i);
            RecordedRoute.Point b = route.points.get(next);

            double distance = a.distanceTo(b);
            double expected = speed > 0.0 ? distance / speed : 0.0;
            boolean measured = TravelGraph.isMeasuredById(ids[i], ids[next]);
            double actual = measured
                    ? TravelGraph.legSecondsById(ids[i], ids[next], expected)
                    : expected;

            legs.add(new Leg(i, next, distance, expected, actual, measured));
        }
        return new Report(List.copyOf(legs), worstOf(legs));
    }

    /**
     * Which hop is losing the most time.
     *
     * Ranked by seconds lost, not by ratio: the thing being spent is seconds,
     * and a hop that is 30% slow over twenty seconds costs more of them than
     * one that is 300% slow over one. The ratio only decides whether a hop is
     * eligible at all, via the floor above.
     */
    static int worstOf(List<Leg> legs) {
        int worst = -1;
        double worstOverhead = MIN_INTERESTING_OVERHEAD;

        for (int i = 0; i < legs.size(); i++) {
            Leg leg = legs.get(i);
            if (!leg.measured()) {
                continue;
            }
            if (leg.overhead() > worstOverhead) {
                worstOverhead = leg.overhead();
                worst = i;
            }
        }
        return worst;
    }

    /**
     * The report as lines for chat, one hop per line.
     *
     * The slowest hop gets its own line above the list, so it is named whether
     * or not the list reaches it.
     *
     * @param limit most hops to list before summarising the rest
     */
    public static List<String> describe(RecordedRoute route, int limit) {
        List<String> out = new ArrayList<>();
        if (route == null) {
            out.add("§7No route selected. §f/route use <name>");
            return out;
        }
        Report report = of(route);
        if (report.isEmpty()) {
            out.add("§7\"" + route.name + "\" has too few stops to have legs.");
            return out;
        }

        out.add("§6" + route.name + " §7— " + report.legs().size() + " legs, "
                + report.measuredCount() + " measured");
        out.add("§7Travel: §f" + fmt(report.totalSeconds()) + "s§7 per lap, predicted §f"
                + fmt(report.expectedSeconds()) + "s");

        Leg worst = report.worstLeg();
        if (worst != null) {
            out.add("§cSlowest: §fstop " + (worst.from() + 1) + " → " + (worst.to() + 1)
                    + "§7 costs §c+" + fmt(worst.overhead()) + "s§7 ("
                    + fmt(worst.distance()) + " blocks in " + fmt(worst.actual()) + "s)");
        } else if (report.measuredCount() == 0) {
            out.add("§7No leg has been walked " + TravelGraph.MIN_LEG_SAMPLES
                    + " times yet — forage a few laps and ask again.");
        } else {
            out.add("§aNo leg is losing time. The route walks as fast as it looks.");
        }

        int shown = 0;
        for (Leg leg : report.legs()) {
            if (shown >= limit) {
                out.add("§8…and " + (report.legs().size() - shown) + " more");
                break;
            }
            shown++;
            String mark = leg.measured() ? "§f" : "§8";
            String note = leg.measured()
                    ? (leg.overhead() > MIN_INTERESTING_OVERHEAD
                            ? " §c+" + fmt(leg.overhead()) + "s" : " §a✓")
                    : " §8(estimate)";
            out.add(mark + " " + (leg.from() + 1) + "→" + (leg.to() + 1)
                    + " §7" + fmt(leg.distance()) + "b "
                    + mark + fmt(leg.actual()) + "s" + note);
        }
        return out;
    }

    private static String fmt(double value) {
        return String.format(java.util.Locale.ROOT, "%.1f", value);
    }
}
