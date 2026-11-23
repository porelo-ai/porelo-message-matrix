package org.fuin.porelo.message.matrix;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.fuin.porelo.wire.LimitFactors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class LimitsTest {

    private final ByteArrayOutputStream outBytes = new ByteArrayOutputStream();

    private final ByteArrayOutputStream errBytes = new ByteArrayOutputStream();

    private final PrintStream out = new PrintStream(outBytes, true, StandardCharsets.UTF_8);

    private final PrintStream err = new PrintStream(errBytes, true, StandardCharsets.UTF_8);

    @AfterEach
    void defaults() {
        Limits.use(LimitFactors.DEFAULT);
    }

    private String stderr() {
        return errBytes.toString(StandardCharsets.UTF_8);
    }

    /** Harm guarded: a wrong limits line is passed over and the run goes on with other limits. */
    @ParameterizedTest
    @ValueSource(strings = {"services=0", "speed=2.0", "services=2.0,services=3.0", "machines=1e2"})
    void aWrongLimitsLineIsRefusedBeforeTheVerb(final String line) {
        final int exit = Main.run(new String[] {"--limits", line, "describe"}, Map.of(), out, err);

        assertThat(exit).as("refused as a usage error: %s", stderr()).isEqualTo(Exit.USAGE);
        assertThat(stderr()).as("names the option").contains("--limits: ");
        assertThat(outBytes.size()).as("the verb did not run").isZero();
    }

    /** Harm guarded: an option without its line takes the verb for the line. */
    @Test
    void theOptionWithoutItsLineIsRefused() {
        final int exit = Main.run(new String[] {"--limits"}, Map.of(), out, err);

        assertThat(exit).as(stderr()).isEqualTo(Exit.USAGE);
        assertThat(stderr()).contains("--limits: the limits line is missing");
    }

    /** Harm guarded: the person's factors never reach the requests to the homeserver. */
    @Test
    void theLineGivenSetsTheRequestLimit() {
        final int exit =
                Main.run(
                        new String[] {"--limits", "services=2.0,polling=3.0", "describe"},
                        Map.of(),
                        out,
                        err);

        assertThat(exit).as(stderr()).isEqualTo(Exit.OK);
        assertThat(MatrixClient.requestTimeout())
                .as("60 s times services")
                .isEqualTo(Duration.ofSeconds(120));
    }

    /** Harm guarded: a factor of an earlier run in the same process stays in force. */
    @Test
    void aRunWithoutTheOptionTakesAllFourAtOne() {
        Main.run(new String[] {"--limits", "services=2.0", "describe"}, Map.of(), out, err);

        Main.run(new String[] {"describe"}, Map.of(), out, err);

        assertThat(Limits.factors()).isEqualTo(LimitFactors.DEFAULT);
        assertThat(MatrixClient.requestTimeout()).isEqualTo(Duration.ofSeconds(60));
    }

    /** Harm guarded: a small factor cuts off every held sync, so no poll ever answers. */
    @Test
    void aSmallFactorNeverCutsOffAHeldSync() {
        Limits.use(LimitFactors.parse("services=0.1"));

        assertThat(MatrixClient.requestTimeout())
                .as("the longest held sync, 30 s, and a margin")
                .isEqualTo(Duration.ofSeconds(Poll.MAX_WAIT_SECONDS + 15L));
    }

    /**
     * Harm guarded: under a small factor a backup or {@code clear} gives up on a healthy poll still
     * holding its sync, and fails with 75.
     */
    @Test
    void aSmallFactorNeverEndsAWaitForAPollBeforeItsSync() {
        Limits.use(LimitFactors.parse("services=0.1"));

        final Duration sync = Duration.ofSeconds(Poll.MAX_WAIT_SECONDS);
        assertThat(Keep.lockWait()).as("a backup's wait for the locks").isGreaterThan(sync);
        assertThat(Lifecycle.pollWait()).as("clear's wait for the poll").isGreaterThan(sync);
    }

    /** Harm guarded: the retries factor is not followed, so a busy room fails as early as ever. */
    @Test
    void theRetriesFactorSetsTheRoundsOfTakingOut() throws Exception {
        Limits.use(LimitFactors.parse("retries=0.2"));
        try (FakeHomeserver homeserver = new FakeHomeserver()) {
            final MatrixClient admin =
                    new MatrixClient(new Config(URI.create(homeserver.url()), "token", null, true));
            final Map<String, String> members = new LinkedHashMap<>();
            members.put("@porelo:example.org", "join");
            members.put("@bob:example.org", "join");
            final String still = membership(members);
            homeserver.answer(200, still);
            for (int round = 0; round < 5; round++) {
                homeserver.answer(200, "{}").answer(200, still);
            }

            assertThatThrownBy(
                            () ->
                                    KeepRoom.keepTo(
                                            admin,
                                            "!r",
                                            "#porelo-p:example.org",
                                            "example.org",
                                            List.of()))
                    .isInstanceOfSatisfying(
                            Failure.class,
                            failure -> assertThat(failure.exitCode()).isEqualTo(Exit.TEMPORARY))
                    .as("5 rounds times 0.2")
                    .hasMessageContaining("after 1 rounds");
        }
    }

    private static String membership(final Map<String, String> members) {
        final var answer = MatrixClient.JSON.createObjectNode();
        final var chunk = answer.putArray("chunk");
        members.forEach(
                (id, membership) ->
                        chunk.addObject()
                                .put("state_key", id)
                                .putObject("content")
                                .put("membership", membership));
        return answer.toString();
    }
}
