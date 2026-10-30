package org.fuin.porelo.message.matrix;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.Test;

class GateRequestTest {

    private static ByteArrayInputStream input(final String text) {
        return new ByteArrayInputStream(text.getBytes(StandardCharsets.ISO_8859_1));
    }

    /** Harm guarded: pipelined bytes are lost or interpreted as another request's body. */
    @Test
    void readsTwoRequestsWithoutConsumingTheNext() throws IOException {
        final var in =
                input(
                        "GET /_matrix/client/versions HTTP/1.1\r\nHost: localhost\r\n\r\nPOST /_matrix/client/v3/login HTTP/1.1\r\nHost: localhost\r\nContent-Length: 2\r\n\r\n{}");
        assertThat(Objects.requireNonNull(GateRequest.read(in)).path())
                .as("first request")
                .isEqualTo("/_matrix/client/versions");
        assertThat(Objects.requireNonNull(GateRequest.read(in)).body())
                .as("second body")
                .isEqualTo("{}".getBytes(StandardCharsets.US_ASCII));
        assertThat(GateRequest.read(in)).as("clean end").isNull();
    }

    /** Harm guarded: decoded chunk data bypasses inspection or framing changes on forwarding. */
    @Test
    void decodesChunksAndPreservesTheirWireBytes() throws IOException {
        final String wire =
                "POST /_matrix/client/v3/register HTTP/1.1\r\nHost: localhost\r\nTransfer-Encoding: chunked\r\n\r\n1\r\n{\r\n1\r\n}\r\n0\r\n\r\n";
        final GateRequest request = Objects.requireNonNull(GateRequest.read(input(wire)));
        assertThat(request.body())
                .as("body inspected by policy")
                .isEqualTo("{}".getBytes(StandardCharsets.US_ASCII));
        final var out = new ByteArrayOutputStream();
        request.forward(out);
        assertThat(out.toString(StandardCharsets.US_ASCII))
                .as("wire framing preserved with close")
                .isEqualTo(wire.replace("\r\n\r\n1", "\r\nConnection: close\r\n\r\n1"));
    }

    /** Harm guarded: request smuggling, unbounded allocation, or ambiguous identity. */
    @Test
    void refusesMalformedOrAmbiguouslyFramedRequests() {
        for (final String wire :
                List.of(
                        "GET / HTTP/1.0\r\nHost: localhost\r\n\r\n",
                        "GET / HTTP/1.1\nHost: localhost\n\n",
                        "GET http://localhost/ HTTP/1.1\r\nHost: localhost\r\n\r\n",
                        "GET / HTTP/1.1\r\n\r\n",
                        "GET / HTTP/1.1\r\nHost: a\r\nHost: b\r\n\r\n",
                        "POST / HTTP/1.1\r\nHost: a\r\nContent-Length: 1\r\nContent-Length: 1\r\n\r\nx",
                        "POST / HTTP/1.1\r\nHost: a\r\nContent-Length: 1\r\nTransfer-Encoding: chunked\r\n\r\n0\r\n\r\n",
                        "POST / HTTP/1.1\r\nHost: a\r\nContent-Length: 67108865\r\n\r\n",
                        "POST / HTTP/1.1\r\nHost: a\r\nContent-Length: -1\r\n\r\n",
                        "POST / HTTP/1.1\r\nHost: a\r\nContent-Length: 2\r\n\r\nx",
                        "POST / HTTP/1.1\r\nHost: a\r\nTransfer-Encoding: chunked\r\n\r\n4000001\r\n",
                        "POST / HTTP/1.1\r\nHost: a\r\nTransfer-Encoding: gzip\r\n\r\n",
                        "GET / HTTP/1.1\r\nHost: a\r\n folded: x\r\n\r\n",
                        "GET /_matrix/client/%76ersions HTTP/1.1\r\nHost: a\r\n\r\n",
                        "GET /_matrix/client/v3/login%2E HTTP/1.1\r\nHost: a\r\n\r\n",
                        "GET /_matrix/client/v3/x%4 HTTP/1.1\r\nHost: a\r\n\r\n",
                        "GET / HTTP/1.1\r\nHost: a\r\nAuthorization: Bearer x\r\nAuthorization: Bearer y\r\n\r\n",
                        "GET /?access_token=x&access_token=y HTTP/1.1\r\nHost: a\r\n\r\n",
                        "GET /%252e%252e/ HTTP/1.1\r\nHost: a\r\n\r\n",
                        "GET /" + "a".repeat(8192) + " HTTP/1.1\r\nHost: a\r\n\r\n",
                        "GET / HTTP/1.1\r\nHost: a\r\nX: " + "a".repeat(8192) + "\r\n\r\n",
                        "GET / HTTP/1.1\r\nHost: a\r\n" + "X: a\r\n".repeat(100) + "\r\n")) {
            assertThatThrownBy(() -> GateRequest.read(input(wire)))
                    .as("malformed HTTP is refused")
                    .isInstanceOf(IOException.class);
        }
    }

    /**
     * Harm guarded: malformed chunk extensions reach a parser that frames the request differently.
     */
    @Test
    void chunkExtensionsHaveToBeWellFormed() throws IOException {
        for (final String extension :
                List.of(";", ";name=", ";name=\"unterminated", ";name=bad value", ";name=\u0000")) {
            final String wire =
                    "POST /_matrix/media/v3/upload HTTP/1.1\r\nHost: localhost\r\nTransfer-Encoding: chunked\r\n\r\n1"
                            + extension
                            + "\r\nx\r\n0\r\n\r\n";
            assertThatThrownBy(() -> GateRequest.read(input(wire)))
                    .as("ambiguous chunk extension is refused")
                    .isInstanceOf(IOException.class);
        }
        final var request =
                Objects.requireNonNull(
                        GateRequest.read(
                                input(
                                        "POST /_matrix/media/v3/upload HTTP/1.1\r\nHost: localhost\r\nTransfer-Encoding: chunked\r\n\r\n1;name=\"a;b\"\r\nx\r\n0\r\n\r\n")));
        assertThat(request.body()).as("valid quoted chunk extension").isEqualTo(new byte[] {'x'});
    }

    /**
     * Harm guarded: query credentials inject HTTP headers or appear in header-construction errors.
     */
    @Test
    void queryCredentialsMustBeValidBearerValues() {
        for (final String token : List.of("%0d%0aInjected%3Avalue", "%00", "a%20b", "")) {
            assertThatThrownBy(
                            () ->
                                    GateRequest.read(
                                            input(
                                                    "GET /_matrix/client/v3/sync?access_token="
                                                            + token
                                                            + " HTTP/1.1\r\nHost: localhost\r\n\r\n")))
                    .as("malformed credential is refused during request parsing")
                    .isInstanceOf(IOException.class);
        }
    }
}
