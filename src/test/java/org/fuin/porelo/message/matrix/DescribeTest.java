package org.fuin.porelo.message.matrix;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class DescribeTest {

    private final ByteArrayOutputStream outBytes = new ByteArrayOutputStream();

    private final ByteArrayOutputStream errBytes = new ByteArrayOutputStream();

    private int describe(final String... args) {
        final String[] all = new String[args.length + 1];
        all[0] = "describe";
        System.arraycopy(args, 0, all, 1, args.length);
        // No environment at all: describe reads nothing.
        return Main.run(
                all,
                Map.of(),
                new PrintStream(outBytes, true, StandardCharsets.UTF_8),
                new PrintStream(errBytes, true, StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName(
            "It prints one JSON object with every field the contract names, reading no configuration")
    void describesItself() throws IOException {
        assertThat(describe()).as(errBytes.toString(StandardCharsets.UTF_8)).isEqualTo(Exit.OK);

        final JsonNode description = MatrixClient.JSON.readTree(outBytes.toByteArray());
        assertThat(description.path("scheme").asText()).isEqualTo("matrix");
        assertThat(description.path("poll").asBoolean(false)).isTrue();
        assertThat(description.path("max_bytes").asInt()).isEqualTo(Describe.MAX_BYTES);
        assertThat(description.path("shown").asBoolean(false)).as("send takes --shown").isTrue();
        assertThat(description.path("persons").asBoolean(false))
                .as("poll takes --persons")
                .isTrue();
        assertThat(description.path("direct").asBoolean(false))
                .as("poll takes --direct, send --to @user")
                .isTrue();
        assertThat(description.path("waits").asBoolean(false)).as("poll takes --wait").isTrue();
        assertThat(description.path("mention").asBoolean(false))
                .as("send takes --mention")
                .isTrue();
        assertThat(description.path("display").asBoolean(false))
                .as("enroll takes --display")
                .isTrue();
        assertThat(description.path("credentials"))
                .singleElement()
                .satisfies(
                        credential -> {
                            assertThat(credential.path("name").asText())
                                    .isEqualTo(Config.ACCESS_TOKEN);
                            assertThat(credential.path("as").asText()).isEqualTo("env");
                        });
        assertThat(description.path("hosts").isArray()).isTrue();
        assertThat(description.path("lifecycle"))
                .extracting(JsonNode::asText)
                .containsExactly("setup", "enroll", "retire", "join", "clear", "settings");
        assertThat(description.path("takes"))
                .as("lifecycle verbs take --machine")
                .extracting(JsonNode::asText)
                .containsExactly("machine");
    }

    /**
     * Harm guarded: once describe claims {@code "takes": ["machine"]}, Porelo passes {@code
     * --machine <name>} to every lifecycle verb; a verb that refuses the option with 64 stops the
     * project, and an unsanitized machine name would go into a Matrix user id's localpart.
     */
    @Test
    @DisplayName(
            "Every lifecycle verb takes --machine <name> in the localpart alphabet, and refuses any other with 64")
    void lifecycleVerbsTakeMachine() {
        for (final String[] args :
                new String[][] {
                    {"setup", "--project", "p", "--machine", "node-1"},
                    {"enroll", "--project", "p", "--task", "t", "--machine", "node-1"},
                    {"retire", "--project", "p", "--task", "t", "--machine", "node-1"},
                    {"join", "--project", "p", "--person", "n", "--machine", "node-1"},
                    {"clear", "--project", "p", "--machine", "node-1"}
                }) {
            final ByteArrayOutputStream err = new ByteArrayOutputStream();
            final int exit =
                    Main.run(
                            args,
                            Map.of(),
                            new ByteArrayInputStream("{".getBytes(StandardCharsets.UTF_8)),
                            new PrintStream(OutputStream.nullOutputStream()),
                            new PrintStream(err, true, StandardCharsets.UTF_8));
            assertThat(exit)
                    .as("%s: %s", String.join(" ", args), err.toString(StandardCharsets.UTF_8))
                    .isEqualTo(Exit.CONFIG);
        }
        for (final String bad : new String[] {"", "Has Spaces", "Upper", "-dash", "a:b", "a@b"}) {
            final int exit =
                    Main.run(
                            new String[] {"setup", "--project", "p", "--machine", bad},
                            Map.of(),
                            new ByteArrayInputStream("{}".getBytes(StandardCharsets.UTF_8)),
                            new PrintStream(OutputStream.nullOutputStream()),
                            new PrintStream(OutputStream.nullOutputStream()));
            assertThat(exit).as(bad).isEqualTo(Exit.USAGE);
        }
        assertThat(
                        Main.run(
                                new String[] {"clear", "--machine", "node-1"},
                                Map.of(),
                                new ByteArrayInputStream("{}".getBytes(StandardCharsets.UTF_8)),
                                new PrintStream(OutputStream.nullOutputStream()),
                                new PrintStream(OutputStream.nullOutputStream())))
                .as("clear without --project takes no --machine")
                .isEqualTo(Exit.USAGE);
    }

    @Test
    @DisplayName(
            "It claims no fact it cannot prove yet, and confirms reading, which receipt answers")
    void claimsNothingUnproven() throws IOException {
        describe();

        final JsonNode description = MatrixClient.JSON.readTree(outBytes.toByteArray());
        assertThat(description.path("attests").isArray()).isTrue();
        assertThat(description.path("attests")).isEmpty();
        assertThat(description.path("confirms").asText()).isEqualTo("read");
    }

    @Test
    @DisplayName("Arguments it does not take are 64")
    void noArguments() {
        assertThat(describe("--verbose")).isEqualTo(Exit.USAGE);
    }
}
