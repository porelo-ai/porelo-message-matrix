package org.fuin.porelo.message.matrix;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Harm guarded: a homeserver that restarts while a verb runs - a poll holding its sync open, a
 * setup's dozen requests - leaves its port free for another account, and a client that checked its
 * owner only once hands that account the token with its next request.
 */
class MatrixClientOwnerTest {

    private static final String HEAD =
            "  sl  local_address rem_address   st tx_queue rx_queue tr tm->when retrnsmt   uid  timeout inode";

    @TempDir private Path dir;

    private static String listening(final int port, final long uid) {
        return String.format(
                "   0: 0100007F:%04X 00000000:0000 0A 00000000:00000000 00:00000000 00000000  %d"
                        + "        0 29667 1 0000000000000000 100 0 0 10 0",
                port, uid);
    }

    @Test
    void localhostSuffixConnectsToIpv4WithoutDns() throws Exception {
        try (FakeHomeserver homeserver = new FakeHomeserver()) {
            homeserver.answer(200, "{\"ok\":true}");
            final URI url = URI.create(homeserver.url().replace("127.0.0.1", "forward.localhost"));
            final MatrixClient client = new MatrixClient(new Config(url, "syt_token", null, true));

            assertThat(client.get("/_matrix/test").path("ok").asBoolean())
                    .as("the IPv4 loopback listener answers a localhost subdomain without DNS")
                    .isTrue();
            assertThat(homeserver.requests())
                    .as("one request reached the loopback server")
                    .hasSize(1);
        }
    }

    @Test
    void localhostSuffixRefusesAnotherOwnersPortBeforeSending() throws Exception {
        final long own = Listener.ownUid(Path.of("/proc/self/status"));
        final Path tcp = dir.resolve("tcp");
        Files.write(tcp, List.of(HEAD, listening(8008, own + 1)), StandardCharsets.US_ASCII);
        final MatrixClient client =
                new MatrixClient(
                        new Config(URI.create("http://a.localhost:8008"), "syt_token", null, true),
                        List.of(tcp));

        assertThatThrownBy(() -> client.get("/_matrix/client/v3/account/whoami"))
                .as("another account must receive no token on a localhost subdomain")
                .isInstanceOfSatisfying(
                        Failure.class,
                        failure -> {
                            assertThat(failure.exitCode())
                                    .as("the listener's owner is refused before connecting")
                                    .isEqualTo(Exit.CONFIG);
                            assertThat(failure)
                                    .as("the token was withheld")
                                    .hasMessageContaining("no token goes to it");
                        });
    }

    @Test
    @DisplayName(
            "Every request asks again whose the port is: another uid there by then gets nothing")
    void everyRequestAsksAgain() throws Exception {
        try (FakeHomeserver homeserver = new FakeHomeserver()) {
            final URI url = URI.create(homeserver.url());
            final long own = Listener.ownUid(Path.of("/proc/self/status"));
            final Path tcp = dir.resolve("tcp");
            Files.write(
                    tcp, List.of(HEAD, listening(url.getPort(), own)), StandardCharsets.US_ASCII);
            final MatrixClient client =
                    new MatrixClient(new Config(url, "syt_token", null, true), List.of(tcp));
            homeserver.answer(200, "{\"user_id\":\"@bob:matrix.test\"}");
            client.get("/_matrix/client/v3/account/whoami");

            Files.write(
                    tcp,
                    List.of(HEAD, listening(url.getPort(), own + 1)),
                    StandardCharsets.US_ASCII);

            assertThatThrownBy(() -> client.get("/_matrix/client/v3/account/whoami"))
                    .as("the second request, once another uid holds the port")
                    .isInstanceOfSatisfying(
                            Failure.class,
                            failure ->
                                    assertThat(failure.exitCode())
                                            .as("refused as configuration")
                                            .isEqualTo(Exit.CONFIG));
            assertThat(homeserver.requests()).as("only the first request went out").hasSize(1);
        }
    }
}
