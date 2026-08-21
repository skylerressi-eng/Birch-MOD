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

    private static final int ROW_HEIGHT = 23;
    static final int WIDGET_HEIGHT = 20;
    private static final int COLUMNS = 2;
    private static final int COLUMN_GAP = 8;

    /** The tab index that is not a grid of settings but a screen of its own. */
    private static final int ROUTES_TAB = 4;

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
        Chrome.tabs(width, activeTab, this::openTab, this::addRenderableWidget);

        int top = Chrome.CONTENT_TOP;
        int bottom = Chrome.contentBottom(height);
        int rows = Math.max(1, (bottom - top) / ROW_HEIGHT);
        int perPage = rows * COLUMNS;

        List<Supplier<AbstractWidget>> controls = tabs.get(activeTab).controls(this);
        int pages = Math.max(1, (controls.size() + perPage - 1) / perPage);
        page = Math.min(page, pages - 1);

        int columnWidth = (width - Chrome.MARGIN * 2 - COLUMN_GAP * (COLUMNS - 1)) / COLUMNS;
        int from = page * perPage;
        int to = Math.min(controls.size(), from + perPage);

        for (int i = from; i < to; i++) {
            int slot = i - from;
            int column = slot % COLUMNS;
            int row = slot / COLUMNS;

            AbstractWidget widget = controls.get(i).get();
            widget.setWidth(columnWidth);
            widget.setPosition(Chrome.MARGIN + column * (columnWidth + COLUMN_GAP),
                    top + 2 + row * ROW_HEIGHT);
            addRenderableWidget(widget);
        }

        int footerY = Chrome.footerY(height);
        if (pages > 1) {
            final int pageCount = pages;
            addRenderableWidget(Button.builder(Component.literal("<"), b -> {
                page = (page - 1 + pageCount) % pageCount;
                rebuildWidgets();
            }).bounds(Chrome.MARGIN, footerY, 20, 20).build());

            Button indicator = addRenderableWidget(Button.builder(
                            Component.literal((page + 1) + " / " + pages), b -> {
                            })
                    .bounds(Chrome.MARGIN + 22, footerY, 46, 20).build());
            indicator.active = false;

            addRenderableWidget(Button.builder(Component.literal(">"), b -> {
                page = (page + 1) % pageCount;
                rebuildWidgets();
            }).bounds(Chrome.MARGIN + 70, footerY, 20, 20).build());
        }

        addRenderableWidget(Button.builder(Component.literal("Done"), b -> onClose())
                .bounds(width - Chrome.MARGIN - 90, footerY, 90, 20).build());
    }

    /** Tabs four and under are grids here; the fifth is a screen of its own. */
    private void openTab(int index) {
        if (index == ROUTES_TAB) {
            minecraft.setScreen(new RoutesScreen(parent));
            return;
        }
        activeTab = index;
        page = 0;
        rebuildWidgets();
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
        Chrome.background(graphics, font, width, height, activeTab);
        super.extractRenderState(graphics, mouseX, mouseY, partial);

        String hint = "§8Hover a setting to see what it does. Changes save as you make them.";
        graphics.text(font, hint, Chrome.MARGIN, Chrome.contentBottom(height) + 4,
                Chrome.TEXT_DIM, false);
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
    public static Supplier<AbstractWidget> toggle(String label, String tooltip,
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
    public static Supplier<AbstractWidget> slider(String label, String tooltip,
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
    public static Supplier<AbstractWidget> action(String label, String tooltip, Runnable onPress) {
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
    public static Supplier<AbstractWidget> heading(String text) {
        return () -> {
            Button button = Button.builder(Component.literal("§e§l" + text), b -> {
                    })
                    .bounds(0, 0, 150, WIDGET_HEIGHT).build();
            button.active = false;
            return button;
        };
    }
}
