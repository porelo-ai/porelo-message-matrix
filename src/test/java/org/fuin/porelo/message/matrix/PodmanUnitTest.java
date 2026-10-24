package org.fuin.porelo.message.matrix;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/** The run the unit's homeserver gets of its own, as podman is asked for it. */
class PodmanUnitTest {

    private static final String IMAGE = "ghcr.io/example/tuwunel@sha256:" + "0".repeat(64);

    @TempDir private Path dir;

    /** Harm guarded: a replaceable session launcher must never execute a diagnostic command. */
    @Test
    void anUntrustedSessionLauncherIsRefused() throws Exception {
        final Path launcher = dir.resolve("setsid");
        Files.writeString(launcher, "#!/bin/sh\necho ran > \"" + dir.resolve("executed") + "\"\n");
        Files.setPosixFilePermissions(launcher, PosixFilePermissions.fromString("rwxrwxrwx"));
        assertThatThrownBy(
                        () -> {
                            final Process process =
                                    new ProcessBuilder(
                                                    PodmanUnit.diagnosticCommand(
                                                            List.of("/bin/true"), launcher))
                                            .start();
                            assertThat(process.waitFor(1, TimeUnit.SECONDS))
                                    .as("the insecure launcher probe terminates promptly")
                                    .isTrue();
                        })
                .as("a launcher writable by others cannot be used")
                .isInstanceOf(IOException.class)
                .hasMessageContaining("must be root-owned and protected");
        assertThat(dir.resolve("executed")).as("the insecure launcher never ran").doesNotExist();
    }

    /** Harm guarded: the cause must be read from the unit even after its container is removed. */
    @Test
    void theRecentLogComesFromTheUserUnitsJournal() throws Exception {
        command(
                "journalctl",
                """
                [ "$*" = '--user -u porelo-matrix-homeserver.service -n 200 -o json' ] || exit 99
                echo '{"MESSAGE":"Error: database unavailable"}'
                echo '{"MESSAGE":"CreateIOUring failed: Function not implemented (errno=38)"}'
                """);

        final Transport.Result result = probe("log");

        assertThat(result.exit()).as("reading the unit's journal succeeds").isZero();
        assertThat(result.out().lines().toList())
                .as("the journal retains error and shutdown lines")
                .containsExactly(
                        "Error: database unavailable",
                        "CreateIOUring failed: Function not implemented (errno=38)");
        assertThat(result.err()).as("journal diagnostics never reach transport stderr").isEmpty();
    }

    /**
     * Harm guarded: a failed diagnostic read must not replace the startup failure or leak output.
     */
    @ParameterizedTest
    @ValueSource(strings = {"missing", "failed"})
    void anUnavailableJournalAnswersNoLines(final String how) throws Exception {
        if ("failed".equals(how)) {
            command(
                    "journalctl",
                    "echo 'Error: partial output'\necho 'private diagnostic' >&2\nexit 1\n");
        }

        final Transport.Result result = probe("log");

        assertThat(result.exit()).as("a journal read failure stays best effort").isZero();
        assertThat(result.out()).as("failed and missing log reads answer an empty list").isEmpty();
        assertThat(result.err()).as("a log read failure adds no stderr lines").isEmpty();
    }

    /** Harm guarded: without a log cause Porelo must receive the actual unit state. */
    @ParameterizedTest
    @ValueSource(strings = {"active", "failed", "inactive"})
    void theStateComesFromTheUserUnit(final String state) throws Exception {
        command(
                "systemctl",
                """
                [ "$*" = '--user show porelo-matrix-homeserver -p ActiveState --value' ] || exit 99
                echo "$PROBE_STATE"
                """);

        final Transport.Result result = probe(state);

        assertThat(result.exit()).as("the unit state can be read").isZero();
        assertThat(result.out().strip())
                .as("the actual state survives failed or inactive units")
                .isEqualTo(state);
    }

