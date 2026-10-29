package org.fuin.porelo.message.matrix;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class MachineTokensTest {

    @TempDir private Path dir;

    private Map<String, String> env;

    @BeforeEach
    void setUp() {
        env = Map.of("HOME", dir.toString(), "XDG_CONFIG_HOME", dir.resolve("config").toString());
        assertThat(
                        Main.run(
                                new String[] {"homeserver-token"},
                                env,
                                new PrintStream(PrintStream.nullOutputStream()),
                                new PrintStream(PrintStream.nullOutputStream())))
                .as("homeserver-token makes the homeserver's token file")
                .isEqualTo(Exit.OK);
    }

    private Path tokens() {
        return dir.resolve("config/porelo/matrix/tokens/registration-token");
    }

    /** Harm guarded: a machine named as a path writes a token outside the account's directory. */
    @ParameterizedTest
    @ValueSource(strings = {"../escape", "a/b", "", ".hidden", "Upper"})
    @DisplayName(
            "A machine's name that is not one Porelo gives is refused before anything is written")
    void refusesANameThatIsAPath(final String machine) {
        assertThatThrownBy(() -> MachineTokens.issue(env, machine))
                .as("'%s' is no machine's name", machine)
                .isInstanceOf(Failure.class)
                .extracting(failure -> ((Failure) failure).exitCode())
                .isEqualTo(Exit.USAGE);
        assertThat(dir.resolve("config/porelo/escape"))
                .as("nothing written outside the account's directory")
                .doesNotExist();
    }

    /** Harm guarded: another account reads the tokens that let anyone register accounts. */
    @Test
    @DisplayName(
            "The homeserver's token file and each machine's token are readable by their owner alone")
    void keepsTheTokensToTheirOwner() throws Exception {
        MachineTokens.issue(env, "hosta");

        for (final Path file :
                List.of(tokens(), dir.resolve("config/porelo/matrix/machine-tokens/hosta"))) {
            assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(file)))
                    .as("%s is its owner's alone", file)
                    .isEqualTo("rw-------");
        }
        assertThat(
                        PosixFilePermissions.toString(
                                Files.getPosixFilePermissions(
                                        dir.resolve("config/porelo/matrix/machine-tokens"))))
                .as("the machines' tokens lie in a directory only its owner can enter")
                .isEqualTo("rwx------");
    }

    /** Harm guarded: a machine's token is handed out that the homeserver never takes. */
    @Test
    @DisplayName(
            "Before the homeserver has a token file, a machine's token is refused and nothing is written")
    void refusesAMachinesTokenBeforeTheHomeserversOwn() throws Exception {
        final Map<String, String> fresh =
                Map.of(
                        "HOME",
                        dir.resolve("fresh").toString(),
                        "XDG_CONFIG_HOME",
                        dir.resolve("fresh/config").toString());

        assertThatThrownBy(() -> MachineTokens.issue(fresh, "hosta"))
                .as("no token is issued that no file the homeserver reads holds")
                .isInstanceOf(Failure.class)
                .extracting(failure -> ((Failure) failure).exitCode())
                .isEqualTo(Exit.CONFIG);
        assertThat(dir.resolve("fresh/config/porelo/matrix/machine-tokens/hosta"))
                .as("nothing kept that could be handed out later")
                .doesNotExist();
    }

    /** Harm guarded: an admitted machine is locked out once the token file is made anew. */
    @Test
    @DisplayName("A token file made anew holds the machines' tokens after the new local one")
    void keepsTheMachinesTokensWhenTheFileIsMadeAnew() throws Exception {
        final String token = MachineTokens.issue(env, "hosta");
        Files.writeString(tokens(), "");

        assertThat(
                        Main.run(
                                new String[] {"homeserver-token"},
                                env,
                                new PrintStream(PrintStream.nullOutputStream()),
                                new PrintStream(PrintStream.nullOutputStream())))
                .as("homeserver-token makes the homeserver's token file")
                .isEqualTo(Exit.OK);

        assertThat(Files.readAllLines(tokens(), StandardCharsets.US_ASCII))
                .as("a new local token first, then the admitted machine's")
                .hasSize(2)
                .endsWith(token);
    }

    /** Harm guarded: a revoked machine's token stays in the file the homeserver reads. */
    @Test
    @DisplayName(
            "The homeserver's file holds the local token first and exactly the tokens not revoked")
    void holdsTheLocalTokenAndTheMachinesNotRevoked() throws Exception {
        final String local = LocalHomeserver.registrationToken(env);
        final String a = MachineTokens.issue(env, "hosta");
        final String b = MachineTokens.issue(env, "hostb");

        assertThat(MachineTokens.revoke(env, "hosta")).as("it had a token").isTrue();
        assertThat(MachineTokens.revoke(env, "hosta")).as("revoked already").isFalse();

        assertThat(Files.readAllLines(tokens(), StandardCharsets.US_ASCII))
                .as("the local token, then the one machine still admitted")
                .containsExactly(local, b)
                .doesNotContain(a);
    }

    /**
     * Harm guarded: a person removed from the host gets a token again, and their new Porelo cannot
     * take its accounts' names back, which stay taken, so it fails later and unclearly.
     */
    @Test
    @DisplayName(
            "A machine expelled before is refused a token in words, even after its token was revoked")
    void refusesAnExpelledMachine() throws Exception {
        MachineTokens.issue(env, "hosta");
        assertThat(expel("hosta")).as("its token goes").isTrue();
        final String before = Files.readString(tokens());

        assertThatThrownBy(() -> MachineTokens.issue(env, "hosta"))
                .as("an expelled machine's name is taken for good")
                .isInstanceOf(Failure.class)
                .hasMessageContaining("hosta was expelled from this homeserver")
                .hasMessageContaining("add the person under another user name")
                .extracting(failure -> ((Failure) failure).exitCode())
                .isEqualTo(Exit.CONFIG);
        assertThat(dir.resolve("config/porelo/matrix/machine-tokens/hosta"))
                .as("no token written for it")
                .doesNotExist();
        assertThat(Files.readString(tokens()))
                .as("the homeserver's token file unchanged")
                .isEqualTo(before);
        assertThat(MachineTokens.issue(env, "hostb"))
                .as("another machine is issued a token")
                .isNotBlank();
    }

    /**
     * Harm guarded: an expelled machine keeps its token, and whoever holds it registers accounts of
     * other names on the homeserver after the person was removed.
     */
    @Test
    @DisplayName("Expelling a machine revokes its token at once, and again is no error")
    void expellingRevokesTheToken() throws Exception {
        final String token = MachineTokens.issue(env, "hosta");

        assertThat(expel("hosta")).as("it had a token").isTrue();

        assertThat(Files.readString(tokens()))
                .as("the homeserver's token file")
                .doesNotContain(token);
        assertThat(dir.resolve("config/porelo/matrix/expelled-machines/hosta"))
                .as("the record")
                .exists();
        assertThat(expel("hosta")).as("expelled again").isFalse();
    }

    /**
     * Harm guarded: a machine whose token was revoked first cannot be expelled, and its accounts
     * stay working.
     */
    @Test
    @DisplayName("A machine revoked before is expelled still, with no token to revoke")
    void expelsAMachineRevokedBefore() throws Exception {
        MachineTokens.issue(env, "hosta");
        MachineTokens.revoke(env, "hosta");

        assertThat(
                        MachineTokens.expel(
                                env, "hosta", "x", () -> List.of("@porelo.hosta:x", "@porelo:x")))
                .as("it had a token")
                .isFalse();

        assertThat(MachineTokens.expelled(env, "hosta")).as("recorded").isTrue();
        assertThat(MachineTokens.admitted(env, "hosta")).as("admitted").isFalse();
    }

    /**
     * Harm guarded: the accounts of one machine end as another's do, so expelling one deactivates
     * the other's, and issuing both lets each take the other's names.
     */
    @Test
    @DisplayName("Machines whose accounts' names would end alike are refused both ways")
    void refusesMachinesEndingAlike() throws Exception {
        MachineTokens.issue(env, "x.hosta");

        assertThatThrownBy(() -> MachineTokens.issue(env, "hosta"))
                .as("issued")
                .isInstanceOf(Failure.class)
                .hasMessageContaining("would end alike");
        assertThatThrownBy(() -> expel("hosta"))
                .as("expelled")
                .isInstanceOf(Failure.class)
                .hasMessageContaining("would end alike");
        assertThatThrownBy(() -> MachineTokens.admissible(env, "hosta"))
                .as("checked before")
                .isInstanceOf(Failure.class)
                .hasMessageContaining("would end alike");
        assertThat(MachineTokens.admitted(env)).as("admitted").containsExactly("x.hosta");
    }

    @Test
    @DisplayName("A machine expelled or named as a path is not admissible, checked before all else")
    void refusesBeforeAllElse() throws Exception {
        MachineTokens.issue(env, "hosta");
        expel("hosta");

        assertThatThrownBy(() -> MachineTokens.admissible(env, "hosta"))
                .isInstanceOf(Failure.class)
                .hasMessageContaining("hosta was expelled");
        assertThatThrownBy(() -> MachineTokens.admissible(env, "../hostb"))
                .isInstanceOf(Failure.class)
                .hasMessageContaining("not a machine's name");
        assertThatCode(() -> MachineTokens.admissible(env, "hostb")).doesNotThrowAnyException();
    }

    private boolean expel(final String machine) throws Failure {
        return MachineTokens.expel(env, machine, "x", () -> List.of("@porelo:x"));
    }

    /**
     * Harm guarded: a name mistyped expels nobody, yet takes the name for good, and the person
     * meant is left in.
     */
    @Test
    @DisplayName("A machine of which nothing is known is not expelled, and no record is made of it")
    void refusesToExpelAMachineUnknown() {
        assertThatThrownBy(() -> expel("hostz"))
                .isInstanceOf(Failure.class)
                .hasMessageContaining("hostz was never admitted")
                .extracting(failure -> ((Failure) failure).exitCode())
                .isEqualTo(Exit.CONFIG);
        assertThat(dir.resolve("config/porelo/matrix/expelled-machines/hostz"))
                .as("no record")
                .doesNotExist();
    }

    /**
     * Harm guarded: a machine admitted after another whose name ends its own takes that one's
     * accounts' names, and expelling either deactivates the other's.
     */
    @Test
    @DisplayName("A machine whose name ends as an admitted one's is refused too")
    void refusesAMachineEndingAsAnAdmittedOne() throws Exception {
        MachineTokens.issue(env, "hosta");

        assertThatThrownBy(() -> MachineTokens.issue(env, "x.hosta"))
                .isInstanceOf(Failure.class)
                .hasMessageContaining("would end alike");
    }

    /**
     * Harm guarded: a machine revoked or expelled is known no more, so another whose accounts end
     * alike is admitted, and expelling one deactivates the other's accounts.
     */
    @Test
    @DisplayName(
            "A machine expelled, or listed on the homeserver with no token, still stands against a name ending alike")
    void weighsEveryMachineKnown() throws Exception {
        MachineTokens.issue(env, "hosta");
        expel("hosta");

        assertThatThrownBy(() -> MachineTokens.issue(env, "x.hosta"))
                .as("against an expelled machine")
                .isInstanceOf(Failure.class)
                .hasMessageContaining("would end alike");
        assertThatThrownBy(
                        () ->
                                MachineTokens.issue(
                                        env,
                                        "y.hostb",
                                        "x",
                                        List.of("@porelo:x", "@porelo.hostb:x")))
                .as("against a machine listed by its @porelo")
                .isInstanceOf(Failure.class)
                .hasMessageContaining("would end alike");
        assertThatThrownBy(
                        () ->
                                MachineTokens.expel(
                                        env,
                                        "hostb",
                                        "x",
                                        () -> List.of("@porelo:x", "@porelo.y.hostb:x")))
                .as("expelling, against a machine listed by its @porelo")
                .isInstanceOf(Failure.class)
                .hasMessageContaining("would end alike");
    }

    /**
     * Harm guarded: a new person is given the name of accounts that exist already, and their Porelo
     * fails later and unclearly on names it cannot take.
     */
    @Test
    @DisplayName("A machine not admitted whose accounts are listed is refused; one admitted is not")
    void refusesNamesTaken() throws Exception {
        final List<String> listed = List.of("@porelo:x", "@porelo.hostc:x", "@task.hostc:x");

        assertThatThrownBy(() -> MachineTokens.issue(env, "hostc", "x", listed))
                .isInstanceOf(Failure.class)
                .hasMessageContaining("the homeserver has accounts of hostc already");
        assertThat(MachineTokens.admitted(env)).as("admitted").isEmpty();

        final String token = MachineTokens.issue(env, "hostd", "x", List.of("@porelo:x"));
        assertThat(MachineTokens.issue(env, "hostd", "x", List.of("@porelo:x", "@porelo.hostd:x")))
                .as("issued again once its accounts exist")
                .isEqualTo(token);
    }

    /**
     * Harm guarded: a refused issue registers the homeserver's administrator first, whose token is
     * then never answered.
     */
    @Test
    @DisplayName(
            "issue refuses a name, an expelled machine or one alike before the homeserver is asked")
    void issueRefusesBeforeTheHomeserver() throws Exception {
        MachineTokens.issue(env, "hosta");
        expel("hosta");
        MachineTokens.issue(env, "hostb");

        for (final String machine : List.of("../hostc", "hosta", "x.hostb")) {
            assertThatThrownBy(
                            () ->
                                    MachineTokenVerb.run(
                                            List.of("issue", machine),
                                            env,
                                            new PrintStream(PrintStream.nullOutputStream()),
                                            new UnusedUnit()))
                    .as(machine)
                    .isInstanceOf(Failure.class)
                    .hasMessageNotContaining("homeserver was asked");
        }
    }

    /** A homeserver that fails the test once asked anything. */
    private static final class UnusedUnit implements Unit {

        private static Failure asked() {
            return new Failure(Exit.TEMPORARY, "the homeserver was asked");
        }

        @Override
        public boolean installed() throws Failure {
            throw asked();
        }

        @Override
        public boolean active() throws Failure {
            throw asked();
        }

        @Override
        public String state() {
            return "unknown";
        }

        @Override
        public List<String> recentLog() {
            return List.of();
        }

        @Override
        public void start() throws Failure {
            throw asked();
        }

        @Override
        public void stop() throws Failure {
            throw asked();
        }

        @Override
        public boolean hasDatabase() throws Failure {
            throw asked();
        }

        @Override
        public void removeDatabase() throws Failure {
            throw asked();
        }

        @Override
        public void signalBackup() throws Failure {
            throw asked();
        }

        @Override
        public int runOnce(final List<String> args, final Map<String, String> secrets)
                throws Failure {
            throw asked();
        }
    }
}
