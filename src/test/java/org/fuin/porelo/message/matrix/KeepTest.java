package org.fuin.porelo.message.matrix;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * What {@code backup} and {@code restore} refuse, and that a refusal or a failed restore leaves the
 * machine as it found it. The homeserver is a stand-in here; {@code KeepIT} runs the real one.
 */
class KeepTest {

    @TempDir private Path dir;

    private Map<String, String> env;

    private StandIn unit;

    @BeforeEach
    void setUp() {
        env =
                Map.of(
                        "HOME", dir.resolve("home").toString(),
                        "XDG_CONFIG_HOME", dir.resolve("config").toString(),
                        "XDG_STATE_HOME", dir.resolve("state").toString());
        unit = new StandIn();
    }

    /** A homeserver that only says what it is asked, and counts what it is told to do. */
    static class StandIn implements Unit {

        boolean active;

        boolean database;

        int runExit;

        int runs;

        int signals;

        @Override
        public boolean installed() {
            return true;
        }

        @Override
        public boolean active() {
            return active;
        }

        @Override
        public String state() {
            return active ? "active" : "inactive";
        }

        @Override
        public List<String> recentLog() {
            return List.of();
        }

        @Override
        public void start() {
            active = true;
        }

        @Override
        public void stop() {
            active = false;
        }

        @Override
        public boolean hasDatabase() {
            return database;
        }

        @Override
        public void removeDatabase() {
            database = false;
        }

        @Override
        public void signalBackup() throws Failure {
            signals++;
        }

        @Override
        public int runOnce(final List<String> args, final Map<String, String> secrets) {
            runs++;
            // As podman does with a volume it is given: made by the run.
            database = true;
            return runExit;
        }
    }

    /** Harm guarded: the actual retake call must protect the password generated for its run. */
    @Test
    void retakingNeverDisclosesItsGeneratedPasswordOnAStartTimeout() throws Exception {
        final String[] captured = {""};
        final Unit retaking =
                new StandIn() {
                    @Override
                    public int runOnce(final List<String> args, final Map<String, String> secrets) {
                        final String execute = secrets.get("TUWUNEL_ADMIN_EXECUTE");
                        captured[0] =
                                execute.substring(
                                        execute.indexOf("porelo ") + 7, execute.indexOf("\", "));
                        return 0;
                    }

                    @Override
                    public List<String> recentLog() {
                        // Spacing avoids the generic token-shape guard so this exercises the
                        // wiring.
                        return List.of("Error: " + String.join(" ", captured[0].split("")));
                    }
                };
        Files.createDirectories(HomeserverToken.directory(env));
        Files.writeString(HomeserverToken.file(env), "Q7\n");
        final ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        try {
            Keep.retake(
                    new MatrixClient(new Config(URI.create("http://127.0.0.1:1"), "", null, true)),
                    URI.create("http://127.0.0.1:1"),
                    env,
                    retaking,
                    Duration.ZERO);
            throw new AssertionError("the restarted homeserver must time out");
        } catch (final Failure failure) {
            Main.reportFailure(
                    "setup", failure, new PrintStream(stderr, true, StandardCharsets.UTF_8));
        }
        assertThat(captured[0].length()).as("the run supplied a generated password").isEqualTo(32);
        final String printed = stderr.toString(StandardCharsets.UTF_8);
        assertThat(printed.replace(" ", "").contains(captured[0]))
                .as("the generated password cannot reach stderr even with spacing")
                .isFalse();
        assertThat(
                        printed.lines()
                                .toList()
                                .getLast()
                                .equals(
                                        "the homeserver's last error was withheld: it held a secret"))
                .as("retake supplies its generated password to the diagnostic filter")
                .isTrue();
    }

    /** A copy as backup writes one: a whole backup, the settings, a position. */
    private Path copy(final String port) throws IOException {
        final Path copy = Files.createDirectories(dir.resolve("copy"));
        Files.createDirectories(copy.resolve("database/meta"));
        Files.writeString(copy.resolve("database/meta/3"), "meta");
        Files.createDirectories(copy.resolve("database/private/3"));
        Files.writeString(copy.resolve("database/private/3/CURRENT"), "x");
        Files.writeString(
                copy.resolve("homeserver.conf"),
                "PORELO_MATRIX_PORT=" + port + "\n",
                StandardCharsets.UTF_8);
        Files.createDirectories(copy.resolve("positions"));
        Files.writeString(copy.resolve("positions/abc.since"), "s1\n!room:localhost\n");
        return copy;
    }

