package org.fuin.porelo.message.matrix;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.InputStream;
import java.net.URI;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Guards the token join's command shape before any credentials or network are needed. */
class LifecycleTest {

    /** Harm guarded: a display option conceals a mistaken lifecycle command. */
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(
            strings = {
                "setup --project demo", "retire --project demo --task t",
                "part --project demo --person alice", "clear --project demo"
            })
    void displayIsRefusedOnOtherLifecycleVerbs(final String command) {
        final String[] args = (command + " --display Alice").split(" ");
        org.assertj.core.api.Assertions.assertThat(reason("{}", Map.of(), args))
                .as("display is refused by the command shape before configuration")
                .startsWith(Exit.USAGE + " usage: " + args[0]);
    }

    @Test
    void tokenAndDisplayReachConfigurationValidation() {
        assertThatThrownBy(
                        () ->
                                Lifecycle.run(
                                        "join",
                                        List.of(
                                                "--project",
                                                "demo",
                                                "--person",
                                                "core",
                                                "--token",
                                                "--display",
                                                "Agent Core"),
                                        Map.of(),
                                        InputStream.nullInputStream(),
                                        System.out,
                                        System.err,
                                        new PodmanUnit(Map.of())))
                .as(
                        "valid token join options reach configuration, rather than unknown-option refusal")
                .isInstanceOfSatisfying(
                        Failure.class,
                        failure ->
                                org.assertj.core.api.Assertions.assertThat(failure.exitCode())
                                        .as("configuration is missing")
                                        .isEqualTo(Exit.CONFIG));
    }

    @Test
    void joinDisplayHasTheSameValidationAsEnroll() {
        assertThatThrownBy(
                        () ->
                                Lifecycle.run(
                                        "join",
                                        List.of(
                                                "--project",
                                                "demo",
                                                "--person",
                                                "core",
                                                "--token",
                                                "--display",
                                                "Agent: Core"),
                                        Map.of(),
                                        InputStream.nullInputStream(),
                                        System.out,
                                        System.err,
                                        new PodmanUnit(Map.of())))
                .as("display validation runs before any state changes")
                .isInstanceOf(Failure.class)
                .hasMessageContaining("--display must be");
    }

    @org.junit.jupiter.api.io.TempDir private java.nio.file.Path dir;

    /** Answers every call as a unit that is not there, and records that it was asked. */
    private static Unit recording(final List<String> calls) {
        return (Unit)
                java.lang.reflect.Proxy.newProxyInstance(
                        Unit.class.getClassLoader(),
                        new Class<?>[] {Unit.class},
                        (proxy, method, args) -> {
                            calls.add(method.getName());
                            final Class<?> type = method.getReturnType();
                            if (type == boolean.class) {
                                return false;
                            }
                            if (type == String.class) {
                                return "";
                            }
                            return type == List.class ? List.of() : null;
                        });
    }

    /**
     * Harm guarded: a task of the project with no conversation joins a room, where it reads and is
     * read by others, because one wrong call or a room peer left over reached the transport.
     */
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(
            strings = {
                "setup --project default",
                "enroll --project default --task t",
                "join --project default --person p"
            })
    void theDefaultProjectGetsNoRoomOnAnyHomeserver(final String command) throws Exception {
        final List<String> args = List.of(command.split(" "));
        final List<String> unitCalls = new java.util.ArrayList<>();
        try (FakeHomeserver homeserver = new FakeHomeserver()) {
            for (final String settings : List.of("{}", central(homeserver.url()))) {
                assertThatThrownBy(
                                () ->
                                        Lifecycle.run(
                                                args.getFirst(),
                                                args.subList(1, args.size()),
                                                centralAccount(),
                                                new java.io.ByteArrayInputStream(
                                                        settings.getBytes(
                                                                java.nio.charset.StandardCharsets
                                                                        .UTF_8)),
                                                new java.io.PrintStream(
                                                        java.io.OutputStream.nullOutputStream()),
                                                new java.io.PrintStream(
                                                        java.io.OutputStream.nullOutputStream()),
                                                recording(unitCalls)))
                        .as("%s with the settings %s is refused", command, settings)
                        .isInstanceOfSatisfying(
                                Failure.class,
                                failure ->
                                        org.assertj.core.api.Assertions.assertThat(
                                                        failure.exitCode())
                                                .as("the refusal is one of configuration")
                                                .isEqualTo(Exit.CONFIG))
                        .hasMessageContaining("default");
            }
            org.assertj.core.api.Assertions.assertThat(homeserver.requests())
                    .as("no request reaches a homeserver before %s is refused", command)
                    .isEmpty();
        }
        org.assertj.core.api.Assertions.assertThat(unitCalls)
                .as("no homeserver is looked at or started before %s is refused", command)
                .isEmpty();
    }

