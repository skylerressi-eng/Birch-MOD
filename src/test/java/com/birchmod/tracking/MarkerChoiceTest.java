package com.birchmod.tracking;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Which log a marker lands on.
 *
 * The complaint this answers is "the marker is on a leaf". It never was — it was
 * on a real log two blocks up a trunk with canopy packed round it, so what you
 * saw was foliage with a green box buried inside. The fix was to prefer a log
 * with open air beside it, and these checks pin that preference down in the one
 * place both the tracker and the world-reading path now ask.
 */
class MarkerChoiceTest {

    /** A world made only of the solid blocks you put in it. */
    private static MarkerChoice.Solid world(Set<Long> solid) {
        return (x, y, z) -> solid.contains(BlockPos.asLong(x, y, z));
    }

    private static Set<Long> blocks(int[]... positions) {
        Set<Long> set = new HashSet<>();
        for (int[] p : positions) {
            set.add(BlockPos.asLong(p[0], p[1], p[2]));
        }
        return set;
    }

    @Test
    @DisplayName("exposure counts the open sides, out of four")
    void exposureCounts() {
        assertEquals(4, MarkerChoice.exposure(world(blocks()), 0, 70, 0),
                "nothing around it is wide open");

        assertEquals(0, MarkerChoice.exposure(world(blocks(
                new int[]{1, 70, 0}, new int[]{-1, 70, 0},
                new int[]{0, 70, 1}, new int[]{0, 70, -1})), 0, 70, 0),
                "walled in on all four sides");

        assertEquals(2, MarkerChoice.exposure(world(blocks(
                new int[]{1, 70, 0}, new int[]{0, 70, 1})), 0, 70, 0));
    }

    @Test
    @DisplayName("blocks above and below do not affect exposure")
    void onlySidesCount() {
        // A log in a trunk has logs above and below it and is still perfectly
        // visible; only what is beside it can hide it.
        assertEquals(4, MarkerChoice.exposure(world(blocks(
                new int[]{0, 71, 0}, new int[]{0, 69, 0})), 0, 70, 0));
    }

    @Test
    @DisplayName("with nothing to read the world with, everything is open")
    void noSamplerMeansOpen() {
        assertEquals(4, MarkerChoice.exposure(null, 0, 70, 0),
                "a guess beats refusing to place a marker at all");
    }

    /** The whole point: a visible log beats a buried one at the same height. */
    @Test
    @DisplayName("an exposed log outscores one packed in canopy")
    void exposureDominates() {
        Set<Long> canopy = blocks(
                new int[]{1, 72, 0}, new int[]{-1, 72, 0},
                new int[]{0, 72, 1}, new int[]{0, 72, -1});
        MarkerChoice.Solid solid = world(canopy);

        int buried = MarkerChoice.score(0, 72, 0, 72, 0, 0, solid);
        int clear = MarkerChoice.score(0, 71, 0, 72, 0, 0, solid);

        assertTrue(clear < buried,
                "clear log scored " + clear + ", buried one " + buried);
    }

    @Test
    @DisplayName("the trunk is preferred over a branch at the same height")
    void trunkBeatsBranch() {
        MarkerChoice.Solid open = world(blocks());
        int trunk = MarkerChoice.score(0, 72, 0, 72, 0, 0, open);
        int branch = MarkerChoice.score(1, 72, 1, 72, 1, 1, open);
        assertTrue(trunk < branch, "trunk " + trunk + " should beat branch " + branch);
    }

    @Test
    @DisplayName("height settles ties between equally good logs")
    void heightBreaksTies() {
        MarkerChoice.Solid open = world(blocks());
        int atDesired = MarkerChoice.score(0, 72, 0, 72, 0, 0, open);
        int twoAbove = MarkerChoice.score(0, 74, 0, 72, 0, 0, open);
        assertTrue(atDesired < twoAbove);
    }

    /**
     * Exposure must outweigh height, or the fix does nothing: the buried log is
     * usually the one nearest the height you asked for.
     */
    @Test
    @DisplayName("visibility outweighs being at exactly the right height")
    void visibilityBeatsHeight() {
        Set<Long> canopy = blocks(
                new int[]{1, 72, 0}, new int[]{-1, 72, 0},
                new int[]{0, 72, 1}, new int[]{0, 72, -1});
        MarkerChoice.Solid solid = world(canopy);

        int buriedAtTheRightHeight = MarkerChoice.score(0, 72, 0, 72, 0, 0, solid);
        int clearButThreeLower = MarkerChoice.score(0, 69, 0, 72, 0, 0, solid);

        assertTrue(clearButThreeLower < buriedAtTheRightHeight,
                "a visible log three blocks off should still win: "
                        + clearButThreeLower + " vs " + buriedAtTheRightHeight);
    }

    @Test
    @DisplayName("the scan window reaches below a trunk's base")
    void windowLooksDownhill() {
        assertTrue(MarkerChoice.BELOW > 0,
                "logs lying downhill inside a footprint were invisible without this");
        assertTrue(MarkerChoice.ABOVE >= 12, "a full trunk has to fit in the window");
    }

    @Test
    @DisplayName("scores never go negative, so comparisons stay sane")
    void scoresAreNonNegative() {
        MarkerChoice.Solid open = world(blocks());
        for (int dy = -MarkerChoice.BELOW; dy < MarkerChoice.ABOVE; dy++) {
            for (int dx = -2; dx <= 2; dx++) {
                for (int dz = -2; dz <= 2; dz++) {
                    int score = MarkerChoice.score(dx, 70 + dy, dz, 72, dx, dz, open);
                    assertTrue(score >= 0, "score " + score + " at dy=" + dy);
                }
            }
        }
    }
}
