package com.birchmod.gui;

import java.util.List;

import com.birchmod.route.LapTracker;
import com.birchmod.route.RecordedRoute;
import com.birchmod.route.RouteLibrary;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.network.chat.Component;

/**
 * The list of saved routes, one clickable row each.
 *
 * A row says the three things you choose between routes on: what it is called,
 * how many stops it has, and the best lap you have ever walked on it. Whether
 * you are following it and whether it is your default are markers rather than
 * words, because they are true of at most one route each and a column of
 * mostly-blank text would be worse than a symbol.
 */
public final class RouteListWidget extends ObjectSelectionList<RouteListWidget.RouteEntry> {

    private static final int ROW_HEIGHT = 26;

    public RouteListWidget(Minecraft minecraft, int width, int height, int x, int y) {
        super(minecraft, width, height, y, ROW_HEIGHT);
        setX(x);
    }

    /** Rebuild from the library, keeping the selection on the same route. */
    public void refresh(double regenSeconds) {
        String keep = selectedName();
        clearEntries();

        List<RecordedRoute> routes = RouteLibrary.all();
        routes.sort(RouteLibrary.ranking(regenSeconds));

        String following = RouteLibrary.getActiveName();
        String preferred = RouteLibrary.getDefaultName();

        for (RecordedRoute route : routes) {
            RouteEntry entry = new RouteEntry(route.name, route.size(), route.bestLapSeconds,
                    route.name.equalsIgnoreCase(following),
                    route.name.equalsIgnoreCase(preferred));
            addEntry(entry);
            if (route.name.equals(keep)) {
                setSelected(entry);
            }
        }
        // Selecting the first route means the detail pane and its buttons are
        // never blank on arrival, which is what an empty right-hand side looks
        // like the first time: broken rather than waiting.
        if (getSelected() == null && !children().isEmpty()) {
            setSelected(children().get(0));
        }
    }

    /** The route the player has picked, or null. */
    public String selectedName() {
        RouteEntry entry = getSelected();
        return entry == null ? null : entry.name;
    }

    @Override
    public int getRowWidth() {
        return getWidth() - 12;
    }

    /** The list sits on a sawn plank, like every other panel on these screens. */
    @Override
    protected void extractListBackground(GuiGraphicsExtractor graphics) {
        BirchSkin.plank(graphics, getX(), getY(), getWidth(), getHeight(), 0x1157 ^ getX());
    }

    /**
     * No separators.
     *
     * Vanilla rules a grey line between every row. On wood that reads as a
     * saw cut across the grain, and the rows already separate themselves —
     * the one you have picked is a slip of bark and the rest are not.
     */
    @Override
    protected void extractListSeparators(GuiGraphicsExtractor graphics) {
    }

    /** One route. */
    public class RouteEntry extends ObjectSelectionList.Entry<RouteEntry> {

        final String name;
        private final int stops;
        private final double bestLap;
        private final boolean following;
        private final boolean isDefault;

        RouteEntry(String name, int stops, double bestLap, boolean following, boolean isDefault) {
            this.name = name;
            this.stops = stops;
            this.bestLap = bestLap;
            this.following = following;
            this.isDefault = isDefault;
        }

        @Override
        public void extractContent(GuiGraphicsExtractor graphics, int mouseX, int mouseY,
                                   boolean hovered, float partial) {
            boolean picked = getSelected() == this;

            // The route you have picked is a slip of bark laid on the plank;
            // the others are just text on wood. That is the whole selection
            // indicator, and it is legible at a glance down a long list.
            int rowX = getContentX() - 2;
            int rowW = getContentWidth() + 4;
            if (picked) {
                BirchSkin.bark(graphics, rowX, getContentY() - 1, rowW, getContentHeight(),
                        BirchSkin.seedFor(name), BirchSkin.State.IDLE);
            } else if (hovered) {
                Chrome.hoverRow(graphics, rowX, getContentY() - 1,
                        rowX + rowW, getContentY() - 1 + getContentHeight());
            }

            int ink = picked ? BirchSkin.INK : Chrome.TEXT;
            int inkDim = picked ? BirchSkin.INK_SOFT : Chrome.TEXT_DIM;
            int x = getContentX() + 4;
            int y = getContentY() + 2;

            // Markers, drawn rather than spelled, so they read the same on bark
            // as on wood and do not fight the ink colour.
            int markX = x;
            if (following) {
                arrow(graphics, markX, y + 1, BirchSkin.LEAF_DEEP);
                markX += 8;
            }
            if (isDefault) {
                star(graphics, markX, y + 1, Chrome.TEXT_GOLD);
                markX += 8;
            }

            int room = getContentWidth() - (markX - getContentX()) - 8;
            graphics.text(minecraft.font,
                    BarkButton.fit(minecraft.font, name, room), markX, y, ink, false);

            String detail = stops + " stops"
                    + (bestLap > 0.0 ? "  ·  best " + LapTracker.format(bestLap) : "");
            graphics.text(minecraft.font,
                    BarkButton.fit(minecraft.font, detail, getContentWidth() - 8),
                    x, y + 11, inkDim, false);
        }

        /** A small solid triangle pointing right. */
        private void arrow(GuiGraphicsExtractor graphics, int x, int y, int colour) {
            for (int i = 0; i < 4; i++) {
                graphics.fill(x + i, y + i, x + i + 1, y + 7 - i, colour);
            }
        }

        /** A four-pointed spark, for the default route. */
        private void star(GuiGraphicsExtractor graphics, int x, int y, int colour) {
            graphics.fill(x + 2, y, x + 4, y + 7, colour);
            graphics.fill(x, y + 2, x + 6, y + 4, colour);
        }

        @Override
        public Component getNarration() {
            return Component.literal(name + ", " + stops + " stops");
        }
    }
}
