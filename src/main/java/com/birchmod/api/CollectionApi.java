package com.birchmod.api;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import com.birchmod.config.BirchConfig;
import com.birchmod.util.HttpUtil;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Your lifetime birch collection, fetched with your API key.
 *
 * <h2>What the key can actually get you</h2>
 * This used to scan {@code /leaderboards}, which serves Hypixel's classic
 * minigames — BedWars, SkyWars, Duels — and carries nothing about Skyblock at
 * all. So setting a key and a username downloaded a few hundred kilobytes every
 * ten minutes, found nothing, and displayed nothing, which is indistinguishable
 * from being broken because in every way that matters it was.
 *
 * There is no public Skyblock collection <em>leaderboard</em> in the API, so
 * there is no rank to be had from a key. What there is, is the number a rank
 * would be computed from: your birch collection total, on the profile you are
 * playing. That is what the key fetches now, and it is worth having — it is the
 * lifetime figure the in-game collection menu shows, without opening it.
 *
 * Your <em>rank</em> still comes from {@link com.birchmod.tracking.CollectionRankTracker},
 * which reads the collection leaderboard out of the game when you open it and
 * needs no key at all.
 *
 * <h2>When it cannot</h2>
 * Skyblock lets you switch collection data off for the API, per profile. When
 * it is off the profile comes back without a collection map, which is not an
 * error and has a specific fix, so it is reported as its own thing rather than
 * as a failure.
 */
public class CollectionApi {

    /**
     * Username to UUID.
     *
     * The current service first, the long-standing one as a fallback. The old
     * endpoint still answers but rate-limits hard, and a forager who sets their
     * name, sees nothing, and sets it again is exactly the traffic it punishes.
     */
    private static final String MOJANG_URL =
            "https://api.minecraftservices.com/minecraft/profile/lookup/name/";
    private static final String MOJANG_LEGACY_URL =
            "https://api.mojang.com/users/profiles/minecraft/";

    private static final String PROFILES_URL =
            "https://api.hypixel.net/v2/skyblock/profiles?uuid=";

    /**
     * Hypixel's key header.
     *
     * The key used to go on the query string, and this asked for it that way
     * long after Hypixel stopped accepting it.
     */
    private static final String KEY_HEADER = "API-Key";

    private static final long REFRESH_MINUTES = 10L;

    /**
     * Shortest gap between lookups triggered by the player.
     *
     * Setting a name or a key asks straight away rather than waiting out the
     * ten-minute timer, but a key pasted one character at a time should not
     * become a burst of requests at Mojang and Hypixel.
     */
    private static final long MIN_MANUAL_GAP_MS = 3_000L;

    private volatile long birchCollected = -1L;
    private volatile String profileName = "";
    private volatile String status = "set API key + name";
    private volatile long lastUpdate = 0L;

