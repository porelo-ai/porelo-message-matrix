package org.fuin.porelo.message.matrix;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * A registration token of its own for each machine admitted to the account's homeserver, so
 * dropping one machine revokes its token alone. The homeserver reads every token from one file,
 * separated by whitespace; the local token stays first, where this machine's transport reads it,
 * and each machine's token is kept beside it under the machine's name, readable by this account
 * alone. The homeserver reads the file at every registration, so a change holds at once.
 */
final class MachineTokens {

    private static final String DIRECTORY = "machine-tokens";

    private static final Set<PosixFilePermission> OWNER_ONLY =
            PosixFilePermissions.fromString("rw-------");

    private static final Set<PosixFilePermission> OWNER_ONLY_DIR =
            PosixFilePermissions.fromString("rwx------");

    private static final int BYTES = 32;

    private static final SecureRandom RANDOM = new SecureRandom();

    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    /** As Porelo names a machine: a file name of its own, never a path. */
    private static final Pattern MACHINE = Pattern.compile("[a-z0-9][a-z0-9._=-]{0,120}");

    private MachineTokens() {}

    /**
     * The machine's token, made the first time and the same on every later call, in the file the
     * homeserver reads by the time this returns.
     */
    static String issue(final Map<String, String> env, final String machine) throws Failure {
        final Path dir = LocalHomeserver.directory(env).resolve(DIRECTORY);
        final Path file = dir.resolve(name(machine));
        final Path tokens = HomeserverToken.file(env);
        try {
            if (!Files.isRegularFile(tokens)
                    || local(Files.readString(tokens, StandardCharsets.US_ASCII)).isEmpty()) {
                throw new Failure(
                        Exit.CONFIG,
                        "the homeserver has made no registration token of its own yet; start it"
                                + " first");
            }
            if (!Files.isDirectory(dir)) {
                Files.createDirectories(dir, PosixFilePermissions.asFileAttribute(OWNER_ONLY_DIR));
            }
            try (FileChannel channel = lock(env);
                    FileLock held = channel.lock()) {
                if (!Files.exists(file)) {
                    final byte[] random = new byte[BYTES];
                    RANDOM.nextBytes(random);
                    write(
                            file,
                            Base64.getUrlEncoder().withoutPadding().encodeToString(random),
                            false);
                }
                combine(env);
            }
            return read(file);
        } catch (final IOException ex) {
            throw new Failure(
                    Exit.CONFIG,
                    "cannot make the registration token of " + machine + ": " + ex.getMessage(),
                    ex);
        }
    }

    /**
     * The machine's token removed, from the file the homeserver reads too; answers whether it had
     * one. Refused at once once this returns.
     */
    static boolean revoke(final Map<String, String> env, final String machine) throws Failure {
        final Path file = LocalHomeserver.directory(env).resolve(DIRECTORY).resolve(name(machine));
        final boolean had;
        try (FileChannel channel = lock(env);
                FileLock held = channel.lock()) {
            had = Files.deleteIfExists(file);
            combine(env);
        } catch (final IOException ex) {
            throw new Failure(
                    Exit.CONFIG,
                    "cannot remove the registration token of " + machine + ": " + ex.getMessage(),
                    ex);
        }
        return had;
    }

    private static String name(final String machine) throws Failure {
        if (!MACHINE.matcher(machine).matches()) {
            throw new Failure(Exit.USAGE, "not a machine's name: '" + machine + "'");
        }
        return machine;
    }

    /**
     * The machines' tokens put back after a local token just made, so a token file made anew locks
     * out no machine admitted before.
     */
    static void include(final Map<String, String> env) throws Failure {
        final Path dir = LocalHomeserver.directory(env).resolve(DIRECTORY);
        if (!Files.isDirectory(dir)) {
            return;
        }
        try (FileChannel channel = lock(env);
                FileLock held = channel.lock()) {
            combine(env);
        } catch (final IOException ex) {
            throw new Failure(
                    Exit.CONFIG,
                    "cannot put the machines' registration tokens back: " + ex.getMessage(),
                    ex);
        }
    }

    /** Every other machine's token removed, once the account's homeserver goes. */
    static void forget(final Map<String, String> env, final Lifecycle.Report report)
            throws Failure {
        final Path dir = LocalHomeserver.directory(env).resolve(DIRECTORY);
        if (!Files.isDirectory(dir)) {
            return;
        }
        try (Stream<Path> files = Files.list(dir)) {
            for (final Path file : files.toList()) {
                LocalHomeserver.delete(
                        file, report, "the registration token of " + file.getFileName());
            }
        } catch (final IOException ex) {
            report.kept(dir.toString(), ex.getMessage());
        }
        try {
            Files.deleteIfExists(dir);
        } catch (final IOException ex) {
            report.kept(dir.toString(), ex.getMessage());
        }
    }

    /** The machines admitted now, by name. */
    static List<String> admitted(final Map<String, String> env) throws Failure {
        final Path dir = LocalHomeserver.directory(env).resolve(DIRECTORY);
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(dir)) {
            return files.map(file -> file.getFileName().toString())
                    .filter(name -> MACHINE.matcher(name).matches())
                    .sorted()
                    .toList();
        } catch (final IOException ex) {
            throw new Failure(
                    Exit.CONFIG,
                    "cannot read the admitted machines in " + dir + ": " + ex.getMessage(),
                    ex);
        }
    }

    /** The token the local token file holds first, which is this machine's own. */
    static String local(final String content) {
        // The first line, even an empty one: a token after it is another machine's.
        final String first = content.lines().findFirst().orElse("").strip();
        return WHITESPACE.split(first, 2)[0];
    }

    /** The local token first, then every machine's, written whole over the homeserver's file. */
    private static void combine(final Map<String, String> env) throws IOException, Failure {
        final Path tokens = HomeserverToken.file(env);
        final List<String> lines = new ArrayList<>();
        lines.add(local(Files.readString(tokens, StandardCharsets.US_ASCII)));
        final Path dir = LocalHomeserver.directory(env).resolve(DIRECTORY);
        if (Files.isDirectory(dir)) {
            try (Stream<Path> files = Files.list(dir)) {
                for (final Path file : files.sorted().toList()) {
                    lines.add(read(file));
                }
            }
        }
        write(tokens, String.join("\n", lines) + "\n", true);
    }

    private static String read(final Path file) throws IOException {
        return Files.readString(file, StandardCharsets.US_ASCII).strip();
    }

    /** Beside the token file, so every writer of it waits for the others. */
    private static FileChannel lock(final Map<String, String> env) throws IOException, Failure {
        return FileChannel.open(
                LocalHomeserver.directory(env).resolve(".tokens.lock"),
                Set.of(StandardOpenOption.CREATE, StandardOpenOption.WRITE),
                PosixFilePermissions.asFileAttribute(OWNER_ONLY));
    }

    /** Written whole beside it, readable by its owner alone, then moved or linked in. */
    private static void write(final Path file, final String content, final boolean replace)
            throws IOException {
        final Path draft =
                Files.createTempFile(
                        file.getParent(),
                        "." + file.getFileName() + "-",
                        ".draft",
                        PosixFilePermissions.asFileAttribute(OWNER_ONLY));
        try {
            Files.writeString(draft, content, StandardCharsets.US_ASCII);
            if (replace) {
                Files.move(draft, file, StandardCopyOption.ATOMIC_MOVE);
            } else {
                Files.createLink(file, draft);
            }
        } catch (final FileAlreadyExistsException ex) {
            // Unreachable under the lock; a machine's token is never replaced.
        } finally {
            Files.deleteIfExists(draft);
        }
    }
}
