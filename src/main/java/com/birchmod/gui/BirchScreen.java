package com.birchmod.gui;

import java.util.ArrayList;
import java.util.List;
import java.util.function.DoubleConsumer;
import java.util.function.DoubleSupplier;
import java.util.function.Supplier;

import com.birchmod.config.BirchConfig;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
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
 * Tabs across the top, a grid of controls beneath, one Done button. Each tab
 * hands back a list of controls rather than positioning anything itself, and
 * the screen fits as many as the window has room for and pages the rest — so a
 * short window shows fewer rows instead of drawing them off the bottom edge,
 * which is what a fixed layout does the first time somebody opens it in a small
 * window.
 */
public class BirchScreen extends Screen {

    private static final int TITLE_Y = 12;
    private static final int TAB_Y = 28;
    private static final int TAB_HEIGHT = 18;
    private static final int GRID_TOP = 54;
    private static final int ROW_HEIGHT = 21;
    private static final int WIDGET_HEIGHT = 20;
    private static final int COLUMNS = 2;
    private static final int COLUMN_GAP = 8;
    private static final int SIDE_MARGIN = 16;
    private static final int FOOTER_HEIGHT = 40;
    private static final int PANEL_COLOR = 0xB0101010;

    /** One page of a tab, built fresh whenever the layout changes. */
    public interface Tab {
        String title();

        /** Controls, in reading order. Positioned by the screen, not the tab. */
        List<Supplier<AbstractWidget>> controls(BirchScreen screen);
    }

    private final Screen parent;
    private final List<Tab> tabs = new ArrayList<>();
    private int activeTab = 0;
    private int page = 0;

    /** Set by a tab when it changes something the screen has to redraw around. */
    private boolean rebuildRequested = false;

    public BirchScreen(Screen parent) {
        super(Component.literal("Birch Optimizer"));
        this.parent = parent;
        tabs.add(new OverlayTab());
        tabs.add(new RouteTab());
        tabs.add(new TreesTab());
        tabs.add(new AlertsTab());
        tabs.add(new RoutesTab());
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
        int usable = Math.max(0, height - GRID_TOP - FOOTER_HEIGHT);
        int rows = Math.max(1, usable / ROW_HEIGHT);
        int perPage = rows * COLUMNS;

        // Tabs.
        int tabWidth = Math.min(96, (width - SIDE_MARGIN * 2) / tabs.size());
        int tabsWidth = tabWidth * tabs.size();
        int tabX = (width - tabsWidth) / 2;

        for (int i = 0; i < tabs.size(); i++) {
            final int index = i;
            Tab tab = tabs.get(i);
            Button button = Button.builder(
                            Component.literal(index == activeTab ? "§f" + tab.title() : "§7" + tab.title()),
                            b -> {
                                activeTab = index;
                                page = 0;
                                rebuildWidgets();
                            })
                    .bounds(tabX + i * tabWidth, TAB_Y, tabWidth - 2, TAB_HEIGHT)
                    .build();
            button.active = index != activeTab;
            addRenderableWidget(button);
        }

        // Controls for the active tab, one page at a time.
        List<Supplier<AbstractWidget>> controls = tabs.get(activeTab).controls(this);
        int pages = Math.max(1, (controls.size() + perPage - 1) / perPage);
        page = Math.min(page, pages - 1);

        int columnWidth = (width - SIDE_MARGIN * 2 - COLUMN_GAP * (COLUMNS - 1)) / COLUMNS;
        int from = page * perPage;
        int to = Math.min(controls.size(), from + perPage);

        for (int i = from; i < to; i++) {
            int slot = i - from;
            int column = slot % COLUMNS;
            int row = slot / COLUMNS;

            AbstractWidget widget = controls.get(i).get();
            widget.setWidth(columnWidth);
            widget.setPosition(SIDE_MARGIN + column * (columnWidth + COLUMN_GAP),
                    GRID_TOP + row * ROW_HEIGHT);
            addRenderableWidget(widget);
        }

        // Footer.
        int footerY = height - 28;
        if (pages > 1) {
            addRenderableWidget(Button.builder(Component.literal("<"), b -> {
                page = (page - 1 + pages) % pages;
                rebuildWidgets();
            }).bounds(width / 2 - 104, footerY, 20, 20).build());

            addRenderableWidget(Button.builder(
                            Component.literal("Page " + (page + 1) + "/" + pages), b -> {
                            })
                    .bounds(width / 2 - 82, footerY, 60, 20).build()).active = false;

            addRenderableWidget(Button.builder(Component.literal(">"), b -> {
                page = (page + 1) % pages;
                rebuildWidgets();
            }).bounds(width / 2 - 20, footerY, 20, 20).build());
        }

        addRenderableWidget(Button.builder(Component.literal("Done"), b -> onClose())
                .bounds(width / 2 + 6, footerY, 98, 20).build());
    }

    @Override
    public void tick() {
        super.tick();
        if (rebuildRequested) {
            rebuildRequested = false;
            rebuildWidgets();
        }
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partial) {
        // Panel first, widgets over it.
        graphics.fill(SIDE_MARGIN - 6, GRID_TOP - 6,
                width - SIDE_MARGIN + 6, height - FOOTER_HEIGHT + 6, PANEL_COLOR);

        super.extractRenderState(graphics, mouseX, mouseY, partial);

        String title = "§6§lBirch Optimizer";
        graphics.text(font, title, (width - font.width(title)) / 2, TITLE_Y, 0xFFFFFFFF, true);

        String hint = tabs.get(activeTab).title();
        graphics.text(font, "§8" + hint, SIDE_MARGIN, height - 44, 0xFFAAAAAA, false);
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

    /** An on/off control wired straight to a config field. */
    static Supplier<AbstractWidget> toggle(String label, String tooltip,
                                           java.util.function.BooleanSupplier get,
                                           java.util.function.Consumer<Boolean> set) {
        return () -> {
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
        };
    }

    /** A slider over a numeric config field. */
    static Supplier<AbstractWidget> slider(String label, String tooltip,
                                           double min, double max, double step,
                                           DoubleSupplier get, DoubleConsumer set) {
        return () -> {
            OptionSlider s = new OptionSlider(label, min, max, step, get.getAsDouble(), value -> {
                set.accept(value);
                BirchConfig.save();
            });
            if (tooltip != null) {
                s.setTooltip(Tooltip.create(Component.literal(tooltip)));
            }
            return s;
        };
    }

    /** A plain button that runs something. */
    static Supplier<AbstractWidget> action(String label, String tooltip, Runnable onPress) {
        return () -> {
            Button button = Button.builder(Component.literal(label), b -> onPress.run())
                    .bounds(0, 0, 150, WIDGET_HEIGHT).build();
            if (tooltip != null) {
                button.setTooltip(Tooltip.create(Component.literal(tooltip)));
            }
            return button;
        };
    }

    /** A label that fills a grid slot, for grouping. */
    static Supplier<AbstractWidget> heading(String text) {
        return () -> {
            Button button = Button.builder(Component.literal("§e§l" + text), b -> {
                    })
                    .bounds(0, 0, 150, WIDGET_HEIGHT).build();
            button.active = false;
            return button;
        };
    }
}
