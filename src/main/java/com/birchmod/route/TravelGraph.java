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
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;

/**
 * What actually happens when you forage, remembered between sessions.
 *
 * <h2>Why this exists</h2>
 * Every route decision used to be made from straight-line distance divided by
 * one global walking speed. That model does not describe the Park. It cannot
 * see the hill you climb between two trees, the fence you go around, the tree
 * that takes four swings instead of one, or the corner where you always stop to
 * pick up drops. Two legs of identical length routinely differ by several
 * seconds, and a plan built on the wrong one of them is wrong everywhere.
 *
 * So the mod stops guessing and measures. Each time a tree is felled, the time
 * since the previous tree was felled is recorded against that ordered pair.
 * That figure is exactly the quantity route planning needs — the whole cost of
 * taking B after A, walking and chopping together — rather than a proxy for it.
 *
 * <h2>Identity</h2>
 * A trunk's base is re-detected a block or two off after it regrows, so
 * positions are matched to existing nodes by proximity rather than equality.
 * Without that every regrowth would mint a fresh node and no leg would ever
 * gather enough samples to be trusted.
 */
public final class TravelGraph {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String FILE_NAME = "birchoptimizer-travel.json";

    /**
     * Positions this close are the same tree.
     *
     * Deliberately the same figure the recorder and the optimizer use for
     * position identity. A graph that merged more loosely than they do would
     * hand back leg times for a pair of trees they still consider distinct,
     * and one that merged more tightly would mint a fresh node on every
     * regrowth and never gather enough samples to be useful.
     */
    private static final double MERGE_DISTANCE = RecordedRoute.SAME_TREE_DISTANCE;

    /** Observations before a leg time is trusted over the distance estimate. */
    public static final int MIN_LEG_SAMPLES = 3;

    /**
     * Longer than this and you did something else in between — read chat, sold
     * to the Bazaar, walked off the island — so it says nothing about the leg.
     */
    private static final double MAX_LEG_SECONDS = 90.0;

    /** Shorter than this is a double-count, not a journey. */
    private static final double MIN_LEG_SECONDS = 0.2;

    /** Weight given to each new observation in the running mean. */
    private static final double SMOOTHING = 0.3;

    /** Keep the file bounded; the least-used trees are dropped first. */
    private static final int MAX_NODES = 512;
    private static final int MAX_LEGS = 4096;

    private static final long SAVE_INTERVAL_MS = 30_000L;

    /** One tree, and how much of your attention it has actually had. */
    public static final class Node {
        public int x;
        public int y;
        public int z;
        /** How many times you have felled this tree. */
        public int chops;

        public Node() {
        }

        Node(int x, int y, int z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }
    }

    /** One ordered pair of trees, and how long that hop really takes. */
    public static final class Leg {
        public int count;
        public double meanSeconds;

        public Leg() {
        }
    }

    private static final class Store {
        Map<String, Node> nodes = new LinkedHashMap<>();
        Map<String, Leg> legs = new LinkedHashMap<>();
    }

    private static Store store = new Store();

