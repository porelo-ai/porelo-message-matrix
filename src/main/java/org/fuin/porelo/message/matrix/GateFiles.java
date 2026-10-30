package org.fuin.porelo.message.matrix;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Private host files, published atomically and refused when their ownership is uncertain. */
final class GateFiles {
    static final String MODE = "PORELO_MATRIX_GATE";
    static final String INNER_PORT = "PORELO_MATRIX_INNER_PORT";

    private GateFiles() {}

    static Path directory(final Map<String, String> env) throws Failure {
        return LocalHomeserver.directory(env).resolve("gate");
    }

    static Path runtime(final Map<String, String> env) throws Failure {
        final String runtime = env.get("XDG_RUNTIME_DIR");
        if (runtime == null || !Path.of(runtime).isAbsolute()) {
            throw new Failure(Exit.CONFIG, "the gate needs an absolute XDG_RUNTIME_DIR");
        }
        return Path.of(runtime).resolve("porelo-matrix-gate");
    }

    static boolean enabled(final Map<String, String> env) throws IOException, Failure {
        final Path conf = LocalHomeserver.directory(env).resolve("homeserver.conf");
        if (!Files.exists(conf)) {
            return false;
        }
        return Files.readAllLines(conf).stream().anyMatch(line -> line.strip().equals(MODE + "=1"));
    }

    static void privateDirectory(final Path dir) throws IOException {
        if (!Files.exists(dir, LinkOption.NOFOLLOW_LINKS)) {
            Files.createDirectories(
                    dir,
                    PosixFilePermissions.asFileAttribute(
                            PosixFilePermissions.fromString("rwx------")));
        }
        owned(dir);
        if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("gate directory is not a directory");
        }
        Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwx------"));
    }

    static void owned(final Path path) throws IOException {
        final var owner =
                path.getFileSystem()
                        .getUserPrincipalLookupService()
                        .lookupPrincipalByName(System.getProperty("user.name"));
        if (Files.isSymbolicLink(path)
                || !Files.getOwner(path, LinkOption.NOFOLLOW_LINKS).equals(owner)) {
            throw new IOException("gate file is not owned by the homeserver account");
        }
    }

    static String read(final Path file) throws IOException {
        owned(file);
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
                || !Files.getPosixFilePermissions(file, LinkOption.NOFOLLOW_LINKS)
                        .equals(PosixFilePermissions.fromString("rw-------"))
                || Files.size(file) > 16384) {
            throw new IOException("gate file is not private or has an invalid size");
        }
        return Files.readString(file, StandardCharsets.UTF_8);
    }

    static String readToken(final Path file) throws IOException {
        final String token = read(file).strip();
        if (!token.matches("[A-Za-z0-9._~+/-]+=*")) {
            throw new IOException("gate token file contains no valid credential");
        }
        return token;
    }

    static void write(final Path file, final String text, final boolean replace)
            throws IOException {
        final Path parent = file.getParent();
        if (parent == null) {
            throw new IOException("gate file has no directory");
        }
        final Path draft =
                Files.createTempFile(
                        parent,
                        ".gate-",
                        ".draft",
                        PosixFilePermissions.asFileAttribute(
                                PosixFilePermissions.fromString("rw-------")));
        try {
            Files.writeString(draft, text, StandardCharsets.UTF_8);
            if (replace) {
                Files.move(
                        draft,
                        file,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } else {
                Files.createLink(file, draft);
            }
        } finally {
            Files.deleteIfExists(draft);
        }
    }

    static FileChannel lock(final Path file) throws IOException {
        return FileChannel.open(
                file,
                java.util.Set.of(
                        StandardOpenOption.CREATE,
                        StandardOpenOption.WRITE,
                        LinkOption.NOFOLLOW_LINKS),
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
    }

    static void configure(final Map<String, String> env) throws IOException, Failure {
        final Path parent = LocalHomeserver.directory(env);
        Files.createDirectories(parent);
        final Path file = parent.resolve("homeserver.conf");
        final List<String> lines = Files.exists(file) ? Files.readAllLines(file) : List.of();
        final List<String> result = new ArrayList<>();
        for (final String line : lines) {
            if (!line.strip().startsWith(MODE + "=")
                    && !line.strip().startsWith(INNER_PORT + "=")) {
                result.add(line);
            }
        }
        result.add(MODE + "=1");
        result.add(INNER_PORT + "=8009");
        if (!lines.equals(result)) {
            write(file, String.join("\n", result) + "\n", true);
        }
    }

    static Path socket(final Map<String, String> env) throws IOException, Failure {
        final Path dir = runtime(env);
        owned(dir);
        if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)
                || !Files.getPosixFilePermissions(dir)
                        .equals(PosixFilePermissions.fromString("rwx------"))) {
            throw new IOException("homeserver socket directory is not private");
        }
        final Path socket = dir.resolve("homeserver.sock");
        owned(socket);
        if (((int) Files.getAttribute(socket, "unix:mode", LinkOption.NOFOLLOW_LINKS) & 0170777)
                != 0140600) {
            throw new IOException("homeserver socket is not a private Unix socket");
        }
        return socket;
    }
}
