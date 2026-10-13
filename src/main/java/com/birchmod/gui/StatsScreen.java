package com.birchmod.gui;

import com.birchmod.compat.ClientGui;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.List;

import com.birchmod.BirchMod;
import com.birchmod.api.BazaarManager;
import com.birchmod.api.CollectionApi;
import com.birchmod.api.CollectionTiers;
import com.birchmod.config.BirchConfig;
import com.birchmod.route.LapTracker;
import com.birchmod.route.RecordedRoute;
import com.birchmod.route.RouteLibrary;
import com.birchmod.route.Splits;
import com.birchmod.route.TravelGraph;
import com.birchmod.stats.SessionStats;
import com.birchmod.tracking.TreeRegenTracker;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Everything the mod has measured, on one screen.
 *
 * <h2>Why this exists</h2>
 * The mod was keeping a great deal more than it ever showed. Lifetime totals,
 * what birch regrowth has actually been timed at, how fast you really travel,
 * your best lap on every route, how many trees you are felling a minute, which
 * form of birch currently pays best — all of it measured, all of it persisted,
 * and all of it reachable only by remembering which of forty chat commands
 * prints it and then reading the answer before it scrolls away.
 *
 * Numbers you cannot look at are not really being kept. So they are here, laid
 * out, live, in the same window as the settings — and the two belong together,
 * because almost every number on this screen is the answer to "did changing
 * that setting help?"
 *
 * <h2>Read-only on purpose</h2>
 * There is one button, and it closes the screen. Resetting the session, picking
 * a route, changing what is tracked — those live on the tabs that own them.
 * A screen that both reports and acts invites reading a figure, pressing
 * something, and no longer knowing which of the two the figure described.
 */
public class StatsScreen extends Screen {

    private static final int TAB_INDEX = 5;

    private static final DecimalFormat INT_FMT = new DecimalFormat("#,##0");
    private static final DecimalFormat ONE_DP = new DecimalFormat("#,##0.0");

    private static final int ROW_HEIGHT = 11;
    private static final int SECTION_HEIGHT = 20;
    private static final int SCROLL_STEP = 24;
    private static final int BAR_HEIGHT = 5;

    /** Nothing readable is left below this, so it is the floor for the list. */
    private static final int MIN_LIST_HEIGHT = 8;

    /**
     * How often the figures are re-read.
     *
     * Four times a second. Nothing here moves faster than that, and rebuilding
     * on every frame would mean a few hundred string formats a second to
     * display a lifetime total.
     */
    private static final long REFRESH_MS = 250L;

    /** One line: a label, its value, and how the value is coloured. */
    private sealed interface Row permits Label, Heading, Bar, Note {
    }

    private record Label(String label, String value, int colour) implements Row {
    }

    private record Heading(String text) implements Row {
    }

    /** A progress bar with a caption, for a tier the player is part-way up. */
    private record Bar(String label, String value, double fraction, int colour) implements Row {
    }

    /** A full-width remark, wrapped, for something that needs a sentence. */
    private record Note(String text, int colour) implements Row {
    }

    private final Screen parent;

    private List<Row> rows = List.of();
    private long builtAt = 0L;
    private int contentHeight = 0;
    private int scroll = 0;

    /** Set when the screen could not be built; closed on the next tick. */
    private boolean broken = false;

