package com.birchmod.hud;

import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.List;

import com.birchmod.api.BazaarManager;
import com.birchmod.api.LeaderboardManager;
import com.birchmod.config.BirchConfig;
import com.birchmod.tracking.BirchTracker;

import net.minecraft.client.Minecraft;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

/**
 * Renders the Birch Optimizer overlay: birch/hour, Bazaar price and
 * leaderboard rank.
 */
public class BirchHud {

    private static final DecimalFormat INT_FMT = new DecimalFormat("#,##0");
    private static final DecimalFormat PRICE_FMT = new DecimalFormat("#,##0.0");

    private static final int COLOR_TITLE = 0xFFD54F; // birch-yellow
    private static final int COLOR_TEXT = 0xFFFFFF;
    private static final int COLOR_MUTED = 0xAAAAAA;

    private final BirchTracker tracker;
    private final BazaarManager bazaar;
    private final LeaderboardManager leaderboard;

    public BirchHud(BirchTracker tracker, BazaarManager bazaar, LeaderboardManager leaderboard) {
        this.tracker = tracker;
        this.bazaar = bazaar;
        this.leaderboard = leaderboard;
    }

    @SubscribeEvent
    public void onRenderOverlay(RenderGameOverlayEvent.Text event) {
        if (!BirchConfig.hudEnabled) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.thePlayer == null || mc.gameSettings.showDebugInfo) {
            return;
        }

        List<String> lines = buildLines();

        int x = BirchConfig.hudX;
        int y = BirchConfig.hudY;
        int lineHeight = mc.fontRendererObj.FONT_HEIGHT + 2;

        // Title first, then data lines.
        mc.fontRendererObj.drawStringWithShadow("§lBirch Optimizer", x, y, COLOR_TITLE);
        y += lineHeight;
        for (String line : lines) {
            mc.fontRendererObj.drawStringWithShadow(line, x, y, COLOR_TEXT);
            y += lineHeight;
        }
    }

    private List<String> buildLines() {
        List<String> lines = new ArrayList<String>();

        // Birch / hour
        double perHour = tracker.getBirchPerHour();
        lines.add("Birch/hr: §a" + INT_FMT.format(perHour));

        // Bazaar price
        if (bazaar.hasData()) {
            String label = BirchConfig.showBuyPrice ? "Buy" : "Sell";
            lines.add("BZ " + label + ": §6" + PRICE_FMT.format(bazaar.getDisplayPrice()) + " coins");

            // Estimated coins/hour from the collected birch.
            double coinsPerHour = perHour * bazaar.getDisplayPrice();
            lines.add("Coins/hr: §6" + INT_FMT.format(coinsPerHour));
        } else {
            lines.add("BZ: §7loading...");
        }

        // Leaderboard rank
        if (leaderboard.hasRank()) {
            String title = leaderboard.getRankTitle();
            String suffix = (title == null || title.isEmpty()) ? "" : " §7(" + title + ")";
            lines.add("Rank: §b#" + INT_FMT.format(leaderboard.getRank()) + suffix);
        } else {
            lines.add("Rank: §7" + leaderboard.getStatus());
        }

        return lines;
    }
}
