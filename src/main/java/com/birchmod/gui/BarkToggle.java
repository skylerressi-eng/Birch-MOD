package com.birchmod.gui;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;

/**
 * An on/off setting, as a slip of bark with a carved switch on the right.
 *
 * Replaces {@code CycleButton}, which draws "Label: ON" as one run of text and
 * says which state it is in with a word you have to read. The state here is a
 * shape and a colour at a fixed place on every row, so a column of twenty
 * settings can be taken in without reading any of them: green pips on the right
 * are on, dark pips are off.
 *
 * It reads its value through a supplier rather than holding one, so the switch
 * cannot drift out of step with the setting it controls — which is what happens
 * when a screen is rebuilt while a stale copy of the value is still on a widget.
 */
public final class BarkToggle extends BarkButton {

    /** The switch, and the gap it keeps from the right edge. */
    private static final int SWITCH_WIDTH = 22;
    private static final int SWITCH_INSET = 4;

    private final BooleanSupplier get;

    public BarkToggle(String label, BooleanSupplier get, Consumer<Boolean> set) {
        super(0, 0, 150, BirchScreen.WIDGET_HEIGHT, Component.literal(label),
                button -> Chrome.guard("toggle", () -> set.accept(!get.getAsBoolean())));
        this.get = get;
    }

    @Override
    protected void extractContents(GuiGraphicsExtractor graphics, int mouseX, int mouseY,
                                   float partial) {
        BirchSkin.State state = state();
        BirchSkin.bark(graphics, getX(), getY(), getWidth(), getHeight(), seed, state);

        boolean on = get.getAsBoolean();
        switchTrack(graphics, on, state);
        labelLeft(graphics, state);
    }

    /** The carved track and its pip, right-aligned on every row. */
    private void switchTrack(GuiGraphicsExtractor graphics, boolean on, BirchSkin.State state) {
        int h = Math.max(6, getHeight() - 10);
        int x = getX() + getWidth() - SWITCH_WIDTH - SWITCH_INSET;
        int y = getY() + (getHeight() - h) / 2;
        if (x <= getX()) {
            return;   // too narrow for a switch; the label alone will do
        }

        // A groove cut into the bark, so the pip sits in something.
        graphics.fill(x, y, x + SWITCH_WIDTH, y + h, 0x50000000);
        BirchSkin.outline(graphics, x, y, SWITCH_WIDTH, h, 0x40000000);

        int pip = SWITCH_WIDTH / 2 - 1;
        int pipX = on ? x + SWITCH_WIDTH - pip - 2 : x + 2;
        int colour = state == BirchSkin.State.DISABLED
                ? BirchSkin.withAlpha(BirchSkin.INK_SOFT, 0xB0)
                : on ? BirchSkin.LEAF : BirchSkin.withAlpha(BirchSkin.INK, 0xC0);

        graphics.fill(pipX, y + 2, pipX + pip, y + h - 2, colour);
        if (on && state != BirchSkin.State.DISABLED) {
            // A sliver of deeper green, so "on" has some weight to it.
            graphics.fill(pipX, y + h - 3, pipX + pip, y + h - 2, BirchSkin.LEAF_DEEP);
        }
    }

    /** Left-aligned label, kept clear of the switch. */
    private void labelLeft(GuiGraphicsExtractor graphics, BirchSkin.State state) {
        Font font = Minecraft.getInstance().font;
        int room = getWidth() - SWITCH_WIDTH - SWITCH_INSET - 10;
        if (room <= 0) {
            return;
        }
        String shown = fit(font, getMessage().getString(), room);
        int y = getY() + (getHeight() - font.lineHeight) / 2 + 1;
        graphics.text(font, shown, getX() + 5, y, inkFor(state), false);
    }
}
