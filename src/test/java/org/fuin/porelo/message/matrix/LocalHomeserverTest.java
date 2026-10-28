package org.fuin.porelo.message.matrix;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The local homeserver's port: chosen once, kept in homeserver.conf. Starting the unit is measured
 * on the VM.
 */
class LocalHomeserverTest {

    @TempDir private Path dir;

    /** Harm guarded: setup must not wait on a new port while the active unit keeps its old one. */
    @Test
    @DisplayName("A newly written port stops an active unit before starting it")
    void aNewPortStopsAnActiveUnitBeforeStartingIt() throws Exception {
        final Map<String, String> env = Map.of("XDG_CONFIG_HOME", dir.toString());
        final List<String> calls = new ArrayList<>();
        final Failure started = new Failure(Exit.TEMPORARY, "end the test before waiting for HTTP");
        final Unit unit =
                new Unit() {
                    @Override
                    public boolean installed() {
                        return true;
                    }

                    @Override
                    public boolean active() {
                        return true;
                    }

                    @Override
                    public String state() {
                        throw new AssertionError("startup diagnostics must not be reached");
                    }

                    @Override
                    public List<String> recentLog() {
                        throw new AssertionError("startup diagnostics must not be reached");
                    }

                    @Override
                    public void start() throws Failure {
                        calls.add("start");
                        throw started;
                    }

                    @Override
                    public void stop() {
                        calls.add("stop");
                    }

                    @Override
                    public boolean hasDatabase() {
                        throw new AssertionError("setup must not read the database");
                    }

                    @Override
                    public void removeDatabase() {
                        throw new AssertionError("setup must not remove the database");
                    }

                    @Override
                    public void signalBackup() {
                        throw new AssertionError("setup must not request a backup");
                    }

                    @Override
                    public int runOnce(final List<String> args, final Map<String, String> secrets) {
                        throw new AssertionError("setup must not run the homeserver directly");
                    }
                };
        assertThat(LocalHomeserver.directory(env).resolve("homeserver.conf"))
                .as("the active unit started before its configuration existed")
                .doesNotExist();

        assertThatThrownBy(() -> LocalHomeserver.ensure(env, unit))
                .as("the stand-in ends setup at start, before the HTTP wait")
                .isSameAs(started);

        assertThat(calls)
                .as("the active unit must read the newly written port on its next start")
                .containsExactly("stop", "start");
    }

    /** Harm guarded: Porelo must receive the startup cause rather than shutdown noise. */
    @Test
    void theLastErrorPrecedesShutdownNoise() throws Exception {
        final String error = "Error: database directory cannot be created";
        final String stderr =
                startupError(
                        List.of(
                                "Error: an earlier failure",
                                error,
                                "2026-01-01 ERROR tuwunel: Critical error starting server: later logged error",
                                "CreateIOUring failed: Function not implemented (errno=38)"),
                        "failed",
                        Map.of());

        assertThat(stderr.lines().toList())
                .as("the timeout stays before the cause alone on the last stderr line")
                .containsExactly(
                        Main.NAME
                                + " setup: the homeserver at http://127.0.0.1:1 did not answer within 0 s",
                        error);
    }

    /** Harm guarded: an ERROR level must remain diagnosable without the plain error summary. */
    @Test
    void theLastErrorLevelIsTheFallback() throws Exception {
        final String error =
                "2026-01-01 ERROR tuwunel: Critical error starting server: bad setting";
        final String stderr =
                startupError(
                        List.of(
                                "ERROR tuwunel: earlier failure",
                                error,
                                "INFO an ERROR_CODE is not an error level"),
                        "failed",
                        Map.of());

        assertThat(stderr.lines().toList().getLast())
                .as("the last ERROR level is used when no Error summary exists")
                .isEqualTo(error);
    }

    static Stream<String> secretLines() {
        return Stream.of(
                "Error: registration token %s was rejected",
                "\u001b[31mError: registration token %s was rejected\u001b[0m",
                "2026-01-01 \u001b[31mERROR\u001b[0m tuwunel: Critical error starting server: %s",
                "Error: %s" + "a".repeat(400),
                "Error: " + "a".repeat(400) + "%s",
                "Error: \u001b]0;%s\u0007invalid setting");
    }

