package com.birchmod.api;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import com.birchmod.util.HttpUtil;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * The collection ladder: how much wood each tier wants, and what it unlocks.
 *
 * <h2>Why this is worth fetching</h2>
 * The mod could already tell you how much birch you have and how fast you are
 * cutting it. Neither of those is the question anyone is actually asking while
 * they forage, which is <em>how much longer</em> — and that needs the one thing
 * the mod did not have: where the next rung is.
 *
 * With the ladder, a total and a rate become an answer. "3,480 birch" is a
 * number; "tier 7, 1,520 to go, about fourteen minutes" is a reason to keep
 * going or a reason to stop.
 *
 * <h2>No key needed</h2>
 * This is {@code /v2/resources/skyblock/collections}, which is static game data
 * rather than anything about you, so it answers without an API key. That makes
 * it the only part of the collection display that works for a player who has
 * never set one — the thresholds and the unlocks are visible straight away, and
 * their own position on the ladder fills in if they add a key later.
 *
 * <h2>Fetched once</h2>
 * The thresholds change when Hypixel ships a content update, so once per
 * session is plenty. A failure is retried on a long timer rather than
 * abandoned, because the first attempt happens at login — exactly when a
 * connection is least likely to be up.
 */
public final class CollectionTiers {

    private static final String RESOURCE_URL =
            "https://api.hypixel.net/v2/resources/skyblock/collections";

    /** Long enough that a failure is not hammered, short enough to recover. */
    private static final long RETRY_MINUTES = 30L;

    /**
     * One rung: the running total it needs, and what reaching it gives you.
     *
     * @param tier     1-based, as Skyblock numbers them
     * @param required the lifetime total this tier wants
     * @param unlocks  what it hands over, in Hypixel's own words
     */
    public record Tier(int tier, long required, List<String> unlocks) {
    }

    /** Where a total sits on a ladder. */
    public record Progress(String name, int tier, int maxTiers,
                           long collected, long nextRequired, long previousRequired) {

        /** At the top: nothing left to earn. */
        public boolean maxed() {
            return nextRequired < 0L;
        }

        /** Still needed for the next tier, or 0 when maxed. */
        public long remaining() {
            return maxed() ? 0L : Math.max(0L, nextRequired - collected);
        }

        /**
         * How far through the current tier, 0..1.
         *
         * Measured from the previous threshold rather than from zero, because
         * the tiers are roughly geometric — 10,000 of 25,000 is 40% of the way
         * to the top but only a fifth of the way through the tier you are in,
         * and the bar you are watching is the tier you are in.
         */
        public double fraction() {
            if (maxed()) {
                return 1.0;
            }
            long span = nextRequired - previousRequired;
            if (span <= 0L) {
                return 1.0;
            }
            double through = (double) (collected - previousRequired) / (double) span;
            return Math.max(0.0, Math.min(1.0, through));
        }

        /**
         * Seconds to the next tier at this rate, or -1 when that cannot be said.
         *
         * A rate of zero is not a very long wait, it is no answer at all — the
         * player has not cut anything yet, or has stopped. Reporting it as
         * infinity, or as some enormous number of hours, reads as a bug.
         */
        public double secondsToNext(double perHour) {
            if (maxed() || perHour <= 0.0 || !Double.isFinite(perHour)) {
                return -1.0;
            }
            return remaining() / perHour * 3600.0;
        }
    }

    /** Product id to its ladder, as fetched. Empty until the first success. */
    private static final Map<String, List<Tier>> LADDERS = new ConcurrentHashMap<>();
    private static final Map<String, String> NAMES = new ConcurrentHashMap<>();

    private static volatile String status = "loading";
    private static volatile boolean loaded = false;

