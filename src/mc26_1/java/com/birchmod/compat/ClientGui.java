package com.birchmod.compat;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * The handful of client calls Minecraft renamed between 26.1 and 26.2.
 *
 * <h2>Why this exists</h2>
 * This mod has been moved between the two versions three times, and each move
 * was the same edit made by hand in nine places — the screen accessor, the
 * overlay message, the chat line, the HUD's hidden flag. Nine mechanical edits
 * is nine chances to miss one, and a missed one does not fail to compile on the
 * version you are testing; it fails on the one you are not. Worse, it meant the
 * two lines of source drifted apart, so a fix made on one was simply absent
 * from the other.
 *
 * So the renames live here, once, and the rest of the mod — every screen,
 * command, keybind and tracker — calls this instead. Switching versions is then
 * a line in {@code gradle.properties}, and the thing it switches is this file
 * plus the three renderers, which differ too much to paper over.
 *
 * <h2>This is the 26.1 copy</h2>
 * Its twin under {@code src/mc26_2} has the same methods against the newer
 * names. Exactly one of the two is compiled, chosen by the build from the
 * target version.
 *
 * <h2>Nulls</h2>
 * Handled here rather than at each call site, because <em>which</em> field to
 * check for null is itself one of the things that moved. A caller should not
 * have to know that 26.1 reaches chat through {@code gui} and 26.2 through
 * {@code gui.hud} in order to guard against it.
 */
public final class ClientGui {

    private ClientGui() {
    }

    /** The screen currently open, or null if the game is not showing one. */
    public static Screen screen(Minecraft client) {
        return client == null ? null : client.screen;
    }

    /** Open a screen, or close the current one by passing null. */
    public static void setScreen(Minecraft client, Screen screen) {
        if (client != null) {
            client.setScreen(screen);
        }
    }

    /** Whether the player has hidden the HUD (F1). */
    public static boolean hudHidden(Minecraft client) {
        return client != null && client.options != null && client.options.hideGui;
    }

    /** Put a message on the action bar, above the hotbar. */
    public static void overlay(Minecraft client, Component text) {
        if (client == null || client.gui == null) {
            return;
        }
        client.gui.setOverlayMessage(text, false);
    }

    /** Add a line to the player's own chat, not sent to the server. */
    public static void chat(Minecraft client, Component text) {
        if (client == null || client.gui == null) {
            return;
        }
        client.gui.getChat().addClientSystemMessage(text);
    }
}
