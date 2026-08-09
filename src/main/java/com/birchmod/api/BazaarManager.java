package com.birchmod.api;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import com.birchmod.config.BirchConfig;
import com.birchmod.util.HttpUtil;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Polls the public Hypixel Bazaar API every 10 minutes. No API key required.
 *
 * Birch can be sold in more than one form, so several related products are
 * fetched and the best per-log payout is worked out. Coin projections are
 * tax-aware: Skyblock takes a cut on sell orders, so gross prices overstate
 * real income.
 */
public class BazaarManager {

    private static final String BAZAAR_URL = "https://api.hypixel.net/skyblock/bazaar";
    private static final long REFRESH_MINUTES = 10L;

    /** Products worth pricing, mapped to how many birch logs one unit is worth. */
    private static final Map<String, Double> RELATED_PRODUCTS = Map.of(
            "BIRCH_LOG", 1.0,
            "ENCHANTED_BIRCH_LOG", 160.0);

    /** One product's live quote. */
    public record Quote(String productId, double buyPrice, double sellPrice) {
        /** Spread between insta-buy and insta-sell, as a fraction of buy price. */
        public double spread() {
            return buyPrice > 0.0 ? (buyPrice - sellPrice) / buyPrice : 0.0;
        }
    }

    private final Map<String, Quote> quotes = new LinkedHashMap<>();
    private volatile long lastUpdate = 0L;
    private volatile String status = "loading";

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "BirchOptimizer-Bazaar");
        t.setDaemon(true);
        return t;
    });

    public void start() {
        scheduler.scheduleAtFixedRate(this::refresh, 0L, REFRESH_MINUTES, TimeUnit.MINUTES);
    }

    private void refresh() {
        try {
            String body = HttpUtil.get(BAZAAR_URL);
            if (body == null) {
                status = "offline";
                return;
            }
            JsonObject root = JsonParser.parseString(body).getAsJsonObject();
            if (!root.has("success") || !root.get("success").getAsBoolean()) {
                status = "api error";
                return;
            }
            JsonObject products = root.getAsJsonObject("products");
            if (products == null) {
                status = "api error";
                return;
            }

            Map<String, Quote> fresh = new LinkedHashMap<>();

            // Always price whatever the config points at, plus the known relatives.
            String configured = BirchConfig.get().bazaarProductId;
            parseInto(products, configured, fresh);
            for (String id : RELATED_PRODUCTS.keySet()) {
                parseInto(products, id, fresh);
            }

            if (fresh.isEmpty()) {
                status = "product not found";
                return;
            }

            synchronized (quotes) {
                quotes.clear();
                quotes.putAll(fresh);
            }
            lastUpdate = System.currentTimeMillis();
            status = "ok";
        } catch (Exception e) {
            status = "error";
        }
    }

    private void parseInto(JsonObject products, String id, Map<String, Quote> out) {
        if (id == null || out.containsKey(id) || !products.has(id)) {
            return;
        }
        JsonObject status = products.getAsJsonObject(id).getAsJsonObject("quick_status");
        if (status == null) {
            return;
        }
        double buy = status.has("buyPrice") ? status.get("buyPrice").getAsDouble() : -1.0;
        double sell = status.has("sellPrice") ? status.get("sellPrice").getAsDouble() : -1.0;
        out.put(id, new Quote(id, buy, sell));
    }

    // ---- Queries ----

    public Quote getQuote(String productId) {
        synchronized (quotes) {
            return quotes.get(productId);
        }
    }

    /** The quote for the configured product. */
    public Quote getPrimaryQuote() {
        return getQuote(BirchConfig.get().bazaarProductId);
    }

    /** Raw price the HUD shows for the primary product, per buy/sell setting. */
    public double getDisplayPrice() {
        Quote quote = getPrimaryQuote();
        if (quote == null) {
            return -1.0;
        }
        return BirchConfig.get().showBuyPrice ? quote.buyPrice() : quote.sellPrice();
    }

    /**
     * What one birch log actually nets after Bazaar tax, taking the best of the
     * tracked products (raw logs vs. enchanted, per-log normalised).
     */
    public double getBestNetPerLog() {
        double best = -1.0;
        synchronized (quotes) {
            for (Quote quote : quotes.values()) {
                Double logsPerUnit = RELATED_PRODUCTS.get(quote.productId());
                if (logsPerUnit == null || logsPerUnit <= 0.0) {
                    continue;
                }
                // Selling means taking the insta-sell price.
                double perLog = quote.sellPrice() / logsPerUnit;
                if (perLog > best) {
                    best = perLog;
                }
            }
        }
        return best > 0.0 ? applyTax(best) : -1.0;
    }

    /** Which product currently gives the best per-log payout. */
    public String getBestProductId() {
        double best = -1.0;
        String bestId = null;
        synchronized (quotes) {
            for (Quote quote : quotes.values()) {
                Double logsPerUnit = RELATED_PRODUCTS.get(quote.productId());
                if (logsPerUnit == null || logsPerUnit <= 0.0) {
                    continue;
                }
                double perLog = quote.sellPrice() / logsPerUnit;
                if (perLog > best) {
                    best = perLog;
                    bestId = quote.productId();
                }
            }
        }
        return bestId;
    }

    /** Apply the configured Bazaar tax to a gross amount. */
    public double applyTax(double gross) {
        BirchConfig config = BirchConfig.get();
        if (!config.applyBazaarTax) {
            return gross;
        }
        return gross * (1.0 - config.bazaarTaxRate);
    }

    public boolean hasData() {
        return lastUpdate > 0L && getDisplayPrice() >= 0.0;
    }

    public String getStatus() {
        return status;
    }

    public long getLastUpdate() {
        return lastUpdate;
    }

    /** Minutes since the last successful refresh, or -1 if never. */
    public long getMinutesSinceUpdate() {
        if (lastUpdate <= 0L) {
            return -1L;
        }
        return (System.currentTimeMillis() - lastUpdate) / 60_000L;
    }
}
