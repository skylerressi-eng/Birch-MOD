package com.birchmod.route;

import com.birchmod.util.Notifier;

import net.minecraft.core.BlockPos;

/**
 * Records a foraging loop by watching which trees you actually chop.
 *
 * Chopping is the right signal: walking past a tree says nothing, but felling
 * one is an explicit statement that it belongs on the route, in the order you
 * worked it. Nothing needs to be clicked or aimed at.
 */
public final class RouteRecorder {

    /**
     * Commands run on one thread and chop detection on another, so every access
     * is synchronized. Without it a chop landing mid-{@code stop()} can be
     * appended to a route that has already been handed off and saved.
     */
    private String name = null;
    private RecordedRoute recording = null;

    /** Begin a new recording, discarding any in progress. */
    public synchronized void start(String routeName) {
        this.name = routeName;
        this.recording = new RecordedRoute(routeName);
    }

    public synchronized boolean isRecording() {
        return recording != null;
    }

    public synchronized String getName() {
        return name;
    }

    public synchronized int getCount() {
        return recording == null ? 0 : recording.size();
    }

    /**
     * Called when a tree is fully chopped. Consecutive repeats of the same tree
     * are collapsed so re-felling one stump does not stack duplicates.
     */
    public synchronized void onTreeChopped(BlockPos base) {
        if (recording == null || base == null) {
            return;
        }
        if (!recording.points.isEmpty()) {
            RecordedRoute.Point last = recording.points.get(recording.points.size() - 1);
            if (last.x == base.getX() && last.y == base.getY() && last.z == base.getZ()) {
                return;
            }
        }
        recording.points.add(new RecordedRoute.Point(base.getX(), base.getY(), base.getZ()));
        Notifier.actionBar("§eRecording §f" + name + " §7— " + recording.size() + " stop(s)");
    }

    /**
     * Finish recording and save.
     *
     * @return the saved route, or null if it had too few stops to be useful
     */
    public synchronized RecordedRoute stop() {
        RecordedRoute finished = recording;
        recording = null;
        name = null;

        if (finished == null || finished.size() < RouteLibrary.MIN_STOPS) {
            return null;
        }
        RouteLibrary.save(finished);
        return finished;
    }

    /** Abandon the recording without saving. */
    public synchronized void cancel() {
        recording = null;
        name = null;
    }
}
