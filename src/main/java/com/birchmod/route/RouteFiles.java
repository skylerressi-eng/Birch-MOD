package com.birchmod.route;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

import net.fabricmc.loader.api.FabricLoader;

/**
 * Routes as files on disk, so one can be sent like any other file.
 *
 * A clipboard is fine for handing a route to the person next to you and
 * useless for keeping one. Files can be backed up, dropped into a Discord
 * message, kept in a folder of routes for different islands, and — the part
 * that matters here — listed, so importing is picking one from a list rather
 * than remembering where you put the code.
 *
 * <h2>What is in one</h2>
 * A couple of comment lines saying what the file is, then the share code. The
 * comments are for whoever opens it in a text editor wondering what they have;
 * reading ignores everything that is not the code, which also means a file
 * somebody pasted a code into by hand imports perfectly well.
 */
public final class RouteFiles {

    public static final String EXTENSION = ".birchroute";

    /** Files listed from the folder at once. */
    private static final int MAX_LISTED = 256;

    /** A route file is a few hundred bytes; this is far past generous. */
    private static final long MAX_FILE_BYTES = 1024L * 1024L;

    private RouteFiles() {
    }

    /** Where exported routes live. */
    public static Path directory() {
        return FabricLoader.getInstance().getConfigDir()
                .resolve("birchoptimizer").resolve("routes");
    }

    /** One file in the folder, with enough read out of it to show in a list. */
    public record Saved(Path file, String routeName, int stops, String problem) {
        public boolean isUsable() {
            return problem == null;
        }

        public String fileName() {
            return file.getFileName().toString();
        }
    }

    // ---- Writing ----

    /**
     * Write a route out.
     *
     * @return the file written
     */
    public static Path write(Path dir, RecordedRoute route) throws IOException {
        Files.createDirectories(dir);
        Path file = dir.resolve(fileNameFor(route.name));

        String body = "# Birch Optimizer route: " + route.name + "\n"
                + "# " + route.size() + " stops. Import it from the Routes tab of /birch gui,\n"
                + "# or paste the line below into /route import.\n"
                + RouteCodec.encode(route) + "\n";

        Files.writeString(file, body, StandardCharsets.UTF_8);
        return file;
    }

    /**
     * A file name that is safe on every platform.
     *
     * Route names come from the player and, once imported, from other players,
     * so anything that is not a plain word is replaced rather than handed to
     * the filesystem.
     */
    public static String fileNameFor(String routeName) {
        StringBuilder clean = new StringBuilder();
        if (routeName != null) {
            for (char c : routeName.toLowerCase(Locale.ROOT).toCharArray()) {
                clean.append(Character.isLetterOrDigit(c) || c == '-' || c == '_' ? c : '-');
                if (clean.length() >= 48) {
                    break;
                }
            }
        }
        String base = clean.toString().replaceAll("-+", "-").replaceAll("^-|-$", "");
        return (base.isEmpty() ? "route" : base) + EXTENSION;
    }

    // ---- Reading ----

    /** Every route file in the folder, newest first, each already checked. */
    public static List<Saved> list(Path dir) {
        List<Saved> found = new ArrayList<>();
        if (!Files.isDirectory(dir)) {
            return found;
        }

        try (Stream<Path> files = Files.list(dir)) {
            List<Path> candidates = files
                    .filter(Files::isRegularFile)
                    .filter(RouteFiles::looksLikeARoute)
                    .sorted(Comparator.comparing(RouteFiles::modifiedAt).reversed())
                    .limit(MAX_LISTED)
                    .toList();

            for (Path file : candidates) {
                found.add(inspect(file));
            }
        } catch (IOException ignored) {
            // An unreadable folder shows as an empty list rather than an error;
            // there is nothing the player could do about it from in here.
        }
        return found;
    }

    private static boolean looksLikeARoute(Path file) {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(EXTENSION) || name.endsWith(".txt");
    }

    private static long modifiedAt(Path file) {
        try {
            return Files.getLastModifiedTime(file).toMillis();
        } catch (IOException e) {
            return 0L;
        }
    }

    /** Read a file far enough to say what it holds, without throwing. */
    public static Saved inspect(Path file) {
        try {
            RecordedRoute route = read(file);
            return new Saved(file, route.name, route.size(), null);
        } catch (IOException e) {
            return new Saved(file, file.getFileName().toString(), 0, "could not be read");
        } catch (RouteCodec.CodecException e) {
            return new Saved(file, file.getFileName().toString(), 0, e.getMessage());
        }
    }

    /**
     * Read a route out of a file.
     *
     * Everything that is not the code is ignored, so the comments at the top
     * cost nothing and a file somebody pasted a bare code into still works.
     */
    public static RecordedRoute read(Path file) throws IOException, RouteCodec.CodecException {
        if (Files.size(file) > MAX_FILE_BYTES) {
            throw new RouteCodec.CodecException("That file is far too large to be a route.");
        }
        String contents = Files.readString(file, StandardCharsets.UTF_8);

        for (String line : contents.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.startsWith(RouteCodec.PREFIX)) {
                return RouteCodec.decode(trimmed);
            }
        }
        throw new RouteCodec.CodecException("No route code in that file.");
    }

    /** Remove an exported file. */
    public static boolean delete(Path file) {
        try {
            return Files.deleteIfExists(file);
        } catch (IOException e) {
            return false;
        }
    }
}
