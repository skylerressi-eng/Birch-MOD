package com.birchmod.tracking;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Birch per hour: what it counts, and what it divides by.
 *
 * This number has been wrong twice for entirely different reasons. It divided
 * by how long ago you logged in rather than by time spent foraging, so the same
 * half hour of chopping reported 10,000/hr on a fresh login and 5,000/hr three
 * hours in. And Enchanted Birch Wood counted as one log instead of the 160 it
 * holds, because the item-id check answered "yes, birch" and returned before
 * the name was ever read.
 */
class BirchRateTest {

    private static final long HOUR = 3_600_000L;
    /** Four times a second, as the tracker really samples. */
    private static final long SAMPLE = 250L;

    /** Chop at a steady rate for a stretch, sampling as the client does. */
    private static long chop(BirchTracker.RateWindow window, long from,
                             long durationMs, double perHour) {
        double perSample = perHour / HOUR * SAMPLE;
        double owed = 0.0;
        long now = from;
        for (long t = 0; t < durationMs; t += SAMPLE) {
            now = from + t + SAMPLE;
            owed += perSample;
            int give = (int) owed;
            owed -= give;
            window.sample(now, give);
        }
        return now;
    }

    /** Stand still, still sampling — the client does not stop ticking. */
    private static long idle(BirchTracker.RateWindow window, long from, long durationMs) {
        long now = from;
        for (long t = 0; t < durationMs; t += SAMPLE) {
            now = from + t + SAMPLE;
            window.sample(now, 0);
        }
        return now;
    }

    @Test
    @DisplayName("a steady rate is reported as it is")
    void steadyRate() {
        BirchTracker.RateWindow w = new BirchTracker.RateWindow();
        long now = chop(w, 1_000_000L, 10 * 60_000L, 12_000);
        assertEquals(12_000.0, w.perHour(now), 200.0);
        assertEquals(2000L, w.collected(), 5L);
    }

    @Test
    @DisplayName("the answer does not depend on when you logged in")
    void independentOfSessionAge() {
        // Fresh session: half an hour at a true 10,000/hr.
        BirchTracker.RateWindow fresh = new BirchTracker.RateWindow();
        long a = chop(fresh, 0L, 30 * 60_000L, 10_000);

        // A session already open a while, then the identical half hour.
        BirchTracker.RateWindow old = new BirchTracker.RateWindow();
        long b = idle(old, 0L, 5 * 60_000L);
        b = chop(old, b, 30 * 60_000L, 10_000);

        assertEquals(10_000.0, fresh.perHour(a), 200.0);
        assertEquals(10_000.0, old.perHour(b), 200.0);
        assertTrue(Math.abs(fresh.perHour(a) - old.perHour(b)) < 100.0,
                "the same chopping must read the same either way");
    }

    @Test
    @DisplayName("time away from the trees does not dilute the rate")
    void idlingDoesNotErode() {
        BirchTracker.RateWindow w = new BirchTracker.RateWindow();
        long now = chop(w, 0L, 10 * 60_000L, 12_000);
        double before = w.perHour(now);

        now = idle(w, now, 15 * 60_000L);
        double after = w.perHour(now);

        assertEquals(12_000.0, before, 200.0);
        assertTrue(Math.abs(before - after) < 100.0,
                "stepping away changed it from " + before + " to " + after);
    }

    @Test
    @DisplayName("but the walk between trees counts, because it is foraging")
    void walkingCounts() {
        BirchTracker.RateWindow w = new BirchTracker.RateWindow();
        long now = 0L;
        // Ten seconds chopping, ten walking, repeated: half the time is spent
        // moving, so the rate is half what the chopping alone would say.
        for (int lap = 0; lap < 30; lap++) {
            now = chop(w, now, 10_000L, 24_000);
            now = idle(w, now, 10_000L);
        }
        assertEquals(12_000.0, w.perHour(now), 400.0);
    }

