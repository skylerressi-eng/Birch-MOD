package com.birchmod.route;

import com.birchmod.config.BirchConfig;
import com.birchmod.tracking.TreeRegenTracker;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Turns a recorded coordinate into what is actually standing there now.
 *
 * <h2>The bug this replaces</h2>
 * Recorded routes store the position a trunk had when you felled it, and the
 * tracker rediscovers that trunk a block or two off after it regrows. The
 * planner used to look trees up by exact position, so almost every recorded
 * stop came back unmatched: no regen clock, no idea whether wood was left on
 * it, and a marker placed at a blind offset above the recorded coordinate —
 * which is why markers kept landing in the canopy. Matching by proximity, and
 * falling back to reading the world rather than guessing, is what keeps a
 * recorded route attached to the ground it was recorded on.
 */
final class TreeSight {

    /** How far up to look for wood when reading the world directly. */
    private static final int PROBE_HEIGHT = 12;

    private TreeSight() {
    }

    /** A recorded stop, resolved against the world as it stands right now. */
    record Live(BlockPos base, TreeRegenTracker.Tree tree, BlockPos center,
                int woodLeft, double readySeconds) {

        /** Somewhere to go: wood standing, or a tree we have not seen yet. */
        boolean hasWood() {
            return woodLeft != 0;
        }

        boolean unfinished() {
            return tree != null && tree.isPartiallyChopped();
        }
    }

    /**
     * Resolve one recorded point.
     *
     * A tracked tree answers everything: which of its logs to mark, how much
     * wood is left, and how long until it is back. An untracked one is read
     * straight out of the world if its chunk is loaded, and otherwise left at
     * the coordinate that was recorded — never displaced upward on a guess.
     */
    static Live resolve(TreeRegenTracker tracker, int x, int y, int z) {
        BlockPos recorded = new BlockPos(x, y, z);
        TreeRegenTracker.Tree tree = tracker.findNear(x, y, z, TreeRegenTracker.SAME_TREE_RADIUS);

        // A tree registered by the sweep but not yet read holds a wood count of
        // zero simply because nobody has looked. Reporting that as "cleared"
        // marches the route straight past a tree it knows nothing about, so an
        // unexamined tree is treated exactly like one out of range: somewhere
        // to go, with its marker read from the world.
        if (tree != null && !tree.isProbed()) {
            return new Live(tree.base, tree, readWorld(tree.base), -1, 0.0);
        }

        if (tree != null) {
            int wood = tree.getWoodCount();
            if (wood > 0) {
                return new Live(tree.base, tree, tree.getTarget(), wood, 0.0);
            }
            double ready = tree.isDowned()
                    ? Math.max(0.0, tracker.getSecondsUntilRegen(tree))
                    : 0.0;
            // Nothing standing: mark the base, which is where it comes back.
            return new Live(tree.base, tree, tree.base, 0, ready);
        }

        return new Live(recorded, null, readWorld(recorded), -1, 0.0);
    }

    /**
     * Find real wood at a recorded position by reading the world.
     *
     * Returns the recorded position unchanged when the chunk is not loaded or
     * nothing birch is there — standing on the trunk base is honest, whereas an
     * offset above it is a guess that lands on leaves as often as wood.
     */
    static BlockPos readWorld(BlockPos recorded) {
        Minecraft client = Minecraft.getInstance();
        if (client == null || client.level == null
                || !client.level.hasChunk(recorded.getX() >> 4, recorded.getZ() >> 4)) {
            return recorded;
        }

        int radius = Math.max(0, Math.min(2, BirchConfig.get().treeFootprint));
        int desired = recorded.getY() + BirchConfig.get().treeCenterHeight;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();

        int bestScore = Integer.MAX_VALUE;
        int bestX = 0;
        int bestY = Integer.MIN_VALUE;
        int bestZ = 0;

        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                for (int dy = 0; dy < PROBE_HEIGHT; dy++) {
                    int x = recorded.getX() + dx;
                    int y = recorded.getY() + dy;
                    int z = recorded.getZ() + dz;
                    cursor.set(x, y, z);
                    BlockState state = client.level.getBlockState(cursor);
                    if (!state.is(Blocks.BIRCH_LOG) && !state.is(Blocks.BIRCH_WOOD)) {
                        continue;
                    }
                    // Same preference the tracker uses: closest to the wanted
                    // height, and heavily biased toward the trunk itself.
                    int score = Math.abs(y - desired) + (Math.abs(dx) + Math.abs(dz)) * 8;
                    if (score < bestScore) {
                        bestScore = score;
                        bestX = x;
                        bestY = y;
                        bestZ = z;
                    }
                }
            }
        }

        return bestY == Integer.MIN_VALUE ? recorded : new BlockPos(bestX, bestY, bestZ);
    }

    /**
     * How long this hop really takes.
     *
     * Measured from your own foraging where the leg has been walked enough
     * times to mean something, and estimated from distance and your measured
     * travel speed until then.
     */
    static double travelSeconds(BlockPos from, BlockPos to) {
        double fallback = distance(from, to) / RouteLibrary.walkSpeed();
        return TravelGraph.legSeconds(
                from.getX(), from.getY(), from.getZ(),
                to.getX(), to.getY(), to.getZ(),
                fallback);
    }

    /** Player to first stop: no leg has ever been measured from where you stand. */
    static double approachSeconds(Vec3 from, BlockPos to) {
        return from.distanceTo(Vec3.atCenterOf(to)) / RouteLibrary.walkSpeed();
    }

    private static double distance(BlockPos a, BlockPos b) {
        double dx = a.getX() - b.getX();
        double dy = a.getY() - b.getY();
        double dz = a.getZ() - b.getZ();
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }
}
