package com.birchmod.hud;

import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.List;

import com.birchmod.api.BazaarManager;
import com.birchmod.api.LeaderboardManager;
import com.birchmod.config.BirchConfig;
import com.birchmod.BirchMod;
import com.birchmod.route.LapTracker;
import com.birchmod.route.RouteBuilder;
import com.birchmod.route.Stop;
import com.birchmod.stats.SessionStats;
import com.birchmod.tracking.BirchTracker;
import com.birchmod.tracking.CollectionRankTracker;
import com.birchmod.tracking.TreeRegenTracker;
import com.birchmod.util.SkyblockDetector;

import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.world.phys.Vec3;

/**
 * Renders the Birch Optimizer overlay.
 *
 * On Minecraft 26.1 the HUD is a render-state pipeline, so this implements
 * {@link HudElement#extractRenderState} rather than an immediate-mode callback.
 */
public class BirchHud implements HudElement {

    private static final DecimalFormat INT_FMT = new DecimalFormat("#,##0");
    private static final DecimalFormat PRICE_FMT = new DecimalFormat("#,##0.0");
    private static final DecimalFormat SEC_FMT = new DecimalFormat("#0.0");

    // ARGB — 26.1 requires the alpha channel to be set explicitly.
    private static final int COLOR_TITLE = 0xFFFFD54F; // birch yellow
    private static final int COLOR_GREEN = 0xFF55FF55;
    private static final int COLOR_GOLD = 0xFFFFAA00;
    private static final int COLOR_AQUA = 0xFF55FFFF;
    private static final int COLOR_GREY = 0xFFAAAAAA;
    private static final int COLOR_WHITE = 0xFFFFFFFF;
    private static final int COLOR_BACKDROP = 0x90000000;

    private static final int PADDING = 3;

    private final BirchTracker tracker;
    private final TreeRegenTracker regenTracker;
    private final CollectionRankTracker collectionRank;
    private final BazaarManager bazaar;
    private final LeaderboardManager leaderboard;
    private final RouteBuilder routeBuilder;

    public BirchHud(BirchTracker tracker,
                    TreeRegenTracker regenTracker,
                    CollectionRankTracker collectionRank,
                    BazaarManager bazaar,
                    LeaderboardManager leaderboard,
                    RouteBuilder routeBuilder) {
        this.tracker = tracker;
        this.regenTracker = regenTracker;
        this.collectionRank = collectionRank;
        this.bazaar = bazaar;
        this.leaderboard = leaderboard;
        this.routeBuilder = routeBuilder;
    }

    /** A single HUD row: text plus the colour it renders in. */
    private record Line(String text, int color) {
    }

    /**
     * How often the rows are rebuilt, regardless of frame rate.
     *
     * Ten times a second is faster than any of these numbers move and faster
     * than an eye can follow, so nothing on screen is any less live than it was.
     */
    private static final long REBUILD_INTERVAL_MS = 100L;

    /**
     * The rows as last built, reused between rebuilds.
     *
     * Building them means a fresh list, a dozen boxed records and a dozen
     * formatted strings, and it was happening on every single frame — hundreds
     * of times a second, to display a birch rate that updates four times a
     * second and a Bazaar price that updates every ten minutes. Everything else
     * in this mod goes out of its way to avoid allocating on a hot path; the
     * overlay was quietly doing more of it than anything else.
     */
    private List<Line> cached = List.of();
    private long builtAt = 0L;
    private int builtWidth = 0;

    private List<Line> rows(BirchConfig config, Minecraft client) {
        long now = System.currentTimeMillis();
        if (now - builtAt < REBUILD_INTERVAL_MS) {
            return cached;
        }
        builtAt = now;
        cached = buildLines(config);

        // The backdrop is sized from the widest row, which is a font
        // measurement per row per frame on top of the building. It can only
        // change when the rows do.
        int width = client.font.width("Birch Optimizer");
        for (Line line : cached) {
            width = Math.max(width, client.font.width(line.text()));
        }
        builtWidth = width;
        return cached;
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker) {
        BirchConfig config = BirchConfig.get();
        if (!config.hudEnabled) {
            return;
        }

        Minecraft client = Minecraft.getInstance();
        if (client == null || client.player == null || client.options.hideGui) {
            return;
        }
        if (!SkyblockDetector.shouldRender(config.onlyInSkyblock)) {
            return;
        }

        List<Line> lines = rows(config, client);
        if (lines.isEmpty()) {
            return;
        }

        int lineHeight = client.font.lineHeight + 2;
        float scale = (float) config.hudScale;

        graphics.pose().pushMatrix();
        graphics.pose().translate(config.hudX, config.hudY);
        if (scale != 1.0f) {
            graphics.pose().scale(scale, scale);
        }

        if (config.hudBackground) {
            int height = lineHeight * (lines.size() + 1);
            graphics.fill(-PADDING, -PADDING, builtWidth + PADDING, height + PADDING,
                    COLOR_BACKDROP);
        }

        int y = 0;
        graphics.text(client.font, "Birch Optimizer", 0, y, COLOR_TITLE, true);
        y += lineHeight;

        for (Line line : lines) {
            graphics.text(client.font, line.text(), 0, y, line.color(), true);
            y += lineHeight;
        }

        graphics.pose().popMatrix();
    }

