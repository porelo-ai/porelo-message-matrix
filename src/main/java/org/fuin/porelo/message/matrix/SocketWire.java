package org.fuin.porelo.message.matrix;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpTimeoutException;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.attribute.UserPrincipal;
import java.time.Duration;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import jdk.net.ExtendedSocketOptions;
import org.jspecify.annotations.Nullable;

/** A single HTTP/1.1 exchange on a Unix socket, bounded by connect and whole-request deadlines. */
final class SocketWire implements Wire {

    /** An answer larger than this is no homeserver's, and is refused before it fills the memory. */
    static final long MAX_BODY = 64L * 1024 * 1024;

    private final Path path;
    private final Duration connectTimeout;
    private final @Nullable UserPrincipal owner;

    SocketWire(final Path path, final Duration connectTimeout) {
        this(path, connectTimeout, null);
    }

    SocketWire(
            final Path path, final Duration connectTimeout, final @Nullable UserPrincipal owner) {
        this.path = path;
        this.connectTimeout = connectTimeout;
        this.owner = owner;
    }

    @Override
    public Reply send(final HttpRequest request) throws IOException, InterruptedException {
        final long deadline = System.nanoTime() + request.timeout().orElseThrow().toNanos();
        final byte[] body = body(request);
        try (SocketChannel socket = SocketChannel.open(StandardProtocolFamily.UNIX);
                Selector selector = Selector.open()) {
            socket.configureBlocking(false);
            final SelectionKey key = socket.register(selector, SelectionKey.OP_CONNECT);
            if (!socket.connect(UnixDomainSocketAddress.of(path))) {
                final long connectDeadline =
                        connectTimeout.toNanos() < deadline - System.nanoTime()
                                ? System.nanoTime() + connectTimeout.toNanos()
                                : deadline;
                while (!socket.finishConnect()) {
                    await(key, SelectionKey.OP_CONNECT, connectDeadline);
                }
            }
            if (owner != null
                    && !socket.getOption(ExtendedSocketOptions.SO_PEERCRED).user().equals(owner)) {
                throw new IOException("Unix listener is not owned by the homeserver account");
            }
            write(socket, key, ByteBuffer.wrap(head(request, body.length)), deadline);
            write(socket, key, ByteBuffer.wrap(body), deadline);
            final InputStream in =
                    new BufferedInputStream(
                            new InputStream() {
                                @Override
                                public int read() throws IOException {
                                    final byte[] one = new byte[1];
                                    return read(one, 0, 1) < 0 ? -1 : one[0] & 0xff;
                                }

                                @Override
                                public int read(
                                        final byte[] bytes, final int offset, final int length)
                                        throws IOException {
                                    if (length == 0) {
                                        return 0;
                                    }
                                    final ByteBuffer buffer =
                                            ByteBuffer.wrap(bytes, offset, length);
                                    int count;
                                    checkDeadline(deadline);
                                    while ((count = socket.read(buffer)) == 0) {
                                        await(key, SelectionKey.OP_READ, deadline);
                                    }
                                    return count;
                                }
                            });
            return read(in, request.method());
        } catch (final java.nio.channels.ClosedByInterruptException ex) {
            throw new InterruptedException("socket request interrupted");
        }
    }

    private static void checkDeadline(final long deadline) throws HttpTimeoutException {
        if (deadline - System.nanoTime() <= 0) {
            throw new HttpTimeoutException("socket request timed out");
        }
    }

