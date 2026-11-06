package org.fuin.porelo.message.matrix;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.SequenceInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

class GateRequestTest {

    /** Harm guarded: invalid UTF-8 query fields acquire a different identity after decoding. */
    @ParameterizedTest
    @ValueSource(strings = {"%FFname=value", "name=%FFvalue"})
    void refusesQueriesBeginningWithAReplacementCharacter(String query) {
        assertThatThrownBy(() -> GateRequest.query(query))
                .as("invalid UTF-8 at the start of a query key or value is refused")
                .isInstanceOf(IOException.class);
    }

    /** Harm guarded: valid chunk extensions cause uploads to fail or consume the next request. */
    @ParameterizedTest(name = "extension {0}")
    @ValueSource(strings = {";flag", ";name=\" \"", ";z=x", ";A=x", ";Z=x", ";0=x", ";9=x", ";!=x"})
    void acceptsChunkExtensionBoundaryCharacters(String extension) throws IOException {
        final String wire =
                "POST / HTTP/1.1\r\nHost: localhost\r\nTransfer-Encoding: chunked\r\n\r\n1"
                        + extension
                        + "\r\nx\r\n0\r\n\r\n";
        final var in = input(wire + "GET /next HTTP/1.1\r\nHost: localhost\r\n\r\n");
        final GateRequest request = Objects.requireNonNull(GateRequest.read(in));
        assertThat(request.body())
                .as("extension leaves the decoded upload intact")
                .containsExactly((byte) 'x');
        final var out = new ByteArrayOutputStream();
        request.forward(out);
        assertThat(out.toString(StandardCharsets.ISO_8859_1))
                .as("extension framing is forwarded intact")
                .isEqualTo(wire.replace("\r\n\r\n1", "\r\nConnection: close\r\n\r\n1"));
        assertThat(Objects.requireNonNull(GateRequest.read(in)).path())
                .as("extension parsing preserves the next request")
                .isEqualTo("/next");
    }

    /** Harm guarded: buffered forwarding stalls while waiting for an upstream response. */
    @Test
    void forwardingDeliversBufferedBytesBeforeReturning() throws IOException {
        final GateRequest request =
                Objects.requireNonNull(
                        GateRequest.read(
                                input(
                                        "POST / HTTP/1.1\r\nHost: localhost\r\nContent-Length: 1\r\n\r\nx")));
        final var delivered = new ByteArrayOutputStream();
        final var buffered = new BufferedOutputStream(delivered);
        request.forward(buffered);
        assertThat(delivered.toString(StandardCharsets.US_ASCII))
                .as("complete request reaches upstream before waiting for its response")
                .isEqualTo(
                        "POST / HTTP/1.1\r\nHost: localhost\r\nContent-Length: 1\r\nConnection: close\r\n\r\nx");
    }

    /** Harm guarded: an encoded hyphen disguises a route name from the gate's policy. */
    @Test
    void refusesAnEncodedHyphenInThePath() {
        assertThatThrownBy(
                        () ->
                                GateRequest.read(
                                        input(
                                                "GET /_matrix/client/v3/a%2Db HTTP/1.1\r\nHost: localhost\r\n\r\n")))
                .as("unreserved hyphen must use the same spelling at gate and homeserver")
                .isInstanceOf(IOException.class);
    }

