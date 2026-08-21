package com.birchmod.gui;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.birchmod.BirchMod;
import com.birchmod.route.RecordedRoute;
import com.birchmod.route.RouteCodec;
import com.birchmod.route.RouteFiles;
import com.birchmod.route.RouteLibrary;
import com.birchmod.util.Notifier;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Pick a route to import.
 *
 * Every route file in the folder, plus whatever is on the clipboard, in one
 * list. The clipboard belongs here rather than behind its own button because
 * from the player's side it is the same act — choose a route from somewhere
 * else and take it — and splitting that across two places makes you decide
 * which kind of import you are doing before you have decided what to import.
 */
public class ImportScreen extends Screen {

    private static final int BUTTON_HEIGHT = 20;

    private final Screen parent;
    private SourceList list;
    private Button importButton;

    public ImportScreen(Screen parent) {
        super(Component.literal("Import a route"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int top = Chrome.CONTENT_TOP;
        int bottom = Chrome.contentBottom(height);
        int listWidth = width - Chrome.MARGIN * 2;

        list = new SourceList(minecraft, listWidth, bottom - top, Chrome.MARGIN, top);
        list.reload();
        addRenderableWidget(list);

        int footerY = Chrome.footerY(height);

        addRenderableWidget(Button.builder(Component.literal("Back"),
                        b -> minecraft.setScreen(parent))
                .bounds(Chrome.MARGIN, footerY, 70, BUTTON_HEIGHT).build());

        Button refresh = addRenderableWidget(Button.builder(Component.literal("Refresh"), b -> {
            list.reload();
            updateButtons();
        }).bounds(Chrome.MARGIN + 76, footerY, 70, BUTTON_HEIGHT).build());
        refresh.setTooltip(Tooltip.create(Component.literal(
                "Look at the folder and the clipboard again — use this after "
                        + "dropping a file in or copying a code.")));

        importButton = addRenderableWidget(Button.builder(Component.literal("Import"),
                        b -> importSelected())
                .bounds(width - Chrome.MARGIN - 110, footerY, 110, BUTTON_HEIGHT).build());
        importButton.setTooltip(Tooltip.create(Component.literal(
                "Add this route to your own. Nothing you have is overwritten.")));

        updateButtons();
    }

    @Override
    public void tick() {
        super.tick();
        updateButtons();
    }

    private void updateButtons() {
        SourceEntry selected = list == null ? null : list.getSelected();
        importButton.active = selected != null && selected.problem == null;
    }

    private void importSelected() {
        SourceEntry selected = list.getSelected();
        if (selected == null || selected.route == null) {
            return;
        }
        RecordedRoute route = selected.route;
        String wanted = route.name;
        route.name = RouteCodec.freeName(wanted, RouteLibrary::exists);

        RouteLibrary.save(route);
        RouteLibrary.setActive(route.name);
        if (BirchMod.routeBuilder != null) {
            BirchMod.routeBuilder.resetCommitment();
        }

        Notifier.chat("§aImported §f" + route.name + "§a with " + route.size()
                + " stops, and you are on it now.");
        if (!route.name.equals(wanted)) {
            Notifier.chat("§8You already had a §f" + wanted + "§8, so this is §f"
                    + route.name + "§8.");
        }
        minecraft.setScreen(parent);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partial) {
        Chrome.background(graphics, font, width, height, 4);
        super.extractRenderState(graphics, mouseX, mouseY, partial);

        String heading = "Import a route";
        graphics.text(font, "§f§l" + heading, (width - font.width(heading)) / 2,
                Chrome.CONTENT_TOP - 18, Chrome.TEXT, false);

        String folder = "§8Files live in " + RouteFiles.directory();
        graphics.text(font, folder, Chrome.MARGIN, Chrome.contentBottom(height) + 4,
                Chrome.TEXT_DIM, false);
    }

    @Override
    public void onClose() {
        minecraft.setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    // ---- The list ----

    /** One importable route: a file, or the clipboard. */
    public class SourceEntry extends ObjectSelectionList.Entry<SourceEntry> {

        private final String label;
        private final String detail;
        final RecordedRoute route;
        final String problem;

        SourceEntry(String label, String detail, RecordedRoute route, String problem) {
            this.label = label;
            this.detail = detail;
            this.route = route;
            this.problem = problem;
        }

        @Override
        public void extractContent(GuiGraphicsExtractor graphics, int mouseX, int mouseY,
                                   boolean hovered, float partial) {
            int x = getContentX() + 4;
            int y = getContentY() + 3;

            graphics.text(minecraft.font, (problem == null ? "§f" : "§7") + label,
                    x, y, Chrome.TEXT, false);
            graphics.text(minecraft.font,
                    problem == null ? "§7" + detail : "§c" + problem,
                    x, y + 11, problem == null ? Chrome.TEXT_DIM : 0xFFFF6666, false);
        }

        @Override
        public Component getNarration() {
            return Component.literal(label);
        }
    }

    /** Files plus the clipboard, rebuilt on demand. */
    public class SourceList extends ObjectSelectionList<SourceEntry> {

        SourceList(Minecraft minecraft, int width, int height, int x, int y) {
            super(minecraft, width, height, y, 26);
            setX(x);
        }

        @Override
        public int getRowWidth() {
            return getWidth() - 12;
        }

        void reload() {
            clearEntries();
            List<SourceEntry> entries = new ArrayList<>();

            for (RouteFiles.Saved saved : RouteFiles.list(RouteFiles.directory())) {
                if (saved.isUsable()) {
                    RecordedRoute route = readQuietly(saved.file());
                    entries.add(route != null
                            ? new SourceEntry(saved.fileName(),
                            route.name + " · " + route.size() + " stops", route, null)
                            : new SourceEntry(saved.fileName(), "", null, "could not be read"));
                } else {
                    entries.add(new SourceEntry(saved.fileName(), "", null, saved.problem()));
                }
            }

            SourceEntry clipboard = fromClipboard();
            if (clipboard != null) {
                entries.add(clipboard);
            }

            for (SourceEntry entry : entries) {
                addEntry(entry);
            }
            if (!children().isEmpty()) {
                setSelected(children().get(0));
            }
        }

        private RecordedRoute readQuietly(Path file) {
            try {
                return RouteFiles.read(file);
            } catch (Exception e) {
                return null;
            }
        }

        /** The clipboard, if it happens to hold a route. */
        private SourceEntry fromClipboard() {
            String text;
            try {
                text = minecraft.keyboardHandler.getClipboard();
            } catch (Exception e) {
                return null;
            }
            if (text == null || text.isBlank()) {
                return null;
            }
            for (String line : text.split("\\R")) {
                String trimmed = line.trim();
                if (!trimmed.startsWith(RouteCodec.PREFIX)) {
                    continue;
                }
                try {
                    RecordedRoute route = RouteCodec.decode(trimmed);
                    return new SourceEntry("From clipboard",
                            route.name + " · " + route.size() + " stops", route, null);
                } catch (RouteCodec.CodecException e) {
                    return new SourceEntry("From clipboard", "", null, e.getMessage());
                }
            }
            return null;
        }
    }
}
