package org.fuin.porelo.message.matrix;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HomeserverTokenTest {

    @TempDir private Path home;

    private final ByteArrayOutputStream errBytes = new ByteArrayOutputStream();

    private int run(final Map<String, String> env) {
        final PrintStream err = new PrintStream(errBytes, true, StandardCharsets.UTF_8);
        return Main.run(new String[] {"homeserver-token"}, env, err, err);
    }

    private Path token() {
        return home.resolve(".config/porelo/matrix/registration-token");
    }

    @Test
    @DisplayName(
            "It makes the token once, readable by its owner alone, in a directory only its owner can enter")
    void makesTheToken() throws IOException {
        assertThat(run(Map.of("HOME", home.toString())))
                .as(errBytes.toString(StandardCharsets.UTF_8))
                .isEqualTo(Exit.OK);

        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(token())))
                .isEqualTo("rw-------");
        assertThat(
                        PosixFilePermissions.toString(
                                Files.getPosixFilePermissions(token().getParent())))
                .isEqualTo("rwx------");
        assertThat(Files.readString(token(), StandardCharsets.US_ASCII))
                .matches("[A-Za-z0-9_-]{43}");
    }

    @Test
    @DisplayName("An existing token is kept as it is, since Porelo may hold it")
    void keepsAnExistingToken() throws IOException {
        run(Map.of("HOME", home.toString()));
        final String first = Files.readString(token(), StandardCharsets.US_ASCII);

        assertThat(run(Map.of("HOME", home.toString()))).isEqualTo(Exit.OK);

        assertThat(Files.readString(token(), StandardCharsets.US_ASCII)).isEqualTo(first);
    }

    /**
     * Harm guarded: a start stopped between making the file and writing it leaves it empty; kept,
     * every later setup fails on it with 78 and the homeserver takes no registration, until
     * somebody removes it by hand.
     */
    @Test
    @DisplayName("An empty token file, left by a start stopped half-way, is made anew")
    void replacesAnEmptyToken() throws IOException {
        Files.createDirectories(
                token().getParent(),
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        Files.createFile(
                token(),
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));

        assertThat(run(Map.of("HOME", home.toString())))
                .as(errBytes.toString(StandardCharsets.UTF_8))
                .isEqualTo(Exit.OK);

        assertThat(Files.readString(token(), StandardCharsets.US_ASCII))
                .as("the token")
                .matches("[A-Za-z0-9_-]{43}");
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(token())))
                .as("who may read it")
                .isEqualTo("rw-------");
        try (var left = Files.list(token().getParent())) {
            assertThat(left.map(p -> p.getFileName().toString()).toList())
                    .as("what is left in the directory")
                    .containsExactly("registration-token");
        }
    }

    @Test
    @DisplayName("XDG_CONFIG_HOME is where it goes when it is set")
    void followsXdgConfigHome() {
        final Path config = home.resolve("elsewhere");

        assertThat(run(Map.of("HOME", home.toString(), "XDG_CONFIG_HOME", config.toString())))
                .isEqualTo(Exit.OK);

        assertThat(config.resolve("porelo/matrix/registration-token")).isRegularFile();
        assertThat(token()).doesNotExist();
    }

    @Test
    @DisplayName("A token others can read is refused with 78, not used")
    void refusesAReadableToken() throws IOException {
        run(Map.of("HOME", home.toString()));
        Files.setPosixFilePermissions(token(), PosixFilePermissions.fromString("rw-r--r--"));

        assertThat(run(Map.of("HOME", home.toString()))).isEqualTo(Exit.CONFIG);
        assertThat(errBytes.toString(StandardCharsets.UTF_8))
                .contains("readable by more than its owner");
    }

    @Test
    @DisplayName("Without HOME or XDG_CONFIG_HOME there is nowhere to put it: 78")
    void nowhereToPutIt() {
        assertThat(run(Map.of())).isEqualTo(Exit.CONFIG);
    }
}