    /** Harm guarded: splitting a journal message can disclose part of a credential before a CR. */
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void aSecretAcrossACarriageReturnIsWithheldAsOneMessage(final boolean bytes) throws Exception {
        final String message = "Error: Q\r7\nshutdown";
        final var record = MatrixClient.JSON.createObjectNode();
        if (bytes) {
            final var array = record.putArray("MESSAGE");
            for (final byte value : message.getBytes(StandardCharsets.UTF_8)) {
                array.add(Byte.toUnsignedInt(value));
            }
        } else {
            record.put("MESSAGE", message);
        }
        Files.writeString(dir.resolve("fixture.cat"), message + "\n");
        Files.writeString(dir.resolve("fixture.json"), record + "\n");
        Files.createDirectories(dir.resolve("porelo/matrix"));
        Files.writeString(dir.resolve("porelo/matrix/registration-token"), "Q7\n");
        command(
                "journalctl",
                """
                case "$*" in
                  *'-o cat') /bin/cat "$XDG_CONFIG_HOME/fixture.cat" ;;
                  *'-o json') /bin/cat "$XDG_CONFIG_HOME/fixture.json" ;;
                  *) exit 99 ;;
                esac
                """);

        final Transport.Result result = probe("cause");

        assertThat(result.exit()).as("the diagnostic probe succeeds").isZero();
        assertThat(result.err().lines().toList().getLast())
                .as("the whole journal message is checked before a CR can split its secret")
                .isEqualTo("the homeserver's last error was withheld: it held a secret");
        assertThat(result.out()).as("the probe has no unfiltered stdout").isEmpty();
    }

    /** Harm guarded: a hung diagnostic command or inherited stdout must not hang setup. */
    @ParameterizedTest
    @ValueSource(strings = {"log", "state"})
    void aCommandThatHangsAnswersWithinAFewSeconds(final String operation) throws Exception {
        command("log".equals(operation) ? "journalctl" : "systemctl", "exec /bin/sleep 30\n");

        final Transport.Result result = probe(operation, 7);

        assertThat(result.exit()).as("the bounded diagnostic read succeeds").isZero();
        assertThat(result.out())
                .as("a hung command gives no diagnostic content")
                .isEqualTo("log".equals(operation) ? "" : "unknown\n");
    }

    /** Harm guarded: waiting for a stdout reader must not outlive a diagnostic timeout. */
    @ParameterizedTest
    @ValueSource(strings = {"log", "state"})
    void aChildHoldingStdoutOpenAnswersWithinAFewSeconds(final String operation) throws Exception {
        command("log".equals(operation) ? "journalctl" : "systemctl", "/bin/sleep 30 &\nwait\n");

        final Transport.Result result = probe(operation, 7);

        assertThat(result.exit()).as("the bounded read with inherited stdout succeeds").isZero();
        assertThat(result.out())
                .as("a child holding stdout gives no diagnostic content")
                .isEqualTo("log".equals(operation) ? "" : "unknown\n");
    }

    /** Harm guarded: a failed journal read must leave a useful and safe unit-state fallback. */
    @Test
    void aFailedLogReadGivesTheUnitState() throws Exception {
        Files.createDirectories(dir.resolve("porelo/matrix"));
        Files.writeString(dir.resolve("porelo/matrix/registration-token"), "Q7\n");
        command("journalctl", "echo 'Error: partial private output'\nexit 1\n");
        command("systemctl", "echo failed\n");

        final Transport.Result result = probe("cause");

        assertThat(result.exit()).as("the fallback probe succeeds").isZero();
        assertThat(result.err().lines().toList().getLast())
                .as("a failed log read falls back to the unit state")
                .isEqualTo("the homeserver's unit is failed");
    }

    static Stream<Arguments> realCauses() {
        return Stream.of(
                        "Error: I/O error: Resource temporarily unavailable (os error 11)",
                        "Error: I/O error: Failed to create RocksDB directory: `Os { code: 2, kind: NotFound, message: \"No such file or directory\" }`.",
                        "Error: There was a problem with your configuration file: data did not match any variant of untagged enum Either for key \"PORT\" in `TUWUNEL_` environment variable(s)")
                .flatMap(
                        message ->
                                Stream.of(
                                        Arguments.of(message, false), Arguments.of(message, true)));
    }

