package com.birchmod.util;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Reading and writing the files the mod cannot afford to lose.
 *
 * Everything here exists because of one sentence that used to be true of all
 * four of this mod's data files: they were saved by opening the real file,
 * emptying it, and streaming the new contents in. That is fine until the moment
 * it is not — a crash, an Alt-F4, a power cut, the OS killing the process on
 * shutdown — and the file is left half-written. On the next launch the parse
 * throws, the catch that was meant to keep a bad file from killing the game
 * quietly hands back defaults, and your routes are gone. Not corrupted in a way
 * you could complain about: gone, silently, replaced by an empty library that
 * saves cleanly over whatever was left.
 *
 * So a save never touches the real file until the new contents are safely on
 * disk. It writes a temporary file beside it, flushes that to the platter
 * rather than trusting the page cache, keeps the last good version as a backup,
 * and only then moves the new file into place — a move being the one filesystem
 * operation that either happens or does not. A read that finds nothing usable
 * falls back to the backup, so even losing the race twice costs you one save
 * rather than everything.
 */
public final class SafeFile {

    /** Suffix for the copy kept from before the last successful save. */
    private static final String BACKUP = ".bak";

    /** Suffix for the half-written file that is about to become the real one. */
    private static final String TEMP = ".tmp";

    /**
     * One lock per file, so two savers cannot interleave.
     *
     * The stats and travel graph are written from a background thread on a
     * timer and again from the client thread on shutdown, and those two can
     * overlap: the shutdown save and the periodic save would be streaming into
     * the same file at the same time. Keyed by the file itself, because
     * unrelated files have no reason to wait on each other.
     */
    private static final Map<Path, Object> LOCKS = new ConcurrentHashMap<>();

    private SafeFile() {
    }

    /**
     * Save {@code content}, atomically, keeping the previous version as backup.
     *
     * @return true if the file on disk now holds the new content
     */
    public static boolean write(Path file, String content) {
        Object lock = LOCKS.computeIfAbsent(file.toAbsolutePath(), key -> new Object());
        synchronized (lock) {
            return writeLocked(file, content);
        }
    }

    private static boolean writeLocked(Path file, String content) {
        Path temp = sibling(file, TEMP);
        try {
            Files.createDirectories(file.getParent());

            // Force to disk before the move. Without this the move can land
            // first and the contents follow, which on an unclean shutdown is
            // how a file ends up existing, correctly named, and empty.
            try (OutputStream out = Files.newOutputStream(temp,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING)) {
                out.write(content.getBytes(StandardCharsets.UTF_8));
                out.flush();
                sync(temp);
            }

            // Keep what was there. If the move below is interrupted the backup
            // is the version a read falls back to.
            if (Files.exists(file)) {
                try {
                    Files.copy(file, sibling(file, BACKUP),
                            StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException ignored) {
                    // A missing backup is worse than no backup, but it is not
                    // a reason to abandon a save that is otherwise ready.
                }
            }

            move(temp, file);
            return true;
        } catch (Exception e) {
            // Best effort: a failed save must not take the game with it, and
            // the previous version of the file is still intact on disk.
            try {
                Files.deleteIfExists(temp);
            } catch (IOException ignored) {
                // Nothing sensible left to do.
            }
            return false;
        }
    }

    /**
     * Read a file, falling back to its backup if it is missing or unusable.
     *
     * @param usable answers whether the text parses; a file that reads but does
     *               not parse is exactly the case the backup exists for, and it
     *               cannot be recognised from here
     * @return the file's content, the backup's, or null if neither will do
     */
    public static String read(Path file, java.util.function.Predicate<String> usable) {
        String primary = readOrNull(file);
        if (primary != null && usable.test(primary)) {
            return primary;
        }
        String backup = readOrNull(sibling(file, BACKUP));
        if (backup != null && usable.test(backup)) {
            return backup;
        }
        return null;
    }

    private static String readOrNull(Path file) {
        try {
            return Files.exists(file) ? Files.readString(file, StandardCharsets.UTF_8) : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    /** Flush a file's contents and metadata out of the page cache. */
    private static void sync(Path file) {
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.WRITE)) {
            channel.force(true);
        } catch (Exception ignored) {
            // Not every filesystem will do this. The move is still ordered
            // correctly relative to the write on all of the ones that matter.
        }
    }

    private static void move(Path from, Path to) throws IOException {
        try {
            Files.move(from, to, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            // Some network and virtual filesystems refuse. A plain replace is
            // weaker but still far better than streaming into the live file.
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static Path sibling(Path file, String suffix) {
        return file.resolveSibling(file.getFileName().toString() + suffix);
    }
}
