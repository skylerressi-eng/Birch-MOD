package com.birchmod.util;

import java.util.Locale;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.world.scores.DisplaySlot;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.Scoreboard;

/**
 * Detects whether the client is currently on Hypixel Skyblock, so the HUD can
 * stay out of the way everywhere else.
 *
 * Server address identifies Hypixel; the sidebar objective title identifies
 * Skyblock specifically. Both are cheap, so this re-checks on a short interval.
 */
public final class SkyblockDetector {

    private static final long CHECK_INTERVAL_MS = 2_000L;

    private static boolean onHypixel = false;
    private static boolean inSkyblock = false;
    private static long lastCheck = 0L;

    private SkyblockDetector() {
    }

    public static void update(Minecraft client) {
        long now = System.currentTimeMillis();
        if (now - lastCheck < CHECK_INTERVAL_MS) {
            return;
        }
        lastCheck = now;

        if (client == null || client.level == null || client.player == null) {
            onHypixel = false;
            inSkyblock = false;
            return;
        }

        onHypixel = checkHypixel(client);
        inSkyblock = onHypixel && checkSkyblockSidebar(client);
    }

    private static boolean checkHypixel(Minecraft client) {
        if (client.isLocalServer()) {
            return false;
        }
        ServerData server = client.getCurrentServer();
        if (server == null || server.ip == null) {
            return false;
        }
        return server.ip.toLowerCase(Locale.ROOT).contains("hypixel");
    }

    /** Skyblock puts "SKYBLOCK" in the scoreboard sidebar title. */
    private static boolean checkSkyblockSidebar(Minecraft client) {
        try {
            Scoreboard scoreboard = client.level.getScoreboard();
            Objective objective = scoreboard.getDisplayObjective(DisplaySlot.SIDEBAR);
            if (objective == null) {
                return false;
            }
            String title = objective.getDisplayName().getString().toUpperCase(Locale.ROOT);
            return title.contains("SKYBLOCK");
        } catch (Exception e) {
            return false;
        }
    }

    public static boolean isOnHypixel() {
        return onHypixel;
    }

    public static boolean isInSkyblock() {
        return inSkyblock;
    }

    /**
     * Whether the overlay should render. Honours the "only in Skyblock" setting;
     * when that is off the HUD shows everywhere.
     */
    public static boolean shouldRender(boolean onlyInSkyblock) {
        return !onlyInSkyblock || inSkyblock;
    }
}
