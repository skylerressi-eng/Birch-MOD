package com.birchmod.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.function.Predicate;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Saving, including the failures the whole thing exists for.
 *
 * What is under test is not "does it write the file" — the old code did that.
 * It is what is on disk when a save does not finish, and whether what is there
 * can still be read. Measured on the old path, 36 of 4000 reads taken during a
 * single save saw a torn file; a crash at any of those moments lost it.
 */
class SafeFileTest {

    /** Anything that looks like whole JSON. Stands in for a real parse. */
    private static final Predicate<String> USABLE =
            s -> s != null && s.startsWith("{") && s.endsWith("}");

    @TempDir
    Path dir;

    private static String read(Path p) throws IOException {
        return Files.exists(p) ? Files.readString(p, StandardCharsets.UTF_8) : null;
    }

    @Test
    @DisplayName("an ordinary save round-trips")
    void roundTrip() throws Exception {
        Path f = dir.resolve("routes.json");
        assertTrue(SafeFile.write(f, "{\"a\":1}"));
        assertEquals("{\"a\":1}", read(f));
        assertEquals("{\"a\":1}", SafeFile.read(f, USABLE));
    }

    /**
     * The point of the exercise: the live file must never be the thing being
     * streamed into, because a half-finished stream is a lost file.
     */
    @Test
    @DisplayName("the live file is whole at every instant during a save")
    void neverTornMidSave() throws Exception {
        Path f = dir.resolve("stats.json");
        SafeFile.write(f, "{\"first\":1}");

        String big = "{\"x\":\"" + "y".repeat(400_000) + "\"}";

        List<String> bad = new ArrayList<>();
        CountDownLatch go = new CountDownLatch(1);
        Thread watcher = new Thread(() -> {
            try {
                go.countDown();
                for (int i = 0; i < 4000; i++) {
                    String seen = read(f);
                    if (seen == null || !USABLE.test(seen)) {
                        bad.add(seen == null ? "<missing>" : seen.length() + " chars, not whole");
                        return;
                    }
                }
            } catch (Exception e) {
                bad.add(e.toString());
            }
        });
        watcher.start();
        go.await();
        SafeFile.write(f, big);
        watcher.join();

        assertTrue(bad.isEmpty(), "a reader saw " + (bad.isEmpty() ? "" : bad.get(0)));
        assertEquals(big, read(f), "and the new version is what ends up there");
    }

    @Test
    @DisplayName("a file torn by a crash falls back to the backup")
    void tornFileRecovered() throws Exception {
        Path f = dir.resolve("graph.json");
        SafeFile.write(f, "{\"good\":1}");
        SafeFile.write(f, "{\"newer\":2}");

        // Simulate the process dying partway through the next save.
        Files.writeString(f, "{\"half-writ", StandardCharsets.UTF_8);

        assertEquals("{\"good\":1}", SafeFile.read(f, USABLE),
                "the previous good version should come back");
    }

    /**
     * A file can be perfectly readable and still be nonsense — the case the old
     * code could not distinguish, because it only caught the parse failure
     * after it had already given up and started over with defaults.
     */
    @Test
    @DisplayName("garbage that reads but will not parse also falls back")
    void unparseableFallsBack() throws Exception {
        Path f = dir.resolve("config.json");
        SafeFile.write(f, "{\"kept\":1}");
        SafeFile.write(f, "{\"kept\":2}");
        Files.writeString(f, "not json at all", StandardCharsets.UTF_8);

        assertEquals("{\"kept\":1}", SafeFile.read(f, USABLE));
    }

    @Test
    @DisplayName("nothing readable anywhere reads as null")
    void nothingOnDisk() throws Exception {
        Path f = dir.resolve("absent.json");
        assertNull(SafeFile.read(f, USABLE));

        Files.writeString(f, "rubbish", StandardCharsets.UTF_8);
        assertNull(SafeFile.read(f, USABLE), "rubbish with no backup is still nothing");
    }

    /**
     * The stats and travel graph are saved from a timer thread and again from
     * the client thread on shutdown, and those two can land together.
     */
    @Test
    @DisplayName("two savers at once never leave a mixed file")
    void concurrentSaversDoNotInterleave() throws Exception {
        Path f = dir.resolve("contended.json");
        String start = "{\"start\":0}";
        SafeFile.write(f, start);

        String a = "{\"a\":\"" + "a".repeat(60_000) + "\"}";
        String b = "{\"b\":\"" + "b".repeat(60_000) + "\"}";

        List<String> bad = new ArrayList<>();
        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            String payload = (i % 2 == 0) ? a : b;
            Thread t = new Thread(() -> {
                for (int n = 0; n < 20; n++) {
                    SafeFile.write(f, payload);
                    try {
                        String seen = read(f);
                        if (seen == null
                                || !(seen.equals(a) || seen.equals(b) || seen.equals(start))) {
                            bad.add(seen == null ? "<missing>" : seen.length() + " chars, mixed");
                            return;
                        }
                    } catch (Exception e) {
                        bad.add(e.toString());
                        return;
                    }
                }
            });
            threads.add(t);
            t.start();
        }
        for (Thread t : threads) {
            t.join();
        }
        assertTrue(bad.isEmpty(), bad.isEmpty() ? "" : bad.get(0));
        String end = read(f);
        assertTrue(a.equals(end) || b.equals(end), "the file is one whole version or the other");
    }

    @Test
    @DisplayName("temporary files do not accumulate, and one backup is kept")
    void folderStaysTidy() throws Exception {
        Path f = dir.resolve("tidy.json");
        for (int i = 0; i < 5; i++) {
            SafeFile.write(f, "{\"n\":" + i + "}");
        }
        long temps;
        try (Stream<Path> stream = Files.list(dir)) {
            temps = stream.filter(p -> p.getFileName().toString().endsWith(".tmp")).count();
        }
        assertEquals(0L, temps, "temporary files were left behind");
        assertTrue(Files.exists(dir.resolve("tidy.json.bak")));
        assertEquals("{\"n\":3}", read(dir.resolve("tidy.json.bak")),
                "the backup is the version before last");
        assertFalse(read(f).isEmpty());
    }
}
