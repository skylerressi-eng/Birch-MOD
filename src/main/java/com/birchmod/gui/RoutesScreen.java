package com.birchmod.gui;

import java.nio.file.Path;

import com.birchmod.BirchMod;
import com.birchmod.route.LapTracker;
import com.birchmod.route.RecordedRoute;
import com.birchmod.route.RouteCodec;
import com.birchmod.route.RouteFiles;
import com.birchmod.route.RouteLibrary;
import com.birchmod.util.Notifier;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Your routes: pick one on the left, do something with it on the right.
 *
 * A list you click and a panel that acts on what you clicked, rather than a
 * grid of buttons repeating every route's name five times. Which route a button
 * applies to is then never a question — it is the one that is highlighted.
 */
public class RoutesScreen extends Screen {

    private static final int TAB_INDEX = 4;
    private static final int BUTTON_HEIGHT = 20;
    private static final int BUTTON_GAP = 3;

    /** Buttons in the detail column, for working out whether they fit. */
    private static final int BUTTONS = 5;

    /** Height the stats block needs before it is worth showing at all. */
    private static final int STATS_HEIGHT = 62;

    /** Nothing readable is left below this, so it is the floor. */
    private static final int MIN_BUTTON_HEIGHT = 13;

    /** Row pitch, button height and whether the stats fit — all from the window. */
    private int pitch = BUTTON_HEIGHT + BUTTON_GAP;
    private int buttonHeight = BUTTON_HEIGHT;
    private boolean showStats = true;

    private final Screen parent;

    private RouteListWidget list;
    private Button follow;
    private Button makeDefault;
    private Button export;
    private Button copy;
    private Button delete;

    /** Redrawn each frame from the selection, so the panel never lies. */
    private String shownName = null;

    /** How long a primed Delete stays primed before it forgets. */
    private static final long CONFIRM_WINDOW_MS = 4_000L;

    /** The route Delete is currently asking about, and until when. */
    private String armedFor = null;
    private long armedUntil = 0L;

    public RoutesScreen(Screen parent) {
        super(Component.literal("Routes"));
        this.parent = parent;
    }

    private double regenSeconds() {
        return BirchMod.regenTracker != null ? BirchMod.regenTracker.getRegenSeconds() : 60.0;
    }

    /** Set when the screen could not be built; closed on the next tick. */
    private boolean broken = false;

    @Override
    protected void init() {
        // A screen is drawn and rebuilt by the game, not by us: anything that
        // escapes here reaches the game's own loop and becomes a crash report.
        // A screen that could not be built has no buttons on it — including
        // the one that closes it — so leave rather than strand the player in
        // an empty window.
        broken = !Chrome.attempt("screen", this::buildScreen);
    }

    private void buildScreen() {
        Chrome.tabs(width, TAB_INDEX, this::openTab, this::addRenderableWidget);

        int top = Chrome.CONTENT_TOP;
        int bottom = Chrome.contentBottom(height);
        int listWidth = Math.max(120, (width - Chrome.MARGIN * 2) * 45 / 100);
        int detailX = Chrome.MARGIN + listWidth + 10;
        int detailWidth = width - Chrome.MARGIN - detailX;

        list = new RouteListWidget(minecraft, listWidth, bottom - top, Chrome.MARGIN, top);
        list.refresh(regenSeconds());
        addRenderableWidget(list);

        // Fit the column to the window rather than assuming it is tall. Five
        // buttons at a fixed pitch ran straight past the bottom of the panel
        // in a short window, which is the first thing anybody sees at a large
        // GUI scale.
        int buttonsTop = top + 26;
        int room = Chrome.contentBottom(height) - 12 - buttonsTop;
        showStats = room >= BUTTONS * (BUTTON_HEIGHT + BUTTON_GAP) + STATS_HEIGHT;
        if (showStats) {
            room -= STATS_HEIGHT;
        }
        // Spacing alone is not enough to make five buttons fit a short window:
        // below a certain height the buttons themselves have to be shorter, or
        // the last one is drawn through the footer.
        pitch = Math.max(MIN_BUTTON_HEIGHT + 1,
                Math.min(BUTTON_HEIGHT + BUTTON_GAP, room / BUTTONS));
        buttonHeight = Math.max(MIN_BUTTON_HEIGHT, Math.min(BUTTON_HEIGHT, pitch - 1));

        int y = buttonsTop;
        follow = addRenderableWidget(Button.builder(Component.literal("Follow"), b -> {
            withSelection(name -> {
                RouteLibrary.setActive(name);
                resetRoute();
                Notifier.actionBar("§aFollowing " + name);
                list.refresh(regenSeconds());
            });
        }).bounds(detailX, y, detailWidth, buttonHeight).build());
        follow.setTooltip(Tooltip.create(Component.literal(
                "Start following this route now.")));

        y += pitch;
        makeDefault = addRenderableWidget(Button.builder(Component.literal("Make default"), b -> {
            withSelection(name -> {
                RouteLibrary.setDefault(name);
                resetRoute();
                Notifier.actionBar("§6" + name + " is your default");
                list.refresh(regenSeconds());
            });
        }).bounds(detailX, y, detailWidth, buttonHeight).build());
        makeDefault.setTooltip(Tooltip.create(Component.literal(
                "Come back to this route on every login, whatever you were "
                        + "following when you left.")));

        y += pitch;
        export = addRenderableWidget(Button.builder(Component.literal("Export to file"),
                b -> withSelection(this::exportToFile))
                .bounds(detailX, y, detailWidth, buttonHeight).build());
        export.setTooltip(Tooltip.create(Component.literal(
                "Save this route as a file you can send to somebody.")));

        y += pitch;
        copy = addRenderableWidget(Button.builder(Component.literal("Copy code"),
                b -> withSelection(this::copyCode))
                .bounds(detailX, y, detailWidth, buttonHeight).build());
        copy.setTooltip(Tooltip.create(Component.literal(
                "Put a share code on the clipboard, to paste straight into a chat.")));

        y += pitch;
        delete = addRenderableWidget(Button.builder(Component.literal("§cDelete"),
                b -> onDeletePressed())
                .bounds(detailX, y, detailWidth, buttonHeight).build());
        delete.setTooltip(Tooltip.create(Component.literal(
                "Remove this route for good. Asks once before it does.")));

        // Footer.
        int footerY = Chrome.footerY(height);
        addRenderableWidget(Button.builder(Component.literal("Import a route…"),
                        b -> minecraft.setScreen(new ImportScreen(this)))
                .bounds(Chrome.MARGIN, footerY, 130, BUTTON_HEIGHT).build());

        addRenderableWidget(Button.builder(Component.literal("Done"), b -> onClose())
                .bounds(width - Chrome.MARGIN - 90, footerY, 90, BUTTON_HEIGHT).build());

        updateButtons();
    }

