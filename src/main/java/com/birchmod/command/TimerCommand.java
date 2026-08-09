package com.birchmod.command;

import java.text.DecimalFormat;

import com.birchmod.config.BirchConfig;
import com.birchmod.tracking.TreeRegenTracker;

import com.mojang.brigadier.CommandDispatcher;

import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.network.chat.Component;

/**
 * Client-side {@code /timer} command.
 *
 * <ul>
 *   <li>{@code /timer mode}  — toggle the floating in-world tree timers</li>
 *   <li>{@code /timer reset} — forget tracked trees and measurements</li>
 *   <li>{@code /timer}       — show current status</li>
 * </ul>
 */
public final class TimerCommand {

    private static final DecimalFormat SEC_FMT = new DecimalFormat("#0.0");

    private TimerCommand() {
    }

    public static void register(TreeRegenTracker regenTracker) {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, access) -> build(dispatcher, regenTracker));
    }

    private static void build(CommandDispatcher<FabricClientCommandSource> dispatcher,
                              TreeRegenTracker regenTracker) {
        dispatcher.register(ClientCommands.literal("timer")
                .then(ClientCommands.literal("mode").executes(ctx -> {
                    BirchConfig config = BirchConfig.get();
                    config.worldTimersEnabled = !config.worldTimersEnabled;
                    BirchConfig.save();

                    feedback(ctx.getSource(), config.worldTimersEnabled
                            ? "§aTimer mode ON §7— floating timers shown above downed trees."
                            : "§cTimer mode OFF §7— floating timers hidden.");
                    return 1;
                }))
                .then(ClientCommands.literal("reset").executes(ctx -> {
                    regenTracker.reset();
                    feedback(ctx.getSource(), "§aRegen timers reset §7— tracked trees and measurements cleared.");
                    return 1;
                }))
                .executes(ctx -> {
                    status(ctx.getSource(), regenTracker);
                    return 1;
                }));
    }

    private static void status(FabricClientCommandSource source, TreeRegenTracker regenTracker) {
        BirchConfig config = BirchConfig.get();

        feedback(source, "§6§lBirch Optimizer §7— timer status");
        feedback(source, "§7Timer mode: " + (config.worldTimersEnabled ? "§aON" : "§cOFF"));
        feedback(source, "§7Trees tracked: §f" + regenTracker.getTrackedCount()
                + " §7(downed: §f" + regenTracker.getDownedTrees().size() + "§7)");

        if (regenTracker.isCalibrated()) {
            feedback(source, "§7Measured regen: §f" + SEC_FMT.format(regenTracker.getRegenSeconds())
                    + "s §7from §f" + regenTracker.getMeasurementCount() + "§7 tree(s)");
            feedback(source, "§7Last measurement: §f"
                    + SEC_FMT.format(regenTracker.getLastMeasurementSeconds()) + "s");
        } else {
            feedback(source, "§7Regen estimate: §f" + SEC_FMT.format(regenTracker.getRegenSeconds())
                    + "s §8(not yet measured — chop a tree and watch it regrow)");
        }
    }

    private static void feedback(FabricClientCommandSource source, String message) {
        source.sendFeedback(Component.literal(message));
    }
}
