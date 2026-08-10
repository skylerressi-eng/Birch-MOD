package com.birchmod;

import com.birchmod.api.BazaarManager;
import com.birchmod.api.LeaderboardManager;
import com.birchmod.command.BirchCommand;
import com.birchmod.command.RouteCommand;
import com.birchmod.command.TimerCommand;
import com.birchmod.config.BirchConfig;
import com.birchmod.hud.BirchHud;
import com.birchmod.input.Keybinds;
import com.birchmod.render.TracerRenderer;
import com.birchmod.render.TreeTimerRenderer;
import com.birchmod.route.RouteBuilder;
import com.birchmod.stats.SessionStats;
import com.birchmod.tracking.BirchTracker;
import com.birchmod.tracking.CollectionRankTracker;
import com.birchmod.tracking.TreeRegenTracker;
import com.birchmod.util.Guard;
import com.birchmod.util.SkyblockDetector;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.resources.Identifier;

/**
 * Birch Optimizer — Fabric client mod for Hypixel Skyblock on Minecraft 26.1.2.
 *
 * Features:
 *  - Birch/hour, measured from inventory deltas (works on a remote server).
 *  - Tax-aware Bazaar pricing across birch products, refreshed every 10 minutes.
 *  - Session and lifetime statistics, persisted between runs.
 *  - Per-tree regeneration timers floating above each downed tree, with alerts.
 *  - Collection leaderboard rank, captured when you open the leaderboard GUI.
 *  - {@code /birch} and {@code /timer} commands plus rebindable keys.
 */
public class BirchMod implements ClientModInitializer {

    public static final String MOD_ID = "birchoptimizer";
    public static final String VERSION = "1.2.0";

    public static BirchTracker tracker;
    public static TreeRegenTracker regenTracker;
    public static CollectionRankTracker collectionRank;
    public static BazaarManager bazaar;
    public static LeaderboardManager leaderboard;
    public static RouteBuilder routeBuilder;

    @Override
    public void onInitializeClient() {
        BirchConfig.load();
        SessionStats.load();

        tracker = new BirchTracker();
        regenTracker = new TreeRegenTracker();
        collectionRank = new CollectionRankTracker();
        bazaar = new BazaarManager();
        leaderboard = new LeaderboardManager();
        routeBuilder = new RouteBuilder(regenTracker);

        // Both API managers poll on their own 10-minute schedule.
        bazaar.start();
        leaderboard.start();

        Keybinds.register();

        // Every entry point is guarded: this mod runs inside someone else's tick
        // and render loops, and must never be the reason their game dies.
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            Guard.run("skyblock-detect", () -> SkyblockDetector.update(client));
            Guard.run("birch-tracker", () -> tracker.tick(client));
            Guard.run("regen-tracker", () -> regenTracker.tick(client));
            Guard.run("collection-rank", () -> collectionRank.tick(client));
            Guard.run("route-builder", () -> {
                if (client.player != null) {
                    routeBuilder.update(client.player.position());
                }
            });
            Guard.run("keybinds", () -> Keybinds.tick(client, BirchMod::resetSession));
        });

        // Persist lifetime totals on a clean exit.
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> SessionStats.save());

        BirchHud hud = new BirchHud(tracker, regenTracker, collectionRank, bazaar, leaderboard, routeBuilder);
        HudElementRegistry.attachElementBefore(
                VanillaHudElements.CHAT,
                Identifier.fromNamespaceAndPath(MOD_ID, "birch_overlay"),
                (graphics, delta) -> Guard.run("hud", () -> hud.extractRenderState(graphics, delta)));

        TreeTimerRenderer treeTimers = new TreeTimerRenderer(regenTracker);
        LevelRenderEvents.AFTER_TRANSLUCENT_FEATURES.register(context ->
                Guard.run("tree-timers", () -> {
                    if (!BirchConfig.get().safeMode) {
                        treeTimers.render(context);
                    }
                }));

        TracerRenderer tracers = new TracerRenderer(routeBuilder);
        LevelRenderEvents.AFTER_TRANSLUCENT_FEATURES.register(context ->
                Guard.run("tracers", () -> {
                    if (!BirchConfig.get().safeMode) {
                        tracers.render(context);
                    }
                }));

        TimerCommand.register(regenTracker);
        BirchCommand.register(tracker, regenTracker, collectionRank, bazaar, leaderboard);
        RouteCommand.register(routeBuilder);
    }

    /** Clear all session-scoped counters. Shared by the keybind and command. */
    public static void resetSession() {
        if (tracker != null) {
            tracker.reset();
        }
        if (regenTracker != null) {
            regenTracker.reset();
        }
        if (collectionRank != null) {
            collectionRank.reset();
        }
        SessionStats.resetSession();
        SessionStats.save();
    }
}
