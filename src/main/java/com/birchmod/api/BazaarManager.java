package com.birchmod.api;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

import com.birchmod.config.BirchConfig;
import com.birchmod.util.HttpUtil;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Polls the public Hypixel Bazaar API for the tracked product (Birch Wood by
 * default) every 10 minutes. No API key is required for Bazaar data.
 */
public class BazaarManager {

    private static final String BAZAAR_URL = "https://api.hypixel.net/skyblock/bazaar";
    private static final long REFRESH_MINUTES = 10L;

    private volatile double buyPrice = -1.0;   // insta-buy price
    private volatile double sellPrice = -1.0;  // insta-sell price
    private volatile long lastUpdate = 0L;

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(new ThreadFactory() {
        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "BirchOptimizer-Bazaar");
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
            String body = HttpUtil.get(BAZAAR_URL);
            if (body == null) {
                return;
            }
            JsonObject root = new JsonParser().parse(body).getAsJsonObject();
            if (!root.has("success") || !root.get("success").getAsBoolean()) {
                return;
            }
            JsonObject products = root.getAsJsonObject("products");
            String id = BirchConfig.bazaarProductId;
            if (products == null || !products.has(id)) {
                return;
            }
            JsonObject status = products.getAsJsonObject(id).getAsJsonObject("quick_status");
            if (status == null) {
                return;
            }
            if (status.has("buyPrice")) {
                buyPrice = status.get("buyPrice").getAsDouble();
            }
            if (status.has("sellPrice")) {
                sellPrice = status.get("sellPrice").getAsDouble();
            }
            lastUpdate = System.currentTimeMillis();
        } catch (Exception ignored) {
            // Leave last-known values in place on failure.
        }
    }

    public double getBuyPrice() {
        return buyPrice;
    }

    public double getSellPrice() {
        return sellPrice;
    }

    /** The price the HUD should display, per config (buy vs sell). */
    public double getDisplayPrice() {
        return BirchConfig.showBuyPrice ? buyPrice : sellPrice;
    }

    public boolean hasData() {
        return lastUpdate > 0L && getDisplayPrice() >= 0.0;
    }

    public long getLastUpdate() {
        return lastUpdate;
    }
}
