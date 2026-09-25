package com.birchmod.gui;

import java.text.DecimalFormat;
import java.util.function.DoubleConsumer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.network.chat.Component;

/**
 * A numeric setting, as bark with the filled part shown in sap green.
 *
 * Minecraft's slider is a groove with a loose handle in it, and the handle tells
 * you where you are but not how far along that is. A filled bar says both at a
 * glance, which is what these settings are usually being judged on — whether the
 * line width is near the thin end, not whether it is 3.5.
 *
 * <h2>Why the arithmetic is here</h2>
 * The vanilla slider works in a 0..1 fraction, which none of these settings are.
 * This holds the real range and the step so a slider for "trees ahead" lands on
 * whole numbers and one for line width lands on halves — dragging to 7.3183
 * trees would be worse than having no slider.
 */
public final class BarkSlider extends AbstractSliderButton {

    private static final DecimalFormat WHOLE = new DecimalFormat("#0");
    private static final DecimalFormat FRACTION = new DecimalFormat("#0.0");

    private final String label;
    private final double min;
    private final double max;
    private final double step;
    private final DoubleConsumer apply;
    private final int seed;

    public BarkSlider(String label, double min, double max, double step,
                      double current, DoubleConsumer apply) {
        super(0, 0, 150, BirchScreen.WIDGET_HEIGHT, Component.empty(),
                toFraction(current, min, max));
        this.label = label;
        this.min = min;
        this.max = max;
        this.step = step > 0 ? step : 1.0;
        this.apply = apply;
        this.seed = BirchSkin.seedFor(label);
        updateMessage();
    }

    private static double toFraction(double current, double min, double max) {
        if (max <= min) {
            return 0.0;
        }
        return Math.max(0.0, Math.min(1.0, (current - min) / (max - min)));
    }

    /** The real value behind the fraction, snapped to the step. */
    public double actual() {
        double raw = min + value * (max - min);
        double snapped = Math.round(raw / step) * step;
        return Math.max(min, Math.min(max, snapped));
    }

    /** Where the fill should reach, as a fraction, snapped like the value is. */
    private double filled() {
        if (max <= min) {
            return 0.0;
        }
        return Math.max(0.0, Math.min(1.0, (actual() - min) / (max - min)));
    }

    @Override
    protected void updateMessage() {
        // Called from the superclass constructor, before our fields are set, so
        // it has to survive a zero step and a null label.
        if (label == null) {
            setMessage(Component.empty());
            return;
        }
        double current = actual();
        String shown = step >= 1.0 ? WHOLE.format(current) : FRACTION.format(current);
        setMessage(Component.literal(label + ": " + shown));
    }

    @Override
    protected void applyValue() {
        apply.accept(actual());
    }

    @Override
    public void extractWidgetRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY,
                                         float partial) {
        BirchSkin.State state = !isActive()
                ? BirchSkin.State.DISABLED
                : isHoveredOrFocused() ? BirchSkin.State.HOVER : BirchSkin.State.IDLE;

        BirchSkin.bark(graphics, getX(), getY(), getWidth(), getHeight(), seed, state);
        fill(graphics, state);
        text(graphics, state);
        handleCursor(graphics);
    }

    /** The filled portion: sap rising up the trunk, left to right. */
    private void fill(GuiGraphicsExtractor graphics, BirchSkin.State state) {
        int inset = 2;
        int trackWidth = getWidth() - inset * 2;
        if (trackWidth <= 0) {
            return;
        }
        int width = (int) Math.round(trackWidth * filled());
        if (width <= 0) {
            return;
        }
        int top = getY() + getHeight() - 5;
        int bottom = getY() + getHeight() - inset;

        int colour = state == BirchSkin.State.DISABLED
                ? BirchSkin.withAlpha(BirchSkin.INK_SOFT, 0x90)
                : BirchSkin.withAlpha(BirchSkin.LEAF, 0xD0);
        graphics.fill(getX() + inset, top, getX() + inset + width, bottom, colour);

        // A notch at the head of the fill, so the exact position is readable
        // even when the bar is nearly full.
        if (state != BirchSkin.State.DISABLED) {
            int head = getX() + inset + width;
            graphics.fill(head - 1, getY() + 3, head, bottom, BirchSkin.LEAF_DEEP);
        }
    }

    private void text(GuiGraphicsExtractor graphics, BirchSkin.State state) {
        Font font = Minecraft.getInstance().font;
        int room = getWidth() - 10;
        if (room <= 0) {
            return;
        }
        String shown = BarkButton.fit(font, getMessage().getString(), room);
        int y = getY() + (getHeight() - font.lineHeight) / 2 - 1;
        graphics.text(font, shown, getX() + 5, y,
                state == BirchSkin.State.DISABLED ? BirchSkin.INK_SOFT : BirchSkin.INK, false);
    }
}
