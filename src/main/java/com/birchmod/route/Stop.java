package com.birchmod.route;

import com.birchmod.tracking.TreeRegenTracker;

import net.minecraft.core.BlockPos;

/**
 * One stop on the route, as the renderers and commands see it.
 *
 * @param tree       the tracked tree, or null when the stop is out of tracking
 *                   range and only its recorded position is known
 * @param base       where the trunk stands — the identity of the stop
 * @param center     the block to mine: always a real log while wood remains,
 *                   never an offset guessed from the base
 * @param etaSeconds when you can expect to be chopping here
 * @param order      1 for the tree you are heading to, ascending after that
 * @param woodLeft   logs still standing, or -1 when the tree is not tracked
 * @param unfinished chopped into and left with wood on it
 */
public record Stop(TreeRegenTracker.Tree tree,
                   BlockPos base,
                   BlockPos center,
                   double etaSeconds,
                   int order,
                   int woodLeft,
                   boolean unfinished) {

    /**
     * Whether there is anything here to chop.
     *
     * An untracked stop counts as having wood. It is a tree you recorded and
     * have not reached yet, and the only way to learn anything about it is to
     * go there — treating "unknown" as "nothing here" is what used to make the
     * route stall on its first stop and never advance.
     */
    public boolean hasWood() {
        return woodLeft != 0;
    }

    /** True once the tracker has actually seen this tree. */
    public boolean isKnown() {
        return woodLeft >= 0;
    }

    /** Cleared out and regrowing, with a wait worth showing. */
    public boolean isWaiting() {
        return woodLeft == 0 && etaSeconds > 0.5;
    }
}