    /** Harm guarded: a person left in a room under the name can never be taken out again. */
    @Test
    void aPersonLeftInARoomOfTheDefaultProjectCanStillBeTakenOut() throws Exception {
        try (FakeHomeserver homeserver = new FakeHomeserver()) {
            homeserver.answer(401, "{\"errcode\":\"M_UNKNOWN_TOKEN\",\"error\":\"unknown\"}");
            final Map<String, String> env = new java.util.HashMap<>(centralAccount());
            env.put(Config.HOMESERVER, homeserver.url());
            exit("{}", env, "part", "--project", "default", "--person", "p");
            org.assertj.core.api.Assertions.assertThat(homeserver.requests())
                    .as("part on the account's own homeserver reaches it")
                    .isNotEmpty();
        }
    }

    /** Harm guarded: a room left over under the name can never be taken away again. */
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(
            strings = {"clear --project default", "retire --project default --task t"})
    void whatIsLeftOverOfTheDefaultProjectCanStillGo(final String command) throws Exception {
        final List<String> args = List.of(command.split(" "));
        try (FakeHomeserver homeserver = new FakeHomeserver()) {
            homeserver.answer(401, "{\"errcode\":\"M_UNKNOWN_TOKEN\",\"error\":\"unknown\"}");
            exit(central(homeserver.url()), centralAccount(), args.toArray(String[]::new));
            org.assertj.core.api.Assertions.assertThat(homeserver.requests())
                    .as("%s reaches the homeserver", command)
                    .isNotEmpty();
        }
    }

