package com.birchmod.input;

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
    }

    /** Drain queued presses. Call once per client tick. */
    public static void tick(Minecraft client, Runnable onResetSession) {
        if (client == null) {
            return;
        }
        BirchConfig config = BirchConfig.get();

        while (toggleHud != null && toggleHud.consumeClick()) {
            config.hudEnabled = !config.hudEnabled;
            BirchConfig.save();
            Notifier.actionBar(config.hudEnabled ? "§aBirch HUD on" : "§cBirch HUD off");
        }

        while (toggleTimers != null && toggleTimers.consumeClick()) {
            config.worldTimersEnabled = !config.worldTimersEnabled;
            BirchConfig.save();
            Notifier.actionBar(config.worldTimersEnabled
                    ? "§aTree timers on" : "§cTree timers off");
        }

        while (resetSession != null && resetSession.consumeClick()) {
            onResetSession.run();
            Notifier.actionBar("§eBirch session reset");
        }
    }
}
