package com.birchmod.route;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;

/**
 * Turns a route into a string you can paste to somebody, and back again.
 *
 * <h2>Shape</h2>
 * {@code BIRCH1-} followed by URL-safe Base64 of gzipped JSON. The prefix
 * carries a version so a future format can be told apart from this one rather
 * than failing as corrupt; Base64 without padding survives chat, Discord and
 * anything else that eats punctuation; and gzip matters because a fifty-stop
 * route is mostly repeated digits and compresses to about a third.
 *
 * <h2>Reading one is the dangerous direction</h2>
 * An exported route is a string from another player, so decoding treats every
 * part of it as hostile: the encoded form is capped before decoding, the
 * decompressed form is capped while decompressing rather than after — a few
 * hundred bytes of gzip can expand to gigabytes otherwise — and every
 * coordinate is checked against the size of a Minecraft world. Anything that
 * fails is a clear refusal rather than a half-imported route.
 */
public final class RouteCodec {

    /** Format marker. Bump the digit if the payload shape ever changes. */
    public static final String PREFIX = "BIRCH1-";

    /** Longest share code accepted, before decoding. */
    private static final int MAX_CODE_CHARS = 64 * 1024;

    /** Most JSON allowed out of the decompressor, so a zip bomb cannot land. */
    private static final int MAX_DECOMPRESSED_BYTES = 512 * 1024;

    /**
     * Stops one share code can carry.
     *
     * Also what the recorder will record, deliberately. When only the import
     * side enforced this, a longer recording encoded without complaint into a
     * code that nothing could ever read back — not the person you sent it to,
     * and not you. A route the mod will make is a route the mod can carry.
     */
    public static final int MAX_POINTS = 512;

    /** Minecraft's own limits, so an import cannot plant a stop outside a world. */
    private static final int MAX_HORIZONTAL = 30_000_000;
    private static final int MAX_VERTICAL = 4_096;

    private static final int MAX_NAME_CHARS = 32;

    private static final Gson GSON = new Gson();

    private RouteCodec() {
    }

    /** Wire shape. Short keys because they are repeated once per stop. */
    private static final class Payload {
        String n;
        int[][] p;
    }

    /** Why an import was refused, in words the player can act on. */
    public static final class CodecException extends Exception {
        private static final long serialVersionUID = 1L;

        public CodecException(String message) {
            super(message);
        }
    }

    // ---- Writing ----

