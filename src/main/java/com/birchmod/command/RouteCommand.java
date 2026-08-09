package com.birchmod.command;

import java.text.DecimalFormat;

import com.birchmod.config.BirchConfig;
import com.birchmod.route.RouteBuilder;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;

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

    public static void register(RouteBuilder routeBuilder) {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, access) ->
                build(dispatcher, routeBuilder));
    }

    private static void build(CommandDispatcher<FabricClientCommandSource> dispatcher,
                              RouteBuilder routeBuilder) {
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

    private static String onOff(boolean value) {
        return value ? "§aon" : "§coff";
    }

    private static void feedback(FabricClientCommandSource source, String message) {
        source.sendFeedback(Component.literal(message));
    }
}