    private List<Line> buildLines(BirchConfig config) {
        List<Line> lines = new ArrayList<>();

        double perHour = tracker.getBirchPerHour();
        double netPerLog = bazaar.getBestNetPerLog();

        if (config.showBirchRate) {
            lines.add(new Line("Birch/hr: " + INT_FMT.format(perHour), COLOR_GREEN));
        }

        if (config.showBazaar) {
            if (bazaar.hasData()) {
                String label = config.showBuyPrice ? "Buy" : "Sell";
                lines.add(new Line("BZ " + label + ": " + PRICE_FMT.format(bazaar.getDisplayPrice()),
                        COLOR_GOLD));
            } else {
                lines.add(new Line("BZ: " + bazaar.getStatus(), COLOR_GREY));
            }
        }

        if (config.showCoinRate && netPerLog > 0.0) {
            String taxNote = config.applyBazaarTax ? " net" : "";
            lines.add(new Line("Coins/hr" + taxNote + ": " + INT_FMT.format(perHour * netPerLog), COLOR_GOLD));
        }

        if (config.showRegen && config.regenTimerEnabled) {
            lines.add(regenLine());
        }

        if (config.showRoute && config.routeEnabled) {
            lines.add(routeLine());
            Line lap = lapLine(config);
            if (lap != null) {
                lines.add(lap);
            }
        }

        if (config.showSession) {
            lines.add(new Line("Session: " + INT_FMT.format(SessionStats.getSessionBirch())
                    + " birch / " + INT_FMT.format(SessionStats.getSessionTrees()) + " trees", COLOR_WHITE));
            lines.add(new Line("Earned: " + INT_FMT.format(SessionStats.getSessionCoins())
                    + " in " + SessionStats.formatDuration(SessionStats.getSessionElapsedMs()), COLOR_WHITE));
        }

        if (config.showCollectionRank && collectionRank.hasRank()) {
            String name = collectionRank.getCollectionName();
            String suffix = (name == null || name.isEmpty()) ? "" : " (" + name + ")";
            lines.add(new Line("Collection: #" + INT_FMT.format(collectionRank.getRank()) + suffix, COLOR_AQUA));
        }

        if (config.showLeaderboard) {
            if (leaderboard.hasRank()) {
                String title = leaderboard.getRankTitle();
                String suffix = (title == null || title.isEmpty()) ? "" : " (" + title + ")";
                lines.add(new Line("Rank: #" + INT_FMT.format(leaderboard.getRank()) + suffix, COLOR_AQUA));
            } else {
                lines.add(new Line("Rank: " + leaderboard.getStatus(), COLOR_GREY));
            }
        }

        return lines;
    }

    /**
     * How this lap is going against your best.
     *
     * The only line on the HUD that reports a result rather than a prediction,
     * so it is the one worth watching. Hidden entirely when no route is being
     * followed, because there is nothing to lap.
     */
    private Line lapLine(BirchConfig config) {
        LapTracker laps = BirchMod.lapTracker;
        if (!config.showLap || laps == null || laps.getLapSize() <= 0) {
            return null;
        }

        double elapsed = laps.getElapsedSeconds();
        if (elapsed < 0.0) {
            return null;
        }

        double best = routeBuilder.getFollowing().bestLapSeconds();
        String progress = laps.getProgress() + "/" + laps.getLapSize();
        String bestNote = best > 0.0 ? " (best " + LapTracker.format(best) + ")" : "";

        // Amber once this lap has already taken longer than your best one.
        int colour = (best > 0.0 && elapsed > best) ? COLOR_GOLD : COLOR_AQUA;
        return new Line("Lap " + progress + ": " + LapTracker.format(elapsed) + bestNote, colour);
    }

    /** Distance and wait for the next stop on the planned route. */
    private Line routeLine() {
        Stop next = routeBuilder.getNext();
        if (next == null) {
            return new Line("Route: no trees in range", COLOR_GREY);
        }

        Minecraft client = Minecraft.getInstance();
        double distance = client != null && client.player != null
                ? client.player.position().distanceTo(Vec3.atCenterOf(next.center()))
                : 0.0;

        String range = INT_FMT.format(distance) + "m";

        // Where in the loop this stop is, so a long route stops feeling
        // like an endless line of identical trees.
        RouteBuilder.Following following = routeBuilder.getFollowing();
        if (following.isActive() && following.index() >= 0) {
            range = (following.index() + 1) + "/" + following.stops() + ", " + range;
        }

        // A trunk you chopped into and walked away from is the one thing worth
        // shouting about — it is free birch standing where you already are.
        if (next.unfinished() && next.woodLeft() > 0) {
            return new Line("Route: " + range + ", finish it — "
                    + next.woodLeft() + " log(s) left", COLOR_GOLD);
        }
        // Out of tracking range: say the distance and nothing else, rather
        // than claiming a tree is ready when nothing has looked at it.
        if (!next.isKnown()) {
            return new Line("Route: " + range + " ahead", COLOR_GOLD);
        }
        if (next.etaSeconds() > 0.01) {
            return new Line("Route: " + range + ", wait "
                    + SEC_FMT.format(next.etaSeconds()) + "s", COLOR_GOLD);
        }
        if (next.woodLeft() > 0) {
            return new Line("Route: " + range + ", " + next.woodLeft() + " log(s)", COLOR_GREEN);
        }
        return new Line("Route: " + range + ", ready", COLOR_GREEN);
    }

    private Line regenLine() {
        double remaining = regenTracker.getSoonestRegen();

        if (remaining < 0.0) {
            return new Line("Regen: fully chop a tree to start", COLOR_GREY);
        }

        int pending = regenTracker.getDownedTrees().size();
        String count = pending > 1 ? " (" + pending + " trees)" : "";

        if (remaining == 0.0) {
            return new Line("Regen: READY" + count, COLOR_GREEN);
        }

        // Mark the figure as an estimate until a real cycle has been measured.
        String suffix = regenTracker.isCalibrated() ? "" : " (est)";
        return new Line("Regen: " + SEC_FMT.format(remaining) + "s" + suffix + count, COLOR_GOLD);
    }
}
