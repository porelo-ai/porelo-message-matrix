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
        // The fake stands where a remote server would be reached; it must hear nothing.
        final String remote =
                homeserver.url().replace("http://127.0.0.1", "https://matrix.example.org");

        assertThat(run("{\"homeserver\":\"" + remote + "\"}", args)).isEqualTo(Exit.CONFIG);
        assertThat(errBytes.toString(StandardCharsets.UTF_8))
                .contains("only this machine's loopback");
        assertThat(homeserver.requests()).isEmpty();
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({"http://127.0.0.1:8008", "http://localhost:8008", "http://[::1]:8008"})
    @DisplayName("A loopback homeserver passes the check - what follows is the verb's own")
    void loopbackPasses(final String url) {
        run("{\"homeserver\":\"" + url + "\"}", "setup", "--project", "p", "--loopback-only");

        assertThat(errBytes.toString(StandardCharsets.UTF_8))
                .doesNotContain("only this machine's loopback");
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
