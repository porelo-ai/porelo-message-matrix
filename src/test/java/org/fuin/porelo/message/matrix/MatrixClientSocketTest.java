package org.fuin.porelo.message.matrix;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpTimeoutException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/** Harm guarded: malformed socket HTTP loses messages or tokens, or leaves the caller hung. */
@Timeout(10)
class MatrixClientSocketTest {

    @TempDir private Path dir;

    private MatrixClient client(final String token) throws Failure {
        return new MatrixClient(
                new Config(URI.create("unix:" + dir.resolve("hs.sock")), token, null, true),
                List.of(dir.resolve("unreadable-tcp-table")));
    }

    @Test
    void getSendsTokenAndReadsContentLength() throws Exception {
        try (FakeSocketHomeserver server =
                new FakeSocketHomeserver(
                        dir.resolve("hs.sock"),
                        "HTTP/1.1 200 OK\r\ncOnTeNt-LeNgTh: 11\r\n\r\n{\"ok\":true}")) {
            assertThat(client("syt_token").get("/_matrix/test?q=%2F").path("ok").asBoolean())
                    .as("the socket's JSON answer")
                    .isTrue();
            assertThat(server.request())
                    .as("one authenticated HTTP request without a body")
                    .startsWith("GET /_matrix/test?q=%2F HTTP/1.1\r\n")
                    .contains(
                            "Host: localhost\r\n",
                            "Authorization: Bearer syt_token\r\n",
                            "Connection: close\r\n")
                    .doesNotContain("Content-Length:", "Content-Type:");
        }
    }

    @Test
    void postSendsUtf8BodyWithoutAToken() throws Exception {
        try (FakeSocketHomeserver server =
                new FakeSocketHomeserver(
                        dir.resolve("hs.sock"),
                        "HTTP/1.1 200 OK\r\nConnection: close\r\n\r\n{\"ok\":true}")) {
            final var body = MatrixClient.JSON.createObjectNode().put("text", "café");
            assertThat(client("").post("/_matrix/login", body).path("ok").asBoolean())
                    .as("a response delimited by close")
                    .isTrue();
            assertThat(server.request())
                    .as("UTF-8 length and body, with no credential before login")
                    .startsWith("POST /_matrix/login HTTP/1.1\r\n")
                    .contains(
                            "Content-Type: application/json\r\n",
                            "Content-Length: "
                                    + MatrixClient.JSON.writeValueAsBytes(body).length
                                    + "\r\n")
                    .endsWith(MatrixClient.JSON.writeValueAsString(body))
                    .doesNotContain("Authorization:");
        }
    }

    @Test
    void chunkedAnswerIsDecoded() throws Exception {
        try (FakeSocketHomeserver server =
                new FakeSocketHomeserver(
                        dir.resolve("hs.sock"),
                        "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n5;ext=yes\r\n{\"ok\"\r\n6\r\n:true}\r\n0\r\nX-Trailer: done\r\n\r\n")) {
            assertThat(client("").get("/_matrix/test").path("ok").asBoolean())
                    .as("chunk framing and trailers do not enter the JSON")
                    .isTrue();
            assertThat(server.request()).as("the request reached the socket").startsWith("GET ");
        }
    }

    @Test
    void stalledBodyEndsAtTheRequestDeadline() throws Exception {
        try (FakeSocketHomeserver server =
                new FakeSocketHomeserver(
                        dir.resolve("hs.sock"),
                        "HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\n{",
                        true)) {
            final SocketWire wire = new SocketWire(dir.resolve("hs.sock"), Duration.ofSeconds(10));
            final HttpRequest request =
                    HttpRequest.newBuilder(URI.create("http://localhost/_matrix/test"))
                            .timeout(Duration.ofMillis(100))
                            .GET()
                            .build();
            assertThatThrownBy(() -> wire.send(request))
                    .as("a peer that withholds the end of a body cannot hang a verb")
                    .isInstanceOf(HttpTimeoutException.class);
            assertThat(server.request())
                    .as("the socket accepted the request before it stalled")
                    .startsWith("GET ");
        }
    }

    @Test
    void unreachableSocketIsTemporary() throws Failure {
        final MatrixClient client = client("t");
        assertThatThrownBy(() -> client.get("/_matrix/test"))
                .as("an unreachable socket can be retried like TCP")
                .isInstanceOfSatisfying(
                        Failure.class,
                        failure -> {
                            assertThat(failure.exitCode())
                                    .as("temporary failure")
                                    .isEqualTo(Exit.TEMPORARY);
                            assertThat(failure)
                                    .as("the existing unreachable message")
                                    .hasMessageContaining("not reachable");
                        });
    }
}
