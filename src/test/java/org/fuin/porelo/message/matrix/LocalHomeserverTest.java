package org.fuin.porelo.message.matrix;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.io.IOException;
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
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The local homeserver's port: chosen once, kept in homeserver.conf. Starting the unit is measured
 * on the VM.
 */
class LocalHomeserverTest {

    @TempDir private Path dir;

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
                "config/porelo/matrix/registration-token",
                "state/porelo/transports/matrix/abc.since",
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