    /** Harm guarded: raw, colored, short, and truncated log errors must never leak a token. */
    @ParameterizedTest
    @MethodSource("secretLines")
    void aRegistrationTokenInTheChosenLineIsWithheld(final String format) throws Exception {
        final String token = "Q7";
        final String stderr = startupError(List.of(format.formatted(token)), "failed", Map.of());

        assertThat(stderr).as("the registration token never reaches stderr").doesNotContain(token);
        assertThat(stderr.lines().toList().getLast())
                .as("a secret-bearing error is explicitly withheld")
                .isEqualTo("the homeserver's last error was withheld: it held a secret");
    }

    /** Harm guarded: stripping ANSI or control characters must not assemble a printable secret. */
    @ParameterizedTest
    @ValueSource(strings = {"Q\u001b[31m7", "Q\u00007", "Q\r7"})
    void aRegistrationTokenRevealedByStrippingIsWithheld(final String colored) throws Exception {
        final String stderr = startupError(List.of("Error: " + colored), "failed", Map.of());

        assertThat(stderr)
                .as("a reconstructed registration token never reaches stderr")
                .doesNotContain("Q7");
        assertThat(stderr.lines().toList().getLast())
                .as("stripping cannot bypass the secret filter")
                .isEqualTo("the homeserver's last error was withheld: it held a secret");
    }

    /** Harm guarded: every credential already held by the transport must stay off stderr. */
    @ParameterizedTest
    @ValueSource(
            strings = {
                Config.ACCESS_TOKEN,
                Config.ADMIN_TOKEN,
                Config.REGISTRATION_TOKEN,
                Config.MACHINE_SECRET,
                "PORELO_MATRIX_ADMIN_PASSWORD"
            })
    void anEnvironmentSecretIsWithheldEvenWhenItIsOneCharacter(final String key) throws Exception {
        final String stderr =
                startupError(List.of("Error: credential Q is invalid"), "failed", Map.of(key, "Q"));

        assertThat(stderr).as("an environment credential never reaches stderr").doesNotContain("Q");
        assertThat(stderr.lines().toList().getLast())
                .as("short environment secrets are withheld too")
                .isEqualTo("the homeserver's last error was withheld: it held a secret");
    }

    /** Harm guarded: unknown credentials and encoded disclosures must stay off stderr. */
    @ParameterizedTest
    @ValueSource(
            strings = {
                "ToKeN=unrecognized",
                "PASSWORD=unrecognized",
                "passwd=unrecognized",
                "secret=unrecognized",
                "Authorization: Basic abc",
                "Bearer abc",
                "Cookie: abc",
                "session=abc",
                "access_token=abc",
                "api_key=abc",
                "apikey=abc",
                "https://person:abc@example.org/path",
                "%41",
                "\\u0041",
                "\\x41",
                "to ken=abc",
                "auth\u001b[31morization=abc",
                "https://person@example.org"
            })
    void credentialLikeContentIsWithheldWithoutAKnownSecret(final String content) throws Exception {
        final String stderr = startupError(List.of("Error: " + content), "failed", Map.of());

        assertThat(stderr.lines().toList().getLast())
                .as("credential-like content is withheld even without a known secret")
                .isEqualTo("the homeserver's last error was withheld: it held a secret");
    }

    /** Harm guarded: lookalikes must not bypass a filter that only recognizes ASCII credentials. */
    @ParameterizedTest
    @ValueSource(strings = {"ｔｏｋｅｎ=abc", "pаssword=abc", "a\u00a0b", "café", "🔒", "Ｑ７"})
    void nonAsciiContentIsWithheld(final String content) throws Exception {
        final String stderr = startupError(List.of("Error: " + content), "failed", Map.of());

        assertThat(stderr.lines().toList().getLast())
                .as("non-ASCII printable content cannot be proved safe")
                .isEqualTo("the homeserver's last error was withheld: it held a secret");
    }

