package com.birchmod.tracking;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import com.birchmod.config.BirchConfig;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

/**
 * Times how long a chopped birch tree takes to regenerate.
 *
 * How it works, entirely client-side (so it works on Hypixel):
 *  1. While you look at a birch log, that position is remembered.
 *  2. When a remembered position turns to air, a countdown starts for it.
 *  3. When it turns back into a birch log, the true regen duration is measured
 *     and folded into a running average.
 *
 * The measured average is what drives the countdown, so the timer calibrates
 * itself to whatever Hypixel's actual regen rate is instead of relying on a
 * hardcoded constant. Until a full cycle has been observed, the configurable
 * {@link BirchConfig#defaultRegenSeconds} is used.
 */
public class TreeRegenTracker {

    /** Cap on tracked positions so a long session cannot grow unbounded. */
    private static final int MAX_TRACKED = 64;

    /** Ignore absurd measurements (block replaced by something unrelated). */
    private static final double MAX_PLAUSIBLE_REGEN_SECONDS = 600.0;

    private static final int SAMPLE_INTERVAL_TICKS = 2; // 10x per second

    private static final class Watched {
        boolean chopped;
        long choppedAt;
    }

    private final Map<BlockPos, Watched> watched = new HashMap<>();

    private double averageRegenSeconds = -1.0;
    private int measurementCount = 0;
    private long lastChopAt = 0L;

    private int tickCounter = 0;

    public void tick(Minecraft client) {
        if (!BirchConfig.get().regenTimerEnabled) {
            return;
        }
        if (client == null || client.player == null || client.level == null) {
            watched.clear();
            return;
        }

        if (++tickCounter < SAMPLE_INTERVAL_TICKS) {
            return;
        }
        tickCounter = 0;

        rememberLookedAtBlock(client);
        updateWatched(client);
    }

    /** Remember any birch log the player is aiming at, so we can watch it. */
    private void rememberLookedAtBlock(Minecraft client) {
        HitResult hit = client.hitResult;
        if (hit == null || hit.getType() != HitResult.Type.BLOCK) {
            return;
        }
        BlockPos pos = ((BlockHitResult) hit).getBlockPos();
        if (!isBirchAt(client, pos)) {
            return;
        }
        if (!watched.containsKey(pos) && watched.size() < MAX_TRACKED) {
            watched.put(pos.immutable(), new Watched());
        }
    }

    /** Detect chop (log -> air) and regrowth (air -> log) transitions. */
    private void updateWatched(Minecraft client) {
        long now = System.currentTimeMillis();

        for (Iterator<Map.Entry<BlockPos, Watched>> it = watched.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<BlockPos, Watched> entry = it.next();
            BlockPos pos = entry.getKey();
            Watched state = entry.getValue();

            // Drop positions we have wandered away from.
            if (client.player.blockPosition().distSqr(pos) > 64 * 64) {
                it.remove();
                continue;
            }

            boolean isBirch = isBirchAt(client, pos);

            if (!state.chopped && !isBirch) {
                // The log we were watching just disappeared: chopped.
                state.chopped = true;
                state.choppedAt = now;
                lastChopAt = now;
            } else if (state.chopped && isBirch) {
                // It grew back — this is a real, measured regen cycle.
                double seconds = (now - state.choppedAt) / 1000.0;
                if (seconds > 0.0 && seconds <= MAX_PLAUSIBLE_REGEN_SECONDS) {
                    recordMeasurement(seconds);
                }
                state.chopped = false;
                state.choppedAt = 0L;
            }
        }
    }

    private void recordMeasurement(double seconds) {
        if (averageRegenSeconds < 0.0) {
            averageRegenSeconds = seconds;
        } else {
            // Running average, weighted toward recent observations.
            averageRegenSeconds = (averageRegenSeconds * 0.7) + (seconds * 0.3);
        }
        measurementCount++;
    }

    private boolean isBirchAt(Minecraft client, BlockPos pos) {
        BlockState state = client.level.getBlockState(pos);
        return state.is(Blocks.BIRCH_LOG) || state.is(Blocks.BIRCH_WOOD);
    }

    /** The regen duration currently in use: measured if known, else config. */
    public double getRegenSeconds() {
        return averageRegenSeconds > 0.0 ? averageRegenSeconds : BirchConfig.get().defaultRegenSeconds;
    }

    public boolean isCalibrated() {
        return measurementCount > 0;
    }

    public int getMeasurementCount() {
        return measurementCount;
    }

    /**
     * @return seconds until the most recently chopped tree should regenerate,
     *         0 if it is already due, or -1 if nothing is being tracked.
     */
    public double getSecondsUntilRegen() {
        if (lastChopAt <= 0L) {
            return -1.0;
        }
        double elapsed = (System.currentTimeMillis() - lastChopAt) / 1000.0;
        double remaining = getRegenSeconds() - elapsed;
        return Math.max(0.0, remaining);
    }

    /** @return how many watched trees are currently chopped and regrowing. */
    public int getPendingCount() {
        int count = 0;
        for (Watched w : watched.values()) {
            if (w.chopped) {
                count++;
            }
        }
        return count;
    }
}
