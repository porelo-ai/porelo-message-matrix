package org.fuin.porelo.message.matrix;

import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.PrintStream;
import java.net.URI;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;

/**
 * {@code backup <dir>} and {@code restore <dir>}: the local homeserver's conversation copied into a
 * directory and put back on another machine, for the host's playbook, not for Porelo. A copy holds
 * the homeserver's online backups, its {@code homeserver.conf} and the poll positions taken just
 * before the backup, so a restored relay hands over exactly what came after its last poll.
 */
final class Keep {

    static final List<String> VERBS = List.of("backup", "restore");

    /** In the state directory: held shared by every poll, and alone by a backup. */
    static final String LOCK = "keep.lock";

    /** In the state directory: a backup holds off new polls while existing polls finish. */
    static final String WANTED = "keep.wanted";

    /**
     * In the state directory: left by a restore, so the next setup takes the administrator back.
     */
    static final String MARKER = "restored";

    private static final String DATABASE = "database";

    private static final String POSITIONS = "positions";

    private static final String CONF = "homeserver.conf";

    /** For a backup of a busy room, measured at about one second: a wide margin. */
    static final Duration BACKUP_WAIT = Duration.ofSeconds(60);

    /** A backup waits for the polls to end their long sync, and for another backup. */
    private static final Duration LOCK_WAIT = Duration.ofSeconds(180);

    private Keep() {}

    static void run(
            final String verb,
            final List<String> args,
            final Map<String, String> env,
            final Unit unit,
            final PrintStream out)
            throws Failure {
        if (args.size() != 1
                || args.getFirst().isBlank()
                || !Path.of(args.getFirst()).isAbsolute()) {
            throw new Failure(Exit.USAGE, "usage: " + verb + " <absolute directory>");
        }
        final Path dir = Path.of(args.getFirst());
        final ObjectNode answer =
                "backup".equals(verb)
                        ? backup(dir, env, unit, BACKUP_WAIT)
                        : restore(dir, env, unit);
        try {
            out.println(MatrixClient.JSON.writeValueAsString(answer));
        } catch (final JacksonException ex) {
            throw new IllegalStateException("A JSON tree could not be written", ex);
        }
    }

    /** Where the unit has the homeserver write its backups: {@code %S/porelo/matrix/backups}. */
    static Path backups(final Map<String, String> env) throws Failure {
        final String xdg = env.get("XDG_STATE_HOME");
        if (xdg != null && Path.of(xdg).isAbsolute()) {
            return Path.of(xdg, "porelo", "matrix", "backups");
        }
        final String home = env.get("HOME");
        if (home == null || !Path.of(home).isAbsolute()) {
            throw new Failure(
                    Exit.CONFIG,
                    "neither XDG_STATE_HOME nor HOME names an absolute directory for the backups");
        }
        return Path.of(home, ".local", "state", "porelo", "matrix", "backups");
    }

    // --- backup
    // ------------------------------------------------------------------------------------------

    static ObjectNode backup(
            final Path dir, final Map<String, String> env, final Unit unit, final Duration wait)
            throws Failure {
        return backup(dir, env, unit, wait, LOCK_WAIT);
    }

