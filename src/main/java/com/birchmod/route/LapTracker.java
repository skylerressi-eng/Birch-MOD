package com.birchmod.route;

/**
 * Times how long it actually takes you to get round your route once.
 *
 * <h2>Why this and not the predicted lap</h2>
 * Everything else in the mod tells you what a route <em>should</em> do. This is
 * the only number that says what it did. A compiled route promising nine trees
 * a minute is a claim; a lap that came in at seventy seconds against a best of
 * sixty-two is a result, and the gap between the two is the thing worth acting
 * on — it is usually a stop you keep arriving at early, or one you keep
 * half-chopping and coming back for.
 *
 * <h2>What counts as a lap</h2>
 * As many confirmed fells as the route has stops. Not "returned to stop one",
 * which sounds more precise and is worse: stops get stepped over while they
 * regrow, so position round the loop is a poor clock, whereas felling is the
 * work itself. Counting fells means a lap is a lap however you got round it.
 *
 * A lap that stalls — you stopped to sell, or read chat, or logged off — is
 * abandoned rather than recorded, because a lap time that includes a trip to
 * the Bazaar is worse than no lap time at all.
 */
public final class LapTracker {

    /** Longer than this between fells and you were not foraging. */
    private static final long STALL_MS = 120_000L;

    /** Ignore anything absurd rather than letting it become your best lap. */
    private static final double MIN_PLAUSIBLE_LAP_SECONDS = 3.0;
    private static final double MAX_PLAUSIBLE_LAP_SECONDS = 3_600.0;

    private String routeName = null;
    private int routeSize = 0;

    private long lapStartedAt = 0L;
    private long lastChopAt = 0L;
    private int fellThisLap = 0;

    // Session figures. The best is also kept per route, on the route itself.
    private double lastLapSeconds = -1.0;
    private double totalLapSeconds = 0.0;
    private int lapsCompleted = 0;
    private int abandoned = 0;

    /**
     * A tree was confirmed felled.
     *
     * @return the lap time in seconds if this fell completed a lap, else -1
     */
    public synchronized double onTreeChopped() {
        RecordedRoute active = RouteLibrary.getActive();
        if (active == null || active.size() < RouteLibrary.MIN_STOPS) {
            // Not following anything, so there is no lap to be part of.
            reset();
            return -1.0;
        }

        long now = System.currentTimeMillis();

        boolean switchedRoute = !active.name.equals(routeName) || active.size() != routeSize;
        boolean stalled = lapStartedAt != 0L && (now - lastChopAt) > STALL_MS;

        if (stalled) {
            abandoned++;
        }
        if (switchedRoute || stalled || lapStartedAt == 0L) {
            routeName = active.name;
            routeSize = active.size();
            lapStartedAt = now;
            lastChopAt = now;
            fellThisLap = 0;
            return -1.0;
        }

        lastChopAt = now;
        fellThisLap++;

        if (fellThisLap < routeSize) {
            return -1.0;
        }

        double seconds = (now - lapStartedAt) / 1000.0;
        lapStartedAt = now;
        fellThisLap = 0;

        if (seconds < MIN_PLAUSIBLE_LAP_SECONDS || seconds > MAX_PLAUSIBLE_LAP_SECONDS) {
            return -1.0;
        }

        lastLapSeconds = seconds;
        totalLapSeconds += seconds;
        lapsCompleted++;

        // The best lap belongs to the route, not the session — it is the number
        // you are trying to beat next time you load in.
        if (active.bestLapSeconds <= 0.0 || seconds < active.bestLapSeconds) {
            active.bestLapSeconds = seconds;
            RouteLibrary.persist();
        }
        return seconds;
    }

    public synchronized void reset() {
        routeName = null;
        routeSize = 0;
        lapStartedAt = 0L;
        lastChopAt = 0L;
        fellThisLap = 0;
    }

    /** Forget the session figures as well as the current lap. */
    public synchronized void resetSession() {
        reset();
        lastLapSeconds = -1.0;
        totalLapSeconds = 0.0;
        lapsCompleted = 0;
        abandoned = 0;
    }

    // ---- Queries ----

    /** Trees felled so far this lap, and how many the lap needs. */
    public synchronized int getProgress() {
        return fellThisLap;
    }

    public synchronized int getLapSize() {
        return routeSize;
    }

    /** Seconds into the current lap, or -1 when no lap is running. */
    public synchronized double getElapsedSeconds() {
        if (lapStartedAt == 0L) {
            return -1.0;
        }
        if (System.currentTimeMillis() - lastChopAt > STALL_MS) {
            return -1.0;   // stalled; it will restart on the next fell
        }
        return (System.currentTimeMillis() - lapStartedAt) / 1000.0;
    }

    public synchronized double getLastLapSeconds() {
        return lastLapSeconds;
    }

    public synchronized double getAverageLapSeconds() {
        return lapsCompleted > 0 ? totalLapSeconds / lapsCompleted : -1.0;
    }

    public synchronized int getLapsCompleted() {
        return lapsCompleted;
    }

    public synchronized int getAbandonedLaps() {
        return abandoned;
    }

    /** The best lap ever recorded for the route being followed, or -1. */
    public static double bestLapForActive() {
        RecordedRoute active = RouteLibrary.getActive();
        return active == null ? -1.0 : active.bestLapSeconds;
    }

    /** "1:24" — laps are read in minutes and seconds, not 84.0. */
    public static String format(double seconds) {
        if (seconds < 0.0) {
            return "--";
        }
        int total = (int) Math.round(seconds);
        int minutes = total / 60;
        int rest = total % 60;
        return minutes > 0
                ? minutes + ":" + (rest < 10 ? "0" : "") + rest
                : rest + "s";
    }
}
