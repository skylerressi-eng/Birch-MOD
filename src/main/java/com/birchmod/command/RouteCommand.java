package com.birchmod.command;

import java.text.DecimalFormat;

import com.birchmod.config.BirchConfig;
import com.birchmod.route.RecordedRoute;
import com.birchmod.route.RouteBuilder;
import com.birchmod.route.RouteLibrary;
import com.birchmod.route.RouteRecorder;
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
                    show(ctx.getSource(), routeBuilder);
                    return 1;
                })
                .then(ClientCommands.argument("enabled", BoolArgumentType.bool()).executes(ctx -> {
                    BirchConfig.get().routeEnabled = BoolArgumentType.getBool(ctx, "enabled");
                    BirchConfig.save();
                    feedback(ctx.getSource(), "§7Route overlay: " + onOff(BirchConfig.get().routeEnabled));
                    return 1;
                }))
                .then(ClientCommands.literal("start")
                        .then(ClientCommands.argument("name", StringArgumentType.word()).executes(ctx -> {
                            String name = StringArgumentType.getString(ctx, "name");
                            recorder.start(name);
                            feedback(ctx.getSource(), "§aRecording §f" + name
                                    + "§a. Chop trees in the order you want them; §f/route stop§a when done.");
                            feedback(ctx.getSource(), "§8Needs at least "
                                    + RouteLibrary.MIN_STOPS + " trees to save.");
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
                .then(ClientCommands.literal("list").executes(ctx -> {
                    list(ctx.getSource(), regenTracker.getRegenSeconds());
                    return 1;
                }))
                .then(ClientCommands.literal("use")
                        .then(ClientCommands.argument("name", StringArgumentType.word()).executes(ctx -> {
                            String name = StringArgumentType.getString(ctx, "name");
                            RecordedRoute route = RouteLibrary.get(name);
                            if (route == null) {
                                feedback(ctx.getSource(), "§cNo route called §f" + name);
                                return 0;
                            }
                            RouteLibrary.setActive(route.name);
                            routeBuilder.resetCommitment();
                            feedback(ctx.getSource(), "§aFollowing §f" + route.name
                                    + "§a (" + route.size() + " stops).");
                            return 1;
                        })))
                .then(ClientCommands.literal("auto").executes(ctx -> {
                    RouteLibrary.clearActive();
                    routeBuilder.resetCommitment();
                    feedback(ctx.getSource(), "§7Back to planning routes automatically.");
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
                .then(ClientCommands.literal("chain")
                        .then(ClientCommands.argument("enabled", BoolArgumentType.bool()).executes(ctx -> {
                            BirchConfig.get().chainTracers = BoolArgumentType.getBool(ctx, "enabled");
                            BirchConfig.save();
                            feedback(ctx.getSource(), "§7Chained tracers: " + onOff(BirchConfig.get().chainTracers));
                            return 1;
                        })))
                .then(ClientCommands.literal("length")
                        .then(ClientCommands.argument("stops", IntegerArgumentType.integer(1, 16))
                                .executes(ctx -> {
                                    BirchConfig.get().routeLength = IntegerArgumentType.getInteger(ctx, "stops");
                                    BirchConfig.save();
                                    feedback(ctx.getSource(), "§7Route length: §f"
                                            + BirchConfig.get().routeLength + " stops");
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

    private static void show(FabricClientCommandSource source, RouteBuilder routeBuilder) {
        feedback(source, "§6§lBirch Optimizer §7— route");

        if (routeBuilder.isEmpty()) {
            feedback(source, "§8No trees in range yet — walk into the grove.");
            return;
        }

        BirchConfig config = BirchConfig.get();
        feedback(source, "§7Overlay: " + onOff(config.routeEnabled)
                + " §7Tracers: " + onOff(config.tracersEnabled)
                + " §7Chain: " + onOff(config.chainTracers));

        for (RouteBuilder.Stop stop : routeBuilder.getRoute()) {
            BlockPos center = stop.center();
            String eta = stop.etaSeconds() <= 0.01
                    ? "§aready"
                    : "§e" + SEC_FMT.format(stop.etaSeconds()) + "s";
            feedback(source, "§7 " + stop.order() + ". §f"
                    + center.getX() + ", " + center.getY() + ", " + center.getZ()
                    + " §8— " + eta);
        }
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

        routes.sort((a, b) -> Double.compare(
                RouteLibrary.score(b, regenSeconds).treesPerMinute(),
                RouteLibrary.score(a, regenSeconds).treesPerMinute()));

        for (RecordedRoute route : routes) {
            RouteLibrary.Score score = RouteLibrary.score(route, regenSeconds);
            String marker = route.name.equalsIgnoreCase(active) ? "§a> " : "§7  ";
            feedback(source, marker + "§f" + route.name + " §7— " + score.stops() + " stops, "
                    + SEC_FMT.format(score.treesPerMinute()) + " trees/min");
        }
        feedback(source, "§8Scored against a " + SEC_FMT.format(regenSeconds) + "s regen. "
                + "§f/route best§8 picks the top one.");
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

    private static String onOff(boolean value) {
        return value ? "§aon" : "§coff";
    }

    private static void feedback(FabricClientCommandSource source, String message) {
        source.sendFeedback(Component.literal(message));
    }
}
