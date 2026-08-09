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
 * Forge's Configuration class does not exist on Fabric, so this is a plain
 * Gson-serialized holder written on demand.
 */
public final class BirchConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String FILE_NAME = "birchoptimizer.json";

    // ---- HUD ----
    public boolean hudEnabled = true;
    public int hudX = 5;
    public int hudY = 5;

    // ---- Bazaar ----
    /** Show insta-buy price (true) or insta-sell price (false). */
    public boolean showBuyPrice = true;
    /** Hypixel Bazaar product id. "BIRCH_LOG" is Birch Wood. */
    public String bazaarProductId = "BIRCH_LOG";

    // ---- Leaderboard ----
    public String hypixelApiKey = "";
    public String playerName = "";

    // ---- Tree regen timer ----
    public boolean regenTimerEnabled = true;
    /**
     * Fallback regen duration in seconds, used until the mod has measured a
     * real regrowth cycle. Once it observes one, the measured value wins.
     */
    public double defaultRegenSeconds = 60.0;

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
        save();
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
