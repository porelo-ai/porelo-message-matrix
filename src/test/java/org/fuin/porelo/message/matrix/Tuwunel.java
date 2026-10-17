package org.fuin.porelo.message.matrix;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The homeserver of {@code homeserver/Dockerfile}, started in rootless podman on loopback for one
 * test class, with just enough of the client API to set up accounts and a room and to look at what
 * arrived. Every secret it makes is random, reaches podman through a file only its owner can read,
 * and is never printed.
 */
final class Tuwunel implements AutoCloseable {

    static final String SERVER_NAME = "matrix.test";

    private static final Path DOCKERFILE = Path.of("homeserver", "Dockerfile");

    private static final Pattern FROM = Pattern.compile("(?m)^FROM\\s+(\\S+)\\s*$");

    private static final Duration START_TIMEOUT = Duration.ofSeconds(60);

    private static final SecureRandom RANDOM = new SecureRandom();

    /** An account in the room: its id and the token the transport is given. */
    record Account(String userId, String accessToken) {

        @Override
        public String toString() {
            return "Account[" + userId + "]";
        }
    }

    private final String container;

    private final Path dir;

    private final String registrationToken;

    private final String url;

    private final HttpClient http = HttpClient.newHttpClient();

    private Tuwunel(
            final String container,
            final Path dir,
            final String registrationToken,
            final String url) {
        this.container = container;
        this.dir = dir;
        this.registrationToken = registrationToken;
        this.url = url;
    }

    static String image() throws IOException {
        final Matcher m = FROM.matcher(Files.readString(DOCKERFILE, StandardCharsets.UTF_8));
        if (!m.find() || !m.group(1).contains("@sha256:")) {
            throw new IllegalStateException(
                    DOCKERFILE + " must pin the image by digest in its FROM line");
        }
        return m.group(1);
    }

