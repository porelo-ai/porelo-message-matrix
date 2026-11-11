package org.fuin.porelo.message.matrix;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * The script that rehearses a project's room on a host in one call, with stand-ins for {@code ssh}
 * and {@code java}: the first opens the forward's local socket, or not, and stays until it is
 * killed; the second records what it was asked and ends as told.
 */
@Timeout(120)
class RehearseBusyRoomTest {

    private static final String SCRIPT = Path.of("build/rehearse-busy-room.sh").toString();

    private static final String SSH =
            """
            #!/bin/sh
            printf 'ssh %s\\n' "$*" >>"$CALLS"
            [ -z "${SSH_FAILS:-}" ] || exit 255
            local=
            while [ $# -gt 0 ]; do
                [ "$1" = -L ] && local=${2%%:*}
                shift
            done
            echo $$ >"$FORWARD_PID"
            [ -n "${SSH_NO_SOCKET:-}" ] || python3 -c \\
                'import socket, sys; socket.socket(socket.AF_UNIX).bind(sys.argv[1])' "$local"
            exec sleep 600
            """;

    private static final String JAVA =
            """
            #!/bin/sh
            printf 'java %s\\n' "$*" >>"$CALLS"
            exit "${JAVA_EXIT:-0}"
            """;

    @TempDir private Path dir;

    private Path calls;

    private record Run(int exit, String output) {}

    @BeforeEach
    void standIns() throws Exception {
        calls = dir.resolve("calls");
        final Path bin = Files.createDirectories(dir.resolve("bin"));
        executable(bin.resolve("ssh"), SSH);
        executable(bin.resolve("java"), JAVA);
    }

    /** Harm guarded: a wrong call reaching the host or the build, or a value smuggled by a line. */
    @Test
    void aWrongCallIsRefusedBeforeAnythingRuns() throws Exception {
        for (final List<String> wrong :
                List.of(
                        List.of("bob", "host.example.org"),
                        List.of("bob", "host.example.org", "foo", "More"),
                        List.of("bob", "host.example.org", "foo", "carol", "-oX"),
                        List.of("bob", "host.example.org", "foo", "carol\nx"),
                        List.of("Bob", "host.example.org", "foo"),
                        List.of("bob", "-oProxyCommand=x", "foo"),
                        List.of("bob", "host.example.org", "Foo"),
                        List.of("bob", "host.example.org", "foo\nbar"))) {
            final Run run = run(wrong, Map.of());

            assertThat(run.exit()).as("the exit: " + wrong + ": " + run.output()).isEqualTo(64);
            assertThat(run.output()).as("what it says").startsWith("usage: ");
        }
        assertThat(calls).as("nothing ran").doesNotExist();
    }

    /**
     * Harm guarded: the rehearsal run with the wrong project, socket or talk log, its exit lost, or
     * the forward left open after it.
     */
    @Test
    void theRehearsalRunsOverTheForwardAndItsExitIsTheScripts() throws Exception {
        final Run run = run(List.of("bob", "host.example.org", "foo"), Map.of("JAVA_EXIT", "65"));

        assertThat(run.exit()).as(run.output()).isEqualTo(65);
        final List<String> called = Files.readAllLines(calls);
        assertThat(called).as("the forward, then the rehearsal").hasSize(2);
        assertThat(called.getFirst())
                .as("the forward")
                .startsWith("ssh -N ")
                .contains(" -o BatchMode=yes ", " -o ExitOnForwardFailure=yes ")
                .matches(".* -L [^ ]+/client\\.sock:/run/porelo-matrix/client\\.sock .*")
                .endsWith(" bob@host.example.org");
        final String socket = called.getFirst().replaceAll(".* -L ([^ ]+/client\\.sock):.*", "$1");
        assertThat(called.get(1))
                .as("the rehearsal")
                .isEqualTo(
                        "java -cp /the/classpath org.fuin.porelo.message.matrix.RoomRehearsal"
                                + " --socket "
                                + socket
                                + " --project foo --talk-log ssh -o BatchMode=yes"
                                + " bob@host.example.org porelo talk log");
        assertThat(Path.of(socket).getParent()).as("its directory is gone").doesNotExist();
        final long forward = Long.parseLong(Files.readString(dir.resolve("forward.pid")).strip());
        assertThat(ProcessHandle.of(forward).map(ProcessHandle::isAlive))
                .as("the forward is closed")
                .isNotEqualTo(java.util.Optional.of(true));
    }

    /**
     * Harm guarded: the project's other persons not reaching the rehearsal, so their accounts and
     * helpers in the room are taken for strangers.
     */
    @Test
    void theProjectsOtherPersonsReachTheRehearsal() throws Exception {
        final Run run = run(List.of("bob", "host.example.org", "foo", "carol", "dave"), Map.of());

        assertThat(run.exit()).as(run.output()).isZero();
        assertThat(Files.readAllLines(calls).get(1))
                .as("the rehearsal")
                .contains(
                        " --project foo --person-login carol --person-login dave --talk-log ssh ");
    }

    /** Harm guarded: a host out of reach read as a room that misbehaves, or waited on for ever. */
    @Test
    void aForwardThatDoesNotComeUpEndsTemporarily() throws Exception {
        for (final Map<String, String> flags :
                List.of(Map.of("SSH_FAILS", "1"), Map.of("SSH_NO_SOCKET", "1", "WAIT", "2"))) {
            Files.deleteIfExists(calls);

            final Run run = run(List.of("bob", "host.example.org", "foo"), flags);

            assertThat(run.exit()).as(flags + ": " + run.output()).isEqualTo(75);
            assertThat(run.output())
                    .as("what it says")
                    .contains("the forward to host.example.org did not come up");
            assertThat(Files.readString(calls)).as("no rehearsal").doesNotContain("java ");
        }
    }

    private Run run(final List<String> arguments, final Map<String, String> flags)
            throws Exception {
        final List<String> command = new java.util.ArrayList<>(List.of("sh", SCRIPT));
        command.addAll(arguments);
        final ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
        builder.environment().clear();
        builder.environment().put("PATH", dir.resolve("bin") + ":/usr/bin:/bin");
        builder.environment().put("HOME", dir.toString());
        builder.environment().put("TMPDIR", dir.toString());
        builder.environment().put("CALLS", calls.toString());
        builder.environment().put("FORWARD_PID", dir.resolve("forward.pid").toString());
        builder.environment().put("REHEARSE_CLASSPATH", "/the/classpath");
        flags.forEach(
                (key, value) -> {
                    if (key.equals("WAIT")) {
                        builder.environment().put("REHEARSE_FORWARD_WAIT", value);
                    } else {
                        builder.environment().put(key, value);
                    }
                });
        final Process process = builder.start();
        final String output =
                new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(process.waitFor(60, TimeUnit.SECONDS)).as("the script ended").isTrue();
        return new Run(process.exitValue(), output);
    }

    private static void executable(final Path file, final String text) throws IOException {
        Files.writeString(file, text);
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rwx------"));
    }
}
