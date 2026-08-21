package com.birchmod.gui;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import com.birchmod.config.BirchConfig;

import net.minecraft.client.gui.components.AbstractWidget;

/** The HUD: whether it is there, how big, and which rows it carries. */
final class OverlayTab implements BirchScreen.Tab {

    @Override
    public String title() {
        return "Overlay";
    }

    @Override
    public List<Supplier<AbstractWidget>> controls(BirchScreen screen) {
        BirchConfig c = BirchConfig.get();
        List<Supplier<AbstractWidget>> controls = new ArrayList<>();

        controls.add(BirchScreen.toggle("HUD", "The overlay as a whole.",
                () -> c.hudEnabled, v -> c.hudEnabled = v));
        controls.add(BirchScreen.toggle("Backdrop", "The dark panel behind the text.",
                () -> c.hudBackground, v -> c.hudBackground = v));
        controls.add(BirchScreen.slider("Scale", "How big the overlay is.",
                0.5, 3.0, 0.1, () -> c.hudScale, v -> c.hudScale = v));
        controls.add(BirchScreen.toggle("Skyblock only",
                "Hide everything when you are not on Skyblock.",
                () -> c.onlyInSkyblock, v -> c.onlyInSkyblock = v));
        controls.add(BirchScreen.slider("X", "Distance from the left edge.",
                0, 400, 1, () -> c.hudX, v -> c.hudX = (int) v));
        controls.add(BirchScreen.slider("Y", "Distance from the top edge.",
                0, 400, 1, () -> c.hudY, v -> c.hudY = (int) v));

        controls.add(BirchScreen.heading("Rows"));
        controls.add(BirchScreen.heading(""));

        controls.add(BirchScreen.toggle("Birch/hour", "Measured from what reaches your inventory.",
                () -> c.showBirchRate, v -> c.showBirchRate = v));
        controls.add(BirchScreen.toggle("Bazaar price", "Live price for the product you are pricing.",
                () -> c.showBazaar, v -> c.showBazaar = v));
        controls.add(BirchScreen.toggle("Coins/hour", "Your rate valued at the best payout.",
                () -> c.showCoinRate, v -> c.showCoinRate = v));
        controls.add(BirchScreen.toggle("Regen", "How long birch takes to come back.",
                () -> c.showRegen, v -> c.showRegen = v));
        controls.add(BirchScreen.toggle("Route", "Distance and wait for the next tree.",
                () -> c.showRoute, v -> c.showRoute = v));
        controls.add(BirchScreen.toggle("Lap timer", "This lap against your best on the route.",
                () -> c.showLap, v -> c.showLap = v));
        controls.add(BirchScreen.toggle("Session", "Birch, trees and coins this session.",
                () -> c.showSession, v -> c.showSession = v));
        controls.add(BirchScreen.toggle("Collection", "Your collection rank, read from the menu.",
                () -> c.showCollectionRank, v -> c.showCollectionRank = v));
        controls.add(BirchScreen.toggle("Leaderboard", "Your rank, fetched with your API key.",
                () -> c.showLeaderboard, v -> c.showLeaderboard = v));

        return controls;
    }
}
