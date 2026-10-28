package org.fuin.porelo.message.matrix;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.InputStream;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Guards the token join's command shape before any credentials or network are needed. */
class LifecycleTest {

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

    /** Harm guarded: a machine is admitted to a homeserver this transport does not run. */
    @Test
    void admitIsRefusedOnACentralHomeserver() {
        org.assertj.core.api.Assertions.assertThat(
                        exit(
                                "{\"homeserver\":\"https://matrix.example.org\",\"room\":\"#p:example.org\"}",
                                account(),
                                "admit",
                                "--project",
                                "demo",
                                "--machine",
                                "hostb"))
                .as("a central homeserver admits machines itself")
                .isEqualTo(Exit.NOT_PERMITTED);
    }

    /** Harm guarded: an offline project's room is opened to another machine. */
    @Test
    void admitIsRefusedForAnOfflineProject() throws Exception {
        // Published, so only the offline project's rule can refuse it.
        java.nio.file.Files.createDirectories(dir.resolve("config/porelo/matrix"));
        java.nio.file.Files.writeString(
                dir.resolve("config/porelo/matrix/homeserver.conf"),
                "PORELO_MATRIX_PORT=8008\nPORELO_MATRIX_PUBLISH=192.0.2.2:8448\n");
        org.assertj.core.api.Assertions.assertThat(
                        reason(
                                "{}",
                                account(),
                                "admit",
                                "--project",
                                "demo",
                                "--machine",
                                "hostb",
                                "--loopback-only"))
                .as("an offline project reaches loopback alone")
                .startsWith(Exit.CONFIG + " an offline project admits no other machine");
    }

    /** Harm guarded: a machine gets a token for a homeserver it cannot reach. */
    @Test
    void admitIsRefusedWhileNothingIsPublished() throws Exception {
        java.nio.file.Files.createDirectories(dir.resolve("config/porelo/matrix"));
        java.nio.file.Files.writeString(
                dir.resolve("config/porelo/matrix/homeserver.conf"), "PORELO_MATRIX_PORT=8008\n");

        org.assertj.core.api.Assertions.assertThat(
                        reason("{}", account(), "admit", "--project", "demo", "--machine", "hostb"))
                .as("nothing is published for the machine to reach")
                .startsWith(Exit.CONFIG + " the homeserver is published on no address");
    }

    /** Harm guarded: the transport registers with a token other than the one meant. */
    @Test
    void twoRegistrationTokensAreRefused() throws Exception {
        final java.nio.file.Path file =
                java.nio.file.Files.writeString(dir.resolve("token"), "from-the-file");
        java.nio.file.Files.setPosixFilePermissions(
                file, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
        final Map<String, String> env = new java.util.HashMap<>(account());
        env.put(Config.REGISTRATION_TOKEN, "from-the-environment");

        org.assertj.core.api.Assertions.assertThat(
                        exit(
                                "{\"homeserver\":\"https://matrix.example.org\",\"room\":\"#p:example.org\","
                                        + "\"registration_token_file\":\""
                                        + file
                                        + "\"}",
                                env,
                                "setup",
                                "--project",
                                "demo",
                                "--machine",
                                "hosta"))
                .as("which of the two was meant cannot be told")
                .isEqualTo(Exit.CONFIG);
    }

    /** Harm guarded: a mistyped dismiss guesses which machine it puts out. */
    @Test
    void dismissNeedsTheMachine() {
        org.assertj.core.api.Assertions.assertThat(
                        exit("{}", account(), "dismiss", "--project", "demo"))
                .as("no machine named, none dismissed")
                .isEqualTo(Exit.USAGE);
    }

    private Map<String, String> centralAccount() {
        final Map<String, String> env = new java.util.HashMap<>(account());
        env.put(Config.MACHINE_SECRET, "machine-secret");
        env.put(Config.ADMIN_TOKEN, "machine-token");
        env.put(Config.MACHINE, "hosta");
        return env;
    }

    private static String central(final String url) {
        return "{\"homeserver\":\"" + url + "\",\"room\":\"!room:example.org\"}";
    }

    /**
     * Harm guarded: a host whose machine the homeserver let go (dismissed, or the homeserver reset)
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
                                    "--machine",
                                    "hosta"))
                    .as("nothing is left on the homeserver to clear, and it says so")
                    .contains("the homeserver had let this machine go already");
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
                                "--machine",
                                "hosta"))
                .as("a homeserver not reached is temporary")
                .startsWith(Exit.TEMPORARY + " ")
                .contains("not reachable");
    }
}
