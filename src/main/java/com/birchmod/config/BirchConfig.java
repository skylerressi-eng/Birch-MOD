package com.birchmod.config;

import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import net.fabricmc.loader.api.FabricLoader;

/**
 * JSON-backed configuration, stored at {@code config/birchoptimizer.json}.
 *
 * Everything here is editable in-game via {@code /birch}, so the file is a
 * persistence detail rather than the primary interface.
 */
public final class BirchConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String FILE_NAME = "birchoptimizer.json";

    // ---- HUD ----
    public boolean hudEnabled = true;
    public int hudX = 5;
    public int hudY = 5;
    public double hudScale = 1.0;
    public boolean hudBackground = true;
    public boolean onlyInSkyblock = true;

    /** Individual HUD rows, so the overlay can be trimmed to what you care about. */
    public boolean showBirchRate = true;
    public boolean showBazaar = true;
    public boolean showCoinRate = true;
    public boolean showRegen = true;
    public boolean showSession = true;
    public boolean showCollectionRank = true;
    public boolean showLeaderboard = true;

    // ---- Bazaar ----
    /** Show insta-buy price (true) or insta-sell price (false). */
    public boolean showBuyPrice = true;
    /** Primary Hypixel Bazaar product id. "BIRCH_LOG" is Birch Wood. */
    public String bazaarProductId = "BIRCH_LOG";
    /**
     * Apply Hypixel's Bazaar tax to coin projections. Skyblock takes a cut on
     * sell orders, so gross prices overstate real income.
     */
    public boolean applyBazaarTax = true;
    /** Bazaar tax rate as a fraction (1.25% by default). */
    public double bazaarTaxRate = 0.0125;

    // ---- Tree regen timer ----
    public boolean regenTimerEnabled = true;
    /** Floating in-world timers above downed trees. Toggled by {@code /timer mode}. */
    public boolean worldTimersEnabled = true;
    /** Fallback regen duration until a real cycle has been measured. */
    public double defaultRegenSeconds = 60.0;
    /** Hide in-world timers beyond this distance, in blocks. */
    public double worldTimerRange = 48.0;

    // ---- Notifications ----
    public boolean notifyOnReady = true;
    public boolean notifySound = true;
    public double notifyVolume = 0.6;
    public double notifyCooldownSeconds = 3.0;

    // ---- Leaderboard ----
    public String hypixelApiKey = "";
    public String playerName = "";

    private static BirchConfig instance = new BirchConfig();

    public static BirchConfig get() {
        return instance;
    }

    private static Path path() {
        return FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME);
    }

    public static void load() {
        Path file = path();
        try {
            if (Files.exists(file)) {
                try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                    BirchConfig loaded = GSON.fromJson(reader, BirchConfig.class);
                    if (loaded != null) {
                        instance = loaded;
                    }
                }
            }
        } catch (Exception e) {
            // Corrupt or unreadable config: keep defaults rather than crashing.
            instance = new BirchConfig();
        }
        instance.clamp();
        save();
    }

    /** Keep hand-edited values inside sane bounds. */
    private void clamp() {
        hudScale = Math.max(0.5, Math.min(3.0, hudScale));
        notifyVolume = Math.max(0.0, Math.min(1.0, notifyVolume));
        notifyCooldownSeconds = Math.max(0.0, Math.min(60.0, notifyCooldownSeconds));
        defaultRegenSeconds = Math.max(1.0, Math.min(900.0, defaultRegenSeconds));
        worldTimerRange = Math.max(4.0, Math.min(128.0, worldTimerRange));
        bazaarTaxRate = Math.max(0.0, Math.min(0.25, bazaarTaxRate));
        hudX = Math.max(0, hudX);
        hudY = Math.max(0, hudY);
        if (bazaarProductId == null || bazaarProductId.isBlank()) {
            bazaarProductId = "BIRCH_LOG";
        }
    }

    public static void save() {
        Path file = path();
        try {
            Files.createDirectories(file.getParent());
            try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                GSON.toJson(instance, writer);
            }
        } catch (Exception ignored) {
            // Non-fatal: the mod still runs with in-memory settings.
        }
    }
}
