package com.birchmod.gui;

import java.util.List;
import java.util.function.Consumer;

import com.birchmod.BirchMod;
import com.birchmod.util.Guard;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

/**
 * The frame every screen in this mod shares.
 *
 * Kept in one place so the settings screens and the route screens look like
 * parts of the same thing rather than three interfaces that happen to ship
 * together. Anything that has to line up across screens — where the panel
 * starts, how tall a tab is, what green means — is a constant here rather than
 * a number written out again in each.
 *
 * <h2>The shape of a screen</h2>
 * A header band carrying the name and the version, a row of tabs whose active
 * one is joined to the panel below it by an accent bar, the panel itself, and a
 * footer band with a hint on the left and the way out on the right. The bands
 * are darker than the panel so the panel reads as the thing you are working in
 * and the rest reads as the frame around it.
 */
public final class Chrome {

    public static final int HEADER_HEIGHT = 44;
    public static final int TAB_Y = 24;
    public static final int TAB_HEIGHT = 18;
    public static final int TAB_WIDTH = 78;
    public static final int CONTENT_TOP = HEADER_HEIGHT + 8;
    public static final int FOOTER_HEIGHT = 32;
    public static final int MARGIN = 16;

    /** Rounded-off inset used wherever a panel needs breathing room. */
    public static final int PAD = 8;

    // ---- Palette ----
    // One source of colour: the skin owns the wood and the foliage, and this
    // names the roles the screens ask for. Two palettes drifting apart is how a
    // screen ends up with three different greens in it.

    private static final int RULE = 0x33FFFFFF;

    /** Birch leaves. The accent everywhere, including on markers in the world. */
    public static final int ACCENT = BirchSkin.LEAF;
    private static final int ACCENT_DIM = BirchSkin.withAlpha(BirchSkin.LEAF, 0x66);

    public static final int TEXT = 0xFFF2EDE1;
    public static final int TEXT_DIM = 0xFFB9B0A0;
    public static final int TEXT_FAINT = 0xFF83796B;
    public static final int TEXT_GREEN = BirchSkin.LEAF;
    public static final int TEXT_GOLD = 0xFFE8B75A;
    public static final int TEXT_RED = 0xFFE0796B;

    private Chrome() {
    }

    /**
     * Passed as the active tab by a screen that has no tab strip.
     *
     * The import screen is reached from a button rather than from the tabs and
     * deliberately has none, but it still draws the shared frame — and the frame
     * joins the chosen tab to the panel with a bar of leaf green. Given a real
     * index it drew that bar under a tab that was not there, leaving a stripe
     * hanging in the header.
     */
    public static final int NO_TAB = -1;

    /** The five tabs, in order. Index is what a screen reports as its own. */
    public static final List<String> TABS =
            List.of("Overlay", "Route", "Trees", "Alerts", "Routes");

    /**
     * Run something a screen does, without letting it take the game down.
     *
     * Every tick and render path in this mod goes through {@link Guard}, on the
     * principle that an overlay showing tree timers is never worth somebody's
     * session. The screens were the exception, and they are the newest code
     * here and the only part that touches the disk and the clipboard while the
     * game is waiting on it — a full disk or a permission the launcher does not
     * have would have come out as a crash report.
     */
    public static void guard(String what, Runnable action) {
        Guard.run("gui-" + what, action);
    }

    /** As {@link #guard}, but reports whether the action got through. */
    public static boolean attempt(String what, Runnable action) {
        return Guard.attempt("gui-" + what, action);
    }

    /** A button whose action cannot crash the game. */
    public static Button.OnPress safely(String what, Runnable action) {
        return button -> guard(what, action);
    }

    /**
     * Build the tab strip.
     *
     * The tab you are on is not a button; it is where you are. It draws as an
     * accent bar joining the label to the panel below rather than as a greyed
     * out button, which is what a disabled button looks like and is the wrong
     * thing to say about the page somebody is currently reading.
     *
     * @param active index of the tab the screen is showing
     * @param onPick called with the index of a tab that was clicked
     */
    public static void tabs(int width, int active, Consumer<Integer> onPick,
                            Consumer<AbstractWidget> add) {
        int tab = tabWidth(width);
        int startX = tabStripLeft(width);

        for (int i = 0; i < TABS.size(); i++) {
            final int index = i;
            boolean current = index == active;

            BarkButton button = new BarkTab(startX + i * tab, TAB_Y, tab - 3, TAB_HEIGHT,
                    Component.literal(TABS.get(i)), b -> onPick.accept(index), current);
            // The tab you are on is still clickable-looking rather than greyed:
            // a disabled tab says "you cannot go here", which is the wrong thing
            // to say about the page somebody is already reading. It is drawn as
            // chosen instead, and pressing it does nothing.
            add.accept(button);
        }
    }

