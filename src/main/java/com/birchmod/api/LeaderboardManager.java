package com.birchmod.api;

import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import com.birchmod.config.BirchConfig;
import com.birchmod.util.HttpUtil;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Your standing on Hypixel's public leaderboards, refreshed every 10 minutes
 * and immediately whenever you change your name or key.
 *
 * <h2>What this can and cannot tell you</h2>
 * Hypixel's public API serves leaderboards for its classic minigames. It has
 * nothing for Skyblock collections, so no key and no username will ever produce
 * a birch rank from here — most foragers will correctly read "not on a Hypixel
 * board" forever. Your birch rank comes from
 * {@link com.birchmod.tracking.CollectionRankTracker}, which reads the
 * collection leaderboard out of the game when you open it and needs no key at
 * all. {@code /birch rank} says which is which, because a blank rank with no
 * explanation reads as a broken lookup rather than a question the API cannot
 * answer.
 *
 * <h2>Saying why</h2>
 * Every failure used to arrive as "api error", which covers a rejected key, a
 * misspelled username, Mojang rate-limiting and the network being down — four
 * problems with four different fixes. Each is now named.
 */
public class LeaderboardManager {

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

    private static final String LEADERBOARDS_URL = "https://api.hypixel.net/leaderboards";

    /**
     * Hypixel's key header.
     *
     * The key used to go on the query string, and this asked for it that way
     * long after Hypixel stopped accepting it — so a perfectly good key came
     * back 403 and was reported as a vague "api error".
     */
    private static final String KEY_HEADER = "API-Key";

    private static final long REFRESH_MINUTES = 10L;

    /**
     * Shortest gap between lookups triggered by the player.
     *
     * Setting a name or a key asks straight away rather than waiting out the
     * ten-minute timer — which is the whole reason entering a name appeared to
     * do nothing — but a key pasted one character at a time should not become
     * a burst of requests at Mojang and Hypixel.
     */
    private static final long MIN_MANUAL_GAP_MS = 3_000L;

    private volatile int rank = -1;
    private volatile String rankTitle = "";
    private volatile String status = "not configured";
    private volatile long lastUpdate = 0L;

    private volatile String cachedUuid = null;
    private volatile String cachedName = null;
    private volatile long lastManualRefresh = 0L;

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "BirchOptimizer-Leaderboard");
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
     * scheduled sweep — up to ten minutes away — to do anything with it. The
     * command even said so, and it read as the feature being broken, because
     * from the player's side it is indistinguishable from one.
     */
    public void refreshNow() {
        long now = System.currentTimeMillis();
        if (now - lastManualRefresh < MIN_MANUAL_GAP_MS) {
            return;
        }
        lastManualRefresh = now;
        status = "checking…";
        // Never on the calling thread: this is the client thread, and it holds
        // two blocking web requests.
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
                return; // resolveUuid has already said why.
            }

            HttpUtil.Response response = HttpUtil.fetch(LEADERBOARDS_URL, KEY_HEADER, apiKey);
            if (!response.ok()) {
                status = describe(response, "rejected your API key");
                return;
            }
            JsonObject root = JsonParser.parseString(response.body()).getAsJsonObject();
            if (!root.has("success") || !root.get("success").getAsBoolean()) {
                status = reasonFrom(root);
                return;
            }

            int bestRank = Integer.MAX_VALUE;
            String bestTitle = "";

            JsonObject leaderboards = root.getAsJsonObject("leaderboards");
            for (Map.Entry<String, JsonElement> game : leaderboards.entrySet()) {
                if (!game.getValue().isJsonArray()) {
                    continue;
                }
                for (JsonElement boardEl : game.getValue().getAsJsonArray()) {
                    JsonObject board = boardEl.getAsJsonObject();
                    if (!board.has("leaders")) {
                        continue;
                    }
                    JsonArray leaders = board.getAsJsonArray("leaders");
                    for (int i = 0; i < leaders.size(); i++) {
                        if (normalize(leaders.get(i).getAsString()).equals(uuid)) {
                            if (i + 1 < bestRank) {
                                bestRank = i + 1;
                                bestTitle = titleOf(board, game.getKey());
                            }
                            break;
                        }
                    }
                }
            }

            if (bestRank == Integer.MAX_VALUE) {
                rank = -1;
                rankTitle = "";
                // Not a failure, and worth saying plainly: these are Hypixel's
                // classic minigame boards. There is no public Skyblock
                // foraging leaderboard to be on, so the honest answer is that
                // your birch rank does not come from here — it comes from
                // opening the collection leaderboard in game.
                status = "not on a Hypixel board";
            } else {
                rank = bestRank;
                rankTitle = bestTitle;
                status = "ok";
            }
            lastUpdate = System.currentTimeMillis();
        } catch (Throwable t) {
            // A scheduled task that throws is cancelled and never runs again, so
            // one bad response would leave the rank frozen for the whole session.
            status = "error";
        }
    }

    /**
     * Whether this could be a Hypixel key at all.
     *
     * They are UUIDs. Catching a pasted username, a truncated key or a stray
     * quote here turns a silent 403 ten minutes later into an answer now.
     */
    public static boolean looksLikeKey(String key) {
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

    /** Turn a failed request into something the player can act on. */
    static String describe(HttpUtil.Response response, String rejected) {
        if (response.unreachable()) {
            return "no connection";
        }
        if (response.unauthorised()) {
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
        if (root.has("cause")) {
            String cause = root.get("cause").getAsString();
            if (cause != null && !cause.isBlank()) {
                return cause.length() > 40 ? cause.substring(0, 40) : cause;
            }
        }
        return "api error";
    }

    private String titleOf(JsonObject board, String fallback) {
        if (board.has("title")) {
            return board.get("title").getAsString();
        }
        if (board.has("prefix")) {
            return board.get("prefix").getAsString();
        }
        return fallback;
    }

    /**
     * The player's UUID, or null with {@link #status} explaining why not.
     *
     * Both Mojang endpoints are tried, because the newer one is occasionally
     * unavailable and the older one is aggressively rate-limited, and being
     * told "unknown player" when the real answer is "Mojang asked us to slow
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

    private String normalize(String uuid) {
        return uuid.replace("-", "").toLowerCase();
    }

    public int getRank() {
        return rank;
    }

    public String getRankTitle() {
        return rankTitle;
    }

    public String getStatus() {
        return status;
    }

    public boolean hasRank() {
        return rank > 0;
    }

    public long getLastUpdate() {
        return lastUpdate;
    }
}
