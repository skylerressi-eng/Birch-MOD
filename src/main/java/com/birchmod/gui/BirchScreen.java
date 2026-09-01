package com.birchmod.gui;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.DoubleConsumer;
import java.util.function.DoubleSupplier;
import java.util.function.Supplier;

import com.birchmod.config.BirchConfig;
import com.birchmod.util.Notifier;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Every setting in one place.
 *
 * The commands are complete and will stay so, but forty of them is a reference
 * manual, not an interface — you have to know a setting exists to change it.
 * Laid out on a screen they are simply visible, and the ones you never think
 * about stop being ones you never find.
 *
 * <h2>Layout</h2>
 * Tabs across the top, a search box, then the tab's controls in two columns
 * under real section headings, and one Done button. Each tab hands back a list
 * of items rather than positioning anything itself.
 *
 * <h2>Why it scrolls rather than pages</h2>
 * It used to page: as many rows as the window had room for, then a {@code < 1/3
 * >} pager to reach the rest. Paging hides things. Which page a setting is on
 * depends on how tall the window happens to be, so there is no learning where
 * anything lives, and there is nothing to tell you the page you are looking at
 * is not all of them. Scrolling shows one continuous list with a bar saying how
 * much is left, and search means the list rarely has to be walked at all.
 *
 * <h2>Headings</h2>
 * Sections are drawn as text with a rule running off them. They were disabled
 * buttons, padded out with a second empty disabled button to fill the other
 * column — so a heading looked exactly like a setting that had been switched
 * off and could not be used, which is the opposite of what a heading is for.
 */
public final class BirchScreen extends Screen {

    private static final int ROW_HEIGHT = 24;
    static final int WIDGET_HEIGHT = 20;
    private static final int COLUMNS = 2;
    private static final int COLUMN_GAP = 8;
    private static final int SECTION_HEIGHT = 22;
    private static final int SEARCH_HEIGHT = 18;

    /** How far one notch of the wheel moves the list. */
    private static final int SCROLL_STEP = 26;

    /** The tab index that is not a grid of settings but a screen of its own. */
    private static final int ROUTES_TAB = 4;

    /**
     * One thing on a settings tab.
     *
     * A section heading or a control, in one type, because a tab writes them
     * in one list and their order is the whole layout. The label and tooltip
     * are kept alongside the widget so search has something to match on
     * without having to ask the widget what it says.
     */
    public static final class Item {
        final String section;
        final String label;
        final String tooltip;
        final Supplier<AbstractWidget> widget;

        private Item(String section, String label, String tooltip,
                     Supplier<AbstractWidget> widget) {
            this.section = section;
            this.label = label;
            this.tooltip = tooltip;
            this.widget = widget;
        }

        boolean isSection() {
            return section != null;
        }

        /** Whether this control answers to what has been typed in search. */
        boolean matches(String needle) {
            if (needle.isEmpty()) {
                return true;
            }
            return contains(label, needle) || contains(tooltip, needle);
        }

        private static boolean contains(String haystack, String needle) {
            return haystack != null && haystack.toLowerCase(Locale.ROOT).contains(needle);
        }
    }

    /** One page of a tab, built fresh whenever the layout changes. */
    public interface Tab {
        String title();

        /** Items, in reading order. Positioned by the screen, not the tab. */
        List<Item> controls(BirchScreen screen);
    }

    /** A control and where it has been placed, so it can be drawn and hit. */
    private record Placed(Item item, AbstractWidget widget, int x, int y) {
    }

    /** A heading and where it has been placed. */
    private record Heading(String text, int y) {
    }

    private final Screen parent;
    private final List<Tab> tabs = new ArrayList<>();
    private int activeTab = 0;

    private EditBox search;
    private String needle = "";

    private final List<Placed> placed = new ArrayList<>();
    private final List<Heading> headings = new ArrayList<>();
    private int contentHeight = 0;
    private int scroll = 0;

    /** Set by a tab when it changes something the screen has to redraw around. */
    private boolean rebuildRequested = false;

    /** Set when the screen could not be built; closed on the next tick. */
    private boolean broken = false;

    public BirchScreen(Screen parent) {
        this(parent, 0);
    }

    public BirchScreen(Screen parent, int tab) {
        super(Component.literal("Birch Optimizer"));
        this.parent = parent;
        tabs.add(new OverlayTab());
        tabs.add(new RouteTab());
        tabs.add(new TreesTab());
        tabs.add(new AlertsTab());
        this.activeTab = Math.max(0, Math.min(tabs.size() - 1, tab));
    }

    /** Rebuild on the next frame. Safe to call from inside a button press. */
    public void requestRebuild() {
        rebuildRequested = true;
    }

