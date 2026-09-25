package com.birchmod.route;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * One tree, one marker.
 *
 * The complaint behind this was "four blue lines on one tree". Both planners
 * can hand back two stops standing on the same trunk — a recorded route can
 * list a tree twice because it regrew a block over, and the tracker can
 * register one physical tree more than once when part of it sits outside the
 * footprint its base claimed. The result on screen was a cluster of boxes on a
 * single tree joined by lines that went nowhere.
 *
 * These stops carry no tracked tree, so merging falls back to where the marker
 * lands, which is the path that has to hold when a tree is out of range.
 */
class DistinctTest {

    private static Stop at(int x, int y, int z, int order) {
        BlockPos base = new BlockPos(x, y, z);
        return new Stop(null, base, base, order * 2.0, order, -1, false);
    }

    private static Stop at(BlockPos base, BlockPos center, int order) {
        return new Stop(null, base, center, order * 2.0, order, -1, false);
    }

    private static List<Stop> distinct(Stop... stops) {
        return RouteBuilder.distinct(new ArrayList<>(List.of(stops)));
    }

    @Test
    @DisplayName("a cluster on one trunk collapses to one stop")
    void clusterCollapses() {
        List<Stop> kept = distinct(
                at(100, 70, 100, 1),
                at(100, 71, 100, 2),
                at(101, 70, 100, 3),
                at(100, 70, 101, 4),
                at(140, 70, 100, 5));

        assertEquals(2, kept.size(), "one trunk plus one real tree is two stops");
        assertEquals(new BlockPos(100, 70, 100), kept.get(0).base(), "the trunk itself is kept");
        assertEquals(new BlockPos(140, 70, 100), kept.get(1).base(), "the separate tree survives");
    }

    @Test
    @DisplayName("a row of trees well apart is left alone")
    void separateTreesSurvive() {
        List<Stop> all = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            all.add(at(100 + i * 12, 70, 100, i + 1));
        }
        assertEquals(6, RouteBuilder.distinct(all).size());
    }

    /**
     * A tree is a vertical thing. The base of a trunk and a log six blocks up
     * it are the same tree but are six blocks apart, so a straight-line test
     * called them different stops and drew a box on each — with a line running
     * in and out of both, which is where four lines on one trunk came from.
     */
    @Test
    @DisplayName("distance is measured across the ground, not through the air")
    void heightDoesNotSplitATrunk() {
        assertEquals(1, distinct(
                at(0, 70, 0, 1),
                at(0, 76, 0, 2)).size(), "base and a log six up are one tree");

        assertEquals(1, distinct(
                at(0, 70, 0, 1),
                at(0, 81, 0, 2)).size(), "the top of a tall trunk is still the same tree");
    }

    @Test
    @DisplayName("markers that land together collapse even from distant bases")
    void markerPositionCounts() {
        // Two recorded points a few blocks apart whose markers resolve to the
        // same log: still one tree.
        List<Stop> kept = distinct(
                at(new BlockPos(0, 70, 0), new BlockPos(2, 72, 0), 1),
                at(new BlockPos(3, 70, 1), new BlockPos(2, 72, 0), 2));
        assertEquals(1, kept.size());
    }

    @Test
    @DisplayName("labels are renumbered with no gaps where a duplicate was dropped")
    void labelsAreContiguous() {
        List<Stop> kept = distinct(
                at(0, 70, 0, 1),
                at(0, 71, 0, 2),
                at(40, 70, 0, 3),
                at(80, 70, 0, 4));

        List<Integer> orders = new ArrayList<>();
        for (Stop s : kept) {
            orders.add(s.order());
        }
        assertEquals(List.of(1, 2, 3), orders);
        assertEquals(1, kept.get(0).order(), "the first stop keeps the marker you are heading to");
    }

    @Test
    @DisplayName("an empty route stays empty and a single stop is untouched")
    void degenerateCases() {
        assertTrue(RouteBuilder.distinct(new ArrayList<>()).isEmpty());
        assertEquals(1, distinct(at(5, 70, 5, 1)).size());
    }
}
