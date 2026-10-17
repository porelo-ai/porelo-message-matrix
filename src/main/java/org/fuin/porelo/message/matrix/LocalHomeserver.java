package org.fuin.porelo.message.matrix;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.jspecify.annotations.Nullable;

/**
 * The account's own homeserver: the user unit of the package {@code porelo-matrix-homeserver}, enabled so it
 * comes back after a boot, on a loopback port chosen once and kept in {@code homeserver.conf}.
 */
final class LocalHomeserver {

    static final String UNIT = "porelo-matrix-homeserver";

    /** The podman volume the unit keeps the database in, and its container; as the unit names them. */
    static final String VOLUME = "porelo-matrix-homeserver";

    static final String CONTAINER = "matrix-homeserver";

    static final String PORT = "PORELO_MATRIX_PORT";

    private static final int FIRST_PORT = 8008;

    private static final int LAST_PORT = 8099;

    /** The first start pulls the image; measured 5 to 6 s on the test machines. */
    private static final Duration START_TIMEOUT = Duration.ofSeconds(180);

    record Running(URI url, int port, String registrationToken) {
    }

    private LocalHomeserver() {
    }

    static Path directory(final Map<String, String> env) throws Failure {
        final String xdg = env.get("XDG_CONFIG_HOME");
        if (xdg != null && Path.of(xdg).isAbsolute()) {
            return Path.of(xdg, "porelo", "matrix");
        }
        final String home = env.get("HOME");
        if (home == null || !Path.of(home).isAbsolute()) {
            throw new Failure(Exit.CONFIG, "neither XDG_CONFIG_HOME nor HOME names the account's configuration directory");
        }
        return Path.of(home, ".config", "porelo", "matrix");
    }

    static Running ensure(final Map<String, String> env) throws Failure {
        final Path dir = directory(env);
        final int port = port(dir.resolve("homeserver.conf"));
        systemctl("show", UNIT, "-p", "LoadState", "--value")
                .filter(state -> state.strip().equals("loaded"))
                .orElseThrow(() -> new Failure(Exit.CONFIG, "the unit " + UNIT + " is not installed for this account:"
                        + " install the package porelo-matrix-homeserver, or name the project's homeserver"));
        // Enabled, not only started: it then comes back after a boot without anybody logging in.
        systemctl("enable", "--now", UNIT)
                .orElseThrow(() -> new Failure(Exit.CONFIG, "systemctl --user enable --now " + UNIT + " failed"));
        final URI url = URI.create("http://127.0.0.1:" + port);
        awaitUp(url);
        return new Running(url, port, registrationToken(env));
    }

    /** The token the unit made before its first start, which only this account can read. */
    static String registrationToken(final Map<String, String> env) throws Failure {
        final Path file = directory(env).resolve("registration-token");
        try {
            final String token = Files.readString(file, StandardCharsets.US_ASCII).strip();
            if (token.isEmpty()) {
                throw new Failure(Exit.CONFIG, file + " is empty");
            }
            return token;
        } catch (final IOException ex) {
            throw new Failure(Exit.CONFIG, "cannot read the homeserver's registration token " + file + ": " + ex.getMessage(), ex);
        }
    }

    /** The port in the file, or the first free one from 8008 on, written there so it stays. */
    static int port(final Path conf) throws Failure {
        final List<String> lines = new ArrayList<>();
        try {
            if (Files.exists(conf)) {
                lines.addAll(Files.readAllLines(conf, StandardCharsets.UTF_8));
            }
        } catch (final IOException ex) {
            throw new Failure(Exit.CONFIG, "cannot read " + conf + ": " + ex.getMessage(), ex);
        }
        for (final String line : lines) {
            if (line.strip().startsWith(PORT + "=")) {
                try {
                    return Integer.parseInt(line.strip().substring(PORT.length() + 1).strip());
                } catch (final NumberFormatException ex) {
                    throw new Failure(Exit.CONFIG, conf + " names a port that is not a number: " + line, ex);
                }
            }
        }
        final int port = freePort();
        lines.add(PORT + "=" + port);
        try {
            final Path dir = conf.getParent();
            if (dir != null && !Files.isDirectory(dir)) {
                Files.createDirectories(dir, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
            }
            Files.write(conf, lines, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (final IOException ex) {
            throw new Failure(Exit.CONFIG, "cannot write the homeserver's port to " + conf + ": " + ex.getMessage(), ex);
        }
        return port;
    }

    private static int freePort() throws Failure {
        for (int port = FIRST_PORT; port <= LAST_PORT; port++) {
            try (ServerSocket socket = new ServerSocket()) {
                socket.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), port));
                return port;
            } catch (final IOException ex) {
                // Taken - by another account's homeserver, say. The next one.
            }
        }
        throw new Failure(Exit.CONFIG, "no free loopback port between " + FIRST_PORT + " and " + LAST_PORT);
    }