    /** Harm guarded: over-filtering must not hide the measured causes of a failed start. */
    @ParameterizedTest
    @MethodSource("realCauses")
    void theMeasuredCausesReachTheCompleteLastStderrLine(final String message, final boolean bytes)
            throws Exception {
        journal(message, bytes);
        final Transport.Result result = probe("cause");
        assertThat(result.err().lines().toList().getLast())
                .as("the measured real cause reaches stderr unchanged")
                .isEqualTo(message);
    }

    /** Harm guarded: unbounded diagnostic output must not exhaust memory or hang setup. */
    @ParameterizedTest
    @ValueSource(strings = {"log", "state"})
    void oversizedOutputDiscardsTheWholeRead(final String operation) throws Exception {
        final String output =
                "log".equals(operation)
                        ? "{\"MESSAGE\":\"Error: earlier safe cause\"}\n"
                                + "{\"MESSAGE\":\""
                                + "x".repeat(1024 * 1024)
                                + "\"}\n"
                        : " ".repeat(1024 * 1024) + "active\n";
        Files.writeString(dir.resolve("output"), output);
        command(
                "log".equals(operation) ? "journalctl" : "systemctl",
                "/bin/cat \"$XDG_CONFIG_HOME/output\"\n");
        final Transport.Result result = probe(operation, 7);
        assertThat(result.out().equals("log".equals(operation) ? "" : "unknown\n"))
                .as("an oversized %s read discards even its safe prefix", operation)
                .isTrue();
    }

    /** Harm guarded: the output cap must not discard reads at its exact maximum. */
    @ParameterizedTest
    @ValueSource(strings = {"log", "state"})
    void outputAtTheLimitStillSuppliesDiagnostics(final String operation) throws Exception {
        final String prefix =
                "log".equals(operation) ? "{\"MESSAGE\":\"Error: safe cause\"}" : "active";
        Files.writeString(
                dir.resolve("output"),
                prefix + " ".repeat(1024 * 1024 - prefix.length() - 1) + "\n");
        command(
                "log".equals(operation) ? "journalctl" : "systemctl",
                "/bin/cat \"$XDG_CONFIG_HOME/output\"\n");
        assertThat(probe(operation, 7).out())
                .as("a read of exactly 1 MiB remains useful")
                .isEqualTo("log".equals(operation) ? "Error: safe cause\n" : "active\n");
    }

    /** Harm guarded: oversized messages must be skipped whole before parsing a startup cause. */
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void oversizedMessagesAreSkippedRatherThanTruncated(final boolean bytes) throws Exception {
        journal("Error: " + "x".repeat(4090), bytes);
        final Transport.Result result = probe("log", 7);
        assertThat(result.out().isEmpty()).as("a 4097-character message is skipped whole").isTrue();
    }

    /** Harm guarded: the message bound must still allow its exact maximum. */
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void aMessageAtTheLimitIsKept(final boolean bytes) throws Exception {
        final String message = "Error: " + "x".repeat(4089);
        journal(message, bytes);
        assertThat(probe("log").out())
                .as("4096 characters are within the journal message bound")
                .isEqualTo(message + "\n");
    }

