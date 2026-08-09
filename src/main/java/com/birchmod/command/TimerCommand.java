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
        feedback(source, "§7Tracking: §aalways on §8(automatic, nothing to start)");
        feedback(source, "§7Floating labels: " + (config.worldTimersEnabled ? "§aON" : "§cOFF"));
        feedback(source, "§7Trees in range: §f" + regenTracker.getTrackedCount()
                + " §8(standing " + regenTracker.getReadyCount()
                + ", regrowing " + regenTracker.getDownedTrees().size() + ")");

        if (regenTracker.isCalibrated()) {
            feedback(source, "§e§lMeasured regen");
            feedback(source, "§7  Using: §f" + SEC_FMT.format(regenTracker.getRegenSeconds()) + "s");
            feedback(source, "§7  Mean: §f" + SEC_FMT.format(regenTracker.getMeanRegenSeconds())
                    + "s §7over §f" + regenTracker.getMeasurementCount() + "§7 cycles");
            feedback(source, "§7  Fastest: §a" + SEC_FMT.format(regenTracker.getFastestRegenSeconds())
                    + "s §7Slowest: §c" + SEC_FMT.format(regenTracker.getSlowestRegenSeconds()) + "s");
            feedback(source, "§7  Last: §f" + SEC_FMT.format(regenTracker.getLastMeasurementSeconds()) + "s");
        } else {
            feedback(source, "§7Regen estimate: §f" + SEC_FMT.format(regenTracker.getRegenSeconds())
                    + "s §8(no cycle measured yet — it will calibrate itself)");
        }
    }

    private static void feedback(FabricClientCommandSource source, String message) {
        source.sendFeedback(Component.literal(message));
    }
}