    /** Written off the client thread; nobody is waiting on the disk. */
    private static final ExecutorService IO = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "BirchOptimizer-Travel");
        t.setDaemon(true);
        return t;
    });

    private static String lastNode = null;
    private static long lastChopAt = 0L;
    private static long lastSave = 0L;

    private TravelGraph() {
    }

    // ---- Recording ----

    /**
     * A tree was fully felled. Credits the tree and, when it follows another
     * closely enough to be the same run of foraging, times the leg between them.
     */
    public static synchronized void onTreeChopped(BlockPos base) {
        if (base == null) {
            return;
        }
        long now = System.currentTimeMillis();
        String id = nodeFor(base);
        Node node = store.nodes.get(id);
        if (node != null) {
            node.chops++;
        }

        if (lastNode != null && !lastNode.equals(id)) {
            double seconds = (now - lastChopAt) / 1000.0;
            if (seconds >= MIN_LEG_SECONDS && seconds <= MAX_LEG_SECONDS) {
                recordLeg(lastNode, id, seconds);
            }
        }

        lastNode = id;
        lastChopAt = now;

        if (now - lastSave >= SAVE_INTERVAL_MS) {
            lastSave = now;
            persistAsync();
        }
    }

    /**
     * Forget where we were in the chain.
     *
     * Called when the world changes under us. Timing the gap across a warp
     * would fold a loading screen into a leg and poison it.
     */
    public static synchronized void breakChain() {
        lastNode = null;
        lastChopAt = 0L;
    }

    private static void recordLeg(String from, String to, double seconds) {
        String key = from + ">" + to;
        Leg leg = store.legs.get(key);
        if (leg == null) {
            if (store.legs.size() >= MAX_LEGS) {
                return;
            }
            leg = new Leg();
            leg.count = 1;
            leg.meanSeconds = seconds;
            store.legs.put(key, leg);
            return;
        }
        leg.count++;
        // Weighted toward recent runs: as gear and speed improve, the old
        // figures describe a player who no longer exists.
        leg.meanSeconds = leg.meanSeconds * (1.0 - SMOOTHING) + seconds * SMOOTHING;
    }

    // ---- Identity ----

    /** The id of the node at this position, creating one if it is new. */
    private static String nodeFor(BlockPos pos) {
        String existing = canonical(pos.getX(), pos.getY(), pos.getZ());
        if (existing != null) {
            return existing;
        }
        evictIfFull();
        String key = key(pos.getX(), pos.getY(), pos.getZ());
        store.nodes.put(key, new Node(pos.getX(), pos.getY(), pos.getZ()));
        return key;
    }

    /** The id of the known node nearest this position, or null if none is close. */
    public static synchronized String canonical(int x, int y, int z) {
        double bestDistSq = MERGE_DISTANCE * MERGE_DISTANCE;
        String best = null;

        for (Map.Entry<String, Node> entry : store.nodes.entrySet()) {
            Node node = entry.getValue();
            double dx = node.x - x;
            double dy = node.y - y;
            double dz = node.z - z;
            double distSq = dx * dx + dy * dy + dz * dz;
            if (distSq <= bestDistSq) {
                bestDistSq = distSq;
                best = entry.getKey();
            }
        }
        return best;
    }

    private static void evictIfFull() {
        if (store.nodes.size() < MAX_NODES) {
            return;
        }
        // Drop the trees you barely touch before the ones you work every lap.
        List<Map.Entry<String, Node>> byUse = new ArrayList<>(store.nodes.entrySet());
        byUse.sort(Comparator.comparingInt(e -> e.getValue().chops));

        int drop = Math.max(1, store.nodes.size() - MAX_NODES + 1);
        for (int i = 0; i < drop && i < byUse.size(); i++) {
            String key = byUse.get(i).getKey();
            store.nodes.remove(key);
            store.legs.keySet().removeIf(leg -> leg.startsWith(key + ">") || leg.endsWith(">" + key));
        }
    }

    private static String key(int x, int y, int z) {
        return x + ":" + y + ":" + z;
    }

    // ---- Queries ----

    /**
     * How long the hop from one tree to the next really takes, in seconds.
     *
     * Falls back to the supplied estimate until the leg has been walked enough
     * times to mean something. A leg measured in one direction stands in for
     * the other: the ground is the same either way, and half a measurement
     * beats none.
     */
    public static synchronized double legSeconds(int fromX, int fromY, int fromZ,
                                                 int toX, int toY, int toZ,
                                                 double fallbackSeconds) {
        String from = canonical(fromX, fromY, fromZ);
        String to = canonical(toX, toY, toZ);
        if (from == null || to == null || from.equals(to)) {
            return fallbackSeconds;
        }

        Leg forward = store.legs.get(from + ">" + to);
        if (forward != null && forward.count >= MIN_LEG_SAMPLES) {
            return forward.meanSeconds;
        }
        Leg backward = store.legs.get(to + ">" + from);
        if (backward != null && backward.count >= MIN_LEG_SAMPLES) {
            return backward.meanSeconds;
        }
        return fallbackSeconds;
    }

    /**
     * The same lookup for callers that have already canonicalised both ends.
     *
     * Resolving a position to a node is a scan of every known tree, and the
     * optimizer asks for thousands of legs in one go; doing that scan once per
     * tree instead of twice per leg is the difference between instant and a
     * visible hitch.
     */
    public static synchronized double legSecondsById(String from, String to, double fallbackSeconds) {
        if (from == null || to == null || from.equals(to)) {
            return fallbackSeconds;
        }
        Leg forward = store.legs.get(from + ">" + to);
        if (forward != null && forward.count >= MIN_LEG_SAMPLES) {
            return forward.meanSeconds;
        }
        Leg backward = store.legs.get(to + ">" + from);
        if (backward != null && backward.count >= MIN_LEG_SAMPLES) {
            return backward.meanSeconds;
        }
        return fallbackSeconds;
    }

    /** Whether a leg between two canonical ids has been measured. */
    public static synchronized boolean isMeasuredById(String from, String to) {
        if (from == null || to == null || from.equals(to)) {
            return false;
        }
        Leg forward = store.legs.get(from + ">" + to);
        if (forward != null && forward.count >= MIN_LEG_SAMPLES) {
            return true;
        }
        Leg backward = store.legs.get(to + ">" + from);
        return backward != null && backward.count >= MIN_LEG_SAMPLES;
    }

    /** How many times the tree with this canonical id has been felled. */
    public static synchronized int chopsById(String id) {
        if (id == null) {
            return 0;
        }
        Node node = store.nodes.get(id);
        return node == null ? 0 : node.chops;
    }

    /** True when this leg has been walked enough times to be trusted. */
    public static synchronized boolean isMeasured(int fromX, int fromY, int fromZ,
                                                  int toX, int toY, int toZ) {
        String from = canonical(fromX, fromY, fromZ);
        String to = canonical(toX, toY, toZ);
        if (from == null || to == null || from.equals(to)) {
            return false;
        }
        Leg forward = store.legs.get(from + ">" + to);
        if (forward != null && forward.count >= MIN_LEG_SAMPLES) {
            return true;
        }
        Leg backward = store.legs.get(to + ">" + from);
        return backward != null && backward.count >= MIN_LEG_SAMPLES;
    }

    /** How many times this tree has been felled, across every session. */
    public static synchronized int chopsAt(int x, int y, int z) {
        String id = canonical(x, y, z);
        if (id == null) {
            return 0;
        }
        Node node = store.nodes.get(id);
        return node == null ? 0 : node.chops;
    }

    public static synchronized int nodeCount() {
        return store.nodes.size();
    }

    /** Legs with enough samples to be used instead of a distance estimate. */
    public static synchronized int measuredLegCount() {
        int measured = 0;
        for (Leg leg : store.legs.values()) {
            if (leg.count >= MIN_LEG_SAMPLES) {
                measured++;
            }
        }
        return measured;
    }

    public static synchronized int legCount() {
        return store.legs.size();
    }

    /** Total chops recorded, so the HUD can say how much evidence there is. */
    public static synchronized int totalChops() {
        int total = 0;
        for (Node node : store.nodes.values()) {
            total += node.chops;
        }
        return total;
    }

    // ---- Persistence ----

    private static Path path() {
        return FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME);
    }

    public static synchronized void load() {
        Path file = path();
        try {
            if (!Files.exists(file)) {
                return;
            }
            try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                Store loaded = GSON.fromJson(reader, Store.class);
                if (loaded != null) {
                    store = loaded;
                    if (store.nodes == null) {
                        store.nodes = new LinkedHashMap<>();
                    }
                    if (store.legs == null) {
                        store.legs = new LinkedHashMap<>();
                    }
                    sanitise();
                }
            }
        } catch (Exception e) {
            store = new Store();
        }
    }

    /** Drop anything a hand-edited or truncated file cannot be trusted to hold. */
    private static void sanitise() {
        store.nodes.entrySet().removeIf(entry -> entry.getValue() == null);
        store.legs.entrySet().removeIf(entry -> {
            Leg leg = entry.getValue();
            if (leg == null || leg.count <= 0) {
                return true;
            }
            if (!Double.isFinite(leg.meanSeconds)
                    || leg.meanSeconds < MIN_LEG_SECONDS
                    || leg.meanSeconds > MAX_LEG_SECONDS) {
                return true;
            }
            String key = entry.getKey();
            int split = key.indexOf('>');
            if (split <= 0) {
                return true;
            }
            // A leg whose endpoints are gone describes nothing.
            return !store.nodes.containsKey(key.substring(0, split))
                    || !store.nodes.containsKey(key.substring(split + 1));
        });
        for (Node node : store.nodes.values()) {
            if (node.chops < 0) {
                node.chops = 0;
            }
        }
    }

    /**
     * Copy under the lock, serialise and write outside it.
     *
     * Turning the graph into JSON is the expensive half, and doing it on the
     * client thread would put a hitch in the middle of play every time the
     * save timer came round. Copying a few hundred four-field objects does not.
     */
    public static void persistAsync() {
        Store snapshot = snapshot();
        IO.execute(() -> write(GSON.toJson(snapshot)));
    }

    /** Blocking write, for a clean shutdown. */
    public static void persistNow() {
        write(GSON.toJson(snapshot()));
    }

    private static synchronized Store snapshot() {
        Store copy = new Store();
        for (Map.Entry<String, Node> entry : store.nodes.entrySet()) {
            Node node = entry.getValue();
            Node clone = new Node(node.x, node.y, node.z);
            clone.chops = node.chops;
            copy.nodes.put(entry.getKey(), clone);
        }
        for (Map.Entry<String, Leg> entry : store.legs.entrySet()) {
            Leg leg = entry.getValue();
            Leg clone = new Leg();
            clone.count = leg.count;
            clone.meanSeconds = leg.meanSeconds;
            copy.legs.put(entry.getKey(), clone);
        }
        return copy;
    }

    private static void write(String json) {
        try {
            Path file = path();
            Files.createDirectories(file.getParent());
            try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                writer.write(json);
            }
        } catch (Exception ignored) {
            // Losing telemetry is not worth crashing the client over.
        }
    }

    /** Forget everything measured. */
    public static synchronized void clear() {
        store = new Store();
        lastNode = null;
        lastChopAt = 0L;
        persistAsync();
    }
}
