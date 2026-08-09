package com.birchmod.hud;

import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.List;

import com.birchmod.api.BazaarManager;
import com.birchmod.api.LeaderboardManager;
import com.birchmod.config.BirchConfig;
import com.birchmod.tracking.BirchTracker;
import com.birchmod.tracking.TreeRegenTracker;

import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;

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
    private static final int COLOR_TEXT = 0xFFFFFFFF;
    private static final int COLOR_GREEN = 0xFF55FF55;
    private static final int COLOR_GOLD = 0xFFFFAA00;
    private static final int COLOR_AQUA = 0xFF55FFFF;
    private static final int COLOR_GREY = 0xFFAAAAAA;

    private final BirchTracker tracker;
    private final TreeRegenTracker regenTracker;
    private final BazaarManager bazaar;
    private final LeaderboardManager leaderboard;

    public BirchHud(BirchTracker tracker,
                    TreeRegenTracker regenTracker,
                    BazaarManager bazaar,
                    LeaderboardManager leaderboard) {
        this.tracker = tracker;
        this.regenTracker = regenTracker;
        this.bazaar = bazaar;
        this.leaderboard = leaderboard;
    }

    /** A single HUD row: text plus the colour it renders in. */
    private record Line(String text, int color) {
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

        int x = config.hudX;
        int y = config.hudY;
        int lineHeight = client.font.lineHeight + 2;

        graphics.text(client.font, "Birch Optimizer", x, y, COLOR_TITLE, true);
        y += lineHeight;

        for (Line line : buildLines(config)) {
            graphics.text(client.font, line.text(), x, y, line.color(), true);
            y += lineHeight;
        }
    }

    private List<Line> buildLines(BirchConfig config) {
        List<Line> lines = new ArrayList<>();

        // Birch per hour.
        double perHour = tracker.getBirchPerHour();
        lines.add(new Line("Birch/hr: " + INT_FMT.format(perHour), COLOR_GREEN));

        // Bazaar price and derived coin rate.
        if (bazaar.hasData()) {
            String label = config.showBuyPrice ? "Buy" : "Sell";
            double price = bazaar.getDisplayPrice();
            lines.add(new Line("BZ " + label + ": " + PRICE_FMT.format(price) + " coins", COLOR_GOLD));
            lines.add(new Line("Coins/hr: " + INT_FMT.format(perHour * price), COLOR_GOLD));
        } else {
            lines.add(new Line("BZ: loading...", COLOR_GREY));
        }

        // Tree regeneration timer.
        if (config.regenTimerEnabled) {
            lines.add(regenLine());
        }

        // Leaderboard rank.
        if (leaderboard.hasRank()) {
            String title = leaderboard.getRankTitle();
            String suffix = (title == null || title.isEmpty()) ? "" : " (" + title + ")";
            lines.add(new Line("Rank: #" + INT_FMT.format(leaderboard.getRank()) + suffix, COLOR_AQUA));
        } else {
            lines.add(new Line("Rank: " + leaderboard.getStatus(), COLOR_GREY));
        }

        return lines;
    }

    private Line regenLine() {
        double remaining = regenTracker.getSecondsUntilRegen();

        if (remaining < 0.0) {
            return new Line("Regen: chop a tree to start", COLOR_GREY);
        }
        if (remaining == 0.0) {
            return new Line("Regen: READY", COLOR_GREEN);
        }

        // Mark the figure as an estimate until a real cycle has been measured.
        String suffix = regenTracker.isCalibrated() ? "" : " (est)";
        return new Line("Regen: " + SEC_FMT.format(remaining) + "s" + suffix, COLOR_GOLD);
    }
}
