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
import java.time.Instant;
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
        Files.createDirectories(LocalHomeserver.directory(env));
        Files.writeString(LocalHomeserver.directory(env).resolve("registration-token"), "Q7\n");
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
                        failure -> assertThat(failure.exitCode()).isEqualTo(Exit.CONFIG));
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

    /** Harm guarded: the copy hands the CA's key to every account that can read the copy. */
    @Test
    @DisplayName("A backup copies the account's CA, its key readable by its owner alone")
    void aBackupCopiesTheCaToItsOwner() throws Exception {
        Files.createDirectories(Keep.backups(env).resolve("meta"));
        Certificate.ensure(
                LocalHomeserver.directory(env),
                java.net.InetAddress.ofLiteral("192.0.2.2"),
                Instant.now());
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

        assertThat(into.resolve("ca.crt"))
                .as("the CA other machines trust")
                .hasSameTextualContentAs(LocalHomeserver.directory(env).resolve("ca.crt"));
        assertThat(
                        java.nio.file.attribute.PosixFilePermissions.toString(
                                Files.getPosixFilePermissions(into.resolve("ca.key"))))
                .as("the CA's key in the copy is its owner's alone")
                .isEqualTo("rw-------");
        assertThat(into.resolve("tls")).as("the server certificate stays behind").doesNotExist();
    }

    /**
     * Harm guarded: the restored machine claims the address of the one it was copied from, or
     * leaves the CA's key readable.
     */
    @Test
    @DisplayName(
            "A restore keeps the copy's CA, its owner's alone, and drops the address it was published on")
    void restoreKeepsTheCaAndDropsTheAddress() throws Exception {
        final Path copy = copy("8008");
        Files.writeString(
                copy.resolve("homeserver.conf"),
                "PORELO_MATRIX_PORT=8008\nPORELO_MATRIX_PUBLISH=192.0.2.2:8448\n",
                StandardCharsets.UTF_8);
        Files.writeString(copy.resolve("ca.crt"), "the CA's certificate");
        Files.writeString(copy.resolve("ca.key"), "the CA's key");

        Keep.restore(copy, env, unit);

        assertThat(conf())
                .as("the port kept, the other machine's address dropped")
                .hasContent("PORELO_MATRIX_PORT=8008");
        assertThat(conf().resolveSibling("ca.crt")).hasContent("the CA's certificate");
        assertThat(
                        java.nio.file.attribute.PosixFilePermissions.toString(
                                Files.getPosixFilePermissions(conf().resolveSibling("ca.key"))))
                .as("the restored key is its owner's alone")
                .isEqualTo("rw-------");
    }

    /** Harm guarded: a restored certificate is paired with a key that does not sign for it. */
    @Test
    @DisplayName("A restore refuses half a CA, in the copy or on this account, and makes nothing")
    void restoreRefusesHalfACa() throws Exception {
        final Path copy = copy("8008");
        Files.writeString(copy.resolve("ca.crt"), "the copy's CA");
        Files.writeString(copy.resolve("ca.key"), "its key");
        Files.createDirectories(conf().getParent());
        Files.writeString(conf().resolveSibling("ca.key"), "a stray key of this account's");

        assertThatThrownBy(() -> Keep.restore(copy, env, unit))
                .as("this account's stray key is never paired with the copy's certificate")
                .isInstanceOfSatisfying(
                        Failure.class,
                        failure -> assertThat(failure.exitCode()).isEqualTo(Exit.CONFIG));
        assertThat(conf().resolveSibling("ca.crt")).as("no certificate copied in").doesNotExist();
        assertNothingMade();

        Files.delete(conf().resolveSibling("ca.key"));
        Files.delete(copy.resolve("ca.key"));
        assertThatThrownBy(() -> Keep.restore(copy, env, unit))
                .as("a copy with a certificate and no key is refused")
                .isInstanceOfSatisfying(
                        Failure.class,
                        failure -> assertThat(failure.exitCode()).isEqualTo(Exit.CONFIG));
        assertNothingMade();
    }

    /** Harm guarded: a restore silently swaps the CA the other machines trust. */
    @Test
    @DisplayName(
            "A restore refuses a copy with another CA than the account's own, and makes nothing")
    void restoreRefusesAnotherCa() throws Exception {
        final Path copy = copy("8008");
        Files.writeString(copy.resolve("ca.crt"), "the copy's CA");
        Files.writeString(copy.resolve("ca.key"), "its key");
        Files.createDirectories(conf().getParent());
        Files.writeString(conf().resolveSibling("ca.crt"), "this account's CA");

        assertThatThrownBy(() -> Keep.restore(copy, env, unit))
                .isInstanceOfSatisfying(
                        Failure.class,
                        failure -> assertThat(failure.exitCode()).isEqualTo(Exit.CONFIG));
        assertThat(conf().resolveSibling("ca.crt")).hasContent("this account's CA");
        assertNothingMade();
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
}
