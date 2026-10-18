package org.fuin.porelo.message.matrix;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Harm guarded: a token in the running transport's environment, read by any other process of the
 * same account from {@code /proc}; with the administrator's, that process can deactivate every
 * account and read every room. Only the native executable is the program Porelo runs, so only it is
 * asked.
 */
class DumpableIT {

    private static final String TOKEN = "syt_the_account_token_nobody_else_reads";

    @Test
    @DisplayName("another process of the account cannot read the running transport's environment")
    void environmentIsNotReadable() throws IOException, InterruptedException {
        assumeTrue(Transport.isNative(), "the native executable is what Porelo runs");
        // A homeserver that takes the connection and never answers keeps the transport running.
        try (ServerSocket silent = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            final ProcessBuilder builder =
                    new ProcessBuilder(List.of(System.getProperty(Transport.EXECUTABLE), "check"))
                            .redirectErrorStream(true)
                            .redirectOutput(ProcessBuilder.Redirect.DISCARD);
            builder.environment().clear();
            builder.environment()
                    .put(Config.HOMESERVER, "http://127.0.0.1:" + silent.getLocalPort());
            builder.environment().put(Config.ACCESS_TOKEN, TOKEN);
            final Process process = builder.start();
            try (Socket held = silent.accept()) {
                // The transport is in its request now, so it is past its start.
                final Path environ = Path.of("/proc", Long.toString(process.pid()), "environ");
                assertThat(process.isAlive()).isTrue();
                assertThatThrownBy(() -> Files.readAllBytes(environ))
                        .isInstanceOf(AccessDeniedException.class);
            } finally {
                process.destroyForcibly().waitFor();
            }
        }
    }
}
