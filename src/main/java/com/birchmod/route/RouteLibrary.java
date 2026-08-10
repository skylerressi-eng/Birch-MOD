package com.birchmod.route;

import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import net.fabricmc.loader.api.FabricLoader;

/**
 * The player's saved foraging loops, and the arithmetic for judging them.
 *
 * <h2>Scoring</h2>
 * A loop's value is trees per second, and the limit is whichever is slower:
 * walking the lap, or waiting for the trees to come back.
 *
 * <pre>
 *   lapTime   = loopDistance / walkSpeed
 *   cycleTime = max(lapTime, regenSeconds)
 *   score     = stops / cycleTime
 * </pre>
 *
 * That second term is the part people get wrong by hand. A tight five-tree loop
 * you can lap in fifteen seconds is not five trees per fifteen seconds if birch
 * takes sixty to regrow — you arrive to bare stumps and the extra laps are
 * wasted. Adding stops helps precisely until the lap is long enough to cover
 * the regen, and stops helping after that.
 */
public final class RouteLibrary {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String FILE_NAME = "birchoptimizer-routes.json";

    /** Rough Skyblock travel speed in blocks/second. */
    public static final double WALK_BLOCKS_PER_SECOND = 7.0;

    /** A recorded loop needs at least this many stops to be worth saving. */
    public static final int MIN_STOPS = 3;

    /** Serialised shape of the file. */
    private static final class Store {
        Map<String, RecordedRoute> routes = new LinkedHashMap<>();
        String active = null;
    }

    private static Store store = new Store();

    private RouteLibrary() {
    }

    /** How a route is expected to perform, given the measured regen time. */
    public record Score(String name, int stops, double loopDistance,
                        double lapSeconds, double cycleSeconds, double treesPerMinute) {
    }

    public static Score score(RecordedRoute route, double regenSeconds) {
        int stops = route.size();
        double distance = route.loopDistance();
        double lap = distance / WALK_BLOCKS_PER_SECOND;
        // You cannot lap faster than the trees come back.
        double cycle = Math.max(lap, Math.max(regenSeconds, 0.001));
        double perMinute = stops / cycle * 60.0;
        return new Score(route.name, stops, distance, lap, cycle, perMinute);
    }

    /**
     * Order routes best-first.
     *
     * Throughput alone leaves ties: once regen caps the cycle, a tight loop and
     * a sprawling one with the same number of stops score identically, even
     * though the sprawling one spends every spare second walking and leaves no
     * margin when you misjudge a swing. Equal throughput therefore prefers the
     * shorter lap.
     */
    public static Comparator<RecordedRoute> ranking(double regenSeconds) {
        return (a, b) -> {
            Score sa = score(a, regenSeconds);
            Score sb = score(b, regenSeconds);
            if (Math.abs(sa.treesPerMinute() - sb.treesPerMinute()) > 1.0e-6) {
                return Double.compare(sb.treesPerMinute(), sa.treesPerMinute());
            }
            return Double.compare(sa.lapSeconds(), sb.lapSeconds());
        };
    }

    /** The saved route with the highest expected throughput. */
    public static RecordedRoute best(double regenSeconds) {
        RecordedRoute best = null;
        Comparator<RecordedRoute> ranking = ranking(regenSeconds);
        for (RecordedRoute route : store.routes.values()) {
            if (route.size() < MIN_STOPS) {
                continue;
            }
            if (best == null || ranking.compare(route, best) < 0) {
                best = route;
            }
        }
        return best;
    }

    // ---- Storage ----

    public static void save(RecordedRoute route) {
        store.routes.put(key(route.name), route);
        persist();
    }

    public static RecordedRoute get(String name) {
        return store.routes.get(key(name));
    }

    public static boolean delete(String name) {
        boolean removed = store.routes.remove(key(name)) != null;
        if (removed && key(name).equals(store.active)) {
            store.active = null;
        }
        persist();
        return removed;
    }

    public static List<RecordedRoute> all() {
        return new ArrayList<>(store.routes.values());
    }

    public static boolean exists(String name) {
        return store.routes.containsKey(key(name));
    }

    // ---- Active selection ----

    public static void setActive(String name) {
        store.active = name == null ? null : key(name);
        persist();
    }

    public static RecordedRoute getActive() {
        return store.active == null ? null : store.routes.get(store.active);
    }

    public static String getActiveName() {
        RecordedRoute active = getActive();
        return active == null ? null : active.name;
    }

    public static void clearActive() {
        store.active = null;
        persist();
    }

    private static String key(String name) {
        return name == null ? "" : name.toLowerCase(Locale.ROOT);
    }

    // ---- Persistence ----

    private static Path path() {
        return FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME);
    }

    public static void load() {
        Path file = path();
        try {
            if (Files.exists(file)) {
                try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                    Store loaded = GSON.fromJson(reader, Store.class);
                    if (loaded != null) {
                        store = loaded;
                        if (store.routes == null) {
                            store.routes = new LinkedHashMap<>();
                        }
                    }
                }
            }
        } catch (Exception e) {
            store = new Store();
        }
    }

    public static void persist() {
        Path file = path();
        try {
            Files.createDirectories(file.getParent());
            try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                GSON.toJson(store, writer);
            }
        } catch (Exception ignored) {
            // Losing a route is not worth crashing the client over.
        }
    }
}