    public StatsScreen(Screen parent) {
        super(Component.literal("Stats"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        broken = !Chrome.attempt("stats-screen", this::buildScreen);
    }

    private void buildScreen() {
        Chrome.tabs(width, TAB_INDEX, this::openTab, this::addRenderableWidget);
        addRenderableWidget(Button.builder(Component.literal("Done"), b -> onClose())
                .bounds(width - Chrome.MARGIN - 80, Chrome.footerY(height), 80, 20).build());
        rebuild();
    }

    private void openTab(int index) {
        if (index == TAB_INDEX) {
            return;
        }
        if (index == 4) {
            ClientGui.setScreen(minecraft, new RoutesScreen(parent));
            return;
        }
        ClientGui.setScreen(minecraft, new BirchScreen(parent, index));
    }

    private int listTop() {
        return Chrome.CONTENT_TOP;
    }

    private int listBottom() {
        return Math.max(listTop() + MIN_LIST_HEIGHT, Chrome.contentBottom(height) - 4);
    }

    // ---- The figures ----

    /**
     * How tall a row is.
     *
     * A {@link Note} is wrapped, so its height depends on the font and the
     * window — it is measured rather than assumed. Assuming it is tempting
     * because the notes here are short, and wrong at the one width where it
     * matters: guess low and the note is drawn through the row beneath it, guess
     * high and every narrow window has a gap in it.
     */
    private int heightOf(Row row) {
        if (row instanceof Heading) {
            return SECTION_HEIGHT;
        }
        if (row instanceof Bar) {
            return ROW_HEIGHT + BAR_HEIGHT + 3;
        }
        if (row instanceof Note note) {
            return noteHeight(note) + 2;
        }
        return ROW_HEIGHT;
    }

    private int noteHeight(Note note) {
        int wrapWidth = Math.max(16, noteRight() - noteLeft());
        return font.wordWrapHeight(Component.literal(note.text()), wrapWidth);
    }

    private int noteLeft() {
        return Chrome.MARGIN + 6;
    }

    private int noteRight() {
        return width - Chrome.MARGIN - 8;
    }

    /**
     * Re-read every figure.
     *
     * Collected into a fresh list under the guard rather than over the live
     * one: if some tracker throws part-way through, what has been gathered so
     * far is still shown and the previous screen is kept otherwise. A stats
     * screen that blanks itself because one number was unavailable is worse
     * than one missing a row.
     */
    private void rebuild() {
        List<Row> fresh = new ArrayList<>();
        Chrome.guard("stats-rows", () -> collectInto(fresh));
        if (fresh.isEmpty()) {
            return;
        }
        rows = List.copyOf(fresh);
        contentHeight = 0;
        for (Row row : rows) {
            contentHeight += heightOf(row);
        }
        clampScroll();
    }

    private void collectInto(List<Row> out) {
        BirchConfig config = BirchConfig.get();
        session(out);
        collection(out, config);
        laps(out);
        market(out, config);
        calibration(out);
        lifetime(out);
    }

    private void session(List<Row> out) {
        out.add(new Heading("This session"));

        double perHour = BirchMod.tracker == null ? 0.0 : BirchMod.tracker.getBirchPerHour();
        out.add(new Label("Birch per hour", INT_FMT.format(perHour), Chrome.TEXT_GREEN));
        out.add(new Label("Best this session",
                INT_FMT.format(SessionStats.getSessionBestRate()), Chrome.TEXT_DIM));

        double treesPerMinute = SessionStats.getRecentTreesPerMinute();
        out.add(new Label("Trees per minute",
                treesPerMinute > 0.0 ? ONE_DP.format(treesPerMinute) : "—",
                treesPerMinute > 0.0 ? Chrome.TEXT_GOLD : Chrome.TEXT_FAINT));

        out.add(new Label("Birch collected",
                INT_FMT.format(SessionStats.getSessionBirch()), Chrome.TEXT));
        out.add(new Label("Trees felled",
                INT_FMT.format(SessionStats.getSessionTrees()), Chrome.TEXT));

        double perTree = SessionStats.getBirchPerTree();
        out.add(new Label("Birch per tree",
                perTree > 0.0 ? ONE_DP.format(perTree) : "—",
                perTree > 0.0 ? Chrome.TEXT_DIM : Chrome.TEXT_FAINT));

        out.add(new Label("Coins earned",
                INT_FMT.format(SessionStats.getSessionCoins()), Chrome.TEXT_GOLD));

        // Both clocks, because the gap between them is the useful part: it is
        // how much of your session was not foraging, and the rate above is
        // measured against the second one.
        out.add(new Label("Logged in",
                SessionStats.formatDuration(SessionStats.getSessionElapsedMs()), Chrome.TEXT_DIM));
        out.add(new Label("Foraging",
                SessionStats.formatDuration(SessionStats.getActiveMs()), Chrome.TEXT_DIM));
    }

    /**
     * Where you are on the collection ladder.
     *
     * The total needs an API key; the ladder does not. So when there is no key
     * this still shows the rungs and what the next one gives, which is most of
     * why anyone looks — and it fills in the rest the moment a key is set.
     */
    private void collection(List<Row> out, BirchConfig config) {
        out.add(new Heading("Collection"));

        CollectionApi api = BirchMod.collectionApi;
        long collected = api == null ? -1L : api.getBirchCollected();
        String product = config.bazaarProductId;

        if (collected >= 0L) {
            String profile = api.getProfileName();
            out.add(new Label("Total collected", INT_FMT.format(collected)
                    + (profile == null || profile.isEmpty() ? "" : "  (" + profile + ")"),
                    Chrome.TEXT_GREEN));
        } else {
            out.add(new Label("Total collected",
                    api == null ? "—" : api.getStatus(), Chrome.TEXT_FAINT));
        }

        CollectionTiers.Progress progress = CollectionTiers.progressFor(product, collected);
        if (progress == null) {
            out.add(new Label("Tier", CollectionTiers.isLoaded()
                    ? (collected < 0L ? "needs your API key" : "no ladder for " + product)
                    : "loading… (" + CollectionTiers.getStatus() + ")", Chrome.TEXT_FAINT));
        } else if (progress.maxed()) {
            out.add(new Label("Tier", "maxed — " + progress.tier() + "/"
                    + progress.maxTiers(), Chrome.TEXT_GREEN));
        } else {
            out.add(new Bar("Tier " + progress.tier() + " of " + progress.maxTiers(),
                    INT_FMT.format(progress.collected()) + " / "
                            + INT_FMT.format(progress.nextRequired()),
                    progress.fraction(), Chrome.ACCENT));

            double rate = BirchMod.tracker == null ? 0.0 : BirchMod.tracker.getBirchPerHour();
            double seconds = progress.secondsToNext(rate);
            out.add(new Label("To next tier",
                    INT_FMT.format(progress.remaining()) + " birch"
                            + (seconds > 0.0
                                    ? "  (" + SessionStats.formatDuration((long) (seconds * 1000.0)) + ")"
                                    : ""),
                    Chrome.TEXT_GOLD));

            List<String> unlocks = CollectionTiers.nextUnlocks(product, collected);
            if (!unlocks.isEmpty()) {
                out.add(new Note("Unlocks: " + String.join(", ", unlocks), Chrome.TEXT_DIM));
            }
        }

        if (BirchMod.collectionRank != null && BirchMod.collectionRank.hasRank()) {
            out.add(new Label("Leaderboard rank",
                    "#" + INT_FMT.format(BirchMod.collectionRank.getRank()), Chrome.TEXT));
        }
    }

    private void laps(List<Row> out) {
        LapTracker laps = BirchMod.lapTracker;
        RecordedRoute active = RouteLibrary.getActive();
        if (laps == null) {
            return;
        }
        out.add(new Heading("Laps"));

        if (active == null) {
            out.add(new Label("Route", "not following one", Chrome.TEXT_FAINT));
            return;
        }
        out.add(new Label("Route", active.name + "  (" + active.size() + " stops)", Chrome.TEXT));

        double elapsed = laps.getElapsedSeconds();
        out.add(new Label("This lap", elapsed >= 0.0
                ? LapTracker.format(elapsed) + "  —  " + laps.getProgress() + "/" + laps.getLapSize()
                : "not started", elapsed >= 0.0 ? Chrome.TEXT_AQUA : Chrome.TEXT_FAINT));

        out.add(new Label("Best ever", active.bestLapSeconds > 0.0
                ? LapTracker.format(active.bestLapSeconds) : "never lapped",
                active.bestLapSeconds > 0.0 ? Chrome.TEXT_GREEN : Chrome.TEXT_FAINT));
        out.add(new Label("Last", LapTracker.format(laps.getLastLapSeconds()), Chrome.TEXT_DIM));
        out.add(new Label("Average", LapTracker.format(laps.getAverageLapSeconds()), Chrome.TEXT_DIM));
        out.add(new Label("Completed", laps.getLapsCompleted()
                + (laps.getAbandonedLaps() > 0
                        ? "  (" + laps.getAbandonedLaps() + " abandoned)" : ""),
                Chrome.TEXT_DIM));

        // The one actionable thing on this screen: which hop is eating the lap.
        Splits.Report splits = Splits.of(active);
        Splits.Leg worst = splits.worstLeg();
        if (worst != null) {
            out.add(new Label("Slowest leg",
                    "stop " + (worst.from() + 1) + " → " + (worst.to() + 1)
                            + "   +" + ONE_DP.format(worst.overhead()) + "s", Chrome.TEXT_RED));
            out.add(new Note(ONE_DP.format(worst.distance()) + " blocks, but it takes "
                    + ONE_DP.format(worst.actual()) + "s. Worth re-recording.",
                    Chrome.TEXT_FAINT));
        } else if (splits.measuredCount() == 0 && !splits.isEmpty()) {
            out.add(new Label("Slowest leg", "needs a few more laps", Chrome.TEXT_FAINT));
        } else if (!splits.isEmpty()) {
            out.add(new Label("Slowest leg", "nothing losing time", Chrome.TEXT_GREEN));
        }
    }

    private void market(List<Row> out, BirchConfig config) {
        BazaarManager bazaar = BirchMod.bazaar;
        if (bazaar == null) {
            return;
        }
        out.add(new Heading("Bazaar"));

        if (!bazaar.hasData()) {
            out.add(new Label("Prices", bazaar.getStatus(), Chrome.TEXT_FAINT));
            return;
        }

        out.add(new Label("Tracking", BazaarManager.friendlyName(config.bazaarProductId)
                + "  (" + config.bazaarProductId + ")", Chrome.TEXT));
        out.add(new Label(config.showBuyPrice ? "Insta-buy" : "Insta-sell",
                ONE_DP.format(bazaar.getDisplayPrice()), Chrome.TEXT_GOLD));

        double netPerLog = bazaar.getBestNetPerLog();
        String best = bazaar.getBestProductId();
        if (netPerLog > 0.0) {
            // Which form pays best is the whole point of fetching more than one
            // price, and the HUD's coins-per-hour is computed from it — so the
            // answer has to be visible somewhere, or that figure is unexplained.
            out.add(new Label("Best payout", ONE_DP.format(netPerLog) + " per log"
                    + (best == null ? "" : "  via " + BazaarManager.friendlyName(best)),
                    Chrome.TEXT_GREEN));

            double perHour = BirchMod.tracker == null ? 0.0 : BirchMod.tracker.getBirchPerHour();
            out.add(new Label("Coins per hour", INT_FMT.format(perHour * netPerLog),
                    Chrome.TEXT_GOLD));
        }
        out.add(new Label("Tax", config.applyBazaarTax
                ? ONE_DP.format(config.bazaarTaxRate * 100.0) + "% applied" : "not applied",
                Chrome.TEXT_DIM));

        long age = bazaar.getMinutesSinceUpdate();
        out.add(new Label("Updated", age < 0L ? "never"
                : age == 0L ? "just now" : age + "m ago", Chrome.TEXT_FAINT));
    }

    /**
     * What the mod has learnt about this player, rather than about the game.
     *
     * These two numbers are underneath every estimate the mod makes — every
     * countdown, every ETA, every route score — and they are both measured from
     * play rather than configured. Showing them is what makes the estimates
     * arguable: an ETA that feels wrong is usually a walk speed that was
     * measured while standing in a tree.
     */
    private void calibration(List<Row> out) {
        out.add(new Heading("Calibrated from your play"));

        TreeRegenTracker regen = BirchMod.regenTracker;
        if (regen != null) {
            boolean known = regen.isCalibrated();
            out.add(new Label("Birch regrowth",
                    ONE_DP.format(regen.getRegenSeconds()) + "s"
                            + (known ? "" : "  (still estimating)"),
                    known ? Chrome.TEXT_GREEN : Chrome.TEXT_FAINT));
            out.add(new Label("Regrowths timed",
                    INT_FMT.format(SessionStats.getRegenSamples()), Chrome.TEXT_DIM));
            out.add(new Label("Trees tracked", regen.getTrackedCount()
                    + " nearby,  " + regen.getDownedTrees().size() + " regrowing",
                    Chrome.TEXT_DIM));
        }

        double speed = SessionStats.getMeasuredWalkSpeed();
        out.add(new Label("Travel speed", speed > 0.0
                ? ONE_DP.format(speed) + " blocks/s"
                : "assuming " + ONE_DP.format(RouteLibrary.DEFAULT_WALK_BLOCKS_PER_SECOND),
                speed > 0.0 ? Chrome.TEXT_GREEN : Chrome.TEXT_FAINT));
        out.add(new Label("Speed samples",
                INT_FMT.format(SessionStats.getWalkSamples()), Chrome.TEXT_DIM));
        out.add(new Label("Trees in travel map",
                INT_FMT.format(TravelGraph.nodeCount()), Chrome.TEXT_DIM));
    }

    private void lifetime(List<Row> out) {
        out.add(new Heading("All time"));
        SessionStats.Lifetime life = SessionStats.getLifetime();

        out.add(new Label("Birch collected", INT_FMT.format(life.birchCollected), Chrome.TEXT_GREEN));
        out.add(new Label("Trees felled", INT_FMT.format(life.treesChopped), Chrome.TEXT));
        out.add(new Label("Coins earned", INT_FMT.format(life.coinsEarned), Chrome.TEXT_GOLD));
        out.add(new Label("Time foraging",
                SessionStats.formatDuration(life.playtimeMs), Chrome.TEXT_DIM));
        out.add(new Label("Best birch per hour",
                INT_FMT.format(life.bestBirchPerHour), Chrome.TEXT_AQUA));
        out.add(new Label("Routes saved", String.valueOf(RouteLibrary.all().size()), Chrome.TEXT_DIM));
    }

    // ---- Drawing ----

    @Override
    public void tick() {
        super.tick();
        if (broken) {
            broken = false;
            com.birchmod.util.Notifier.chat("§cBirch Optimizer could not open the stats screen. "
                    + "See /birch diag.");
            onClose();
            return;
        }
        if (System.currentTimeMillis() - builtAt >= REFRESH_MS) {
            builtAt = System.currentTimeMillis();
            Chrome.guard("stats-refresh", this::rebuild);
        }
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partial) {
        extractTransparentBackground(graphics);
        Chrome.background(graphics, font, width, height, TAB_INDEX);
        super.extractRenderState(graphics, mouseX, mouseY, partial);

        int top = listTop();
        int bottom = listBottom();
        int left = Chrome.MARGIN;
        int right = width - Chrome.MARGIN - 8;

        graphics.enableScissor(0, top, width, bottom);

        int y = top - scroll;
        for (Row row : rows) {
            int next = y + heightOf(row);
            // Rows entirely outside the view still have to advance the cursor,
            // but nothing else: a long screen is mostly off-screen, and these
            // are font measurements.
            if (next > top && y < bottom) {
                draw(graphics, row, left, right, y);
            }
            y = next;
        }

        graphics.disableScissor();

        Chrome.scrollbar(graphics, width - Chrome.MARGIN - 3, top, bottom - top,
                contentHeight, scroll);
        Chrome.hint(graphics, font,
                "Everything here is measured, not configured. Scroll for more.", height);
    }

    private void draw(GuiGraphicsExtractor graphics, Row row, int left, int right, int y) {
        if (row instanceof Heading heading) {
            Chrome.section(graphics, font, heading.text(), left, y + 8, right);
            return;
        }
        if (row instanceof Label label) {
            graphics.text(font, label.label(), left, y, Chrome.TEXT_FAINT, false);
            graphics.text(font, label.value(),
                    right - font.width(label.value()), y, label.colour(), false);
            return;
        }
        if (row instanceof Note note) {
            graphics.textWithWordWrap(font, Component.literal(note.text()),
                    noteLeft(), y, noteRight() - noteLeft(), note.colour());
            return;
        }
        if (row instanceof Bar bar) {
            graphics.text(font, bar.label(), left, y, Chrome.TEXT_FAINT, false);
            graphics.text(font, bar.value(),
                    right - font.width(bar.value()), y, Chrome.TEXT, false);

            int barTop = y + ROW_HEIGHT + 1;
            graphics.fill(left, barTop, right, barTop + BAR_HEIGHT, 0x60000000);
            int filled = (int) Math.round((right - left) * bar.fraction());
            if (filled > 0) {
                graphics.fill(left, barTop, left + filled, barTop + BAR_HEIGHT, bar.colour());
            }
        }
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (super.mouseScrolled(mouseX, mouseY, scrollX, scrollY)) {
            return true;
        }
        if (scrollY != 0.0) {
            scroll -= (int) Math.round(scrollY * SCROLL_STEP);
            clampScroll();
            return true;
        }
        return false;
    }

    private void clampScroll() {
        int max = Math.max(0, contentHeight - (listBottom() - listTop()));
        scroll = Math.max(0, Math.min(max, scroll));
    }

    @Override
    public void onClose() {
        if (minecraft != null) {
            ClientGui.setScreen(minecraft, parent);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
