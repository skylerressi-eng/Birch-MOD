package com.birchmod.route;

/**
 * When the route moves on from a stop, and where it moves to.
 *
 * These three rules are the whole behaviour anyone actually feels while
 * foraging, so they are kept here as plain arithmetic over plain arrays —
 * nothing about trees, blocks or the world. That makes them exercisable
 * directly, which matters because the bugs they replace were not subtle
 * mistakes in tricky code; they were reasonable-looking rules that turned out
 * to mean something else on the ground.
 *
 * The arrays are parallel and index a recorded loop:
 * <ul>
 *   <li>{@code hasWood[i]} — something to chop at stop {@code i}. A stop that
 *       is out of tracking range counts as having wood: it is a tree you
 *       recorded and have not reached, and the only way to find out is to go.</li>
 *   <li>{@code ready[i]} — seconds until stop {@code i} has wood again.</li>
 * </ul>
 */
final class Advance {

    /**
     * When everything is regrowing, only move the wait somewhere else if it
     * saves more than this. Without it the marker hops between stumps whose
     * timers are within a second of each other.
     */
    static final double WAIT_SWITCH_MARGIN = 3.0;

    private Advance() {
    }

    /**
     * Step forward through the loop and stop at the first stop with wood.
     *
     * Order is never altered, and a stop with wood on it is never passed. That
     * is the entire promise a recorded route makes.
     */
    static int inOrder(int index, boolean[] hasWood, double[] ready) {
        int size = hasWood.length;
        if (size == 0) {
            return index;
        }
        int at = Math.floorMod(index, size);

        for (int guard = 0; guard < size; guard++) {
            if (hasWood[at]) {
                return at;
            }
            at = (at + 1) % size;
        }
        return parkOnSoonest(at, ready);
    }

    /**
     * A cleared stop hands over to whichever stop costs least to reach, rather
     * than to the next one in the recorded order.
     *
     * Note what this still will not do: if the current stop has wood on it, it
     * is kept, however attractive a neighbour looks. Relaxing the order is not
     * the same as abandoning a half-chopped trunk.
     *
     * @param cost seconds to reach each stop, waiting included
     */
    static int toCheapest(int index, boolean[] hasWood, double[] ready, double[] cost) {
        int size = hasWood.length;
        if (size == 0) {
            return index;
        }
        int at = Math.floorMod(index, size);
        if (hasWood[at]) {
            return at;
        }

        int best = -1;
        double bestCost = Double.MAX_VALUE;
        for (int i = 0; i < size; i++) {
            if (hasWood[i] && cost[i] < bestCost) {
                bestCost = cost[i];
                best = i;
            }
        }
        return best >= 0 ? best : parkOnSoonest(at, ready);
    }

    /**
     * Nothing anywhere has wood — you have out-run the whole grove — so wait
     * where the wait is shortest, but only if that is meaningfully better than
     * waiting where you already are.
     */
    static int parkOnSoonest(int index, double[] ready) {
        int size = ready.length;
        if (size == 0) {
            return index;
        }
        int at = Math.floorMod(index, size);

        int soonest = at;
        double soonestReady = Double.MAX_VALUE;
        for (int i = 0; i < size; i++) {
            if (ready[i] < soonestReady) {
                soonestReady = ready[i];
                soonest = i;
            }
        }
        return soonestReady + WAIT_SWITCH_MARGIN < ready[at] ? soonest : at;
    }
}