    static Tuwunel start() throws IOException, InterruptedException {
        final Path dir =
                Files.createTempDirectory(
                        "tuwunel-",
                        PosixFilePermissions.asFileAttribute(
                                PosixFilePermissions.fromString("rwx------")));
        final String token = random();
        final Path env =
                Files.createFile(
                        dir.resolve("env"),
                        PosixFilePermissions.asFileAttribute(
                                PosixFilePermissions.fromString("rw-------")));
        Files.writeString(
                env,
                String.join(
                        "\n",
                        "TUWUNEL_SERVER_NAME=" + SERVER_NAME,
                        "TUWUNEL_ADDRESS=0.0.0.0",
                        "TUWUNEL_PORT=8008",
                        "TUWUNEL_DATABASE_PATH=/data",
                        "TUWUNEL_ALLOW_FEDERATION=false",
                        "TUWUNEL_ALLOW_REGISTRATION=true",
                        "TUWUNEL_REGISTRATION_TOKEN=" + token,
                        ""),
                StandardCharsets.UTF_8);
        // Never "porelo-…": the build runs in a developer's own podman, where Porelo takes such a
        // name for a task.
        final String container = "tuwunel-it-" + random().substring(0, 8).toLowerCase();
        // A start that fails half-way removes what it made - the container and the directory with
        // the token - before it says why.
        try {
            podman(
                    "run",
                    "-d",
                    "--rm",
                    "--name",
                    container,
                    "--env-file",
                    env.toString(),
                    "-p",
                    "127.0.0.1::8008",
                    image());
            final String port = podman("port", container, "8008/tcp").strip().replaceAll(".*:", "");
            final Tuwunel tuwunel = new Tuwunel(container, dir, token, "http://127.0.0.1:" + port);
            tuwunel.awaitUp();
            return tuwunel;
        } catch (final IOException | InterruptedException | RuntimeException ex) {
            try {
                podman("rm", "-f", "-i", container);
            } catch (final IOException | RuntimeException cleanup) {
                ex.addSuppressed(cleanup);
            }
            try (Stream<Path> files = Files.walk(dir)) {
                for (final Path file : files.sorted(java.util.Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(file);
                }
            } catch (final IOException cleanup) {
                ex.addSuppressed(cleanup);
            }
            throw ex;
        }
    }

    String url() {
        return url;
    }

    /**
     * Makes the homeserver anew on the same address, as removing its database does: every account
     * and room gone.
     */
    void reset() throws IOException, InterruptedException {
        podman("rm", "-f", container);
        podman(
                "run",
                "-d",
                "--rm",
                "--name",
                container,
                "--env-file",
                dir.resolve("env").toString(),
                "-p",
                "127.0.0.1:" + URI.create(url).getPort() + ":8008",
                image());
        awaitUp();
    }

    /**
     * For the lifecycle's tests, which register as Porelo's setup does on a homeserver that is not
     * the account's.
     */
    String registrationToken() {
        return registrationToken;
    }

    Account register(final String user) throws IOException, InterruptedException {
        final ObjectNode request =
                MatrixClient.JSON
                        .createObjectNode()
                        .put("username", user)
                        .put("password", random());
        // The first call opens the registration session; the second answers its token stage.
        final JsonNode session = call("POST", "/_matrix/client/v3/register", null, request, 401);
        request.putObject("auth")
                .put("type", "m.login.registration_token")
                .put("token", registrationToken)
                .put("session", session.path("session").asText());
        final JsonNode answer = call("POST", "/_matrix/client/v3/register", null, request, 200);
        return new Account(answer.path("user_id").asText(), answer.path("access_token").asText());
    }

    /**
     * What anybody registered on the homeserver can do: make a public room under an alias of their
     * choosing.
     */
    String claimAlias(final Account owner, final String localAlias)
            throws IOException, InterruptedException {
        final ObjectNode request =
                MatrixClient.JSON
                        .createObjectNode()
                        .put("preset", "public_chat")
                        .put("room_alias_name", localAlias);
        return call("POST", "/_matrix/client/v3/createRoom", owner, request, 200)
                .path("room_id")
                .asText();
    }

    String createRoom(final Account owner, final Account... invited)
            throws IOException, InterruptedException {
        final ObjectNode request =
                MatrixClient.JSON.createObjectNode().put("preset", "private_chat");
        for (final Account account : invited) {
            request.withArray("invite").add(account.userId());
        }
        final String room =
                call("POST", "/_matrix/client/v3/createRoom", owner, request, 200)
                        .path("room_id")
                        .asText();
        for (final Account account : invited) {
            call(
                    "POST",
                    "/_matrix/client/v3/join/" + MatrixClient.segment(room),
                    account,
                    MatrixClient.JSON.createObjectNode(),
                    200);
        }
        return room;
    }

    /**
     * What a person's client does to write to one account alone: a new room, marked direct,
     * inviting it.
     */
    String openDirect(final Account from, final Account to)
            throws IOException, InterruptedException {
        return openDirect(from, to, false);
    }

    /**
     * A direct chat as a client opens it, encrypted from the start when asked - as nheko does by
     * default.
     */
    String openDirect(final Account from, final Account to, final boolean encrypted)
            throws IOException, InterruptedException {
        final ObjectNode request =
                MatrixClient.JSON
                        .createObjectNode()
                        .put("preset", "private_chat")
                        .put("is_direct", true);
        request.putArray("invite").add(to.userId());
        if (encrypted) {
            request.putArray("initial_state")
                    .addObject()
                    .put("type", "m.room.encryption")
                    .put("state_key", "")
                    .putObject("content")
                    .put("algorithm", "m.megolm.v1.aes-sha2");
        }
        return call("POST", "/_matrix/client/v3/createRoom", from, request, 200)
                .path("room_id")
                .asText();
    }

    /** An event of any type, content as given - what an encrypting client sends. */
    void sendEvent(
            final Account account, final String room, final String type, final ObjectNode content)
            throws IOException, InterruptedException {
        call(
                "PUT",
                "/_matrix/client/v3/rooms/"
                        + MatrixClient.segment(room)
                        + "/send/"
                        + type
                        + "/"
                        + random(),
                account,
                content,
                200);
    }

    /** The account's membership in a room, as the room's state says it, or "" when it has none. */
    String membership(final Account asker, final String room, final String user)
            throws IOException, InterruptedException {
        return call(
                        "GET",
                        "/_matrix/client/v3/rooms/"
                                + MatrixClient.segment(room)
                                + "/state/m.room.member/"
                                + MatrixClient.segment(user),
                        asker,
                        null,
                        200)
                .path("membership")
                .asText("");
    }

    /** What a person's client sends, content as given; answers the event's id. */
    String sayContent(final Account account, final String room, final ObjectNode content)
            throws IOException, InterruptedException {
        return call(
                        "PUT",
                        "/_matrix/client/v3/rooms/"
                                + MatrixClient.segment(room)
                                + "/send/m.room.message/"
                                + random(),
                        account,
                        content,
                        200)
                .path("event_id")
                .asText();
    }

    /** What a person's client sends: a plain text message, with no signature beside it. */
    void say(final Account account, final String room, final String text)
            throws IOException, InterruptedException {
        final ObjectNode content =
                MatrixClient.JSON.createObjectNode().put("msgtype", "m.text").put("body", text);
        call(
                "PUT",
                "/_matrix/client/v3/rooms/"
                        + MatrixClient.segment(room)
                        + "/send/m.room.message/"
                        + random(),
                account,
                content,
                200);
    }

    /**
     * An event of the transport's earlier form: the message in the body, its signature beside it.
     */
    void sayInTheEarlierForm(final Account account, final String room, final String message)
            throws IOException, InterruptedException {
        final ObjectNode content =
                MatrixClient.JSON
                        .createObjectNode()
                        .put("msgtype", "m.text")
                        .put("body", message)
                        .put(Send.SIGNATURE_FIELD, "AAEC");
        call(
                "PUT",
                "/_matrix/client/v3/rooms/"
                        + MatrixClient.segment(room)
                        + "/send/m.room.message/"
                        + random(),
                account,
                content,
                200);
    }

    /** What a person's client does on reading the room up to an event: a public read receipt. */
    void read(final Account account, final String room, final String eventId)
            throws IOException, InterruptedException {
        call(
                "POST",
                "/_matrix/client/v3/rooms/"
                        + MatrixClient.segment(room)
                        + "/receipt/m.read/"
                        + MatrixClient.segment(eventId),
                account,
                MatrixClient.JSON.createObjectNode(),
                200);
    }

    /** Every {@code m.room.message} the account sees in the room, oldest first. */
    /** The rooms an account is invited to and has not answered, as its own sync has them. */
    List<String> invitedRooms(final Account account) throws IOException, InterruptedException {
        final JsonNode sync = call("GET", "/_matrix/client/v3/sync?timeout=0", account, null, 200);
        final List<String> rooms = new java.util.ArrayList<>();
        sync.path("rooms").path("invite").properties().forEach(e -> rooms.add(e.getKey()));
        return rooms;
    }

    List<JsonNode> messages(final Account account, final String room)
            throws IOException, InterruptedException {
        final JsonNode sync = call("GET", "/_matrix/client/v3/sync?timeout=0", account, null, 200);
        final List<JsonNode> messages = new java.util.ArrayList<>();
        for (final JsonNode event :
                sync.path("rooms").path("join").path(room).path("timeline").path("events")) {
            if ("m.room.message".equals(event.path("type").asText())) {
                messages.add(event);
            }
        }
        return messages;
    }

    private JsonNode call(
            final String method,
            final String path,
            final Account account,
            final JsonNode body,
            final int expected)
            throws IOException, InterruptedException {
        final HttpRequest.Builder builder =
                HttpRequest.newBuilder(URI.create(url + path))
                        .method(
                                method,
                                body == null
                                        ? HttpRequest.BodyPublishers.noBody()
                                        : HttpRequest.BodyPublishers.ofByteArray(
                                                MatrixClient.JSON.writeValueAsBytes(body)));
        if (account != null) {
            builder.header("Authorization", "Bearer " + account.accessToken());
        }
        final HttpResponse<byte[]> response =
                http.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() != expected) {
            throw new IllegalStateException(
                    method
                            + " "
                            + path
                            + " answered "
                            + response.statusCode()
                            + ": "
                            + new String(response.body(), StandardCharsets.UTF_8)
                                    .replace(registrationToken, "<token>"));
        }
        return MatrixClient.JSON.readTree(response.body());
    }