    public Font font() {
        return this.font;
    }

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
        placed.clear();
        headings.clear();

        Chrome.tabs(width, activeTab, this::openTab, this::addRenderableWidget);

        // Search keeps its text across a rebuild, so typing and then toggling
        // something does not throw away what was typed.
        String previous = needle;
        search = new EditBox(font, Chrome.MARGIN, Chrome.CONTENT_TOP,
                width - Chrome.MARGIN * 2, SEARCH_HEIGHT,
                Component.literal("Search settings"));
        search.setHint(Component.literal("Search settings…"));
        search.setMaxLength(48);
        search.setValue(previous);
        search.setResponder(text -> {
            needle = text == null ? "" : text.trim().toLowerCase(Locale.ROOT);
            scroll = 0;
            layout();
        });
        addRenderableWidget(search);

        addRenderableWidget(Button.builder(Component.literal("Done"), b -> onClose())
                .bounds(width - Chrome.MARGIN - 80, Chrome.footerY(height), 80, 20).build());

        layout();
    }

    private int listTop() {
        return Chrome.CONTENT_TOP + SEARCH_HEIGHT + 8;
    }

    private int listBottom() {
        return Chrome.contentBottom(height) - 4;
    }

    /**
     * Place every visible control, in two columns under its heading.
     *
     * Controls take part in input through {@code addWidget} rather than
     * {@code addRenderableWidget} so that this screen draws them itself, inside
     * a clip rectangle. Letting the screen draw them would spill half-scrolled
     * rows over the header and the footer.
     */
    private void layout() {
        for (Placed p : placed) {
            removeWidget(p.widget());
        }
        placed.clear();
        headings.clear();

        List<Item> items = tabs.get(activeTab).controls(this);
        int columnWidth = (width - Chrome.MARGIN * 2 - COLUMN_GAP * (COLUMNS - 1) - 6) / COLUMNS;

        int y = 0;
        int column = 0;

        for (int index = 0; index < items.size(); index++) {
            Item item = items.get(index);
            if (item.isSection()) {
                // A heading with nothing under it is noise, so it only earns
                // its space once something below it survives the search.
                if (!anyVisibleUnder(items, index)) {
                    continue;
                }
                if (column != 0) {
                    y += ROW_HEIGHT;
                    column = 0;
                }
                if (y > 0) {
                    y += 6;
                }
                headings.add(new Heading(item.section, y));
                y += SECTION_HEIGHT;
                continue;
            }
            if (!item.matches(needle)) {
                continue;
            }

            AbstractWidget widget = item.widget.get();
            widget.setWidth(columnWidth);
            int x = Chrome.MARGIN + column * (columnWidth + COLUMN_GAP);
            placed.add(new Placed(item, widget, x, y));
            addWidget(widget);

            column++;
            if (column >= COLUMNS) {
                column = 0;
                y += ROW_HEIGHT;
            }
        }
        if (column != 0) {
            y += ROW_HEIGHT;
        }
        contentHeight = y;
        clampScroll();
        applyPositions();
    }

    /** Whether any control between this heading and the next one is showing. */
    private boolean anyVisibleUnder(List<Item> items, int headingIndex) {
        for (int i = headingIndex + 1; i < items.size(); i++) {
            Item next = items.get(i);
            if (next.isSection()) {
                return false;
            }
            if (next.matches(needle)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Move the widgets to where the current scroll puts them.
     *
     * Done when the scroll changes rather than while drawing, because a click
     * is tested against wherever a widget currently is — positioning during
     * render would mean input for a frame was judged against the frame before.
     */
    private void applyPositions() {
        int top = listTop();
        int bottom = listBottom();

        for (Placed p : placed) {
            int drawY = top + p.y() - scroll;
            p.widget().setPosition(p.x(), drawY);
            // Off the view entirely: hidden, so it cannot be clicked through
            // the header or the footer. A hidden widget refuses clicks on its
            // own, so nothing here needs to touch "active" — that says whether
            // a control is usable, which is a different question and one the
            // tab that built it gets to answer.
            p.widget().visible = drawY + WIDGET_HEIGHT > top && drawY < bottom;
        }
    }

    private void clampScroll() {
        int extent = listBottom() - listTop();
        int max = Math.max(0, contentHeight - extent);
        scroll = Math.max(0, Math.min(max, scroll));
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (super.mouseScrolled(mouseX, mouseY, scrollX, scrollY)) {
            return true;
        }
        if (scrollY != 0.0) {
            scroll -= (int) Math.round(scrollY * SCROLL_STEP);
            clampScroll();
            applyPositions();
            return true;
        }
        return false;
    }

    /** Tabs four and under are grids here; the fifth is a screen of its own. */
    private void openTab(int index) {
        if (index == ROUTES_TAB) {
            minecraft.setScreen(new RoutesScreen(parent));
            return;
        }
        activeTab = index;
        scroll = 0;
        rebuildWidgets();
    }

    @Override
    public void tick() {
        super.tick();
        if (broken) {
            broken = false;
            Notifier.chat("§cBirch Optimizer could not open that screen. "
                    + "Your settings are unchanged; see /birch diag.");
            onClose();
            return;
        }
        Chrome.guard("screen", () -> {
            if (rebuildRequested) {
                rebuildRequested = false;
                rebuildWidgets();
            }
        });
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partial) {
        Chrome.background(graphics, font, width, height, activeTab);
        super.extractRenderState(graphics, mouseX, mouseY, partial);

        int top = listTop();
        int bottom = listBottom();
        int right = width - Chrome.MARGIN;

        // Everything in the scrolling area is clipped to it, so a row halfway
        // off the top is cut rather than drawn over the tabs.
        graphics.enableScissor(0, top, width, bottom);

        for (Heading heading : headings) {
            int y = top + heading.y() - scroll;
            Chrome.section(graphics, font, heading.text(), Chrome.MARGIN, y + 6, right - 6);
        }

        for (Placed p : placed) {
            if (!p.widget().visible) {
                continue;
            }
            int y = p.widget().getY();
            if (mouseX >= p.x() && mouseX < p.x() + p.widget().getWidth()
                    && mouseY >= y && mouseY < y + WIDGET_HEIGHT) {
                Chrome.hoverRow(graphics, p.x() - 3, y - 2,
                        p.x() + p.widget().getWidth() + 3, y + WIDGET_HEIGHT + 2);
            }
            p.widget().extractRenderState(graphics, mouseX, mouseY, partial);
        }

        graphics.disableScissor();

        Chrome.scrollbar(graphics, right - 3, top, bottom - top, contentHeight, scroll);

        if (placed.isEmpty()) {
            String none = needle.isEmpty()
                    ? "Nothing on this tab."
                    : "No setting matches \"" + search.getValue() + "\".";
            graphics.text(font, none, Chrome.MARGIN, top + 8, Chrome.TEXT_DIM, false);
        }

        Chrome.hint(graphics, font, needle.isEmpty()
                ? "Hover a setting to see what it does. Changes save as you make them."
                : placed.size() + " setting(s) matching. Clear the box to see them all.",
                height);
    }

    @Override
    public void onClose() {
        BirchConfig.save();
        if (minecraft != null) {
            minecraft.setScreen(parent);
        }
    }

    @Override
    public boolean isPauseScreen() {
        // Foraging carries on behind the menu; pausing would only matter in
        // single player, and this is a Skyblock mod.
        return false;
    }

    // ---- Control helpers, shared by every tab ----

    /** A section heading. Drawn as a heading, not as a switched-off button. */
    public static Item section(String title) {
        return new Item(title, null, null, null);
    }

    /** An on/off control wired straight to a config field. */
    public static Item toggle(String label, String tooltip,
                              java.util.function.BooleanSupplier get,
                              java.util.function.Consumer<Boolean> set) {
        return new Item(null, label, tooltip, () -> {
            CycleButton<Boolean> button = CycleButton.onOffBuilder(get.getAsBoolean())
                    .create(0, 0, 150, WIDGET_HEIGHT, Component.literal(label),
                            (widget, value) -> {
                                set.accept(value);
                                BirchConfig.save();
                            });
            if (tooltip != null) {
                button.setTooltip(Tooltip.create(Component.literal(tooltip)));
            }
            return button;
        });
    }

    /** A slider over a numeric config field. */
    public static Item slider(String label, String tooltip,
                              double min, double max, double step,
                              DoubleSupplier get, DoubleConsumer set) {
        return new Item(null, label, tooltip, () -> {
            OptionSlider s = new OptionSlider(label, min, max, step, get.getAsDouble(), value -> {
                set.accept(value);
                BirchConfig.save();
            });
            if (tooltip != null) {
                s.setTooltip(Tooltip.create(Component.literal(tooltip)));
            }
            return s;
        });
    }

    /** A plain button that runs something. */
    public static Item action(String label, String tooltip, Runnable onPress) {
        return new Item(null, label, tooltip, () -> {
            Button button = Button.builder(Component.literal(label),
                            Chrome.safely("action", onPress))
                    .bounds(0, 0, 150, WIDGET_HEIGHT).build();
            if (tooltip != null) {
                button.setTooltip(Tooltip.create(Component.literal(tooltip)));
            }
            return button;
        });
    }
}
