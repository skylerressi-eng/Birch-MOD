package com.birchmod;

import com.birchmod.api.BazaarManager;
import com.birchmod.api.CollectionApi;
import com.birchmod.command.BirchCommand;
import com.birchmod.command.RouteCommand;
import com.birchmod.command.TimerCommand;
import com.birchmod.config.BirchConfig;
import com.birchmod.hud.BirchHud;
import com.birchmod.input.Keybinds;
import com.birchmod.render.TracerRenderer;
import com.birchmod.render.TreeTimerRenderer;
import com.birchmod.route.LapTracker;
import com.birchmod.route.RouteBuilder;
import com.birchmod.route.RouteLibrary;
import com.birchmod.route.RouteRecorder;
import com.birchmod.route.TravelGraph;
import com.birchmod.stats.SessionStats;
import com.birchmod.tracking.BirchTracker;
import com.birchmod.tracking.CollectionRankTracker;
import com.birchmod.tracking.LeftoverWatch;
import com.birchmod.tracking.MovementTracker;
import com.birchmod.tracking.TreeRegenTracker;
import com.birchmod.util.Guard;
import com.birchmod.util.Notifier;
import com.birchmod.util.SkyblockDetector;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.resources.Identifier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Birch Optimizer — Fabric client mod for Hypixel Skyblock on Minecraft 26.1.2.
 *
 * Features:
 *  - Birch/hour, measured from inventory deltas (works on a remote server).
 *  - Tax-aware Bazaar pricing across birch products, refreshed every 10 minutes.
 *  - Session and lifetime statistics, persisted between runs.
 *  - Per-tree regeneration timers floating above each downed tree, with alerts.
 *  - Birch collection total from the API, and your rank read from the game.
 *  - {@code /birch} and {@code /timer} commands plus rebindable keys.
 */
public class BirchMod implements ClientModInitializer {

    public static final String MOD_ID = "birchoptimizer";
    /**
     * Read from the jar's own metadata rather than a constant, so what the mod
     * reports can never drift from the build actually loaded.
     */
    public static String version() {
        return FabricLoader.getInstance().getModContainer(MOD_ID)
                .map(container -> container.getMetadata().getVersion().getFriendlyString())
                .orElse("unknown");
    }

    public static BirchTracker tracker;
    public static TreeRegenTracker regenTracker;
    public static CollectionRankTracker collectionRank;
    public static BazaarManager bazaar;
    public static CollectionApi collectionApi;
    public static RouteBuilder routeBuilder;
    public static RouteRecorder routeRecorder;
    public static MovementTracker movementTracker;
    public static LapTracker lapTracker;
    public static LeftoverWatch leftoverWatch;

    private static final Logger LOGGER = LoggerFactory.getLogger("BirchOptimizer");

    @Override
    public void onInitializeClient() {
        LOGGER.info("Birch Optimizer {} starting for Minecraft 26.1.2", version());

        BirchConfig.load();
        SessionStats.load();
        RouteLibrary.load();
        TravelGraph.load();

        tracker = new BirchTracker();
        regenTracker = new TreeRegenTracker();
        collectionRank = new CollectionRankTracker();
        bazaar = new BazaarManager();
        collectionApi = new CollectionApi();
        routeBuilder = new RouteBuilder(regenTracker);
        routeRecorder = new RouteRecorder();
        movementTracker = new MovementTracker();
        lapTracker = new LapTracker();
        leftoverWatch = new LeftoverWatch(regenTracker);
        // Felling a tree is the one unambiguous signal in the whole mod: it
        // says which tree, in what order, and — with the previous one — how
        // long that leg really took.
        // A spot on the active route, or one already felled before, is tracked
        // whatever is left standing on it.
        regenTracker.setKnownSpots((x, y, z) ->
                RouteLibrary.activeContains(x, y, z)
                        || TravelGraph.canonical(x, y, z) != null);

        regenTracker.setChopListener(base -> {
            routeRecorder.onTreeChopped(base);
            TravelGraph.onTreeChopped(base);

            double lap = lapTracker.onTreeChopped();
            if (lap > 0.0) {
                announceLap(lap);
            }
        });

        // Both API managers poll on their own 10-minute schedule.
        bazaar.start();
        collectionApi.start();

        Keybinds.register();

        // Every entry point is guarded: this mod runs inside someone else's tick
        // and render loops, and must never be the reason their game dies.
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            Guard.run("skyblock-detect", () -> SkyblockDetector.update(client));
            Guard.run("birch-tracker", () -> tracker.tick(client));
            Guard.run("regen-tracker", () -> regenTracker.tick(client));
            // Between worlds there is no leg to time; measuring across a
            // loading screen would fold it into the hop and poison the figure.
            Guard.run("travel-chain", () -> {
                if (client.level == null) {
                    TravelGraph.breakChain();
                }
            });
            Guard.run("collection-rank", () -> collectionRank.tick(client));
            Guard.run("movement", () -> movementTracker.tick(client));
            Guard.run("leftovers", () -> leftoverWatch.tick(client));
            Guard.run("route-builder", () -> {
                if (client.player != null) {
                    routeBuilder.update(client.player.position());
                }
            });
            Guard.run("keybinds", () -> Keybinds.tick(client, BirchMod::resetSession));
        });

        // Persist on a clean exit. All three are written off the client thread
        // while playing, so the last of each has to be flushed here — a queued
        // write on a daemon thread does not survive the process stopping.
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
            SessionStats.save();
            TravelGraph.persistNow();
            RouteLibrary.persistNow();
        });

        BirchHud hud = new BirchHud(tracker, regenTracker, collectionRank, bazaar, collectionApi, routeBuilder);
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
        BirchCommand.register(tracker, regenTracker, collectionRank, bazaar, collectionApi);
        RouteCommand.register(routeBuilder, routeRecorder, regenTracker);
    }

    /**
     * Announce a completed lap, and say whether it was your quickest.
     *
     * A lap time nobody sees is a lap time nobody acts on, and the moment it
     * lands is the only moment it means anything.
     */
    private static void announceLap(double seconds) {
        double best = LapTracker.bestLapForActive();
        boolean record = best > 0.0 && Math.abs(best - seconds) < 1.0e-9;

        Notifier.actionBar(record
                ? "§6§lBEST LAP §e" + LapTracker.format(seconds)
                : "§aLap " + lapTracker.getLapsCompleted() + " §f"
                        + LapTracker.format(seconds)
                        + " §8(best " + LapTracker.format(best) + ")");
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
        if (lapTracker != null) {
            lapTracker.resetSession();
        }
        if (leftoverWatch != null) {
            leftoverWatch.reset();
        }
        SessionStats.resetSession();
        SessionStats.save();
    }
}
