package org.fuin.porelo.message.matrix;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.StandardProtocolFamily;
import java.net.URI;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import jdk.net.ExtendedSocketOptions;
import org.jspecify.annotations.Nullable;

/** Only the homeserver account's private socket may receive the gate's credentials. */
final class GateHomeserver implements Gate.Backend {
    private final Map<String, String> env;

    GateHomeserver(final Map<String, String> env) {
        this.env = env;
    }

    MatrixClient client(final String token) throws Failure, IOException {
        final var owner =
                java.nio.file.FileSystems.getDefault()
                        .getUserPrincipalLookupService()
                        .lookupPrincipalByName(System.getProperty("user.name"));
        return new MatrixClient(
                new Config(
                        URI.create("unix:" + GateFiles.socket(env).toUri().getRawPath()),
                        token,
                        null,
                        true),
                Listener.TABLES,
                owner);
    }

    @Override
    public GateResponse exchange(
            final String token,
            final String method,
            final String path,
            final @Nullable JsonNode body)
            throws Failure, IOException {
        return GateResponse.upstream(client(token).rawExchange(method, path, body));
    }

    @Override
    public GateResponse passwordLogin(final GateRequest request) throws IOException, Failure {
        try (SocketChannel upstream = connect()) {
            request.forward(Channels.newOutputStream(upstream));
            return GateResponse.upstream(
                    SocketWire.read(
                            new BufferedInputStream(Channels.newInputStream(upstream)),
                            request.method()));
        }
    }

    private SocketChannel connect() throws IOException, Failure {
        final SocketChannel upstream = SocketChannel.open(StandardProtocolFamily.UNIX);
        try {
            upstream.connect(UnixDomainSocketAddress.of(GateFiles.socket(env)));
            final var owner =
                    java.nio.file.FileSystems.getDefault()
                            .getUserPrincipalLookupService()
                            .lookupPrincipalByName(System.getProperty("user.name"));
            if (!upstream.getOption(ExtendedSocketOptions.SO_PEERCRED).user().equals(owner)) {
                throw new IOException("Unix listener is not owned by the homeserver account");
            }
            return upstream;
        } catch (final IOException | Failure | RuntimeException ex) {
            upstream.close();
            throw ex;
        }
    }

    @Override
    public int forward(final GateRequest request, final OutputStream out)
            throws IOException, Failure {
        try (SocketChannel upstream = connect()) {
            request.forward(Channels.newOutputStream(upstream));
            final var in = new BufferedInputStream(Channels.newInputStream(upstream));
            final String line = GateRequest.requiredLine(in);
            if (!line.matches("HTTP/1\\.[01] [1-5][0-9]{2}.*")) {
                throw new IOException("homeserver answered an invalid status line");
            }
            final int status = Integer.parseInt(line.substring(9, 12));
            out.write((line + "\r\n").getBytes(StandardCharsets.ISO_8859_1));
            // No whole-request deadline: a sync may remain held open for its requested timeout.
            in.transferTo(out);
            out.flush();
            return status;
        }
    }
}
