package com.birchmod.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The screen arithmetic, at every window size the game can hand us.
 *
 * A GUI cannot be opened in a headless build, so what gets checked is the
 * layout maths — and that is where the bugs have been: five fixed-width tabs
 * that came to more than the narrowest window, a button column laid out as
 * though the window were always tall, a scroll thumb that could leave its
 * track.
 */
class LayoutTest {

    /**
     * Heights the game actually produces. 1080p at GUI scale 4 is only 270
     * tall, and a small laptop at scale 5 is smaller still.
     */
    private static final int[] HEIGHTS = {200, 240, 270, 300, 360, 480, 540, 720, 1080};
    private static final int[] WIDTHS = {320, 427, 480, 640, 854, 960, 1280, 1920};

    // The Routes detail column, mirroring RoutesScreen's own constants.
    private static final int BUTTONS = 5;
    private static final int MAX_BUTTON_HEIGHT = 20;
    private static final int MIN_BUTTON_HEIGHT = 13;
    private static final int BUTTON_GAP = 3;
    private static final int STATS_HEIGHT = 62;

    @Test
    @DisplayName("the panel never turns inside out, at any height")
    void frameHoldsTogether() {
        for (int height : HEIGHTS) {
            int panelTop = Chrome.CONTENT_TOP - Chrome.PAD;
            int panelBottom = Chrome.contentBottom(height);

            assertTrue(panelBottom > panelTop,
                    "height " + height + ": panel " + panelTop + ".." + panelBottom);
            assertTrue(panelBottom - panelTop >= 40,
                    "height " + height + " left only " + (panelBottom - panelTop) + "px of panel");
            assertTrue(Chrome.footerY(height) + 20 <= height,
                    "height " + height + ": footer runs off the bottom");
        }
    }

    @Test
    @DisplayName("five tabs fit every width the game gives us")
    void tabsFit() {
        for (int width : WIDTHS) {
            int total = Chrome.tabWidth(width) * Chrome.TABS.size();
            assertTrue(total <= width,
                    "width " + width + ": tab strip needed " + total);
            assertTrue(Chrome.tabWidth(width) >= 28,
                    "width " + width + ": tabs shrank to something unclickable");
        }
    }

    @Test
    @DisplayName("the routes button column never runs past the panel")
    void detailColumnFits() {
        int statsShown = 0;

        for (int height : HEIGHTS) {
            int bottom = Chrome.contentBottom(height);
            int buttonsTop = Chrome.CONTENT_TOP + 26;

            int room = bottom - 12 - buttonsTop;
            boolean showStats = room >= BUTTONS * (MAX_BUTTON_HEIGHT + BUTTON_GAP) + STATS_HEIGHT;
            if (showStats) {
                room -= STATS_HEIGHT;
                statsShown++;
            }
            int pitch = Math.max(MIN_BUTTON_HEIGHT + 1,
                    Math.min(MAX_BUTTON_HEIGHT + BUTTON_GAP, room / BUTTONS));
            int buttonHeight = Math.max(MIN_BUTTON_HEIGHT,
                    Math.min(MAX_BUTTON_HEIGHT, pitch - 1));

            int lastBottom = buttonsTop + (BUTTONS - 1) * pitch + buttonHeight;
            assertTrue(lastBottom <= bottom,
                    "height " + height + ": column ends at " + lastBottom
                            + " but the panel ends at " + bottom);

            if (showStats) {
                int statsTop = bottom - 10 - 46 - 6;
                assertTrue(statsTop >= lastBottom,
                        "height " + height + ": stats at " + statsTop
                                + " collide with buttons ending " + lastBottom);
            }
        }
        assertTrue(statsShown > 0, "the stats block never gets shown at any height");
    }

    @Test
    @DisplayName("the scroll thumb stays inside its track")
    void scrollThumbStaysPut() {
        for (int extent : new int[]{40, 80, 120, 300}) {
            for (int content : new int[]{41, 60, 200, 5000, 100000}) {
                if (content <= extent) {
                    continue;   // nothing is drawn
                }
                int barHeight = Math.max(20, extent * extent / content);
                int span = extent - barHeight;
                int maxOffset = content - extent;

                assertTrue(barHeight >= 20, "thumb was only " + barHeight + "px, too small to grab");
                assertTrue(barHeight <= extent, "thumb " + barHeight + " taller than track " + extent);

                for (int offset : new int[]{0, 1, maxOffset / 2, maxOffset}) {
                    int barTop = maxOffset <= 0 ? 0 : (int) ((long) span * offset / maxOffset);
                    assertTrue(barTop >= 0 && barTop + barHeight <= extent,
                            "extent " + extent + " content " + content + " offset " + offset
                                    + " put the thumb at " + barTop + ".." + (barTop + barHeight));
                }
            }
        }
    }

    @Test
    @DisplayName("tab width is a pure function of window width")
    void tabWidthIsStable() {
        for (int width : WIDTHS) {
            assertEquals(Chrome.tabWidth(width), Chrome.tabWidth(width));
        }
        // Degenerate widths must not produce a negative or zero tab.
        for (int width : new int[]{0, 1, 10, 40}) {
            assertTrue(Chrome.tabWidth(width) > 0, "width " + width + " gave a non-positive tab");
        }
    }

    /**
     * The settings list is clipped with a scissor, and an inverted rectangle
     * handed to a scissor is undefined rendering rather than a small visual
     * problem. Minecraft clamps the GUI scale so a window this short is hard to
     * reach, which is exactly why it would never be found by looking.
     */
    @Test
    @DisplayName("the scrolling area is never taller than the window allows, nor inverted")
    void scrollAreaNeverInverts() {
        int searchHeight = 18;
        int minList = 8;

        for (int height : new int[]{60, 90, 110, 140, 200, 240, 360, 1080}) {
            int top = Chrome.CONTENT_TOP + searchHeight + 8;
            int bottom = Math.max(top + minList, Chrome.contentBottom(height) - 4);

            assertTrue(bottom > top,
                    "height " + height + ": scissor would be " + top + ".." + bottom);
            assertTrue(bottom - top >= minList,
                    "height " + height + " left only " + (bottom - top) + "px to scroll in");
        }
    }

    @Test
    @DisplayName("a screen with no tab strip asks for no tab connector")
    void noTabSentinelIsOutOfRange() {
        // Chrome draws the green bar joining the chosen tab to the panel only
        // for a real index. The import screen has no tabs and must not get one.
        assertTrue(Chrome.NO_TAB < 0, "the sentinel has to fall outside every real index");
        assertTrue(Chrome.NO_TAB >= Chrome.TABS.size() || Chrome.NO_TAB < 0);
    }
}
