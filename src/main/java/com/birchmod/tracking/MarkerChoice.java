package com.birchmod.tracking;

/**
 * Which log a marker should sit on.
 *
 * <h2>Why this is its own class</h2>
 * Two things pick a marker. {@link TreeRegenTracker} picks one for a tree it is
 * watching, and {@code TreeSight} picks one by reading the world for a recorded
 * stop that is not being watched — out of range, or a fresh login before the
 * sweep has found it. They must agree, because from the player's side they are
 * the same marker on the same tree and whether the mod happens to be tracking
 * it right now is invisible.
 *
 * They did not agree. The tracker learned to prefer a log with open air beside
 * it, which is the fix for markers that appeared to sit "on leaves" — they were
 * really on a log two up a trunk with canopy packed round it. It also learned to
 * look below a trunk's base, for logs lying downhill on a slope. Neither change
 * reached the world-reading path, so a recorded stop out of tracking range could
 * still put its marker inside the canopy, and a comment there claiming it used
 * "the same preference the tracker uses" is what would stop anyone noticing.
 *
 * So the preference lives here, once, and both ask this.
 */
public final class MarkerChoice {

    /**
     * How far below a trunk's base to consider.
     *
     * Ground claimed by a footprint may dip downhill, and a log lying in it is
     * choppable birch that belongs to that tree.
     */
    public static final int BELOW = 3;

    /** How far up a trunk to consider. */
    public static final int ABOVE = 12;

    /** Sides of a block that are open air, out of four. */
    public interface Solid {
        boolean isSolid(int x, int y, int z);
    }

    private MarkerChoice() {
    }

    /**
     * Score a candidate log. Lower is better.
     *
     * Three things, in order of how much they matter. Exposure dominates,
     * because a marker you cannot see is the entire complaint. Then the trunk
     * itself over a branch, because that is where you stand. Then height,
     * because it is the weakest of the three preferences and should only settle
     * ties between logs that are equally visible and equally central.
     *
     * @param desired   the height the marker would ideally sit at
     * @param dx        horizontal offset from the trunk's own column
     * @param dz        the other horizontal offset
     * @param solid     reads the world, or null when nothing can be read
     */
    public static int score(int x, int y, int z, int desired, int dx, int dz, Solid solid) {
        return Math.abs(y - desired)
                + (Math.abs(dx) + Math.abs(dz)) * 8
                + (4 - exposure(solid, x, y, z)) * 10;
    }

    /**
     * How many of a block's four sides are open air.
     *
     * Leaves count as solid, which is the point: a log walled in by canopy is
     * one you cannot see, whether what surrounds it is foliage or stone. With
     * nothing to read the world with, everything is assumed open — an unscored
     * guess beats refusing to place a marker.
     */
    public static int exposure(Solid solid, int x, int y, int z) {
        if (solid == null) {
            return 4;
        }
        int open = 0;
        if (!solid.isSolid(x + 1, y, z)) {
            open++;
        }
        if (!solid.isSolid(x - 1, y, z)) {
            open++;
        }
        if (!solid.isSolid(x, y, z + 1)) {
            open++;
        }
        if (!solid.isSolid(x, y, z - 1)) {
            open++;
        }
        return open;
    }
}
