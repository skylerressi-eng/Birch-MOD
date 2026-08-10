package com.birchmod.route;

import java.util.ArrayList;
import java.util.List;

/**
 * A foraging loop the player walked and recorded, stored as the ordered
 * positions of the trees they actually chopped.
 *
 * Positions are plain ints rather than BlockPos so the whole thing serialises
 * to JSON without a custom adapter.
 */
public final class RecordedRoute {

    /** One recorded tree, by the base position of its trunk. */
    public static final class Point {
        public int x;
        public int y;
        public int z;

        public Point() {
        }

        public Point(int x, int y, int z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }

        public double distanceTo(Point other) {
            double dx = x - other.x;
            double dy = y - other.y;
            double dz = z - other.z;
            return Math.sqrt(dx * dx + dy * dy + dz * dz);
        }
    }

    public String name = "";
    public List<Point> points = new ArrayList<>();
    public long recordedAt = 0L;

    public RecordedRoute() {
    }

    public RecordedRoute(String name) {
        this.name = name;
        this.recordedAt = System.currentTimeMillis();
    }

    public int size() {
        return points == null ? 0 : points.size();
    }

    /**
     * Trees this close together are the same tree.
     *
     * A trunk's base can be re-detected a block off after it regrows, so exact
     * coordinates are not a reliable identity.
     */
    public static final double SAME_TREE_DISTANCE = 2.0;

    /** Whether this loop already visits the tree at these coordinates. */
    public boolean contains(int x, int y, int z) {
        if (points == null) {
            return false;
        }
        for (Point point : points) {
            if (near(point, x, y, z)) {
                return true;
            }
        }
        return false;
    }

    private static boolean near(Point point, int x, int y, int z) {
        double dx = point.x - x;
        double dy = point.y - y;
        double dz = point.z - z;
        return Math.sqrt(dx * dx + dy * dy + dz * dz) <= SAME_TREE_DISTANCE;
    }

    /**
     * Drop repeat visits, keeping the first of each.
     *
     * A loop that lists the same tree twice draws two markers on it joined by a
     * line, which reads as a step in the route when it is the same stop. Routes
     * recorded before this was enforced still carry the repeats, so they are
     * repaired when loaded rather than left to misbehave.
     *
     * @return how many repeats were removed
     */
    public int dedupe() {
        if (points == null || points.isEmpty()) {
            return 0;
        }
        List<Point> unique = new ArrayList<>(points.size());
        for (Point point : points) {
            boolean seen = false;
            for (Point kept : unique) {
                if (near(kept, point.x, point.y, point.z)) {
                    seen = true;
                    break;
                }
            }
            if (!seen) {
                unique.add(point);
            }
        }
        int removed = points.size() - unique.size();
        points = unique;
        return removed;
    }

    /**
     * Total walking distance for one full lap, including the hop from the last
     * tree back to the first — a foraging route is a loop, not a line.
     */
    public double loopDistance() {
        if (size() < 2) {
            return 0.0;
        }
        double total = 0.0;
        for (int i = 0; i < points.size(); i++) {
            total += points.get(i).distanceTo(points.get((i + 1) % points.size()));
        }
        return total;
    }

    /** The index of the recorded stop closest to a position. */
    public int nearestIndex(double x, double y, double z) {
        int best = 0;
        double bestDistSq = Double.MAX_VALUE;
        for (int i = 0; i < points.size(); i++) {
            Point p = points.get(i);
            double dx = p.x - x;
            double dy = p.y - y;
            double dz = p.z - z;
            double distSq = dx * dx + dy * dy + dz * dz;
            if (distSq < bestDistSq) {
                bestDistSq = distSq;
                best = i;
            }
        }
        return best;
    }
}
