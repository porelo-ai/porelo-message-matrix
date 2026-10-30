package org.fuin.porelo.message.matrix;

import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.channels.UnresolvedAddressException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.attribute.UserPrincipal;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLHandshakeException;
import org.jspecify.annotations.Nullable;

/**
 * The few client-API calls the transport makes, each turned into a JSON answer or a {@link Failure}
 * whose exit code says whether Porelo should try again.
 */
final class MatrixClient {

    static final ObjectMapper JSON = new ObjectMapper();

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(60);

    private final Config config;

    private final Wire wire;

    private final List<Path> tables;
    private final @Nullable UserPrincipal socketOwner;

    MatrixClient(final Config config) throws Failure {
        this(config, Listener.TABLES);
    }

    /** With the kernel's socket tables read from {@code tables}, so a test can give its own. */
    MatrixClient(final Config config, final List<Path> tables) throws Failure {
        this(config, tables, null);
    }

    MatrixClient(
            final Config config, final List<Path> tables, final @Nullable UserPrincipal socketOwner)
            throws Failure {
        this.config = config;
        this.tables = tables;
        this.socketOwner = socketOwner;
        if ("unix".equals(config.homeserver().getScheme())) {
            this.wire =
                    new SocketWire(
                            Path.of(config.homeserver().getPath()), CONNECT_TIMEOUT, socketOwner);
            return;
        }
        // A redirect would carry the token to wherever the homeserver points, so none is followed.
        final HttpClient.Builder builder =
                HttpClient.newBuilder()
                        .connectTimeout(CONNECT_TIMEOUT)
                        .followRedirects(HttpClient.Redirect.NEVER);
        final SSLContext tls = Trust.context(config);
        if (tls != null) {
            builder.sslContext(tls);
        }
        final HttpClient http = builder.build();
        this.wire =
                request -> {
                    final HttpResponse<byte[]> response =
                            http.send(request, HttpResponse.BodyHandlers.ofByteArray());
                    return new Wire.Reply(response.statusCode(), response.body());
                };
    }

    /**
     * A status and a JSON body, as the homeserver gave them - for the calls whose refusals are
     * answers.
     */
    record Answer(int status, JsonNode body) {

        String errcode() {
            return body.path("errcode").asText("");
        }
    }

    Config config() {
        return config;
    }

    /**
     * The same homeserver and trust, acting as another account - or as nobody, with an empty token.
     */
    MatrixClient as(final String accessToken) throws Failure {
        return new MatrixClient(
                new Config(config.homeserver(), accessToken, config.caFile(), config.verifyTls()),
                tables,
                socketOwner);
    }

    /**
     * Sends and answers what came back, whatever its status; only an unreachable server is a
     * failure.
     */
    Answer exchange(final String method, final String path, @Nullable final JsonNode body)
            throws Failure {
        final HttpRequest.Builder builder =
                request(path)
                        .method(
                                method,
                                body == null
                                        ? HttpRequest.BodyPublishers.noBody()
                                        : HttpRequest.BodyPublishers.ofByteArray(bytes(body)));
        if (body != null) {
            builder.header("Content-Type", "application/json");
        }
        final HttpRequest request = builder.build();
        final Wire.Reply response = send(request);
        JsonNode node;
        try {
            node = JSON.readTree(response.body());
        } catch (final IOException ex) {
            node = null;
        }
        return new Answer(response.statusCode(), node == null ? JSON.createObjectNode() : node);
    }

