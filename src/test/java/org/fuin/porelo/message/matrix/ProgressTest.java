package org.fuin.porelo.message.matrix;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ProgressTest {

    /** Harm guarded: a blocked operation must report without polling and stop at scope exit. */
    @Test
    void scheduledProgressContinuesDuringBlockingWorkAndStopsWhenClosed() throws Exception {
        final AdvancingClock clock = new AdvancingClock();
        final ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        final CountDownLatch repeated = new CountDownLatch(1);
        final AtomicInteger lines = new AtomicInteger();
        final PrintStream err =
                new PrintStream(stderr, true, StandardCharsets.UTF_8) {
                    @Override
                    public void println(final String line) {
                        super.println(line);
                        if (lines.incrementAndGet() == 2) {
                            repeated.countDown();
                        }
                    }
                };
        final Progress progress =
                new Progress(clock::nanoTime, Duration.ofMillis(1), err, List.of());
        try (progress) {
            progress.phase("copying the homeserver backup");
            clock.advance(Duration.ofSeconds(10));
            assertThat(repeated.await(5, TimeUnit.SECONDS))
                    .as("the timer reports while the operation itself is blocked")
                    .isTrue();
        }
        assertThat(stderr.toString(StandardCharsets.UTF_8).lines().toList())
                .as("the scheduled update carries elapsed time from the injected clock")
                .containsExactly(
                        "copying the homeserver backup (0 s)",
                        "copying the homeserver backup (10 s)");
        clock.advance(Duration.ofSeconds(10));
        progress.tick();
        assertThat(stderr.toString(StandardCharsets.UTF_8).lines().toList())
                .as("a closed scope cannot append progress after a result or failure")
                .hasSize(2);
    }

    /** Harm guarded: a wait must not report an interval before it has elapsed. */
    @Test
    void aTickPrintsOnlyOnceTheWholeIntervalHasElapsed() {
        final AdvancingClock clock = new AdvancingClock();
        final ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        try (Progress progress =
                new Progress(
                        clock::nanoTime,
                        Duration.ofSeconds(10),
                        new PrintStream(stderr, true, StandardCharsets.UTF_8),
                        List.of())) {
            progress.phase("waiting for the homeserver", Duration.ofSeconds(180));
            clock.advance(Duration.ofSeconds(10).minusNanos(1));
            progress.tick();
            assertThat(stderr.toString(StandardCharsets.UTF_8).lines().toList())
                    .as("a tick one nanosecond early must not announce ten seconds")
                    .containsExactly("waiting for the homeserver (0 s of 180)");
            clock.advance(Duration.ofNanos(1));
            progress.tick();
            assertThat(stderr.toString(StandardCharsets.UTF_8).lines().toList())
                    .as("the interval boundary emits exactly one new line")
                    .containsExactly(
                            "waiting for the homeserver (0 s of 180)",
                            "waiting for the homeserver (10 s of 180)");
        }
    }

    /** Harm guarded: interruption must not falsely describe a safe phase as secret-bearing. */
    @Test
    void anInterruptedThreadStillPrintsItsSafePhase() {
        final ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        try (Progress progress =
                new Progress(new PrintStream(stderr, true, StandardCharsets.UTF_8))) {
            Thread.currentThread().interrupt();
            try {
                progress.phase("waiting for the backup locks", Duration.ofSeconds(180));
                assertThat(Thread.currentThread().isInterrupted())
                        .as("filtering progress preserves the command's interrupt status")
                        .isTrue();
            } finally {
                Thread.interrupted();
            }
        }
        assertThat(stderr.toString(StandardCharsets.UTF_8).lines().toList())
                .as("a static phase is safe even when its calling thread is interrupted")
                .containsExactly("waiting for the backup locks (0 s of 180)");
    }

    /**
     * Harm guarded: a wait announced with a limit rounded up ends on a line short of it, as if it
     * had given up early.
     */
    @Test
    void aTimeoutOnARoundedUpLimitSaysTheWholeLimit() {
        final AdvancingClock clock = new AdvancingClock();
        final ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        try (Progress progress =
                new Progress(
                        clock::nanoTime,
                        Duration.ofSeconds(10),
                        new PrintStream(stderr, true, StandardCharsets.UTF_8),
                        List.of())) {
            progress.phase("waiting for the backup locks", Duration.ofMillis(179_800));
            clock.advance(Duration.ofMillis(179_800));
            progress.timeout();
            assertThat(stderr.toString(StandardCharsets.UTF_8).lines().toList())
                    .as("the last line names the limit the first one announced")
                    .containsExactly(
                            "waiting for the backup locks (0 s of 180)",
                            "waiting for the backup locks (180 s of 180)");
        }
    }

    /** Harm guarded: timeout output must not exceed its budget or repeat after the final line. */
    @Test
    void aTimeoutClampsElapsedTimeAndPrintsItsLimitOnlyOnce() {
        final AdvancingClock clock = new AdvancingClock();
        final ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        try (Progress progress =
                new Progress(
                        clock::nanoTime,
                        Duration.ofSeconds(10),
                        new PrintStream(stderr, true, StandardCharsets.UTF_8),
                        List.of())) {
            progress.phase("waiting for the homeserver", Duration.ofSeconds(180));
            clock.advance(Duration.ofSeconds(182));
            progress.timeout();
            clock.advance(Duration.ofSeconds(10));
            progress.timeout();
            progress.tick();
            assertThat(stderr.toString(StandardCharsets.UTF_8).lines().toList())
                    .as("an overshooting request produces one final line at the stated limit")
                    .containsExactly(
                            "waiting for the homeserver (0 s of 180)",
                            "waiting for the homeserver (180 s of 180)");
        }
    }

    /** Harm guarded: a timeout following the timer's limit line must not announce it twice. */
    @Test
    void aTimeoutDoesNotRepeatALimitAlreadyPrintedByTheTimer() {
        final AdvancingClock clock = new AdvancingClock();
        final ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        try (Progress progress =
                new Progress(
                        clock::nanoTime,
                        Duration.ofSeconds(10),
                        new PrintStream(stderr, true, StandardCharsets.UTF_8),
                        List.of())) {
            progress.phase("waiting for the homeserver", Duration.ofSeconds(180));
            clock.advance(Duration.ofSeconds(180));
            progress.tick();
            clock.advance(Duration.ofSeconds(2));
            progress.timeout();
            assertThat(stderr.toString(StandardCharsets.UTF_8).lines().toList())
                    .as("the last line stays at the limit despite a late timeout check")
                    .containsExactly(
                            "waiting for the homeserver (0 s of 180)",
                            "waiting for the homeserver (180 s of 180)");
        }
    }

    /** Harm guarded: closed commands must not leave a live progress timer behind. */
    @Test
    void closingAReporterTerminatesItsTimerThread() throws Exception {
        final Progress progress =
                new Progress(
                        new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8));
        try (progress) {
            progress.phase("waiting for the homeserver", Duration.ofSeconds(180));
            assertThat(progressThreads())
                    .as("the phase actually starts a live timer, so cleanup is exercised")
                    .isNotEmpty();
        }
        for (final Thread thread : progressThreads()) {
            thread.join(Duration.ofSeconds(5));
        }
        assertThat(progressThreads())
                .as("no matrix-progress thread remains live after closing the reporter")
                .isEmpty();
    }

    private static List<Thread> progressThreads() {
        return Thread.getAllStackTraces().keySet().stream()
                .filter(thread -> thread.isAlive() && thread.getName().equals("matrix-progress"))
                .toList();
    }

    /** Harm guarded: progress must never disclose an outside value known to be a secret. */
    @Test
    void aSecretInAProgressLineIsWithheld() {
        final ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        try (Progress progress =
                new Progress(
                        () -> 0L,
                        Duration.ofSeconds(10),
                        new PrintStream(stderr, true, StandardCharsets.UTF_8),
                        List.of("orchard"))) {
            progress.phase("waiting for orchard", Duration.ofSeconds(30));
        }
        assertThat(stderr.toString(StandardCharsets.UTF_8))
                .as("known secrets cannot reach stderr through a progress phase")
                .doesNotContain("orchard")
                .contains("withheld: it held a secret");
    }
}
