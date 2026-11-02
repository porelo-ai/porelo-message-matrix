package org.fuin.porelo.message.matrix;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code clear --project} forgets the relay's poll position. Harm guarded: Porelo polls the relay
 * on its own schedule, and clears without stopping it; a poll in its long sync while the position
 * is deleted writes it back afterwards, and the project set up again then ends every poll with 78.
 */
class ForgetPositionTest {

    private static final String KEY = "k";

    private static final String RELAY = "@porelo-relay-demo:x";

    @TempDir Path state;

    @Test
    @DisplayName("While a poll holds the relay, its position is kept, and said why")
    void keptWhileAPollHoldsIt() throws Exception {
        Files.writeString(state.resolve(KEY + ".since"), "s1");
        Files.createFile(state.resolve(KEY + ".lock"));
        final Process poll = holdLock(state.resolve(KEY + ".lock"));
        final Lifecycle.Report report = new Lifecycle.Report();
        try {
            Lifecycle.forgetPosition(state, KEY, RELAY, report, Duration.ofSeconds(2));
        } finally {
            poll.getOutputStream().close();
            poll.waitFor();
        }

        assertThat(state.resolve(KEY + ".since")).as("the position").exists();
        assertThat(report.answer().path("removed")).as("what was removed").isEmpty();
        assertThat(report.answer().path("kept").get(0).path("why").asText())
                .as("why it was kept")
                .contains("poll");
    }

    @Test
    @DisplayName("Once no poll holds it, the position and its lock are forgotten")
    void forgottenWhenNoPollHoldsIt() throws Exception {
        Files.writeString(state.resolve(KEY + ".since"), "s1");
        Files.createFile(state.resolve(KEY + ".lock"));
        final Lifecycle.Report report = new Lifecycle.Report();

        Lifecycle.forgetPosition(state, KEY, RELAY, report, Duration.ofSeconds(2));

        assertThat(state.resolve(KEY + ".since")).as("the position").doesNotExist();
        assertThat(state.resolve(KEY + ".lock")).as("its lock").doesNotExist();
        assertThat(report.answer().path("removed").get(0).asText())
                .as("what was removed")
                .isEqualTo("the poll position of " + RELAY);
    }

    private void forgetUnlessStillIn(final String... joined) {
        Lifecycle.forgetPosition(
                state,
                KEY,
                RELAY,
                new Lifecycle.Report(),
                Duration.ofSeconds(20),
                position -> position.takenOnlyInRoomsOutside(List.of(joined)));
    }

    /** Harm guarded: a position forgotten for a room joined beside, so all is handed over again. */
    @Test
    void aPositionInARoomTheRelayIsStillInIsKept() throws Exception {
        Files.writeString(state.resolve(KEY + ".since"), "s1\n!old:x\n");

        forgetUnlessStillIn("!old:x", "!new:x");

        assertThat(state.resolve(KEY + ".since")).as("the position").hasContent("s1\n!old:x");
    }

    /** Harm guarded: a position only in rooms left stays, and every later poll refuses it. */
    @Test
    void aPositionOnlyInRoomsLeftIsForgotten() throws Exception {
        Files.writeString(state.resolve(KEY + ".since"), "s1\n!old:x\n");

        forgetUnlessStillIn("!new:x");

        assertThat(state.resolve(KEY + ".since")).as("the position").doesNotExist();
    }

    /**
     * Harm guarded: a position saved while a second setup waited for the lock is forgotten on what
     * was read before, and what that poll delivered is handed over twice.
     */
    @Test
    void whatIsAskedIsThePositionFoundUnderTheLock() throws Exception {
        Files.writeString(state.resolve(KEY + ".since"), "s1\n!old:x\n");
        Files.createFile(state.resolve(KEY + ".lock"));
        final Process poll = holdLock(state.resolve(KEY + ".lock"));
        final Thread setup = new Thread(() -> forgetUnlessStillIn("!new:x"));
        setup.start();
        Thread.sleep(500);
        Files.writeString(state.resolve(KEY + ".since"), "s2\n!new:x\n");
        poll.getOutputStream().close();
        poll.waitFor();
        setup.join();

        assertThat(state.resolve(KEY + ".since")).as("the newer position").hasContent("s2\n!new:x");
    }

    /** A poll of the relay in a process of its own, holding the lock as long as it runs. */
    private static Process holdLock(final Path lock) throws Exception {
        final Process holder =
                new ProcessBuilder(
                                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                                "-cp",
                                System.getProperty("java.class.path"),
                                LockHolder.class.getName(),
                                lock.toString())
                        .redirectError(ProcessBuilder.Redirect.INHERIT)
                        .start();
        final BufferedReader said =
                new BufferedReader(
                        new InputStreamReader(holder.getInputStream(), StandardCharsets.UTF_8));
        assertThat(said.readLine()).as("the poll").isEqualTo("held");
        return holder;
    }
}