    /** Harm guarded: alternate encodings and credential assignments must never leave on stderr. */
    @ParameterizedTest
    @ValueSource(
            strings = {
                "UTc=",
                "&#81;&#55;",
                "API-KEY=x",
                "Q+7",
                "a=b",
                "a%b",
                "a&b",
                "a#b",
                "a\\b",
                "a+b",
                "a@b",
                "a<b",
                "a>b",
                "a*b",
                "a$b",
                "a|b",
                "a^b",
                "a~b",
                "abcDEF012345",
                "01234567890z",
                "abc_def-1234",
                "0123456789abcdef",
                "u9M4r2K8",
                "abcde_1-"
            })
    void contentOutsideTheAllowlistIsWithheld(final String content) throws Exception {
        final String stderr = startupError(List.of("Error: " + content), "failed", Map.of());
        assertThat(stderr.lines().toList().getLast())
                .as("the allowlist withholds the complete unsafe message")
                .isEqualTo("the homeserver's last error was withheld: it held a secret");
    }

    /** Harm guarded: the grammar must preserve ordinary errors while rejecting token shapes. */
    @ParameterizedTest
    @ValueSource(
            strings = {
                "abcdefghijklmnopqrstuvwxyz",
                "12345678901234567890",
                "abcDE01",
                ".,:;()[]{} ` '\" _-/!?",
                "API-KEY: x",
                "api_key: x",
                "apikey: x",
                "token: x",
                "password: x",
                "passwd: x",
                "secret: x",
                "authorization: x",
                "bearer: x",
                "cookie: x",
                "session: x",
                "access_token: x"
            })
    void allowedTextAndCredentialWordsAreDistinguished(final String content) throws Exception {
        final boolean credential = content.endsWith(": x");
        final String stderr = startupError(List.of("Error: " + content), "failed", Map.of());
        assertThat(stderr.lines().toList().getLast())
                .as("allowed characters and short runs survive unless they name credentials")
                .isEqualTo(
                        credential
                                ? "the homeserver's last error was withheld: it held a secret"
                                : "Error: " + content);
    }

    /** Harm guarded: a secret hidden in stripped ANSI still needs composed decoding checks. */
    @ParameterizedTest
    @ValueSource(strings = {"Q+7", "%2551%2537", "\\u0025" + "51%37", "Ｑ ７"})
    void composedDecodingsOfRawContentStillProtectKnownSecrets(final String content)
            throws Exception {
        final String stderr =
                startupError(
                        List.of("\u001b]0;" + content + "\u0007Error: invalid setting"),
                        "failed",
                        Map.of());
        assertThat(stderr.lines().toList().getLast())
                .as("decoding compositions of the original message cannot disclose a known secret")
                .isEqualTo("the homeserver's last error was withheld: it held a secret");
    }

    /** Harm guarded: even a fragment of a known password must never reach stderr. */
    @ParameterizedTest
    @ValueSource(strings = {"u9M4r2K8", "u9M4r2K8.z6P3b7V5.d1H0n8F6.c4X2w9Y7"})
    void fragmentsOfAKnownPasswordAreWithheld(final String content) throws Exception {
        final String stderr =
                startupError(
                        List.of("Error: " + content),
                        "failed",
                        Map.of(),
                        "u9M4r2K8z6P3b7V5d1H0n8F6c4X2w9Y7");
        assertThat(stderr.lines().toList().getLast())
                .as("eight-character password fragments are withheld")
                .isEqualTo("the homeserver's last error was withheld: it held a secret");
    }

    static Stream<org.junit.jupiter.params.provider.Arguments> secretWindows() {
        final String secret = "abcdefgh.ijklmnop qrstuvwxyzABCDE";
        return java.util.stream.IntStream.rangeClosed(0, secret.length() - 8)
                .boxed()
                .flatMap(
                        start -> {
                            final String window = secret.substring(start, start + 8);
                            final String escaped =
                                    window.chars()
                                            .mapToObj(point -> "\\u%04x".formatted(point))
                                            .collect(java.util.stream.Collectors.joining());
                            return Stream.of(
                                            "Error: " + window,
                                            "\u001b]0;"
                                                    + escaped.replace("\\", "%255c")
                                                    + "\u0007Error: invalid setting")
                                    .map(
                                            line ->
                                                    org.junit.jupiter.params.provider.Arguments.of(
                                                            secret, line));
                        });
    }

    /** Harm guarded: punctuation, spaces, and composed decoding must not hide secret windows. */
    @ParameterizedTest
    @MethodSource("secretWindows")
    void everyKnownSecretWindowIsWithheld(final String secret, final String line) throws Exception {
        final String stderr =
                startupError(List.of(line), "failed", Map.of(Config.ADMIN_TOKEN, secret));
        assertThat(stderr.lines().toList().getLast())
                .as(
                        "every eight-character window is withheld, including decoded and composed forms")
                .isEqualTo("the homeserver's last error was withheld: it held a secret");
    }

