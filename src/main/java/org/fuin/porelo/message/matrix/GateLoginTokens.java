package org.fuin.porelo.message.matrix;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;

/** Private single-use tokens shared by the per-connection gate processes. */
final class GateLoginTokens {
    private final Path directory;
    private final Clock clock;

    GateLoginTokens(final Path directory, final Clock clock) {
        this.directory = directory;
        this.clock = clock;
    }

    static String hash(final String token) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (final NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }

    String issue(final String login) throws IOException {
        synchronized (GateLoginTokens.class) {
            return issueLocked(login);
        }
    }

    /** A person signs in with one; more than this unused at once is a loop, not a person. */
    static final int OPEN_PER_LOGIN = 16;

    private String issueLocked(final String login) throws IOException {
        if (!GateRules.login(login)) {
            throw new IOException("invalid login");
        }
        GateFiles.privateDirectory(directory);
        try (FileChannel channel = GateFiles.lock(directory.resolve(".lock"));
                FileLock held = channel.lock()) {
            final Instant now = clock.instant();
            int open = 0;
            try (var files = Files.list(directory)) {
                for (final Path file :
                        files.filter(path -> path.getFileName().toString().matches("[0-9a-f]{64}"))
                                .toList()) {
                    final String[] record;
                    try {
                        record = record(file);
                    } catch (final IOException ex) {
                        // A record nobody can read signs nobody in; kept, it would stop every
                        // issue.
                        Files.deleteIfExists(file);
                        continue;
                    }
                    if (!now.isBefore(expiry(record))) {
                        Files.delete(file);
                    } else if (record[0].equals(login)) {
                        open++;
                    }
                }
            }
            if (open >= OPEN_PER_LOGIN) {
                throw new IOException("too many open login tokens");
            }
            final String token = Accounts.secret();
            GateFiles.write(
                    directory.resolve(hash(token)),
                    login + "\n" + now.plusSeconds(120).getEpochSecond() + "\n",
                    false);
            return token;
        }
    }

    boolean consume(final String token, final String login) throws IOException {
        if (!token.matches("[A-Za-z0-9_-]{43}") || !GateRules.login(login)) {
            return false;
        }
        GateFiles.privateDirectory(directory);
        // The JVM lock serializes test threads; the file lock serializes independent gate
        // processes.
        synchronized (GateLoginTokens.class) {
            try (FileChannel channel = GateFiles.lock(directory.resolve(".lock"));
                    FileLock held = channel.lock()) {
                final Path file = directory.resolve(hash(token));
                if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
                    return false;
                }
                final String[] record = record(file);
                if (!GateRules.loginTokenValid(record[0], login, expiry(record), clock.instant())) {
                    return false;
                }
                Files.delete(file);
                return true;
            }
        }
    }

    private static String[] record(final Path file) throws IOException {
        final String[] record = GateFiles.read(file).split("\n");
        if (record.length != 2 || !GateRules.login(record[0])) {
            throw new IOException("invalid login-token record");
        }
        return record;
    }

    private static Instant expiry(final String[] record) throws IOException {
        try {
            return Instant.ofEpochSecond(Long.parseLong(record[1]));
        } catch (final IllegalArgumentException | java.time.DateTimeException ex) {
            throw new IOException("invalid login-token expiry", ex);
        }
    }
}
