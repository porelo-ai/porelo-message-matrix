package org.fuin.porelo.message.matrix;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Harm guarded: while the account's own homeserver is down, another account on the machine can
 * listen on its loopback port, and every verb would hand that account a token. Here the other
 * account is a listener in a user namespace of its own, so the kernel shows it under a sub-uid, as
 * it would show another account.
 */
class LoopbackOwnerIT {

    private static final Pattern BASE =
            Pattern.compile("(?m)^\\s*base_image:\\s*\"([^\"]+@sha256:[0-9a-f]+)\"\\s*$");

    private static final String TOKEN = "syt_must_never_reach_another_account";

    @TempDir private Path dir;

    private String container;

    @AfterEach
    void tearDown() throws IOException, InterruptedException {
        if (container != null) {
            podman("rm", "-f", "-t", "0", container);
        }
    }

    @Test
    @DisplayName(
            "A loopback port another uid listens on gets no request and no token: 78, naming the owner")
    void anotherUidsListenerGetsNothing() throws Exception {
        assumeTrue(
                System.getenv(Tuwunel.BINARY) == null,
                "no container starts where the homeserver runs as a binary of the test");
        final int port;
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        container = "porelo-matrix-loopback-owner-" + port;
        podman(
                "run",
                "-d",
                "--rm",
                "--name",
                container,
                "--network",
                "host",
                "--userns=auto",
                "--user",
                "1000",
                baseImage(),
                "perl",
                "-e",
                "use Socket; socket(S,PF_INET,SOCK_STREAM,0) or die; setsockopt(S,SOL_SOCKET,SO_REUSEADDR,1);"
                        + " bind(S,pack_sockaddr_in("
                        + port
                        + ",inet_aton(\"127.0.0.1\"))) or die \"bind: $!\"; listen(S,5) or die;"
                        + " $|=1; print \"listening\\n\"; while (accept(C,S)) { print \"accepted\\n\";"
                        + " while (<C>) { print \"got: $_\"; last if /^\\r?$/ } close(C) }");
        awaitLog("listening");

        final Transport.Result result =
                Transport.run(
                        Map.of(
                                Config.HOMESERVER,
                                "http://127.0.0.1:" + port,
                                Config.ACCESS_TOKEN,
                                TOKEN,
                                "XDG_STATE_HOME",
                                dir.toString()),
                        "poll",
                        "--into",
                        Files.createDirectories(dir.resolve("inbound")).toString());

        assertThat(podman("logs", container))
                .as("what the other account's listener received")
                .doesNotContain("accepted")
                .doesNotContain(TOKEN);
        assertThat(result.exit())
                .as("refused before any request: %s", result.err())
                .isEqualTo(Exit.CONFIG);
        assertThat(result.err())
                .as("says which port and that another uid holds it")
                .contains("127.0.0.1:" + port)
                .contains("uid");
    }

    /** The Ubuntu image this repository's task is built from, by its digest. */
    private static String baseImage() throws IOException {
        final Matcher m =
                BASE.matcher(Files.readString(Path.of("porelo.yml"), StandardCharsets.UTF_8));
        if (!m.find()) {
            throw new IllegalStateException("porelo.yml names no base_image by its digest");
        }
        return m.group(1);
    }

    private void awaitLog(final String line) throws IOException, InterruptedException {
        final Instant deadline = Instant.now().plus(Duration.ofSeconds(120));
        while (Instant.now().isBefore(deadline)) {
            if (podman("logs", container).contains(line)) {
                return;
            }
            Thread.sleep(200);
        }
        throw new IllegalStateException(
                container + " did not say '" + line + "': " + podman("logs", container));
    }

    private static String podman(final String... args) throws IOException, InterruptedException {
        final List<String> command = new ArrayList<>(List.of("podman"));
        command.addAll(List.of(args));
        final Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        final String output =
                new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (process.waitFor() != 0) {
            throw new IllegalStateException(String.join(" ", command) + " failed: " + output);
        }
        return output;
    }
}
