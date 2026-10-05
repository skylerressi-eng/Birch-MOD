package com.birchmod.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Placing a collection total on the Skyblock ladder.
 *
 * <h2>Where the numbers come from</h2>
 * The ladder below is Birch Log's, copied from Hypixel's own
 * {@code /v2/resources/skyblock/collections} as it answers today: nine tiers at
 * 50, 100, 250, 500, 1,000, 2,000, 5,000, 10,000 and 25,000. It is written out
 * rather than fetched because a test that needs the internet is a test that
 * fails on a train, and because what is being checked here is the arithmetic,
 * not Hypixel's uptime.
 *
 * <h2>Why it is worth a test at all</h2>
 * It is a boundary problem, and boundary problems are where a reasonable-looking
 * loop is off by one in a way nobody notices until it is on screen. Reaching a
 * threshold exactly <em>is</em> that tier — 1,000 birch against a tier-5
 * requirement of 1,000 is tier 5 with the whole of tier 6 ahead, not tier 4 with
 * nothing left to go. Getting that wrong shows a player the tier they just
 * earned as the one they are working toward.
 */
class CollectionTiersTest {

    /** Birch Log, as Hypixel serves it. */
    private static List<CollectionTiers.Tier> birch() {
        return List.of(
                new CollectionTiers.Tier(1, 50, List.of("Birch Minion Recipes")),
                new CollectionTiers.Tier(2, 100, List.of("Beginner Foraging Sack Recipe")),
                new CollectionTiers.Tier(3, 250, List.of("Portal to Birch Park Recipe")),
                new CollectionTiers.Tier(4, 500, List.of("Sculptor's Axe Recipe")),
                new CollectionTiers.Tier(5, 1_000, List.of("Enchanted Birch Log Recipe")),
                new CollectionTiers.Tier(6, 2_000, List.of("Birch Forest Biome Stick Recipe")),
                new CollectionTiers.Tier(7, 5_000, List.of("Medium Foraging Sack Recipe")),
                new CollectionTiers.Tier(8, 10_000, List.of("Large Foraging Sack Recipe")),
                new CollectionTiers.Tier(9, 25_000, List.of("Large Enchanted Foraging Sack Recipe")));
    }

    private static CollectionTiers.Progress at(long collected) {
        return CollectionTiers.locate("Birch Log", birch(), collected);
    }

    @Test
    @DisplayName("nothing collected is tier zero, working on the first")
    void bottomOfTheLadder() {
        CollectionTiers.Progress p = at(0);
        assertEquals(0, p.tier());
        assertEquals(9, p.maxTiers());
        assertEquals(50L, p.nextRequired());
        assertEquals(50L, p.remaining());
        assertEquals(0.0, p.fraction(), 1e-9);
        assertFalse(p.maxed());
    }

    @Test
    @DisplayName("hitting a threshold exactly earns that tier")
    void thresholdIsInclusive() {
        // The off-by-one that would show somebody the tier they just finished
        // as the one they are still working toward.
        assertEquals(5, at(1_000).tier(), "1,000 birch is tier 5, not tier 4");
        assertEquals(2_000L, at(1_000).nextRequired());
        assertEquals(4, at(999).tier(), "one short is still the tier below");
    }

    @Test
    @DisplayName("progress is measured through the current tier, not from zero")
    void fractionIsWithinTheTier() {
        // 10,000 of 25,000 is 40% of the way up the whole ladder and none of
        // the way into tier 8 — and tier 8 is the bar being drawn.
        CollectionTiers.Progress justArrived = at(10_000);
        assertEquals(8, justArrived.tier());
        assertEquals(0.0, justArrived.fraction(), 1e-9);

        // Half way from 10,000 to 25,000 is 17,500.
        assertEquals(0.5, at(17_500).fraction(), 1e-9);
    }

    @Test
    @DisplayName("the top of the ladder has nothing left to go")
    void maxed() {
        CollectionTiers.Progress p = at(25_000);
        assertEquals(9, p.tier());
        assertTrue(p.maxed());
        assertEquals(0L, p.remaining());
        assertEquals(1.0, p.fraction(), 1e-9);
        assertEquals(-1.0, p.secondsToNext(10_000.0), "no wait to report once maxed");

        assertTrue(at(1_000_000).maxed(), "past the top is still the top");
    }

    @Test
    @DisplayName("time to the next tier comes off the current rate")
    void etaFromRate() {
        CollectionTiers.Progress p = at(4_000);
        assertEquals(6, p.tier(), "4,000 birch has cleared 2,000 but not 5,000");
        assertEquals(5_000L, p.nextRequired());
        assertEquals(1_000L, p.remaining());

        // A thousand logs at two thousand an hour is half an hour.
        assertEquals(1_800.0, p.secondsToNext(2_000.0), 1e-6);
    }

