package org.fuin.porelo.message.matrix;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.SequenceInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Harm guarded: incorrect HTTP framing truncates answers or accepts a partial response as complete.
 */
class SocketWireTest {

    private static Wire.Reply read(final String response) throws IOException {
        return SocketWire.read(
                new ByteArrayInputStream(response.getBytes(StandardCharsets.UTF_8)), "GET");
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "HTTP/1.1 200 OK\r\ncOnTeNt-LeNgTh: 11\r\n\r\n{\"ok\":true}ignored",
                "HTTP/1.1 200 OK\r\nTransfer-Encoding: Chunked\r\n\r\n5;ext=yes\r\n{\"ok\"\r\n6\r\n:true}\r\n0\r\nX-Trailer: done\r\n\r\n",
                "HTTP/1.0 200 OK\r\n\r\n{\"ok\":true}",
                "HTTP/1.1 100 Continue\r\n\r\nHTTP/1.1 200 OK\r\nContent-Length: 11\r\n\r\n{\"ok\":true}"
            })
    void responseFramingReturnsExactlyTheBody(final String response) throws IOException {
        final Wire.Reply reply = read(response);
        assertThat(reply.statusCode()).as("the final response's status").isEqualTo(200);
        assertThat(new String(reply.body(), StandardCharsets.UTF_8))
                .as("neither framing nor bytes beyond the body enter the answer")
                .isEqualTo("{\"ok\":true}");
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "HTTP/1.1 200 OK\r\nContent-Length: 12\r\n\r\n{\"ok\":true}",
                "HTTP/1.1 200 OK\r\nContent-Length: -1\r\n\r\n",
                "HTTP/1.1 200 OK\r\nContent-Length: 9999999999999999999999999\r\n\r\n",
                "HTTP/1.1 200 OK\r\nContent-Length: 67108865\r\n\r\n{}",
                "HTTP/1.1 200 OK\r\nContent-Length: 2\r\nContent-Length: 3\r\n\r\n{}",
                "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n2\r\n{}XX0\r\n\r\n",
                "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\nx\r\n",
                "HTTP/1.1 200 OK\r\nTransfer-Encoding: gzip\r\n\r\n",
                "HTTP/1.1 nope\r\n\r\n{}",
                "HTTP/1.1 200 OK\r\nnot-a-header\r\n\r\n{}"
            })
    void malformedOrTruncatedResponsesFail(final String response) {
        assertThatThrownBy(() -> read(response))
                .as("invalid framing cannot become a successful answer")
                .isInstanceOf(IOException.class);
    }

    @Test
    void noContentNeedsNoBody() throws IOException {
        assertThat(read("HTTP/1.1 204 No Content\r\n\r\n").body())
                .as("a bodyless answer ends at the headers")
                .isEmpty();
    }

    @Test
    void anAnswerWithoutLengthStopsAtTheLimit() {
        // Harm guarded: a peer streaming without end fills the transport's memory.
        final byte[] head = "HTTP/1.1 200 OK\r\n\r\n".getBytes(StandardCharsets.UTF_8);
        final InputStream endless =
                new SequenceInputStream(
                        new ByteArrayInputStream(head),
                        new InputStream() {
                            @Override
                            public int read() {
                                return 'x';
                            }

                            @Override
                            public int read(final byte[] b, final int off, final int len) {
                                Arrays.fill(b, off, off + len, (byte) 'x');
                                return len;
                            }
                        });
        assertThatThrownBy(() -> SocketWire.read(endless, "GET"))
                .as("an answer past the limit is refused, not kept")
                .isInstanceOf(IOException.class)
                .hasMessageContaining("larger than");
    }
}
