package org.fuin.porelo.message.matrix;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.StandardProtocolFamily;
import java.net.URI;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.attribute.UserPrincipal;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/** Who may read a registration token that goes over a Unix socket. */
@Timeout(10)
class RegisterOverASocketTest {

    private static final String TOKEN_CHALLENGE =
            "{\"flows\":[{\"stages\":[\"m.login.registration_token\"]}],\"session\":\"s\"}";

    private static final String REGISTERED =
            "{\"user_id\":\"@porelo.bob:example.org\",\"access_token\":\"new-session\"}";

    @TempDir private Path dir;

    /** Harm guarded: a person's registration token goes to another account's listener. */
    @Test
    void aTokenDoesNotGoToAListenerOfAnotherAccount() throws Exception {
        final UserPrincipal nobody =
                FileSystems.getDefault()
                        .getUserPrincipalLookupService()
                        .lookupPrincipalByName("nobody");
        try (ScriptedSocket socket =
                new ScriptedSocket(dir.resolve("room.sock"), TOKEN_CHALLENGE)) {
            assertThatThrownBy(
                            () ->
                                    Accounts.register(
                                            client(),
                                            "porelo.bob",
                                            "derived-password",
                                            "a-lent-token",
                                            () -> nobody))
                    .isInstanceOfSatisfying(
                            Failure.class,
                            failure ->
                                    assertThat(failure.exitCode())
                                            .as("waiting cures nothing")
                                            .isEqualTo(Exit.CONFIG))
                    .hasMessageContaining("nobody");
            socket.awaitTheLastConnection();
            assertThat(socket.requests())
                    .as("only the challenge's request arrived, without the token")
                    .hasSize(1)
                    .noneMatch(request -> request.contains("a-lent-token"));
        }
    }

    /** Harm guarded: the check refuses the tunnel's own end, so no moved task can register. */
    @Test
    void aTokenGoesToAListenerOfThisAccount() throws Exception {
        try (ScriptedSocket socket =
                new ScriptedSocket(dir.resolve("room.sock"), TOKEN_CHALLENGE, REGISTERED)) {
            final Accounts.Session account =
                    Accounts.register(client(), "porelo.bob", "derived-password", "a-lent-token");
            assertThat(account.userId())
                    .as("the account was made")
                    .isEqualTo("@porelo.bob:example.org");
            assertThat(socket.requests().getLast())
                    .as("the token answered the challenge")
                    .contains("a-lent-token");
        }
    }

    private MatrixClient client() throws Failure {
        return new MatrixClient(
                new Config(URI.create("unix:" + dir.resolve("room.sock")), "", null, true));
    }

    /** Answers each connection with the next JSON body as a 401, the last one as a 200. */
    private static final class ScriptedSocket implements AutoCloseable {
        private final ServerSocketChannel server;
        private final List<String> requests = new CopyOnWriteArrayList<>();
        private final Thread worker;

        ScriptedSocket(final Path path, final String... bodies) throws IOException {
            server = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
            server.bind(UnixDomainSocketAddress.of(path));
            worker = Thread.ofVirtual().start(() -> answer(bodies));
        }

        private void answer(final String... bodies) {
            try {
                for (final String body : bodies) {
                    try (SocketChannel socket = server.accept()) {
                        final String request = read(Channels.newInputStream(socket));
                        requests.add(request);
                        final String status =
                                REGISTERED.equals(body) ? "200 OK" : "401 Unauthorized";
                        final String reply =
                                "HTTP/1.1 " + status + "\r\nConnection: close\r\n\r\n" + body;
                        Channels.newOutputStream(socket)
                                .write(reply.getBytes(StandardCharsets.UTF_8));
                    }
                }
                // The refused client still connects: whatever it writes there is what must not go.
                try (SocketChannel socket = server.accept()) {
                    final String last = read(Channels.newInputStream(socket));
                    if (!last.isEmpty()) {
                        requests.add(last);
                    }
                }
            } catch (final ClosedChannelException ex) {
                // Closed by the test's end, with nothing more to answer.
                return;
            } catch (final IOException ex) {
                requests.add("failed: " + ex);
            }
        }

        private static String read(final InputStream in) throws IOException {
            final ByteArrayOutputStream head = new ByteArrayOutputStream();
            while (!head.toString(StandardCharsets.US_ASCII).endsWith("\r\n\r\n")) {
                final int next = in.read();
                if (next < 0) {
                    return head.toString(StandardCharsets.US_ASCII);
                }
                head.write(next);
            }
            final String text = head.toString(StandardCharsets.US_ASCII);
            final int length =
                    text.lines()
                            .filter(line -> line.startsWith("Content-Length: "))
                            .mapToInt(line -> Integer.parseInt(line.substring(16)))
                            .findFirst()
                            .orElse(0);
            return text + new String(in.readNBytes(length), StandardCharsets.UTF_8);
        }

        void awaitTheLastConnection() throws InterruptedException {
            worker.join(5_000);
        }

        List<String> requests() {
            return requests;
        }

        @Override
        public void close() throws IOException {
            server.close();
        }
    }
}
