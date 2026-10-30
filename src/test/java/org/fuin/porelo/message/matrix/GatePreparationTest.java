package org.fuin.porelo.message.matrix;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GatePreparationTest {
    @TempDir private Path dir;

    /** Harm guarded: a host mode still exposes TCP or cannot start its private socket listener. */
    @Test
    void preparationKeepsOtherSettingsAndIsIdempotentBeforeStartup() throws Exception {
        final Map<String, String> env =
                Map.of(
                        "XDG_STATE_HOME",
                        dir.resolve("state").toString(),
                        "XDG_CONFIG_HOME",
                        dir.resolve("config").toString(),
                        "XDG_RUNTIME_DIR",
                        dir.resolve("runtime").toString());
        final Path conf = LocalHomeserver.directory(env).resolve("homeserver.conf");
        Files.createDirectories(conf.getParent());
        Files.writeString(conf, "# a setting to keep\nPORELO_MATRIX_PORT=18008\n");
        final var bytes = new ByteArrayOutputStream();
        final var out = new PrintStream(bytes);
        assertThat(Main.run(new String[] {"gate", "--prepare"}, env, out, out))
                .as("prepare without a running homeserver")
                .isZero();
        assertThat(
                        MatrixClient.JSON
                                .readTree(bytes.toString())
                                .path("administrator")
                                .asBoolean(true))
                .as("administrator waits for the socket")
                .isFalse();
        assertThat(Files.readString(conf))
                .as("unrelated configuration survives")
                .contains(
                        "# a setting to keep\nPORELO_MATRIX_PORT=18008\n",
                        "PORELO_MATRIX_GATE=1\n",
                        "PORELO_MATRIX_INNER_PORT=8009\n");
        final var modified = Files.getLastModifiedTime(conf);
        bytes.reset();
        assertThat(Main.run(new String[] {"gate", "--prepare"}, env, out, out))
                .as("repeat")
                .isZero();
        assertThat(Files.getLastModifiedTime(conf)).as("no repeated rewrite").isEqualTo(modified);
        assertThat(
                        PosixFilePermissions.toString(
                                Files.getPosixFilePermissions(GateFiles.directory(env))))
                .as("only the homeserver account enters")
                .isEqualTo("rwx------");
        assertThat(Main.run(new String[] {"homeserver-token"}, env, out, out))
                .as("unit preparation")
                .isZero();
        assertThat(Files.readString(conf.getParent().resolve(Publish.TLS_ENV)))
                .as("only the private socket is added to the container environment")
                .isEqualTo(
                        "TUWUNEL_UNIX_SOCKET_PATH=/gate/homeserver.sock\nTUWUNEL_UNIX_SOCKET_PERMS=600\n");
        assertThat(
                        PosixFilePermissions.toString(
                                Files.getPosixFilePermissions(GateFiles.runtime(env))))
                .as("private mount")
                .isEqualTo("rwx------");
    }

    /** Harm guarded: a manually started gate trusts stdin as an authenticated connection. */
    @Test
    void startupWithoutAnInheritedSocketIsRefused() {
        final var bytes = new ByteArrayOutputStream();
        final var out = new PrintStream(bytes);
        assertThat(Main.run(new String[] {"gate"}, Map.of(), out, out))
                .as("no authenticated inherited connection")
                .isEqualTo(78);
    }
}