    private void openTab(int index) {
        if (index == TAB_INDEX) {
            return;
        }
        minecraft.setScreen(new BirchScreen(parent, index));
    }

    @Override
    public void tick() {
        super.tick();
        if (broken) {
            broken = false;
            Notifier.chat(
                    "§cBirch Optimizer could not open that screen. "
                            + "Your settings are unchanged; see /birch diag.");
            onClose();
            return;
        }
        Chrome.guard("screen", this::updateButtons);
    }

    /**
     * Keep the buttons honest about the selection.
     *
     * A button that does nothing is worse than one that is not there: it says
     * the thing is possible. With nothing picked they are all off, and the two
     * that are already true of the selected route say so instead of offering
     * to do them again.
     */
    private void updateButtons() {
        shownName = list == null ? null : list.selectedName();
        boolean any = shownName != null;

        boolean isFollowing = any && shownName.equalsIgnoreCase(RouteLibrary.getActiveName());
        boolean isDefault = any && shownName.equalsIgnoreCase(RouteLibrary.getDefaultName());

        follow.active = any && !isFollowing;
        follow.setMessage(Component.literal(isFollowing ? "§aFollowing" : "Follow"));

        makeDefault.active = any && !isDefault;
        makeDefault.setMessage(Component.literal(isDefault ? "§6Default" : "Make default"));

        export.active = any;
        copy.active = any;

        // Selecting a different route cancels a question asked about the last.
        if (armedFor != null && !armedFor.equals(shownName)) {
            disarm();
        }
        delete.active = any;
        delete.setMessage(Component.literal(
                deleteArmed() ? "§4§lDelete for good?" : "§cDelete"));
    }

    /**
     * Run an action on the selected route, if there is one.
     *
     * Guarded here rather than at each button, because every one of these ends
     * up touching something that can fail — the disk, the clipboard, the saved
     * library — and a screen is the one place in this mod where a throw used to
     * reach the game.
     */
    private void withSelection(java.util.function.Consumer<String> action) {
        Chrome.guard("routes", () -> {
            String name = list.selectedName();
            if (name != null) {
                action.accept(name);
            }
        });
    }

    // ---- Deleting ----

    /**
     * Delete asks first.
     *
     * A route is minutes of walking a grove in the order you worked out, and
     * the button that destroys it sat directly under four that do not, at the
     * end of a column, where the mouse ends up. Its own tooltip said "there is
     * no undo", which is a warning where a question was wanted. The first press
     * arms it and says so; the second does it. Anything else — picking another
     * route, leaving, or simply waiting — puts it back.
     */
    private void onDeletePressed() {
        if (armedFor != null && armedFor.equals(list.selectedName())
                && System.currentTimeMillis() < armedUntil) {
            withSelection(name -> {
                RouteLibrary.delete(name);
                resetRoute();
                Notifier.actionBar("§7Deleted " + name);
                list.refresh(regenSeconds());
            });
            disarm();
            return;
        }
        armedFor = list.selectedName();
        armedUntil = System.currentTimeMillis() + CONFIRM_WINDOW_MS;
        updateButtons();
    }

    private void disarm() {
        armedFor = null;
        armedUntil = 0L;
    }

    /** Whether the delete button is currently asking rather than deleting. */
    private boolean deleteArmed() {
        return armedFor != null
                && armedFor.equals(shownName)
                && System.currentTimeMillis() < armedUntil;
    }

