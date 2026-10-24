package org.fuin.porelo.message.matrix;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/** Guards against losing every busy host's backup and hanging polls behind a backup. */
@Timeout(15)
class KeepWriterPreferenceTest {

    private static final Duration LOCK_BUDGET = Duration.ofSeconds(5);

    @TempDir private Path dir;

    private final List<Process> children = new ArrayList<>();

    private Map<String, String> env;

    private Path state;

    private Path into;

    @BeforeEach
    void setUp() throws Exception {
        env =
                Map.of(
                        "XDG_STATE_HOME",
                        dir.resolve("state").toString(),
                        "XDG_CONFIG_HOME",
                        dir.resolve("config").toString());
        state = Files.createDirectories(Poll.stateDirectory(env));
        into = Files.createDirectory(dir.resolve("copy"));
        Files.createDirectories(Keep.backups(env).resolve("meta"));
    }

    @AfterEach
    void stopChildren() throws InterruptedException {
        for (final Process child : children) {
            child.destroyForcibly();
        }
        for (final Process child : children) {
            assertThat(child.waitFor(5, TimeUnit.SECONDS))
                    .as("the test's child process must be reaped")
                    .isTrue();
        }
    }

    private Process poller(final String mode) throws IOException {
        final Process child =
                new ProcessBuilder(
                                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                                "-cp",
                                System.getProperty("java.class.path"),
                                SharedLockLooper.class.getName(),
                                state.toString(),
                                mode)
                        .start();
        children.add(child);
        return child;
    }

    private void awaitHold(final Process child) throws Exception {
        final CompletableFuture<String> said =
                CompletableFuture.supplyAsync(
                        () -> {
                            try {
                                return child.inputReader(StandardCharsets.UTF_8).readLine();
                            } catch (final IOException failure) {
                                throw new UncheckedIOException(failure);
                            }
                        });
        assertThat(said.get(5, TimeUnit.SECONDS))
                .as("each poll process must hold the shared lock before the backup starts")
                .isEqualTo("held");
    }

    private Unit answeringUnit() {
        final KeepTest.StandIn unit =
                new KeepTest.StandIn() {
                    @Override
                    public void signalBackup() throws Failure {
                        try {
                            Files.writeString(Keep.backups(env).resolve("meta/1"), "snapshot");
                        } catch (final IOException failure) {
                            throw new Failure(
                                    Exit.CONFIG, "cannot make the test snapshot", failure);
                        }
                    }
                };
        unit.active = true;
        return unit;
    }

    private int backupExit(final Duration budget) throws Failure {
        try {
            Keep.backup(into, env, answeringUnit(), Duration.ofSeconds(1), budget);
            return Exit.OK;
        } catch (final Failure failure) {
            if (failure.exitCode() != Exit.TEMPORARY) {
                throw failure;
            }
            return failure.exitCode();
        }
    }

    private void assertNeitherLockIsHeld() throws IOException {
        for (final String name : List.of(Keep.WANTED, Keep.LOCK)) {
            try (FileChannel channel =
                            FileChannel.open(state.resolve(name), StandardOpenOption.WRITE);
                    FileLock acquired = channel.tryLock()) {
                assertThat(acquired)
                        .as("the backup must release %s after refusal", name)
                        .isNotNull();
            }
        }
    }

    /** Harm guarded: an expired backup must not acquire a lock and start copying positions. */
    @Test
    void aLockReleasedJustAfterTheBudgetCannotBeTaken() throws Exception {
        final Process holder = poller("hold");
        awaitHold(holder);
        try (var timer = Executors.newSingleThreadScheduledExecutor()) {
            final var release =
                    timer.schedule(
                            () -> {
                                holder.getOutputStream().close();
                                return null;
                            },
                            275,
                            TimeUnit.MILLISECONDS);
            final int exit = backupExit(Duration.ofMillis(250));
            release.get(5, TimeUnit.SECONDS);
            assertThat(holder.waitFor(5, TimeUnit.SECONDS))
                    .as("the holder releases just after the backup's budget")
                    .isTrue();
            assertNeitherLockIsHeld();
            assertThat(exit)
                    .as("a lock released after the 250 ms budget must not admit the backup")
                    .isEqualTo(Exit.TEMPORARY);
        }
    }

