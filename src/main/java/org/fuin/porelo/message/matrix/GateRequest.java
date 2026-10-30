package org.fuin.porelo.message.matrix;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/** A bounded HTTP/1.1 request, with the body kept in its original wire framing. */
final class GateRequest {
    static final int LINE_LIMIT = 8192;
    static final int BODY_LIMIT = 64 * 1024 * 1024;
    private final String method;
    private final String target;
    private final String path;
    private final List<String> headers;
    private final Map<String, String> fields;
    private final Map<String, String> query;
    private final byte[] body;
    private final byte[] wireBody;

    private GateRequest(
            final String line,
            final List<String> headers,
            final Map<String, String> fields,
            final byte[] body,
            final byte[] wireBody)
            throws IOException {
        final String[] parts = line.split(" ", -1);
        if (parts.length != 3 || !parts[0].matches("[A-Z]+") || !parts[2].equals("HTTP/1.1")) {
            throw malformed();
        }
        method = parts[0];
        target = parts[1];
        try {
            final URI uri = new URI(target);
            if (!target.startsWith("/")
                    || target.startsWith("//")
                    || uri.getRawAuthority() != null
                    || uri.getRawFragment() != null
                    || target.chars().anyMatch(c -> c <= 32 || c >= 127)) {
                throw malformed();
            }
            path = uri.getPath();
            if (path == null
                    || path.contains("%")
                    || path.contains("\\")
                    || path.contains("//")
                    || path.indexOf('\ufffd') >= 0
                    || path.chars().anyMatch(c -> c < 32 || c == 127)
                    || Arrays.stream(path.split("/", -1))
                            .anyMatch(p -> p.equals(".") || p.equals(".."))
                    || uri.getRawPath().toLowerCase(Locale.ROOT).contains("%2f")
                    || encodesAnUnreservedCharacter(uri.getRawPath())) {
                throw malformed();
            }
            query = query(uri.getRawQuery() == null ? "" : uri.getRawQuery());
        } catch (final URISyntaxException ex) {
            throw malformed();
        }
        this.headers = List.copyOf(headers);
        this.fields = Map.copyOf(fields);
        this.body = body;
        this.wireBody = wireBody;
        final String host = fields.get("host");
        if (host == null || !validHost(host)) {
            throw malformed();
        }
        final String authorization = fields.get("authorization");
        if (authorization != null
                && (!authorization.matches("(?i:Bearer) [A-Za-z0-9._~+/-]+=*")
                        || query.containsKey("access_token"))) {
            throw malformed();
        }
        if (query.containsKey("access_token")
                && !query.get("access_token").matches("[A-Za-z0-9._~+/-]+=*")) {
            throw malformed();
        }
    }

    private static boolean validHost(final String host) {
        try {
            final URI origin = new URI("http://" + host);
            return origin.getHost() != null
                    && origin.getUserInfo() == null
                    && origin.getRawPath().isEmpty()
                    && origin.getRawQuery() == null
                    && origin.getRawFragment() == null
                    && origin.getPort() <= 65535;
        } catch (final URISyntaxException ex) {
            return false;
        }
    }

