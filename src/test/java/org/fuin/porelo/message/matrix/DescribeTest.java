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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

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
        assertThat(description.path("secrets").asText())
                .as("the secrets are taken on the input")
                .isEqualTo("input");
        assertThat(description.path("hosts").isArray()).isTrue();
        assertThat(description.path("lifecycle"))
                .as("the lifecycle verbs Porelo may run")
                .extracting(JsonNode::asText)
                .containsExactly(
                        "setup",
                        "enroll",
                        "retire",
                        "join",
                        "part",
                        "admit",
                        "dismiss",
                        "members",
                        "clear",
                        "settings");
        assertThat(description.path("takes"))
                .as("central lifecycle verbs take --login, and the admin's setup --create-room")
                .extracting(JsonNode::asText)
                .containsExactly("login", "create-room");
    }

    /** Harm guarded: Porelo needs the declared dependency to explain a start while it waits. */
    @Test
    void describeNamesWhatAStartWaitsOn() throws IOException {
        describe();
        final JsonNode description = MatrixClient.JSON.readTree(outBytes.toByteArray());

        assertThat(description.path("waitsOn").asText())
                .as("describe names the dependency a start waits on")
                .isEqualTo("the room's homeserver")
                .hasSizeLessThanOrEqualTo(80)
                .doesNotContain("\n", "\r");
    }

    /**
     * Harm guarded: central lifecycle verbs must accept {@code --login <name>} so each person can
     * manage its own accounts; an unsanitized name would go into a Matrix user id's localpart.
     */
    @Test
    @DisplayName("Central lifecycle verbs take --login and refuse invalid" + " characters with 78")
    void lifecycleVerbsTakeLogin() {
        for (final String[] args :
                new String[][] {
                    {"setup", "--project", "p", "--login", "node-1"},
                    {"enroll", "--project", "p", "--task", "t", "--login", "node-1"},
                    {"retire", "--project", "p", "--task", "t", "--login", "node-1"},
                    {"join", "--project", "p", "--person", "n", "--login", "node-1"},
                    {"clear", "--project", "p", "--login", "node-1"}
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
        for (final String bad : new String[] {"", "Has Spaces", "-dash", "a:b", "a@b", "\u212A"}) {
            final int exit =
                    Main.run(
                            new String[] {"setup", "--project", "p", "--login", bad},
                            Map.of(),
                            new ByteArrayInputStream("{}".getBytes(StandardCharsets.UTF_8)),
                            new PrintStream(OutputStream.nullOutputStream()),
                            new PrintStream(OutputStream.nullOutputStream()));
            assertThat(exit).as(bad).isEqualTo(Exit.CONFIG);
        }
    }

    /** Harm guarded: malformed part arguments must be refused before any account is changed. */
    @ParameterizedTest(name = "{0}")
    @ValueSource(
            strings = {
                "part --project p",
                "part --project p --person n --reset",
                "part --project p --person n --machine Node-1",
                "part --project p --person n --task t"
            })
    @DisplayName("part requires --person and refuses --reset, --machine and --task")
    void partRefusesArgumentsOutsideItsShape(final String line) {
        final int exit =
                Main.run(
                        line.split(" "),
                        Map.of(),
                        new ByteArrayInputStream("{".getBytes(StandardCharsets.UTF_8)),
                        new PrintStream(outBytes, true, StandardCharsets.UTF_8),
                        new PrintStream(errBytes, true, StandardCharsets.UTF_8));

        assertThat(exit).as("%s must stop before reading settings", line).isEqualTo(Exit.USAGE);
        assertThat(errBytes.toString(StandardCharsets.UTF_8))
                .as("part is recognized and explains the allowed arguments")
                .contains("usage: part");
        assertThat(outBytes.toString(StandardCharsets.UTF_8))
                .as("a refused command produces no success answer")
                .isEmpty();
    }

    /**
     * Harm guarded: a valid part invocation must reach lifecycle dispatch instead of doing nothing.
     */
    @Test
    @DisplayName("part with --person and --loopback-only reaches the settings check")
    void partReachesTheLifecycle() {
        final int exit =
                Main.run(
                        new String[] {"part", "--project", "p", "--person", "n", "--loopback-only"},
                        Map.of(),
                        new ByteArrayInputStream("{".getBytes(StandardCharsets.UTF_8)),
                        new PrintStream(outBytes, true, StandardCharsets.UTF_8),
                        new PrintStream(errBytes, true, StandardCharsets.UTF_8));

        assertThat(exit)
                .as("valid part arguments reach the malformed settings")
                .isEqualTo(Exit.CONFIG);
    }

    /** Harm guarded: part must never kick or lock a machinery account. */
    @Test
    @DisplayName("part refuses a person's name beginning with porelo")
    void partRefusesAMachineryName() {
        final int exit =
                Main.run(
                        new String[] {"part", "--project", "p", "--person", "porelo-admin"},
                        Map.of(),
                        new ByteArrayInputStream("{}".getBytes(StandardCharsets.UTF_8)),
                        new PrintStream(outBytes, true, StandardCharsets.UTF_8),
                        new PrintStream(errBytes, true, StandardCharsets.UTF_8));

        assertThat(exit)
                .as("a machinery name is refused before provisioning")
                .isEqualTo(Exit.USAGE);
        assertThat(errBytes.toString(StandardCharsets.UTF_8))
                .as("the refusal explains the protected namespace")
                .contains("a person's name must not begin with 'porelo'");
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
