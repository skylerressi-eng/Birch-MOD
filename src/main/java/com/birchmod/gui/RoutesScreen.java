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

        int y = top + 34;
        follow = addRenderableWidget(Button.builder(Component.literal("Follow"), b -> {
            withSelection(name -> {
                RouteLibrary.setActive(name);
                resetRoute();
                Notifier.actionBar("§aFollowing " + name);
                list.refresh(regenSeconds());
            });
        }).bounds(detailX, y, detailWidth, BUTTON_HEIGHT).build());
        follow.setTooltip(Tooltip.create(Component.literal(
                "Start following this route now.")));

        y += BUTTON_HEIGHT + BUTTON_GAP;
        makeDefault = addRenderableWidget(Button.builder(Component.literal("Make default"), b -> {
            withSelection(name -> {
                RouteLibrary.setDefault(name);
                resetRoute();
                Notifier.actionBar("§6" + name + " is your default");
                list.refresh(regenSeconds());
            });
        }).bounds(detailX, y, detailWidth, BUTTON_HEIGHT).build());
        makeDefault.setTooltip(Tooltip.create(Component.literal(
                "Come back to this route on every login, whatever you were "
                        + "following when you left.")));

        y += BUTTON_HEIGHT + BUTTON_GAP;
        export = addRenderableWidget(Button.builder(Component.literal("Export to file"),
                b -> withSelection(this::exportToFile))
                .bounds(detailX, y, detailWidth, BUTTON_HEIGHT).build());
        export.setTooltip(Tooltip.create(Component.literal(
                "Save this route as a file you can send to somebody.")));

        y += BUTTON_HEIGHT + BUTTON_GAP;
        copy = addRenderableWidget(Button.builder(Component.literal("Copy code"),
                b -> withSelection(this::copyCode))
                .bounds(detailX, y, detailWidth, BUTTON_HEIGHT).build());
        copy.setTooltip(Tooltip.create(Component.literal(
                "Put a share code on the clipboard, to paste straight into a chat.")));

        y += BUTTON_HEIGHT + BUTTON_GAP;
        delete = addRenderableWidget(Button.builder(Component.literal("§cDelete"),
                b -> onDeletePressed())
                .bounds(detailX, y, detailWidth, BUTTON_HEIGHT).build());
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
        super.extractRenderState(graphics, mouseX, mouseY, partial);

        int listWidth = Math.max(120, (width - Chrome.MARGIN * 2) * 45 / 100);
        int detailX = Chrome.MARGIN + listWidth + 10;
        int y = Chrome.CONTENT_TOP + 4;

        if (shownName == null) {
            graphics.text(font, "§7No routes saved yet.", detailX, y, Chrome.TEXT_DIM, false);
            graphics.text(font, "§8/route start <name>", detailX, y + 12, Chrome.TEXT_DIM, false);
            return;
        }

        RecordedRoute route = RouteLibrary.get(shownName);
        if (route == null) {
            return;
        }
        RouteLibrary.Score score = RouteLibrary.score(route, regenSeconds());

        graphics.text(font, "§f§l" + route.name, detailX, y, Chrome.TEXT, true);

        String best = route.bestLapSeconds > 0.0
                ? "Best lap " + LapTracker.format(route.bestLapSeconds)
                : "Never lapped";
        graphics.text(font, "§7" + route.size() + " stops · §a" + best,
                detailX, y + 12, Chrome.TEXT_DIM, false);

        // Below the buttons: what the plan expects of this route.
        int notesY = Chrome.contentBottom(height) - 26;
        graphics.text(font, String.format("§8Predicted %.1f trees/min", score.treesPerMinute()),
                detailX, notesY, Chrome.TEXT_DIM, false);
        graphics.text(font, String.format("§8Lap %s of walking",
                        LapTracker.format(score.lapSeconds())),
                detailX, notesY + 11, Chrome.TEXT_DIM, false);
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
