package org.fuin.porelo.message.matrix;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class GateResponseTest {
    /**
     * Harm guarded: an upstream authentication refusal loses its framing or becomes a different
     * answer.
     */
    @Test
    void whoamiRefusalsRetainEveryUpstreamByte() throws Exception {
        final String wire =
                "HTTP/1.1 401 Unauthorized\r\nTransfer-Encoding: chunked\r\nX-Reason: original\r\n\r\n2\r\n{}\r\n0\r\n\r\n";
        final Wire.Reply reply =
                SocketWire.read(
                        new ByteArrayInputStream(wire.getBytes(StandardCharsets.US_ASCII)), "GET");
        final var out = new ByteArrayOutputStream();
        GateResponse.upstream(reply).write(out, false);
        assertThat(out.toString(StandardCharsets.US_ASCII))
                .as("the entire refusal survives unchanged")
                .isEqualTo(wire);
    }
}