    private int exit(final String settings, final Map<String, String> env, final String... args) {
        try {
            Lifecycle.run(
                    args[0],
                    List.of(args).subList(1, args.length),
                    env,
                    new java.io.ByteArrayInputStream(
                            settings.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                    new java.io.PrintStream(java.io.OutputStream.nullOutputStream()),
                    new java.io.PrintStream(java.io.OutputStream.nullOutputStream()),
                    new PodmanUnit(Map.of()));
            return Exit.OK;
        } catch (final Failure failure) {
            return failure.exitCode();
        }
    }

    /** What the verb printed on stdout. */
    private String out(final String settings, final Map<String, String> env, final String... args)
            throws Failure {
        final java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        Lifecycle.run(
                args[0],
                List.of(args).subList(1, args.length),
                env,
                new java.io.ByteArrayInputStream(
                        settings.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                new java.io.PrintStream(bytes, true, java.nio.charset.StandardCharsets.UTF_8),
                new java.io.PrintStream(java.io.OutputStream.nullOutputStream()),
                new PodmanUnit(Map.of()));
        return bytes.toString(java.nio.charset.StandardCharsets.UTF_8);
    }

    /** The reason a person reads, so a refusal is told from another that answers the same code. */
    private String reason(
            final String settings, final Map<String, String> env, final String... args) {
        try {
            Lifecycle.run(
                    args[0],
                    List.of(args).subList(1, args.length),
                    env,
                    new java.io.ByteArrayInputStream(
                            settings.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                    new java.io.PrintStream(java.io.OutputStream.nullOutputStream()),
                    new java.io.PrintStream(java.io.OutputStream.nullOutputStream()),
                    new PodmanUnit(Map.of()));
            return "";
        } catch (final Failure failure) {
            return failure.exitCode() + " " + failure.getMessage();
        }
    }

    private Map<String, String> account() {
        return Map.of("HOME", dir.toString(), "XDG_CONFIG_HOME", dir.resolve("config").toString());
    }

    /**
     * Harm guarded: the old setting still chooses a token from a file, or an account is registered
     * before the setting is refused.
     */
    @Test
    void settingsNamingARegistrationTokenFileAreRefusedBeforeAnything() throws Exception {
        final java.nio.file.Path file =
                java.nio.file.Files.writeString(dir.resolve("token"), "from-the-file");
        java.nio.file.Files.setPosixFilePermissions(
                file, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
        final String named =
                "{\"homeserver\":\"https://192.0.2.2\",\"room\":\"#p:example.org\","
                        + "\"registration_token_file\":\""
                        + file
                        + "\"}";

        org.assertj.core.api.Assertions.assertThat(
                        reason(named, account(), "setup", "--project", "demo", "--login", "hosta"))
                .as("refused by name, with no homeserver reached")
                .startsWith(Exit.CONFIG + " the project's settings name 'registration_token_file'");
    }

    /** Harm guarded: an invalid suffix can claim another login's accounts. */
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(
            strings = {
                "a.b",
                "Bob",
                "porelo",
                "porelo-x",
                "a12345678901234567890123456789012",
                "1bob",
                "bob=",
                ""
            })
    void invalidLoginsAreRefusedBeforeAnyRequest(final String login) {
        org.assertj.core.api.Assertions.assertThat(
                        reason(
                                central("https://matrix.example.org"),
                                account(),
                                "setup",
                                "--project",
                                "demo",
                                "--login",
                                login))
                .as("the suffix '%s' must never reach the homeserver", login)
                .startsWith(Exit.CONFIG + " ")
                .contains("login", "lower-case", "32", "porelo");
        final Map<String, String> env = new java.util.HashMap<>(centralAccount());
        env.put("PORELO_MATRIX_LOGIN", login);
        org.assertj.core.api.Assertions.assertThat(
                        reason(
                                central("https://matrix.example.org"),
                                env,
                                "retire",
                                "--project",
                                "demo",
                                "--task",
                                "work"))
                .as("kept login names follow the same rule")
                .startsWith(Exit.CONFIG + " ")
                // An empty kept login is the account's own homeserver's mark: no login at all.
                .contains("login", login.isEmpty() ? "--login" : "lower-case");
    }

    /** Harm guarded: one login's saved credentials are used to provision another's accounts. */
    @Test
    void aLoginMustMatchTheKeptLogin() {
        org.assertj.core.api.Assertions.assertThat(
                        reason(
                                central("https://matrix.example.org"),
                                centralAccount(),
                                "setup",
                                "--project",
                                "demo",
                                "--login",
                                "bob"))
                .as("a mismatched login is refused before registration")
                .startsWith(Exit.CONFIG + " --login bob does not match PORELO_MATRIX_LOGIN");
    }

    /**
     * Harm guarded: a caller using the obsolete option provisions accounts with the wrong scope.
     */
    @Test
    void centralVerbsRefuseTheMachineOption() {
        for (final String verb : List.of("setup", "enroll", "retire", "join", "part", "clear")) {
            org.assertj.core.api.Assertions.assertThat(
                            reason("{}", account(), verb, "--project", "demo", "--machine", "bob"))
                    .as("%s no longer takes --machine", verb)
                    .startsWith(Exit.USAGE + " usage:");
        }
    }

    /**
     * Harm guarded: the host's gate is treated as an external server needing a client-held token.
     */
    @Test
    void theHostsGateNeedsNoRegistrationToken() {
        org.assertj.core.api.Assertions.assertThat(
                        reason(
                                central("https://matrix.localhost"),
                                account(),
                                "setup",
                                "--project",
                                "demo",
                                "--login",
                                "bob"))
                .as("gate registration asks no token, whatever then comes of reaching it")
                .doesNotContain(Config.REGISTRATION_TOKEN);
        org.assertj.core.api.Assertions.assertThat(
                        reason(
                                central("https://matrix.example.org"),
                                account(),
                                "setup",
                                "--project",
                                "demo",
                                "--login",
                                "bob"))
                .as("an external homeserver still requires its registration token")
                .startsWith(Exit.CONFIG + " " + Config.REGISTRATION_TOKEN + " is not set");
    }

    /**
     * Harm guarded: a token lent for a homeserver behind a tunnel is dropped, so registering there
     * can never succeed.
     */
    @Test
    void aTokenLentForASocketIsKept() throws Failure {
        final Settings settings =
                new Settings(URI.create("unix:/run/room.sock"), null, true, "#p:example.org");
        assertThat(
                        Lifecycle.elsewhereToken(
                                settings, Map.of(Config.REGISTRATION_TOKEN, "lent-token")))
                .as("what was lent goes on")
                .isEqualTo("lent-token");
        assertThat(Lifecycle.elsewhereToken(settings, Map.of()))
                .as("a gate needs none, and only its challenge can tell")
                .isEmpty();
    }

    private Map<String, String> centralAccount() {
        final Map<String, String> env = new java.util.HashMap<>(account());
        env.put(Config.MACHINE_SECRET, "machine-secret");
        env.put(Config.ADMIN_TOKEN, "admin-token");
        env.put("PORELO_MATRIX_LOGIN", "hosta");
        return env;
    }

    private static String central(final String url) {
        return "{\"homeserver\":\"" + url + "\",\"room\":\"!room:example.org\"}";
    }

    /**
     * Harm guarded: a host whose machine the homeserver let go (expelled, or the homeserver reset)
     * can never clear, so its give-back stops on a refusal nobody can act on.
     */
    @Test
    @org.junit.jupiter.api.DisplayName(
            "clear on a central homeserver that no longer knows the machine's token ends 0")
    void clearAfterTheHomeserverLetTheMachineGo() throws Exception {
        try (FakeHomeserver homeserver = new FakeHomeserver()) {
            homeserver.answer(401, "{\"errcode\":\"M_UNKNOWN_TOKEN\",\"error\":\"unknown\"}");

            org.assertj.core.api.Assertions.assertThat(
                            out(
                                    central(homeserver.url()),
                                    centralAccount(),
                                    "clear",
                                    "--project",
                                    "demo",
                                    "--login",
                                    "hosta"))
                    .as("nothing is left on the homeserver to clear, and it says so")
                    .contains("the homeserver had let this login go already");
        }
    }

    /**
     * Harm guarded: a task started on a host while the tunnel to the room is down, or while its
     * account cannot open the socket, is made anyway and reports into no room; a 75 naming the
     * socket and the cause lets the start stop and say why.
     */
    @Test
    @org.junit.jupiter.api.DisplayName(
            "setup through a socket whose tunnel is down, or that the account cannot open, ends 75 naming it")
    void setupThroughASocketNotReachedIsTemporary(
            @org.junit.jupiter.api.io.TempDir final java.nio.file.Path dir) throws Exception {
        final java.nio.file.Path down = dir.resolve("down.sock");
        final java.nio.file.Path closed = dir.resolve("closed.sock");
        final Map<String, String> env =
                Map.of(
                        "XDG_STATE_HOME",
                        dir.resolve("state").toString(),
                        "PORELO_MATRIX_LOGIN",
                        "hosta",
                        Config.REGISTRATION_TOKEN,
                        "lent-token");
        try (java.nio.channels.ServerSocketChannel listener =
                java.nio.channels.ServerSocketChannel.open(java.net.StandardProtocolFamily.UNIX)) {
            listener.bind(java.net.UnixDomainSocketAddress.of(closed));
            java.nio.file.Files.setPosixFilePermissions(closed, java.util.Set.of());

            final List<String> causes = new java.util.ArrayList<>();
            for (final java.nio.file.Path socket : List.of(down, closed)) {
                final String reason =
                        reason(
                                central("unix:" + socket),
                                env,
                                "setup",
                                "--project",
                                "demo",
                                "--login",
                                "hosta");
                assertThat(reason)
                        .as(
                                "%s: a socket not reached is temporary, and named",
                                socket.getFileName())
                        .startsWith(Exit.TEMPORARY + " ")
                        .contains("unix:" + socket + " not reachable: ");
                causes.add(reason.substring(reason.indexOf("not reachable: ")));
            }
            // The system's own words, in its language: a missing tunnel and a missing grant differ.
            assertThat(causes.getLast())
                    .as("a socket the account cannot open names another cause than a missing one")
                    .isNotEqualTo(causes.getFirst());
        }
    }

    /**
     * Harm guarded: a homeserver briefly away is reported as a token that is not valid, so the
     * person looks for a fault in the setup that is not there, and nothing is tried again.
     */
    @Test
    @org.junit.jupiter.api.DisplayName(
            "clear on a central homeserver that cannot be reached ends 75, saying so")
    void clearWhileTheHomeserverIsNotReached() throws Exception {
        final String url;
        try (FakeHomeserver homeserver = new FakeHomeserver()) {
            url = homeserver.url();
        }

        org.assertj.core.api.Assertions.assertThat(
                        reason(
                                central(url),
                                centralAccount(),
                                "clear",
                                "--project",
                                "demo",
                                "--login",
                                "hosta"))
                .as("a homeserver not reached is temporary")
                .startsWith(Exit.TEMPORARY + " ")
                .contains("not reachable");
    }

    /**
     * Harm guarded: a homeserver that goes away while clear works through the room ends 0, so the
     * host is given back with accounts a later clear would have taken out.
     */
    @Test
    @org.junit.jupiter.api.DisplayName(
            "clear on a central homeserver that fails busy midway ends 75, not 0")
    void clearThatFailsBusyMidwayIsTemporary() throws Exception {
        try (FakeHomeserver homeserver = new FakeHomeserver()) {
            homeserver.answer(200, "{\"user_id\":\"@porelo.hosta:example.org\"}");
            homeserver.answer(503, "{\"errcode\":\"M_UNKNOWN\",\"error\":\"busy\"}");
            homeserver.answer(503, "{\"errcode\":\"M_UNKNOWN\",\"error\":\"busy\"}");

            org.assertj.core.api.Assertions.assertThat(
                            exit(
                                    central(homeserver.url()),
                                    centralAccount(),
                                    "clear",
                                    "--project",
                                    "demo",
                                    "--login",
                                    "hosta"))
                    .as("busy is temporary, worth trying again")
                    .isEqualTo(Exit.TEMPORARY);
        }
    }
}