    /**
     * Harm guarded: contention on both locks must not double how long a backup blocks its caller.
     */
    @Test
    void bothLocksShareOneBudget() throws Exception {
        final Process poll = poller("hold");
        awaitHold(poll);
        final Process wanted =
                new ProcessBuilder(
                                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                                "-cp",
                                System.getProperty("java.class.path"),
                                LockHolder.class.getName(),
                                state.resolve(Keep.WANTED).toString())
                        .start();
        children.add(wanted);
        awaitHold(wanted);
        try (var timer = Executors.newSingleThreadScheduledExecutor()) {
            final var release =
                    timer.schedule(
                            () -> {
                                wanted.getOutputStream().close();
                                return null;
                            },
                            600,
                            TimeUnit.MILLISECONDS);
            final long started = System.nanoTime();
            final int exit = backupExit(Duration.ofMillis(800));
            final Duration elapsed = Duration.ofNanos(System.nanoTime() - started);
            release.get(5, TimeUnit.SECONDS);
            assertThat(wanted.waitFor(5, TimeUnit.SECONDS))
                    .as("the first lock is released before the single budget ends")
                    .isTrue();
            assertThat(poll.isAlive())
                    .as("the second lock stays shared past the remaining budget")
                    .isTrue();
            poll.getOutputStream().close();
            assertThat(poll.waitFor(5, TimeUnit.SECONDS))
                    .as("the shared holder must be reaped before checking for leaked locks")
                    .isTrue();
            assertNeitherLockIsHeld();
            assertThat(exit)
                    .as("contention on the second lock exhausts the budget")
                    .isEqualTo(Exit.TEMPORARY);
            assertThat(elapsed)
                    .as(
                            "both lock waits must fit one 800 ms budget with 200 ms scheduling tolerance")
                    .isBetween(Duration.ofMillis(800), Duration.ofMillis(1000));
        }
    }

    /** Harm guarded: overlapping poll loops must not prevent all backups of the conversation. */
    @Test
    void aBackupFinishesWithinTheLongestPollHoldPlusTwoSeconds() throws Exception {
        for (int i = 0; i < 3; i++) {
            awaitHold(poller("loop"));
        }
        Files.writeString(state.resolve("abc.since"), "s1\n");
        final long started = System.nanoTime();
        int exit = Exit.OK;
        String reason = "";
        try {
            Keep.backup(into, env, answeringUnit(), Duration.ofSeconds(1), LOCK_BUDGET);
        } catch (final Failure failure) {
            exit = failure.exitCode();
            reason = failure.getMessage();
        }
        assertThat(exit).as("writer starvation: %s", reason).isEqualTo(Exit.OK);
        assertThat(Duration.ofNanos(System.nanoTime() - started))
                .as("the longest running poll holds for at most three seconds")
                .isLessThan(LOCK_BUDGET);
        assertThat(into.resolve("database/meta/1"))
                .as("the backup completed while all three poll loops still run")
                .hasContent("snapshot");
        assertThat(into.resolve("positions/abc.since"))
                .as("the poll position belongs to the completed backup")
                .hasContent("s1");
        assertThat(children)
                .as("the backup must succeed without stopping the polls")
                .allMatch(Process::isAlive);
    }

    /** Harm guarded: a waiting or hung backup must never make a new poll wait. */
    @Test
    void aPollIsRefusedWithinOneSecondWhileABackupWantsTheLock() throws Exception {
        try (FileChannel wanted =
                        FileChannel.open(
                                state.resolve(Keep.WANTED),
                                StandardOpenOption.CREATE,
                                StandardOpenOption.READ,
                                StandardOpenOption.WRITE);
                FileLock held = wanted.lock()) {
            final Process poll = poller("once");
            assertThat(poll.waitFor(1, TimeUnit.SECONDS))
                    .as("a poll must answer within one second while the backup holds keep.wanted")
                    .isTrue();
            assertThat(poll.exitValue())
                    .as("the waiting backup must refuse a new poll")
                    .isEqualTo(Exit.TEMPORARY);
        }
        final Process next = poller("once");
        assertThat(next.waitFor(1, TimeUnit.SECONDS))
                .as("the next poll can acquire the lock once the backup releases it")
                .isTrue();
        assertThat(next.exitValue()).as("the refusal must not leave a lock behind").isZero();
    }
}