    /** A tab: bark, with the current one marked as chosen rather than disabled. */
    private static final class BarkTab extends BarkButton {
        private final boolean current;

        BarkTab(int x, int y, int w, int h, Component message, OnPress onPress,
                boolean current) {
            super(x, y, w, h, message, onPress);
            this.current = current;
        }

        @Override
        protected BirchSkin.State state() {
            if (current) {
                return BirchSkin.State.CHOSEN;
            }
            return isHoveredOrFocused() ? BirchSkin.State.HOVER : BirchSkin.State.IDLE;
        }
    }

    /**
     * How wide one tab is at this window width.
     *
     * Five tabs at a fixed width came to more than the narrowest window the
     * game will hand us — 320 scaled pixels, which is what 1080p at GUI scale
     * 6 looks like — so the strip ran off both edges and the outer tabs could
     * not be clicked. It shrinks to fit instead.
     */
    public static int tabWidth(int width) {
        int room = Math.max(0, width - MARGIN * 2);
        return Math.max(28, Math.min(TAB_WIDTH, room / TABS.size()));
    }

    private static int tabStripLeft(int width) {
        return Math.max(0, (width - tabWidth(width) * TABS.size()) / 2);
    }

    /**
     * The grove, the panel cut into it, and the title.
     *
     * Called before the widgets are drawn, so everything here is behind them.
     * The wood runs the whole window and the working area is a sawn plank laid
     * over it — so the screen reads as something standing in a birch forest
     * rather than as a dark rectangle with green text on it.
     */
    public static void background(GuiGraphicsExtractor graphics, Font font,
                                  int width, int height, int active) {
        BirchSkin.grove(graphics, width, height);

        int panelTop = CONTENT_TOP - PAD;
        int panelBottom = contentBottom(height);

        BirchSkin.plank(graphics, MARGIN - PAD, panelTop,
                width - (MARGIN - PAD) * 2, Math.max(4, panelBottom - panelTop),
                0xB17C4 ^ width);

        // The chosen tab is joined to the plank below it by a bar of leaf
        // green, so the tab and the page read as one thing. Only when there is
        // a tab there to join.
        if (active >= 0 && active < TABS.size()) {
            int tab = tabWidth(width);
            int tabX = tabStripLeft(width) + active * tab;
            graphics.fill(tabX, TAB_Y + TAB_HEIGHT, tabX + tab - 3, panelTop + 1,
                    BirchSkin.LEAF_DEEP);
        }

        title(graphics, font, width);
    }

    /** The name, a sprig of birch, and which build this is. */
    private static void title(GuiGraphicsExtractor graphics, Font font, int width) {
        String name = "Birch Optimizer";
        int x = MARGIN;

        // A little trunk-and-leaves mark, drawn rather than spelled, so the
        // header carries the same wood the rest of the screen is made of.
        graphics.fill(x + 3, 9, x + 5, 19, BirchSkin.BARK_MID);
        graphics.fill(x + 3, 12, x + 5, 13, BirchSkin.LENTICEL);
        graphics.fill(x + 3, 16, x + 5, 17, BirchSkin.LENTICEL);
        graphics.fill(x, 6, x + 8, 9, BirchSkin.LEAF_DEEP);
        graphics.fill(x + 1, 4, x + 7, 6, BirchSkin.LEAF);

        graphics.text(font, name, x + 13, 9, TEXT, true);

        String version = "v" + BirchMod.version();
        graphics.text(font, version, width - MARGIN - font.width(version), 9, TEXT_FAINT, false);
    }

