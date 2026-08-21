package com.birchmod.command;

import java.text.DecimalFormat;

import com.birchmod.config.BirchConfig;
import com.birchmod.BirchMod;
import com.birchmod.route.LapTracker;
import com.birchmod.route.RecordedRoute;
import com.birchmod.route.RouteCodec;
import com.birchmod.route.RouteBuilder;
import com.birchmod.route.RouteLibrary;
import com.birchmod.route.RouteOptimizer;
import com.birchmod.route.RouteRecorder;
import com.birchmod.route.Stop;
import com.birchmod.route.TravelGraph;
import com.birchmod.stats.SessionStats;
import com.birchmod.tracking.TreeRegenTracker;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;

import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

/**
 * Client-side {@code /route} command for the foraging route overlay.
 */
public final class RouteCommand {

    private static final DecimalFormat SEC_FMT = new DecimalFormat("#0.0");

    /**
     * Names that {@code /route <name>} can never reach.
     *
     * Every subcommand, plus the two words the overlay toggle reads. Naming a
     * route after one of them is not an error — it saves and runs perfectly
     * well through {@code /route use} — but the short form silently does
     * something else entirely, which is worth knowing at the moment you choose
     * the name rather than the moment it confuses you.
     */
    private static final java.util.Set<String> RESERVED = java.util.Set.of(
            "true", "false", "start", "stop", "cancel", "compile", "list", "use",
            "strict", "minlogs", "stats", "help", "setdefault", "default", "auto",
            "best", "delete", "tracers", "path", "chain", "length", "width",
            "filled", "labels", "center", "export", "import", "gui");

    private RouteCommand() {
    }

