package org.fuin.porelo.message.matrix;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
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
                            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                            // Closed, as Porelo hands it: the transport reads its input to the end.
                            .redirectInput(new java.io.File("/dev/null"));
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

    /** Starts enough to catch the moment between exec and the transport's first step, measured. */
    private static final int STARTS = 300;

    /**
     * Harm guarded: the token readable in the moment before the running transport makes itself
     * unreadable, which 25 of 300 starts showed while Porelo handed it in the environment.
     */
    @Test
    @DisplayName(
            "with the token on its input, no start of the transport shows it in /proc, from the"
                    + " moment the process exists, and each start used it")
    void tokenOnTheInputIsNeverInProc() throws IOException, InterruptedException {
        assumeTrue(Transport.isNative(), "the native executable is what Porelo runs");
        final byte[] input =
                ("{\"secrets\":{\""
                                + Config.HOMESERVER
                                + "\":\"%s\",\""
                                + Config.ACCESS_TOKEN
                                + "\":\""
                                + TOKEN
                                + "\"}}")
                        .getBytes(StandardCharsets.UTF_8);
        try (FakeHomeserver homeserver = new FakeHomeserver()) {
            final byte[] filled =
                    new String(input, StandardCharsets.UTF_8)
                            .formatted(homeserver.url())
                            .getBytes(StandardCharsets.UTF_8);
            int seen = 0;
            for (int start = 0; start < STARTS; start++) {
                homeserver.answer(200, "{\"user_id\":\"@bob:matrix.test\"}");
                final ProcessBuilder builder =
                        new ProcessBuilder(
                                        List.of(System.getProperty(Transport.EXECUTABLE), "check"))
                                .redirectErrorStream(true)
                                .redirectOutput(ProcessBuilder.Redirect.DISCARD);
                builder.environment().clear();
                final Process process = builder.start();
                final Path proc = Path.of("/proc", Long.toString(process.pid()));
                // Read before the input is written, so the transport has not yet run a step.
                seen += readable(proc);
                try (var stdin = process.getOutputStream()) {
                    stdin.write(filled);
                } catch (final IOException ex) {
                    // Gone before it read its input; its exit code below says so.
                }
                while (process.isAlive()) {
                    seen += readable(proc);
                }
                assertThat(process.waitFor())
                        .as("start %d used the token from its input", start)
                        .isZero();
            }
            assertThat(homeserver.requests())
                    .hasSize(STARTS)
                    .allSatisfy(
                            request ->
                                    assertThat(request.authorization())
                                            .isEqualTo("Bearer " + TOKEN));
            // Some reads must have caught environ before the transport made itself unreadable, or
            // the test proves nothing of that moment.
            assertThat(seen).as("reads of environ before it was unreadable").isPositive();
        }
    }

    /**
     * Reads environ and cmdline while they are readable, failing on the token; answers how often
     * environ was readable.
     */
    private static int readable(final Path proc) {
        int reads = 0;
        for (final String file : List.of("environ", "cmdline")) {
            try {
                final String text =
                        new String(Files.readAllBytes(proc.resolve(file)), StandardCharsets.UTF_8);
                assertThat(text).as("%s/%s", proc, file).doesNotContain(TOKEN);
                if ("environ".equals(file)) {
                    reads++;
                }
            } catch (final IOException ex) {
                // Unreadable or gone: nothing to read there.
            }
        }
        return reads;
    }
}
