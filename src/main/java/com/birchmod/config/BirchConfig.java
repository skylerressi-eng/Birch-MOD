package com.birchmod.config;

import java.nio.file.Path;

import com.birchmod.util.SafeFile;
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

    /**
     * Bumped when a default changes in a way an existing settings file would
     * otherwise override forever.
     */
    private static final int CURRENT_VERSION = 3;

    /** Upper bound on how many stops can be shown at once. */
    public static final int MAX_ROUTE_LENGTH = 32;

    /**
     * Which set of defaults this file was written against.
     *
     * Starts at zero rather than the current version on purpose: a file saved
     * before this field existed has no value for it, and Gson leaves the
     * initialiser in place, so zero is exactly what an old file reads as.
     */
    public int configVersion = 0;

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
    public boolean showRoute = true;
    public boolean showSession = true;
    public boolean showCollectionRank = true;
    public boolean showLeaderboard = true;
    /** Live lap timer against your best lap on the active route. */
    public boolean showLap = true;

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
    /** Outline logs left behind on trees chopped into but not finished. */
    public boolean highlightLeftoverLogs = true;

    /**
     * Disable all in-world rendering (tracers, highlights, floating timers)
     * while keeping the HUD and tracking. The escape hatch if another mod's
     * renderer disagrees with ours.
     */
    public boolean safeMode = false;

    // ---- Route planning ----
    /** Draw the planned route: green highlight on each tree's centre block. */
    public boolean routeEnabled = true;
    /** Draw tracer lines to the route. */
    public boolean tracersEnabled = true;
    /** Draw the blue line onward from the tree you are chopping to the next one. */
    public boolean chainTracers = true;
    /**
     * Draw the whole planned loop, rather than just the tree you are chopping
     * and the one after it.
     *
     * Planning looks several stops ahead either way — that lookahead is what
     * lets a stop still regrowing be stepped over — but only two stops are
     * drawn, because a dense grove with a line to every planned tree is not
     * readable.
     */
    public boolean showFullPath = false;
    /**
     * How many trees ahead to show, the tree you are chopping included.
     *
     * Two is the tree under your axe and the one you are going to next, which
     * is one line to one tree. Turn it up to see further round the loop.
     */
    public int routeLength = 2;
    /** Blocks above the trunk base to treat as the tree's centre. */
    public int treeCenterHeight = 2;
    /**
     * Follow a recorded route in exactly the order it was recorded.
     *
     * On, the route is a contract: it never reorders, and it never moves you on
     * from a tree while wood is still standing on it. Off, a stop that will not
     * have regrown by the time you reach it is stepped over and picked up next
     * lap, which is faster on paper but no longer the route you walked.
     */
    public boolean strictRoute = true;
    /**
     * Half-width, in columns, of the block of ground one tree occupies. 1 gives
     * a 3x3 footprint, which covers the paired trunks and side branches that a
     * single column misses.
     */
    public int treeFootprint = 1;
    /**
     * Birch within reach of a spot before it is treated as somewhere to chop.
     *
     * 1 takes every piece of birch there is, which is the point: a single log
     * lying on the ground is choppable and regenerates like anything else, and
     * a threshold was only ever a crude stand-in for "do not make clutter".
     * Clutter is the merge's job, and the merge now joins logs that touch
     * rather than guessing from how far apart their bases are, so the
     * threshold no longer has to exclude real birch to keep the view clean.
     * Raise it if the scenery where you forage still earns markers.
     */
    public int minTreeLogs = 1;
    /** Width of tracer and highlight lines, in pixels. */
    public double lineWidth = 4.0;
    /** Fill the block to mine with translucent colour, not just an outline. */
    public boolean filledHighlight = true;
    /** Draw numbered "1 - READY" labels above each routed tree. */
    public boolean showRouteLabels = true;

    // ---- Notifications ----
    public boolean notifyOnReady = true;
    /**
     * Say something on the action bar when you walk away from a trunk you did
     * not finish. Each trunk is mentioned once, and only once you have really
     * left it.
     */
    public boolean notifyLeftovers = true;
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
        try {
            String json = SafeFile.read(path(), BirchConfig::parses);
            if (json != null) {
                BirchConfig loaded = GSON.fromJson(json, BirchConfig.class);
                if (loaded != null) {
                    instance = loaded;
                }
            }
        } catch (Exception e) {
            // Corrupt or unreadable config: keep defaults rather than crashing.
            instance = new BirchConfig();
        }
        instance.migrate();
        instance.clamp();
        save();
    }

    /**
     * Bring an older settings file up to the current defaults.
     *
     * Changing a default only helps people who have never run the mod: everyone
     * else has the old value written to disk, where it quietly wins forever.
     * Drawing the whole loop was the default once, and it is the reason a dense
     * grove filled up with lines to trees you were not going to next.
     */
    private void migrate() {
        if (configVersion < 2) {
            showFullPath = false;
        }
        if (configVersion < 3) {
            // Two logs was a stand-in for "do not make clutter", and it cost
            // real birch to buy that: a pair of logs lying on the ground is
            // choppable and regrows, and it was being skipped. The merge does
            // the decluttering properly now, so the threshold can stop.
            minTreeLogs = 1;
        }
        configVersion = CURRENT_VERSION;
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
        routeLength = Math.max(1, Math.min(MAX_ROUTE_LENGTH, routeLength));
        treeCenterHeight = Math.max(0, Math.min(12, treeCenterHeight));
        treeFootprint = Math.max(0, Math.min(2, treeFootprint));
        minTreeLogs = Math.max(1, Math.min(8, minTreeLogs));
        lineWidth = Math.max(0.5, Math.min(10.0, lineWidth));
        if (bazaarProductId == null || bazaarProductId.isBlank()) {
            bazaarProductId = "BIRCH_LOG";
        }
    }

    public static void save() {
        try {
            SafeFile.write(path(), GSON.toJson(instance));
        } catch (Exception ignored) {
            // Non-fatal: the mod still runs with in-memory settings.
        }
    }

    /** Whether this text is settings we could actually load. */
    private static boolean parses(String json) {
        try {
            return GSON.fromJson(json, BirchConfig.class) != null;
        } catch (Exception ignored) {
            return false;
        }
    }
}
