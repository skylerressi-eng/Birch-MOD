package com.birchmod.gui;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import com.birchmod.BirchMod;
import com.birchmod.route.LapTracker;
import com.birchmod.route.RecordedRoute;
import com.birchmod.route.RouteCodec;
import com.birchmod.route.RouteLibrary;
import com.birchmod.util.Notifier;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;

/**
 * Your saved routes, and moving them between accounts.
 *
 * Each route gets a row saying what it is and a row of things to do with it.
 * Export puts a share code on the clipboard; import reads whatever is on the
 * clipboard. Going through the clipboard rather than a text field is not
 * laziness — a fifty-stop route is a few hundred characters, and typing that
 * into a Minecraft text box is not something anyone would do twice.
 */
final class RoutesTab implements BirchScreen.Tab {

    @Override
    public String title() {
        return "Routes";
    }

    @Override
    public List<Supplier<AbstractWidget>> controls(BirchScreen screen) {
        List<Supplier<AbstractWidget>> controls = new ArrayList<>();

        controls.add(BirchScreen.action("Import from clipboard",
                "Paste a code somebody sent you, then press this. "
                        + "Codes start with " + RouteCodec.PREFIX,
                () -> importFromClipboard(screen)));
        controls.add(BirchScreen.action("Export all",
                "Put a code for every saved route on the clipboard, one per line.",
                () -> exportAll(screen)));

        String following = RouteLibrary.getActiveName();
        String preferred = RouteLibrary.getDefaultName();

        List<RecordedRoute> routes = RouteLibrary.all();
        if (routes.isEmpty()) {
            controls.add(BirchScreen.heading("Nothing saved yet"));
            controls.add(BirchScreen.heading(""));
            controls.add(BirchScreen.action("How to record one",
                    "Close this, then /route start <name>, chop the trees in the "
                            + "order you want them, and /route stop.",
                    () -> Notifier.chat("§7Record a route with §f/route start <name>"
                            + "§7, then §f/route stop§7 when you have been round.")));
            return controls;
        }

        double regen = BirchMod.regenTracker != null
                ? BirchMod.regenTracker.getRegenSeconds()
                : 60.0;
        routes.sort(RouteLibrary.ranking(regen));

        for (RecordedRoute route : routes) {
            final String name = route.name;
            boolean isFollowing = name.equalsIgnoreCase(following);
            boolean isDefault = name.equalsIgnoreCase(preferred);

            String mark = (isFollowing ? "§a> " : "§7") + (isDefault ? "§6* " : "");
            String best = route.bestLapSeconds > 0.0
                    ? " §8" + LapTracker.format(route.bestLapSeconds)
                    : "";

            // Row one says what it is; row two is what you can do with it.
            controls.add(BirchScreen.heading(mark + "§f" + name
                    + " §7" + route.size() + " stops" + best));

            controls.add(BirchScreen.action(isFollowing ? "§aFollowing" : "Follow",
                    isFollowing ? "This is the route you are on." : "Start following " + name + ".",
                    () -> {
                        RouteLibrary.setActive(name);
                        resetRoute();
                        screen.requestRebuild();
                    }));

            controls.add(BirchScreen.action(isDefault ? "§6Default" : "Make default",
                    "Come back to " + name + " on every login, whatever you were "
                            + "following when you left.",
                    () -> {
                        RouteLibrary.setDefault(name);
                        resetRoute();
                        screen.requestRebuild();
                    }));

            controls.add(BirchScreen.action("Copy code",
                    "Put a share code for " + name + " on the clipboard.",
                    () -> exportOne(screen, name)));

            controls.add(BirchScreen.action("§cDelete",
                    "Remove " + name + " for good. There is no undo.",
                    () -> {
                        RouteLibrary.delete(name);
                        resetRoute();
                        screen.requestRebuild();
                    }));

            controls.add(BirchScreen.heading(""));
        }

        return controls;
    }

    // ---- Sharing ----

    private static void exportOne(BirchScreen screen, String name) {
        RecordedRoute route = RouteLibrary.get(name);
        if (route == null) {
            Notifier.chat("§cNo route called §f" + name);
            return;
        }
        String code = RouteCodec.encode(route);
        if (setClipboard(code)) {
            Notifier.chat("§aCopied §f" + name + "§a — " + code.length()
                    + " characters, paste it to whoever wants it.");
        }
    }

    private static void exportAll(BirchScreen screen) {
        List<String> codes = RouteCodec.encodeAll(RouteLibrary.all());
        if (codes.isEmpty()) {
            Notifier.chat("§cNothing saved worth exporting.");
            return;
        }
        if (setClipboard(String.join("\n", codes))) {
            Notifier.chat("§aCopied §f" + codes.size() + "§a route(s) to the clipboard.");
        }
    }

    private static void importFromClipboard(BirchScreen screen) {
        String clipboard = getClipboard();
        if (clipboard == null || clipboard.isBlank()) {
            Notifier.chat("§cThe clipboard is empty.");
            return;
        }

        int imported = 0;
        String lastFailure = null;

        // A clipboard may hold one code or a whole library, so every line is
        // tried and the tally reported rather than stopping at the first dud.
        for (String line : clipboard.split("\\R")) {
            if (line.isBlank()) {
                continue;
            }
            try {
                RecordedRoute route = RouteCodec.decode(line);
                route.name = RouteCodec.freeName(route.name, RouteLibrary::exists);
                RouteLibrary.save(route);
                imported++;
            } catch (RouteCodec.CodecException e) {
                lastFailure = e.getMessage();
            }
        }

        if (imported > 0) {
            Notifier.chat("§aImported §f" + imported + "§a route(s).");
            screen.requestRebuild();
        } else {
            Notifier.chat("§cNothing imported. "
                    + (lastFailure != null ? lastFailure : "No route code on the clipboard."));
        }
    }

    private static boolean setClipboard(String text) {
        Minecraft client = Minecraft.getInstance();
        if (client == null) {
            return false;
        }
        try {
            client.keyboardHandler.setClipboard(text);
            return true;
        } catch (Exception e) {
            Notifier.chat("§cCould not reach the clipboard.");
            return false;
        }
    }

    private static String getClipboard() {
        Minecraft client = Minecraft.getInstance();
        if (client == null) {
            return null;
        }
        try {
            return client.keyboardHandler.getClipboard();
        } catch (Exception e) {
            return null;
        }
    }

    private static void resetRoute() {
        if (BirchMod.routeBuilder != null) {
            BirchMod.routeBuilder.resetCommitment();
        }
    }
}