    private static void await(final SelectionKey key, final int operation, final long deadline)
            throws IOException {
        key.interestOps(operation);
        final Selector selector = key.selector();
        while (true) {
            if (Thread.currentThread().isInterrupted()) {
                throw new java.nio.channels.ClosedByInterruptException();
            }
            final long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                if (operation == SelectionKey.OP_CONNECT) {
                    throw new HttpConnectTimeoutException("socket connect timed out");
                }
                throw new HttpTimeoutException("socket request timed out");
            }
            if (selector.select(Math.max(1, Duration.ofNanos(remaining).toMillis())) > 0) {
                selector.selectedKeys().clear();
                return;
            }
        }
    }

    private static void write(
            final SocketChannel socket,
            final SelectionKey key,
            final ByteBuffer bytes,
            final long deadline)
            throws IOException {
        while (bytes.hasRemaining()) {
            checkDeadline(deadline);
            if (socket.write(bytes) == 0) {
                await(key, SelectionKey.OP_WRITE, deadline);
            }
        }
    }

    private static byte[] head(final HttpRequest request, final int length) {
        final String target =
                request.uri().getRawPath()
                        + (request.uri().getRawQuery() == null
                                ? ""
                                : "?" + request.uri().getRawQuery());
        final StringBuilder head =
                new StringBuilder(request.method())
                        .append(' ')
                        .append(target)
                        .append(" HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n");
        request.headers()
                .map()
                .forEach(
                        (name, values) ->
                                values.forEach(
                                        value ->
                                                head.append(name)
                                                        .append(": ")
                                                        .append(value)
                                                        .append("\r\n")));
        // Every request that carries a body says how long it is, whatever its type.
        if (request.bodyPublisher().isPresent() && !"GET".equals(request.method())) {
            head.append("Content-Length: ").append(length).append("\r\n");
        }
        return head.append("\r\n").toString().getBytes(StandardCharsets.ISO_8859_1);
    }

    private static byte[] body(final HttpRequest request) throws IOException, InterruptedException {
        if (request.bodyPublisher().isEmpty()) {
            return new byte[0];
        }
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        final CompletableFuture<byte[]> result = new CompletableFuture<>();
        request.bodyPublisher()
                .orElseThrow()
                .subscribe(
                        new Flow.Subscriber<ByteBuffer>() {
                            @Override
                            public void onSubscribe(final Flow.Subscription subscription) {
                                subscription.request(Long.MAX_VALUE);
                            }

                            @Override
                            public void onNext(final ByteBuffer item) {
                                final byte[] part = new byte[item.remaining()];
                                item.get(part);
                                bytes.writeBytes(part);
                            }

                            @Override
                            public void onError(final Throwable failure) {
                                result.completeExceptionally(failure);
                            }

                            @Override
                            public void onComplete() {
                                result.complete(bytes.toByteArray());
                            }
                        });
        try {
            return result.get();
        } catch (final ExecutionException ex) {
            throw new IOException("cannot write the request body", ex);
        }
    }

    static Reply read(final InputStream in, final String method) throws IOException {
        final ByteArrayOutputStream raw = new ByteArrayOutputStream();
        final InputStream recording =
                new java.io.FilterInputStream(in) {
                    @Override
                    public int read() throws IOException {
                        final int value = in.read();
                        if (value >= 0) {
                            raw.write(value);
                        }
                        return value;
                    }

                    @Override
                    public int read(final byte[] bytes, final int offset, final int length)
                            throws IOException {
                        final int count = in.read(bytes, offset, length);
                        if (count > 0) {
                            raw.write(bytes, offset, count);
                        }
                        return count;
                    }
                };
        final Reply parsed = readParsed(recording, method);
        return new Reply(parsed.statusCode(), parsed.body(), raw.toByteArray());
    }

    private static Reply readParsed(final InputStream in, final String method) throws IOException {
        int status;
        Map<String, String> headers;
        do {
            final String statusLine = line(in);
            final String[] parts = statusLine.split(" ", 3);
            if (parts.length < 2
                    || !("HTTP/1.1".equals(parts[0]) || "HTTP/1.0".equals(parts[0]))
                    || !parts[1].matches("[1-5][0-9]{2}")) {
                throw new IOException("invalid HTTP status line");
            }
            status = Integer.parseInt(parts[1]);
            headers = headers(in);
        } while (status >= 100 && status < 200 && status != 101);
        if ("HEAD".equals(method) || status == 101 || status == 204 || status == 304) {
            return new Reply(status, new byte[0]);
        }
        final ByteArrayOutputStream body = new ByteArrayOutputStream();
        final String transfer = headers.get("Transfer-Encoding");
        if (transfer != null) {
            if (!"chunked".equalsIgnoreCase(transfer)) {
                throw new IOException("unsupported HTTP transfer encoding");
            }
            while (true) {
                final String size = line(in).split(";", 2)[0];
                final long length = number(size, 16);
                if (length == 0) {
                    headers(in);
                    break;
                }
                copy(in, body, length);
                if (!line(in).isEmpty()) {
                    throw new IOException("invalid HTTP chunk ending");
                }
            }
        } else if (headers.containsKey("Content-Length")) {
            copy(in, body, number(headers.get("Content-Length"), 10));
        } else {
            copy(in, body, MAX_BODY + 1, true);
        }
        return new Reply(status, body.toByteArray());
    }

    private static long number(final String text, final int radix) throws IOException {
        if (text.isEmpty() || !text.chars().allMatch(c -> Character.digit(c, radix) >= 0)) {
            throw new IOException("invalid HTTP body length");
        }
        try {
            return Long.parseLong(text, radix);
        } catch (final NumberFormatException ex) {
            throw new IOException("invalid HTTP body length", ex);
        }
    }

    private static Map<String, String> headers(final InputStream in) throws IOException {
        final Map<String, String> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (int count = 0; count < 1000; count++) {
            final String line = line(in);
            if (line.isEmpty()) {
                return headers;
            }
            final int colon = line.indexOf(':');
            if (colon <= 0) {
                throw new IOException("invalid HTTP header");
            }
            final String name = line.substring(0, colon);
            final String value = line.substring(colon + 1).trim();
            headers.merge(name, value, (first, next) -> first + ", " + next);
        }
        throw new IOException("too many HTTP headers");
    }

    private static String line(final InputStream in) throws IOException {
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        while (bytes.size() < 65536) {
            final int b = in.read();
            if (b < 0) {
                throw new EOFException("HTTP response ended before its framing");
            }
            if (b == '\r') {
                if (in.read() != '\n') {
                    throw new IOException("invalid HTTP line ending");
                }
                return bytes.toString(StandardCharsets.ISO_8859_1);
            }
            bytes.write(b);
        }
        throw new IOException("HTTP line is too long");
    }

    private static void copy(
            final InputStream in, final ByteArrayOutputStream out, final long length)
            throws IOException {
        copy(in, out, length, false);
    }

    /** Reads {@code length} bytes, or up to the end when {@code toEnd}, never past the limit. */
    private static void copy(
            final InputStream in,
            final ByteArrayOutputStream out,
            final long length,
            final boolean toEnd)
            throws IOException {
        if (out.size() + Math.min(length, MAX_BODY + 1) > MAX_BODY && !toEnd) {
            throw new IOException("HTTP body is larger than " + MAX_BODY + " bytes");
        }
        final byte[] buffer = new byte[8192];
        long remaining = length;
        while (remaining > 0) {
            final int count = in.read(buffer, 0, (int) Math.min(buffer.length, remaining));
            if (count < 0) {
                if (toEnd) {
                    return;
                }
                throw new EOFException("HTTP response ended before its body");
            }
            out.write(buffer, 0, count);
            remaining -= count;
            if (out.size() > MAX_BODY) {
                throw new IOException("HTTP body is larger than " + MAX_BODY + " bytes");
            }
        }
    }
}
