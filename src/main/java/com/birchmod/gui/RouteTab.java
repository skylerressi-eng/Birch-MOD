package com.birchmod.gui;

import java.util.ArrayList;
import java.util.List;

import com.birchmod.BirchMod;
import com.birchmod.config.BirchConfig;

/** What the route draws, and how closely it follows what you recorded. */
final class RouteTab implements BirchScreen.Tab {

    @Override
    public String title() {
        return "Route";
    }

    @Override
    public List<BirchScreen.Item> controls(BirchScreen screen) {
        BirchConfig c = BirchConfig.get();
        List<BirchScreen.Item> controls = new ArrayList<>();

        controls.add(BirchScreen.toggle("Overlay", "Boxes and lines in the world.",
                () -> c.routeEnabled, v -> c.routeEnabled = v));
        controls.add(BirchScreen.toggle("Tracer", "The line from you to the tree you are chopping.",
                () -> c.tracersEnabled, v -> c.tracersEnabled = v));
        controls.add(BirchScreen.slider("Trees ahead",
                "How many trees to show. 2 is this one and the next.",
                1, BirchConfig.MAX_ROUTE_LENGTH, 1,
                () -> c.routeLength, v -> c.routeLength = (int) v));
        controls.add(BirchScreen.toggle("Onward line", "The blue line to the next tree.",
                () -> c.chainTracers, v -> c.chainTracers = v));
        controls.add(BirchScreen.toggle("Whole loop",
                "Draw every stop on the route, ignoring the count above.",
                () -> c.showFullPath, v -> c.showFullPath = v));
        controls.add(BirchScreen.toggle("Labels", "Numbered labels above each stop.",
                () -> c.showRouteLabels, v -> c.showRouteLabels = v));
        controls.add(BirchScreen.toggle("Fill block",
                "Fill the block to mine, rather than outlining it.",
                () -> c.filledHighlight, v -> c.filledHighlight = v));
        controls.add(BirchScreen.slider("Line width", "Thickness of boxes and lines.",
                0.5, 10.0, 0.5, () -> c.lineWidth, v -> c.lineWidth = v));

        controls.add(BirchScreen.section("Following"));

        controls.add(BirchScreen.toggle("Strict order",
                "On: exactly the order you recorded. Off: a cleared stop hands "
                        + "over to the nearest ready tree. Neither moves you off wood.",
                () -> c.strictRoute, v -> {
                    c.strictRoute = v;
                    if (BirchMod.routeBuilder != null) {
                        BirchMod.routeBuilder.resetCommitment();
                    }
                }));
        controls.add(BirchScreen.slider("Marker height",
                "How far up a trunk to aim the marker.",
                0, 12, 1, () -> c.treeCenterHeight, v -> c.treeCenterHeight = (int) v));
        controls.add(BirchScreen.slider("Min logs",
                "Birch needed at a spot before it is marked. 1 takes every log "
                        + "there is; raise it if scenery earns markers.",
                1, 8, 1, () -> c.minTreeLogs, v -> c.minTreeLogs = (int) v));
        controls.add(BirchScreen.slider("Tree size",
                "How wide a patch of ground counts as one tree. 1 is a 3x3.",
                0, 2, 1, () -> c.treeFootprint, v -> c.treeFootprint = (int) v));

        return controls;
    }
}