    private static void awaitUp(final URI url) throws Failure {
        final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        final Instant deadline = Instant.now().plus(START_TIMEOUT);
        while (Instant.now().isBefore(deadline)) {
            try {
                final HttpResponse<Void> response = http.send(
                        HttpRequest.newBuilder(url.resolve("/_matrix/client/versions")).build(),
                        HttpResponse.BodyHandlers.discarding());
                if (response.statusCode() == 200) {
                    return;
                }
            } catch (final IOException ex) {
                // Not listening yet.
            } catch (final InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new Failure(Exit.TEMPORARY, "interrupted while waiting for the homeserver", ex);
            }
            try {
                Thread.sleep(200);
            } catch (final InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new Failure(Exit.TEMPORARY, "interrupted while waiting for the homeserver", ex);
            }
        }
        throw new Failure(Exit.TEMPORARY, "the homeserver at " + url + " did not answer within " + START_TIMEOUT.toSeconds() + " s");
    }

    /**
     * Everything the transport left on this account, once no project uses it any more: the homeserver stopped
     * and disabled, its container and database removed, its port and registration token, and every poll
     * position. The package stays; clearing is not uninstalling. Run again, it finds nothing and says so.
     */
    /** Where the unit keeps the id of its container: {@code %t/%N.cid}, in the account's runtime directory. */
    static @Nullable Path cidFile(final Map<String, String> env) {
        final String runtime = env.get("XDG_RUNTIME_DIR");
        return runtime == null || runtime.isBlank() ? null : Path.of(runtime, UNIT + ".cid");
    }

    static Lifecycle.Report clear(final Map<String, String> env) throws Failure {
        final Lifecycle.Report report = new Lifecycle.Report();
        final boolean installed = systemctl("show", UNIT, "-p", "LoadState", "--value")
                .filter(state -> state.strip().equals("loaded")).isPresent();
        if (installed) {
            final boolean enabled = systemctl("is-enabled", "-q", UNIT).isPresent();
            final boolean active = systemctl("is-active", "-q", UNIT).isPresent();
            if (enabled || active) {
                if (systemctl("disable", "--now", UNIT).isPresent()) {
                    report.removed("the homeserver: " + UNIT + " stopped and disabled");
                } else {
                    report.kept("the homeserver " + UNIT, "systemctl --user disable --now " + UNIT + " failed");
                }
            }
        }
        try {
            // Only the unit's own container, by the id podman gave it: one somebody else named so is not ours.
            // The unit's stop removes it already; this catches one a crash left behind.
            final @Nullable Path cid = cidFile(env);
            if (cid != null && Files.isRegularFile(cid)) {
                if (podman("rm", "-f", "-v", "-i", "--cidfile=" + cid) == 0) {
                    report.removed("the container " + CONTAINER);
                } else {
                    report.kept("the container " + CONTAINER, "podman rm failed");
                }
            }
            if (podman("volume", "exists", VOLUME) == 0) {
                if (podman("volume", "rm", "-f", VOLUME) == 0) {
                    report.removed("the homeserver's database, the podman volume " + VOLUME);
                } else {
                    report.kept("the podman volume " + VOLUME, "podman volume rm failed");
                }
            }
        } catch (final IOException ex) {
            report.kept("the homeserver's container and database", "cannot run podman: " + ex.getMessage());
        }
        final Path dir = directory(env);
        for (final String file : List.of("homeserver.conf", "registration-token")) {
            delete(dir.resolve(file), report, "the homeserver's " + ("homeserver.conf".equals(file) ? "port" : "registration token")
                    + ", " + dir.resolve(file));
        }
        try {
            Files.deleteIfExists(dir);
        } catch (final IOException ex) {
            // Something else is in it, which is not the transport's to remove.
        }
        final Path state = Poll.stateDirectory(env);
        if (Files.isDirectory(state)) {
            try (var walk = Files.walk(state)) {
                for (final Path path : walk.sorted(java.util.Comparator.reverseOrder()).toList()) {
                    Files.delete(path);
                }
                report.removed("every poll position and reference, " + state);
            } catch (final IOException ex) {
                report.kept(state.toString(), ex.getMessage());
            }
        }
        return report;
    }

    private static void delete(final Path file, final Lifecycle.Report report, final String what) {
        try {
            if (Files.deleteIfExists(file)) {
                report.removed(what);
            }
        } catch (final IOException ex) {
            report.kept(what, ex.getMessage());
        }
    }

    /** Runs podman, answering its exit code. */
    private static int podman(final String... args) throws IOException, Failure {
        final List<String> command = new ArrayList<>(List.of("podman"));
        command.addAll(List.of(args));
        try {
            final Process process = new ProcessBuilder(command).redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
            return process.waitFor();
        } catch (final InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new Failure(Exit.TEMPORARY, "interrupted while running podman", ex);
        }
    }

    /** Runs {@code systemctl --user}, answering its output when it succeeded. */
    private static Optional<String> systemctl(final String... args) throws Failure {
        final List<String> command = new ArrayList<>(List.of("systemctl", "--user"));
        command.addAll(List.of(args));
        try {
            final Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            final String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            return process.waitFor() == 0 ? Optional.of(output) : Optional.empty();
        } catch (final IOException ex) {
            throw new Failure(Exit.CONFIG, "cannot run systemctl --user: " + ex.getMessage(), ex);
        } catch (final InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new Failure(Exit.TEMPORARY, "interrupted while running systemctl", ex);
        }
    }

}
