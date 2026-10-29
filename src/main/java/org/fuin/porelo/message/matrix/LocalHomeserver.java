package org.fuin.porelo.message.matrix;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.text.Normalizer;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * The account's own homeserver: the user unit of the package {@code porelo-matrix-homeserver},
 * enabled so it comes back after a boot, on a loopback port chosen once and kept in {@code
 * homeserver.conf}.
 */
final class LocalHomeserver {

    static final String UNIT = "porelo-matrix-homeserver";

    /**
     * The podman volume the unit keeps the database in, and its container; as the unit names them.
     */
    static final String VOLUME = "porelo-matrix-homeserver";

    static final String CONTAINER = "matrix-homeserver";

    static final String PORT = "PORELO_MATRIX_PORT";

    static final String SERVER_NAME = "PORELO_MATRIX_SERVER_NAME";

    /**
     * The address and port the homeserver is also served on, over TLS, for another machine: a VPN
     * address of this machine, never a wildcard. Unset, it is served on loopback alone.
     */
    static final String PUBLISH = "PORELO_MATRIX_PUBLISH";

    /** The host name the published address is also reached by, which its certificate names. */
    static final String PUBLISH_NAME = "PORELO_MATRIX_PUBLISH_NAME";

    /** The server name the unit gives the homeserver when its file names none. */
    private static final String DEFAULT_SERVER_NAME = "localhost";

    private static final int FIRST_PORT = 8008;

    private static final int LAST_PORT = 8099;

    /** The first start pulls the image; measured 5 to 6 s on the test machines. */
    static final Duration START_TIMEOUT = Duration.ofSeconds(180);

    private static final Pattern ANSI =
            Pattern.compile(
                    "\u001b(?:\\[[0-?]*[ -/]*[@-~]|\\][^\u0007\u001b]*(?:\u0007|\u001b\\\\)|[@-_])");

    private static final Pattern CONTROLS = Pattern.compile("[\\p{Cc}\\p{Cf}\\p{Zl}\\p{Zp}]");

    private static final Pattern ERROR_LEVEL = Pattern.compile("(?:^|\\s)ERROR(?:\\s|:|$)");

    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private static final Pattern CREDENTIAL =
            Pattern.compile(
                    "token|password|passwd|secret|authorization|bearer|cookie|session|access_token"
                            + "|api[-_]?key",
                    Pattern.CASE_INSENSITIVE);

    private static final Pattern JSON_ESCAPE =
            Pattern.compile("\\\\(?:u[0-9a-fA-F]{4}|[\"\\\\/bfnrt])");

    private static final String ALLOWED_PUNCTUATION = " .,:;()[]{} `'\"_-/!?";

    private static final Pattern TOKEN_RUN = Pattern.compile("[A-Za-z0-9_-]{8,}+");

    private static final Duration DIAGNOSTIC_TIMEOUT = Duration.ofSeconds(4);

    private static final int CAUSE_LIMIT = 300;

    private static final String WITHHELD =
            "the homeserver's last error was withheld: it held a secret";

    record Running(URI url, int port, String registrationToken) {}

    private LocalHomeserver() {}

    static Path directory(final Map<String, String> env) throws Failure {
        return configHome(env).resolve("porelo").resolve("matrix");
    }

    /** {@code $XDG_CONFIG_HOME}, else {@code ~/.config}. */
    static Path configHome(final Map<String, String> env) throws Failure {
        final String xdg = env.get("XDG_CONFIG_HOME");
        if (xdg != null && Path.of(xdg).isAbsolute()) {
            return Path.of(xdg);
        }
        final String home = env.get("HOME");
        if (home == null || !Path.of(home).isAbsolute()) {
            throw new Failure(
                    Exit.CONFIG,
                    "neither XDG_CONFIG_HOME nor HOME names the account's configuration directory");
        }
        return Path.of(home, ".config");
    }

    static Running ensure(final Map<String, String> env, final Unit unit) throws Failure {
        final Path conf = directory(env).resolve("homeserver.conf");
        if (!unit.installed()) {
            throw new Failure(
                    Exit.CONFIG,
                    "the unit "
                            + UNIT
                            + " is not installed for this account:"
                            + " install the package porelo-matrix-homeserver, or name the project's homeserver");
        }
        // A homeserver started before its port was written runs on the unit's default, and one an
        // earlier version started mounts the token file, not its directory: stopped first, its
        // next start reads both where they are now, and the port is chosen with its own freed.
        if (unit.active() && (!hasPort(conf) || !Files.exists(HomeserverToken.file(env)))) {
            unit.stop();
        }
        final int port = port(conf);
        unit.start();
        final URI url = URI.create("http://127.0.0.1:" + port);
        awaitUp(url, env, unit);
        return new Running(url, port, registrationToken(env));
    }