    /** Harm guarded: ambiguous HTTP framing bypasses the gate's inspection. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("requestRefusals")
    void refusesRequestsAtTheRemainingSyntaxEdges(String reason, String wire) {
        assertThatThrownBy(
                        () ->
                                GateRequest.read(
                                        new ByteArrayInputStream(
                                                wire.getBytes(StandardCharsets.UTF_8))))
                .as(reason)
                .isInstanceOf(IOException.class);
    }

    private static Stream<Arguments> requestRefusals() {
        final String start = "POST / HTTP/1.1\r\nHost: localhost\r\n";
        return Stream.of(
                Arguments.of("unsupported Connection", start + "Connection: upgrade\r\n\r\n"),
                Arguments.of(
                        "hex digit in decimal length",
                        start + "Content-Length: A\r\n\r\n" + "x".repeat(10)),
                Arguments.of(
                        "non-hex chunk size",
                        start
                                + "Transfer-Encoding: chunked\r\n\r\nG\r\n"
                                + "x".repeat(16)
                                + "\r\n0\r\n\r\n"),
                Arguments.of("empty header name", start + ": value\r\n\r\n"),
                Arguments.of(
                        "replacement character in header name", start + "X-\ufffd: value\r\n\r\n"),
                Arguments.of("control character in header", start + "X: a\u0001b\r\n\r\n"),
                Arguments.of(
                        "cut-off percent escape", "GET /x% HTTP/1.1\r\nHost: localhost\r\n\r\n"));
    }

    /** Harm guarded: a valid upload is rejected or an oversized upload exhausts gate memory. */
    @ParameterizedTest(name = "chunked={0}, over limit={1}")
    @MethodSource("bodyLimits")
    void enforcesTheBodyLimitForBothFramings(boolean chunked, boolean oversized)
            throws IOException {
        final int limit = 64 * 1024 * 1024;
        final int size = limit + (oversized ? 1 : 0);
        final String framing =
                chunked
                        ? "Transfer-Encoding: chunked\r\n\r\n" + Integer.toHexString(size) + "\r\n"
                        : "Content-Length: " + size + "\r\n\r\n";
        try (final InputStream wire =
                new SequenceInputStream(
                        new SequenceInputStream(
                                input("POST / HTTP/1.1\r\nHost: localhost\r\n" + framing),
                                repeatedBody(size)),
                        input(
                                (chunked ? "\r\n0\r\n\r\n" : "")
                                        + "GET /next HTTP/1.1\r\nHost: localhost\r\n\r\n"))) {
            if (oversized) {
                assertThatThrownBy(() -> GateRequest.read(wire))
                        .as("over-limit body is refused for chunked=%s", chunked)
                        .isInstanceOf(IOException.class);
            } else {
                final GateRequest request = Objects.requireNonNull(GateRequest.read(wire));
                assertThat(request.body())
                        .as("all bytes at the inclusive limit for chunked=%s", chunked)
                        .hasSize(limit)
                        .containsOnly((byte) 'x');
                assertThat(Objects.requireNonNull(GateRequest.read(wire)).path())
                        .as("body framing leaves the following request intact")
                        .isEqualTo("/next");
            }
        }
    }

    private static Stream<Arguments> bodyLimits() {
        return Stream.of(
                Arguments.of(false, false),
                Arguments.of(false, true),
                Arguments.of(true, false),
                Arguments.of(true, true));
    }

    private static InputStream repeatedBody(int size) {
        return new InputStream() {
            private int remaining = size;

            @Override
            public int read() {
                return remaining-- > 0 ? 'x' : -1;
            }

            @Override
            public int read(byte[] buffer, int offset, int length) {
                if (length == 0) {
                    return 0;
                }
                if (remaining <= 0) {
                    return -1;
                }
                final int count = Math.min(length, remaining);
                Arrays.fill(buffer, offset, offset + count, (byte) 'x');
                remaining -= count;
                return count;
            }
        };
    }

    /** Harm guarded: connection lifetime diverges from the client's request. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("connectionOptions")
    void interpretsTheConnectionLifetime(String connection, boolean expected) throws IOException {
        final GateRequest request =
                Objects.requireNonNull(
                        GateRequest.read(
                                input(
                                        "GET / HTTP/1.1\r\nHost: localhost\r\n"
                                                + connection
                                                + "\r\n")));
        assertThat(request.close()).as("requested lifetime for %s", connection).isEqualTo(expected);
    }

    private static Stream<Arguments> connectionOptions() {
        return Stream.of(
                Arguments.of("Connection: Close\r\n", true),
                Arguments.of("Connection: keep-alive\r\n", false));
    }

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
                        "GET / HTTP/1.1\r\nHost: a\r\nOrigin: vector://vector\r\nOrigin: https://x.example\r\n\r\n",
                        "GET / HTTP/1.1\r\nHost: a\r\nSec-Fetch-Site: none\r\nSec-Fetch-Site: cross-site\r\n\r\n",
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

    /** Harm guarded: a chunk size read as decimal cuts a body differently from the homeserver. */
    @Test
    void aChunkSizeInLettersIsHexadecimal() throws IOException {
        final GateRequest request =
                Objects.requireNonNull(
                        GateRequest.read(
                                input(
                                        "POST / HTTP/1.1\r\nHost: localhost\r\n"
                                                + "Transfer-Encoding: chunked\r\n\r\n"
                                                + "a\r\n0123456789\r\n0\r\n\r\n")));

        assertThat(request.body()).as("ten bytes").hasSize(10);
    }
}
