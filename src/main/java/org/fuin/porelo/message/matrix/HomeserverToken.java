package org.fuin.porelo.message.matrix;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@code homeserver-token}: makes the local homeserver's registration token once, before its first
 * start, and its TLS settings before every start. Not a verb of Porelo's transport contract - the
 * homeserver's unit runs it. The token is a file readable by this account alone: the homeserver
 * reads it to let accounts register, and Porelo reads it to register the accounts of its tasks, so
 * it exists in one place.
 */
final class HomeserverToken {

    private static final Set<PosixFilePermission> OWNER_ONLY =
            PosixFilePermissions.fromString("rw-------");

    private static final Set<PosixFilePermission> OWNER_ONLY_DIR =
            PosixFilePermissions.fromString("rwx------");

    private static final int BYTES = 32;

    private static final String NAME = "registration-token";

    private static final String TOKENS = "tokens";

    private HomeserverToken() {}

    static void run(final List<String> args, final Map<String, String> env) throws Failure {
        if (!args.isEmpty()) {
            throw new Failure(Exit.USAGE, "usage: homeserver-token");
        }
        backups(env);
        token(env);
        Publish.prepare(env);
    }

    private static void token(final Map<String, String> env) throws Failure {
        final Path file = file(env);
        try {
            moveIntoItsDirectory(env, file);
            if (Files.exists(file)) {
                // An existing token is kept: Porelo may hold it, and a new one would lock Porelo
                // out without a word. An empty file is none - a start stopped before writing it -
                // and Porelo can hold nothing of it, so it is made anew.
                if (!Files.getPosixFilePermissions(file).equals(OWNER_ONLY)) {
                    throw new Failure(
                            Exit.CONFIG,
                            file + " is readable by more than its owner; fix it or remove it");
                }
                if (Files.size(file) > 0) {
                    return;
                }
                Files.delete(file);
            }
            final Path dir = file.getParent();
            if (dir != null && !Files.isDirectory(dir)) {
                Files.createDirectories(dir, PosixFilePermissions.asFileAttribute(OWNER_ONLY_DIR));
            }
            final byte[] random = new byte[BYTES];
            new SecureRandom().nextBytes(random);
            final String token = Base64.getUrlEncoder().withoutPadding().encodeToString(random);
            // Written whole beside it with its final permissions, never readable by others, then
            // linked in under its name: a link never replaces a token another start made first, and
            // the name never stands for a file half written.
            final Path draft =
                    Files.createTempFile(
                            file.getParent(),
                            ".registration-token-",
                            ".draft",
                            PosixFilePermissions.asFileAttribute(OWNER_ONLY));
            try {
                Files.writeString(draft, token, StandardCharsets.US_ASCII);
                Files.createLink(file, draft);
            } finally {
                Files.deleteIfExists(draft);
            }
            MachineTokens.include(env);
        } catch (final FileAlreadyExistsException ex) {
            // Another start made it between the check and the link: that one is the token.
            return;
        } catch (final IOException ex) {
            throw new Failure(
                    Exit.CONFIG,
                    "cannot make the registration token " + file + ": " + ex.getMessage(),
                    ex);
        }
    }

    /**
     * The directory the unit mounts for the homeserver's backups, which must exist before it does:
     * the account's alone, since a backup holds every token and password hash.
     */
    private static void backups(final Map<String, String> env) throws Failure {
        final Path dir = Keep.backups(env);
        try {
            if (!Files.isDirectory(dir)) {
                Files.createDirectories(dir, PosixFilePermissions.asFileAttribute(OWNER_ONLY_DIR));
            }
            Files.setPosixFilePermissions(dir, OWNER_ONLY_DIR);
        } catch (final IOException ex) {
            throw new Failure(
                    Exit.CONFIG,
                    "cannot make the homeserver's backup directory " + dir + ": " + ex.getMessage(),
                    ex);
        }
    }

    /**
     * A token an earlier version kept beside the configuration moved into its own directory, once.
     * The unit mounts that directory, not the file: a file mount keeps the file the container
     * started with, so a machine admitted or dismissed later, whose token is written by a rename,
     * would not be seen until a restart.
     */
    private static void moveIntoItsDirectory(final Map<String, String> env, final Path file)
            throws IOException, Failure {
        final Path earlier = LocalHomeserver.directory(env).resolve(NAME);
        if (Files.exists(file) || !Files.isRegularFile(earlier)) {
            return;
        }
        final Path dir = file.getParent();
        if (dir != null && !Files.isDirectory(dir)) {
            Files.createDirectories(dir, PosixFilePermissions.asFileAttribute(OWNER_ONLY_DIR));
        }
        Files.move(earlier, file, StandardCopyOption.ATOMIC_MOVE);
    }

    /**
     * {@code $XDG_CONFIG_HOME/porelo/matrix/tokens/registration-token}, else under {@code
     * ~/.config}.
     */
    static Path file(final Map<String, String> env) throws Failure {
        return directory(env).resolve(NAME);
    }

    /**
     * The directory the unit mounts for the homeserver: the token file and nothing else of the
     * account's.
     */
    static Path directory(final Map<String, String> env) throws Failure {
        final String xdg = env.get("XDG_CONFIG_HOME");
        if (xdg != null && Path.of(xdg).isAbsolute()) {
            return Path.of(xdg, "porelo", "matrix", TOKENS);
        }
        final String home = env.get("HOME");
        if (home == null || !Path.of(home).isAbsolute()) {
            throw new Failure(
                    Exit.CONFIG,
                    "neither XDG_CONFIG_HOME nor HOME names an absolute directory for the token");
        }
        return Path.of(home, ".config", "porelo", "matrix", TOKENS);
    }
}
