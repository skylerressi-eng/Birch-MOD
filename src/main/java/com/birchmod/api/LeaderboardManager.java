package com.birchmod.api;

import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
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
 * Hypixel does not expose a single "birch" leaderboard, so v1 resolves the
 * player's UUID and scans the public leaderboards, reporting the best (lowest)
 * position it finds. Once the exact board to track is decided, point
 * {@link #refresh()} at that specific source instead.
 *
 * Requires a Hypixel API key and the player's username (see config).
 */
public class LeaderboardManager {

    private static final String MOJANG_URL = "https://api.mojang.com/users/profiles/minecraft/";
    private static final String LEADERBOARDS_URL = "https://api.hypixel.net/leaderboards?key=";
    private static final long REFRESH_MINUTES = 10L;

    private volatile int rank = -1;            // best position found (1-based); -1 = unknown
    private volatile String rankTitle = "";    // which leaderboard that rank is on
    private volatile String status = "not configured";
    private volatile long lastUpdate = 0L;

    private volatile String cachedUuid = null;
    private volatile String cachedName = null;

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(new ThreadFactory() {
        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "BirchOptimizer-Leaderboard");
            t.setDaemon(true);
            return t;
        }
    });

    public void start() {
        scheduler.scheduleAtFixedRate(new Runnable() {
            @Override
            public void run() {
                refresh();
            }
        }, 0L, REFRESH_MINUTES, TimeUnit.MINUTES);
    }

    private void refresh() {
        try {
            String apiKey = BirchConfig.hypixelApiKey;
            String name = BirchConfig.playerName;
            if (apiKey == null || apiKey.trim().isEmpty() || name == null || name.trim().isEmpty()) {
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
            JsonObject root = new JsonParser().parse(body).getAsJsonObject();
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
                        String leaderUuid = normalize(leaders.get(i).getAsString());
                        if (leaderUuid.equals(uuid)) {
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
            JsonObject obj = new JsonParser().parse(body).getAsJsonObject();
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
