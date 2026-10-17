package org.fuin.porelo.message.matrix;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The local homeserver's port: chosen once, kept in homeserver.conf. Starting the unit is measured
 * on the VM.
 */
class LocalHomeserverTest {

    @TempDir private Path dir;

    @Test
    @DisplayName(
            "Without a port in homeserver.conf, a free one from 8008 on is chosen and written there, beside what the file holds")
    void choosesAndKeeps() throws Exception {
        final Path conf = dir.resolve("porelo/matrix/homeserver.conf");
        Files.createDirectories(conf.getParent());
        Files.writeString(conf, "PORELO_MATRIX_SERVER_NAME=localhost\n", StandardCharsets.UTF_8);

        final int port = LocalHomeserver.port(conf);

        assertThat(port).isBetween(8008, 8099);
        assertThat(Files.readAllLines(conf, StandardCharsets.UTF_8))
                .containsExactly(
                        "PORELO_MATRIX_SERVER_NAME=localhost", "PORELO_MATRIX_PORT=" + port);
        assertThat(LocalHomeserver.port(conf)).as("chosen once, then kept").isEqualTo(port);
    }

    @Test
    @DisplayName("A port that is taken is passed over")
    void passesOverATakenPort() throws Exception {
        final Path conf = dir.resolve("homeserver.conf");
        final int first = LocalHomeserver.port(dir.resolve("probe.conf"));
        try (ServerSocket taken = new ServerSocket()) {
            taken.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), first));

            assertThat(LocalHomeserver.port(conf)).isNotEqualTo(first);
        }
    }

    @Test
    @DisplayName(
            "A port already in the file is used as it is, and one that is not a number is refused with 78")
    void usesTheFile() throws IOException, Failure {
        final Path conf =
                Files.writeString(
                        dir.resolve("homeserver.conf"),
                        "PORELO_MATRIX_PORT=18008\n",
                        StandardCharsets.UTF_8);
        assertThat(LocalHomeserver.port(conf)).isEqualTo(18008);

        Files.write(conf, List.of("PORELO_MATRIX_PORT=eighty"), StandardCharsets.UTF_8);
        assertThatThrownBy(() -> LocalHomeserver.port(conf))
                .isInstanceOf(Failure.class)
                .satisfies(ex -> assertThat(((Failure) ex).exitCode()).isEqualTo(Exit.CONFIG));
    }

    @Test
    @DisplayName("The configuration directory follows XDG_CONFIG_HOME, else HOME")
    void directory() throws Failure {
        assertThat(LocalHomeserver.directory(Map.of("XDG_CONFIG_HOME", "/c", "HOME", "/h")))
                .isEqualTo(Path.of("/c/porelo/matrix"));
        assertThat(LocalHomeserver.directory(Map.of("HOME", "/h")))
                .isEqualTo(Path.of("/h/.config/porelo/matrix"));
    }
}
