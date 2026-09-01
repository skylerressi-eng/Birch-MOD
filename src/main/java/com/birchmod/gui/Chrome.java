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
    // Near-black bands, a slightly lifted panel, and one accent. Birch green is
    // the accent because it is what every marker in the world is already drawn
    // in; a settings screen in a different colour would read as a different mod.

    private static final int BAND = 0xF00B0D10;
    private static final int PANEL_TOP = 0xF0181C22;
    private static final int PANEL_BOTTOM = 0xF012151A;
    private static final int RULE = 0x33FFFFFF;
    private static final int RULE_STRONG = 0x55FFFFFF;

    public static final int ACCENT = 0xFF6ECF6E;
    private static final int ACCENT_DIM = 0x556ECF6E;
    private static final int TAB_HOVER = 0x22FFFFFF;

    public static final int TEXT = 0xFFFFFFFF;
    public static final int TEXT_DIM = 0xFF9AA1A9;
    public static final int TEXT_FAINT = 0xFF6A7078;
    public static final int TEXT_GREEN = 0xFF7FE07F;
    public static final int TEXT_GOLD = 0xFFFFC24D;
    public static final int TEXT_RED = 0xFFE87070;

    /** Alternating row wash, so a long list of controls does not run together. */
    public static final int ROW_TINT = 0x14FFFFFF;

    private Chrome() {
    }

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

            Button button = Button.builder(
                            Component.literal(current
                                    ? "§f§l" + TABS.get(i)
                                    : "§7" + TABS.get(i)),
                            b -> onPick.accept(index))
                    .bounds(startX + i * tab, TAB_Y, tab - 3, TAB_HEIGHT)
                    .build();
            button.active = !current;
            add.accept(button);
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
     * Header band, tab underline, panel and footer band.
     *
     * Called before the widgets are drawn, so everything here is behind them.
     */
    public static void background(GuiGraphicsExtractor graphics, Font font,
                                  int width, int height, int active) {
        int panelTop = CONTENT_TOP - PAD;
        int panelBottom = contentBottom(height);

        // Bands top and bottom, panel between them.
        graphics.fill(0, 0, width, panelTop, BAND);
        graphics.fillGradient(0, panelTop, width, panelBottom, PANEL_TOP, PANEL_BOTTOM);
        graphics.fill(0, panelBottom, width, height, BAND);

        graphics.fill(0, panelTop, width, panelTop + 1, RULE);
        graphics.fill(0, panelBottom - 1, width, panelBottom, RULE);

        // The accent bar under the active tab, sitting on the panel edge so the
        // tab and the page below it read as one thing.
        int tab = tabWidth(width);
        int tabX = tabStripLeft(width) + active * tab;
        graphics.fill(tabX, TAB_Y + TAB_HEIGHT, tabX + tab - 3, TAB_Y + TAB_HEIGHT + 2, ACCENT);
        graphics.fill(tabX, panelTop, tabX + tab - 3, panelTop + 1, ACCENT);

        title(graphics, font, width);
    }

    /** The name, a leaf, and which build this is. */
    private static void title(GuiGraphicsExtractor graphics, Font font, int width) {
        String name = "Birch Optimizer";
        int x = MARGIN;
        graphics.text(font, "§a❦", x, 8, ACCENT, false);
        graphics.text(font, "§f§l" + name, x + 12, 8, TEXT, false);

        String version = "v" + BirchMod.version();
        graphics.text(font, version, width - MARGIN - font.width(version), 8, TEXT_FAINT, false);
    }

    /** A section heading: small caps-ish label with a rule running off it. */
    public static void section(GuiGraphicsExtractor graphics, Font font,
                               String label, int x, int y, int right) {
        graphics.text(font, "§f" + label, x, y, ACCENT, false);
        int ruleX = x + font.width(label) + 6;
        if (ruleX < right) {
            graphics.fill(ruleX, y + 3, right, y + 4, ACCENT_DIM);
        }
    }

    /** A horizontal rule across the panel. */
    public static void rule(GuiGraphicsExtractor graphics, int left, int right, int y) {
        graphics.fill(left, y, right, y + 1, RULE);
    }

    /** A boxed sub-panel, for a detail pane beside a list. */
    public static void card(GuiGraphicsExtractor graphics, int left, int top,
                            int right, int bottom) {
        graphics.fill(left, top, right, bottom, 0x30000000);
        graphics.fill(left, top, right, top + 1, RULE);
        graphics.fill(left, bottom - 1, right, bottom, RULE);
        graphics.fill(left, top, left + 1, bottom, RULE);
        graphics.fill(right - 1, top, right, bottom, RULE);
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
        graphics.fill(x, top, x + 3, top + extent, 0x30000000);

        int barHeight = Math.max(20, extent * extent / content);
        int span = extent - barHeight;
        int maxOffset = content - extent;
        int barTop = top + (maxOffset <= 0 ? 0 : (int) ((long) span * offset / maxOffset));
        graphics.fill(x, barTop, x + 3, barTop + barHeight, RULE_STRONG);
    }

    /** Highlight behind the control the mouse is over. */
    public static void hoverRow(GuiGraphicsExtractor graphics, int left, int top,
                                int right, int bottom) {
        graphics.fill(left, top, right, bottom, TAB_HOVER);
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
