package org.fuin.porelo.message.matrix;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class MainTest {

    private final ByteArrayOutputStream errBytes = new ByteArrayOutputStream();

    private final ByteArrayOutputStream outBytes = new ByteArrayOutputStream();

    private final PrintStream err = new PrintStream(errBytes, true, StandardCharsets.UTF_8);

    @Test
    @DisplayName("Without a verb it prints its usage and exits with 64")
    void noVerb() {
        final int exit = Main.run(new String[0], Map.of(), err, err);

        assertThat(exit).isEqualTo(Exit.USAGE);
        assertThat(stderr()).startsWith("usage: porelo-message-transport-matrix");
    }

    @Test
    @DisplayName("An unknown verb is refused with 64, naming it")
    void unknownVerb() {
        final int exit = Main.run(new String[] {"deliver-anywhere"}, Map.of(), err, err);

        assertThat(exit).isEqualTo(Exit.USAGE);
        assertThat(stderr()).contains("unknown verb 'deliver-anywhere'");
    }

    /** Harm guarded: a person running a verb by hand waits on a terminal for input never given. */
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({
        "/dev/pts/3, true",
        "/dev/tty1, true",
        "pipe:[123456], false",
        "/tmp/transport-1.in, false",
        "/dev/null, false"
    })
    @DisplayName("A terminal on stdin is told from the pipe or file Porelo hands")
    void tellsATerminalOnStdin(
            final String target,
            final boolean terminal,
            @org.junit.jupiter.api.io.TempDir final java.nio.file.Path dir)
            throws java.io.IOException {
        final java.nio.file.Path link =
                java.nio.file.Files.createSymbolicLink(
                        dir.resolve("0"), java.nio.file.Path.of(target));

        assertThat(Main.isTerminal(link)).isEqualTo(terminal);
    }

    @Test
    @DisplayName("A stdin that is no link, or none, is not a terminal")
    void noLinkIsNoTerminal(@org.junit.jupiter.api.io.TempDir final java.nio.file.Path dir) {
        assertThat(Main.isTerminal(dir.resolve("absent"))).isFalse();
    }

    private int runWithInput(final String input, final String... args) {
        return Main.run(
                args,
                Map.of(Config.HOMESERVER, "http://127.0.0.1:1"),
                new java.io.ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)),
                new PrintStream(outBytes, true, StandardCharsets.UTF_8),
                err);
    }

    /** Harm guarded: a lifecycle verb reads the homeserver of the old way beside the input's. */
    @Test
    @DisplayName(
            "A lifecycle verb takes the homeserver Porelo kept from its input over the"
                    + " environment's")
    void aLifecycleVerbReadsItsSecretsFromTheInput() {
        final int exit =
                runWithInput(
                        "{\"settings\":{},\"secrets\":{\""
                                + Config.HOMESERVER
                                + "\":\"https://matrix.example.org\"}}",
                        "enroll",
                        "--project",
                        "p",
                        "--task",
                        "t",
                        "--loopback-only");

        assertThat(exit).as(stderr()).isEqualTo(Exit.CONFIG);
        assertThat(stderr())
                .as("the input's homeserver, refused for an offline project")
                .contains("its homeserver is https://matrix.example.org");
    }

    /**
     * Harm guarded: the administrator's token comes on a command line or in the environment because
     * {@code machine-token} reads it from no input.
     */
    @Test
    @DisplayName("machine-token takes the administrator's token from the input's secrets")
    void machineTokenReadsTheAdminTokenFromTheInput() {
        assertThat(runWithInput("{}", "machine-token", "expel", "../hosta"))
                .as("with none")
                .isEqualTo(Exit.CONFIG);
        assertThat(stderr()).as("with none").contains("expel needs the administrator's token");
        errBytes.reset();

        final int exit =
                runWithInput(
                        "{\"secrets\":{\"" + Config.ADMIN_TOKEN + "\":\"t\"}}",
                        "machine-token",
                        "expel",
                        "../hosta");

        assertThat(exit).as(stderr()).isEqualTo(Exit.USAGE);
        assertThat(stderr())
                .as("the token taken, the name refused before the homeserver is started")
                .contains("not a machine's name")
                .doesNotContain("administrator's token");
    }

    /** Harm guarded: a draft handed the new way is checked as something else. */
    @Test
    @DisplayName("settings reads the draft from the new way's object")
    void settingsReadsTheDraftFromTheInput() throws java.io.IOException {
        final int exit = runWithInput("{\"settings\":{\"tls_verify\":\"sometimes\"}}", "settings");

        assertThat(exit).as("a draft's faults are findings").isEqualTo(Exit.OK);
        assertThat(MatrixClient.JSON.readTree(outBytes.toByteArray()).path("refused").toString())
                .contains("tls_verify");
    }

    private String stderr() {
        return errBytes.toString(StandardCharsets.UTF_8);
    }
}
