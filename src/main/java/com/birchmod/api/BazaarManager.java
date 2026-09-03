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

    private static final String BAZAAR_URL = "https://api.hypixel.net/v2/skyblock/bazaar";
    private static final long REFRESH_MINUTES = 10L;

    /**
     * Raw birch wood, as the Bazaar actually names it.
     *
     * Not {@code BIRCH_LOG}, which is what this asked for and which does not
     * exist — the Bazaar has no such product, so every lookup missed, the price
     * row read "product not found" forever, and coins per hour was left working
     * off enchanted birch alone. Skyblock still uses pre-1.13 item ids here, so
     * the woods are one id with a data value after it: {@code LOG} is oak,
     * {@code LOG:1} spruce, {@code LOG:2} birch.
     */
    public static final String BIRCH_PRODUCT = "LOG:2";

    /** Enchanted birch, and how many raw logs go into one. */
    public static final String ENCHANTED_BIRCH_PRODUCT = "ENCHANTED_BIRCH_LOG";

    /** Products worth pricing, mapped to how many birch logs one unit is worth. */
    private static final Map<String, Double> RELATED_PRODUCTS = Map.of(
            BIRCH_PRODUCT, 1.0,
            ENCHANTED_BIRCH_PRODUCT, 160.0);

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
        } catch (Throwable t) {
            // A scheduled task that throws is cancelled and never runs again, so
            // one bad response would leave prices frozen for the whole session.
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
                double logsPerUnit = logsPerUnit(quote.productId());
                if (logsPerUnit <= 0.0) {
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
                double logsPerUnit = logsPerUnit(quote.productId());
                if (logsPerUnit <= 0.0) {
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

    /**
     * How many birch logs one unit of a product is worth.
     *
     * A product the player configured that is not one of the known relatives
     * was priced on the HUD but silently skipped when working out coins per
     * hour, so the two disagreed. An unknown product counts as one log per
     * unit, which is right for any raw log and at least keeps the figures
     * consistent with what is being displayed.
     */
    private double logsPerUnit(String productId) {
        Double known = RELATED_PRODUCTS.get(productId);
        if (known != null) {
            return known;
        }
        return productId != null && productId.equals(BirchConfig.get().bazaarProductId) ? 1.0 : 0.0;
    }

    /**
     * A name for a product id, since the ids are not readable.
     *
     * "LOG:2" tells nobody it means birch wood, and it is the one people have
     * to recognise to know the price on their screen is the right one.
     */
    public static String friendlyName(String productId) {
        if (BIRCH_PRODUCT.equals(productId)) {
            return "Birch Wood";
        }
        if (ENCHANTED_BIRCH_PRODUCT.equals(productId)) {
            return "Enchanted Birch Wood";
        }
        return productId == null ? "?" : productId;
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
