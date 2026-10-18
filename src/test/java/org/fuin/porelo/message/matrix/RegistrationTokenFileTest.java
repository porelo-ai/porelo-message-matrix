package org.fuin.porelo.message.matrix;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Harm guarded: the registration token lets anyone register accounts on the homeserver, so a file
 * another account can read hands that to it; an empty or missing file would register with nothing
 * and fail far from its cause.
 */
class RegistrationTokenFileTest {

    @TempDir private Path dir;

    private Path file(final String name, final String content, final String permissions)
            throws IOException {
        final Path file = Files.writeString(dir.resolve(name), content);
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString(permissions));
        return file;
    }

    @Test
    @DisplayName("The token is read from a file only the account can read, without its line end")
    void readFromOwnFile() throws Exception {
        assertThat(RegistrationTokenFile.read(file("own", "the-token\n", "rw-------")))
                .isEqualTo("the-token");
    }

    @Test
    @DisplayName("A file the group or others can read, an empty one and a missing one are 78")
    void refused() throws Exception {
        for (final Path bad :
                new Path[] {
                    file("group", "the-token", "rw-r-----"),
                    file("others", "the-token", "rw----r--"),
                    file("empty", "\n", "rw-------"),
                    dir.resolve("absent")
                }) {
            assertThatThrownBy(() -> RegistrationTokenFile.read(bad))
                    .as(bad.toString())
                    .isInstanceOf(Failure.class)
                    .satisfies(f -> assertThat(((Failure) f).exitCode()).isEqualTo(Exit.CONFIG));
        }
    }
}