    /** The token the unit made before its first start, which only this account can read. */
    static String registrationToken(final Map<String, String> env) throws Failure {
        final Path file = HomeserverToken.file(env);
        try {
            // The machines' tokens follow this machine's own, which is first.
            final String token =
                    MachineTokens.local(Files.readString(file, StandardCharsets.US_ASCII));
            if (token.isEmpty()) {
                throw new Failure(Exit.CONFIG, file + " is empty");
            }
            return token;
        } catch (final IOException ex) {
            throw new Failure(
                    Exit.CONFIG,
                    "cannot read the homeserver's registration token "
                            + file
                            + ": "
                            + ex.getMessage(),
                    ex);
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
                    throw new Failure(
                            Exit.CONFIG, conf + " names a port that is not a number: " + line, ex);
                }
            }
        }
        final int port = freePort();
        lines.add(PORT + "=" + port);
        try {
            final Path dir = conf.getParent();
            if (dir != null && !Files.isDirectory(dir)) {
                Files.createDirectories(
                        dir,
                        PosixFilePermissions.asFileAttribute(
                                PosixFilePermissions.fromString("rwx------")));
            }
            Files.write(
                    conf,
                    lines,
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING);
        } catch (final IOException ex) {
            throw new Failure(
                    Exit.CONFIG,
                    "cannot write the homeserver's port to " + conf + ": " + ex.getMessage(),
                    ex);
        }
        return port;
    }

    /** Whether the file names the port already. */
    private static boolean hasPort(final Path conf) throws Failure {
        try {
            return Files.exists(conf) && setting(conf, PORT) != null;
        } catch (final IOException ex) {
            throw new Failure(Exit.CONFIG, "cannot read " + conf + ": " + ex.getMessage(), ex);
        }
    }

    /** The value a line {@code key=value} of the file gives, or null when none does. */
    static @Nullable String setting(final Path conf, final String key) throws IOException {
        for (final String line : Files.readAllLines(conf, StandardCharsets.UTF_8)) {
            if (line.strip().startsWith(key + "=")) {
                return line.strip().substring(key.length() + 1).strip();
            }
        }
        return null;
    }

    /** The server name the unit starts the homeserver with: the file's, else its default. */
    static String serverName(final Path conf) throws Failure {
        try {
            final @Nullable String name = Files.exists(conf) ? setting(conf, SERVER_NAME) : null;
            return name == null || name.isEmpty() ? DEFAULT_SERVER_NAME : name;
        } catch (final IOException ex) {
            throw new Failure(Exit.CONFIG, "cannot read " + conf + ": " + ex.getMessage(), ex);
        }
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
        throw new Failure(
                Exit.CONFIG, "no free loopback port between " + FIRST_PORT + " and " + LAST_PORT);
    }

    static void awaitUp(final URI url) throws Failure {
        awaitUp(url, START_TIMEOUT);
    }

    /** Waits after a unit start, protecting any additional secrets made during this invocation. */
    static void awaitUp(
            final URI url, final Map<String, String> env, final Unit unit, final String... secrets)
            throws Failure {
        awaitUp(url, START_TIMEOUT, env, unit, secrets);
    }

    /** Waits after starting the unit, with its environment available to protect diagnostics. */
    static void awaitUp(
            final URI url,
            final Duration within,
            final Map<String, String> env,
            final Unit unit,
            final String... secrets)
            throws Failure {
        if (!answersWithin(url, within)) {
            throw new Failure(
                    Exit.TEMPORARY,
                    timeout(url, within) + "\n" + startupCause(env, unit, List.of(secrets)));
        }
    }

    /** Until the homeserver answers on its port, within the limit given. */
    static void awaitUp(final URI url, final Duration within) throws Failure {
        if (!answersWithin(url, within)) {
            throw new Failure(Exit.TEMPORARY, timeout(url, within));
        }
    }

    private static String timeout(final URI url, final Duration within) {
        return "the homeserver at " + url + " did not answer within " + within.toSeconds() + " s";
    }

    private static boolean answersWithin(final URI url, final Duration within) throws Failure {
        final HttpClient http =
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        final Instant deadline = Instant.now().plus(within);
        while (Instant.now().isBefore(deadline)) {
            try {
                final HttpResponse<Void> response =
                        http.send(
                                // A port that takes the connection and never answers holds
                                // no request past this, so the limit above is kept.
                                HttpRequest.newBuilder(url.resolve("/_matrix/client/versions"))
                                        .timeout(Duration.ofSeconds(2))
                                        .build(),
                                HttpResponse.BodyHandlers.discarding());
                if (response.statusCode() == 200) {
                    return true;
                }
            } catch (final IOException ex) {
                // Not listening yet.
            } catch (final InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new Failure(
                        Exit.TEMPORARY, "interrupted while waiting for the homeserver", ex);
            }
            try {
                Thread.sleep(200);
            } catch (final InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new Failure(
                        Exit.TEMPORARY, "interrupted while waiting for the homeserver", ex);
            }
        }
        return false;
    }

    private static String startupCause(
            final Map<String, String> env, final Unit unit, final List<String> additionalSecrets) {
        final FutureTask<String> cause =
                new FutureTask<>(() -> readStartupCause(env, unit, additionalSecrets));
        Thread.ofPlatform().daemon().name("homeserver-startup-cause").start(cause);
        try {
            return cause.get(DIAGNOSTIC_TIMEOUT.toNanos(), TimeUnit.NANOSECONDS);
        } catch (final InterruptedException ex) {
            Thread.currentThread().interrupt();
            return "the homeserver's unit is unknown";
        } catch (final ExecutionException | TimeoutException ex) {
            return "the homeserver's unit is unknown";
        } finally {
            cause.cancel(true);
        }
    }

    private static String readStartupCause(
            final Map<String, String> env, final Unit unit, final List<String> additionalSecrets) {
        final List<String> secrets = new ArrayList<>(additionalSecrets);
        try {
            secrets.add(registrationToken(env));
        } catch (final Failure ex) {
            // Without the local token, no log line can be proved safe to print.
            return "the homeserver's unit is " + unit.state();
        }
        env.forEach(
                (key, value) -> {
                    if (key.startsWith("PORELO_MATRIX_")
                            && (key.contains("TOKEN")
                                    || key.contains("PASSWORD")
                                    || key.contains("SECRET"))) {
                        secrets.add(value);
                    }
                });
        final List<String> log =
                unit.recentLog().stream()
                        .filter(line -> line.length() <= PodmanUnit.MESSAGE_LIMIT)
                        .toList();
        final Optional<String> summary =
                log.reversed().stream()
                        .filter(line -> stripControls(line).startsWith("Error: "))
                        .findFirst();
        final Optional<String> chosen =
                summary.or(
                        () ->
                                log.reversed().stream()
                                        .filter(
                                                line ->
                                                        ERROR_LEVEL
                                                                .matcher(stripControls(line))
                                                                .find())
                                        .findFirst());
        if (chosen.isEmpty()) {
            return "the homeserver's unit is " + unit.state();
        }
        final String raw = chosen.orElseThrow();
        final String clean = stripControls(raw);
        if (!safeCause(raw, clean, secrets)) {
            return WITHHELD;
        }
        return clean.substring(0, Math.min(CAUSE_LIMIT, clean.length()));
    }

    private static boolean safeCause(
            final String raw, final String clean, final List<String> secrets) {
        if (clean.chars().anyMatch(point -> !allowedCharacter(point))) {
            return false;
        }
        final Matcher runs = TOKEN_RUN.matcher(clean);
        while (runs.find()) {
            final String run = runs.group();
            if (run.chars().anyMatch(point -> point >= '0' && point <= '9')
                    && run.chars()
                            .anyMatch(
                                    point ->
                                            point >= 'A' && point <= 'Z'
                                                    || point >= 'a' && point <= 'z')) {
                return false;
            }
        }
        final Set<String> forms = new LinkedHashSet<>();
        forms.add(raw);
        forms.add(clean);
        try {
            for (int round = 0; round < 6; round++) {
                final int before = forms.size();
                for (final String form : List.copyOf(forms)) {
                    if (Thread.currentThread().isInterrupted() || forms.size() > 1024) {
                        return false;
                    }
                    forms.add(stripControls(form));
                    forms.add(WHITESPACE.matcher(form).replaceAll(""));
                    forms.add(URLDecoder.decode(form, StandardCharsets.UTF_8));
                    forms.add(jsonUnescaped(form));
                    forms.add(Normalizer.normalize(form, Normalizer.Form.NFKC));
                }
                for (final String form : forms) {
                    if (CREDENTIAL.matcher(form).find()) {
                        return false;
                    }
                    for (final String secret : secrets) {
                        if (containsSecretWindow(form, secret)) {
                            return false;
                        }
                    }
                }
                if (forms.size() == before) {
                    return true;
                }
            }
        } catch (final IllegalArgumentException ex) {
            // Malformed encodings cannot establish that a message is safe.
            return false;
        }
        // Refuse unresolved compositions rather than trusting a partial decoding.
        return false;
    }

    private static boolean containsSecretWindow(final String form, final String secret) {
        if (secret.isEmpty()) {
            return false;
        }
        final int length = Math.min(8, secret.length());
        for (int start = 0; start <= secret.length() - length; start++) {
            if (form.contains(secret.substring(start, start + length))) {
                return true;
            }
        }
        return false;
    }

    private static boolean allowedCharacter(final int point) {
        return point >= 'A' && point <= 'Z'
                || point >= 'a' && point <= 'z'
                || point >= '0' && point <= '9'
                || ALLOWED_PUNCTUATION.indexOf(point) >= 0;
    }

    private static String jsonUnescaped(final String line) {
        return JSON_ESCAPE
                .matcher(line)
                .replaceAll(
                        escape ->
                                Matcher.quoteReplacement(
                                        switch (escape.group().charAt(1)) {
                                            case 'u' ->
                                                    String.valueOf(
                                                            (char)
                                                                    Integer.parseInt(
                                                                            escape.group()
                                                                                    .substring(2),
                                                                            16));
                                            case 'b' -> "\b";
                                            case 'f' -> "\f";
                                            case 'n' -> "\n";
                                            case 'r' -> "\r";
                                            case 't' -> "\t";
                                            default -> escape.group().substring(1);
                                        }));
    }

    private static String stripControls(final String line) {
        return CONTROLS.matcher(ANSI.matcher(line).replaceAll("")).replaceAll("");
    }

    /**
     * Everything the transport left on this account, once no project uses it any more: the
     * homeserver stopped and disabled, its container and database removed, its port and
     * registration token, and every poll position. The package stays; clearing is not uninstalling.
     * Run again, it finds nothing and says so.
     */
    /**
     * Where the unit keeps the id of its container: {@code %t/%N.cid}, in the account's runtime
     * directory.
     */
    static @Nullable Path cidFile(final Map<String, String> env) {
        final String runtime = env.get("XDG_RUNTIME_DIR");
        return runtime == null || runtime.isBlank() ? null : Path.of(runtime, UNIT + ".cid");
    }

    static Lifecycle.Report clear(final Map<String, String> env) throws Failure {
        final Lifecycle.Report report = new Lifecycle.Report();
        final boolean installed =
                systemctl("show", UNIT, "-p", "LoadState", "--value")
                        .filter(state -> state.strip().equals("loaded"))
                        .isPresent();
        if (installed) {
            final boolean enabled = systemctl("is-enabled", "-q", UNIT).isPresent();
            final boolean active = systemctl("is-active", "-q", UNIT).isPresent();
            if (enabled || active) {
                if (systemctl("disable", "--now", UNIT).isPresent()) {
                    report.removed("the homeserver: " + UNIT + " stopped and disabled");
                } else {
                    report.kept(
                            "the homeserver " + UNIT,
                            "systemctl --user disable --now " + UNIT + " failed");
                }
            }
        }
        // The published address goes with the homeserver: nothing may listen for it any more.
        final boolean published = Files.exists(Publish.dropIn(env));
        boolean closed = true;
        if (published) {
            closed = Publish.Systemd.closeSocket();
            if (closed) {
                report.removed("the homeserver's published address: " + Publish.SOCKET + " closed");
            } else {
                report.kept(
                        "the socket " + Publish.SOCKET,
                        "systemctl --user disable --now " + Publish.SOCKET + " failed");
            }
        }
        try {
            // Only the unit's own container, by the id podman gave it: one somebody else named so
            // is not ours.
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
            report.kept(
                    "the homeserver's container and database",
                    "cannot run podman: " + ex.getMessage());
        }
        final Path backups = Keep.backups(env);
        if (Files.isDirectory(backups)) {
            try (var walk = Files.walk(backups)) {
                for (final Path path : walk.sorted(java.util.Comparator.reverseOrder()).toList()) {
                    Files.delete(path);
                }
                report.removed("the homeserver's backups, " + backups);
            } catch (final IOException ex) {
                report.kept(backups.toString(), ex.getMessage());
            }
        }
        final Path conf = directory(env).resolve("homeserver.conf");
        delete(conf, report, "the homeserver's port, " + conf);
        final Path token = HomeserverToken.file(env);
        delete(token, report, "the homeserver's registration token, " + token);
        final Path earlier = directory(env).resolve("registration-token");
        if (Files.exists(earlier)) {
            delete(earlier, report, "the homeserver's registration token, " + earlier);
        }
        forgetPublished(env, report, closed);
        if (published) {
            systemctl("daemon-reload");
        }
        for (final Path dir : List.of(HomeserverToken.directory(env), directory(env))) {
            try {
                Files.deleteIfExists(dir);
            } catch (final IOException ex) {
                // Something else is in it, which is not the transport's to remove.
            }
        }
        final Path state = Poll.stateDirectory(env);
        if (Files.isDirectory(state)) {
            try (var walk = Files.walk(state)) {
                for (final Path path : walk.sorted(java.util.Comparator.reverseOrder()).toList()) {
                    Files.delete(path);
                }
                report.removed("every poll and room position and reference, " + state);
            } catch (final IOException ex) {
                report.kept(state.toString(), ex.getMessage());
            }
        }
        return report;
    }

    /**
     * What publishing made, once the socket is closed: its address, the CA and its key, the server
     * certificate and its key, and the TLS settings. Files only, so a run again finds nothing.
     */
    static void forgetPublished(
            final Map<String, String> env, final Lifecycle.Report report, final boolean closed)
            throws Failure {
        final Path dropIn = Publish.dropIn(env);
        // A socket still open keeps its address, so the next clear knows to close it.
        if (closed) {
            delete(dropIn, report, "the socket's address, " + dropIn);
        }
        try {
            final @Nullable Path dropIns = dropIn.getParent();
            if (dropIns != null) {
                Files.deleteIfExists(dropIns);
            }
        } catch (final IOException ex) {
            // Somebody else's drop-in is in it, which is not the transport's to remove.
        }
        final Path dir = directory(env);
        // A certificate made elsewhere is the host administrator's to remove, with its CA.
        final boolean placed = Certificate.placed(dir);
        for (final String file :
                placed
                        ? List.of(Publish.TLS_ENV, ".certificate.lock", ".tokens.lock")
                        : List.of(
                                Certificate.CA_CERTIFICATE,
                                Certificate.CA_KEY,
                                Publish.TLS_ENV,
                                ".certificate.lock",
                                ".tokens.lock")) {
            delete(
                    dir.resolve(file),
                    report,
                    "the homeserver's " + file + ", " + dir.resolve(file));
        }
        MachineTokens.forget(env, report);
        if (placed) {
            return;
        }
        final Path tls = dir.resolve(Certificate.TLS);
        for (final String file : List.of(Certificate.SERVER_CERTIFICATE, Certificate.SERVER_KEY)) {
            delete(
                    tls.resolve(file),
                    report,
                    "the homeserver's " + file + ", " + tls.resolve(file));
        }
        try {
            Files.deleteIfExists(tls);
        } catch (final IOException ex) {
            report.kept(tls.toString(), ex.getMessage());
        }
    }

    static void delete(final Path file, final Lifecycle.Report report, final String what) {
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
            final Process process =
                    new ProcessBuilder(command)
                            .redirectErrorStream(true)
                            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                            .start();
            return process.waitFor();
        } catch (final InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new Failure(Exit.TEMPORARY, "interrupted while running podman", ex);
        }
    }

    /** Runs {@code systemctl --user}, answering its output when it succeeded. */
    static Optional<String> systemctl(final String... args) throws Failure {
        final List<String> command = new ArrayList<>(List.of("systemctl", "--user"));
        command.addAll(List.of(args));
        try {
            final Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            final String output =
                    new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            return process.waitFor() == 0 ? Optional.of(output) : Optional.empty();
        } catch (final IOException ex) {
            throw new Failure(Exit.CONFIG, "cannot run systemctl --user: " + ex.getMessage(), ex);
        } catch (final InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new Failure(Exit.TEMPORARY, "interrupted while running systemctl", ex);
        }
    }
}