    /**
     * Encode a route as a share code.
     *
     * The lap time is deliberately left out. It is a record of what the person
     * exporting can do, and it is not transferable — arriving with somebody
     * else's best already on the board would make your own first lap look like
     * a failure.
     */
    public static String encode(RecordedRoute route) {
        Payload payload = new Payload();
        payload.n = route.name;
        payload.p = new int[route.size()][];

        for (int i = 0; i < route.size(); i++) {
            RecordedRoute.Point point = route.points.get(i);
            payload.p[i] = new int[]{point.x, point.y, point.z};
        }

        byte[] json = GSON.toJson(payload).getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(compressed)) {
            gzip.write(json);
        } catch (Exception e) {
            // Compressing an in-memory byte array does not fail in practice.
            throw new IllegalStateException("could not encode route", e);
        }
        return PREFIX + Base64.getUrlEncoder().withoutPadding()
                .encodeToString(compressed.toByteArray());
    }

    // ---- Reading ----

    /**
     * Decode a share code into a route.
     *
     * @throws CodecException if the code is not one of ours, or not usable
     */
    public static RecordedRoute decode(String code) throws CodecException {
        if (code == null || code.isBlank()) {
            throw new CodecException("Nothing to import.");
        }
        String trimmed = code.trim();

        if (trimmed.length() > MAX_CODE_CHARS) {
            throw new CodecException("That code is far too long to be a route.");
        }
        if (!trimmed.startsWith(PREFIX)) {
            throw new CodecException("That is not a Birch route code — they start with "
                    + PREFIX.substring(0, PREFIX.length() - 1) + ".");
        }

        byte[] compressed;
        try {
            compressed = Base64.getUrlDecoder().decode(trimmed.substring(PREFIX.length()));
        } catch (IllegalArgumentException e) {
            throw new CodecException("That code is damaged — it may have been cut short.");
        }

        String json = inflate(compressed);
        Payload payload;
        try {
            payload = GSON.fromJson(json, Payload.class);
        } catch (JsonSyntaxException e) {
            throw new CodecException("That code is damaged.");
        }
        if (payload == null || payload.p == null) {
            throw new CodecException("That code has no route in it.");
        }
        if (payload.p.length > MAX_POINTS) {
            throw new CodecException("That route has " + payload.p.length
                    + " stops; " + MAX_POINTS + " is the most that can be imported.");
        }

        RecordedRoute route = new RecordedRoute(cleanName(payload.n));
        for (int[] point : payload.p) {
            if (point == null || point.length < 3) {
                throw new CodecException("That code is damaged — a stop is incomplete.");
            }
            if (!inWorld(point[0], point[1], point[2])) {
                throw new CodecException("That route has a stop outside the world.");
            }
            route.points.add(new RecordedRoute.Point(point[0], point[1], point[2]));
        }

        route.dedupe();
        if (route.size() < RouteLibrary.MIN_STOPS) {
            throw new CodecException("That route only has " + route.size()
                    + " distinct stop(s); " + RouteLibrary.MIN_STOPS + " are needed.");
        }
        return route;
    }

    /**
     * Decompress, refusing to keep going past the cap.
     *
     * Checking the size afterwards is too late: the memory is already gone by
     * then, and a few hundred bytes of gzip can hold gigabytes.
     */
    private static String inflate(byte[] compressed) throws CodecException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];

        try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(compressed))) {
            int read;
            while ((read = gzip.read(buffer)) > 0) {
                if (out.size() + read > MAX_DECOMPRESSED_BYTES) {
                    throw new CodecException("That code unpacks to far more than a route should.");
                }
                out.write(buffer, 0, read);
            }
        } catch (CodecException e) {
            throw e;
        } catch (Exception e) {
            throw new CodecException("That code is damaged — it may have been cut short.");
        }
        return out.toString(StandardCharsets.UTF_8);
    }

    private static boolean inWorld(int x, int y, int z) {
        return Math.abs(x) <= MAX_HORIZONTAL
                && Math.abs(z) <= MAX_HORIZONTAL
                && Math.abs(y) <= MAX_VERTICAL;
    }

    /**
     * A name that is safe to type back at a command.
     *
     * Imported names reach a Brigadier word argument and a map key, so anything
     * that is not a plain word is replaced rather than trusted.
     */
    private static String cleanName(String raw) {
        if (raw == null || raw.isBlank()) {
            return "imported";
        }
        StringBuilder clean = new StringBuilder();
        for (char c : raw.toLowerCase(Locale.ROOT).toCharArray()) {
            if (Character.isLetterOrDigit(c) || c == '_' || c == '-') {
                clean.append(c);
            }
            if (clean.length() >= MAX_NAME_CHARS) {
                break;
            }
        }
        return clean.isEmpty() ? "imported" : clean.toString();
    }

    /**
     * A name not already taken, by adding a number if it is.
     *
     * Importing must never quietly replace a route you recorded yourself.
     */
    public static String freeName(String wanted, java.util.function.Predicate<String> taken) {
        if (!taken.test(wanted)) {
            return wanted;
        }
        for (int suffix = 2; suffix < 1000; suffix++) {
            String candidate = wanted + "-" + suffix;
            if (!taken.test(candidate)) {
                return candidate;
            }
        }
        return wanted + "-" + System.currentTimeMillis();
    }

    /** Every saved route in one code, for moving a whole library. */
    public static List<String> encodeAll(List<RecordedRoute> routes) {
        List<String> codes = new ArrayList<>(routes.size());
        for (RecordedRoute route : routes) {
            if (route != null && route.size() >= RouteLibrary.MIN_STOPS) {
                codes.add(encode(route));
            }
        }
        return codes;
    }
}
