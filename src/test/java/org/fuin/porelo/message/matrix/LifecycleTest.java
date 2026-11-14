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
                "join --project default --person p",
                "members --project default"
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
     * Harm guarded: a registration token lent from the person's vault goes on towards a host's gate
     * on a socket or a {@code *.localhost}, which registers by the caller's login and needs none.
     */
    @Test
    void aTokenLentForAHostsGateIsDropped() throws Failure {
        for (final String homeserver :
                List.of("unix:/run/room.sock", "http://gate.localhost:8008")) {
            final Settings settings =
                    new Settings(URI.create(homeserver), null, true, "#p:example.org");
            assertThat(
                            Lifecycle.elsewhereToken(
                                    settings, Map.of(Config.REGISTRATION_TOKEN, "lent-token")))
                    .as("nothing lent goes on to %s", homeserver)
                    .isEmpty();
        }
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
     * Harm guarded: a task enrolled under a name the room's readers do not take as a task of its
     * project gets no line meant for it, and nothing says so.
     */
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(
            strings = {
                "porelo-demo_x",
                "porelo-other-x",
                "porelo-demo-",
                "worker",
                "porelo-demo-x.",
                "porelo-relay-demo-x"
            })
    void enrollRefusesATaskNameMembersCannotReadBack(final String task) throws Exception {
        try (FakeHomeserver homeserver = new FakeHomeserver()) {
            org.assertj.core.api.Assertions.assertThat(
                            reason(
                                    central(homeserver.url()),
                                    centralAccount(),
                                    "enroll",
                                    "--project",
                                    "demo",
                                    "--task",
                                    task))
                    .as("'%s' is no task of demo as members reads one", task)
                    .startsWith(Exit.CONFIG + " ")
                    .contains("porelo-demo-<task>");
            org.assertj.core.api.Assertions.assertThat(homeserver.requests())
                    .as("nothing reaches the homeserver for a refused name")
                    .isEmpty();
        }
        org.assertj.core.api.Assertions.assertThat(
                        reason("{}", account(), "enroll", "--project", "demo", "--task", task))
                .as("the account's own homeserver refuses '%s' before it is started", task)
                .startsWith(Exit.CONFIG + " ")
                .contains("porelo-demo-<task>");
    }

    /** The name members reads as a task of its project, on both homeservers. */
    @Test
    void aTaskNameOfTheProjectIsATask() {
        assertThat(Members.task("porelo-demo-x", "demo")).as("the contract's form").isTrue();
        assertThat(Members.task("porelo-demo-a.b", "demo")).as("a dot inside the task").isTrue();
        assertThat(Members.task("porelo-demo_x", "demo")).as("porelo's container form").isFalse();
        assertThat(Members.task("porelo-demo-x.", "demo"))
                .as("a trailing dot, which the host's gate refuses before the login")
                .isFalse();
        assertThat(Members.task("porelo-relay-x", "relay"))
                .as("a relay's name stays a helper's, even in a project called relay")
                .isFalse();
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
     * Harm guarded: a task started on a host while the gate's socket is down, or while its account
     * cannot open it, is made anyway and reports into no room; a 75 naming the socket and the cause
     * lets the start stop and say why.
     */
    @Test
    @org.junit.jupiter.api.DisplayName(
            "setup through a gate's socket that is down, or that the account cannot open, ends 75 naming it")
    void setupThroughASocketNotReachedIsTemporary(
            @org.junit.jupiter.api.io.TempDir final java.nio.file.Path dir) throws Exception {
        final java.nio.file.Path down = dir.resolve("down.sock");
        final java.nio.file.Path closed = dir.resolve("closed.sock");
        final Map<String, String> env =
                Map.of(
                        "XDG_STATE_HOME",
                        dir.resolve("state").toString(),
                        "PORELO_MATRIX_LOGIN",
                        "hosta");
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
            // The system's own words, in its language: a missing socket and a missing grant differ.
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

    private static final String NOT_FOUND = "{\"errcode\":\"M_NOT_FOUND\",\"error\":\"none\"}";

    /** The central room's answers up to its member lists, as members asks them. */
    private static void aClosedCentralRoom(final FakeHomeserver homeserver) {
        homeserver
                .answer(200, "{\"user_id\":\"@porelo.hosta:example.org\"}")
                .answer(200, "{\"joined_rooms\":[\"!room:example.org\"]}")
                .answer(200, "{\"join_rule\":\"invite\"}")
                .answer(200, "{\"guest_access\":\"forbidden\"}");
    }

    private static String member(final String id, final String membership, final String name) {
        return "{\"type\":\"m.room.member\",\"state_key\":\""
                + id
                + "\",\"content\":{\"membership\":\""
                + membership
                + "\",\"displayname\":\""
                + name
                + "\"}}";
    }

    /**
     * Harm guarded: the window says nobody is in a room two people and a task are in, or shows one
     * who left as still in, or a member under a name the room does not show.
     */
    @Test
    @org.junit.jupiter.api.DisplayName(
            "members on a central homeserver lists who is joined or invited, each once, and changes nothing")
    void membersListsTheCentralRoom() throws Exception {
        try (FakeHomeserver homeserver = new FakeHomeserver()) {
            aClosedCentralRoom(homeserver);
            homeserver
                    .answer(200, "{\"membership\":\"join\"}")
                    .answer(
                            200,
                            "{\"chunk\":["
                                    + member(
                                            "@porelo-demo-pi.hosta:example.org", "join", "Agent Pi")
                                    + ","
                                    + member("@ada:example.org", "join", "Ada\\u0007")
                                    + ","
                                    + member("@porelo-relay-demo.hosta:example.org", "join", "")
                                    + ","
                                    + member(
                                            "@porelo.hosta:example.org",
                                            "join",
                                            "Porelo (administrator)")
                                    + "]}")
                    .answer(
                            200,
                            "{\"chunk\":[" + member("@bea:example.org", "invite", "Bea") + "]}");

            final String answer =
                    out(
                            central(homeserver.url()),
                            centralAccount(),
                            "members",
                            "--project",
                            "demo",
                            "--login",
                            "hosta");

            assertThat(MatrixClient.JSON.readTree(answer))
                    .as("each member with its kind, state and the room's name")
                    .isEqualTo(
                            MatrixClient.JSON.readTree(
                                    "{\"room\":\"!room:example.org\",\"members\":["
                                            + "{\"id\":\"@porelo-demo-pi.hosta:example.org\",\"shown\":\"Agent Pi\","
                                            + "\"state\":\"join\",\"kind\":\"task\",\"task\":\"porelo-demo-pi\",\"login\":\"hosta\"},"
                                            + "{\"id\":\"@ada:example.org\",\"shown\":\"Ada\",\"state\":\"join\",\"kind\":\"person\"},"
                                            + "{\"id\":\"@bea:example.org\",\"shown\":\"Bea\",\"state\":\"invite\",\"kind\":\"person\"},"
                                            + "{\"id\":\"@porelo-relay-demo.hosta:example.org\","
                                            + "\"shown\":\"@porelo-relay-demo.hosta:example.org\",\"state\":\"join\",\"kind\":\"helper\"},"
                                            + "{\"id\":\"@porelo.hosta:example.org\",\"shown\":\"Porelo (administrator)\","
                                            + "\"state\":\"join\",\"kind\":\"helper\"}]}"));
            assertThat(homeserver.requests())
                    .as("both member lists are asked by membership, and nothing is written")
                    .allSatisfy(request -> assertThat(request.method()).isEqualTo("GET"))
                    .anySatisfy(request -> assertThat(request.query()).isEqualTo("membership=join"))
                    .anySatisfy(
                            request -> assertThat(request.query()).isEqualTo("membership=invite"));
        }
    }

    /** Harm guarded: another project's room is answered as this project's. */
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(
            strings = {
                "404:{\"errcode\":\"M_NOT_FOUND\",\"error\":\"none\"}",
                "200:{\"membership\":\"leave\"}",
                "200:{\"membership\":\"invite\"}"
            })
    @org.junit.jupiter.api.DisplayName(
            "members in a central room the project's relay is not joined to ends 78, naming it")
    void membersRefusesACentralRoomWithoutTheRelay(final String relay) throws Exception {
        try (FakeHomeserver homeserver = new FakeHomeserver()) {
            aClosedCentralRoom(homeserver);
            homeserver.answer(
                    Integer.parseInt(relay.substring(0, 3)),
                    relay.substring(relay.indexOf(':') + 1));

            assertThat(
                            reason(
                                    central(homeserver.url()),
                                    centralAccount(),
                                    "members",
                                    "--project",
                                    "demo",
                                    "--login",
                                    "hosta"))
                    .as("not this project's room")
                    .startsWith(Exit.CONFIG + " ")
                    .contains("@porelo-relay-demo.hosta:example.org");
        }
    }

    /**
     * Harm guarded: a login out of the room is shown the members of the moment it left, as if they
     * were in now.
     */
    @Test
    @org.junit.jupiter.api.DisplayName(
            "members in a central room the login is not in answers nobody, asking no list")
    void membersOutOfTheRoomAnswersNobody() throws Exception {
        try (FakeHomeserver homeserver = new FakeHomeserver()) {
            homeserver
                    .answer(200, "{\"user_id\":\"@porelo.hosta:example.org\"}")
                    .answer(200, "{\"joined_rooms\":[\"!other:example.org\"]}");

            assertThat(
                            out(
                                    central(homeserver.url()),
                                    centralAccount(),
                                    "members",
                                    "--project",
                                    "demo",
                                    "--login",
                                    "hosta"))
                    .as("an empty list")
                    .isEqualTo("{\"room\":\"!room:example.org\",\"members\":[]}\n");
            assertThat(homeserver.requests())
                    .as("no member list is asked")
                    .noneSatisfy(request -> assertThat(request.rawPath()).endsWith("/members"));
        }
    }

    /** Harm guarded: asking who is in starts a homeserver or makes a room before setup. */
    @Test
    @org.junit.jupiter.api.DisplayName("members before setup answers nobody, asking no homeserver")
    void membersBeforeSetupAnswersNobody() throws Exception {
        final List<String> unitCalls = new java.util.ArrayList<>();
        try (FakeHomeserver homeserver = new FakeHomeserver()) {
            for (final String settings : List.of("{}", central(homeserver.url()))) {
                final java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
                Lifecycle.run(
                        "members",
                        List.of("--project", "demo", "--login", "hosta"),
                        account(),
                        new java.io.ByteArrayInputStream(
                                settings.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                        new java.io.PrintStream(
                                bytes, true, java.nio.charset.StandardCharsets.UTF_8),
                        new java.io.PrintStream(java.io.OutputStream.nullOutputStream()),
                        recording(unitCalls));
                assertThat(bytes.toString(java.nio.charset.StandardCharsets.UTF_8))
                        .as("with the settings %s, an empty list", settings)
                        .isEqualTo(
                                "{}".equals(settings)
                                        ? "{\"members\":[]}\n"
                                        : "{\"room\":\"!room:example.org\",\"members\":[]}\n");
            }
            assertThat(homeserver.requests()).as("no homeserver is asked").isEmpty();
        }
        assertThat(unitCalls).as("no homeserver is looked at or started").isEmpty();
    }

    /**
     * Harm guarded: a missing room on the account's own homeserver is made, or ends in an error.
     */
    @Test
    @org.junit.jupiter.api.DisplayName(
            "members on the account's own homeserver with no room answers nobody")
    void membersWithNoRoomOnTheOwnHomeserverAnswersNobody() throws Exception {
        try (FakeHomeserver homeserver = new FakeHomeserver()) {
            homeserver.answer(200, "{\"user_id\":\"@porelo:example.org\"}").answer(404, NOT_FOUND);
            final Map<String, String> env = new java.util.HashMap<>(account());
            env.put(Config.HOMESERVER, homeserver.url());
            env.put(Config.ADMIN_TOKEN, "admin-token");

            assertThat(out("{}", env, "members", "--project", "demo"))
                    .as("an empty list under the project's alias")
                    .isEqualTo("{\"room\":\"#porelo-demo:example.org\",\"members\":[]}\n");
            assertThat(homeserver.requests())
                    .as("only read")
                    .allSatisfy(request -> assertThat(request.method()).isEqualTo("GET"));
        }
    }

    /** Harm guarded: a room behind a host's gate, named by its alias, is never found. */
    @Test
    @org.junit.jupiter.api.DisplayName(
            "members in a central room named by its alias reads the room the alias names")
    void membersResolvesACentralAlias() throws Exception {
        final String settings = "{\"homeserver\":\"%s\",\"room\":\"#porelo-demo:example.org\"}";
        try (FakeHomeserver homeserver = new FakeHomeserver()) {
            homeserver
                    .answer(200, "{\"user_id\":\"@porelo.hosta:example.org\"}")
                    .answer(200, "{\"room_id\":\"!room:example.org\"}")
                    .answer(200, "{\"joined_rooms\":[\"!room:example.org\"]}")
                    .answer(200, "{\"join_rule\":\"invite\"}")
                    .answer(200, "{\"guest_access\":\"forbidden\"}")
                    .answer(200, "{\"membership\":\"join\"}")
                    .answer(200, "{\"chunk\":[" + member("@ada:example.org", "join", "Ada") + "]}")
                    .answer(200, "{\"chunk\":[]}");

            assertThat(
                            out(
                                    settings.formatted(homeserver.url()),
                                    centralAccount(),
                                    "members",
                                    "--project",
                                    "demo"))
                    .as("the alias's room, under the alias")
                    .isEqualTo(
                            "{\"room\":\"#porelo-demo:example.org\",\"members\":[{\"id\":\"@ada:example.org\","
                                    + "\"shown\":\"Ada\",\"state\":\"join\",\"kind\":\"person\"}]}\n");
        }
        try (FakeHomeserver homeserver = new FakeHomeserver()) {
            homeserver
                    .answer(200, "{\"user_id\":\"@porelo.hosta:example.org\"}")
                    .answer(404, NOT_FOUND);

            assertThat(
                            out(
                                    settings.formatted(homeserver.url()),
                                    centralAccount(),
                                    "members",
                                    "--project",
                                    "demo"))
                    .as("an alias naming no room: nobody")
                    .isEqualTo("{\"room\":\"#porelo-demo:example.org\",\"members\":[]}\n");
        }
    }

    /** Harm guarded: who is in an open room is answered as who is in the project's. */
    @Test
    @org.junit.jupiter.api.DisplayName(
            "members in an open central room ends 78 and leaves nothing, asking only")
    void membersRefusesAnOpenCentralRoomWithoutLeavingIt() throws Exception {
        try (FakeHomeserver homeserver = new FakeHomeserver()) {
            homeserver
                    .answer(200, "{\"user_id\":\"@porelo.hosta:example.org\"}")
                    .answer(200, "{\"joined_rooms\":[\"!room:example.org\"]}")
                    .answer(200, "{\"join_rule\":\"public\"}")
                    .answer(200, "{\"guest_access\":\"can_join\"}");

            assertThat(
                            reason(
                                    central(homeserver.url()),
                                    centralAccount(),
                                    "members",
                                    "--project",
                                    "demo"))
                    .as("not a room the project's can be")
                    .startsWith(Exit.CONFIG + " ")
                    .contains("cannot be used as the project's room");
            assertThat(homeserver.requests())
                    .as("only asked")
                    .allSatisfy(request -> assertThat(request.method()).isEqualTo("GET"));
        }
    }

    /** Harm guarded: a room under the project's alias that someone else made is answered. */
    @Test
    @org.junit.jupiter.api.DisplayName(
            "members on the account's own homeserver in a room someone else made ends 78")
    void membersRefusesARoomTheAdministratorDidNotMake() throws Exception {
        try (FakeHomeserver homeserver = new FakeHomeserver()) {
            homeserver
                    .answer(200, "{\"user_id\":\"@porelo:example.org\"}")
                    .answer(200, "{\"room_id\":\"!other:example.org\"}")
                    .answer(
                            200,
                            "{\"chunk\":[{\"type\":\"m.room.create\",\"sender\":\"@eve:example.org\"}]}")
                    .answer(200, "{\"join_rule\":\"invite\"}")
                    .answer(200, "{\"guest_access\":\"forbidden\"}");

            assertThat(reason("{}", ownAccount(homeserver), "members", "--project", "demo"))
                    .as("not Porelo's room")
                    .startsWith(Exit.CONFIG + " ")
                    .contains("@eve:example.org");
        }
    }

    /** Harm guarded: a homeserver's broken answer is taken for a room nobody is in. */
    @Test
    @org.junit.jupiter.api.DisplayName(
            "members where the homeserver names no room for the alias ends 76")
    void membersRefusesAnAliasAnsweredWithNoRoom() throws Exception {
        for (final String found : List.of("{}", "{\"room_id\":null}", "{\"room_id\":\"\"}")) {
            try (FakeHomeserver homeserver = new FakeHomeserver()) {
                homeserver.answer(200, "{\"user_id\":\"@porelo:example.org\"}").answer(200, found);

                assertThat(reason("{}", ownAccount(homeserver), "members", "--project", "demo"))
                        .as("the alias answered %s", found)
                        .startsWith(Exit.PROTOCOL + " ");
            }
        }
    }

    /** Harm guarded: another login's name is taken, or none checked, before setup. */
    @Test
    @org.junit.jupiter.api.DisplayName(
            "members on a central homeserver before setup checks the login, then answers nobody")
    void membersBeforeSetupOnACentralHomeserverChecksTheLogin() throws Exception {
        final Map<String, String> env = new java.util.HashMap<>(account());
        env.put("PORELO_MATRIX_LOGIN", "hosta");
        final String settings = central("https://matrix.example.org");

        assertThat(reason(settings, env, "members", "--project", "demo", "--login", "bob"))
                .as("a login that is not the kept one")
                .startsWith(Exit.CONFIG + " --login bob does not match");
        assertThat(reason(settings, account(), "members", "--project", "demo"))
                .as("no login at all")
                .startsWith(Exit.CONFIG + " ");
        assertThat(out(settings, env, "members", "--project", "demo"))
                .as("the configured room, nobody in it yet")
                .isEqualTo("{\"room\":\"!room:example.org\",\"members\":[]}\n");
    }

    /** Harm guarded: an empty homeserver Porelo kept ends in an error instead of nobody. */
    @Test
    @org.junit.jupiter.api.DisplayName(
            "members on the account's own homeserver with an empty homeserver kept answers nobody")
    void membersWithABlankHomeserverAnswersNobody() throws Exception {
        final Map<String, String> env = new java.util.HashMap<>(account());
        env.put(Config.HOMESERVER, " ");
        env.put(Config.ADMIN_TOKEN, "admin-token");

        assertThat(out("{}", env, "members", "--project", "demo"))
                .as("not set up")
                .isEqualTo("{\"members\":[]}\n");
    }

    /** Harm guarded: an option members has no use for is taken silently. */
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(
            strings = {"--task t", "--person p", "--display D", "--reset", "--token"})
    void membersRefusesOptionsItHasNoUseFor(final String option) {
        final List<String> args =
                new java.util.ArrayList<>(List.of("members", "--project", "demo"));
        args.addAll(List.of(option.split(" ")));

        assertThat(reason("{}", account(), args.toArray(String[]::new)))
                .as(option)
                .startsWith(Exit.USAGE + " usage: members");
    }

    private Map<String, String> ownAccount(final FakeHomeserver homeserver) {
        final Map<String, String> env = new java.util.HashMap<>(account());
        env.put(Config.HOMESERVER, homeserver.url());
        env.put(Config.ADMIN_TOKEN, "admin-token");
        return env;
    }
}
