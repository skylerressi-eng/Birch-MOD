package com.birchmod.gui;

import java.util.ArrayList;
import java.util.List;

import com.birchmod.config.BirchConfig;

/** When the mod is allowed to say something. */
final class AlertsTab implements BirchScreen.Tab {

    @Override
    public String title() {
        return "Alerts";
    }

    @Override
    public List<BirchScreen.Item> controls(BirchScreen screen) {
        BirchConfig c = BirchConfig.get();
        List<BirchScreen.Item> controls = new ArrayList<>();

        controls.add(BirchScreen.toggle("Tree ready", "Say something when trees come back.",
                () -> c.notifyOnReady, v -> c.notifyOnReady = v));
        controls.add(BirchScreen.toggle("Sound", "Play a note with those alerts.",
                () -> c.notifySound, v -> c.notifySound = v));
        controls.add(BirchScreen.slider("Volume", "How loud that note is.",
                0.0, 1.0, 0.1, () -> c.notifyVolume, v -> c.notifyVolume = v));
        controls.add(BirchScreen.slider("Quiet time",
                "Seconds between alerts, so a grove coming back at once cannot spam you.",
                0, 60, 1, () -> c.notifyCooldownSeconds, v -> c.notifyCooldownSeconds = v));
        controls.add(BirchScreen.toggle("Left behind",
                "A nudge when you walk away from a trunk you did not finish. "
                        + "Each trunk is mentioned once.",
                () -> c.notifyLeftovers, v -> c.notifyLeftovers = v));

        return controls;
    }
}
