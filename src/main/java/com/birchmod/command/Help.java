package com.birchmod.command;

import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.network.chat.Component;

/**
 * The command reference, in the game.
 *
 * A mod with forty subcommands and no readable list of them is a mod where most
 * of them do not exist as far as anyone is concerned. This is grouped by what
 * you are trying to do rather than alphabetically, and every line says what the
 * command is <em>for</em> — a list of names you already have to know is not
 * help.
 */
final class Help {

    private Help() {
    }

    static void route(FabricClientCommandSource source) {
        line(source, "§6§lBirch Optimizer §7— route commands");

        header(source, "Recording a route");
        entry(source, "/route start <name>", "begin recording; chop trees in the order you want them");
        entry(source, "/route stop", "save the recording and start following it");
        entry(source, "/route cancel", "throw the recording away");
        note(source, "Needs at least 3 trees. Saving and activating happen in one step.");

        header(source, "Choosing what to follow");
        entry(source, "/route list", "every saved route, best first, with your best lap");
        entry(source, "/route <name>", "run that route now");
        entry(source, "/route setdefault <name>", "run it now and come back to it every login");
        entry(source, "/route default", "go back to your default route");
        entry(source, "/route use <name>", "the long way to write /route <name>");
        entry(source, "/route best", "follow whichever scores highest right now");
        entry(source, "/route compile [name]", "merge all your recordings into one optimised loop");
        entry(source, "/route auto", "follow nothing; plan from whatever is standing nearby");
        entry(source, "/route delete <name>", "remove a saved route");
        note(source, "compile, best and auto each replace what you are following now, "
                + "but never your default.");

        header(source, "How it follows you round");
        entry(source, "/route strict <true|false>", "true: exactly the order you recorded. "
                + "false: a cleared stop hands over to the nearest ready tree");
        note(source, "Neither setting will move you off a tree with wood still on it.");

        header(source, "What you see");
        entry(source, "/route <true|false>", "the route overlay as a whole");
        entry(source, "/route tracers <true|false>", "the line from you to the tree you are chopping");
        entry(source, "/route length <1-32>", "how many trees ahead to show — 10 draws the next ten");
        entry(source, "/route path <true|false>", "draw the whole loop, ignoring the length");
        entry(source, "/route chain <true|false>", "the blue line onward to the next tree");
        entry(source, "/route labels <true|false>", "numbered labels above each stop");
        entry(source, "/route filled <true|false>", "fill the block to mine, or outline it only");
        entry(source, "/route width <0.5-10>", "line thickness");
        entry(source, "/route center <0-12>", "how high up a trunk to aim the marker");
        entry(source, "/route minlogs <1-8>", "birch needed at a spot before it is marked — 2 takes trunks and log piles, 1 takes everything");

        header(source, "What it knows");
        entry(source, "/route", "the current route, stop by stop, with what is left on each");
        entry(source, "/route stats", "measured leg times, travel speed and chop history");

        line(source, "§8§f/birch help§8 covers tracking, prices and the overlay.");
    }

    static void birch(FabricClientCommandSource source) {
        line(source, "§6§lBirch Optimizer §7— general commands");

        header(source, "Where you stand");
        entry(source, "/birch", "birch/hour, prices, session totals and rank");
        entry(source, "/birch stats", "the full breakdown, session and lifetime");
        entry(source, "/birch bazaar", "live prices and spreads across birch products");
        entry(source, "/birch reset", "clear the session counters");

        header(source, "The overlay");
        entry(source, "/birch hud <true|false>", "the whole HUD");
        entry(source, "/birch hud pos <x> <y>", "where it sits on screen");
        entry(source, "/birch hud scale <0.5-3>", "how big it is");
        entry(source, "/birch hud bg <true|false>", "the dark panel behind it");
        entry(source, "/birch lap <true|false>", "the live lap timer against your best");
        entry(source, "/birch skyblockonly <true|false>", "hide everything outside Skyblock");

        header(source, "Being told things");
        entry(source, "/birch notify <true|false>", "alerts when trees come back");
        entry(source, "/birch notify sound <true|false>", "the sound those alerts make");
        entry(source, "/birch leftovers <true|false>", "a nudge when you walk away from a "
                + "trunk you did not finish");

        header(source, "Trees and timers");
        entry(source, "/timer", "regen calibration: what has been measured, and over how many cycles");
        entry(source, "/timer mode", "floating countdowns above regrowing trees");
        entry(source, "/timer reset", "forget the measurements and calibrate again");

        header(source, "Prices and rank");
        entry(source, "/birch tax <true|false>", "subtract Bazaar tax from coin projections");
        entry(source, "/birch apikey <key>", "your Hypixel API key, for rank lookups");
        entry(source, "/birch name <username>", "which account to look up");

        header(source, "When something looks wrong");
        entry(source, "/birch diag", "what has failed, and how often");
        entry(source, "/birch safemode <true|false>", "turn off all in-world drawing, keep tracking");

        line(source, "§8§f/route help§8 covers recording and following routes.");
    }

    // ---- Formatting ----

    private static void header(FabricClientCommandSource source, String text) {
        line(source, "§e§l" + text);
    }

    private static void entry(FabricClientCommandSource source, String command, String what) {
        line(source, " §f" + command + " §8— §7" + what);
    }

    private static void note(FabricClientCommandSource source, String text) {
        line(source, " §8" + text);
    }

    private static void line(FabricClientCommandSource source, String message) {
        source.sendFeedback(Component.literal(message));
    }
}