    static @Nullable GateRequest read(final InputStream in) throws IOException {
        final String line = line(in, true);
        if (line == null) {
            return null;
        }
        final List<String> headers = new ArrayList<>();
        final Map<String, String> fields = new LinkedHashMap<>();
        for (; ; ) {
            final String header = requiredLine(in);
            if (header.isEmpty()) {
                break;
            }
            if (headers.size() == 100) {
                throw malformed();
            }
            final int colon = header.indexOf(':');
            if (colon <= 0
                    || !header.substring(0, colon).matches("[!#$%&'*+.^_`|~0-9A-Za-z-]+")
                    || header.chars().anyMatch(c -> c < 32 && c != '\t' || c == 127)) {
                throw malformed();
            }
            final String name = header.substring(0, colon).toLowerCase(Locale.ROOT);
            final String value = header.substring(colon + 1).strip();
            if (fields.containsKey(name)
                    && List.of(
                                    "host",
                                    "content-length",
                                    "transfer-encoding",
                                    "authorization",
                                    "connection")
                            .contains(name)) {
                throw malformed();
            }
            headers.add(header);
            fields.put(name, value);
        }
        if (fields.containsKey("expect")
                || fields.containsKey("upgrade")
                || fields.containsKey("trailer")
                || fields.containsKey("content-length")
                        && fields.containsKey("transfer-encoding")) {
            throw malformed();
        }
        final String connection = fields.getOrDefault("connection", "keep-alive");
        if (!connection.equalsIgnoreCase("close") && !connection.equalsIgnoreCase("keep-alive")) {
            throw malformed();
        }
        final ByteArrayOutputStream body = new ByteArrayOutputStream();
        final ByteArrayOutputStream wire = new ByteArrayOutputStream();
        if (fields.containsKey("transfer-encoding")) {
            if (!fields.get("transfer-encoding").equalsIgnoreCase("chunked")) {
                throw malformed();
            }
            for (; ; ) {
                final String chunk = requiredLine(in);
                writeLine(wire, chunk);
                final long size = chunkSize(chunk);
                if (size > BODY_LIMIT - body.size() || wire.size() > 2 * BODY_LIMIT) {
                    throw malformed();
                }
                if (size == 0) {
                    if (!requiredLine(in).isEmpty()) {
                        throw malformed();
                    }
                    writeLine(wire, "");
                    break;
                }
                copy(in, body, wire, (int) size);
                if (!requiredLine(in).isEmpty()) {
                    throw malformed();
                }
                writeLine(wire, "");
            }
        } else if (fields.containsKey("content-length")) {
            final long size = number(fields.get("content-length"), 10);
            if (size > BODY_LIMIT) {
                throw malformed();
            }
            copy(in, body, wire, (int) size);
        }
        return new GateRequest(line, headers, fields, body.toByteArray(), wire.toByteArray());
    }

    static Map<String, String> query(final String raw) throws IOException {
        final Map<String, String> result = new LinkedHashMap<>();
        if (raw.isEmpty()) {
            return result;
        }
        for (final String pair : raw.split("&", -1)) {
            final String[] parts = pair.split("=", 2);
            try {
                final String key = URLDecoder.decode(parts[0], StandardCharsets.UTF_8);
                final String value =
                        URLDecoder.decode(
                                parts.length == 2 ? parts[1] : "", StandardCharsets.UTF_8);
                if (key.indexOf('\ufffd') >= 0
                        || value.indexOf('\ufffd') >= 0
                        || result.putIfAbsent(key, value) != null) {
                    throw malformed();
                }
            } catch (final IllegalArgumentException ex) {
                throw malformed();
            }
        }
        return result;
    }

    private static long number(final String value, final int radix) throws IOException {
        if (!value.matches(radix == 16 ? "[0-9a-fA-F]+" : "[0-9]+")) {
            throw malformed();
        }
        try {
            return Long.parseLong(value, radix);
        } catch (final NumberFormatException ex) {
            throw malformed();
        }
    }

    private static long chunkSize(final String line) throws IOException {
        final int separator = line.indexOf(';');
        if (separator < 0) {
            return number(line, 16);
        }
        final long size = number(line.substring(0, separator), 16);
        int at = separator;
        while (at < line.length()) {
            if (line.charAt(at++) != ';') {
                throw malformed();
            }
            at = whitespace(line, at);
            final int start = at;
            while (at < line.length() && tokenCharacter(line.charAt(at))) {
                at++;
            }
            if (at == start) {
                throw malformed();
            }
            at = whitespace(line, at);
            if (at < line.length() && line.charAt(at) == '=') {
                at = whitespace(line, at + 1);
                if (at < line.length() && line.charAt(at) == '"') {
                    at = quotedExtension(line, at + 1);
                } else {
                    final int value = at;
                    while (at < line.length() && tokenCharacter(line.charAt(at))) {
                        at++;
                    }
                    if (at == value) {
                        throw malformed();
                    }
                }
                at = whitespace(line, at);
            }
        }
        return size;
    }

