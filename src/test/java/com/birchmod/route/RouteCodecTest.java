package com.birchmod.route;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Base64;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Share codes, and surviving the trip to somebody else.
 *
 * <h2>The thing codes are actually for</h2>
 * A route code exists to be pasted — into chat, into Discord, into a forum
 * post, onto a wiki. All four of those wrap long strings, and the wrap arrives
 * back as a newline or a run of spaces in the middle of the code. That decoded
 * as damage, so a code that was entirely intact came back "it may have been cut
 * short" and the person pasting it had no way to tell that from a code that
 * really was truncated. The format was fine; the only broken part was the
 * assumption that a string handed between two people arrives as it left.
 *
 * <h2>And the dangerous direction</h2>
 * Decoding takes a string from a stranger, so the refusals matter as much as
 * the successes: a code must not be able to import a stop outside the world, or
 * unpack to more memory than the game has.
 */
class RouteCodecTest {

    private static RecordedRoute loop() {
        RecordedRoute route = new RecordedRoute("grove");
        route.points.add(new RecordedRoute.Point(10, 70, 20));
        route.points.add(new RecordedRoute.Point(30, 70, 25));
        route.points.add(new RecordedRoute.Point(45, 71, 10));
        route.points.add(new RecordedRoute.Point(20, 70, -5));
        return route;
    }

    @Test
    @DisplayName("a route survives a round trip unchanged")
    void roundTrip() throws Exception {
        RecordedRoute back = RouteCodec.decode(RouteCodec.encode(loop()));

        assertEquals("grove", back.name);
        assertEquals(4, back.size());
        assertEquals(10, back.points.get(0).x);
        assertEquals(70, back.points.get(0).y);
        assertEquals(20, back.points.get(0).z);
        assertEquals(-5, back.points.get(3).z);
    }

    @Test
    @DisplayName("a code wrapped by chat still imports")
    void wrappedCodeStillWorks() throws Exception {
        String code = RouteCodec.encode(loop());

        // What Discord does to a long string, and what a forum post does.
        String wrapped = code.substring(0, 20) + "\n" + code.substring(20);
        assertEquals(4, RouteCodec.decode(wrapped).size());

        String spaced = code.substring(0, 15) + "  " + code.substring(15);
        assertEquals(4, RouteCodec.decode(spaced).size());

        String multiline = code.replaceAll("(.{12})", "$1\n");
        assertEquals(4, RouteCodec.decode(multiline).size(),
                "broken onto a dozen lines, as a wiki table would");
    }

    @Test
    @DisplayName("a code transcoded to standard Base64 still imports")
    void standardAlphabetIsAccepted() throws Exception {
        // The mod writes the URL-safe alphabet, but a code that has been round
        // tripped through somebody else's tool may come back with + and /. The
        // bytes underneath are the same either way.
        String code = RouteCodec.encode(loop());
        byte[] raw = Base64.getUrlDecoder().decode(code.substring(RouteCodec.PREFIX.length()));
        String standard = RouteCodec.PREFIX + Base64.getEncoder().encodeToString(raw);

        assertEquals(4, RouteCodec.decode(standard).size());
    }

    @Test
    @DisplayName("what is not a route code is refused by name")
    void refusals() {
        assertThrows(RouteCodec.CodecException.class, () -> RouteCodec.decode(null));
        assertThrows(RouteCodec.CodecException.class, () -> RouteCodec.decode("   "));

        RouteCodec.CodecException notOurs = assertThrows(RouteCodec.CodecException.class,
                () -> RouteCodec.decode("https://example.com/route"));
        assertTrue(notOurs.getMessage().contains("BIRCH1"),
                "says what a real one looks like, rather than just refusing");

        assertThrows(RouteCodec.CodecException.class,
                () -> RouteCodec.decode(RouteCodec.PREFIX + "!!!!not base64!!!!"));
        assertThrows(RouteCodec.CodecException.class,
                () -> RouteCodec.decode(RouteCodec.PREFIX),
                "a prefix with nothing after it");
    }

    @Test
    @DisplayName("a code of the right shape but not gzip is refused")
    void validBase64ButNotARoute() {
        String code = RouteCodec.PREFIX + Base64.getUrlEncoder().withoutPadding()
                .encodeToString("this is not gzipped json".getBytes());
        assertThrows(RouteCodec.CodecException.class, () -> RouteCodec.decode(code));
    }

    @Test
    @DisplayName("too few distinct stops is refused, and says how few")
    void tooShort() {
        RecordedRoute pair = new RecordedRoute("pair");
        pair.points.add(new RecordedRoute.Point(0, 70, 0));
        pair.points.add(new RecordedRoute.Point(40, 70, 0));

        RouteCodec.CodecException e = assertThrows(RouteCodec.CodecException.class,
                () -> RouteCodec.decode(RouteCodec.encode(pair)));
        assertTrue(e.getMessage().contains("2 distinct"), e.getMessage());
    }

    @Test
    @DisplayName("duplicate stops collapse, and can take a route under the limit")
    void duplicatesCollapse() {
        // Two recorded points on one trunk are one stop, so a "four stop"
        // route of three trees and a repeat is really three.
        RecordedRoute route = new RecordedRoute("dupes");
        route.points.add(new RecordedRoute.Point(0, 70, 0));
        route.points.add(new RecordedRoute.Point(0, 70, 1));  // same tree
        route.points.add(new RecordedRoute.Point(40, 70, 0));

        assertThrows(RouteCodec.CodecException.class,
                () -> RouteCodec.decode(RouteCodec.encode(route)),
                "three points, two trees — not enough for a loop");
    }

    @Test
    @DisplayName("a name is cleaned down to something a command can take back")
    void namesAreCleaned() throws Exception {
        RecordedRoute nasty = loop();
        nasty.name = "My Grove!! §c<script>";
        RecordedRoute back = RouteCodec.decode(RouteCodec.encode(nasty));

        assertEquals("mygrovecscript", back.name);
        assertTrue(back.name.chars().allMatch(
                c -> Character.isLetterOrDigit(c) || c == '_' || c == '-'));
    }

    @Test
    @DisplayName("a nameless route gets a name rather than an empty one")
    void emptyName() throws Exception {
        RecordedRoute blank = loop();
        blank.name = "   ";
        assertEquals("imported", RouteCodec.decode(RouteCodec.encode(blank)).name);
    }

    @Test
    @DisplayName("importing never silently replaces a route you already have")
    void freeNameAvoidsCollisions() {
        assertEquals("grove", RouteCodec.freeName("grove", name -> false));
        assertEquals("grove-2", RouteCodec.freeName("grove", "grove"::equals));
        assertEquals("grove-3", RouteCodec.freeName("grove",
                name -> name.equals("grove") || name.equals("grove-2")));
    }
}