    // ---- Sharing ----

    private void exportToFile(String name) {
        RecordedRoute route = RouteLibrary.get(name);
        if (route == null) {
            return;
        }
        try {
            Path written = RouteFiles.write(RouteFiles.directory(), route);
            Notifier.chat("§aSaved §f" + written.getFileName() + "§a to §7"
                    + RouteFiles.directory());
            Notifier.actionBar("§aExported " + name);
        } catch (Exception e) {
            Notifier.chat("§cCould not write that route to a file.");
        }
    }

    private void copyCode(String name) {
        RecordedRoute route = RouteLibrary.get(name);
        if (route == null) {
            return;
        }
        String code = RouteCodec.encode(route);
        try {
            minecraft.keyboardHandler.setClipboard(code);
            Notifier.actionBar("§aCopied " + name + " — " + code.length() + " characters");
        } catch (Exception e) {
            Notifier.chat("§cCould not reach the clipboard.");
        }
    }

    private static void resetRoute() {
        if (BirchMod.routeBuilder != null) {
            BirchMod.routeBuilder.resetCommitment();
        }
    }

    // ---- Drawing ----

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partial) {
        Chrome.background(graphics, font, width, height, TAB_INDEX);

        int listWidth = Math.max(120, (width - Chrome.MARGIN * 2) * 45 / 100);
        int detailX = Chrome.MARGIN + listWidth + 10;
        int detailRight = width - Chrome.MARGIN;

        // The pane sits in a card, so the buttons read as belonging to the
        // route named above them rather than floating beside a list.
        Chrome.card(graphics, detailX - 6, Chrome.CONTENT_TOP - 4,
                detailRight, Chrome.contentBottom(height) - 4);

        super.extractRenderState(graphics, mouseX, mouseY, partial);

        int y = Chrome.CONTENT_TOP + 2;

        if (shownName == null) {
            emptyPane(graphics, detailX, y, detailRight);
            return;
        }

        RecordedRoute route = RouteLibrary.get(shownName);
        if (route == null) {
            return;
        }

        graphics.text(font, "§f§l" + route.name, detailX, y, Chrome.TEXT, false);

        // Badges say what this route already is, so the buttons below do not
        // have to carry that as well.
        String badges = "";
        if (route.name.equalsIgnoreCase(RouteLibrary.getActiveName())) {
            badges += "§a▶ following  ";
        }
        if (route.name.equalsIgnoreCase(RouteLibrary.getDefaultName())) {
            badges += "§6★ default";
        }
        graphics.text(font, badges.isEmpty() ? "§8" + route.size() + " stops" : badges,
                detailX, y + 11, Chrome.TEXT_DIM, false);

        if (showStats) {
            statsCard(graphics, route, detailX, detailRight);
        }
    }

    /** What the plan expects of this route, as labelled rows. */
    private void statsCard(GuiGraphicsExtractor graphics, RecordedRoute route,
                           int left, int right) {
        RouteLibrary.Score score = RouteLibrary.score(route, regenSeconds());

        int bottom = Chrome.contentBottom(height) - 10;
        int top = bottom - 46;
        Chrome.rule(graphics, left, right - 6, top - 6);

        row(graphics, left, right, top, "Stops", String.valueOf(route.size()), Chrome.TEXT);
        row(graphics, left, right, top + 12, "Best lap",
                route.bestLapSeconds > 0.0 ? LapTracker.format(route.bestLapSeconds) : "never lapped",
                route.bestLapSeconds > 0.0 ? Chrome.TEXT_GREEN : Chrome.TEXT_FAINT);
        row(graphics, left, right, top + 24, "Predicted lap",
                LapTracker.format(score.lapSeconds()), Chrome.TEXT_DIM);
        row(graphics, left, right, top + 36, "Trees/min",
                String.format("%.1f", score.treesPerMinute()), Chrome.TEXT_GOLD);
    }

    /** A label on the left, its value flush right. */
    private void row(GuiGraphicsExtractor graphics, int left, int right, int y,
                     String label, String value, int colour) {
        graphics.text(font, label, left, y, Chrome.TEXT_FAINT, false);
        graphics.text(font, value, right - 6 - font.width(value), y, colour, false);
    }

    /** Nothing selected, because there is nothing to select. */
    private void emptyPane(GuiGraphicsExtractor graphics, int x, int y, int right) {
        graphics.text(font, "§fNo routes yet", x, y, Chrome.TEXT, false);
        graphics.textWithWordWrap(font, Component.literal(
                        "§7Record one by chopping the trees you want, in the order you want them."),
                x, y + 14, right - x - 6, Chrome.TEXT_DIM);
        graphics.text(font, "§8/route start <name>", x, y + 46, Chrome.TEXT_FAINT, false);
        graphics.text(font, "§8/route stop", x, y + 57, Chrome.TEXT_FAINT, false);
        graphics.text(font, "§7…or Import a route below.", x, y + 73, Chrome.TEXT_DIM, false);
    }

    @Override
    public void onClose() {
        minecraft.setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
