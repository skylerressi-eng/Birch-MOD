package com.birchmod.util;

import com.birchmod.config.BirchConfig;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;

/**
 * Action-bar and sound alerts, rate-limited so a grove of trees regenerating at
 * once cannot spam the player.
 */
public final class Notifier {

    private static long lastAlertAt = 0L;

    private Notifier() {
    }

    /**
     * Announce that a tree is ready, respecting the configured cooldown.
     *
     * @return true if the alert was actually shown
     */
    public static boolean treeReady(int readyCount) {
        BirchConfig config = BirchConfig.get();
        if (!config.notifyOnReady) {
            return false;
        }

        long now = System.currentTimeMillis();
        long cooldownMs = (long) (config.notifyCooldownSeconds * 1000.0);
        if (now - lastAlertAt < cooldownMs) {
            return false;
        }
        lastAlertAt = now;

        String message = readyCount > 1
                ? "§a§l" + readyCount + " trees ready"
                : "§a§lTree ready";
        actionBar(message);
        ping();
        return true;
    }

    /** Show a message on the action bar (above the hotbar). */
    public static void actionBar(String message) {
        Minecraft client = Minecraft.getInstance();
        if (client == null || client.gui == null) {
            return;
        }
        client.gui.setOverlayMessage(Component.literal(message), false);
    }

    /** Send a message to chat, prefixed with the mod name. */
    public static void chat(String message) {
        Minecraft client = Minecraft.getInstance();
        if (client == null || client.gui == null) {
            return;
        }
        client.gui.getChat().addClientSystemMessage(
                Component.literal("§6[Birch] §r" + message));
    }

    /** Short audible ping, if sounds are enabled. */
    public static void ping() {
        BirchConfig config = BirchConfig.get();
        if (!config.notifySound) {
            return;
        }
        Minecraft client = Minecraft.getInstance();
        if (client == null || client.player == null) {
            return;
        }
        client.player.playSound(
                SoundEvents.NOTE_BLOCK_PLING.value(),
                (float) config.notifyVolume,
                1.6f);
    }

    public static void reset() {
        lastAlertAt = 0L;
    }
}