    /** Harm guarded: a command's exiting parent must not leave a child running on the machine. */
    @ParameterizedTest
    @ValueSource(strings = {"log", "state", "timeout"})
    void everyObservedChildIsGoneAfterDiagnostics(final String operation) throws Exception {
        command(
                "state".equals(operation) ? "systemctl" : "journalctl",
                "/bin/sleep 30 &\necho $! > \"$XDG_CONFIG_HOME/child.pid\"\n"
                        + ("timeout".equals(operation) ? "wait\n" : "/bin/sleep 0.2\n"));
        try {
            probe("state".equals(operation) ? "state" : "log", 7);
            final long pid = Long.parseLong(Files.readString(dir.resolve("child.pid")).strip());
            assertThat(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false))
                    .as("the recorded diagnostic child is no longer alive after %s", operation)
                    .isFalse();
        } finally {
            if (Files.exists(dir.resolve("child.pid"))) {
                ProcessHandle.of(Long.parseLong(Files.readString(dir.resolve("child.pid")).strip()))
                        .ifPresent(ProcessHandle::destroyForcibly);
            }
        }
    }

    /** Harm guarded: a child born between descendant samples must not outlive diagnostics. */
    @ParameterizedTest
    @ValueSource(strings = {"log", "state", "timeout"})
    void theWholeDiagnosticGroupIsGoneAfterItsParentExits(final String operation) throws Exception {
        command(
                "state".equals(operation) ? "systemctl" : "journalctl",
                "read pid comm state ppid pgrp rest < /proc/$$/stat\n"
                        + "echo \"$pgrp\" > \"$XDG_CONFIG_HOME/group.pid\"\n"
                        + "/bin/sleep .015\n/bin/sleep 20 &\n"
                        + "echo $! > \"$XDG_CONFIG_HOME/child.pid\"\n"
                        + ("timeout".equals(operation) ? "wait\n" : "exit\n"));
        try {
            probe("state".equals(operation) ? "state" : "log", 7, true);
            final long group = Long.parseLong(Files.readString(dir.resolve("group.pid")).strip());
            assertThat(liveGroup(group))
                    .as("no live process remains in diagnostic group %s after %s", group, operation)
                    .isEmpty();
        } finally {
            if (Files.exists(dir.resolve("child.pid"))) {
                ProcessHandle.of(Long.parseLong(Files.readString(dir.resolve("child.pid")).strip()))
                        .ifPresent(ProcessHandle::destroyForcibly);
            }
        }
    }

    private static List<Long> liveGroup(final long group) {
        return ProcessHandle.allProcesses()
                .filter(
                        process -> {
                            try {
                                final String stat =
                                        Files.readString(
                                                Path.of("/proc", "" + process.pid(), "stat"));
                                final String[] fields =
                                        stat.substring(stat.lastIndexOf(')') + 2).split(" ");
                                return !"Z".equals(fields[0]) && Long.parseLong(fields[2]) == group;
                            } catch (final IOException ex) {
                                return false;
                            }
                        })
                .map(ProcessHandle::pid)
                .toList();
    }

    private void journal(final String message, final boolean bytes) throws IOException {
        final var record = MatrixClient.JSON.createObjectNode();
        if (bytes) {
            final var array = record.putArray("MESSAGE");
            for (final byte value : message.getBytes(StandardCharsets.UTF_8)) {
                array.add(Byte.toUnsignedInt(value));
            }
        } else {
            record.put("MESSAGE", message);
        }
        Files.writeString(dir.resolve("output"), record + "\n");
        Files.createDirectories(dir.resolve("porelo/matrix"));
        Files.writeString(dir.resolve("porelo/matrix/registration-token"), "Q7\n");
        command("journalctl", "/bin/cat \"$XDG_CONFIG_HOME/output\"\n");
    }

    private void command(final String name, final String script) throws IOException {
        final Path file = Files.writeString(dir.resolve(name), "#!/bin/sh\n" + script);
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rwx------"));
    }

    private Transport.Result probe(final String state) throws Exception {
        return probe(state, 15);
    }

    private Transport.Result probe(final String state, final int seconds) throws Exception {
        return probe(state, seconds, false);
    }

    private Transport.Result probe(final String state, final int seconds, final boolean ownSession)
            throws Exception {
        final Path out = dir.resolve("probe.out");
        final Path err = dir.resolve("probe.err");
        final List<String> invocation =
                new ArrayList<>(
                        List.of(
                                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                                "-cp",
                                System.getProperty("surefire.test.class.path"),
                                JournalProbe.class.getName(),
                                state));
        if (ownSession) {
            invocation.addFirst("/usr/bin/setsid");
        }
        final ProcessBuilder builder =
                new ProcessBuilder(invocation)
                        .redirectOutput(out.toFile())
                        .redirectError(err.toFile());
        builder.environment().clear();
        builder.environment().put("PATH", dir.toString());
        builder.environment().put("PROBE_STATE", state);
        builder.environment().put("XDG_CONFIG_HOME", dir.toString());
        final Process process = builder.start();
        try {
            assertThat(process.waitFor(seconds, TimeUnit.SECONDS))
                    .as("%s diagnostic finishes within %s seconds", state, seconds)
                    .isTrue();
            return new Transport.Result(
                    process.exitValue(), Files.readString(out), Files.readString(err));
        } finally {
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
        }
    }

    /** Reads the unit through isolated commands without changing the test JVM's environment. */
    public static final class JournalProbe {
        private JournalProbe() {}

        /** Prints only what the transport would receive from the unit. */
        public static void main(final String[] args) throws Exception {
            // Ownership is namespace-dependent; exercise the actual session and cleanup here.
            final PodmanUnit unit = new PodmanUnit(System.getenv(), path -> {});
            if ("log".equals(args[0])) {
                unit.recentLog().forEach(System.out::println);
            } else if ("cause".equals(args[0])) {
                try {
                    LocalHomeserver.awaitUp(
                            URI.create("http://127.0.0.1:1"), Duration.ZERO, System.getenv(), unit);
                    throw new AssertionError("the zero-duration start must time out");
                } catch (final Failure failure) {
                    Main.reportFailure("setup", failure, System.err);
                }
            } else {
                System.out.println(unit.state());
            }
        }
    }

    /** Harm guarded: backup and restore must not fail from core-scaled resource use. */
    @Test
    void boundsEveryTuwunelSettingBeforeTheImage() {
        final List<String> command =
                PodmanUnit.command("localhost", Path.of("/b"), IMAGE, List.of(), Set.of());
        final List<String> options = command.subList(0, command.indexOf(IMAGE));

        HomeserverBounds.SETTINGS.forEach(
                (key, value) ->
                        assertThat(options)
                                .as("the one-shot run passes %s before the image", key)
                                .containsSequence("-e", key + "=" + value));
    }

    /** Harm guarded: backup and restore must not exhaust the host's resources. */
    @Test
    void boundsTheContainerBeforeTheImage() {
        final List<String> command =
                PodmanUnit.command("localhost", Path.of("/b"), IMAGE, List.of(), Set.of());

        assertThat(command.subList(0, command.indexOf(IMAGE)))
                .as("the one-shot run bounds the container before its image")
                .containsAll(HomeserverBounds.PODMAN_FLAGS);
    }

    @Test
    @DisplayName(
            "a secret for the run is named on podman's command line, never given there, and the run's output stays out of the journal")
    void aSecretIsNeverOnTheCommandLine() {
        final List<String> command =
                PodmanUnit.command(
                        "localhost",
                        Path.of("/state/porelo/matrix/backups"),
                        IMAGE,
                        List.of(),
                        Set.of("TUWUNEL_ADMIN_EXECUTE"));

        assertThat(command)
                .as("the secret's name alone, after -e")
                .containsSubsequence("-e", "TUWUNEL_ADMIN_EXECUTE")
                .noneMatch(arg -> arg.startsWith("TUWUNEL_ADMIN_EXECUTE="));
        assertThat(command)
                .as("the run's output may repeat the secret")
                .contains("--log-driver=none");
        assertThat(command)
                .as("no port published, no registration")
                .doesNotContain("-p")
                .contains("TUWUNEL_ALLOW_REGISTRATION=false");
    }

    @Test
    @DisplayName(
            "the run's own arguments come after the image, so podman never reads them as its own")
    void argumentsFollowTheImage() {
        final List<String> command =
                PodmanUnit.command(
                        "localhost",
                        Path.of("/b"),
                        IMAGE,
                        List.of("--restore-backup", "--execute", "server shutdown"),
                        Set.of());

        assertThat(command.subList(command.indexOf(IMAGE) + 1, command.size()))
                .as("after the image")
                .containsExactly("--restore-backup", "--execute", "server shutdown");
    }
}
