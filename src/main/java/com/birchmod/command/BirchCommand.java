package com.birchmod.command;

import java.text.DecimalFormat;

import com.birchmod.BirchMod;
import com.birchmod.api.BazaarManager;
import com.birchmod.api.LeaderboardManager;
import com.birchmod.config.BirchConfig;
import com.birchmod.stats.SessionStats;
import com.birchmod.tracking.BirchTracker;
import com.birchmod.tracking.CollectionRankTracker;
import com.birchmod.tracking.TreeRegenTracker;
import com.birchmod.util.Guard;
import com.birchmod.util.SkyblockDetector;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;

import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.network.chat.Component;

/**
 * Client-side {@code /birch} command — the main interface to the mod, so
 * nothing requires hand-editing JSON.
 */
public final class BirchCommand {

    private static final DecimalFormat INT_FMT = new DecimalFormat("#,##0");
    private static final DecimalFormat DEC_FMT = new DecimalFormat("#,##0.0");

    private BirchCommand() {
    }

    public static void register(BirchTracker tracker,
                                TreeRegenTracker regenTracker,
                                CollectionRankTracker collectionRank,
                                BazaarManager bazaar,
                                LeaderboardManager leaderboard) {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, access) ->
                build(dispatcher, tracker, regenTracker, collectionRank, bazaar, leaderboard));
    }

    private static void build(CommandDispatcher<FabricClientCommandSource> dispatcher,
                              BirchTracker tracker,
                              TreeRegenTracker regenTracker,
                              CollectionRankTracker collectionRank,
                              BazaarManager bazaar,
                              LeaderboardManager leaderboard) {

        dispatcher.register(ClientCommands.literal("birch")
                // /birch — overview
                .executes(ctx -> {
                    overview(ctx.getSource(), tracker, regenTracker, bazaar);
                    return 1;
                })

                // /birch stats
                .then(ClientCommands.literal("stats").executes(ctx -> {
                    stats(ctx.getSource(), tracker, regenTracker);
                    return 1;
                }))

                // /birch bazaar
                .then(ClientCommands.literal("bazaar").executes(ctx -> {
                    bazaarInfo(ctx.getSource(), bazaar);
                    return 1;
                }))

                // /birch reset
                .then(ClientCommands.literal("reset").executes(ctx -> {
                    tracker.reset();
                    regenTracker.reset();
                    collectionRank.reset();
                    SessionStats.resetSession();
                    SessionStats.save();
                    feedback(ctx.getSource(), "§aSession reset §7— counters, trees and rank cleared.");
                    return 1;
                }))

                // /birch hud <on|off> | pos <x> <y> | scale <n> | bg <bool>
                .then(ClientCommands.literal("hud")
                        .then(ClientCommands.argument("enabled", BoolArgumentType.bool()).executes(ctx -> {
                            BirchConfig.get().hudEnabled = BoolArgumentType.getBool(ctx, "enabled");
                            BirchConfig.save();
                            feedback(ctx.getSource(), "§7HUD: " + onOff(BirchConfig.get().hudEnabled));
                            return 1;
                        }))
                        .then(ClientCommands.literal("pos")
                                .then(ClientCommands.argument("x", IntegerArgumentType.integer(0, 10000))
                                        .then(ClientCommands.argument("y", IntegerArgumentType.integer(0, 10000))
                                                .executes(ctx -> {
                                                    BirchConfig config = BirchConfig.get();
                                                    config.hudX = IntegerArgumentType.getInteger(ctx, "x");
                                                    config.hudY = IntegerArgumentType.getInteger(ctx, "y");
                                                    BirchConfig.save();
                                                    feedback(ctx.getSource(), "§7HUD moved to §f"
                                                            + config.hudX + ", " + config.hudY);
                                                    return 1;
                                                }))))
                        .then(ClientCommands.literal("scale")
                                .then(ClientCommands.argument("scale", DoubleArgumentType.doubleArg(0.5, 3.0))
                                        .executes(ctx -> {
                                            BirchConfig.get().hudScale = DoubleArgumentType.getDouble(ctx, "scale");
                                            BirchConfig.save();
                                            feedback(ctx.getSource(), "§7HUD scale: §f"
                                                    + DEC_FMT.format(BirchConfig.get().hudScale) + "x");
                                            return 1;
                                        })))
                        .then(ClientCommands.literal("bg")
                                .then(ClientCommands.argument("enabled", BoolArgumentType.bool()).executes(ctx -> {
                                    BirchConfig.get().hudBackground = BoolArgumentType.getBool(ctx, "enabled");
                                    BirchConfig.save();
                                    feedback(ctx.getSource(), "§7HUD background: "
                                            + onOff(BirchConfig.get().hudBackground));
                                    return 1;
                                }))))

                // /birch notify <on|off>
                .then(ClientCommands.literal("notify")
                        .then(ClientCommands.argument("enabled", BoolArgumentType.bool()).executes(ctx -> {
                            BirchConfig.get().notifyOnReady = BoolArgumentType.getBool(ctx, "enabled");
                            BirchConfig.save();
                            feedback(ctx.getSource(), "§7Ready alerts: " + onOff(BirchConfig.get().notifyOnReady));
                            return 1;
                        }))
                        .then(ClientCommands.literal("sound")
                                .then(ClientCommands.argument("enabled", BoolArgumentType.bool()).executes(ctx -> {
                                    BirchConfig.get().notifySound = BoolArgumentType.getBool(ctx, "enabled");
                                    BirchConfig.save();
                                    feedback(ctx.getSource(), "§7Alert sound: " + onOff(BirchConfig.get().notifySound));
                                    return 1;
                                }))))

                // /birch tax <on|off>
                .then(ClientCommands.literal("tax")
                        .then(ClientCommands.argument("enabled", BoolArgumentType.bool()).executes(ctx -> {
                            BirchConfig.get().applyBazaarTax = BoolArgumentType.getBool(ctx, "enabled");
                            BirchConfig.save();
                            feedback(ctx.getSource(), "§7Bazaar tax in projections: "
                                    + onOff(BirchConfig.get().applyBazaarTax));
                            return 1;
                        })))

                // /birch skyblockonly <on|off>
                .then(ClientCommands.literal("skyblockonly")
                        .then(ClientCommands.argument("enabled", BoolArgumentType.bool()).executes(ctx -> {
                            BirchConfig.get().onlyInSkyblock = BoolArgumentType.getBool(ctx, "enabled");
                            BirchConfig.save();
                            feedback(ctx.getSource(), "§7Only show in Skyblock: "
                                    + onOff(BirchConfig.get().onlyInSkyblock));
                            return 1;
                        })))

                // /birch apikey <key> and /birch name <username>
                .then(ClientCommands.literal("apikey")
                        .then(ClientCommands.argument("key", StringArgumentType.string()).executes(ctx -> {
                            BirchConfig.get().hypixelApiKey = StringArgumentType.getString(ctx, "key");
                            BirchConfig.save();
                            feedback(ctx.getSource(), "§aAPI key saved §7— rank refreshes within 10 minutes.");
                            return 1;
                        })))
                .then(ClientCommands.literal("name")
                        .then(ClientCommands.argument("username", StringArgumentType.string()).executes(ctx -> {
                            BirchConfig.get().playerName = StringArgumentType.getString(ctx, "username");
                            BirchConfig.save();
                            feedback(ctx.getSource(), "§aUsername saved §7— rank refreshes within 10 minutes.");
                            return 1;
                        })))

                // /birch safemode <on|off> — disable all in-world rendering
                .then(ClientCommands.literal("safemode")
                        .then(ClientCommands.argument("enabled", BoolArgumentType.bool()).executes(ctx -> {
                            BirchConfig.get().safeMode = BoolArgumentType.getBool(ctx, "enabled");
                            BirchConfig.save();
                            feedback(ctx.getSource(), "§7Safe mode: " + onOff(BirchConfig.get().safeMode)
                                    + " §8(in-world rendering "
                                    + (BirchConfig.get().safeMode ? "off" : "on") + ")");
                            return 1;
                        })))

                // /birch diag — what has failed, for bug reports
                .then(ClientCommands.literal("diag").executes(ctx -> {
                    diagnostics(ctx.getSource());
                    return 1;
                }))

                // /birch help
                .then(ClientCommands.literal("help").executes(ctx -> {
                    help(ctx.getSource());
                    return 1;
                })));
    }

    // ---- Output ----

    private static void overview(FabricClientCommandSource source,
                                 BirchTracker tracker,
                                 TreeRegenTracker regenTracker,
                                 BazaarManager bazaar) {
        header(source);
        feedback(source, "§7Birch/hr: §a" + INT_FMT.format(tracker.getBirchPerHour()));

        double net = bazaar.getBestNetPerLog();
        if (net > 0.0) {
            feedback(source, "§7Best net/log: §6" + DEC_FMT.format(net)
                    + " §7via §f" + bazaar.getBestProductId());
            feedback(source, "§7Coins/hr: §6" + INT_FMT.format(tracker.getBirchPerHour() * net));
        } else {
            feedback(source, "§7Bazaar: §8" + bazaar.getStatus());
        }

        feedback(source, "§7Trees tracked: §f" + regenTracker.getTrackedCount()
                + " §7(downed: §f" + regenTracker.getDownedTrees().size() + "§7)");
        feedback(source, "§7Skyblock detected: " + onOff(SkyblockDetector.isInSkyblock()));
        feedback(source, "§8Run /birch help for all commands.");
    }

    private static void stats(FabricClientCommandSource source,
                              BirchTracker tracker,
                              TreeRegenTracker regenTracker) {
        SessionStats.Lifetime lifetime = SessionStats.getLifetime();

        header(source);
        feedback(source, "§e§lSession");
        feedback(source, "§7  Birch: §f" + INT_FMT.format(SessionStats.getSessionBirch()));
        feedback(source, "§7  Trees: §f" + INT_FMT.format(SessionStats.getSessionTrees())
                + " §8(" + DEC_FMT.format(SessionStats.getBirchPerTree()) + " birch/tree)");
        feedback(source, "§7  Coins: §6" + INT_FMT.format(SessionStats.getSessionCoins()));
        feedback(source, "§7  Best rate: §a" + INT_FMT.format(SessionStats.getSessionBestRate()) + "§7/hr");
        feedback(source, "§7  Elapsed: §f" + SessionStats.formatDuration(SessionStats.getSessionElapsedMs())
                + " §8(active " + SessionStats.formatDuration(SessionStats.getActiveMs()) + ")");

        feedback(source, "§e§lLifetime");
        feedback(source, "§7  Birch: §f" + INT_FMT.format(lifetime.birchCollected));
        feedback(source, "§7  Trees: §f" + INT_FMT.format(lifetime.treesChopped));
        feedback(source, "§7  Coins: §6" + INT_FMT.format(lifetime.coinsEarned));
        feedback(source, "§7  Best rate: §a" + INT_FMT.format(lifetime.bestBirchPerHour) + "§7/hr");
        feedback(source, "§7  Active time: §f" + SessionStats.formatDuration(lifetime.playtimeMs));

        if (regenTracker.isCalibrated()) {
            feedback(source, "§7Measured regen: §f" + DEC_FMT.format(regenTracker.getRegenSeconds())
                    + "s §8(" + regenTracker.getMeasurementCount() + " samples)");
        }
    }

    private static void bazaarInfo(FabricClientCommandSource source, BazaarManager bazaar) {
        header(source);
        if (!bazaar.hasData()) {
            feedback(source, "§7Bazaar: §8" + bazaar.getStatus());
            return;
        }

        for (String id : new String[]{"BIRCH_LOG", "ENCHANTED_BIRCH_LOG"}) {
            BazaarManager.Quote quote = bazaar.getQuote(id);
            if (quote == null) {
                continue;
            }
            feedback(source, "§f" + id);
            feedback(source, "§7  Buy: §6" + DEC_FMT.format(quote.buyPrice())
                    + " §7Sell: §6" + DEC_FMT.format(quote.sellPrice())
                    + " §8(spread " + DEC_FMT.format(quote.spread() * 100.0) + "%)");
        }

        double net = bazaar.getBestNetPerLog();
        BirchConfig config = BirchConfig.get();
        feedback(source, "§7Best net/log: §6" + DEC_FMT.format(net)
                + " §7via §f" + bazaar.getBestProductId());
        feedback(source, "§7Tax applied: " + onOff(config.applyBazaarTax)
                + " §8(" + DEC_FMT.format(config.bazaarTaxRate * 100.0) + "%)");

        long mins = bazaar.getMinutesSinceUpdate();
        feedback(source, "§8Updated " + (mins <= 0 ? "just now" : mins + "m ago") + ", refreshes every 10m.");
    }

    /** Report which components have thrown — the first thing to check on a bug. */
    private static void diagnostics(FabricClientCommandSource source) {
        header(source);
        feedback(source, "§7Version: §f" + BirchMod.version() + " §7for MC §f26.1.2 §8(no mixins)");
        feedback(source, "§7Safe mode: " + onOff(BirchConfig.get().safeMode));

        String[] features = {
                "hud", "tracers", "tree-timers", "birch-tracker", "regen-tracker",
                "collection-rank", "route-builder", "keybinds", "skyblock-detect"
        };

        boolean anyFailure = false;
        for (String feature : features) {
            int count = Guard.getFailureCount(feature);
            if (count == 0) {
                continue;
            }
            anyFailure = true;
            String state = Guard.isDisabled(feature) ? "§cDISABLED" : "§eerrored";
            feedback(source, "§7  " + feature + ": " + state + " §8(" + count + " failures)");
        }

        if (!anyFailure) {
            feedback(source, "§aNo component has thrown this session.");
            feedback(source, "§8If the game still misbehaves, the cause is outside this mod.");
        } else {
            feedback(source, "§8Full stack traces are in your latest.log — search 'BirchOptimizer'.");
        }
    }

    private static void help(FabricClientCommandSource source) {
        header(source);
        feedback(source, "§f/birch §7— overview");
        feedback(source, "§f/birch stats §7— session + lifetime totals");
        feedback(source, "§f/birch bazaar §7— live prices and spreads");
        feedback(source, "§f/birch reset §7— clear session counters");
        feedback(source, "§f/birch hud <true|false> §7— toggle overlay");
        feedback(source, "§f/birch hud pos <x> <y> §7— move overlay");
        feedback(source, "§f/birch hud scale <0.5-3.0> §7— resize overlay");
        feedback(source, "§f/birch hud bg <true|false> §7— overlay backdrop");
        feedback(source, "§f/birch notify <true|false> §7— ready alerts");
        feedback(source, "§f/birch notify sound <true|false> §7— alert sound");
        feedback(source, "§f/birch tax <true|false> §7— Bazaar tax in projections");
        feedback(source, "§f/birch skyblockonly <true|false> §7— hide outside Skyblock");
        feedback(source, "§f/birch apikey <key> §7· §f/birch name <username>");
        feedback(source, "§f/timer mode §7— toggle floating tree timers");
        feedback(source, "§f/route §7— show the planned route");
        feedback(source, "§f/route <true|false> §7— toggle route overlay");
        feedback(source, "§f/route path <true|false> §7— whole path vs next tree only");
        feedback(source, "§f/route tracers <true|false> §7· §f/route chain <true|false>");
        feedback(source, "§f/route length <1-16> §7· §f/route center <0-12>");
        feedback(source, "§f/birch safemode <true|false> §7— disable in-world rendering");
        feedback(source, "§f/birch diag §7— report component failures");
    }

    private static void header(FabricClientCommandSource source) {
        feedback(source, "§6§lBirch Optimizer");
    }

    private static String onOff(boolean value) {
        return value ? "§aon" : "§coff";
    }

    private static void feedback(FabricClientCommandSource source, String message) {
        source.sendFeedback(Component.literal(message));
    }
}
