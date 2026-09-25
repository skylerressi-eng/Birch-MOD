package com.birchmod.hud;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The overlay must stay somewhere you can see it.
 *
 * Its position is saved in scaled pixels, so a corner that was fine on a wide
 * monitor is off the edge in a small window or at a larger GUI scale. An
 * overlay drawn off screen looks exactly like one that has stopped working,
 * which is how this was originally reported.
 */
class HudPositionTest {

    private static final int[] SCREENS = {200, 240, 320, 360, 480, 640, 854, 1080, 1920};
    private static final int[] EXTENTS = {40, 90, 140, 220, 400};
    private static final int[] WANTED = {0, 5, 50, 200, 400, 1500, 100000};

    @Test
    @DisplayName("a position that already fits is left alone")
    void fittingPositionUntouched() {
        assertEquals(5, BirchHud.clampToScreen(5, 120, 640));
        assertEquals(200, BirchHud.clampToScreen(200, 120, 640));
        assertEquals(0, BirchHud.clampToScreen(0, 120, 640));
    }

    @Test
    @DisplayName("a position off the edge is pulled back against it")
    void offscreenIsRecovered() {
        int placed = BirchHud.clampToScreen(600, 120, 640);
        assertTrue(placed + 120 <= 640, "still off the edge at x=" + placed);
        assertTrue(placed > 400, "should sit near the edge, not jump to zero; got " + placed);

        // Saved on a 1920-wide screen, then played in a small window.
        int tiny = BirchHud.clampToScreen(1500, 140, 320);
        assertTrue(tiny >= 0 && tiny + 140 <= 320, "x=" + tiny);
    }

    @Test
    @DisplayName("a panel bigger than the window starts at the edge")
    void oversizePanelStartsAtZero() {
        // Showing the start of it beats nudging it off the opposite edge so the
        // end fits.
        assertEquals(0, BirchHud.clampToScreen(50, 400, 200));
        assertEquals(0, BirchHud.clampToScreen(0, 400, 200));
        assertEquals(0, BirchHud.clampToScreen(50, 100, 0));
    }

    @Test
    @DisplayName("every combination lands on screen and never negative")
    void sweepEveryCombination() {
        for (int screen : SCREENS) {
            for (int extent : EXTENTS) {
                for (int want : WANTED) {
                    int got = BirchHud.clampToScreen(want, extent, screen);
                    assertTrue(got >= 0,
                            "negative position for screen " + screen + " extent " + extent);
                    boolean fits = got + extent <= screen;
                    assertTrue(fits || got == 0,
                            "screen " + screen + " extent " + extent + " wanted " + want
                                    + " landed at " + got + " and hangs off the edge");
                }
            }
        }
    }
}
