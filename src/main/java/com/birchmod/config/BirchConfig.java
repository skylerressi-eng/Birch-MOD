package com.birchmod.config;

import java.io.File;

import net.minecraftforge.common.config.Configuration;

/**
 * Simple persisted configuration for Birch Optimizer.
 *
 * The Hypixel API key + player name are used by {@link com.birchmod.api.LeaderboardManager}
 * to look up your ranking. If left blank, the leaderboard line shows "N/A".
 */
public final class BirchConfig {

    private static Configuration config;

    // HUD position (top-left origin, in GUI-scaled pixels).
    public static int hudX = 5;
    public static int hudY = 5;
    public static boolean hudEnabled = true;

    // Which Bazaar price to show: BUY (insta-buy) or SELL (insta-sell).
    public static boolean showBuyPrice = true;

    // Leaderboard lookup credentials.
    public static String hypixelApiKey = "";
    public static String playerName = "";

    // The Bazaar product id we track. "BIRCH_LOG" is Birch Wood in Skyblock.
    public static String bazaarProductId = "BIRCH_LOG";

    private BirchConfig() {
    }

    public static void init(File file) {
        config = new Configuration(file);
        load();
    }

    public static void load() {
        config.load();

        hudEnabled = config.getBoolean("hudEnabled", "hud", true, "Show the Birch Optimizer HUD overlay.");
        hudX = config.getInt("hudX", "hud", 5, 0, 10000, "HUD X position (pixels from left).");
        hudY = config.getInt("hudY", "hud", 5, 0, 10000, "HUD Y position (pixels from top).");

        showBuyPrice = config.getBoolean("showBuyPrice", "bazaar", true,
                "Show insta-buy price (true) or insta-sell price (false).");
        bazaarProductId = config.getString("bazaarProductId", "bazaar", "BIRCH_LOG",
                "Hypixel Bazaar product id to track.");

        hypixelApiKey = config.getString("hypixelApiKey", "leaderboard", "",
                "Hypixel API key (run /api new in-game). Required for leaderboard rank.");
        playerName = config.getString("playerName", "leaderboard", "",
                "Your Minecraft username, used for leaderboard rank lookup.");

        save();
    }

    public static void save() {
        if (config != null && config.hasChanged()) {
            config.save();
        }
    }
}