    /**
     * A section heading: a leaf, the label, and a rule running off it.
     *
     * The rule is what makes it read as a heading rather than as a switched-off
     * control, which is what it used to be built out of.
     */
    public static void section(GuiGraphicsExtractor graphics, Font font,
                               String label, int x, int y, int right) {
        // A leaf, two triangles back to back.
        for (int i = 0; i < 3; i++) {
            graphics.fill(x + i, y + 3 - i, x + i + 1, y + 5 + i, BirchSkin.LEAF);
            graphics.fill(x + 5 - i, y + 3 - i, x + 6 - i, y + 5 + i, BirchSkin.LEAF_DEEP);
        }
        int textX = x + 10;
        graphics.text(font, label, textX, y, ACCENT, false);

        int ruleX = textX + font.width(label) + 6;
        if (ruleX < right) {
            graphics.fill(ruleX, y + 3, right, y + 4, ACCENT_DIM);
        }
    }

    /**
     * A groove cut into the wood, for something you type into.
     *
     * Dark and inset, with the light edge on the bottom rather than the top, so
     * it reads as a channel rather than as a raised panel.
     */
    public static void groove(GuiGraphicsExtractor graphics, int x, int y, int w, int h) {
        graphics.fill(x, y, x + w, y + h, 0x80000000);
        graphics.fill(x, y, x + w, y + 1, 0x60000000);
        graphics.fill(x, y + h - 1, x + w, y + h, 0x28FFFFFF);
        graphics.fill(x, y, x + 1, y + h, 0x50000000);
        graphics.fill(x + w - 1, y, x + w, y + h, 0x18FFFFFF);
    }

    /** A horizontal rule across the panel. */
    public static void rule(GuiGraphicsExtractor graphics, int left, int right, int y) {
        graphics.fill(left, y, right, y + 1, RULE);
    }

    /** A boxed sub-panel, for a detail pane beside a list. */
    public static void card(GuiGraphicsExtractor graphics, int left, int top,
                            int right, int bottom) {
        BirchSkin.plank(graphics, left, top, Math.max(2, right - left),
                Math.max(2, bottom - top), 0xCA4D ^ left ^ top);
    }

    /**
     * The scrollbar for a scrolling area, drawn only when there is more to see.
     *
     * @param extent  height of the visible area
     * @param content total height of what is being scrolled
     * @param offset  how far down it is scrolled
     */
    public static void scrollbar(GuiGraphicsExtractor graphics, int x, int top,
                                 int extent, int content, int offset) {
        if (content <= extent) {
            return;
        }
        graphics.fill(x, top, x + 4, top + extent, 0x50000000);

        int barHeight = Math.max(20, extent * extent / content);
        int span = extent - barHeight;
        int maxOffset = content - extent;
        int barTop = top + (maxOffset <= 0 ? 0 : (int) ((long) span * offset / maxOffset));
        // A sliver of trunk, complete with a mark or two, rather than a grey bar.
        graphics.fill(x, barTop, x + 4, barTop + barHeight, BirchSkin.BARK_MID);
        graphics.fill(x + 3, barTop, x + 4, barTop + barHeight, BirchSkin.BARK_DARK);
        for (int i = 0; i < Math.max(1, barHeight / 9); i++) {
            int my = barTop + 3 + (int) (BirchSkin.noise(0x5C0B, i) * Math.max(1, barHeight - 6));
            graphics.fill(x, my, x + 3, my + 1, BirchSkin.withAlpha(BirchSkin.LENTICEL, 0xA0));
        }
    }

    /**
     * A wash behind the control the mouse is over.
     *
     * Warm rather than white: a plain white overlay on wood grey it out, which
     * reads as the row being switched off rather than being pointed at.
     */
    public static void hoverRow(GuiGraphicsExtractor graphics, int left, int top,
                                int right, int bottom) {
        graphics.fill(left, top, right, bottom, BirchSkin.withAlpha(BirchSkin.LEAF, 0x18));
    }

    /** Bottom of the usable area. */
    public static int contentBottom(int height) {
        return height - FOOTER_HEIGHT;
    }

    /** Y for a row of footer buttons. */
    public static int footerY(int height) {
        return height - 25;
    }

    /** The hint line that sits along the bottom left. */
    public static void hint(GuiGraphicsExtractor graphics, Font font, String text, int height) {
        graphics.text(font, text, MARGIN, footerY(height) + 6, TEXT_FAINT, false);
    }
}
