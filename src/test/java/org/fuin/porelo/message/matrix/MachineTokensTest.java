package org.fuin.porelo.message.matrix;

import static org.assertj.core.api.Assertions.assertThat;
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
}