    private volatile String cachedUuid = null;
    private volatile String cachedName = null;
    private volatile long lastManualRefresh = 0L;

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "BirchOptimizer-Collection");
        t.setDaemon(true);
        return t;
    });

    public void start() {
        scheduler.scheduleAtFixedRate(this::refresh, 0L, REFRESH_MINUTES, TimeUnit.MINUTES);
    }

    /**
     * Look again now, because the player just changed something.
     *
     * Setting a name used to save it to disk and stop there, leaving the next
     * scheduled sweep — up to ten minutes away — to do anything with it. From
     * the player's side that is indistinguishable from a feature that does not
     * work.
     */
    public void refreshNow() {
        long now = System.currentTimeMillis();
        if (now - lastManualRefresh < MIN_MANUAL_GAP_MS) {
            return;
        }
        lastManualRefresh = now;
        status = "checking…";
        // Never on the calling thread: that is the client thread, and this
        // holds two blocking web requests.
        scheduler.execute(this::refresh);
    }

    private void refresh() {
        try {
            BirchConfig config = BirchConfig.get();
            String apiKey = config.hypixelApiKey == null ? "" : config.hypixelApiKey.trim();
            String name = config.playerName == null ? "" : config.playerName.trim();

            if (name.isBlank() && apiKey.isBlank()) {
                status = "set API key + name";
                return;
            }
            // Say which one is missing. "set API key + name" when only the key
            // is missing sends people to re-type a name that was already right.
            if (name.isBlank()) {
                status = "set your name";
                return;
            }
            if (apiKey.isBlank()) {
                status = "set your API key";
                return;
            }
            if (!looksLikeKey(apiKey)) {
                status = "API key looks wrong";
                return;
            }

            String uuid = resolveUuid(name);
            if (uuid == null) {
                return;   // resolveUuid has already said why.
            }

            HttpUtil.Response response =
                    HttpUtil.fetch(PROFILES_URL + uuid, KEY_HEADER, apiKey);
            if (!response.ok()) {
                status = describe(response, "API key rejected");
                return;
            }

            JsonObject root = JsonParser.parseString(response.body()).getAsJsonObject();
            if (!root.has("success") || !root.get("success").getAsBoolean()) {
                status = reasonFrom(root);
                return;
            }
            readProfiles(root, uuid);
            lastUpdate = System.currentTimeMillis();
        } catch (Throwable t) {
            // A scheduled task that throws is cancelled and never runs again,
            // so one bad response would freeze this for the whole session.
            status = "error";
        }
    }

    /**
     * Take the birch total off the profile being played.
     *
     * Collections are per profile, so the one you are on is the one that
     * matters — but if Hypixel has not marked a selection, the largest total
     * across profiles is a better guess than the first in the list.
     */
    void readProfiles(JsonObject root, String uuid) {
        JsonElement profilesEl = root.get("profiles");
        if (profilesEl == null || profilesEl.isJsonNull() || !profilesEl.isJsonArray()
                || profilesEl.getAsJsonArray().isEmpty()) {
            birchCollected = -1L;
            status = "no Skyblock profile";
            return;
        }

        long selected = -1L;
        String selectedName = "";
        long best = -1L;
        String bestName = "";
        boolean sawMember = false;

        for (JsonElement element : profilesEl.getAsJsonArray()) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject profile = element.getAsJsonObject();
            JsonObject members = profile.getAsJsonObject("members");
            if (members == null || !members.has(uuid) || !members.get(uuid).isJsonObject()) {
                continue;
            }
            sawMember = true;

            long birch = birchIn(members.getAsJsonObject(uuid));
            if (birch < 0L) {
                continue;   // this profile is not sharing collections
            }
            String cute = profile.has("cute_name")
                    ? profile.get("cute_name").getAsString() : "";

            if (profile.has("selected") && profile.get("selected").getAsBoolean()) {
                selected = birch;
                selectedName = cute;
            }
            if (birch > best) {
                best = birch;
                bestName = cute;
            }
        }

        if (selected >= 0L) {
            birchCollected = selected;
            profileName = selectedName;
            status = "ok";
        } else if (best >= 0L) {
            birchCollected = best;
            profileName = bestName;
            status = "ok";
        } else {
            birchCollected = -1L;
            // A real profile that will not say. This has a specific fix and is
            // worth naming, because it looks exactly like a broken key.
            status = sawMember ? "collections not shared" : "not on that profile";
        }
    }

    /** Birch in one member's collection map, or -1 if it is not being shared. */
    static long birchIn(JsonObject member) {
        JsonElement collection = member.get("collection");
        if (collection == null || !collection.isJsonObject()) {
            return -1L;
        }
        JsonObject map = collection.getAsJsonObject();
        JsonElement birch = map.get(BazaarManager.BIRCH_PRODUCT);
        if (birch == null || !birch.isJsonPrimitive()) {
            // Sharing collections but has never cut birch: nothing collected,
            // which is a real answer and not a missing one.
            return 0L;
        }
        try {
            return Math.max(0L, birch.getAsLong());
        } catch (Exception ignored) {
            return 0L;
        }
    }

    /**
     * Whether this could be a Hypixel key at all.
     *
     * They are UUIDs. Catching a pasted username, a truncated key or a stray
     * quote here turns a silent rejection ten minutes later into an answer now.
     */
    public static boolean looksLikeKey(String key) {
        if (key == null) {
            return false;
        }
        String bare = key.replace("-", "");
        if (bare.length() != 32) {
            return false;
        }
        for (int i = 0; i < bare.length(); i++) {
            char c = Character.toLowerCase(bare.charAt(i));
            boolean hex = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f');
            if (!hex) {
                return false;
            }
        }
        return true;
    }

    /**
     * Turn a failed request into something the player can act on.
     *
     * The codes are what Hypixel really answers: a missing key is a 400 and an
     * unusable one is a 403, both with a {@code cause} in the body.
     */
    public static String describe(HttpUtil.Response response, String rejected) {
        if (response.unreachable()) {
            return "no connection";
        }
        if (response.unauthorised() || response.status() == 400) {
            return rejected;
        }
        if (response.rateLimited()) {
            return "rate limited, retrying";
        }
        if (response.notFound()) {
            return "not found";
        }
        return "api error " + response.status();
    }

    /** Hypixel's own explanation, when it gives one. */
    private static String reasonFrom(JsonObject root) {
        if (root.has("cause") && root.get("cause").isJsonPrimitive()) {
            String cause = root.get("cause").getAsString();
            if (cause != null && !cause.isBlank()) {
                return cause.length() > 40 ? cause.substring(0, 40) : cause;
            }
        }
        return "api error";
    }

    /**
     * The player's UUID, or null with {@link #status} explaining why not.
     *
     * Both Mojang endpoints are tried, because the newer one is occasionally
     * unavailable and the older one is aggressively rate-limited, and being
     * told "no such player" when the real answer is "Mojang asked us to slow
     * down" sends people off to check a username that was never wrong.
     */
    private String resolveUuid(String name) {
        if (cachedUuid != null && name.equalsIgnoreCase(cachedName)) {
            return cachedUuid;
        }

        HttpUtil.Response response = HttpUtil.fetch(MOJANG_URL + name, null, null);
        if (!response.ok() && !response.notFound()) {
            response = HttpUtil.fetch(MOJANG_LEGACY_URL + name, null, null);
        }

        if (!response.ok()) {
            status = response.notFound() ? "no such player" : describe(response, "mojang refused");
            return null;
        }

        try {
            JsonObject obj = JsonParser.parseString(response.body()).getAsJsonObject();
            if (obj.has("id")) {
                cachedUuid = normalize(obj.get("id").getAsString());
                cachedName = name;
                return cachedUuid;
            }
        } catch (Exception ignored) {
            // Fall through: a body we cannot read is as good as no answer.
        }
        status = "no such player";
        return null;
    }

    private static String normalize(String uuid) {
        return uuid.replace("-", "").toLowerCase(java.util.Locale.ROOT);
    }

    // ---- Queries ----

    /** Lifetime birch on the profile you are playing, or -1 if unknown. */
    public long getBirchCollected() {
        return birchCollected;
    }

    /** Which profile that figure came from, e.g. "Mango". */
    public String getProfileName() {
        return profileName;
    }

    public boolean hasCollection() {
        return birchCollected >= 0L;
    }

    public String getStatus() {
        return status;
    }

    public long getLastUpdate() {
        return lastUpdate;
    }

    /** Forget the resolved UUID, e.g. when the username changes. */
    public void forgetPlayer() {
        cachedUuid = null;
        cachedName = null;
    }
}
