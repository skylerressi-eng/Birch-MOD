package com.birchmod.route;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.birchmod.tracking.TreeRegenTracker;

import net.minecraft.core.BlockPos;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Resolving a recorded coordinate, and what that is allowed to cost.
 *
 * <h2>The expensive part, and why it is not done here</h2>
 * Finding which log a marker belongs on means scanning the trunk's whole
 * footprint — a few hundred block lookups for one stop. Resolving a stop
 * happens for every point on the recorded route, two and a half times a second;
 * drawing one happens for the handful of stops ahead of you. Doing the scan
 * during the first meant a long recording spent almost all of it positioning
 * markers nobody was going to see: a 512-stop route, which the recorder will
 * make, came to the better part of half a million block lookups a second to
 * place thirty-six markers.
 *
 * Nothing the follower decides — has this stop got wood, when is it back, which
 * stop is next — reads the marker at all, so the scan belongs with the drawing.
 * What is pinned here is that separation, because it is invisible: putting the
 * scan back would not change a single number on screen, it would only make the
 * game slower on exactly the long routes where that is hardest to attribute.
 */
class TreeSightTest {

    @Test
    @DisplayName("an unknown stop keeps its recorded position and asks for no scan")
    void unknownStopIsCheap() {
        // Nothing tracked, so this is the out-of-range case — which is every
        // stop on a long route, most of the time.
        TreeSight.Live live = TreeSight.resolve(new TreeRegenTracker(), 100, 70, -40);

        assertFalse(live.markerKnown(),
                "the marker has not been found yet, and saying so is what defers the scan");
        assertEquals(new BlockPos(100, 70, -40), live.base());
        assertEquals(new BlockPos(100, 70, -40), live.center(),
                "the recorded coordinate, not a guess offset above it");
    }

    @Test
    @DisplayName("an unknown stop is somewhere to go, not somewhere cleared")
    void unknownMeansGo() {
        // -1, not 0. Reporting an unexamined stop as cleared is what marched
        // the route past trees it knew nothing about.
        TreeSight.Live live = TreeSight.resolve(new TreeRegenTracker(), 10, 70, 10);
        assertEquals(-1, live.woodLeft());
        assertTrue(live.hasWood());
        assertEquals(0.0, live.readySeconds());
        assertFalse(live.unfinished(), "nothing is known about it, so nothing is half-done");
    }

    @Test
    @DisplayName("a marker already known is handed back without a scan")
    void knownMarkerIsReturnedDirectly() {
        BlockPos chosen = new BlockPos(5, 74, 5);
        TreeSight.Live known = new TreeSight.Live(
                new BlockPos(5, 70, 5), null, chosen, 4, 0.0, true);

        assertEquals(chosen, TreeSight.marker(known));
    }

    @Test
    @DisplayName("a marker not yet known falls back to the base rather than nowhere")
    void unknownMarkerFallsBackToBase() {
        // Outside a game there is no world to read, and the honest answer is
        // the trunk base. Standing on the base is right; an offset above it is
        // a guess that lands in leaves as often as on wood.
        BlockPos base = new BlockPos(5, 70, 5);
        TreeSight.Live unresolved = new TreeSight.Live(base, null, base, -1, 0.0, false);

        assertEquals(base, TreeSight.marker(unresolved));
    }

    @Test
    @DisplayName("hasWood is about having something to chop, not about a count")
    void hasWoodSemantics() {
        BlockPos base = new BlockPos(0, 70, 0);
        assertTrue(new TreeSight.Live(base, null, base, 7, 0.0, true).hasWood(),
                "wood standing");
        assertTrue(new TreeSight.Live(base, null, base, -1, 0.0, false).hasWood(),
                "unknown counts as somewhere to go");
        assertFalse(new TreeSight.Live(base, null, base, 0, 30.0, true).hasWood(),
                "a felled stump is not somewhere to go");
    }
}