    private Path conf() throws Failure {
        return LocalHomeserver.directory(env).resolve("homeserver.conf");
    }

    private void assertNothingMade() throws Failure {
        assertThat(Keep.backups(env)).as("no backups copied in").doesNotExist();
        assertThat(Poll.stateDirectory(env).resolve("abc.since"))
                .as("no position copied in")
                .doesNotExist();
        assertThat(Poll.stateDirectory(env).resolve(Keep.MARKER)).as("no marker").doesNotExist();
        assertThat(unit.database).as("no database made").isFalse();
    }

    /** Harm guarded: a backup overwrites an existing copy. */
    @Test
    @DisplayName(
            "backup refuses a directory that is not empty, and asks the homeserver for nothing")
    void backupRefusesADirectoryNotEmpty() throws IOException {
        final Path into = Files.createDirectories(dir.resolve("into"));
        Files.writeString(into.resolve("left"), "something");
        unit.active = true;

        assertThatThrownBy(() -> Keep.backup(into, env, unit, Duration.ofSeconds(1)))
                .isInstanceOfSatisfying(
                        Failure.class,
                        failure -> assertThat(failure.exitCode()).isEqualTo(Exit.CONFIG))
                .hasMessageContaining("is not empty");
        assertThat(into.resolve("left")).as("existing data is preserved").hasContent("something");
        assertThat(unit.signals).as("backups asked for").isZero();
    }

    @Test
    @DisplayName(
            "backup gives up when no backup appears in time, as a temporary failure, and leaves no half copy")
    void backupWithoutAnAnswerIsTemporary() throws IOException, Failure {
        final Path into = Files.createDirectories(dir.resolve("into"));
        Files.createDirectories(Keep.backups(env));
        Files.createDirectories(Poll.stateDirectory(env));
        Files.writeString(Poll.stateDirectory(env).resolve("abc.since"), "s1\n");
        unit.active = true;

        assertThatThrownBy(() -> Keep.backup(into, env, unit, Duration.ofMillis(300)))
                .isInstanceOfSatisfying(
                        Failure.class,
                        failure -> assertThat(failure.exitCode()).isEqualTo(Exit.TEMPORARY));
        assertThat(unit.signals).as("backups asked for").isEqualTo(1);
        assertThat(into).as("no half copy left to be taken for a whole one").isEmptyDirectory();
    }