    @Test
    @DisplayName("idling before the first log banks nothing")
    void noTimeBankedBeforeTheFirstLog() {
        BirchTracker.RateWindow w = new BirchTracker.RateWindow();
        long now = idle(w, 0L, 20 * 60_000L);
        assertEquals(0L, w.activeMs(), "twenty idle minutes must not be credited");

        now = chop(w, now, 5 * 60_000L, 12_000);
        assertEquals(12_000.0, w.perHour(now), 300.0);
    }

    @Test
    @DisplayName("birch ages out of the rolling hour")
    void windowRollsOff() {
        BirchTracker.RateWindow w = new BirchTracker.RateWindow();
        long now = chop(w, 0L, 5 * 60_000L, 12_000);
        assertEquals(1000L, w.collected(), 5L);

        now = idle(w, now + HOUR, 2_000L);
        assertEquals(0L, w.collected(), "an hour later it has all aged out");
        assertEquals(0.0, w.perHour(now), "and the rate is zero, not a stale figure");
        assertEquals(0L, w.activeMs(), "the foraging clock ages out with the birch");
    }

    @Test
    @DisplayName("a two-second burst is not extrapolated into a wild figure")
    void earlySpikesAreTamed() {
        BirchTracker.RateWindow w = new BirchTracker.RateWindow();
        long now = chop(w, 0L, 2_000L, 12_000);
        double rate = w.perHour(now);
        assertTrue(rate > 0.0, "it should not be zero either");
        assertTrue(rate <= 12_100.0, "a burst read as " + rate);
    }

    @Test
    @DisplayName("Enchanted Birch Wood is worth the 160 logs it holds")
    void enchantedIsWorthWhatItHolds() {
        int e = BirchTracker.ENCHANTED_LOGS;

        assertEquals(1, BirchTracker.birchValue("birch log", 1, true));
        assertEquals(64, BirchTracker.birchValue("birch wood", 64, true));
        assertEquals(e, BirchTracker.birchValue("enchanted birch wood", 1, true));
        assertEquals(64 * e, BirchTracker.birchValue("enchanted birch wood", 64, true));
    }

    @Test
    @DisplayName("things that merely have birch in the name are not birch logs")
    void namedButNotBirch() {
        assertEquals(0, BirchTracker.birchValue("birch planks", 64, true));
        assertEquals(0, BirchTracker.birchValue("birch sapling", 16, false));
        assertEquals(0, BirchTracker.birchValue("birch axe", 1, false));
        assertEquals(0, BirchTracker.birchValue("oak log", 32, false));
    }

    @Test
    @DisplayName("an unnamed vanilla log still counts")
    void vanillaFallback() {
        assertEquals(12, BirchTracker.birchValue(null, 12, true));
        assertEquals(0, BirchTracker.birchValue(null, 12, false));
    }

    @Test
    @DisplayName("compacting moves the count by nothing at all")
    void compactingIsInvisible() {
        int e = BirchTracker.ENCHANTED_LOGS;
        int before = BirchTracker.birchValue("birch wood", e, true);
        int after = BirchTracker.birchValue("enchanted birch wood", 1, true);

        assertEquals(before, after, "160 raw logs and one enchanted are the same birch");
        assertEquals(0, BirchTracker.gainFrom(before, after), "so it registers as no gain");
        assertEquals(12, BirchTracker.gainFrom(after, after + 12),
                "and the next logs after a compact count in full");
    }

    @Test
    @DisplayName("what counts as a gain, and what is refused")
    void gainRules() {
        assertEquals(6, BirchTracker.gainFrom(100, 106), "ordinary chopping");
        assertEquals(0, BirchTracker.gainFrom(403, 3), "birch leaving adds nothing");
        assertEquals(0, BirchTracker.gainFrom(10, 10_000), "an impossible jump");
        assertEquals(0, BirchTracker.gainFrom(0, 200), "a stack out of an empty inventory");
        assertEquals(6, BirchTracker.gainFrom(0, 6), "a few logs into an empty one is fine");
        // The withdrawn guard: birch leaving must not create a debt that the
        // next real haul has to pay off. Compacting, sacks, dropping and
        // selling all remove birch and none of them mean the next logs are
        // somebody else's.
        assertEquals(7, BirchTracker.gainFrom(3, 10),
                "chopping right after birch left still counts in full");
    }
}
