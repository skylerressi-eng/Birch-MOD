package com.birchmod.util;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Fail-safe wrapper around the mod's entry points.
 *
 * A client mod runs inside someone else's render and tick loops, alongside
 * whatever else they have installed. An unhandled exception thrown from a
 * render callback takes the whole game down, which is never an acceptable
 * outcome for an overlay that shows tree timers.
 *
 * Every entry point runs through here: the first failure is logged with a full
 * stack trace, repeated failures disable that one feature, and the rest of the
 * mod carries on. A crash in the tracer renderer must not cost you the HUD, and
 * must never cost you the game.
 */
public final class Guard {

    private static final Logger LOGGER = LoggerFactory.getLogger("BirchOptimizer");

    /** Failures tolerated before a feature is switched off for the session. */
    private static final int MAX_FAILURES = 5;

    private static final Map<String, Integer> failures = new ConcurrentHashMap<>();
    private static final Set<String> disabled = ConcurrentHashMap.newKeySet();

    private Guard() {
    }

    /**
     * Run {@code action}, swallowing any {@link Throwable} it raises.
     *
     * @param feature stable name used for reporting and for disabling
     */
    public static void run(String feature, Runnable action) {
        attempt(feature, action);
    }

    /**
     * As {@link #run}, but says whether the action got through.
     *
     * Most callers have nothing useful to do about a failure — a tick that did
     * not happen is simply a tick that did not happen. A screen is different:
     * if the thing that failed was building it, the player is left looking at
     * an empty window with no buttons on it, including the one that closes it.
     *
     * @return true if the action ran without throwing
     */
    public static boolean attempt(String feature, Runnable action) {
        if (disabled.contains(feature)) {
            return false;
        }
        try {
            action.run();
            return true;
        } catch (Throwable t) {
            recordFailure(feature, t);
            return false;
        }
    }

    private static void recordFailure(String feature, Throwable t) {
        int count = failures.merge(feature, 1, Integer::sum);

        if (count == 1) {
            LOGGER.error("[{}] threw; the feature is suppressed for now and will be "
                    + "disabled after {} failures. Please report this trace.", feature, MAX_FAILURES, t);
        }

        if (count >= MAX_FAILURES && disabled.add(feature)) {
            LOGGER.error("[{}] disabled for this session after {} failures.", feature, count);
            // Best effort only — never let reporting a failure cause another.
            try {
                Notifier.chat("§cBirch Optimizer disabled §f" + feature
                        + "§c after repeated errors. Everything else still works.");
            } catch (Throwable ignored) {
                // Nothing sensible left to do.
            }
        }
    }

    public static boolean isDisabled(String feature) {
        return disabled.contains(feature);
    }

    public static Set<String> getDisabled() {
        return Set.copyOf(disabled);
    }

    public static int getFailureCount(String feature) {
        return failures.getOrDefault(feature, 0);
    }

    /** Re-enable everything, e.g. after the user changes a setting. */
    public static void reset() {
        disabled.clear();
        failures.clear();
    }
}
