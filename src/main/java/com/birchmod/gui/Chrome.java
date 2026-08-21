package com.birchmod.gui;

import java.util.List;
import java.util.function.Consumer;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

/**
 * The frame every screen in this mod shares: a title, a row of tabs, a panel
 * and a footer rule.
 *
 * Kept in one place so the settings screens and the route screens look like
 * parts of the same thing rather than three interfaces that happen to ship
 * together. Anything that has to line up across screens — where the panel
 * starts, how tall a tab is — is a constant here rather than a number written
 * out again in each.
 */
public final class Chrome {

    public static final int TITLE_Y = 10;
    public static final int TAB_Y = 26;
    public static final int TAB_HEIGHT = 20;
    public static final int TAB_WIDTH = 74;
    public static final int CONTENT_TOP = 54;
    public static final int FOOTER_HEIGHT = 34;
    public static final int MARGIN = 14;

    // A dark slate panel with a lighter rule above and below it, so content
    // sits in something rather than floating on the world.
    private static final int PANEL = 0xC0121418;
    private static final int PANEL_EDGE = 0x40FFFFFF;
    private static final int TAB_ACTIVE = 0xFF3A5F3A;

    public static final int TEXT = 0xFFFFFFFF;
    public static final int TEXT_DIM = 0xFFA0A6AC;
    public static final int TEXT_GREEN = 0xFF66DD66;
    public static final int TEXT_GOLD = 0xFFFFAA00;

    private Chrome() {
    }

    /** The five tabs, in order. Index is what a screen reports as its own. */
    public static final List<String> TABS =
            List.of("Overlay", "Route", "Trees", "Alerts", "Routes");

    /**
     * Build the tab strip.
     *
     * @param active  index of the tab the screen is showing
     * @param onPick  called with the index of a tab that was clicked
     */
    public static void tabs(int width, int active, Consumer<Integer> onPick,
                            Consumer<AbstractWidget> add) {
        int total = TAB_WIDTH * TABS.size();
        int startX = (width - total) / 2;

        for (int i = 0; i < TABS.size(); i++) {
            final int index = i;
            boolean current = index == active;

            Button button = Button.builder(
                            Component.literal(current ? "§f§l" + TABS.get(i) : "§7" + TABS.get(i)),
                            b -> onPick.accept(index))
                    .bounds(startX + i * TAB_WIDTH, TAB_Y, TAB_WIDTH - 2, TAB_HEIGHT)
                    .build();
            // The tab you are on is not a button; it is where you are.
            button.active = !current;
            add.accept(button);
        }
    }

    /** Title, panel and rules. Call before the widgets are drawn. */
    public static void background(GuiGraphicsExtractor graphics, Font font,
                                  int width, int height, int active) {
        int top = CONTENT_TOP - 4;
        int bottom = height - FOOTER_HEIGHT;

        graphics.fill(MARGIN - 4, top, width - MARGIN + 4, bottom, PANEL);
        graphics.fill(MARGIN - 4, top, width - MARGIN + 4, top + 1, PANEL_EDGE);
        graphics.fill(MARGIN - 4, bottom - 1, width - MARGIN + 4, bottom, PANEL_EDGE);

        // A bar under the tab you are on, to tie it to the panel.
        int total = TAB_WIDTH * TABS.size();
        int startX = (width - total) / 2;
        graphics.fill(startX + active * TAB_WIDTH, TAB_Y + TAB_HEIGHT - 2,
                startX + active * TAB_WIDTH + TAB_WIDTH - 2, TAB_Y + TAB_HEIGHT, TAB_ACTIVE);

        String title = "Birch Optimizer";
        graphics.text(font, "§6§l" + title, (width - font.width(title)) / 2, TITLE_Y, TEXT, true);
    }

    /** Bottom of the usable area. */
    public static int contentBottom(int height) {
        return height - FOOTER_HEIGHT;
    }

    /** Y for a row of footer buttons. */
    public static int footerY(int height) {
        return height - 26;
    }
}
