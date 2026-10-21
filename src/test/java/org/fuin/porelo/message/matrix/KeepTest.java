package org.fuin.porelo.message.matrix;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
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
    private static final class StandIn implements Unit {

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
        public void signalBackup() {
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
