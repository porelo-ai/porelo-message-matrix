package org.fuin.porelo.message.matrix;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/** An HTTP answer; upstream refusals retain their original wire bytes. */
final class GateResponse {
    private final int status;
    private final byte[] body;
    private final byte[] wire;
    private final String location;

    GateResponse(final int status, final JsonNode body) {
        this(status, body.toString().getBytes(StandardCharsets.UTF_8), new byte[0], "");
    }

    private GateResponse(
            final int status, final byte[] body, final byte[] wire, final String location) {
        this.status = status;
        this.body = body;
        this.wire = wire;
        this.location = location;
    }

    static GateResponse upstream(final Wire.Reply reply) {
        return new GateResponse(reply.statusCode(), reply.body(), reply.raw(), "");
    }

    static GateResponse error(final int status, final String code) {
        return new GateResponse(
                status,
                MatrixClient.JSON
                        .createObjectNode()
                        .put("errcode", code)
                        .put("error", "Request refused by the host's gate"));
    }

    static GateResponse redirect(final String location) {
        return new GateResponse(302, new byte[0], new byte[0], location);
    }

    int status() {
        return status;
    }

    JsonNode json() throws IOException {
        final JsonNode value = MatrixClient.JSON.readTree(body);
        if (value == null || !value.isObject()) {
            throw new IOException("homeserver answered no JSON object");
        }
        return value;
    }

    void write(final OutputStream out, final boolean close) throws IOException {
        if (wire.length != 0) {
            out.write(wire);
        } else {
            final String head =
                    "HTTP/1.1 "
                            + status
                            + " "
                            + reason(status)
                            + "\r\nContent-Type: application/json\r\nContent-Length: "
                            + body.length
                            + "\r\nCache-Control: no-store\r\n"
                            + (close ? "Connection: close\r\n" : "")
                            + (location.isEmpty() ? "" : "Location: " + location + "\r\n")
                            + "\r\n";
            out.write(head.getBytes(StandardCharsets.ISO_8859_1));
            out.write(body);
        }
        out.flush();
    }

    private static String reason(final int status) {
        return switch (status) {
            case 200 -> "OK";
            case 302 -> "Found";
            case 400 -> "Bad Request";
            case 401 -> "Unauthorized";
            case 403 -> "Forbidden";
            case 404 -> "Not Found";
            default -> "Bad Gateway";
        };
    }
}
