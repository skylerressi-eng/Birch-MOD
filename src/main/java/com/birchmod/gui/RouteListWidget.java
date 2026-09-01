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
            int x = getContentX() + 4;
            int y = getContentY() + 3;

            String marks = (following ? "§a▶ " : "") + (isDefault ? "§6★ " : "");
            graphics.text(minecraft.font, marks + "§f" + name, x, y, Chrome.TEXT, false);

            String detail = stops + " stops"
                    + (bestLap > 0.0 ? "  §8best " + LapTracker.format(bestLap) : "");
            graphics.text(minecraft.font, "§7" + detail, x, y + 11, Chrome.TEXT_DIM, false);
        }

        @Override
        public Component getNarration() {
            return Component.literal(name + ", " + stops + " stops");
        }
    }
}