    public static void register(RouteBuilder routeBuilder,
                                RouteRecorder recorder,
                                TreeRegenTracker regenTracker) {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, access) ->
                build(dispatcher, routeBuilder, recorder, regenTracker));
    }

    private static void build(CommandDispatcher<FabricClientCommandSource> dispatcher,
                              RouteBuilder routeBuilder,
                              RouteRecorder recorder,
                              TreeRegenTracker regenTracker) {
        dispatcher.register(ClientCommands.literal("route")
                .executes(ctx -> {
                    show(ctx.getSource(), routeBuilder, regenTracker);
                    return 1;
                })
                .then(ClientCommands.argument("enabled", BoolArgumentType.bool()).executes(ctx -> {
                    BirchConfig.get().routeEnabled = BoolArgumentType.getBool(ctx, "enabled");
                    BirchConfig.save();
                    feedback(ctx.getSource(), "§7Route overlay: " + onOff(BirchConfig.get().routeEnabled));
                    return 1;
                }))
                // /route <name> — run that route. Brigadier tries the boolean
                // above first and falls through to here when the word is not
                // "true" or "false", so both readings of /route <word> work.
                .then(ClientCommands.argument("route", StringArgumentType.word()).executes(ctx ->
                        follow(ctx.getSource(), routeBuilder,
                                StringArgumentType.getString(ctx, "route"))))
                .then(ClientCommands.literal("start")
                        .then(ClientCommands.argument("name", StringArgumentType.word()).executes(ctx -> {
                            String name = StringArgumentType.getString(ctx, "name");
                            recorder.start(name);
                            feedback(ctx.getSource(), "§aRecording §f" + name
                                    + "§a. Chop trees in the order you want them; §f/route stop§a when done.");
                            feedback(ctx.getSource(), "§8Needs at least "
                                    + RouteLibrary.MIN_STOPS + " trees to save.");
                            if (RESERVED.contains(name.toLowerCase(java.util.Locale.ROOT))) {
                                feedback(ctx.getSource(), "§e  Heads up: §f/route " + name
                                        + "§e is already a command, so it will not launch this route.");
                                feedback(ctx.getSource(), "§8  Use §f/route use " + name
                                        + "§8, or §f/route cancel§8 and pick another name.");
                            }
                            return 1;
                        })))
                .then(ClientCommands.literal("stop").executes(ctx -> {
                    if (!recorder.isRecording()) {
                        feedback(ctx.getSource(), "§cNot recording. Start one with §f/route start <name>");
                        return 0;
                    }
                    int captured = recorder.getCount();
                    RecordedRoute saved = recorder.stop();
                    if (saved == null) {
                        feedback(ctx.getSource(), "§cDiscarded — only " + captured + " tree(s), need "
                                + RouteLibrary.MIN_STOPS + ".");
                        return 0;
                    }
                    RouteLibrary.setActive(saved.name);
                    routeBuilder.resetCommitment();
                    feedback(ctx.getSource(), "§aSaved §f" + saved.name + "§a with "
                            + saved.size() + " stops, and made it active.");
                    describe(ctx.getSource(), saved, regenTracker.getRegenSeconds());
                    return 1;
                }))
                .then(ClientCommands.literal("cancel").executes(ctx -> {
                    recorder.cancel();
                    feedback(ctx.getSource(), "§7Recording cancelled.");
                    return 1;
                }))
                .then(ClientCommands.literal("compile")
                        .executes(ctx -> compile(ctx.getSource(), routeBuilder, regenTracker, "optimized"))
                        .then(ClientCommands.argument("name", StringArgumentType.word()).executes(ctx ->
                                compile(ctx.getSource(), routeBuilder, regenTracker,
                                        StringArgumentType.getString(ctx, "name")))))
                .then(ClientCommands.literal("list").executes(ctx -> {
                    list(ctx.getSource(), regenTracker.getRegenSeconds());
                    return 1;
                }))
                .then(ClientCommands.literal("use")
                        .then(ClientCommands.argument("name", StringArgumentType.word()).executes(ctx ->
                                follow(ctx.getSource(), routeBuilder,
                                        StringArgumentType.getString(ctx, "name")))))
                .then(ClientCommands.literal("strict")
                        .then(ClientCommands.argument("enabled", BoolArgumentType.bool()).executes(ctx -> {
                            BirchConfig.get().strictRoute = BoolArgumentType.getBool(ctx, "enabled");
                            BirchConfig.save();
                            routeBuilder.resetCommitment();
                            feedback(ctx.getSource(), BirchConfig.get().strictRoute
                                    ? "§aStrict§7: following your recorded order exactly."
                                    : "§eRelaxed§7: a cleared tree hands over to the nearest ready one.");
                            feedback(ctx.getSource(), "§8Either way you are never moved on "
                                    + "from a tree with wood still on it.");
                            return 1;
                        })))
                .then(ClientCommands.literal("minlogs")
                        .then(ClientCommands.argument("logs", IntegerArgumentType.integer(1, 8))
                                .executes(ctx -> {
                                    int logs = IntegerArgumentType.getInteger(ctx, "logs");
                                    BirchConfig.get().minTreeLogs = logs;
                                    BirchConfig.save();
                                    feedback(ctx.getSource(), "§7Marking anything with §f" + logs
                                            + "§7 or more birch within reach.");
                                    feedback(ctx.getSource(), logs <= 1
                                            ? "§8  Every piece of birch counts, decoration included."
                                            : "§8  Trunks and log piles count; single stray logs do not.");
                                    feedback(ctx.getSource(), "§8  Walk away and back to re-scan.");
                                    return 1;
                                })))
                .then(ClientCommands.literal("export")
                        .then(ClientCommands.argument("name", StringArgumentType.word()).executes(ctx -> {
                            String name = StringArgumentType.getString(ctx, "name");
                            RecordedRoute route = RouteLibrary.get(name);
                            if (route == null) {
                                feedback(ctx.getSource(), "§cNo route called §f" + name);
                                return 0;
                            }
                            String code = RouteCodec.encode(route);
                            boolean copied = copyToClipboard(code);
                            feedback(ctx.getSource(), "§6§lBirch Optimizer §7— exported §f" + name);
                            feedback(ctx.getSource(), copied
                                    ? "§aCopied to your clipboard §8(" + code.length() + " characters)"
                                    : "§eCould not reach the clipboard; the code is below.");
                            if (!copied) {
                                feedback(ctx.getSource(), "§7" + code);
                            }
                            feedback(ctx.getSource(), "§8Whoever you send it to runs "
                                    + "§f/route import <code>§8.");
                            return 1;
                        })))
                .then(ClientCommands.literal("import")
                        .then(ClientCommands.argument("code", StringArgumentType.greedyString())
                                .executes(ctx -> importRoute(ctx.getSource(), routeBuilder,
                                        StringArgumentType.getString(ctx, "code"))))
                        .executes(ctx -> importRoute(ctx.getSource(), routeBuilder, clipboard())))
                .then(ClientCommands.literal("gui").executes(ctx -> {
                    net.minecraft.client.Minecraft client = net.minecraft.client.Minecraft.getInstance();
                    if (client != null) {
                        client.execute(() -> client.setScreen(new com.birchmod.gui.BirchScreen(null)));
                    }
                    return 1;
                }))
                .then(ClientCommands.literal("stats").executes(ctx -> {
                    learned(ctx.getSource());
                    return 1;
                }))
                .then(ClientCommands.literal("help").executes(ctx -> {
                    Help.route(ctx.getSource());
                    return 1;
                }))
                .then(ClientCommands.literal("setdefault")
                        .then(ClientCommands.argument("name", StringArgumentType.word()).executes(ctx -> {
                            String name = StringArgumentType.getString(ctx, "name");
                            RecordedRoute route = RouteLibrary.get(name);
                            if (route == null) {
                                feedback(ctx.getSource(), "§cNo route called §f" + name);
                                return 0;
                            }
                            RouteLibrary.setDefault(route.name);
                            routeBuilder.resetCommitment();
                            feedback(ctx.getSource(), "§a§f" + route.name
                                    + "§a is your default, and you are on it now.");
                            feedback(ctx.getSource(), "§8It comes back every login, "
                                    + "whatever you were following when you left.");
                            return 1;
                        })))
                .then(ClientCommands.literal("default").executes(ctx -> {
                    RecordedRoute restored = RouteLibrary.applyDefault();
                    if (restored == null) {
                        feedback(ctx.getSource(), "§cNo default set. §f/route setdefault <name>");
                        return 0;
                    }
                    routeBuilder.resetCommitment();
                    feedback(ctx.getSource(), "§aBack on your default: §f" + restored.name);
                    return 1;
                }))
                .then(ClientCommands.literal("auto").executes(ctx -> {
                    RouteLibrary.clearActive();
                    routeBuilder.resetCommitment();
                    feedback(ctx.getSource(), "§7Back to planning routes automatically.");
                    String preferred = RouteLibrary.getDefaultName();
                    if (preferred != null) {
                        feedback(ctx.getSource(), "§8Your default §f" + preferred
                                + "§8 comes back next login. §f/route setdefault§8 changes it.");
                    }
                    return 1;
                }))
                .then(ClientCommands.literal("best").executes(ctx -> {
                    double regen = regenTracker.getRegenSeconds();
                    RecordedRoute best = RouteLibrary.best(regen);
                    if (best == null) {
                        feedback(ctx.getSource(), "§cNo saved routes yet. Record one with §f/route start <name>");
                        return 0;
                    }
                    RouteLibrary.setActive(best.name);
                    routeBuilder.resetCommitment();
                    feedback(ctx.getSource(), "§aBest route: §f" + best.name);
                    describe(ctx.getSource(), best, regen);
                    return 1;
                }))
                .then(ClientCommands.literal("delete")
                        .then(ClientCommands.argument("name", StringArgumentType.word()).executes(ctx -> {
                            String name = StringArgumentType.getString(ctx, "name");
                            boolean removed = RouteLibrary.delete(name);
                            feedback(ctx.getSource(), removed
                                    ? "§7Deleted §f" + name
                                    : "§cNo route called §f" + name);
                            return removed ? 1 : 0;
                        })))
                .then(ClientCommands.literal("tracers")
                        .then(ClientCommands.argument("enabled", BoolArgumentType.bool()).executes(ctx -> {
                            BirchConfig.get().tracersEnabled = BoolArgumentType.getBool(ctx, "enabled");
                            BirchConfig.save();
                            feedback(ctx.getSource(), "§7Tracers: " + onOff(BirchConfig.get().tracersEnabled));
                            return 1;
                        })))
                .then(ClientCommands.literal("path")
                        .then(ClientCommands.argument("enabled", BoolArgumentType.bool()).executes(ctx -> {
                            BirchConfig.get().showFullPath = BoolArgumentType.getBool(ctx, "enabled");
                            BirchConfig.save();
                            feedback(ctx.getSource(), BirchConfig.get().showFullPath
                                    ? "§7Showing the §fwhole loop§7."
                                    : "§7Showing the §ftree you are on§7 and a blue line "
                                            + "to the §fnext one§7.");
                            return 1;
                        })))
                .then(ClientCommands.literal("chain")
                        .then(ClientCommands.argument("enabled", BoolArgumentType.bool()).executes(ctx -> {
                            BirchConfig.get().chainTracers = BoolArgumentType.getBool(ctx, "enabled");
                            BirchConfig.save();
                            feedback(ctx.getSource(), "§7Chained tracers: " + onOff(BirchConfig.get().chainTracers));
                            return 1;
                        })))
                .then(ClientCommands.literal("length")
                        .then(ClientCommands.argument("stops",
                                        IntegerArgumentType.integer(1, BirchConfig.MAX_ROUTE_LENGTH))
                                .executes(ctx -> {
                                    int stops = IntegerArgumentType.getInteger(ctx, "stops");
                                    BirchConfig.get().routeLength = stops;
                                    BirchConfig.save();
                                    feedback(ctx.getSource(), "§7Showing the next §f" + stops
                                            + "§7 tree(s)" + (stops > 1
                                            ? " §8(" + (stops - 1) + " blue line(s) ahead)"
                                            : " §8(no line onward at 1)"));
                                    if (BirchConfig.get().showFullPath) {
                                        feedback(ctx.getSource(), "§e  Note: §f/route path§e is on, "
                                                + "so the whole loop is drawn and this is ignored. "
                                                + "§f/route path false§e to use the length.");
                                    }
                                    return 1;
                                })))
                .then(ClientCommands.literal("width")
                        .then(ClientCommands.argument("width", DoubleArgumentType.doubleArg(0.5, 10.0))
                                .executes(ctx -> {
                                    BirchConfig.get().lineWidth = DoubleArgumentType.getDouble(ctx, "width");
                                    BirchConfig.save();
                                    feedback(ctx.getSource(), "§7Line width: §f"
                                            + SEC_FMT.format(BirchConfig.get().lineWidth));
                                    return 1;
                                })))
                .then(ClientCommands.literal("filled")
                        .then(ClientCommands.argument("enabled", BoolArgumentType.bool()).executes(ctx -> {
                            BirchConfig.get().filledHighlight = BoolArgumentType.getBool(ctx, "enabled");
                            BirchConfig.save();
                            feedback(ctx.getSource(), "§7Filled highlight: "
                                    + onOff(BirchConfig.get().filledHighlight));
                            return 1;
                        })))
                .then(ClientCommands.literal("labels")
                        .then(ClientCommands.argument("enabled", BoolArgumentType.bool()).executes(ctx -> {
                            BirchConfig.get().showRouteLabels = BoolArgumentType.getBool(ctx, "enabled");
                            BirchConfig.save();
                            feedback(ctx.getSource(), "§7Route labels: "
                                    + onOff(BirchConfig.get().showRouteLabels));
                            return 1;
                        })))
                .then(ClientCommands.literal("center")
                        .then(ClientCommands.argument("height", IntegerArgumentType.integer(0, 12))
                                .executes(ctx -> {
                                    BirchConfig.get().treeCenterHeight =
                                            IntegerArgumentType.getInteger(ctx, "height");
                                    BirchConfig.save();
                                    feedback(ctx.getSource(), "§7Tree centre height: §f+"
                                            + BirchConfig.get().treeCenterHeight + " blocks");
                                    return 1;
                                }))));
    }

    /**
     * Take a route somebody else recorded.
     *
     * Never overwrites: an imported name that is already taken gets a number,
     * because losing a route you walked yourself to a paste is not a trade
     * anyone would accept.
     */
    private static int importRoute(FabricClientCommandSource source,
                                   RouteBuilder routeBuilder,
                                   String code) {
        if (code == null || code.isBlank()) {
            feedback(source, "§cNothing to import. §f/route import <code>§c, "
                    + "or copy a code first and run §f/route import§c on its own.");
            return 0;
        }
        RecordedRoute route;
        try {
            route = RouteCodec.decode(code.trim());
        } catch (RouteCodec.CodecException e) {
            feedback(source, "§c" + e.getMessage());
            return 0;
        }

        String wanted = route.name;
        route.name = RouteCodec.freeName(wanted, RouteLibrary::exists);
        RouteLibrary.save(route);
        RouteLibrary.setActive(route.name);
        routeBuilder.resetCommitment();

        feedback(source, "§aImported §f" + route.name + "§a with "
                + route.size() + " stops, and you are on it now.");
        if (!route.name.equals(wanted)) {
            feedback(source, "§8You already had a §f" + wanted
                    + "§8, so this one was saved as §f" + route.name + "§8.");
        }
        feedback(source, "§8§f/route setdefault " + route.name
                + "§8 to keep it after a restart.");
        return 1;
    }

    private static String clipboard() {
        net.minecraft.client.Minecraft client = net.minecraft.client.Minecraft.getInstance();
        if (client == null) {
            return null;
        }
        try {
            return client.keyboardHandler.getClipboard();
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean copyToClipboard(String text) {
        net.minecraft.client.Minecraft client = net.minecraft.client.Minecraft.getInstance();
        if (client == null) {
            return false;
        }
        try {
            client.keyboardHandler.setClipboard(text);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** Start following a saved route by name. */
    private static int follow(FabricClientCommandSource source,
                              RouteBuilder routeBuilder,
                              String name) {
        RecordedRoute route = RouteLibrary.get(name);
        if (route == null) {
            feedback(source, "§cNo route called §f" + name);
            feedback(source, "§8§f/route list§8 shows what you have saved.");
            return 0;
        }
        RouteLibrary.setActive(route.name);
        routeBuilder.resetCommitment();
        feedback(source, "§aFollowing §f" + route.name + "§a (" + route.size() + " stops).");

        String preferred = RouteLibrary.getDefaultName();
        if (preferred != null && !preferred.equalsIgnoreCase(route.name)) {
            feedback(source, "§8Your default is still §f" + preferred
                    + "§8, and comes back next login. §f/route setdefault "
                    + route.name + "§8 to change that.");
        }
        return 1;
    }

    private static void show(FabricClientCommandSource source,
                             RouteBuilder routeBuilder,
                             TreeRegenTracker regenTracker) {
        feedback(source, "§6§lBirch Optimizer §7— route");

        if (routeBuilder.isEmpty()) {
            feedback(source, "§8No trees in range yet — walk into the grove.");
            return;
        }

        BirchConfig config = BirchConfig.get();
        feedback(source, "§7Overlay: " + onOff(config.routeEnabled)
                + " §7Tracers: " + onOff(config.tracersEnabled)
                + " §7Full path: " + onOff(config.showFullPath));

        performance(source, regenTracker.getRegenSeconds());

        for (Stop stop : routeBuilder.getRoute()) {
            BlockPos center = stop.center();
            String eta = stop.etaSeconds() <= 0.01
                    ? "§aready"
                    : "§e" + SEC_FMT.format(stop.etaSeconds()) + "s";
            feedback(source, "§7 " + stop.order() + ". §f"
                    + center.getX() + ", " + center.getY() + ", " + center.getZ()
                    + " §8— " + eta + " " + woodNote(stop));
        }
    }

    /** How much is left standing at a stop — the reason it is still a stop. */
    private static String woodNote(Stop stop) {
        if (!stop.isKnown()) {
            return "§8(not in range yet)";
        }
        if (stop.woodLeft() == 0) {
            return "§8(cleared)";
        }
        return (stop.unfinished() ? "§c" : "§a") + stop.woodLeft() + " log(s)"
                + (stop.unfinished() ? " §cleft behind" : "");
    }



    /**
     * Merge every saved route into one optimised loop and activate it.
     *
     * The compiled route is saved under its own name, so the recordings it was
     * built from stay untouched and can be re-compiled later as more are added.
     */
    private static int compile(FabricClientCommandSource source,
                               RouteBuilder routeBuilder,
                               TreeRegenTracker regenTracker,
                               String name) {
        var sources = RouteLibrary.all();
        // Never fold a previous compile back into the next one.
        sources.removeIf(route -> route.name.equalsIgnoreCase(name));

        if (sources.isEmpty()) {
            feedback(source, "§cNothing to compile. Record routes with §f/route start <name>");
            return 0;
        }

        double regen = regenTracker.getRegenSeconds();
        RouteOptimizer.Result result = RouteOptimizer.compile(sources, regen, name);
        if (result == null) {
            feedback(source, "§cNot enough distinct trees across those routes (need "
                    + RouteLibrary.MIN_STOPS + ").");
            return 0;
        }

        RouteLibrary.save(result.route());
        RouteLibrary.setActive(name);
        routeBuilder.resetCommitment();

        feedback(source, "§6§lBirch Optimizer §7— compiled §f" + name);
        feedback(source, "§7  Merged §f" + result.sourceRoutes() + "§7 routes into §f"
                + result.uniqueTrees() + "§7 distinct trees");
        if (result.droppedOneOffs() > 0) {
            feedback(source, "§7  Dropped §f" + result.droppedOneOffs()
                    + "§7 one-off detour(s) you only took once");
        }
        if (result.rescuedByUse() > 0) {
            feedback(source, "§7  Kept §f" + result.rescuedByUse()
                    + "§7 tree(s) one recording missed but you chop constantly");
        }
        feedback(source, "§7  Kept §f" + result.chosen()
                + "§7 agreed tree(s), reordered to shorten the loop");
        feedback(source, "§7  Lap: §f" + SEC_FMT.format(result.lapSeconds())
                + "s §7vs regen §f" + SEC_FMT.format(regen) + "s");

        int percentMeasured = (int) Math.round(result.measuredFraction() * 100.0);
        feedback(source, "§7  Timed from §f" + result.measuredLegs() + "§7/§f"
                + result.totalLegs() + "§7 legs you have actually walked §8("
                + percentMeasured + "% measured, rest estimated)");

        if (result.lapSeconds() + 0.5 < regen) {
            feedback(source, "§e  Lap still shorter than regen — record more trees to fill the wait.");
        } else {
            feedback(source, "§a  Lap covers the regen: trees are ready as you reach them.");
        }
        feedback(source, "§7  Throughput: §f" + SEC_FMT.format(result.treesPerMinute()) + " trees/min");
        feedback(source, "§aNow following it. §8/route auto to go back to automatic planning.");
        return 1;
    }

    /** All saved routes with their expected throughput, best first. */
    private static void list(FabricClientCommandSource source, double regenSeconds) {
        feedback(source, "§6§lBirch Optimizer §7— saved routes");
        var routes = RouteLibrary.all();
        if (routes.isEmpty()) {
            feedback(source, "§8None yet. Record one with §f/route start <name>");
            return;
        }
        String active = RouteLibrary.getActiveName();
        String defaultName = RouteLibrary.getDefaultName();

        routes.sort(RouteLibrary.ranking(regenSeconds));

        for (RecordedRoute route : routes) {
            RouteLibrary.Score score = RouteLibrary.score(route, regenSeconds);
            String marker = route.name.equalsIgnoreCase(active) ? "§a> " : "§7  ";
            if (route.name.equalsIgnoreCase(defaultName)) {
                marker = marker + "§6* ";
            }
            String best = route.bestLapSeconds > 0.0
                    ? " §8· best lap §f" + LapTracker.format(route.bestLapSeconds)
                    : " §8· never lapped";
            feedback(source, marker + "§f" + route.name + " §7— " + score.stops() + " stops, "
                    + SEC_FMT.format(score.treesPerMinute()) + " trees/min" + best);
        }
        feedback(source, "§8§a>§8 following · §6*§8 default. Run one with §f/route <name>§8, "
                + "keep it with §f/route setdefault <name>§8.");
    }

    /** Explain why a route scores as it does. */
    private static void describe(FabricClientCommandSource source, RecordedRoute route, double regenSeconds) {
        RouteLibrary.Score score = RouteLibrary.score(route, regenSeconds);
        feedback(source, "§7  Lap: §f" + SEC_FMT.format(score.loopDistance()) + " blocks, "
                + SEC_FMT.format(score.lapSeconds()) + "s walking");
        feedback(source, "§7  Regen: §f" + SEC_FMT.format(regenSeconds) + "s");

        if (score.lapSeconds() < regenSeconds) {
            feedback(source, "§e  Lap is shorter than regen — you will arrive before trees are back.");
            feedback(source, "§8  Add stops to fill the wait; the cycle is capped at "
                    + SEC_FMT.format(score.cycleSeconds()) + "s either way.");
        } else {
            feedback(source, "§a  Lap covers the regen — trees are ready as you reach them.");
        }
        feedback(source, "§7  Throughput: §f" + SEC_FMT.format(score.treesPerMinute()) + " trees/min");
    }

    /**
     * Compare what the active route promised against what is actually being
     * felled, so a plan that looks good on paper can be checked against the
     * ground.
     */
    private static void performance(FabricClientCommandSource source, double regenSeconds) {
        RecordedRoute active = RouteLibrary.getActive();
        double actual = SessionStats.getRecentTreesPerMinute();

        if (active == null) {
            if (actual > 0.0) {
                feedback(source, "§7Actual: §f" + SEC_FMT.format(actual)
                        + "§7 trees/min §8(last few minutes)");
            }
            return;
        }

        speedNote(source);
        RouteLibrary.Score predicted = RouteLibrary.score(active, regenSeconds);
        if (actual <= 0.0) {
            feedback(source, "§7Following §f" + active.name + "§7, predicted §f"
                    + SEC_FMT.format(predicted.treesPerMinute()) + "§7 trees/min");
            return;
        }

        int percent = (int) Math.round(actual / Math.max(predicted.treesPerMinute(), 0.001) * 100.0);
        String colour = percent >= 85 ? "§a" : percent >= 60 ? "§e" : "§c";

        feedback(source, "§7Following §f" + active.name);
        feedback(source, "§7  Predicted: §f" + SEC_FMT.format(predicted.treesPerMinute())
                + "§7 trees/min §8· §7actual: " + colour + SEC_FMT.format(actual)
                + "§7 trees/min " + colour + "(" + percent + "%)");

        if (percent < 60) {
            feedback(source, "§8  Well under plan — walking the loop slower than assumed, "
                    + "or trees are being missed.");
        }
        laps(source, active);
    }

    /**
     * Laps actually walked. The predicted figures above are a model; this is
     * the clock, and it is the only line that can tell you whether a change you
     * made to the route helped.
     */
    private static void laps(FabricClientCommandSource source, RecordedRoute active) {
        LapTracker tracker = BirchMod.lapTracker;
        if (tracker == null) {
            return;
        }
        double best = active.bestLapSeconds;

        if (tracker.getLapsCompleted() == 0) {
            feedback(source, best > 0.0
                    ? "§7  Best lap: §f" + LapTracker.format(best) + " §8(from an earlier session)"
                    : "§8  No full lap yet — finish " + active.size()
                            + " trees on this route to time one.");
        } else {
            feedback(source, "§7  Laps: §f" + tracker.getLapsCompleted()
                    + "§7 · last §f" + LapTracker.format(tracker.getLastLapSeconds())
                    + "§7 · avg §f" + LapTracker.format(tracker.getAverageLapSeconds())
                    + "§7 · best §a" + LapTracker.format(best));
        }

        double elapsed = tracker.getElapsedSeconds();
        if (elapsed >= 0.0 && tracker.getLapSize() > 0) {
            feedback(source, "§7  This lap: §f" + tracker.getProgress() + "/" + tracker.getLapSize()
                    + " trees, " + LapTracker.format(elapsed) + " so far");
        }
    }

    /**
     * What the mod has learned by watching you forage, and how much of the
     * planning still rests on estimates.
     */
    private static void learned(FabricClientCommandSource source) {
        feedback(source, "§6§lBirch Optimizer §7— what it has learned");

        int trees = TravelGraph.nodeCount();
        int chops = TravelGraph.totalChops();
        int measured = TravelGraph.measuredLegCount();
        int legs = TravelGraph.legCount();

        feedback(source, "§7Trees known: §f" + trees + " §8(" + chops + " chops recorded)");
        feedback(source, "§7Legs timed: §f" + measured + "§7 of §f" + legs
                + " §8(needs " + TravelGraph.MIN_LEG_SAMPLES + " passes each to count)");
        speedNote(source);

        if (measured == 0) {
            feedback(source, "§8Nothing measured yet — plans are still estimates from "
                    + "distance. Forage a few laps and they sharpen on their own.");
        } else {
            feedback(source, "§8Compiled routes are ordered by these times, not by "
                    + "straight-line distance.");
        }
        feedback(source, "§7Route order: " + (BirchConfig.get().strictRoute
                ? "§astrict §8(exactly as recorded)"
                : "§erelaxed §8(cleared stops hand over to the nearest ready tree)"));
    }

    /** Show the travel speed the estimates are built on, and where it came from. */
    private static void speedNote(FabricClientCommandSource source) {
        double speed = RouteLibrary.walkSpeed();
        long samples = SessionStats.getWalkSamples();
        boolean measured = SessionStats.getMeasuredWalkSpeed() > 0.0 && speed
                != RouteLibrary.DEFAULT_WALK_BLOCKS_PER_SECOND;

        feedback(source, "§7Travel speed: §f" + SEC_FMT.format(speed) + "§7 blocks/s "
                + (measured
                ? "§8(measured from " + samples + " samples)"
                : "§8(default — still measuring, " + samples + " samples so far)"));
    }

    private static String onOff(boolean value) {
        return value ? "§aon" : "§coff";
    }

    private static void feedback(FabricClientCommandSource source, String message) {
        source.sendFeedback(Component.literal(message));
    }
}