    @Test
    @DisplayName("a rate of zero is no answer rather than a very long wait")
    void noRateMeansNoEstimate() {
        // Reported as infinity, or as some enormous number of hours, this reads
        // as a broken counter to anyone who has just logged in.
        CollectionTiers.Progress p = at(4_000);
        assertEquals(-1.0, p.secondsToNext(0.0));
        assertEquals(-1.0, p.secondsToNext(-5.0));
        assertEquals(-1.0, p.secondsToNext(Double.NaN));
        assertEquals(-1.0, p.secondsToNext(Double.POSITIVE_INFINITY));
    }

    @Test
    @DisplayName("a ladder served out of order is still placed correctly")
    void unsortedLadder() {
        // Hypixel sends these ascending. The first version of this walked them
        // expecting that and stopped at the first rung not yet cleared, which
        // against a shuffled ladder reported tier 0 for a player most of the
        // way up it — and the only thing standing between that and a player was
        // a sort in the fetching code, which this arithmetic does not go
        // through.
        List<CollectionTiers.Tier> shuffled = List.of(
                new CollectionTiers.Tier(3, 250, List.of()),
                new CollectionTiers.Tier(1, 50, List.of()),
                new CollectionTiers.Tier(2, 100, List.of()));

        CollectionTiers.Progress p = CollectionTiers.locate("x", shuffled, 120);
        assertEquals(2, p.tier(), "highest rung cleared");
        assertEquals(250L, p.nextRequired(), "lowest rung not cleared");
        assertEquals(3, p.maxTiers());
        // The bar measures from the rung below, which must be the right one.
        assertEquals(20.0 / 150.0, p.fraction(), 1e-9);
    }

    // ---- Reading Hypixel's document ----

    /**
     * A trimmed copy of the real response: the shape, not the whole 80 KB.
     *
     * Two categories, because the parser walks all of them rather than only
     * Foraging — the mod can be pointed at any Bazaar product, and the ids are
     * unique across the document.
     */
    private static final String DOCUMENT = """
            {
              "success": true,
              "collections": {
                "FORAGING": {
                  "items": {
                    "LOG:2": {
                      "name": "Birch Log",
                      "maxTiers": 2,
                      "tiers": [
                        {"tier": 1, "amountRequired": 50, "unlocks": ["Birch Minion Recipes"]},
                        {"tier": 2, "amountRequired": 100, "unlocks": []}
                      ]
                    },
                    "BROKEN": { "name": "No Tiers" }
                  }
                },
                "MINING": {
                  "items": {
                    "COBBLESTONE": {
                      "name": "Cobblestone",
                      "tiers": [{"tier": 1, "amountRequired": 50}]
                    }
                  }
                }
              }
            }
            """;

    @Test
    @DisplayName("every category is read, and a tierless item is skipped")
    void parsesTheDocument() {
        Map<String, List<CollectionTiers.Tier>> ladders = new HashMap<>();
        Map<String, String> names = new HashMap<>();
        JsonObject root = JsonParser.parseString(DOCUMENT).getAsJsonObject();

        assertEquals(2, CollectionTiers.parseInto(root, ladders, names),
                "two items have tiers; the third has none and is not a ladder");

        assertEquals(2, ladders.get("LOG:2").size());
        assertEquals("Birch Log", names.get("LOG:2"));
        assertTrue(ladders.containsKey("COBBLESTONE"), "mining is read too, not just foraging");
        assertFalse(ladders.containsKey("BROKEN"), "an item with no tiers is not a ladder");
    }

    @Test
    @DisplayName("a tier with no unlocks listed is still a tier")
    void missingUnlocksIsNotMissingTier() {
        Map<String, List<CollectionTiers.Tier>> ladders = new HashMap<>();
        CollectionTiers.parseInto(JsonParser.parseString(DOCUMENT).getAsJsonObject(),
                ladders, new HashMap<>());
        List<CollectionTiers.Tier> mining = ladders.get("COBBLESTONE");
        assertEquals(1, mining.size());
        assertEquals(List.of(), mining.get(0).unlocks());
    }

    @Test
    @DisplayName("a document with nothing in it is read as nothing, not as a crash")
    void emptyDocument() {
        Map<String, List<CollectionTiers.Tier>> ladders = new HashMap<>();
        assertEquals(0, CollectionTiers.parseInto(
                JsonParser.parseString("{\"success\":true}").getAsJsonObject(),
                ladders, new HashMap<>()));
        assertEquals(0, CollectionTiers.parseInto(
                JsonParser.parseString("{\"collections\":[]}").getAsJsonObject(),
                ladders, new HashMap<>()), "an array where an object belongs");
    }

    @Test
    @DisplayName("an unknown product has no ladder rather than a made-up one")
    void unknownProduct() {
        // Null rather than a zeroed record: "tier 0 of 0" is a claim about the
        // player, and the honest state is that nothing is known.
        assertNull(CollectionTiers.progressFor("NOT_A_PRODUCT", 500));
        assertNull(CollectionTiers.progressFor(null, 500));
        assertNull(CollectionTiers.progressFor("LOG:2", -1),
                "a total of -1 means the API has not answered yet");
    }
}
