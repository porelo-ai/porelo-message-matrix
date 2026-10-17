package org.fuin.porelo.message.matrix;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * A homeserver on loopback that answers what a test queues and records what it was asked, so each
 * exit code can be reached without a real server.
 */
final class FakeHomeserver implements AutoCloseable {

    record Request(
            String method, String rawPath, String query, String authorization, String body) {}

    private record Answer(int status, String body, String location) {}

    private final HttpServer server;

    private final Deque<Answer> answers = new ArrayDeque<>();

    private final List<Request> requests = new ArrayList<>();

    FakeHomeserver() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", this::handle);
        server.start();
    }

    String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    synchronized FakeHomeserver answer(final int status, final String body) {
        answers.add(new Answer(status, body, null));
        return this;
    }

    /** A redirect elsewhere, as a homeserver taken over or misconfigured might answer. */
    synchronized FakeHomeserver redirect(final int status, final String location) {
        answers.add(new Answer(status, "", location));
        return this;
    }

    synchronized List<Request> requests() {
        return List.copyOf(requests);
    }

    private void handle(final HttpExchange exchange) throws IOException {
        final Answer answer;
        synchronized (this) {
            final String query = exchange.getRequestURI().getRawQuery();
            requests.add(
                    new Request(
                            exchange.getRequestMethod(),
                            exchange.getRequestURI().getRawPath(),
                            query == null ? "" : query,
                            exchange.getRequestHeaders().getFirst("Authorization"),
                            new String(
                                    exchange.getRequestBody().readAllBytes(),
                                    StandardCharsets.UTF_8)));
            answer =
                    answers.isEmpty()
                            ? new Answer(
                                    500,
                                    "{\"errcode\":\"M_UNKNOWN\",\"error\":\"nothing queued\"}",
                                    null)
                            : answers.poll();
        }
        final byte[] bytes = answer.body().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        if (answer.location() != null) {
            exchange.getResponseHeaders().add("Location", answer.location());
        }
        exchange.sendResponseHeaders(answer.status(), bytes.length == 0 ? -1 : bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
