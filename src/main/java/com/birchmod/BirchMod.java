package com.birchmod;

import com.birchmod.api.BazaarManager;
import com.birchmod.api.LeaderboardManager;
import com.birchmod.config.BirchConfig;
import com.birchmod.hud.BirchHud;
import com.birchmod.tracking.BirchTracker;
import com.birchmod.tracking.TreeRegenTracker;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.resources.Identifier;

/**
 * Birch Optimizer — Fabric client mod for Hypixel Skyblock on Minecraft 26.1.2.
 *
 * Features:
 *  - Birch/hour, measured from inventory deltas (works on a remote server).
 *  - Live Bazaar price of Birch Wood, refreshed every 10 minutes.
 *  - Leaderboard rank, refreshed every 10 minutes.
 *  - Tree regeneration timer that self-calibrates from observed regrowth.
 */
public class BirchMod implements ClientModInitializer {

    public static final String MOD_ID = "birchoptimizer";

    public static BirchTracker tracker;
    public static TreeRegenTracker regenTracker;
    public static BazaarManager bazaar;
    public static LeaderboardManager leaderboard;

    @Override
    public void onInitializeClient() {
        BirchConfig.load();

        tracker = new BirchTracker();
        regenTracker = new TreeRegenTracker();
        bazaar = new BazaarManager();
        leaderboard = new LeaderboardManager();

        // Both API managers poll on their own 10-minute schedule.
        bazaar.start();
        leaderboard.start();

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            tracker.tick(client);
            regenTracker.tick(client);
        });

        BirchHud hud = new BirchHud(tracker, regenTracker, bazaar, leaderboard);
        HudElementRegistry.attachElementBefore(
                VanillaHudElements.CHAT,
                Identifier.fromNamespaceAndPath(MOD_ID, "birch_overlay"),
                hud);
    }
}
