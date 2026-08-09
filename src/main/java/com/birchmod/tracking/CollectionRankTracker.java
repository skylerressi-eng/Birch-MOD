package com.birchmod.tracking;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;

/**
 * Notes your position whenever you open a Skyblock collection leaderboard.
 *
 * Skyblock's collection leaderboards are chest GUIs, and their contents arrive
 * by packet <em>after</em> the screen opens — so this polls the open screen each
 * tick rather than reading it once on init, and stops once a rank is found.
 *
 * A line is treated as yours when it mentions your username or is marked with a
 * "you" marker, and a rank is any leading position number on that line.
 */
public class CollectionRankTracker {

    private static final int SCAN_INTERVAL_TICKS = 10; // 2x per second

    /** Titles worth scanning. */
    private static final Pattern TITLE_PATTERN =
            Pattern.compile("(?i)(collection|leaderboard|top )");

    /** "#12", "#1,234", "12.", "1,234th" — the position on a leaderboard line. */
    private static final Pattern RANK_PATTERN =
            Pattern.compile("(?:#\\s*|\\brank\\s*|\\bposition\\s*:?\\s*)?(\\d{1,3}(?:,\\d{3})*|\\d+)\\s*(?:st|nd|rd|th|\\.)?");

    /** Explicit "your rank" style lines are the most reliable signal. */
    private static final Pattern SELF_RANK_PATTERN =
            Pattern.compile("(?i)(?:your|you are)\\D{0,20}?(\\d{1,3}(?:,\\d{3})*|\\d+)");

    /** Strip section-sign colour codes. */
    private static final Pattern COLOR_CODES = Pattern.compile("(?i)§[0-9A-FK-OR]");

    private volatile int rank = -1;
    private volatile String collectionName = "";
    private volatile long capturedAt = 0L;

    private int tickCounter = 0;
    private Screen lastScreen = null;

    public void tick(Minecraft client) {
        if (client == null || client.player == null) {
            return;
        }

        Screen screen = client.screen;
        if (screen != lastScreen) {
            // New screen: allow a fresh capture.
            lastScreen = screen;
            tickCounter = 0;
        }
        if (!(screen instanceof AbstractContainerScreen<?> container)) {
            return;
        }

        if (++tickCounter < SCAN_INTERVAL_TICKS) {
            return;
        }
        tickCounter = 0;

        String title = plain(screen.getTitle());
        if (title == null || !TITLE_PATTERN.matcher(title).find()) {
            return;
        }

        String username = client.player.getGameProfile().name();
        scan(container, title, username);
    }

    private void scan(AbstractContainerScreen<?> container, String title, String username) {
        for (ItemStack stack : container.getMenu().getItems()) {
            if (stack.isEmpty()) {
                continue;
            }

            for (String line : textOf(stack)) {
                Integer found = rankFrom(line, username);
                if (found != null) {
                    rank = found;
                    collectionName = title.trim();
                    capturedAt = System.currentTimeMillis();
                    return;
                }
            }
        }
    }

    /** Extract a rank from a line, if the line refers to the local player. */
    private Integer rankFrom(String line, String username) {
        if (line == null || line.isBlank()) {
            return null;
        }

        // "Your rank: #123" style — unambiguous, so trust it directly.
        Matcher self = SELF_RANK_PATTERN.matcher(line);
        if (self.find()) {
            return parse(self.group(1));
        }

        // Otherwise the line must name the player: "#12 Notch 1,234,567".
        if (username == null || !line.toLowerCase().contains(username.toLowerCase())) {
            return null;
        }
        Matcher rankMatcher = RANK_PATTERN.matcher(line);
        if (rankMatcher.find()) {
            return parse(rankMatcher.group(1));
        }
        return null;
    }

    private Integer parse(String raw) {
        try {
            int value = Integer.parseInt(raw.replace(",", ""));
            return value > 0 ? value : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Display name plus lore, colour codes stripped. */
    private List<String> textOf(ItemStack stack) {
        List<String> lines = new ArrayList<>();
        try {
            lines.add(plain(stack.getHoverName()));
            ItemLore lore = stack.get(DataComponents.LORE);
            if (lore != null) {
                for (Component line : lore.lines()) {
                    lines.add(plain(line));
                }
            }
        } catch (Exception ignored) {
            // A malformed stack should never break the scan.
        }
        return lines;
    }

    private String plain(Component component) {
        if (component == null) {
            return null;
        }
        return COLOR_CODES.matcher(component.getString()).replaceAll("");
    }

    // ---- Queries ----

    public boolean hasRank() {
        return rank > 0;
    }

    public int getRank() {
        return rank;
    }

    public String getCollectionName() {
        return collectionName;
    }

    public long getCapturedAt() {
        return capturedAt;
    }

    public void reset() {
        rank = -1;
        collectionName = "";
        capturedAt = 0L;
    }
}
