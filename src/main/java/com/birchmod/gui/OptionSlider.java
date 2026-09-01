package com.birchmod.gui;

import java.text.DecimalFormat;
import java.util.function.DoubleConsumer;

import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.network.chat.Component;

/**
 * A slider over a numeric setting.
 *
 * Minecraft's slider works in a 0..1 fraction, which is not what any of these
 * settings are. This holds the real range and the step, so a slider for "trees
 * ahead" lands on whole numbers and one for line width lands on halves —
 * dragging to 7.3183 trees would be worse than no slider at all.
 */
public final class OptionSlider extends AbstractSliderButton {

    private static final DecimalFormat WHOLE = new DecimalFormat("#0");
    private static final DecimalFormat FRACTION = new DecimalFormat("#0.0");

    private final String label;
    private final double min;
    private final double max;
    private final double step;
    private final DoubleConsumer apply;

    public OptionSlider(String label, double min, double max, double step,
                        double current, DoubleConsumer apply) {
        super(0, 0, 150, 20, Component.empty(), toFraction(current, min, max));
        this.label = label;
        this.min = min;
        this.max = max;
        this.step = step > 0 ? step : 1.0;
        this.apply = apply;
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

    @Override
    protected void updateMessage() {
        double current = actual();
        String shown = step >= 1.0 ? WHOLE.format(current) : FRACTION.format(current);
        setMessage(Component.literal(label + ": §f" + shown));
    }

    @Override
    protected void applyValue() {
        apply.accept(actual());
    }
}