    /** A backup with one bounded budget for acquiring its locks. */
    static ObjectNode backup(
            final Path dir,
            final Map<String, String> env,
            final Unit unit,
            final Duration wait,
            final Duration lockWait)
            throws Failure {
        final long started = System.nanoTime();
        if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) {
            throw new Failure(Exit.CONFIG, dir + " is not a directory to copy into");
        }
        if (!empty(dir)) {
            throw new Failure(Exit.CONFIG, dir + " is not empty: a copy goes into an empty one");
        }
        if (!unit.installed()) {
            throw new Failure(
                    Exit.CONFIG,
                    "the unit " + LocalHomeserver.UNIT + " is not installed for this account");
        }
        if (!unit.active()) {
            throw new Failure(
                    Exit.TEMPORARY, "the homeserver " + LocalHomeserver.UNIT + " is not running");
        }
        final Path backups = backups(env);
        if (!Files.isDirectory(backups, LinkOption.NOFOLLOW_LINKS)) {
            throw new Failure(
                    Exit.CONFIG,
                    backups
                            + " does not exist: the running unit makes no backups; restart it after"
                            + " updating the package porelo-matrix-homeserver");
        }
        final Path state = Poll.stateDirectory(env);
        final long id;
        boolean done = false;
        try {
            Files.createDirectories(state);
            // New polls yield before the positions lock, so overlapping polls cannot starve us.
            try (FileChannel wanted = lockFile(state, WANTED);
                    FileLock intent = exclusive(wanted, started, lockWait)) {
                try (FileChannel channel = lockFile(state);
                        FileLock held = exclusive(channel, started, lockWait)) {
                    // The positions first: taken before the backup, none points past what it holds.
                    copyPositions(state, dir.resolve(POSITIONS));
                    final long before = newest(backups.resolve("meta")).orElse(0);
                    unit.signalBackup();
                    id = awaitBackup(backups.resolve("meta"), before, wait);
                    // Still held: a later backup thins the old ones, and would remove files being
                    // copied.
                    copyTree(backups, dir.resolve(DATABASE));
                }
                final Path conf = LocalHomeserver.directory(env).resolve(CONF);
                if (Files.isRegularFile(conf)) {
                    Files.copy(conf, dir.resolve(CONF));
                }
            }
            done = true;
        } catch (final IOException ex) {
            throw new Failure(Exit.CONFIG, "cannot copy into " + dir + ": " + ex.getMessage(), ex);
        } finally {
            if (!done) {
                // Found empty: a half copy left in it could be taken for a whole one.
                emptyAgain(dir);
            }
        }
        return MatrixClient.JSON.createObjectNode().put("backup", id);
    }

    private static long awaitBackup(final Path meta, final long before, final Duration wait)
            throws Failure, IOException {
        final Instant deadline = Instant.now().plus(wait);
        while (true) {
            final OptionalLong newest = newest(meta);
            if (newest.isPresent() && newest.getAsLong() > before) {
                return newest.getAsLong();
            }
            if (!Instant.now().isBefore(deadline)) {
                throw new Failure(
                        Exit.TEMPORARY,
                        "the homeserver made no backup within " + wait.toSeconds() + " s");
            }
            pause();
        }
    }

    /**
     * The newest backup's id: the name of its file in {@code meta}, which the homeserver writes
     * last.
     */
    private static OptionalLong newest(final Path meta) throws IOException {
        if (!Files.isDirectory(meta)) {
            return OptionalLong.empty();
        }
        try (Stream<Path> files = Files.list(meta)) {
            return files.map(file -> file.getFileName().toString())
                    .filter(name -> name.matches("[0-9]{1,18}"))
                    .mapToLong(Long::parseLong)
                    .max();
        }
    }

    // --- restore
    // ------------------------------------------------------------------------------------------

    static ObjectNode restore(final Path dir, final Map<String, String> env, final Unit unit)
            throws Failure {
        if (!unit.installed()) {
            throw new Failure(
                    Exit.CONFIG,
                    "the unit " + LocalHomeserver.UNIT + " is not installed for this account");
        }
        // A homeserver here, restored by an earlier up or made by a setup, is never replaced.
        if (unit.hasDatabase()) {
            return MatrixClient.JSON.createObjectNode().put("restore", "present");
        }
        if (unit.active()) {
            throw new Failure(
                    Exit.CONFIG,
                    "the homeserver "
                            + LocalHomeserver.UNIT
                            + " runs without its database volume; stop it first");
        }
        final Path database = dir.resolve(DATABASE);
        final long id;
        final Path confHere = LocalHomeserver.directory(env).resolve(CONF);
        final Path backups = backups(env);
        final Path state = Poll.stateDirectory(env);
        try {
            if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) {
                throw new Failure(Exit.CONFIG, dir + " is not a directory holding a copy");
            }
            onlyFilesAndDirectories(dir);
            onlyPositions(dir.resolve(POSITIONS));
            id =
                    newest(database.resolve("meta"))
                            .orElseThrow(
                                    () ->
                                            new Failure(
                                                    Exit.CONFIG,
                                                    dir
                                                            + " holds no whole backup of the homeserver"));
            final Path confThere = dir.resolve(CONF);
            if (!Files.isRegularFile(confThere)) {
                throw new Failure(Exit.CONFIG, dir + " holds no " + CONF);
            }
            if (Files.exists(confHere)) {
                same(confHere, confThere, LocalHomeserver.PORT, "port");
                same(confHere, confThere, LocalHomeserver.SERVER_NAME, "server name");
            }
            if (Files.exists(backups) && !empty(backups)) {
                throw new Failure(
                        Exit.CONFIG, backups + " holds backups of another database already");
            }
            if (Files.isDirectory(state) && hasPositions(state)) {
                throw new Failure(
                        Exit.CONFIG, state + " holds poll positions of another database already");
            }
        } catch (final IOException ex) {
            throw new Failure(Exit.CONFIG, "cannot read " + dir + ": " + ex.getMessage(), ex);
        }
        final List<Path> made = new ArrayList<>();
        boolean done = false;
        try {
            if (!Files.exists(confHere)) {
                Files.createDirectories(
                        confHere.getParent(),
                        PosixFilePermissions.asFileAttribute(
                                PosixFilePermissions.fromString("rwx------")));
                Files.copy(dir.resolve(CONF), confHere);
                made.add(confHere);
            }
            made.add(backups);
            copyTree(database, backups);
            final Path positions = dir.resolve(POSITIONS);
            if (Files.isDirectory(positions)) {
                Files.createDirectories(state);
                made.addAll(copyContents(positions, state));
            }
            final int exit =
                    unit.runOnce(
                            List.of("--restore-backup", "--execute", "server shutdown"), Map.of());
            if (exit != 0) {
                throw new Failure(
                        Exit.CONFIG,
                        "the homeserver's restore run answered "
                                + exit
                                + "; nothing is kept of it");
            }
            final Path marker = state.resolve(MARKER);
            Files.writeString(marker, id + "\n", StandardCharsets.US_ASCII);
            done = true;
        } catch (final IOException ex) {
            throw new Failure(
                    Exit.CONFIG, "cannot restore from " + dir + ": " + ex.getMessage(), ex);
        } finally {
            if (!done) {
                undo(made, unit);
            }
        }
        return MatrixClient.JSON.createObjectNode().put("restore", id);
    }

    /** What a restore that failed made, removed again: it found none of it there. */
    private static void undo(final List<Path> made, final Unit unit) throws Failure {
        final List<String> left = new ArrayList<>();
        for (final Path path : made.reversed()) {
            try {
                deleteTree(path);
            } catch (final IOException ex) {
                left.add(path + " (" + ex.getMessage() + ")");
            }
        }
        if (unit.hasDatabase()) {
            unit.removeDatabase();
        }
        if (!left.isEmpty()) {
            throw new Failure(
                    Exit.CONFIG, "the restore failed and left " + String.join(", ", left));
        }
    }

    private static void same(final Path here, final Path there, final String key, final String what)
            throws Failure, IOException {
        final String mine = LocalHomeserver.setting(here, key);
        final String theirs = LocalHomeserver.setting(there, key);
        if (!java.util.Objects.equals(mine, theirs)) {
            throw new Failure(
                    Exit.CONFIG,
                    "the copy's homeserver had the "
                            + what
                            + " "
                            + theirs
                            + ", this account's has "
                            + mine
                            + ": a restore keeps them, so remove "
                            + here
                            + " or restore elsewhere");
        }
    }

    // --- after a restore
    // ------------------------------------------------------------------------------------------

    /** Whether a restore left its marker, which only the next setup removes. */
    static boolean restored(final Map<String, String> env) throws Failure {
        return Files.isRegularFile(Poll.stateDirectory(env).resolve(MARKER));
    }

    /**
     * The administrator of a restored homeserver taken back, since Porelo's vault came not with the
     * copy: its password set anew in a run of the homeserver's own while the unit is stopped, its
     * other sessions logged out, and the marker removed.
     */
    static Accounts.Session retake(
            final MatrixClient nobody,
            final URI url,
            final Map<String, String> env,
            final Unit unit)
            throws Failure {
        return retake(nobody, url, env, unit, LocalHomeserver.START_TIMEOUT);
    }

    /** Retakes with a bounded restart wait, including when the unit never answers. */
    static Accounts.Session retake(
            final MatrixClient nobody,
            final URI url,
            final Map<String, String> env,
            final Unit unit,
            final Duration within)
            throws Failure {
        final String password = Accounts.password();
        unit.stop();
        final int exit;
        try {
            exit =
                    unit.runOnce(
                            List.of(),
                            Map.of(
                                    "TUWUNEL_ADMIN_EXECUTE",
                                    "[\"users reset-password "
                                            + Lifecycle.ADMIN
                                            + " "
                                            + password
                                            + "\", \"server shutdown\"]"));
        } finally {
            unit.start();
        }
        LocalHomeserver.awaitUp(url, within, env, unit, password);
        if (exit != 0) {
            throw new Failure(
                    Exit.CONFIG,
                    "the restored homeserver's administrator '"
                            + Lifecycle.ADMIN
                            + "' could not be taken back: its run answered "
                            + exit);
        }
        final Accounts.Session session = Accounts.alone(nobody, Lifecycle.ADMIN, password);
        try {
            Files.deleteIfExists(Poll.stateDirectory(env).resolve(MARKER));
        } catch (final IOException ex) {
            throw new Failure(
                    Exit.CONFIG, "cannot remove the restore's marker: " + ex.getMessage(), ex);
        }
        return session;
    }

    // --- the lock poll and backup share
    // ------------------------------------------------------------------------------------------

    static FileChannel lockFile(final Path state) throws IOException {
        return lockFile(state, LOCK);
    }

    private static FileChannel lockFile(final Path state, final String name) throws IOException {
        return FileChannel.open(
                state.resolve(name),
                StandardOpenOption.CREATE,
                StandardOpenOption.READ,
                StandardOpenOption.WRITE);
    }

    /**
     * For a poll: 75 while a backup holds it, never a wait, so a backup that hangs never hangs the
     * polls with it; Porelo polls again.
     */
    static FileLock shared(final FileChannel channel, final Path state)
            throws IOException, Failure {
        try (FileChannel wanted = lockFile(state, WANTED);
                FileLock intent = shared(wanted)) {
            return shared(channel);
        }
    }

    private static FileLock shared(final FileChannel channel) throws IOException, Failure {
        @Nullable FileLock lock;
        try {
            lock = channel.tryLock(0, Long.MAX_VALUE, true);
        } catch (final OverlappingFileLockException ex) {
            lock = null;
        }
        if (lock == null) {
            throw new Failure(
                    Exit.TEMPORARY,
                    "a backup is copying the poll positions; poll again once it has ended");
        }
        return lock;
    }

    private static FileLock exclusive(
            final FileChannel channel, final long started, final Duration lockWait)
            throws IOException, Failure {
        while (true) {
            if (System.nanoTime() - started >= lockWait.toNanos()) {
                throw new Failure(
                        Exit.TEMPORARY,
                        "a poll or another backup held "
                                + LOCK
                                + " for "
                                + lockWait.toSeconds()
                                + " s");
            }
            try {
                final @Nullable FileLock lock = channel.tryLock();
                if (lock != null) {
                    return lock;
                }
            } catch (final OverlappingFileLockException ex) {
                // Held in this process: waited for like any other.
            }
            final long remaining = lockWait.toNanos() - (System.nanoTime() - started);
            if (remaining > 0) {
                pause(Duration.ofNanos(Math.min(100_000_000L, remaining)));
            }
        }
    }

    // --- files
    // ------------------------------------------------------------------------------------------

    /** The positions and references, never a lock or the marker. */
    private static void copyPositions(final Path state, final Path into) throws IOException {
        Files.createDirectories(into);
        try (Stream<Path> files = Files.list(state)) {
            for (final Path file : files.toList()) {
                final String name = file.getFileName().toString();
                if (name.endsWith(".since")
                        && Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                    Files.copy(file, into.resolve(name), LinkOption.NOFOLLOW_LINKS);
                }
            }
        }
        final Path refs = state.resolve("refs");
        if (Files.isDirectory(refs, LinkOption.NOFOLLOW_LINKS)) {
            copyTree(refs, into.resolve("refs"));
        }
    }

    private static boolean hasPositions(final Path state) throws IOException {
        try (Stream<Path> files = Files.list(state)) {
            return files.map(file -> file.getFileName().toString())
                    .anyMatch(name -> name.endsWith(".since") || "refs".equals(name));
        }
    }

    /** A tree copied file by file; the directory it goes into is the account's alone. */
    private static void copyTree(final Path from, final Path into) throws IOException {
        Files.createDirectories(
                into,
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        copyContents(from, into);
    }

    /** What is in {@code from}, copied into {@code into}; answers what it made at the top. */
    private static List<Path> copyContents(final Path from, final Path into) throws IOException {
        final List<Path> top = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(from)) {
            for (final Path source : walk.toList()) {
                if (source.equals(from)) {
                    continue;
                }
                final Path target = into.resolve(from.relativize(source).toString());
                if (Files.isSymbolicLink(source)) {
                    throw new IOException(source + " is a link, which a copy never holds");
                }
                if (Files.isDirectory(source)) {
                    Files.createDirectories(target);
                } else {
                    Files.copy(source, target, LinkOption.NOFOLLOW_LINKS);
                }
                if (from.equals(source.getParent())) {
                    top.add(target);
                }
            }
        }
        return top;
    }

    /** Input from another machine: plain files and directories only, so no copy reaches past it. */
    private static void onlyFilesAndDirectories(final Path dir) throws IOException, Failure {
        try (Stream<Path> walk = Files.walk(dir)) {
            for (final Path path : walk.toList()) {
                if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                        && !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                    throw new Failure(
                            Exit.CONFIG,
                            path + " is neither a file nor a directory; the copy is refused");
                }
            }
        }
    }

    /** What goes into the state directory: positions and references, never a lock or a mark. */
    private static void onlyPositions(final Path positions) throws IOException, Failure {
        if (!Files.isDirectory(positions)) {
            return;
        }
        try (Stream<Path> files = Files.list(positions)) {
            for (final Path path : files.toList()) {
                final String name = path.getFileName().toString();
                final boolean position = name.endsWith(".since") && Files.isRegularFile(path);
                final boolean references = "refs".equals(name) && Files.isDirectory(path);
                if (!position && !references) {
                    throw new Failure(
                            Exit.CONFIG,
                            path
                                    + " is neither a poll position nor the references; the copy is refused");
                }
            }
        }
    }

    private static boolean empty(final Path dir) throws Failure {
        try (Stream<Path> files = Files.list(dir)) {
            return files.findAny().isEmpty();
        } catch (final IOException ex) {
            throw new Failure(Exit.CONFIG, "cannot read " + dir + ": " + ex.getMessage(), ex);
        }
    }

    private static void emptyAgain(final Path dir) throws Failure {
        try (Stream<Path> files = Files.list(dir)) {
            for (final Path path : files.toList()) {
                deleteTree(path);
            }
        } catch (final IOException ex) {
            throw new Failure(
                    Exit.CONFIG,
                    "the backup failed and left a part of its copy in "
                            + dir
                            + ": "
                            + ex.getMessage(),
                    ex);
        }
    }

    private static void deleteTree(final Path tree) throws IOException {
        if (!Files.exists(tree, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(tree)) {
            for (final Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(path);
            }
        }
    }

    private static void pause() throws Failure {
        pause(Duration.ofMillis(100));
    }

    private static void pause(final Duration duration) throws Failure {
        try {
            Thread.sleep(duration);
        } catch (final InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new Failure(Exit.TEMPORARY, "interrupted while waiting", ex);
        }
    }
}
