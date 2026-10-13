package com.birchmod.input;

import com.birchmod.compat.ClientGui;
import com.birchmod.config.BirchConfig;
import com.birchmod.util.Notifier;

import com.mojang.blaze3d.platform.InputConstants;

import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;

import org.lwjgl.glfw.GLFW;

/**
 * Keyboard shortcuts, all rebindable from vanilla Controls.
 *
 * Defaults are unbound-adjacent on purpose: they sit on keys Skyblock players
 * rarely use, and can be cleared entirely in Controls.
 */
public final class Keybinds {

    private static final KeyMapping.Category CATEGORY =
            KeyMapping.Category.register(Identifier.fromNamespaceAndPath("birchoptimizer", "main"));

    private static KeyMapping toggleHud;
    private static KeyMapping toggleTimers;
    private static KeyMapping resetSession;
    private static KeyMapping openSettings;
    private static KeyMapping openStats;

    private Keybinds() {
    }

    public static void register() {
        toggleHud = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.birchoptimizer.toggle_hud", GLFW.GLFW_KEY_B, CATEGORY));
        toggleTimers = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.birchoptimizer.toggle_timers", GLFW.GLFW_KEY_N, CATEGORY));
        resetSession = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.birchoptimizer.reset_session", InputConstants.Type.KEYSYM,
                InputConstants.UNKNOWN.getValue(), CATEGORY));
        // Unbound by default: a settings screen is worth a key, but not worth
        // taking one somebody already uses.
        openSettings = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.birchoptimizer.open_settings", InputConstants.Type.KEYSYM,
                InputConstants.UNKNOWN.getValue(), CATEGORY));
        // Also unbound. Worth its own key rather than two clicks through the
        // settings, because it is the screen you check mid-run — "how much
        // longer" is a question you ask while standing in the grove.
        openStats = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.birchoptimizer.open_stats", InputConstants.Type.KEYSYM,
                InputConstants.UNKNOWN.getValue(), CATEGORY));
    }

    /** Drain queued presses. Call once per client tick. */
    public static void tick(Minecraft client, Runnable onResetSession) {
        if (client == null) {
            return;
        }
        BirchConfig config = BirchConfig.get();

        // Throttled, not synchronous: the synchronous save fsyncs and then
        // atomically replaces the file, and this runs on the thread drawing
        // the frame. It coalesces instead, writes off-thread, and is flushed
        // when a screen closes and again on shutdown — so the toggle still
        // survives the session, without a disk stall under the keypress.
        while (toggleHud != null && toggleHud.consumeClick()) {
            config.hudEnabled = !config.hudEnabled;
            BirchConfig.saveThrottled();
            Notifier.actionBar(config.hudEnabled ? "§aBirch HUD on" : "§cBirch HUD off");
        }

        while (toggleTimers != null && toggleTimers.consumeClick()) {
            config.worldTimersEnabled = !config.worldTimersEnabled;
            BirchConfig.saveThrottled();
            Notifier.actionBar(config.worldTimersEnabled
                    ? "§aTree timers on" : "§cTree timers off");
        }

        while (openSettings != null && openSettings.consumeClick()) {
            ClientGui.setScreen(client, new com.birchmod.gui.BirchScreen(null));
        }

        while (openStats != null && openStats.consumeClick()) {
            ClientGui.setScreen(client, new com.birchmod.gui.StatsScreen(null));
        }

        while (resetSession != null && resetSession.consumeClick()) {
            onResetSession.run();
            Notifier.actionBar("§eBirch session reset");
        }
    }
}
