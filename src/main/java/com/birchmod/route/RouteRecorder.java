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
    public void onTreeChopped(BlockPos base) {
        String routeName;
        int count;

        synchronized (this) {
            if (recording == null || base == null) {
                return;
            }
            // A tree belongs on a loop once. Only the previous point was
            // checked before, so recording a lap and a half — chopping a tree,
            // working round, and reaching it again after it regrew — stored it
            // twice. The route then drew two markers on one tree with a line
            // running between them.
            if (recording.contains(base.getX(), base.getY(), base.getZ())) {
                return;
            }
            recording.points.add(new RecordedRoute.Point(base.getX(), base.getY(), base.getZ()));
            routeName = name;
            count = recording.size();
        }

        Notifier.actionBar("§eRecording §f" + routeName + " §7— " + count + " stop(s)");
    }

    /**
     * Finish recording and save.
     *
     * @return the saved route, or null if it had too few stops to be useful
     */
    public RecordedRoute stop() {
        RecordedRoute finished;
        synchronized (this) {
            finished = recording;
            recording = null;
            name = null;
        }

        if (finished == null || finished.size() < RouteLibrary.MIN_STOPS) {
            return null;
        }
        // Saving writes the routes file. Doing that inside the lock would make
        // a chop arriving on the client thread wait on a disk write, so the
        // recording is detached first and only then persisted.
        RouteLibrary.save(finished);
        return finished;
    }

    /** Abandon the recording without saving. */
    public synchronized void cancel() {
        recording = null;
        name = null;
    }
}