    static Stream<org.junit.jupiter.params.provider.Arguments> disguisedSecrets() {
        return Stream.of(
                org.junit.jupiter.params.provider.Arguments.of("Q 7", "Q7"),
                org.junit.jupiter.params.provider.Arguments.of("Q\t7", "Q7"),
                org.junit.jupiter.params.provider.Arguments.of("%51%37", "Q7"),
                org.junit.jupiter.params.provider.Arguments.of("Q+7", "Q 7"),
                org.junit.jupiter.params.provider.Arguments.of("Q\\/7", "Q/7"),
                org.junit.jupiter.params.provider.Arguments.of("\\u0051\\u0037", "Q7"),
                org.junit.jupiter.params.provider.Arguments.of("Ｑ７", "Q7"));
    }

    /** Harm guarded: an encoded or spaced credential must not bypass literal matching. */
    @ParameterizedTest
    @MethodSource("disguisedSecrets")
    void normalizedKnownSecretsAreWithheld(final String content, final String secret)
            throws Exception {
        final String stderr =
                startupError(
                        List.of("Error: " + content), "failed", Map.of(Config.ADMIN_TOKEN, secret));

        assertThat(stderr.lines().toList().getLast())
                .as("known secrets are withheld in every decoded and normalized form")
                .isEqualTo("the homeserver's last error was withheld: it held a secret");
    }

    /** Harm guarded: retaking an account makes a password absent from the saved credentials. */
    @Test
    void aPasswordMadeWhileRetakingIsWithheldWithoutACredentialMarker() throws Exception {
        final String password = "u9M4r2K8z6P3b7V5d1H0n8F6c4X2w9Y7";
        final String stderr =
                startupError(List.of("Error: " + password), "failed", Map.of(), password);

        assertThat(stderr.lines().toList().getLast())
                .as("the extra password made by Keep.retake is withheld without marker words")
                .isEqualTo("the homeserver's last error was withheld: it held a secret");
        assertThat(stderr)
                .as("the generated password never reaches stderr")
                .doesNotContain(password);
    }

    /**
     * Harm guarded: unavailable registration credentials must prevent reading a log for disclosure.
     */
    @Test
    void anUnreadableRegistrationTokenGivesOnlyTheUnitState() throws Exception {
        final String stderr =
                startupError(
                        Map.of("XDG_CONFIG_HOME", dir.toString()),
                        new LoggedUnit(List.of("Error: unseen-credential"), "failed"));

        assertThat(stderr.lines().toList().getLast())
                .as("an unreadable registration token allows only the unit state")
                .isEqualTo("the homeserver's unit is failed");
    }

    /** Harm guarded: a failed start without an error must still supply Porelo a useful cause. */
    @ParameterizedTest
    @ValueSource(strings = {"active", "failed", "inactive"})
    void theUnitStateExplainsAStartWithoutAnError(final String state) throws Exception {
        final String stderr =
                startupError(List.of("INFO startup", "INFO ERROR_CODE=0"), state, Map.of());

        assertThat(stderr.lines().toList().getLast())
                .as("the unit state is the cause when the log has no error")
                .isEqualTo("the homeserver's unit is " + state);
    }

    /** Harm guarded: control codes or an unbounded log line must not corrupt Porelo's cause. */
    @Test
    void theCauseIsStrippedAndLimitedToThreeHundredCharacters() throws Exception {
        final String stderr =
                startupError(
                        List.of(
                                "\u001b[31mError: \u001b[0m\u001b]0;title\u0007\u0000\t\r\n\u007f\u0085\u2028\u2029"
                                        + "x".repeat(400)),
                        "failed",
                        Map.of());

        assertThat(stderr.lines().toList().getLast())
                .as("ANSI and controls are removed before keeping 300 characters")
                .isEqualTo("Error: " + "x".repeat(293));
    }

