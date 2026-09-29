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
 * starts, how tall a tab is — is a constant here rather than a number written
 * out again in each.
 *
 * <h2>Looking like Minecraft</h2>
 * Vanilla widgets, and the translucent backdrop every other screen in the game
 * uses. This was briefly a hand-drawn birch skin: procedural bark on every
 * button over a grove of trunks. It was thematic and it looked bad, so it is
 * gone. A settings screen is furniture — it should disappear into the game
 * rather than announce itself, and the game already has a look.
 *
 * What is drawn here is the minimum vanilla does not provide: a title, a tab
 * strip, a faint wash to group the content, and a section heading. Buttons,
 * toggles, sliders and lists are Minecraft's own, untouched.
 */
public final class Chrome {

    public static final int HEADER_HEIGHT = 40;
    public static final int TAB_Y = 22;
    public static final int TAB_HEIGHT = 18;
    public static final int TAB_WIDTH = 78;
    public static final int CONTENT_TOP = HEADER_HEIGHT + 8;
    public static final int FOOTER_HEIGHT = 32;
    public static final int MARGIN = 16;

    /** Inset used wherever a panel needs breathing room. */
    public static final int PAD = 8;

    /**
     * Passed as the active tab by a screen that has no tab strip.
     *
     * The import screen is reached from a button rather than from the tabs and
     * deliberately has none, but it still draws the shared frame — and the
     * frame marks the chosen tab. Given a real index it marked a tab that was
     * not there.
     */
    public static final int NO_TAB = -1;

    // ---- Palette ----
    // Minecraft's own greys and accents, so nothing on these screens is a
    // colour the rest of the game never uses.

    /** A barely-there wash to group the working area, not a solid panel. */
    private static final int PANEL = 0x40000000;
    private static final int RULE = 0x30FFFFFF;
    private static final int RULE_STRONG = 0x60FFFFFF;

    /** The mark under the tab you are on. */
    public static final int ACCENT = 0xFF7FB238;

    public static final int TEXT = 0xFFFFFFFF;
    public static final int TEXT_DIM = 0xFFA0A0A0;
    public static final int TEXT_FAINT = 0xFF707070;
    public static final int TEXT_GREEN = 0xFF55FF55;
    public static final int TEXT_GOLD = 0xFFFFAA00;
    public static final int TEXT_RED = 0xFFFF5555;

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
     * session. The screens are the only part that touches the disk and the
     * clipboard while the game is waiting on it — a full disk or a permission
     * the launcher does not have would otherwise be a crash report.
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
     * Build the tab strip, as ordinary buttons.
     *
     * The tab you are on is disabled, which is what vanilla does for the page
     * you are already reading, and marked underneath so the state is not
     * carried by greying alone.
     */
    public static void tabs(int width, int active, Consumer<Integer> onPick,
                            Consumer<AbstractWidget> add) {
        int tab = tabWidth(width);
        int startX = tabStripLeft(width);

        for (int i = 0; i < TABS.size(); i++) {
            final int index = i;
            boolean current = index == active;

            Button button = Button.builder(Component.literal(TABS.get(i)),
                            b -> onPick.accept(index))
                    .bounds(startX + i * tab, TAB_Y, tab - 4, TAB_HEIGHT)
                    .build();
            button.active = !current;
            add.accept(button);
        }
    }

    /**
     * How wide one tab is at this window width.
     *
     * Five tabs at a fixed width came to more than the narrowest window the
     * game will hand us — 320 scaled pixels — so the strip ran off both edges
     * and the outer tabs could not be clicked. It shrinks to fit instead.
     */
    public static int tabWidth(int width) {
        int room = Math.max(0, width - MARGIN * 2);
        return Math.max(28, Math.min(TAB_WIDTH, room / TABS.size()));
    }

    private static int tabStripLeft(int width) {
        return Math.max(0, (width - tabWidth(width) * TABS.size()) / 2);
    }

    /**
     * Title, the mark under the active tab, and a wash behind the content.
     *
     * Called after the screen's own transparent backdrop and before the
     * widgets, so everything here sits between the two.
     */
    public static void background(GuiGraphicsExtractor graphics, Font font,
                                  int width, int height, int active) {
        int panelTop = CONTENT_TOP - PAD;
        int panelBottom = contentBottom(height);

        graphics.fill(MARGIN - PAD, panelTop, width - MARGIN + PAD, panelBottom, PANEL);
        graphics.fill(MARGIN - PAD, panelTop, width - MARGIN + PAD, panelTop + 1, RULE);
        graphics.fill(MARGIN - PAD, panelBottom - 1, width - MARGIN + PAD, panelBottom, RULE);

        if (active >= 0 && active < TABS.size()) {
            int tab = tabWidth(width);
            int tabX = tabStripLeft(width) + active * tab;
            graphics.fill(tabX, TAB_Y + TAB_HEIGHT, tabX + tab - 4,
                    TAB_Y + TAB_HEIGHT + 2, ACCENT);
        }

        title(graphics, font, width);
    }

    /** The name, and which build this is. */
    private static void title(GuiGraphicsExtractor graphics, Font font, int width) {
        String name = "Birch Optimizer";
        graphics.text(font, name, MARGIN, 10, TEXT, true);

        String version = "v" + BirchMod.version();
        graphics.text(font, version, width - MARGIN - font.width(version), 10, TEXT_FAINT, false);
    }

    /**
     * A section heading: a label with a rule running off it.
     *
     * The rule is what makes it read as a heading rather than as a switched-off
     * control, which is what it used to be built out of — a disabled button,
     * padded with a second empty one to fill the other column.
     */
    public static void section(GuiGraphicsExtractor graphics, Font font,
                               String label, int x, int y, int right) {
        graphics.text(font, label, x, y, TEXT_GOLD, false);
        int ruleX = x + font.width(label) + 6;
        if (ruleX < right) {
            graphics.fill(ruleX, y + 3, right, y + 4, RULE);
        }
    }

    /** A horizontal rule across the panel. */
    public static void rule(GuiGraphicsExtractor graphics, int left, int right, int y) {
        graphics.fill(left, y, right, y + 1, RULE);
    }

    /** A boxed sub-panel, for a detail pane beside a list. */
    public static void card(GuiGraphicsExtractor graphics, int left, int top,
                            int right, int bottom) {
        graphics.fill(left, top, right, bottom, 0x40000000);
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
        graphics.fill(x, top, x + 4, top + extent, 0x50000000);

        int barHeight = Math.max(20, extent * extent / content);
        int span = extent - barHeight;
        int maxOffset = content - extent;
        int barTop = top + (maxOffset <= 0 ? 0 : (int) ((long) span * offset / maxOffset));
        graphics.fill(x, barTop, x + 4, barTop + barHeight, RULE_STRONG);
    }

    /** A wash behind the row the mouse is over. */
    public static void hoverRow(GuiGraphicsExtractor graphics, int left, int top,
                                int right, int bottom) {
        graphics.fill(left, top, right, bottom, 0x20FFFFFF);
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

    /** A groove for something you type into. */
    public static void groove(GuiGraphicsExtractor graphics, int x, int y, int w, int h) {
        graphics.fill(x, y, x + w, y + h, 0x60000000);
        graphics.fill(x, y, x + w, y + 1, RULE);
        graphics.fill(x, y + h - 1, x + w, y + h, RULE);
        graphics.fill(x, y, x + 1, y + h, RULE);
        graphics.fill(x + w - 1, y, x + w, y + h, RULE);
    }
}
