package com.birchmod.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Nothing the skin draws may leave the surface it belongs to.
 *
 * This is the invariant that matters most in generated graphics and the one
 * least likely to be noticed while writing it. A rectangle a few pixels outside
 * a button does not read as a flaw in the button — it reads as the screen being
 * broken, and it would only show up on some sizes, on some seeds, with the
 * grain that happened to come out of the hash that time.
 *
 * So it is checked across every widget size the screens actually use and a wide
 * sweep of seeds, rather than argued about in a comment.
 */
class BarkGeometryTest {

    /** Widths the screens really produce, from a tab to a full-width footer. */
    private static final int[] WIDTHS =
            {3, 4, 5, 8, 12, 20, 28, 40, 64, 70, 78, 90, 110, 130, 150, 200, 260, 400, 900};
    private static final int[] HEIGHTS = {3, 4, 6, 8, 13, 16, 18, 20, 24, 26, 40, 80};

    @Test
    @DisplayName("every mark stays inside its surface, at every size and seed")
    void marksNeverEscape() {
        int placed = 0;

        for (int w : WIDTHS) {
            for (int h : HEIGHTS) {
                for (int seed = -40; seed <= 40; seed++) {
                    int count = BirchSkin.markCount(w, h);
                    for (int i = 0; i < count; i++) {
                        long mark = BirchSkin.placeMark(seed, i, w, h);
                        if (mark == BirchSkin.NO_MARK) {
                            continue;
                        }
                        placed++;
                        int dx = BirchSkin.markX(mark);
                        int dy = BirchSkin.markY(mark);
                        int len = BirchSkin.markLength(mark);
                        int thick = BirchSkin.markThickness(mark);

                        String where = "w=" + w + " h=" + h + " seed=" + seed + " i=" + i
                                + " -> x " + dx + ".." + (dx + len)
                                + " y " + dy + ".." + (dy + thick);

                        assertTrue(dx >= 1, "left edge escaped: " + where);
                        assertTrue(dy >= 1, "top edge escaped: " + where);
                        assertTrue(dx + len <= w - 1, "right edge escaped: " + where);
                        assertTrue(dy + thick <= h - 1, "bottom edge escaped: " + where);
                        assertTrue(len > 0 && thick > 0, "degenerate mark: " + where);

                        // The lip is drawn one row under a thick mark; it must
                        // fit too, or it is the thing that escapes.
                        if (thick == 2) {
                            assertTrue(dx + len - 1 <= w - 1, "lip escaped: " + where);
                        }
                    }
                }
            }
        }
        assertTrue(placed > 10_000, "only placed " + placed + " marks; the sweep is not covering");
    }

    /**
     * The self-balancing bit: room to start is reduced by the mark's own
     * length, so the right edge lands in the same place whatever the hash
     * produced. If that ever stops holding, marks start clipping on wide
     * surfaces only, which is the hardest case to spot.
     */
    @Test
    @DisplayName("the right edge lands at w-4 regardless of mark length")
    void rightEdgeIsBounded() {
        for (int w : new int[]{20, 40, 78, 150, 400}) {
            int worst = 0;
            for (int seed = 0; seed < 400; seed++) {
                for (int i = 0; i < BirchSkin.markCount(w, 20); i++) {
                    long mark = BirchSkin.placeMark(seed, i, w, 20);
                    if (mark == BirchSkin.NO_MARK) {
                        continue;
                    }
                    worst = Math.max(worst, BirchSkin.markX(mark) + BirchSkin.markLength(mark));
                }
            }
            assertEquals(w - 4, worst,
                    "width " + w + ": furthest right edge was " + worst);
        }
    }

    @Test
    @DisplayName("a surface too small for a mark is simply left plain")
    void tinySurfacesAreSkipped() {
        // Narrower than a mark plus its margins: every placement must decline
        // rather than squeeze something in at a negative offset.
        for (int w : new int[]{3, 4, 5, 6, 7, 8, 9}) {
            for (int i = 0; i < BirchSkin.markCount(w, 20); i++) {
                long mark = BirchSkin.placeMark(1234, i, w, 20);
                if (mark != BirchSkin.NO_MARK) {
                    assertTrue(BirchSkin.markX(mark) + BirchSkin.markLength(mark) <= w - 1,
                            "w=" + w + " squeezed a mark past the edge");
                }
            }
        }
        for (int h : new int[]{3, 4, 5, 6}) {
            for (int i = 0; i < BirchSkin.markCount(150, h); i++) {
                long mark = BirchSkin.placeMark(1234, i, 150, h);
                if (mark != BirchSkin.NO_MARK) {
                    assertTrue(BirchSkin.markY(mark) + BirchSkin.markThickness(mark) <= h - 1,
                            "h=" + h + " squeezed a mark past the edge");
                }
            }
        }
    }

    @Test
    @DisplayName("placement is a pure function, so the grain does not crawl")
    void placementIsStable() {
        for (int seed = -20; seed <= 20; seed++) {
            for (int i = 0; i < BirchSkin.MAX_MARKS; i++) {
                assertEquals(BirchSkin.placeMark(seed, i, 150, 20),
                        BirchSkin.placeMark(seed, i, 150, 20),
                        "the same surface must place the same mark every frame");
            }
        }
    }

    @Test
    @DisplayName("mark count grows with area but is capped")
    void markCountIsSane() {
        assertTrue(BirchSkin.markCount(3, 3) >= 2, "even a tiny surface asks for a couple");
        assertTrue(BirchSkin.markCount(150, 20) >= 2);
        assertEquals(BirchSkin.MAX_MARKS, BirchSkin.markCount(4000, 4000),
                "a huge surface must not ask for thousands of rectangles");

        assertTrue(BirchSkin.markCount(400, 80) >= BirchSkin.markCount(80, 20),
                "a bigger surface should not carry fewer marks");
    }

    @Test
    @DisplayName("marks differ from each other on the same surface")
    void marksAreNotAllIdentical() {
        long first = BirchSkin.placeMark(7, 0, 150, 20);
        int same = 0;
        for (int i = 1; i < BirchSkin.markCount(150, 20); i++) {
            if (BirchSkin.placeMark(7, i, 150, 20) == first) {
                same++;
            }
        }
        assertTrue(same == 0, same + " marks landed exactly on top of the first");
    }
}