    /** Harm guarded: copying a lock would replace the identity shared by live processes. */
    @Test
    void aBackupCopiesPositionsAndReferencesWithoutItsWantedLock() throws Exception {
        final Path state = Files.createDirectories(Poll.stateDirectory(env));
        Files.writeString(state.resolve(Keep.WANTED), "");
        Files.writeString(state.resolve("abc.since"), "s1\n");
        Files.createDirectories(state.resolve("refs"));
        Files.writeString(state.resolve("refs/event"), "reference");
        Files.createDirectories(Keep.backups(env).resolve("meta"));
        final StandIn answering =
                new StandIn() {
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
        answering.active = true;
        final Path into = Files.createDirectory(dir.resolve("into"));

        Keep.backup(into, env, answering, Duration.ofSeconds(1));

        try (var files = Files.list(into.resolve("positions"))) {
            assertThat(files.map(file -> file.getFileName().toString()))
                    .as("only the position and references are copied, never keep.wanted")
                    .containsExactlyInAnyOrder("abc.since", "refs");
        }
        assertThat(into.resolve("positions/refs/event"))
                .as("references are preserved alongside the position")
                .hasContent("reference");
    }

    /** Harm guarded: restoring a lock can split synchronization between running processes. */
    @Test
    void restoreRefusesAWantedLockAmongThePositions() throws Exception {
        final Path copy = copy("8008");
        Files.writeString(copy.resolve("positions").resolve(Keep.WANTED), "");

        assertThatThrownBy(() -> Keep.restore(copy, env, unit))
                .as("a copy cannot supply the live processes' backup gate")
                .isInstanceOfSatisfying(
                        Failure.class,
                        failure ->
                                assertThat(failure.exitCode())
                                        .as("a lock in the copy is a configuration refusal")
                                        .isEqualTo(Exit.CONFIG));
        assertThat(Poll.stateDirectory(env).resolve(Keep.WANTED))
                .as("the foreign lock is never restored")
                .doesNotExist();
        assertThat(unit.runs).as("the refused copy never reaches the homeserver").isZero();
        assertNothingMade();
    }

    /**
     * Harm guarded: restoring an earlier backup silently places a CA certificate and private key
     * into the account's configuration directory.
     */
    @Test
    @DisplayName("restore leaves any CA from an earlier backup out of the account")
    void restoreLeavesACaFromAnEarlierBackupBehind() throws Exception {
        final Path copy = copy("8008");
        Files.writeString(copy.resolve("ca.crt"), "the CA's certificate");
        Files.writeString(copy.resolve("ca.key"), "the CA's key");

        Keep.restore(copy, env, unit);

        assertThat(conf().resolveSibling("ca.crt"))
                .as("no CA certificate restored into the account")
                .doesNotExist();
        assertThat(conf().resolveSibling("ca.key"))
                .as("no CA private key restored into the account")
                .doesNotExist();
    }

    /** Harm guarded: an earlier version's published address comes back with its backup. */
    @Test
    @DisplayName("restore leaves an earlier backup's published address out of the settings")
    void restoreLeavesAPublishedAddressBehind() throws Exception {
        final Path copy = copy("8008");
        Files.writeString(
                copy.resolve("homeserver.conf"),
                "PORELO_MATRIX_PORT=8008\nPORELO_MATRIX_PUBLISH=192.0.2.1:8448\n"
                        + "PORELO_MATRIX_PUBLISH_NAME=host.example\n");

        Keep.restore(copy, env, unit);

        assertThat(conf()).as("only the port is restored").hasContent("PORELO_MATRIX_PORT=8008");
    }

    @Test
    @DisplayName("restore finds a database and leaves it as it is: nothing copied, nothing run")
    void restoreLeavesADatabaseAlone() throws Exception {
        final Path copy = copy("8008");
        unit.database = true;

        assertThat(Keep.restore(copy, env, unit).path("restore").asText()).isEqualTo("present");
        assertThat(unit.runs).as("runs of the homeserver's own").isZero();
        assertThat(Keep.backups(env)).as("no backups copied in").doesNotExist();
        assertThat(conf()).as("no settings written").doesNotExist();
    }

    @Test
    @DisplayName("restore refuses a copy made on another port, and makes nothing")
    void restoreRefusesAnotherPort() throws Exception {
        final Path copy = copy("8011");
        Files.createDirectories(conf().getParent());
        Files.writeString(conf(), "PORELO_MATRIX_PORT=8008\n", StandardCharsets.UTF_8);

        assertThatThrownBy(() -> Keep.restore(copy, env, unit))
                .isInstanceOfSatisfying(
                        Failure.class,
                        failure -> assertThat(failure.exitCode()).isEqualTo(Exit.CONFIG));
        assertThat(conf()).as("this account's settings").hasContent("PORELO_MATRIX_PORT=8008");
        assertThat(unit.runs).as("runs of the homeserver's own").isZero();
        assertNothingMade();
    }

    @Test
    @DisplayName("restore refuses a copy without a whole backup, and makes nothing")
    void restoreRefusesAnUnfinishedBackup() throws Exception {
        final Path copy = copy("8008");
        Files.delete(copy.resolve("database/meta/3"));

        assertThatThrownBy(() -> Keep.restore(copy, env, unit))
                .isInstanceOfSatisfying(
                        Failure.class,
                        failure -> assertThat(failure.exitCode()).isEqualTo(Exit.CONFIG));
        assertThat(conf()).as("no settings written").doesNotExist();
        assertNothingMade();
    }

    @Test
    @DisplayName(
            "restore refuses a copy holding a link, which could reach past it, and makes nothing")
    void restoreRefusesALink() throws Exception {
        final Path copy = copy("8008");
        Files.createSymbolicLink(
                copy.resolve("database/private/3/OPTIONS"), Path.of("/etc/passwd"));

        assertThatThrownBy(() -> Keep.restore(copy, env, unit))
                .isInstanceOfSatisfying(
                        Failure.class,
                        failure -> assertThat(failure.exitCode()).isEqualTo(Exit.CONFIG));
        assertThat(conf()).as("no settings written").doesNotExist();
        assertNothingMade();
    }

    @Test
    @DisplayName(
            "a restore whose run fails takes away all it made: settings, backups, positions, database")
    void aFailedRestoreLeavesNothing() throws Exception {
        final Path copy = copy("8008");
        unit.runExit = 1;

        assertThatThrownBy(() -> Keep.restore(copy, env, unit))
                .isInstanceOfSatisfying(
                        Failure.class,
                        failure -> assertThat(failure.exitCode()).isEqualTo(Exit.CONFIG));
        assertThat(unit.runs).as("runs of the homeserver's own").isEqualTo(1);
        assertThat(conf()).as("the settings it wrote").doesNotExist();
        assertNothingMade();
    }

    @Test
    @DisplayName(
            "restore refuses a copy whose positions hold anything but positions and references")
    void restoreRefusesAStrangePosition() throws Exception {
        final Path copy = copy("8008");
        Files.writeString(copy.resolve("positions/keep.lock"), "x");

        assertThatThrownBy(() -> Keep.restore(copy, env, unit))
                .isInstanceOfSatisfying(
                        Failure.class,
                        failure -> assertThat(failure.exitCode()).isEqualTo(Exit.CONFIG));
        assertThat(conf()).as("no settings written").doesNotExist();
        assertNothingMade();
    }

    @Test
    @DisplayName("restore refuses a homeserver that runs without its database")
    void restoreRefusesARunningHomeserver() throws Exception {
        final Path copy = copy("8008");
        unit.active = true;

        assertThatThrownBy(() -> Keep.restore(copy, env, unit))
                .isInstanceOfSatisfying(
                        Failure.class,
                        failure -> assertThat(failure.exitCode()).isEqualTo(Exit.CONFIG));
        assertNothingMade();
    }

    /** Harm guarded: a backup that says it is whole and holds no database to restore from. */
    @Test
    void aBackupHoldsTheHomeserversDatabaseAndItsSettings() throws Exception {
        Files.createDirectories(Keep.backups(env).resolve("meta"));
        Files.createDirectories(conf().getParent());
        Files.writeString(conf(), "PORELO_MATRIX_PORT=8008\n", StandardCharsets.UTF_8);
        final StandIn answering =
                new StandIn() {
                    @Override
                    public void signalBackup() throws Failure {
                        try {
                            final Path backups = Keep.backups(env);
                            Files.createDirectories(backups.resolve("private/7"));
                            Files.writeString(backups.resolve("private/7/CURRENT"), "database");
                            Files.writeString(backups.resolve("meta/7"), "snapshot");
                        } catch (final IOException failure) {
                            throw new Failure(
                                    Exit.CONFIG, "cannot make the test snapshot", failure);
                        }
                    }
                };
        answering.active = true;
        final Path into = Files.createDirectory(dir.resolve("into"));

        final var answer = Keep.backup(into, env, answering, Duration.ofSeconds(1));

        assertThat(answer.path("backup").asLong()).as("the backup it names").isEqualTo(7);
        assertThat(into.resolve("database/meta/7")).as("its mark").hasContent("snapshot");
        assertThat(into.resolve("database/private/7/CURRENT"))
                .as("the database")
                .hasContent("database");
        assertThat(into.resolve("homeserver.conf"))
                .as("the settings")
                .hasContent("PORELO_MATRIX_PORT=8008");
    }

    /** Harm guarded: another homeserver's data, whose name every account's id carries, is taken. */
    @Test
    void restoreRefusesAnotherServerName() throws Exception {
        final Path copy = copy("8008");
        Files.writeString(
                copy.resolve("homeserver.conf"),
                "PORELO_MATRIX_PORT=8008\n" + LocalHomeserver.SERVER_NAME + "=there.example\n");
        Files.createDirectories(conf().getParent());
        Files.writeString(
                conf(),
                "PORELO_MATRIX_PORT=8008\n" + LocalHomeserver.SERVER_NAME + "=here.example\n");

        assertThatThrownBy(() -> Keep.restore(copy, env, unit))
                .isInstanceOfSatisfying(
                        Failure.class,
                        failure -> assertThat(failure.exitCode()).isEqualTo(Exit.CONFIG))
                .hasMessageContaining("server name")
                .hasMessageContaining("there.example");
        assertThat(unit.runs).as("runs of the homeserver's own").isZero();
        assertNothingMade();
    }

    /**
     * Harm guarded: settings the copy only links to, read from wherever the link points and written
     * here as this account's; a link among the database would stop at its copying, this one not.
     */
    @Test
    void restoreRefusesSettingsThatAreALink() throws Exception {
        final Path copy = copy("8008");
        final Path elsewhere =
                Files.writeString(dir.resolve("elsewhere"), "PORELO_MATRIX_PORT=1\n");
        Files.delete(copy.resolve("homeserver.conf"));
        Files.createSymbolicLink(copy.resolve("homeserver.conf"), elsewhere);

        assertThatThrownBy(() -> Keep.restore(copy, env, unit))
                .isInstanceOfSatisfying(
                        Failure.class,
                        failure -> assertThat(failure.exitCode()).isEqualTo(Exit.CONFIG))
                .hasMessageContaining("neither a file nor a directory");
        assertThat(conf()).as("no settings written").doesNotExist();
        assertThat(unit.runs).as("runs of the homeserver's own").isZero();
    }

    /** Harm guarded: a failed restore leaves something behind, and nobody hears what. */
    @Test
    void whatAFailedRestoreCouldNotTakeAwayIsNamed() throws Exception {
        final Path copy = copy("8008");
        final Path stuck = Keep.backups(env).resolve("meta");
        final StandIn failing =
                new StandIn() {
                    @Override
                    public int runOnce(final List<String> args, final Map<String, String> secrets) {
                        stuck.toFile().setWritable(false);
                        return 1;
                    }
                };
        try {
            assertThatThrownBy(() -> Keep.restore(copy, env, failing))
                    .isInstanceOfSatisfying(
                            Failure.class,
                            failure -> assertThat(failure.exitCode()).isEqualTo(Exit.CONFIG))
                    .hasMessageContaining("left")
                    .hasMessageContaining(Keep.backups(env).toString());
        } finally {
            stuck.toFile().setWritable(true);
        }
    }

    /** A stand-in saying what was done to the homeserver, in order. */
    private static class Recording extends StandIn {

        final List<String> done = new java.util.ArrayList<>();

        @Override
        public void stop() {
            done.add("stop");
            super.stop();
        }

        @Override
        public void start() {
            done.add("start");
            super.start();
        }
    }

    /** Harm guarded: a password taken back from a run that failed leaves the homeserver down. */
    @Test
    void takingTheAdministratorBackStartsTheHomeserverAgainWhateverItsRunDoes() throws Exception {
        final Recording failing =
                new Recording() {
                    @Override
                    public int runOnce(final List<String> args, final Map<String, String> secrets) {
                        done.add("run");
                        throw new IllegalStateException("the run fell over");
                    }
                };
        final MatrixClient nobody =
                new MatrixClient(
                        Config.from(
                                Map.of(
                                        Config.HOMESERVER,
                                        "http://127.0.0.1:9",
                                        Config.ACCESS_TOKEN,
                                        "t")));

        assertThatThrownBy(
                        () ->
                                Keep.retake(
                                        nobody,
                                        URI.create("http://127.0.0.1:9"),
                                        env,
                                        failing,
                                        Duration.ofMillis(1)))
                .hasMessageContaining("the run fell over");

        assertThat(failing.done)
                .as("stopped, run, started")
                .containsExactly("stop", "run", "start");
        assertThat(failing.active).as("running again").isTrue();
    }

    /** Harm guarded: a stale backup loses newer conversation data. */
    @Test
    void anUnchangedBackupIdDoesNotCountAsANewBackup() throws Exception {
        final Path meta = Files.createDirectories(Keep.backups(env).resolve("meta"));
        Files.writeString(meta.resolve("7"), "old snapshot");
        final Path into = Files.createDirectory(dir.resolve("into"));
        unit.active = true;
        assertThatThrownBy(() -> Keep.backup(into, env, unit, Duration.ZERO))
                .isInstanceOfSatisfying(
                        Failure.class,
                        failure -> {
                            assertThat(failure.exitCode())
                                    .as("a new snapshot can be retried")
                                    .isEqualTo(Exit.TEMPORARY);
                            assertThat(failure.getMessage())
                                    .as("the unit did not publish a newer snapshot")
                                    .contains("made no backup");
                        });
        assertThat(into).as("no stale copy can be mistaken for a fresh one").isEmptyDirectory();
    }

    /** Harm guarded: backup cancellation is ignored. */
    @Test
    void anInterruptedBackupWaitStopsAndRemovesItsPartialCopy() throws Exception {
        Files.createDirectories(Keep.backups(env));
        final Path into = Files.createDirectory(dir.resolve("into"));
        unit.active = true;
        Thread.currentThread().interrupt();
        try {
            assertThatThrownBy(() -> Keep.backup(into, env, unit, Duration.ofMillis(300)))
                    .isInstanceOfSatisfying(
                            Failure.class,
                            failure -> {
                                assertThat(failure.exitCode())
                                        .as("interruption is retryable")
                                        .isEqualTo(Exit.TEMPORARY);
                                assertThat(failure.getMessage())
                                        .as("cancellation stops the wait before its deadline")
                                        .contains("interrupted while waiting");
                            });
            assertThat(Thread.currentThread().isInterrupted())
                    .as("caller retains cancellation")
                    .isTrue();
            assertThat(into).as("no partial copy remains").isEmptyDirectory();
        } finally {
            Thread.interrupted();
        }
    }

    /** Harm guarded: unrelated metadata crashes restoration. */
    @Test
    void restoreIgnoresNonNumericMetadataAndReportsTheRestoredId() throws Exception {
        final Path copy = copy("8008");
        Files.writeString(copy.resolve("database/meta/not-a-backup"), "unrelated");
        final var result = Keep.restore(copy, env, unit);
        assertThat(result).as("restore returns the backup identity to its caller").isNotNull();
        assertThat(result.path("restore").asLong())
                .as("only numeric metadata identifies a backup")
                .isEqualTo(3);
        assertThat(Poll.stateDirectory(env).resolve(Keep.MARKER))
                .as("next setup must retake the restored account")
                .hasContent("3");
    }

    /** Harm guarded: an unreleasable poll lock blocks backups. */
    @Test
    void closingTheReturnedSharedLockAllowsAnExclusiveWriter() throws Exception {
        final Path state = Files.createDirectories(Poll.stateDirectory(env));
        try (var channel = Keep.lockFile(state)) {
            try (var held = Keep.shared(channel, state)) {
                assertThat(held).as("the caller owns a releasable lock").isNotNull();
                assertThat(held.isValid()).as("the returned lock is held").isTrue();
            }
            try (var writer = channel.tryLock()) {
                assertThat(writer)
                        .as("closing the poll lock releases the position file")
                        .isNotNull();
            }
        }
    }

    /** Harm guarded: cancellation is ignored while a poll blocks a backup. */
    @Test
    void anInterruptedLockWaitStopsBeforeSignallingABackup() throws Exception {
        final Path state = Files.createDirectories(Poll.stateDirectory(env));
        Files.createDirectories(Keep.backups(env));
        final Path into = Files.createDirectory(dir.resolve("into"));
        unit.active = true;
        try (var channel = Keep.lockFile(state);
                var held = channel.lock()) {
            Thread.currentThread().interrupt();
            try {
                assertThatThrownBy(
                                () ->
                                        Keep.backup(
                                                into,
                                                env,
                                                unit,
                                                Duration.ZERO,
                                                Duration.ofMillis(300)))
                        .isInstanceOfSatisfying(
                                Failure.class,
                                failure -> {
                                    assertThat(failure.exitCode())
                                            .as("lock wait can be retried")
                                            .isEqualTo(Exit.TEMPORARY);
                                    assertThat(failure.getMessage())
                                            .as("cancellation rather than deadline exhaustion")
                                            .contains("interrupted while waiting");
                                });
                assertThat(unit.signals).as("no backup starts after cancellation").isZero();
            } finally {
                Thread.interrupted();
            }
        }
    }

    /** Harm guarded: invalid restored references lose message identities. */
    @Test
    void restoreRefusesAFileNamedRefs() throws Exception {
        final Path copy = copy("8008");
        Files.writeString(copy.resolve("positions/refs"), "not a directory");
        assertThatThrownBy(() -> Keep.restore(copy, env, unit))
                .isInstanceOfSatisfying(
                        Failure.class,
                        failure -> {
                            assertThat(failure.exitCode())
                                    .as("invalid backup is configuration")
                                    .isEqualTo(Exit.CONFIG);
                            assertThat(failure.getMessage())
                                    .as("references must be a directory")
                                    .contains("neither a poll position nor the references");
                        });
        assertNothingMade();
        assertThat(unit.runs).as("invalid references never reach the restore process").isZero();
    }
}
