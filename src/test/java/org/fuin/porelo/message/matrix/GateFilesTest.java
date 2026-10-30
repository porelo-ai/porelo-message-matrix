package org.fuin.porelo.message.matrix;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GateFilesTest {
    @TempDir private Path dir;

    /**
     * Harm guarded: a public or malformed administrator file leaks a credential through a
     * diagnostic.
     */
    @Test
    void administratorCredentialsMustBePrivateAndValid() throws Exception {
        final Path file = dir.resolve("admin-token");
        GateFiles.write(file, Accounts.secret(), false);
        assertThat(GateFiles.readToken(file).isEmpty()).as("private token is accepted").isFalse();
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-r--r--"));
        assertThatThrownBy(() -> GateFiles.readToken(file))
                .as("public file is refused")
                .isInstanceOf(IOException.class);
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
        GateFiles.write(file, "invalid\ncredential", true);
        assertThatThrownBy(() -> GateFiles.readToken(file))
                .as("invalid header characters are refused before HTTP construction")
                .isInstanceOf(IOException.class)
                .hasMessage("gate token file contains no valid credential");
    }

    /** Harm guarded: atomic publication follows a symlink or replaces a first writer's token. */
    @Test
    void firstWriterWinsAndSymbolicLinksAreRefused() throws Exception {
        final Path file = dir.resolve("record");
        GateFiles.write(file, "first", false);
        assertThatThrownBy(() -> GateFiles.write(file, "second", false))
                .as("one winner")
                .isInstanceOf(java.nio.file.FileAlreadyExistsException.class);
        assertThat(GateFiles.read(file)).as("original record remains").isEqualTo("first");
        final Path link = dir.resolve("link");
        Files.createSymbolicLink(link, file);
        assertThatThrownBy(() -> GateFiles.read(link))
                .as("symlink cannot supply a credential")
                .isInstanceOf(IOException.class);
    }
}
