package com.birchmod.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

/**
 * A button cut from birch bark.
 *
 * Extends the vanilla {@link Button} so every bit of its behaviour is kept —
 * pressing, keyboard activation, tooltips, narration, the click sound — and
 * replaces only what it looks like. {@code AbstractButton} seals the outer
 * render method but calls {@code extractContents} from it and nothing else, and
 * the vanilla sprite is drawn by that method in each concrete subclass rather
 * than above it, so overriding {@code extractContents} is a clean swap: no
 * vanilla texture underneath to hide and no behaviour reimplemented badly.
 *
 * <h2>The label</h2>
 * Dark ink on pale bark, with no text shadow. A shadow exists to lift light
 * text off a dark background; on cream bark it only smudges, and bark is the
 * one place in Minecraft's UI where the usual white-on-grey assumption is
 * upside down.
 */
public class BarkButton extends Button {

    /** Ink needs a little air, or letters sit on the grain. */
    private static final int LABEL_INSET = 4;

    /** Fixed per button, so its grain is its own and never moves. */
    protected final int seed;

    /** Drawn in the corner when set — a mark rather than a word. */
    private String badge = null;
    private int badgeColour = BirchSkin.LEAF_DEEP;

    /**
     * Whether this button destroys something.
     *
     * Said with the surface rather than with the text. A red label on cream
     * bark is a small red thing among a column of dark labels and is easy to
     * slide past; bark stained through with rot is not, and it leaves the label
     * free to say what the button does instead of spending its colour on a
     * warning.
     */
    private boolean danger = false;

    public BarkButton(int x, int y, int width, int height,
                      Component message, OnPress onPress) {
        super(x, y, width, height, message, onPress, DEFAULT_NARRATION);
        this.seed = BirchSkin.seedFor(message.getString());
    }

    /** A small corner mark, e.g. a star on the default route. */
    public BarkButton badge(String mark, int colour) {
        this.badge = mark;
        this.badgeColour = colour;
        return this;
    }

    /** Mark this button as destructive; see {@link #danger}. */
    public BarkButton danger(boolean value) {
        this.danger = value;
        return this;
    }

    /** What this button is currently saying about itself. */
    protected BirchSkin.State state() {
        if (!isActive()) {
            return BirchSkin.State.DISABLED;
        }
        return isHoveredOrFocused() ? BirchSkin.State.HOVER : BirchSkin.State.IDLE;
    }

    @Override
    protected void extractContents(GuiGraphicsExtractor graphics, int mouseX, int mouseY,
                                   float partial) {
        BirchSkin.State state = state();
        BirchSkin.bark(graphics, getX(), getY(), getWidth(), getHeight(), seed, state);
        if (danger && state != BirchSkin.State.DISABLED) {
            stain(graphics, state);
        }
        label(graphics, state);
    }

    /** A wash of rot over the bark, stronger while the mouse is on it. */
    private void stain(GuiGraphicsExtractor graphics, BirchSkin.State state) {
        int alpha = state == BirchSkin.State.HOVER ? 0x58 : 0x30;
        graphics.fill(getX() + 1, getY() + 1, getX() + getWidth() - 1,
                getY() + getHeight() - 1, BirchSkin.withAlpha(0x00A34A32, alpha));
        if (state == BirchSkin.State.HOVER) {
            BirchSkin.outline(graphics, getX(), getY(), getWidth(), getHeight(),
                    BirchSkin.withAlpha(0x00C05540, 0xC0));
        }
    }

    /** The label, centred, trimmed to fit rather than spilling over the edge. */
    protected void label(GuiGraphicsExtractor graphics, BirchSkin.State state) {
        Font font = Minecraft.getInstance().font;
        String text = getMessage().getString();
        int room = getWidth() - LABEL_INSET * 2;
        if (room <= 0) {
            return;
        }

        String shown = fit(font, text, room);
        int x = getX() + (getWidth() - font.width(shown)) / 2;
        int y = getY() + (getHeight() - font.lineHeight) / 2 + 1;

        graphics.text(font, shown, x, y, inkFor(state), false);

        if (badge != null) {
            graphics.text(font, badge, getX() + 3, getY() + 2, badgeColour, false);
        }
    }

    /** Ink colour for a state. Disabled bark takes pale ink, not dark. */
    protected static int inkFor(BirchSkin.State state) {
        return state == BirchSkin.State.DISABLED ? BirchSkin.INK_SOFT : BirchSkin.INK;
    }

    /**
     * Shorten text with an ellipsis until it fits.
     *
     * Vanilla lets a long label run past both edges of a button. On a bark
     * surface that reads as a mistake rather than as overflow, because there is
     * no flat grey to run out onto.
     */
    public static String fit(Font font, String text, int room) {
        if (text == null) {
            return "";
        }
        if (font.width(text) <= room) {
            return text;
        }
        String ellipsis = "…";
        int budget = room - font.width(ellipsis);
        if (budget <= 0) {
            return "";
        }
        int end = text.length();
        while (end > 0 && font.width(text.substring(0, end)) > budget) {
            end--;
        }
        return text.substring(0, end) + ellipsis;
    }
}
