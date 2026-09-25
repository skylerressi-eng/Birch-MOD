package com.birchmod.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.birchmod.api.BazaarManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Settings migration: write-once code that nothing ever checks.
 *
 * A changed default only reaches somebody who has never run the mod. Everybody
 * else has the old value on disk, where it quietly wins forever, and every one
 * of these steps exists because a real fix was invisible to existing players
 * until it was written. That makes migration the least-exercised and
 * highest-consequence code here — it runs once per person, on their real
 * settings, and if it is wrong nobody can tell it from the original bug.
 */
class BirchConfigTest {

    /** A settings file written before any of this existed reads as version 0. */
    private static BirchConfig ancient() {
        BirchConfig c = new BirchConfig();
        c.configVersion = 0;
        c.showFullPath = true;
        c.minTreeLogs = 2;
        c.bazaarProductId = "BIRCH_LOG";
        return c;
    }

    @Test
    @DisplayName("a file from before any migration gets all of them")
    void ancientFileIsFullyMigrated() {
        BirchConfig c = ancient();
        c.migrate();

        assertFalse(c.showFullPath,
                "drawing the whole loop filled a dense grove with lines to trees "
                        + "you were not going to next");
        assertEquals(1, c.minTreeLogs, "a pair of logs on the ground is choppable birch");
        assertEquals(BazaarManager.BIRCH_PRODUCT, c.bazaarProductId,
                "BIRCH_LOG matches no Bazaar product and never did");
        assertEquals(4, c.configVersion, "and it is now up to date");
    }

    @Test
    @DisplayName("each step runs only for the versions below it")
    void stepsAreVersionGated() {
        // Someone already on 3 has had the first two steps; only the product id
        // is still wrong.
        BirchConfig c = new BirchConfig();
        c.configVersion = 3;
        c.showFullPath = true;
        c.minTreeLogs = 4;
        c.bazaarProductId = "BIRCH_LOG";
        c.migrate();

        assertTrue(c.showFullPath, "a setting they chose since must not be reset");
        assertEquals(4, c.minTreeLogs, "nor a threshold they raised on purpose");
        assertEquals(BazaarManager.BIRCH_PRODUCT, c.bazaarProductId, "but the dead id goes");
    }

    /**
     * The narrow part of the product migration: only the id that cannot work is
     * replaced. Replacing whatever was there would quietly undo a deliberate
     * choice, which is worse than the bug.
     */
    @Test
    @DisplayName("a product chosen on purpose is left alone")
    void deliberateProductSurvives() {
        BirchConfig c = new BirchConfig();
        c.configVersion = 2;
        c.bazaarProductId = "ENCHANTED_BIRCH_LOG";
        c.migrate();
        assertEquals("ENCHANTED_BIRCH_LOG", c.bazaarProductId);
    }

    @Test
    @DisplayName("migrating an up-to-date file changes nothing")
    void currentFileIsUntouched() {
        BirchConfig c = new BirchConfig();
        c.configVersion = 4;
        c.showFullPath = true;
        c.minTreeLogs = 6;
        c.bazaarProductId = "BIRCH_LOG";
        c.migrate();

        assertTrue(c.showFullPath);
        assertEquals(6, c.minTreeLogs);
        assertEquals("BIRCH_LOG", c.bazaarProductId,
                "past its version, even a bad value is the player's to keep");
    }

    @Test
    @DisplayName("migration is idempotent")
    void runningItTwiceIsTheSame() {
        BirchConfig once = ancient();
        once.migrate();

        BirchConfig twice = ancient();
        twice.migrate();
        twice.migrate();

        assertEquals(once.showFullPath, twice.showFullPath);
        assertEquals(once.minTreeLogs, twice.minTreeLogs);
        assertEquals(once.bazaarProductId, twice.bazaarProductId);
        assertEquals(once.configVersion, twice.configVersion);
    }

    @Test
    @DisplayName("a hand-edited file is clamped back into range")
    void clampBoundsEverything() {
        BirchConfig c = new BirchConfig();
        c.hudScale = 99.0;
        c.notifyVolume = -4.0;
        c.routeLength = 9999;
        c.treeCenterHeight = -3;
        c.treeFootprint = 17;
        c.minTreeLogs = 0;
        c.lineWidth = 0.0;
        c.worldTimerRange = 1.0;
        c.bazaarTaxRate = 5.0;
        c.hudX = -50;
        c.bazaarProductId = "   ";
        c.clamp();

        assertTrue(c.hudScale <= 3.0 && c.hudScale >= 0.5, "scale was " + c.hudScale);
        assertTrue(c.notifyVolume >= 0.0 && c.notifyVolume <= 1.0);
        assertTrue(c.routeLength >= 1 && c.routeLength <= BirchConfig.MAX_ROUTE_LENGTH);
        assertTrue(c.treeCenterHeight >= 0 && c.treeCenterHeight <= 12);
        assertTrue(c.treeFootprint >= 0 && c.treeFootprint <= 2);
        assertTrue(c.minTreeLogs >= 1 && c.minTreeLogs <= 8);
        assertTrue(c.lineWidth >= 0.5 && c.lineWidth <= 10.0);
        assertTrue(c.worldTimerRange >= 4.0);
        assertTrue(c.bazaarTaxRate <= 0.25);
        assertEquals(0, c.hudX, "a negative position would put the overlay off screen");
        assertEquals(BazaarManager.BIRCH_PRODUCT, c.bazaarProductId,
                "a blank product id falls back to birch rather than matching nothing");
    }

    @Test
    @DisplayName("clamping is idempotent, so saving repeatedly does not drift")
    void clampIsStable() {
        BirchConfig c = new BirchConfig();
        c.hudScale = 1.7;
        c.lineWidth = 4.0;
        c.clamp();
        double scale = c.hudScale;
        double width = c.lineWidth;
        c.clamp();
        c.clamp();
        assertEquals(scale, c.hudScale);
        assertEquals(width, c.lineWidth);
    }

    @Test
    @DisplayName("the defaults a new player gets are already in range")
    void defaultsAreValid() {
        BirchConfig fresh = new BirchConfig();
        BirchConfig clamped = new BirchConfig();
        clamped.clamp();

        assertEquals(fresh.hudScale, clamped.hudScale);
        assertEquals(fresh.routeLength, clamped.routeLength);
        assertEquals(fresh.minTreeLogs, clamped.minTreeLogs);
        assertEquals(fresh.lineWidth, clamped.lineWidth);
        assertEquals(fresh.bazaarProductId, clamped.bazaarProductId);
        assertEquals(BazaarManager.BIRCH_PRODUCT, fresh.bazaarProductId,
                "a new player must start on a product that exists");
    }
}
