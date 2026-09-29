package com.birchmod.route;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * When the route moves on from a stop, and where it moves to.
 *
 * These rules are the whole behaviour anyone feels while foraging, and the bugs
 * they replace were not subtle mistakes in tricky code — they were
 * reasonable-looking rules that meant something else on the ground. So they are
 * plain arithmetic over plain arrays, and exercised directly.
 *
 * <h2>What "has wood" means</h2>
 * A stop out of tracking range reports that it does not know, and the follower
 * has to read that as somewhere to go: it is a tree you recorded and have not
 * reached, and the only way to find out is to walk there. That is right, and it
 * is also why a felled stop that stops being tracked is so damaging — it looks
 * identical to one you have not visited, so the route will not advance off it
 * and its marker stays green over a stump. The fix for that is in the tracker,
 * which keeps route stops tracked; what is pinned here is that the advance rules
 * themselves do the right thing once they are told the truth.
 */
class AdvanceTest {

    @Test
    @DisplayName("a stop with wood is never passed")
    void woodIsNeverAbandoned() {
        boolean[] hasWood = {true, true, true};
        double[] ready = {0, 0, 0};
        assertEquals(0, Advance.inOrder(0, hasWood, ready), "held, not advanced");
        assertEquals(2, Advance.inOrder(2, hasWood, ready));
    }

    @Test
    @DisplayName("a felled stop is stepped over, in order")
    void felledStopsAreSkipped() {
        // Stop 0 chopped, stop 1 chopped, stop 2 standing.
        boolean[] hasWood = {false, false, true};
        double[] ready = {30, 20, 0};
        assertEquals(2, Advance.inOrder(0, hasWood, ready),
                "the green marker has to move off a stump");
    }

    @Test
    @DisplayName("advancing wraps round the loop")
    void advanceWraps() {
        boolean[] hasWood = {true, false, false};
        double[] ready = {0, 15, 15};
        assertEquals(0, Advance.inOrder(1, hasWood, ready), "wrapped back to the one with wood");
    }

    @Test
    @DisplayName("with nothing standing anywhere, it waits where the wait is shortest")
    void parksOnSoonest() {
        boolean[] hasWood = {false, false, false};
        double[] ready = {40, 5, 30};
        assertEquals(1, Advance.inOrder(0, hasWood, ready));
    }

    @Test
    @DisplayName("but it does not hop between stumps whose timers are close")
    void parkingHasHysteresis() {
        double[] ready = {10, 8, 30};
        // Two seconds better is not worth moving the wait for.
        assertEquals(0, Advance.parkOnSoonest(0, ready));

        double[] worthIt = {30, 5, 40};
        assertEquals(1, Advance.parkOnSoonest(0, worthIt));
    }

    @Test
    @DisplayName("an unknown stop counts as somewhere to go")
    void unknownMeansGo() {
        // This is the semantics the tracker bug exploited: a stop that is not
        // tracked cannot be distinguished from one you have never reached, so
        // the route holds on it. Correct for a stop genuinely out of range.
        boolean[] hasWood = {true};
        double[] ready = {0};
        assertEquals(0, Advance.inOrder(0, hasWood, ready));
    }

    @Test
    @DisplayName("relaxed order hands a cleared stop to the cheapest reachable one")
    void cheapestWhenNotStrict() {
        boolean[] hasWood = {false, true, true};
        double[] ready = {20, 0, 0};
        double[] cost = {0, 30, 4};
        assertEquals(2, Advance.toCheapest(0, hasWood, ready, cost));
    }

    @Test
    @DisplayName("relaxed order still will not walk away from wood")
    void relaxedStillKeepsWood() {
        boolean[] hasWood = {true, true};
        double[] ready = {0, 0};
        double[] cost = {99, 1};
        assertEquals(0, Advance.toCheapest(0, hasWood, ready, cost),
                "a half-chopped trunk is not abandoned for a closer one");
    }

    @Test
    @DisplayName("an empty loop is left alone rather than crashing")
    void emptyLoop() {
        assertEquals(3, Advance.inOrder(3, new boolean[0], new double[0]));
        assertEquals(3, Advance.toCheapest(3, new boolean[0], new double[0], new double[0]));
        assertEquals(3, Advance.parkOnSoonest(3, new double[0]));
    }

    @Test
    @DisplayName("a negative or oversized index is folded into the loop")
    void indexIsNormalised() {
        boolean[] hasWood = {false, true, false};
        double[] ready = {5, 0, 5};
        assertEquals(1, Advance.inOrder(-1, hasWood, ready));
        assertEquals(1, Advance.inOrder(7, hasWood, ready));
    }
}
