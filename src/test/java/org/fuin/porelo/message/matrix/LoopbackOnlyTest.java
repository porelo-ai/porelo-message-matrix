package org.fuin.porelo.message.matrix;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * {@code --loopback-only}, which Porelo passes for an offline project: a homeserver elsewhere is
 * refused before any request, since reaching it at all is what an offline project must never do.
 */
class LoopbackOnlyTest {

    private FakeHomeserver homeserver;

    private final ByteArrayOutputStream errBytes = new ByteArrayOutputStream();

    @BeforeEach
    void setUp() throws IOException {
        homeserver = new FakeHomeserver();
    }

    @AfterEach
    void tearDown() {
        homeserver.close();
    }

    private int run(final String settings, final String... args) {
        return Main.run(
                args,
                Map.of(
                        Config.REGISTRATION_TOKEN,
                        "t",
                        Config.HOMESERVER,
                        "https://matrix.example.org",
                        Config.ADMIN_TOKEN,
                        "a"),
                new ByteArrayInputStream(settings.getBytes(StandardCharsets.UTF_8)),
                new PrintStream(OutputStream.nullOutputStream()),
                new PrintStream(errBytes, true, StandardCharsets.UTF_8));
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({"setup", "enroll", "join"})
    @DisplayName(
            "A homeserver that is not loopback is refused with 78, before anything is asked of it")
    void refusedBeforeAnyRequest(final String verb) {
        final String[] args =
                switch (verb) {
                    case "setup" -> new String[] {"setup", "--project", "p", "--loopback-only"};
                    case "enroll" ->
                            new String[] {
                                "enroll", "--project", "p", "--task", "t", "--loopback-only"
                            };
                    default ->
                            new String[] {
                                "join", "--project", "p", "--person", "n", "--loopback-only"
                            };
                };
        final String remote = "https://matrix.example.org";

        assertThat(run("{\"homeserver\":\"" + remote + "\"}", args)).isEqualTo(Exit.CONFIG);
        assertThat(errBytes.toString(StandardCharsets.UTF_8))
                .contains("only this machine's loopback");
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({"127.0.0.1", "localhost", "[::1]"})
    @DisplayName("A loopback homeserver passes the check - what follows is the verb's own")
    void loopbackPasses(final String host) {
        // The fake's port, so nothing outside the test is reached, whatever the name resolves to.
        final String url = homeserver.url().replace("127.0.0.1", host);

        run("{\"homeserver\":\"" + url + "\"}", "setup", "--project", "p", "--loopback-only");

        assertThat(errBytes.toString(StandardCharsets.UTF_8))
                .as("what stderr says")
                .doesNotContain("only this machine's loopback");
    }

    /**
     * Harm guarded: the verbs after setup reach the homeserver Porelo kept from setup, and carry
     * the admin token there; plain http to another host would send it readable.
     */
    @ParameterizedTest(name = "{0}")
    @CsvSource({
        "'enroll --project p --task t'",
        "'retire --project p --task t'",
        "'join --project p --person n'"
    })
    @DisplayName(
            "The kept homeserver in plain http on another host is refused with 78, before any request")
    void keptPlainHttpElsewhereIsRefused(final String line) {
        final int exit =
                run(
                        Map.of(
                                Config.HOMESERVER,
                                "http://matrix.example.org",
                                Config.ADMIN_TOKEN,
                                "a"),
                        "{}",
                        line.split(" "));

        assertThat(exit).as(errBytes.toString(StandardCharsets.UTF_8)).isEqualTo(Exit.CONFIG);
        assertThat(errBytes.toString(StandardCharsets.UTF_8))
                .as("the refusal")
                .contains("plain http on another host");
    }

    /**
     * Harm guarded as above; {@code clear} says in {@code kept} what it could not remove, and ends
     * with 0, so the refusal is read there.
     */
    @Test
    @DisplayName("clear keeps the project's room when the kept homeserver is plain http elsewhere")
    void clearRefusesKeptPlainHttpElsewhere() {
        final ByteArrayOutputStream outBytes = new ByteArrayOutputStream();
        final int exit =
                Main.run(
                        new String[] {"clear", "--project", "p"},
                        Map.of(
                                Config.HOMESERVER,
                                "http://matrix.example.org",
                                Config.ADMIN_TOKEN,
                                "a"),
                        new ByteArrayInputStream("{}".getBytes(StandardCharsets.UTF_8)),
                        new PrintStream(outBytes, true, StandardCharsets.UTF_8),
                        new PrintStream(errBytes, true, StandardCharsets.UTF_8));

        assertThat(exit).as(errBytes.toString(StandardCharsets.UTF_8)).isEqualTo(Exit.OK);
        assertThat(outBytes.toString(StandardCharsets.UTF_8))
                .as("what clear kept, and why")
                .contains("plain http on another host");
    }

    /**
     * Harm guarded: an offline project's verbs after setup reach the homeserver Porelo kept, not
     * the settings' one; one elsewhere would be reached although the project may reach nothing.
     */
    @ParameterizedTest(name = "{0}")
    @CsvSource({"enroll --task t", "join --person n"})
    @DisplayName("Under --loopback-only, a kept homeserver elsewhere is refused with 78")
    void keptHomeserverElsewhereIsRefusedOffline(final String rest) {
        final String[] verb = rest.split(" ");
        final int exit =
                run(
                        Map.of(
                                Config.HOMESERVER,
                                "https://matrix.example.org",
                                Config.ADMIN_TOKEN,
                                "a"),
                        "{}",
                        verb[0],
                        "--project",
                        "p",
                        verb[1],
                        verb[2],
                        "--loopback-only");

        assertThat(exit).as(errBytes.toString(StandardCharsets.UTF_8)).isEqualTo(Exit.CONFIG);
        assertThat(errBytes.toString(StandardCharsets.UTF_8))
                .as("the refusal")
                .contains("only this machine's loopback");
    }

    /**
     * Harm guarded: with the certificate unchecked, whoever answers in the homeserver's place
     * receives the admin token; switched off in the project's settings, it must be as loud as in
     * the environment.
     */
    @ParameterizedTest(name = "{0}")
    @CsvSource({"setup", "enroll"})
    @DisplayName("tls_verify off in the project's settings is warned of on stderr")
    void verifyOffInSettingsIsWarned(final String verb) {
        final String[] args =
                "setup".equals(verb)
                        ? new String[] {"setup", "--project", "p"}
                        : new String[] {"enroll", "--project", "p", "--task", "t"};

        run(
                Map.of(Config.HOMESERVER, "https://127.0.0.1:1", Config.ADMIN_TOKEN, "a"),
                "{\"homeserver\":\"https://127.0.0.1:1\",\"tls_verify\":\"off\"}",
                args);

        assertThat(errBytes.toString(StandardCharsets.UTF_8))
                .as("what stderr says")
                .contains("WARNING")
                .contains("tls_verify");
    }

    private int run(final Map<String, String> env, final String settings, final String... args) {
        return Main.run(
                args,
                env,
                new ByteArrayInputStream(settings.getBytes(StandardCharsets.UTF_8)),
                new PrintStream(OutputStream.nullOutputStream()),
                new PrintStream(errBytes, true, StandardCharsets.UTF_8));
    }

    @ParameterizedTest(name = "clear {0}")
    @CsvSource({
        "--loopback-only",
        "'--project p --loopback-only'",
        "'--project p --task t'",
        "'--project p --person n'",
        "--project",
        "'--task t'"
    })
    @DisplayName(
            "clear takes --project or nothing; any other shape is 64, and nothing is asked of a homeserver")
    void clearShapes(final String rest) {
        final String[] args = ("clear " + rest).split(" ");

        assertThat(run("{\"homeserver\":\"" + homeserver.url() + "\"}", args))
                .isEqualTo(Exit.USAGE);
        assertThat(errBytes.toString(StandardCharsets.UTF_8))
                .contains("clear [--project <project>]");
        assertThat(homeserver.requests()).isEmpty();
    }
}
