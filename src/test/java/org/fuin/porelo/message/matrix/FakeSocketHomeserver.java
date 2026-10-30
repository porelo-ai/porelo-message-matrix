package org.fuin.porelo.message.matrix;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** One HTTP exchange on a Unix socket, recording the bytes the client actually sends. */
final class FakeSocketHomeserver implements AutoCloseable {

    private final Path path;
    private final ServerSocketChannel server;
    private final CompletableFuture<String> request = new CompletableFuture<>();
    private final Thread worker;
    private final CountDownLatch release = new CountDownLatch(1);

    FakeSocketHomeserver(final Path path, final String response) throws IOException {
        this(path, response, false);
    }

    FakeSocketHomeserver(final Path path, final String response, final boolean keepOpen)
            throws IOException {
        this.path = path;
        server = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
        try {
            server.bind(UnixDomainSocketAddress.of(path));
        } catch (final IOException ex) {
            server.close();
            throw ex;
        }
        worker =
                Thread.ofVirtual()
                        .start(
                                () -> {
                                    try (SocketChannel socket = server.accept()) {
                                        final InputStream in =
                                                new BufferedInputStream(
                                                        Channels.newInputStream(socket));
                                        final ByteArrayOutputStream head =
                                                new ByteArrayOutputStream();
                                        while (!head.toString(StandardCharsets.US_ASCII)
                                                .endsWith("\r\n\r\n")) {
                                            final int b = in.read();
                                            if (b < 0) {
                                                throw new IOException(
                                                        "request ended before its headers");
                                            }
                                            head.write(b);
                                        }
                                        int length = 0;
                                        for (final String line :
                                                head.toString(StandardCharsets.US_ASCII)
                                                        .split("\r\n")) {
                                            if (line.startsWith("Content-Length: ")) {
                                                length =
                                                        Integer.parseInt(
                                                                line.substring(
                                                                        "Content-Length: "
                                                                                .length()));
                                            }
                                        }
                                        final byte[] body = in.readNBytes(length);
                                        Channels.newOutputStream(socket)
                                                .write(response.getBytes(StandardCharsets.UTF_8));
                                        request.complete(
                                                head.toString(StandardCharsets.US_ASCII)
                                                        + new String(body, StandardCharsets.UTF_8));
                                        if (keepOpen) {
                                            release.await();
                                        }
                                    } catch (final Exception ex) {
                                        request.completeExceptionally(ex);
                                    }
                                });
    }

    String request() throws Exception {
        return request.get(5, TimeUnit.SECONDS);
    }

    @Override
    public void close() throws IOException {
        release.countDown();
        worker.interrupt();
        server.close();
        Files.deleteIfExists(path);
    }
}
