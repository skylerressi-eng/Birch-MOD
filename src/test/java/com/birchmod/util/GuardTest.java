package com.birchmod.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The thing standing between a bug in this mod and somebody's crash report.
 *
 * <h2>Why the names matter more than the catching</h2>
 * The catching is the easy half and it was never wrong. The half that was wrong
 * is what a failure is attributed to. A name is switched off for the rest of the
 * session after five failures, so a name shared between unrelated things takes
 * all of them down together — and the mod was guarding every screen as
 * {@code "screen"} and every button as {@code "action"}. Five failures spread
 * across five unrelated buttons therefore disabled every button in the mod,
 * including every one that was working, and the player was told the mod had
 * disabled "action".
 *
 * That is not a catching bug, it is a naming bug, and it is invisible to a test
 * that only checks that exceptions do not escape. These check the isolation.
 */
class GuardTest {

    @BeforeEach
    void clean() {
        Guard.reset();
    }

    private static void boom() {
        throw new IllegalStateException("as a renderer would");
    }

    @Test
    @DisplayName("an exception does not escape, and is reported as not having run")
    void swallowsAndReports() {
        assertFalse(Guard.attempt("render", GuardTest::boom));
        assertTrue(Guard.attempt("render", () -> { }), "the next attempt still runs");
    }

    @Test
    @DisplayName("a feature is switched off only after repeated failure")
    void disablesAfterRepeats() {
        // Not on the first: one bad frame is a bad frame, and a mod that
        // switches a feature off the first time a chunk was mid-load would
        // switch most of itself off on any busy login.
        for (int i = 0; i < 4; i++) {
            Guard.attempt("render", GuardTest::boom);
            assertFalse(Guard.isDisabled("render"), "still trying after " + (i + 1));
        }
        Guard.attempt("render", GuardTest::boom);
        assertTrue(Guard.isDisabled("render"), "off after five");
        assertEquals(5, Guard.getFailureCount("render"));
    }

    @Test
    @DisplayName("a disabled feature stops being run at all")
    void disabledMeansNotRun() {
        AtomicInteger ran = new AtomicInteger();
        for (int i = 0; i < 5; i++) {
            Guard.attempt("render", GuardTest::boom);
        }
        assertFalse(Guard.attempt("render", ran::incrementAndGet));
        assertEquals(0, ran.get(), "a feature that is off is not called, not called and caught");
    }

    @Test
    @DisplayName("one broken feature never takes a working one with it")
    void failuresDoNotSpread() {
        // The whole point. Five failures in the tracer must not cost the HUD,
        // and five failures spread across five different buttons must not cost
        // the sixth.
        for (int i = 0; i < 20; i++) {
            Guard.attempt("tracer", GuardTest::boom);
        }
        assertTrue(Guard.isDisabled("tracer"));

        AtomicInteger hudRan = new AtomicInteger();
        assertTrue(Guard.attempt("hud", hudRan::incrementAndGet));
        assertEquals(1, hudRan.get());
        assertFalse(Guard.isDisabled("hud"));
        assertEquals(0, Guard.getFailureCount("hud"));
    }

    @Test
    @DisplayName("five failures across five names disable none of them")
    void oneEachIsNotFiveOfOne() {
        // This is exactly the shape of the bug: the count has to be per name,
        // or a handful of unrelated hiccups reads as one feature being broken.
        for (String button : new String[]{"Follow", "Export", "Copy", "Delete", "Import"}) {
            Guard.attempt(button, GuardTest::boom);
        }
        for (String button : new String[]{"Follow", "Export", "Copy", "Delete", "Import"}) {
            assertFalse(Guard.isDisabled(button), button + " should still work");
        }
        assertTrue(Guard.getDisabled().isEmpty());
    }

    @Test
    @DisplayName("resetting gives everything another go")
    void resetReenables() {
        for (int i = 0; i < 5; i++) {
            Guard.attempt("render", GuardTest::boom);
        }
        assertTrue(Guard.isDisabled("render"));

        Guard.reset();
        assertFalse(Guard.isDisabled("render"));
        assertEquals(0, Guard.getFailureCount("render"));

        AtomicInteger ran = new AtomicInteger();
        assertTrue(Guard.attempt("render", ran::incrementAndGet));
        assertEquals(1, ran.get());
    }

    @Test
    @DisplayName("an Error is caught too, not only an Exception")
    void catchesThrowable() {
        // A missing method on a changed Minecraft class arrives as a
        // NoSuchMethodError, not an exception — and that is the failure mode a
        // mod built against one game version actually has.
        assertFalse(Guard.attempt("render", () -> {
            throw new NoSuchMethodError("Gui.setOverlayMessage");
        }));
        assertEquals(1, Guard.getFailureCount("render"));
    }
}
