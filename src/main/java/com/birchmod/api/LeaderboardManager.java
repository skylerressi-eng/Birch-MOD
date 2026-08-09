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
 * Looks up the player's leaderboard ranking every 10 minutes.
 *
 * Hypixel exposes no dedicated "birch" leaderboard, so this resolves the
 * player's UUID and scans the public leaderboards, reporting the best (lowest)
 * position found. Point {@link #refresh()} at a specific board once the exact
 * ranking to track is decided.
 *
 * Requires a Hypixel API key and username in the config.
 */
public class LeaderboardManager {

    private static final String MOJANG_URL = "https://api.mojang.com/users/profiles/minecraft/";
    private static final String LEADERBOARDS_URL = "https://api.hypixel.net/leaderboards?key=";
    private static final long REFRESH_MINUTES = 10L;

    private volatile int rank = -1;
    private volatile String rankTitle = "";
    private volatile String status = "not configured";
    private volatile long lastUpdate = 0L;

    private volatile String cachedUuid = null;
    private volatile String cachedName = null;

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "BirchOptimizer-Leaderboard");
        t.setDaemon(true);
        return t;
    });

    public void start() {
        scheduler.scheduleAtFixedRate(this::refresh, 0L, REFRESH_MINUTES, TimeUnit.MINUTES);
    }

    private void refresh() {
        try {
            BirchConfig config = BirchConfig.get();
            String apiKey = config.hypixelApiKey;
            String name = config.playerName;

            if (apiKey == null || apiKey.isBlank() || name == null || name.isBlank()) {
                status = "set API key + name";
                return;
            }

            String uuid = resolveUuid(name.trim());
            if (uuid == null) {
                status = "unknown player";
                return;
            }

            String body = HttpUtil.get(LEADERBOARDS_URL + apiKey.trim());
            if (body == null) {
                status = "api error";
                return;
            }
            JsonObject root = JsonParser.parseString(body).getAsJsonObject();
            if (!root.has("success") || !root.get("success").getAsBoolean()) {
                status = "api error";
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
                status = "unranked";
            } else {
                rank = bestRank;
                rankTitle = bestTitle;
                status = "ok";
            }
            lastUpdate = System.currentTimeMillis();
        } catch (Exception e) {
            status = "error";
        }
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

    private String resolveUuid(String name) {
        if (cachedUuid != null && name.equalsIgnoreCase(cachedName)) {
            return cachedUuid;
        }
        String body = HttpUtil.get(MOJANG_URL + name);
        if (body == null) {
            return null;
        }
        try {
            JsonObject obj = JsonParser.parseString(body).getAsJsonObject();
            if (obj.has("id")) {
                cachedUuid = normalize(obj.get("id").getAsString());
                cachedName = name;
                return cachedUuid;
            }
        } catch (Exception ignored) {
        }
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
