package com.birchmod.gui;

import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * Birch bark, drawn rather than shipped.
 *
 * Every surface in this mod's screens is a piece of birch: pale cream, a faint
 * vertical grain, and the dark horizontal dashes — lenticels — that make birch
 * birch and nothing else. It is all rectangles, which is the whole reason it
 * works. A texture would have to be authored at one size and then stretched
 * over buttons of a dozen different widths, and stretched bark reads as a
 * smear; bark composed at draw time is sharp at any size, on any GUI scale, and
 * adds nothing to the jar.
 *
 * <h2>Why the marks stand still</h2>
 * Lenticel positions come from {@link #noise}, a pure hash, seeded from what a
 * widget <em>is</em> rather than where it happens to be. Seeding from position
 * would make every mark crawl across the bark as a list scrolled, and seeding
 * from a real random number generator would make them boil from frame to
 * frame. A button's grain is therefore its own, the same every time the screen
 * is opened, and it stays put while the list moves under the mouse.
 *
 * <h2>Cost</h2>
 * A piece of bark is a gradient plus a handful of one- and two-pixel
 * rectangles, and nothing here allocates. That matters because the grove behind
 * the screen is a few dozen trunks and it is redrawn every frame.
 */
public final class BirchSkin {

    // ---- Bark ----

    /** Sunlit bark. */
    public static final int BARK_LIGHT = 0xFFF4EFE3;
    /** The body of a trunk. */
    public static final int BARK_MID = 0xFFE0D8C6;
    /** Bark in shadow, and the underside of a curl. */
    public static final int BARK_DARK = 0xFFBFB49C;
    /** The dark dashes. Not black — bark marks are a soft charcoal. */
    public static final int LENTICEL = 0xE0413730;
    /** The pale lip that sits under a peeling mark. */
    public static final int LENTICEL_LIP = 0x60FFFFFF;

    // ---- Foliage and wood ----

    /** Birch leaves in sun; the accent for anything that needs attention. */
    public static final int LEAF = 0xFF8FD36B;
    public static final int LEAF_DEEP = 0xFF4E8C3C;
    /** The far end of a grove, where trunks fade into shade. */
    public static final int GROVE_FAR = 0xFF1B2A1E;
    public static final int GROVE_NEAR = 0xFF0E1611;
    /** Forest floor. */
    public static final int SOIL = 0xFF231B14;

    // ---- Ink ----

    public static final int INK = 0xFF2A241E;
    public static final int INK_SOFT = 0xFF5A5045;
    public static final int INK_ON_DARK = 0xFFF2EDE1;

    private BirchSkin() {
    }

    /** How a piece of bark should look right now. */
    public enum State {
        /** Sitting there. */
        IDLE,
        /** The mouse is over it. */
        HOVER,
        /** Being clicked. */
        PRESSED,
        /** Cannot be used. */
        DISABLED,
        /** Switched on, or the tab you are reading. */
        CHOSEN
    }

    /**
     * A deterministic value in {@code [0, 1)} from a seed and an index.
     *
     * A hash, not a generator: it holds no state, allocates nothing, and gives
     * the same answer for the same pair forever, which is exactly what a
     * texture does and exactly what makes the grain stay still.
     */
    public static float noise(int seed, int index) {
        int h = seed * 0x9E3779B9 + index * 0x85EBCA6B + 0x165667B1;
        h ^= h >>> 15;
        h *= 0xD168AAAD;
        h ^= h >>> 13;
        h *= 0xAF723597;
        h ^= h >>> 16;
        // Top 24 bits, so the low-order noise of the multiply is discarded.
        return (h >>> 8) / (float) (1 << 24);
    }

    /** A stable seed for a widget, from its label rather than its position. */
    public static int seedFor(String label) {
        if (label == null || label.isEmpty()) {
            return 0x5EED;
        }
        int h = 0x811C9DC5;
        for (int i = 0; i < label.length(); i++) {
            h = (h ^ label.charAt(i)) * 0x01000193;
        }
        return h;
    }

    /**
     * Draw a piece of birch bark.
     *
     * @param seed  chooses this surface's grain; see the class notes
     * @param state tints the whole thing, so one surface reads as four
     */
    public static void bark(GuiGraphicsExtractor graphics, int x, int y, int w, int h,
                            int seed, State state) {
        if (w <= 2 || h <= 2) {
            return;
        }

        int top = tint(BARK_LIGHT, state);
        int bottom = tint(BARK_MID, state);
        graphics.fillGradient(x, y, x + w, y + h, top, bottom);

        // A darker foot, because bark curves away from the light at the bottom.
        graphics.fill(x, y + h - 2, x + w, y + h, tint(BARK_DARK, state));

        grain(graphics, x, y, w, h, seed, state);
        lenticels(graphics, x, y, w, h, seed, state);
        edge(graphics, x, y, w, h, state);
    }

    /** Faint vertical streaking, so the bark is not a flat wash. */
    private static void grain(GuiGraphicsExtractor graphics, int x, int y, int w, int h,
                              int seed, State state) {
        int streaks = Math.max(2, w / 22);
        for (int i = 0; i < streaks; i++) {
            int sx = x + 2 + (int) (noise(seed, 100 + i) * (w - 4));
            boolean pale = noise(seed, 200 + i) > 0.5f;
            int colour = pale
                    ? withAlpha(tint(BARK_LIGHT, state), 0x50)
                    : withAlpha(tint(BARK_DARK, state), 0x40);
            graphics.fill(sx, y + 1, sx + 1, y + h - 2, colour);
        }
    }

    /**
     * The dashes.
     *
     * Short, horizontal, and never touching an edge — a mark running off the
     * side of a button reads as a crack in the button rather than as bark. Each
     * gets a pale lip beneath it, which is what gives birch its slightly peeling
     * look and is most of why this reads as bark and not as scratches.
     */
    private static void lenticels(GuiGraphicsExtractor graphics, int x, int y, int w, int h,
                                  int seed, State state) {
        int marks = Math.max(2, (w * h) / 420);
        marks = Math.min(marks, 14);

        int alpha = state == State.DISABLED ? 0x70 : 0xE0;

        for (int i = 0; i < marks; i++) {
            int len = 3 + (int) (noise(seed, 300 + i) * Math.min(w / 3.0f, 14.0f));
            int thick = noise(seed, 400 + i) > 0.72f ? 2 : 1;

            int span = w - 6 - len;
            int room = h - 4 - thick;
            if (span <= 0 || room <= 0) {
                continue;
            }
            int mx = x + 3 + (int) (noise(seed, 500 + i) * span);
            int my = y + 2 + (int) (noise(seed, 600 + i) * room);

            graphics.fill(mx, my, mx + len, my + thick, withAlpha(LENTICEL, alpha));
            // The lip, half a mark long and offset, so it does not read as an
            // outline around the dash.
            if (thick == 2 && my + thick + 1 < y + h - 2) {
                graphics.fill(mx + 1, my + thick, mx + len - 1, my + thick + 1, LENTICEL_LIP);
            }
        }
    }

    /** A border, lit from the top left, plus whatever the state wants to say. */
    private static void edge(GuiGraphicsExtractor graphics, int x, int y, int w, int h,
                            State state) {
        int light = 0x70FFFFFF;
        int dark = 0x50000000;

        if (state == State.PRESSED) {
            // Swap the lighting to sink it into the screen.
            light = 0x50000000;
            dark = 0x60FFFFFF;
        }

        graphics.fill(x, y, x + w, y + 1, light);
        graphics.fill(x, y, x + 1, y + h, light);
        graphics.fill(x, y + h - 1, x + w, y + h, dark);
        graphics.fill(x + w - 1, y, x + w, y + h, dark);

        if (state == State.HOVER) {
            // A rim of leaf green, as though light were coming through canopy.
            outline(graphics, x, y, w, h, withAlpha(LEAF, 0xA0));
        } else if (state == State.CHOSEN) {
            outline(graphics, x, y, w, h, withAlpha(LEAF_DEEP, 0xFF));
            graphics.fill(x + 1, y + h - 3, x + w - 1, y + h - 1, withAlpha(LEAF_DEEP, 0xC0));
        }
    }

    /** A one-pixel frame, drawn as four rectangles. */
    public static void outline(GuiGraphicsExtractor graphics, int x, int y, int w, int h,
                               int colour) {
        graphics.fill(x, y, x + w, y + 1, colour);
        graphics.fill(x, y + h - 1, x + w, y + h, colour);
        graphics.fill(x, y + 1, x + 1, y + h - 1, colour);
        graphics.fill(x + w - 1, y + 1, x + w, y + h - 1, colour);
    }

    /**
     * Shift a bark colour for a state.
     *
     * Hover warms and brightens, pressed darkens, disabled drains the warmth
     * out toward grey — the same surface saying four different things, which is
     * cheaper and more consistent than four sets of colours to keep in step.
     */
    public static int tint(int argb, State state) {
        return switch (state) {
            case IDLE, CHOSEN -> argb;
            case HOVER -> scale(argb, 1.08f, 1.06f, 1.00f);
            case PRESSED -> scale(argb, 0.82f, 0.82f, 0.80f);
            case DISABLED -> desaturate(argb, 0.55f, 0.78f);
        };
    }

    private static int scale(int argb, float rf, float gf, float bf) {
        int a = argb >>> 24;
        int r = clamp((int) (((argb >> 16) & 0xFF) * rf));
        int g = clamp((int) (((argb >> 8) & 0xFF) * gf));
        int b = clamp((int) ((argb & 0xFF) * bf));
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    private static int desaturate(int argb, float amount, float brightness) {
        int a = argb >>> 24;
        int r = (argb >> 16) & 0xFF;
        int g = (argb >> 8) & 0xFF;
        int b = argb & 0xFF;
        int grey = (r * 30 + g * 59 + b * 11) / 100;

        r = clamp((int) ((r + (grey - r) * amount) * brightness));
        g = clamp((int) ((g + (grey - g) * amount) * brightness));
        b = clamp((int) ((b + (grey - b) * amount) * brightness));
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    public static int withAlpha(int argb, int alpha) {
        return (clamp(alpha) << 24) | (argb & 0x00FFFFFF);
    }

    private static int clamp(int v) {
        return v < 0 ? 0 : Math.min(v, 255);
    }

    // ---- The grove ----

    /**
     * A birch wood, receding into shade, behind the whole screen.
     *
     * Three ranks of trunks. The far rank is narrow, close together and tinted
     * most of the way to the background; the near rank is wide, sparse and
     * almost full strength. That difference alone reads as depth — no blur and
     * no transparency tricks, just size, spacing and how far each rank has been
     * mixed toward the colour behind it.
     *
     * The layout is seeded from the window width, so it settles into the same
     * wood every time a screen of that size is opened rather than reshuffling
     * as you move between tabs.
     */
    public static void grove(GuiGraphicsExtractor graphics, int width, int height) {
        graphics.fillGradient(0, 0, width, height, GROVE_FAR, GROVE_NEAR);

        // Canopy: a band of leaf shadow across the top.
        graphics.fillGradient(0, 0, width, Math.max(18, height / 5),
                withAlpha(LEAF_DEEP, 0x90), withAlpha(LEAF_DEEP, 0x00));

        int seed = 0xB17C4 ^ width;
        int floor = height - Math.max(12, height / 8);

        rank(graphics, width, height, floor, seed, 3, 9, 0.80f, 0x55);
        rank(graphics, width, height, floor, seed * 31, 6, 6, 0.55f, 0x33);
        rank(graphics, width, height, floor, seed * 7, 11, 4, 0.28f, 0x22);

        // Forest floor, over the feet of the trunks.
        graphics.fillGradient(0, floor, width, height, withAlpha(SOIL, 0xD0), SOIL);

        // A vignette, so the panel in the middle has something to sit on.
        graphics.fillGradient(0, 0, width, height, 0x00000000, 0x30000000);
    }

    /**
     * One rank of trunks.
     *
     * @param trunkWidth  how wide a trunk in this rank is
     * @param count       how many to place across the window
     * @param strength    how much of the bark colour survives the distance
     * @param markAlpha   how visible the lenticels are at this distance
     */
    private static void rank(GuiGraphicsExtractor graphics, int width, int height, int floor,
                             int seed, int trunkWidth, int count, float strength, int markAlpha) {
        int body = mix(GROVE_FAR, BARK_MID, strength);
        int shade = mix(GROVE_FAR, BARK_DARK, strength);

        for (int i = 0; i < count; i++) {
            // Spread across the window with a nudge, so they are neither a
            // grid nor a clump.
            float slot = (i + 0.5f) / count;
            int x = (int) ((slot + (noise(seed, i) - 0.5f) * 0.7f / count) * width)
                    - trunkWidth / 2;
            if (x + trunkWidth < 0 || x > width) {
                continue;
            }
            // Trunks nearer the camera run off the bottom of the screen.
            int treeTop = -4 + (int) (noise(seed, 50 + i) * 18);
            int treeBottom = floor + (int) (noise(seed, 90 + i) * 8);

            graphics.fill(x, treeTop, x + trunkWidth, treeBottom, body);
            // A shaded side, always the same side, so the whole wood is lit
            // from one direction.
            graphics.fill(x + trunkWidth - Math.max(1, trunkWidth / 3), treeTop,
                    x + trunkWidth, treeBottom, shade);

            trunkMarks(graphics, x, treeTop, trunkWidth, treeBottom - treeTop,
                    seed * 13 + i, markAlpha);
        }
    }

    /** Lenticels on a distant trunk: fewer, fainter, and never full width. */
    private static void trunkMarks(GuiGraphicsExtractor graphics, int x, int y, int w, int h,
                                   int seed, int alpha) {
        if (w < 3 || h < 12) {
            return;
        }
        int marks = Math.max(2, h / 34);
        for (int i = 0; i < marks; i++) {
            int len = Math.max(1, (int) (w * (0.45f + noise(seed, i) * 0.45f)));
            int my = y + 4 + (int) (noise(seed, 60 + i) * (h - 8));
            int mx = x + (int) (noise(seed, 120 + i) * Math.max(1, w - len));
            graphics.fill(mx, my, mx + len, my + 1, withAlpha(LENTICEL, alpha));
        }
    }

    /** Blend {@code b} into {@code a}. {@code amount} 0 keeps a, 1 gives b. */
    public static int mix(int a, int b, float amount) {
        float t = Math.max(0.0f, Math.min(1.0f, amount));
        int ar = (a >> 16) & 0xFF;
        int ag = (a >> 8) & 0xFF;
        int ab = a & 0xFF;
        int br = (b >> 16) & 0xFF;
        int bg = (b >> 8) & 0xFF;
        int bb = b & 0xFF;
        int r = clamp((int) (ar + (br - ar) * t));
        int g = clamp((int) (ag + (bg - ag) * t));
        int bl = clamp((int) (ab + (bb - ab) * t));
        return 0xFF000000 | (r << 16) | (g << 8) | bl;
    }

    /**
     * A plank: the darker wood the screen's panels are cut from.
     *
     * Not bark — a sawn face, so it is grain without lenticels. Panels want to
     * recede; bark wants to be pressed. Using the same surface for both would
     * make the whole screen one texture and nothing would stand out.
     */
    public static void plank(GuiGraphicsExtractor graphics, int x, int y, int w, int h,
                             int seed) {
        if (w <= 2 || h <= 2) {
            return;
        }
        graphics.fillGradient(x, y, x + w, y + h, 0xF02A2318, 0xF01E1811);

        int lines = Math.max(2, h / 26);
        for (int i = 0; i < lines; i++) {
            int ly = y + 2 + (int) (noise(seed, 700 + i) * (h - 4));
            int inset = (int) (noise(seed, 800 + i) * (w / 4.0f));
            graphics.fill(x + inset, ly, x + w - inset, ly + 1, 0x18FFFFFF);
        }
        outline(graphics, x, y, w, h, 0x40000000);
        graphics.fill(x, y, x + w, y + 1, 0x18FFFFFF);
    }
}
