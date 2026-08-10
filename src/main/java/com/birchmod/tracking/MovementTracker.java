package com.birchmod.tracking;

import com.birchmod.stats.SessionStats;

import net.minecraft.client.Minecraft;
import net.minecraft.world.level.Level;

/**
 * Measures how fast the player actually travels.
 *
 * Every arrival estimate, every decision to skip a stop still regrowing, and
 * the whole route score rest on how long it takes to get between trees. That
 * was a constant — seven blocks a second, roughly vanilla sprinting — and
 * Skyblock does not work like vanilla. A speed stat of a few hundred percent
 * moves you several times faster than that, which makes every lap look longer
 * than it is, every ETA too pessimistic, and the optimiser pick fewer trees
 * than it should.
 *
 * So it is measured rather than assumed, and remembered between sessions.
 *
 * <h2>What counts as a sample</h2>
 * Only horizontal movement, sampled twice a second. A sample is discarded
 * unless it falls in a plausible band: below it the player is standing around
 * or nudging into a tree, above it they have warped or been teleported, and
 * neither says anything about how fast they cross a grove.
 */
public class MovementTracker {

    private static final int SAMPLE_INTERVAL_TICKS = 10; // twice a second

    /** Slower than this is loitering, not travelling. */
    private static final double MIN_PLAUSIBLE_SPEED = 2.0;

    /** Faster than this is a warp or teleport, not walking. */
    private static final double MAX_PLAUSIBLE_SPEED = 60.0;

    private int tickCounter = 0;
    private long lastSampleAt = 0L;
    private double lastX = Double.NaN;
    private double lastZ = Double.NaN;
    private Level lastLevel = null;

    public void tick(Minecraft client) {
        if (client == null || client.player == null) {
            reset(null);
            return;
        }

        // Changing island teleports the player; the jump is not travel.
        Level level = client.player.level();
        if (level != lastLevel) {
            reset(level);
            return;
        }

        if (++tickCounter < SAMPLE_INTERVAL_TICKS) {
            return;
        }
        tickCounter = 0;

        long now = System.currentTimeMillis();
        double x = client.player.getX();
        double z = client.player.getZ();

        if (Double.isNaN(lastX)) {
            lastX = x;
            lastZ = z;
            lastSampleAt = now;
            return;
        }

        double elapsed = (now - lastSampleAt) / 1000.0;
        double dx = x - lastX;
        double dz = z - lastZ;

        lastX = x;
        lastZ = z;
        lastSampleAt = now;

        if (elapsed <= 0.05) {
            return;
        }

        double speed = Math.sqrt(dx * dx + dz * dz) / elapsed;
        if (speed >= MIN_PLAUSIBLE_SPEED && speed <= MAX_PLAUSIBLE_SPEED) {
            SessionStats.recordWalkSample(speed);
        }
    }

    private void reset(Level level) {
        lastX = Double.NaN;
        lastZ = Double.NaN;
        lastLevel = level;
        tickCounter = 0;
    }
}