    private static int quotedExtension(final String line, final int start) throws IOException {
        int at = start;
        while (at < line.length()) {
            char c = line.charAt(at++);
            if (c == '"') {
                return at;
            }
            if (c == '\\') {
                if (at == line.length()) {
                    throw malformed();
                }
                c = line.charAt(at++);
            }
            if (c < 32 && c != '\t' || c == 127) {
                throw malformed();
            }
        }
        throw malformed();
    }

    private static int whitespace(final String line, final int start) {
        int at = start;
        while (at < line.length() && (line.charAt(at) == ' ' || line.charAt(at) == '\t')) {
            at++;
        }
        return at;
    }

    private static boolean tokenCharacter(final char c) {
        return c >= 'a' && c <= 'z'
                || c >= 'A' && c <= 'Z'
                || c >= '0' && c <= '9'
                || "!#$%&'*+-.^_`|~".indexOf(c) >= 0;
    }

    static String requiredLine(final InputStream in) throws IOException {
        final String line = line(in, false);
        if (line == null) {
            throw malformed();
        }
        return line;
    }

    private static @Nullable String line(final InputStream in, final boolean cleanEnd)
            throws IOException {
        final ByteArrayOutputStream line = new ByteArrayOutputStream();
        for (; ; ) {
            final int b = in.read();
            if (b < 0) {
                if (line.size() == 0 && cleanEnd) {
                    return null;
                }
                throw new EOFException("incomplete HTTP framing");
            }
            if (b == '\r') {
                if (in.read() != '\n') {
                    throw malformed();
                }
                return line.toString(StandardCharsets.ISO_8859_1);
            }
            if (b == '\n' || line.size() == LINE_LIMIT) {
                throw malformed();
            }
            line.write(b);
        }
    }

    private static void copy(
            final InputStream in,
            final ByteArrayOutputStream body,
            final ByteArrayOutputStream wire,
            final int size)
            throws IOException {
        final byte[] buffer = new byte[8192];
        int remaining = size;
        while (remaining > 0) {
            final int count = in.read(buffer, 0, Math.min(buffer.length, remaining));
            if (count < 0) {
                throw malformed();
            }
            body.write(buffer, 0, count);
            wire.write(buffer, 0, count);
            remaining -= count;
        }
    }

    private static IOException malformed() {
        return new IOException("malformed or oversized HTTP request");
    }

    private static void writeLine(final OutputStream out, final String line) throws IOException {
        out.write((line + "\r\n").getBytes(StandardCharsets.ISO_8859_1));
    }

    String method() {
        return method;
    }

    String path() {
        return path;
    }

    String host() {
        return fields.getOrDefault("host", "");
    }

    String parameter(final String name) {
        return query.getOrDefault(name, "");
    }

    byte[] body() {
        return body;
    }

    boolean close() {
        return fields.getOrDefault("connection", "").equalsIgnoreCase("close");
    }

    String token() {
        final String header = fields.get("authorization");
        return header == null ? query.getOrDefault("access_token", "") : header.substring(7);
    }

    void forward(final OutputStream out) throws IOException {
        writeLine(out, method + " " + target + " HTTP/1.1");
        for (final String header : headers) {
            if (!header.substring(0, header.indexOf(':')).equalsIgnoreCase("connection")) {
                writeLine(out, header);
            }
        }
        writeLine(out, "Connection: close");
        writeLine(out, "");
        out.write(wireBody);
        out.flush();
    }

    /**
     * A letter, digit or {@code - . _ ~} written as {@code %XX}: the gate decides on the decoded
     * path and the homeserver routes the written one, so the two must not tell apart a name.
     */
    static boolean encodesAnUnreservedCharacter(final String rawPath) {
        for (int i = rawPath.indexOf('%'); i >= 0; i = rawPath.indexOf('%', i + 1)) {
            if (i + 2 >= rawPath.length()) {
                return true;
            }
            final int c;
            try {
                c = Integer.parseInt(rawPath.substring(i + 1, i + 3), 16);
            } catch (final NumberFormatException ex) {
                return true;
            }
            if (Character.isLetterOrDigit(c) && c < 128 || "-._~".indexOf(c) >= 0) {
                return true;
            }
        }
        return false;
    }
}