    private void awaitUp() throws InterruptedException {
        final Instant deadline = Instant.now().plus(START_TIMEOUT);
        while (Instant.now().isBefore(deadline)) {
            try {
                final HttpResponse<Void> response =
                        http.send(
                                HttpRequest.newBuilder(URI.create(url + "/_matrix/client/versions"))
                                        .build(),
                                HttpResponse.BodyHandlers.discarding());
                if (response.statusCode() == 200) {
                    return;
                }
            } catch (final IOException ex) {
                // Not listening yet.
            }
            Thread.sleep(100);
        }
        throw new IllegalStateException(
                "Tuwunel did not answer on " + url + " within " + START_TIMEOUT);
    }

    private static String podman(final String... args) throws IOException, InterruptedException {
        final List<String> command = new java.util.ArrayList<>(List.of("podman"));
        command.addAll(List.of(args));
        final Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        final String output =
                new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (process.waitFor() != 0) {
            throw new IllegalStateException(String.join(" ", command) + " failed: " + output);
        }
        return output;
    }

    private static String random() {
        final byte[] bytes = new byte[24];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    @Override
    public void close() throws IOException, InterruptedException {
        try {
            podman("rm", "-f", container);
        } finally {
            Files.deleteIfExists(dir.resolve("env"));
            Files.deleteIfExists(dir);
        }
    }
}
