package com.birchmod;

import com.birchmod.api.BazaarManager;
import com.birchmod.api.LeaderboardManager;
import com.birchmod.config.BirchConfig;
import com.birchmod.hud.BirchHud;
import com.birchmod.tracking.BirchTracker;

import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;

/**
 * Birch Optimizer — main mod entry point.
 *
 * Features (v0.1):
 *  - Birch/hour: tracks birch logs collected and shows a live rate.
 *  - Bazaar price: live Birch Wood buy/sell price, refreshed every 10 minutes.
 *  - Leaderboard rank: your ranking, refreshed every 10 minutes.
 */
@Mod(modid = BirchMod.MODID, name = BirchMod.NAME, version = BirchMod.VERSION, clientSideOnly = true)
public class BirchMod {

    public static final String MODID = "birchoptimizer";
    public static final String NAME = "Birch Optimizer";
    public static final String VERSION = "0.1.0";

    public static BirchTracker tracker;
    public static BazaarManager bazaar;
    public static LeaderboardManager leaderboard;
    public static BirchHud hud;

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        BirchConfig.init(event.getSuggestedConfigurationFile());
    }

    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
        tracker = new BirchTracker();
        bazaar = new BazaarManager();
        leaderboard = new LeaderboardManager();
        hud = new BirchHud(tracker, bazaar, leaderboard);

        MinecraftForge.EVENT_BUS.register(tracker);
        MinecraftForge.EVENT_BUS.register(hud);

        // Both API managers refresh on their own 10-minute schedule.
        bazaar.start();
        leaderboard.start();
    }
}