    static String segment(final String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    /** An internal gate call whose refusal must retain the homeserver's complete HTTP answer. */
    Wire.Reply rawExchange(final String method, final String path, final @Nullable JsonNode body)
            throws Failure {
        final HttpRequest.Builder builder =
                request(path)
                        .method(
                                method,
                                body == null
                                        ? HttpRequest.BodyPublishers.noBody()
                                        : HttpRequest.BodyPublishers.ofByteArray(bytes(body)));
        if (body != null) {
            builder.header("Content-Type", "application/json");
        }
        return send(builder.build());
    }

    JsonNode put(final String path, final JsonNode body) throws Failure {
        return call(
                request(path)
                        .PUT(HttpRequest.BodyPublishers.ofByteArray(bytes(body)))
                        .header("Content-Type", "application/json"));
    }

    JsonNode post(final String path, final JsonNode body) throws Failure {
        return call(
                request(path)
                        .POST(HttpRequest.BodyPublishers.ofByteArray(bytes(body)))
                        .header("Content-Type", "application/json"));
    }

    JsonNode get(final String path) throws Failure {
        return call(request(path).GET());
    }

    /**
     * Like {@link #get}, but a 404 - the event or state is not there, or not visible - answers
     * null.
     */
    @Nullable JsonNode find(final String path) throws Failure {
        try {
            return get(path);
        } catch (final NotFound ex) {
            return null;
        }
    }

    /** A 404, told apart so that {@link #find} can answer it with null. */
    private static final class NotFound extends Failure {

        private static final long serialVersionUID = 1L;

        NotFound(final String reason) {
            super(Exit.PROTOCOL, reason);
        }
    }

    private HttpRequest.Builder request(final String path) {
        final HttpRequest.Builder builder =
                HttpRequest.newBuilder(URI.create(endpoint() + path)).timeout(REQUEST_TIMEOUT);
        // Registration and login are made by nobody yet; every other call as the account.
        if (!config.accessToken().isEmpty()) {
            builder.header("Authorization", "Bearer " + config.accessToken());
        }
        return builder;
    }

    private URI endpoint() {
        final URI url = config.homeserver();
        if ("unix".equals(url.getScheme())) {
            return URI.create("http://localhost");
        }
        final String host = url.getHost();
        if (host != null && host.toLowerCase(Locale.ROOT).endsWith(".localhost")) {
            return URI.create(
                    url.getScheme()
                            + "://127.0.0.1"
                            + (url.getPort() < 0 ? "" : ":" + url.getPort())
                            + url.getRawPath());
        }
        return url;
    }

    private Wire.Reply send(final HttpRequest request) throws Failure {
        // Asked at every request: a homeserver that restarted since the last one may have left its
        // port to another account.
        if (!"unix".equals(config.homeserver().getScheme())) {
            Listener.ensureOwn(config.homeserver(), tables);
        }
        try {
            return wire.send(request);
        } catch (final SSLHandshakeException ex) {
            // Not a moment's trouble: a certificate this machine does not trust stays untrusted,
            // and whoever
            // presents it may not be the homeserver. Retrying would only send the token again.
            throw new Failure(
                    Exit.CONFIG,
                    "homeserver " + config.homeserver() + " is not trusted: " + ex.getMessage(),
                    ex);
        } catch (final IOException ex) {
            throw new Failure(
                    Exit.TEMPORARY,
                    "homeserver " + config.homeserver() + " not reachable: " + cause(ex),
                    ex);
        } catch (final InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new Failure(Exit.TEMPORARY, "interrupted while calling the homeserver", ex);
        }
    }

    private JsonNode call(final HttpRequest.Builder builder) throws Failure {
        final HttpRequest request = builder.build();
        final Wire.Reply response = send(request);
        final int status = response.statusCode();
        if (status >= 200 && status < 300) {
            return parse(response.body(), request);
        }
        final String reason =
                request.method()
                        + " "
                        + request.uri().getPath()
                        + " answered "
                        + status
                        + " "
                        + matrixError(response.body());
        if (status == 401 || status == 403) {
            throw new Failure(Exit.NOT_PERMITTED, reason);
        }
        if (status == 404) {
            throw new NotFound(reason);
        }
        if (status == 413) {
            throw new Failure(Exit.DATA, reason);
        }
        if (status == 429 || status >= 500) {
            throw new Failure(Exit.TEMPORARY, reason);
        }
        throw new Failure(Exit.PROTOCOL, reason);
    }

    private static JsonNode parse(final byte[] body, final HttpRequest request) throws Failure {
        try {
            final JsonNode node = JSON.readTree(body);
            if (node == null || !node.isObject()) {
                throw new Failure(
                        Exit.PROTOCOL,
                        request.uri().getPath() + " answered something that is not a JSON object");
            }
            return node;
        } catch (final IOException ex) {
            throw new Failure(
                    Exit.PROTOCOL,
                    request.uri().getPath() + " answered something that is not JSON",
                    ex);
        }
    }

    /**
     * Why a connection failed, in words a person can act on. The JDK's connect failures often carry
     * no message of their own, and "ConnectException" alone does not tell a stopped homeserver from
     * a mistyped name.
     */
    static String cause(final Throwable failure) {
        String deepest = null;
        boolean notAccepted = false;
        for (Throwable t = failure; t != null; t = t.getCause()) {
            // The JDK's client reports a refused connection as a ConnectException with no message
            // at all,
            // so "refused" cannot be read - only that nothing accepted the connection.
            notAccepted |= t instanceof ConnectException;
            if (t instanceof UnresolvedAddressException || t instanceof UnknownHostException) {
                return "no such host";
            }
            if (t instanceof HttpConnectTimeoutException) {
                return "timed out connecting";
            }
            if (t instanceof HttpTimeoutException) {
                return "no answer within " + REQUEST_TIMEOUT.toSeconds() + " s";
            }
            final String message = t.getMessage();
            if (message != null
                    && message.toLowerCase(Locale.ROOT).contains("connection refused")) {
                return "connection refused";
            }
            if (message != null && !message.isBlank()) {
                deepest = message;
            }
        }
        if (deepest != null) {
            return deepest;
        }
        return notAccepted
                ? "the connection was not accepted (refused, or no route)"
                : failure.getClass().getName();
    }

    /** The homeserver's own words for a refusal, so the person reading stderr sees why. */
    private static String matrixError(final byte[] body) {
        try {
            final JsonNode node = JSON.readTree(body);
            if (node != null && node.isObject()) {
                return node.path("errcode").asText("") + ": " + node.path("error").asText("");
            }
        } catch (final IOException ex) {
            // Not JSON: the status alone has to do.
        }
        return "(no Matrix error in the answer)";
    }

    private static byte[] bytes(final JsonNode body) {
        try {
            return JSON.writeValueAsBytes(body);
        } catch (final JacksonException ex) {
            throw new IllegalStateException("A JSON tree could not be written", ex);
        }
    }
}