    /** Harm guarded: parsing or filtering a slow log must not extend the diagnostic budget. */
    @Test
    void diagnosticsBoundSlowLogTraversal() throws Exception {
        final List<String> slow =
                new java.util.AbstractList<>() {
                    @Override
                    public int size() {
                        return 1;
                    }

                    @Override
                    public String get(final int index) {
                        try {
                            Thread.sleep(Duration.ofSeconds(30));
                        } catch (final InterruptedException ex) {
                            Thread.currentThread().interrupt();
                        }
                        return "Error: delayed cause";
                    }
                };
        assertTimeoutPreemptively(
                Duration.ofSeconds(5),
                () -> {
                    final String stderr = startupError(slow, "failed", Map.of());
                    assertThat(stderr.lines().toList().getLast())
                            .as("a diagnostic budget includes traversing the log before filtering")
                            .isEqualTo("the homeserver's unit is unknown");
                });
    }

    private String startupError(
            final List<String> log,
            final String state,
            final Map<String, String> credentials,
            final String... secrets)
            throws Exception {
        final Map<String, String> env = new HashMap<>(credentials);
        env.put("XDG_CONFIG_HOME", dir.toString());
        Files.createDirectories(HomeserverToken.directory(env));
        Files.writeString(HomeserverToken.file(env), "Q7\n");
        return startupError(env, new LoggedUnit(log, state), secrets);
    }

    private String startupError(
            final Map<String, String> env, final Unit unit, final String... secrets)
            throws Exception {
        final ByteArrayOutputStream err = new ByteArrayOutputStream();
        try {
            LocalHomeserver.awaitUp(
                    URI.create("http://127.0.0.1:1"), Duration.ZERO, env, unit, secrets);
            throw new AssertionError("a homeserver with no time to answer must time out");
        } catch (final Failure failure) {
            final int exit =
                    Main.reportFailure(
                            "setup", failure, new PrintStream(err, true, StandardCharsets.UTF_8));
            assertThat(exit)
                    .as("the failed start keeps its temporary exit code")
                    .isEqualTo(Exit.TEMPORARY);
        }
        return err.toString(StandardCharsets.UTF_8);
    }

    /** A stopped unit whose startup log and state are observable without a machine service. */
    private record LoggedUnit(List<String> recentLog, String state) implements Unit {
        @Override
        public boolean installed() {
            return true;
        }

        @Override
        public boolean active() {
            return "active".equals(state);
        }

        @Override
        public void start() {
            throw new AssertionError("the unit was already started");
        }

        @Override
        public void stop() {
            throw new AssertionError("diagnostics must not stop the unit");
        }

        @Override
        public boolean hasDatabase() {
            throw new AssertionError("diagnostics must not read the database");
        }

        @Override
        public void removeDatabase() {
            throw new AssertionError("diagnostics must not remove the database");
        }

        @Override
        public void signalBackup() {
            throw new AssertionError("diagnostics must not request a backup");
        }

        @Override
        public int runOnce(final List<String> args, final Map<String, String> secrets) {
            throw new AssertionError("diagnostics must not run the homeserver");
        }
    }

    @Test
    @DisplayName(
            "Without a port in homeserver.conf, a free one from 8008 on is chosen and written there, beside what the file holds")
    void choosesAndKeeps() throws Exception {
        final Path conf = dir.resolve("porelo/matrix/homeserver.conf");
        Files.createDirectories(conf.getParent());
        Files.writeString(conf, "PORELO_MATRIX_SERVER_NAME=localhost\n", StandardCharsets.UTF_8);

        final int port = LocalHomeserver.port(conf);

        assertThat(port).isBetween(8008, 8099);
        assertThat(Files.readAllLines(conf, StandardCharsets.UTF_8))
                .containsExactly(
                        "PORELO_MATRIX_SERVER_NAME=localhost", "PORELO_MATRIX_PORT=" + port);
        assertThat(LocalHomeserver.port(conf)).as("chosen once, then kept").isEqualTo(port);
    }

