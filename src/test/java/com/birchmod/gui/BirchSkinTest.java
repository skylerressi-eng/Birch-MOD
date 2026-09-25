package com.birchmod.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The bark is generated, so what makes it look like bark is arithmetic, and
 * arithmetic can be checked without a window.
 *
 * The thing that would actually go wrong here is the grain moving. A texture
 * cannot crawl; a procedural surface can, and it would crawl in exactly the
 * situation that is hardest to notice while writing it and most obvious while
 * using it — a list being scrolled.
 */
class BirchSkinTest {

    @Test
    @DisplayName("noise is a pure function of its inputs")
    void noiseIsStable() {
        for (int seed = -50; seed < 50; seed++) {
            for (int i = 0; i < 20; i++) {
                assertEquals(BirchSkin.noise(seed, i), BirchSkin.noise(seed, i),
                        "same inputs must always give the same answer");
            }
        }
    }

    @Test
    @DisplayName("noise stays inside [0, 1) for every seed and index")
    void noiseIsBounded() {
        for (int seed = -2000; seed < 2000; seed += 7) {
            for (int i = 0; i < 40; i++) {
                float v = BirchSkin.noise(seed, i);
                assertTrue(v >= 0.0f && v < 1.0f,
                        "seed " + seed + " index " + i + " gave " + v);
            }
        }
        // The extremes of the int range are where a sign bug would show.
        for (int seed : new int[]{Integer.MIN_VALUE, Integer.MAX_VALUE, 0, -1}) {
            for (int i = 0; i < 40; i++) {
                float v = BirchSkin.noise(seed, i);
                assertTrue(v >= 0.0f && v < 1.0f, "seed " + seed + " gave " + v);
            }
        }
    }

    @Test
    @DisplayName("noise actually varies, rather than returning one value")
    void noiseSpreads()
    {
        int[] buckets = new int[10];
        for (int i = 0; i < 4000; i++) {
            buckets[(int) (BirchSkin.noise(0xBEEF, i) * 10)]++;
        }
        for (int b = 0; b < buckets.length; b++) {
            // A flat hash would pile everything into one bucket; this only
            // asks that every tenth of the range gets used.
            assertTrue(buckets[b] > 150, "bucket " + b + " had " + buckets[b]);
        }
    }

    @Test
    @DisplayName("a widget's grain comes from its label, not its position")
    void seedFollowsTheLabel() {
        assertEquals(BirchSkin.seedFor("Line width"), BirchSkin.seedFor("Line width"),
                "the same control must keep its grain between rebuilds");
        assertNotEquals(BirchSkin.seedFor("Line width"), BirchSkin.seedFor("Marker height"),
                "different controls should not be identical twins");
        // Null and empty must not blow up, and must be stable.
        assertEquals(BirchSkin.seedFor(null), BirchSkin.seedFor(null));
        assertEquals(BirchSkin.seedFor(""), BirchSkin.seedFor(""));
    }

    @Test
    @DisplayName("tints keep the alpha channel and stay in range")
    void tintsAreWellFormed() {
        int[] inputs = {BirchSkin.BARK_LIGHT, BirchSkin.BARK_MID, BirchSkin.BARK_DARK,
                0xFF000000, 0xFFFFFFFF, 0x80123456};

        for (int in : inputs) {
            for (BirchSkin.State state : BirchSkin.State.values()) {
                int out = BirchSkin.tint(in, state);
                assertEquals(in >>> 24, out >>> 24,
                        "alpha must survive the tint for " + state);
                for (int shift : new int[]{16, 8, 0}) {
                    int channel = (out >> shift) & 0xFF;
                    assertTrue(channel >= 0 && channel <= 255,
                            state + " produced channel " + channel);
                }
            }
        }
    }

    @Test
    @DisplayName("hover is brighter than idle and pressed is darker")
    void statesReadInTheRightDirection() {
        int idle = luminance(BirchSkin.tint(BirchSkin.BARK_MID, BirchSkin.State.IDLE));
        int hover = luminance(BirchSkin.tint(BirchSkin.BARK_MID, BirchSkin.State.HOVER));
        int pressed = luminance(BirchSkin.tint(BirchSkin.BARK_MID, BirchSkin.State.PRESSED));
        int disabled = luminance(BirchSkin.tint(BirchSkin.BARK_MID, BirchSkin.State.DISABLED));

        assertTrue(hover > idle, "hover " + hover + " should be brighter than idle " + idle);
        assertTrue(pressed < idle, "pressed " + pressed + " should be darker than idle " + idle);
        assertTrue(disabled < idle, "disabled " + disabled + " should be duller than idle");
    }

    @Test
    @DisplayName("disabled bark loses its warmth")
    void disabledIsDesaturated() {
        int idle = BirchSkin.tint(BirchSkin.BARK_MID, BirchSkin.State.IDLE);
        int off = BirchSkin.tint(BirchSkin.BARK_MID, BirchSkin.State.DISABLED);
        assertTrue(spread(off) < spread(idle),
                "disabled should be closer to grey than live bark");
    }

    @Test
    @DisplayName("mix moves between two colours and clamps at the ends")
    void mixBehaves() {
        int black = 0xFF000000;
        int white = 0xFFFFFFFF;

        assertEquals(0xFF000000, BirchSkin.mix(black, white, 0.0f));
        assertEquals(0xFFFFFFFF, BirchSkin.mix(black, white, 1.0f));
        // Out of range must clamp rather than overshoot into a wrapped channel.
        assertEquals(0xFF000000, BirchSkin.mix(black, white, -3.0f));
        assertEquals(0xFFFFFFFF, BirchSkin.mix(black, white, 9.0f));

        int half = BirchSkin.mix(black, white, 0.5f);
        int channel = half & 0xFF;
        assertTrue(channel > 100 && channel < 155, "halfway was " + channel);
        assertEquals(0xFF, half >>> 24, "mix always returns an opaque colour");
    }

    @Test
    @DisplayName("withAlpha replaces only the alpha channel")
    void alphaIsIsolated() {
        int colour = 0x11223344;
        assertEquals(0xFF223344, BirchSkin.withAlpha(colour, 0xFF));
        assertEquals(0x00223344, BirchSkin.withAlpha(colour, 0x00));
        // Out of range clamps rather than bleeding into the red channel.
        assertEquals(0xFF223344, BirchSkin.withAlpha(colour, 999));
        assertEquals(0x00223344, BirchSkin.withAlpha(colour, -5));
    }

    private static int luminance(int argb) {
        int r = (argb >> 16) & 0xFF;
        int g = (argb >> 8) & 0xFF;
        int b = argb & 0xFF;
        return (r * 30 + g * 59 + b * 11) / 100;
    }

    /** How far apart the channels are — a proxy for saturation. */
    private static int spread(int argb) {
        int r = (argb >> 16) & 0xFF;
        int g = (argb >> 8) & 0xFF;
        int b = argb & 0xFF;
        return Math.max(r, Math.max(g, b)) - Math.min(r, Math.min(b, g));
    }
}
