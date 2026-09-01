package com.birchmod.gui;

import java.util.ArrayList;
import java.util.List;

import com.birchmod.BirchMod;
import com.birchmod.config.BirchConfig;
import com.birchmod.util.Notifier;

/** Regrowth timing, and what gets drawn on the trees themselves. */
final class TreesTab implements BirchScreen.Tab {

    @Override
    public String title() {
        return "Trees";
    }

    @Override
    public List<BirchScreen.Item> controls(BirchScreen screen) {
        BirchConfig c = BirchConfig.get();
        List<BirchScreen.Item> controls = new ArrayList<>();

        controls.add(BirchScreen.toggle("Regen timers", "Track how long trees take to come back.",
                () -> c.regenTimerEnabled, v -> c.regenTimerEnabled = v));
        controls.add(BirchScreen.toggle("Floating countdowns",
                "Show the time left above each regrowing tree.",
                () -> c.worldTimersEnabled, v -> c.worldTimersEnabled = v));
        controls.add(BirchScreen.toggle("Leftover logs",
                "Outline in red the logs left on a tree you chopped into.",
                () -> c.highlightLeftoverLogs, v -> c.highlightLeftoverLogs = v));
        controls.add(BirchScreen.slider("Timer range", "Hide countdowns beyond this many blocks.",
                4, 128, 4, () -> c.worldTimerRange, v -> c.worldTimerRange = v));
        controls.add(BirchScreen.slider("Assumed regen",
                "Used only until a real regrowth has been timed.",
                1, 300, 1, () -> c.defaultRegenSeconds, v -> c.defaultRegenSeconds = v));
        controls.add(BirchScreen.toggle("Safe mode",
                "Turn off all in-world drawing, keeping the HUD and tracking. "
                        + "The escape hatch if another mod's renderer disagrees with ours.",
                () -> c.safeMode, v -> c.safeMode = v));

        controls.add(BirchScreen.section("Calibration"));

        controls.add(BirchScreen.action("Recalibrate regen",
                "Forget what has been measured and start timing again.",
                () -> {
                    if (BirchMod.regenTracker != null) {
                        BirchMod.regenTracker.reset();
                    }
                    Notifier.actionBar("§eRegen calibration cleared");
                }));
        controls.add(BirchScreen.action("Reset session",
                "Clear this session's counters. Lifetime totals are kept.",
                () -> {
                    BirchMod.resetSession();
                    Notifier.actionBar("§eSession reset");
                }));

        return controls;
    }
}
