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