    private static final ScheduledExecutorService SCHEDULER =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "BirchOptimizer-Tiers");
                t.setDaemon(true);
                return t;
            });

    private CollectionTiers() {
    }

    public static void start() {
        SCHEDULER.scheduleAtFixedRate(CollectionTiers::refresh, 0L, RETRY_MINUTES, TimeUnit.MINUTES);
    }

    private static void refresh() {
        // Static game data: once it is in, stop asking.
        if (loaded) {
            return;
        }
        try {
            HttpUtil.Response response = HttpUtil.fetch(RESOURCE_URL, null, null);
            if (!response.ok()) {
                status = CollectionApi.describe(response, "refused");
                return;
            }
            JsonObject root = JsonParser.parseString(response.body()).getAsJsonObject();
            if (!root.has("success") || !root.get("success").getAsBoolean()) {
                status = "api error";
                return;
            }
            int found = parseInto(root, LADDERS, NAMES);
            if (found == 0) {
                status = "no collections";
                return;
            }
            loaded = true;
            status = "ok";
        } catch (Throwable t) {
            // A scheduled task that throws is cancelled and never runs again.
            status = "error";
        }
    }

    /**
     * Read every collectable item's ladder out of the resource document.
     *
     * Walks all categories rather than only Foraging. The ids are unique across
     * the document, nothing here is specific to wood, and a forager who points
     * the mod at something else — the Bazaar product is a setting — gets the
     * same display without this having to know what they chose.
     *
     * @return how many ladders were read
     */
    static int parseInto(JsonObject root,
                         Map<String, List<Tier>> ladders,
                         Map<String, String> names) {
        JsonElement collectionsEl = root.get("collections");
        if (collectionsEl == null || !collectionsEl.isJsonObject()) {
            return 0;
        }

        int count = 0;
        for (Map.Entry<String, JsonElement> category : collectionsEl.getAsJsonObject().entrySet()) {
            if (!category.getValue().isJsonObject()) {
                continue;
            }
            JsonElement itemsEl = category.getValue().getAsJsonObject().get("items");
            if (itemsEl == null || !itemsEl.isJsonObject()) {
                continue;
            }
            for (Map.Entry<String, JsonElement> item : itemsEl.getAsJsonObject().entrySet()) {
                if (!item.getValue().isJsonObject()) {
                    continue;
                }
                List<Tier> ladder = tiersOf(item.getValue().getAsJsonObject());
                if (ladder.isEmpty()) {
                    continue;
                }
                ladders.put(item.getKey(), ladder);
                String name = nameOf(item.getValue().getAsJsonObject());
                if (name != null) {
                    names.put(item.getKey(), name);
                }
                count++;
            }
        }
        return count;
    }

    /** The rungs of one item, in ascending order of requirement. */
    private static List<Tier> tiersOf(JsonObject item) {
        JsonElement tiersEl = item.get("tiers");
        if (tiersEl == null || !tiersEl.isJsonArray()) {
            return List.of();
        }
        List<Tier> out = new java.util.ArrayList<>();
        for (JsonElement element : tiersEl.getAsJsonArray()) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject tier = element.getAsJsonObject();
            if (!tier.has("tier") || !tier.has("amountRequired")) {
                continue;
            }
            try {
                out.add(new Tier(tier.get("tier").getAsInt(),
                        tier.get("amountRequired").getAsLong(),
                        unlocksOf(tier)));
            } catch (Exception ignored) {
                // One malformed rung should not cost the whole ladder.
            }
        }
        // Hypixel lists them in order. Sorted anyway so that a screen showing
        // the whole ladder shows it the way up — the arithmetic no longer
        // depends on this, which is the only reason it is safe to rely on a
        // display detail here.
        out.sort((a, b) -> Long.compare(a.required(), b.required()));
        return List.copyOf(out);
    }

    private static List<String> unlocksOf(JsonObject tier) {
        JsonElement unlocksEl = tier.get("unlocks");
        if (unlocksEl == null || !unlocksEl.isJsonArray()) {
            return List.of();
        }
        JsonArray array = unlocksEl.getAsJsonArray();
        List<String> out = new java.util.ArrayList<>(array.size());
        for (JsonElement element : array) {
            if (element.isJsonPrimitive()) {
                out.add(element.getAsString());
            }
        }
        return List.copyOf(out);
    }

    private static String nameOf(JsonObject item) {
        JsonElement name = item.get("name");
        return name != null && name.isJsonPrimitive() ? name.getAsString() : null;
    }

    // ---- Queries ----

    /**
     * Where a total sits on a product's ladder, or null if that is not known.
     *
     * Null rather than a zeroed record, because "tier 0 of 0" is a claim about
     * the player and the honest state here is that the ladder has not arrived.
     */
    public static Progress progressFor(String productId, long collected) {
        if (productId == null || collected < 0L) {
            return null;
        }
        List<Tier> ladder = LADDERS.get(productId);
        if (ladder == null || ladder.isEmpty()) {
            return null;
        }
        return locate(NAMES.getOrDefault(productId, productId), ladder, collected);
    }

    /**
     * Hypixel's own name for a collectable id, or null if it is not known.
     *
     * The id a player sees in the settings is {@code LOG:2}, which names
     * nothing. This is where "Birch Log" comes from, and it comes from the game
     * rather than from a table in the mod, so a wood added next season is named
     * correctly without anybody editing anything.
     */
    public static String nameOf(String productId) {
        return productId == null ? null : NAMES.get(productId);
    }

    /**
     * Place a total on a ladder.
     *
     * Kept apart from the fetching so it can be exercised against a ladder
     * written out by hand. The arithmetic is the part that is read off the
     * screen and the part that has an off-by-one in it if anyone gets the
     * boundary wrong: reaching a threshold exactly <em>is</em> that tier, so
     * 1,000 birch against a tier-5 requirement of 1,000 is tier 5 and not tier
     * 4 with nothing left to go.
     *
     * Order-independent on purpose. This walked the rungs expecting them
     * ascending and stopped at the first one not yet cleared, which is correct
     * for the document Hypixel actually serves and silently wrong for any other
     * — against an unsorted ladder it reported tier 0 for a player most of the
     * way up. The sort in {@link #tiersOf} meant the fetched path was safe
     * while this, the part anything can call, was not.
     */
    static Progress locate(String name, List<Tier> ladder, long collected) {
        int tier = 0;
        long previous = 0L;
        long next = -1L;
        int maxTiers = 0;

        for (Tier rung : ladder) {
            maxTiers = Math.max(maxTiers, rung.tier());
            if (collected >= rung.required()) {
                // The highest rung cleared is the tier; the rung itself is the
                // floor the progress bar measures from.
                if (rung.required() >= previous) {
                    tier = rung.tier();
                    previous = rung.required();
                }
            } else if (next < 0L || rung.required() < next) {
                // The lowest rung not cleared is what is being worked toward.
                next = rung.required();
            }
        }
        return new Progress(name, tier, maxTiers, collected, next, previous);
    }

    /**
     * What the next tier up hands over, for a product and a total.
     *
     * The lowest uncleared rung, found rather than assumed to come first, for
     * the same reason {@link #locate} does not assume an order.
     */
    public static List<String> nextUnlocks(String productId, long collected) {
        List<Tier> ladder = productId == null ? null : LADDERS.get(productId);
        if (ladder == null) {
            return List.of();
        }
        Tier next = null;
        for (Tier rung : ladder) {
            if (collected < rung.required()
                    && (next == null || rung.required() < next.required())) {
                next = rung;
            }
        }
        return next == null ? List.of() : next.unlocks();
    }

    /** The whole ladder for a product, for a screen that wants to show it. */
    public static List<Tier> ladder(String productId) {
        List<Tier> ladder = productId == null ? null : LADDERS.get(productId);
        return ladder == null ? List.of() : ladder;
    }

    public static boolean isLoaded() {
        return loaded;
    }

    public static String getStatus() {
        return status;
    }

    /** How many ladders are known. Reported by {@code /birch diag}. */
    public static int size() {
        return LADDERS.size();
    }
}