    @Test
    @DisplayName("A port that is taken is passed over")
    void passesOverATakenPort() throws Exception {
        final Path conf = dir.resolve("homeserver.conf");
        final int first = LocalHomeserver.port(dir.resolve("probe.conf"));
        try (ServerSocket taken = new ServerSocket()) {
            taken.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), first));

            assertThat(LocalHomeserver.port(conf)).isNotEqualTo(first);
        }
    }

    @Test
    @DisplayName(
            "A port already in the file is used as it is, and one that is not a number is refused with 78")
    void usesTheFile() throws IOException, Failure {
        final Path conf =
                Files.writeString(
                        dir.resolve("homeserver.conf"),
                        "PORELO_MATRIX_PORT=18008\n",
                        StandardCharsets.UTF_8);
        assertThat(LocalHomeserver.port(conf)).isEqualTo(18008);

        Files.write(conf, List.of("PORELO_MATRIX_PORT=eighty"), StandardCharsets.UTF_8);
        assertThatThrownBy(() -> LocalHomeserver.port(conf))
                .isInstanceOf(Failure.class)
                .satisfies(ex -> assertThat(((Failure) ex).exitCode()).isEqualTo(Exit.CONFIG));
    }

    @Test
    @DisplayName("The configuration directory follows XDG_CONFIG_HOME, else HOME")
    void directory() throws Failure {
        assertThat(LocalHomeserver.directory(Map.of("XDG_CONFIG_HOME", "/c", "HOME", "/h")))
                .isEqualTo(Path.of("/c/porelo/matrix"));
        assertThat(LocalHomeserver.directory(Map.of("HOME", "/h")))
                .isEqualTo(Path.of("/h/.config/porelo/matrix"));
    }

    /**
     * Harm guarded: a port that takes the connection and never answers - another program on it, or
     * a homeserver hung while it starts - holds {@code setup}, and Porelo's task start with it,
     * without end.
     */
    @Test
    @DisplayName("A port that never answers ends the wait at its limit, with 75")
    void silentPortEndsTheWait() throws Exception {
        try (ServerSocket silent = new ServerSocket(0, 50, InetAddress.getLoopbackAddress())) {
            final List<Socket> held = new ArrayList<>();
            final Thread acceptor =
                    Thread.ofVirtual()
                            .start(
                                    () -> {
                                        try {
                                            while (true) {
                                                held.add(silent.accept());
                                            }
                                        } catch (final IOException ex) {
                                            // closed
                                        }
                                    });
            final URI url = URI.create("http://127.0.0.1:" + silent.getLocalPort());

            final Failure failure =
                    assertTimeoutPreemptively(
                            Duration.ofSeconds(30),
                            () -> {
                                try {
                                    LocalHomeserver.awaitUp(url, Duration.ofSeconds(3));
                                    return null;
                                } catch (final Failure ex) {
                                    return ex;
                                }
                            });

            assertThat(failure).as("the end of the wait").isNotNull();
            assertThat(failure.exitCode()).as("its exit").isEqualTo(Exit.TEMPORARY);
            silent.close();
            acceptor.join();
            for (final Socket socket : held) {
                socket.close();
            }
        }
    }

    /** Harm guarded: Porelo's account clear must not leave its homeserver and secrets behind. */
    @Test
    void accountClearTakesTheMachineName() throws Exception {
        prepareAccountClear();
        final Transport.Result plain = clearAccount(List.of());
        assertAccountCleared(plain);

        prepareAccountClear();
        final Transport.Result named = clearAccount(List.of("--machine", "node-1"));
        assertAccountCleared(named);
        assertThat(named.out())
                .as("the machine name changes no account-level answer")
                .isEqualTo(plain.out());
    }

    /** Harm guarded: malformed account clear arguments must not delete an account's data. */
    @Test
    void invalidAccountClearTouchesNothing() throws Exception {
        prepareAccountClear();
        for (final List<String> args :
                List.of(
                        List.of("--machine", "a b"),
                        List.of("--task", "t"),
                        List.of("--machine"),
                        List.of("--machine", "node-1", "--task", "t"),
                        List.of("--machine", "node-1", "--machine", "node-2"))) {
            final Transport.Result result = clearAccount(args);
            assertThat(result.exit()).as("invalid account clear: %s", args).isEqualTo(Exit.USAGE);
            for (final String file : accountFiles()) {
                assertThat(dir.resolve(file))
                        .as("%s remains after %s", file, args)
                        .hasContent("kept");
            }
            assertThat(dir.resolve("calls"))
                    .as("no unit or database command for %s", args)
                    .doesNotExist();
        }
    }

    /**
     * Harm guarded: a project's clear must never be taken for the account's, which deletes it all.
     */
    @Test
    void aProjectClearNamingItsMachineLeavesTheAccountAlone() throws Exception {
        prepareAccountClear();
        clearAccount(List.of("--project", "p", "--machine", "node-1"));
        for (final String file : accountFiles()) {
            assertThat(dir.resolve(file))
                    .as("%s remains after a project's clear", file)
                    .hasContent("kept");
        }
    }

    private static List<String> accountFiles() {
        return List.of(
                "active",
                "enabled",
                "database",
                "config/porelo/matrix/homeserver.conf",
                "config/porelo/matrix/tokens/registration-token",
                "state/porelo/transports/matrix/abc.since",
                "state/porelo/transports/matrix/room/abc.since",
                "state/porelo/matrix/backups/database/meta/3");
    }

    private void prepareAccountClear() throws IOException {
        for (final String file : accountFiles()) {
            final Path path = dir.resolve(file);
            Files.createDirectories(path.getParent());
            Files.writeString(path, "kept");
        }
        final Path bin = Files.createDirectories(dir.resolve("bin"));
        final Path systemctl = bin.resolve("systemctl");
        Files.writeString(
                systemctl,
                """
                #!/bin/sh
                echo systemctl >> "$HOME/calls"
                case "$*" in
                  '--user show porelo-matrix-homeserver -p LoadState --value') echo loaded ;;
                  '--user is-enabled -q porelo-matrix-homeserver') [ -f "$HOME/enabled" ] ;;
                  '--user is-active -q porelo-matrix-homeserver') [ -f "$HOME/active" ] ;;
                  '--user disable --now porelo-matrix-homeserver') /bin/rm -f "$HOME/active" "$HOME/enabled" ;;
                  *) exit 99 ;;
                esac
                """);
        final Path podman = bin.resolve("podman");
        Files.writeString(
                podman,
                """
                #!/bin/sh
                echo podman >> "$HOME/calls"
                case "$*" in
                  'volume exists porelo-matrix-homeserver') [ -f "$HOME/database" ] ;;
                  'volume rm -f porelo-matrix-homeserver') /bin/rm -f "$HOME/database" ;;
                  *) exit 99 ;;
                esac
                """);
        for (final Path command : List.of(systemctl, podman)) {
            Files.setPosixFilePermissions(command, PosixFilePermissions.fromString("rwx------"));
        }
    }

    private void assertAccountCleared(final Transport.Result result) {
        assertThat(result.exit()).as("account clear: %s", result.err()).isEqualTo(Exit.OK);
        for (final String file : accountFiles()) {
            assertThat(dir.resolve(file)).as("account clear removes %s", file).doesNotExist();
        }
    }

    private Transport.Result clearAccount(final List<String> args) throws Exception {
        final List<String> command =
                new ArrayList<>(
                        List.of(
                                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                                "-cp",
                                System.getProperty("surefire.test.class.path"),
                                ClearAccount.class.getName(),
                                "clear"));
        command.addAll(args);
        final Path out = dir.resolve("clear.out");
        final Path err = dir.resolve("clear.err");
        final ProcessBuilder builder =
                new ProcessBuilder(command)
                        .redirectOutput(out.toFile())
                        .redirectError(err.toFile());
        builder.environment().clear();
        builder.environment()
                .putAll(
                        Map.of(
                                "PATH",
                                dir.resolve("bin").toString(),
                                "HOME",
                                dir.toString(),
                                "XDG_CONFIG_HOME",
                                dir.resolve("config").toString(),
                                "XDG_STATE_HOME",
                                dir.resolve("state").toString()));
        final Process process = builder.start();
        // A project's clear reads its settings from standard input; an open one would hold it.
        process.getOutputStream().close();
        try {
            assertThat(process.waitFor(15, TimeUnit.SECONDS))
                    .as("account clear ends within 15 s")
                    .isTrue();
            return new Transport.Result(
                    process.exitValue(), Files.readString(out), Files.readString(err));
        } finally {
            process.destroyForcibly();
        }
    }

    /**
     * Runs the CLI with isolated stand-in commands, without changing the test JVM's environment.
     */
    public static final class ClearAccount {

        private ClearAccount() {}

        /** Runs the same entry point Porelo's invocation reaches. */
        public static void main(final String[] args) {
            System.exit(Main.run(args, System.getenv(), System.in, System.out, System.err));
        }
    }
}
