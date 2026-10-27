package org.fuin.porelo.message.matrix;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Guards against lost room lines, unread work leaving the account, and escaped credentials. */
@Timeout(90)
class RoomIT {

    @TempDir private static Path bin;
    private static Path binary;
    @TempDir private Path dir;
    private ProcessUnit unit;
    private Map<String, String> env;
    private Map<String, String> kept;
    private MatrixClient core;
    private MatrixClient matrix;
    private MatrixClient smith;
    private String room;
    private String self;
    private Path helper;
    private int transaction;

    /** Uses the same pinned homeserver as the other integration tests. */
    @BeforeAll
    static void extractBinary() throws Exception {
        binary = ProcessUnit.binary(bin);
    }

    /** Each test owns its configuration, database and three members. */
    @BeforeEach
    void setUp() throws Exception {
        env =
                new HashMap<>(
                        Map.of(
                                "HOME",
                                dir.resolve("home").toString(),
                                "XDG_CONFIG_HOME",
                                dir.resolve("config").toString(),
                                "XDG_STATE_HOME",
                                dir.resolve("state").toString()));
        unit = new ProcessUnit(env, binary, dir.resolve("unit"));
        final JsonNode setup = lifecycle(env, "setup", "--project", "demo");
        kept = new HashMap<>(env);
        for (final String map : List.of("account", "secrets")) {
            setup.path(map)
                    .properties()
                    .forEach(entry -> kept.put(entry.getKey(), entry.getValue().asText()));
        }
        room = setup.path("conversation").asText();
        core = person("core", "Agent Core");
        matrix = person("matrix", "Agent Matrix");
        smith = person("smith", "Agent Smith");
        self = Accounts.whoami(core);
        env.put("FIXTURE_TOKEN", core.config().accessToken());
        helper = dir.resolve("credential-helper");
        Files.writeString(
                helper,
                "#!/bin/sh\ncat >/dev/null\nprintf 'username=x-access-token\\npassword=%s\\n' \"$FIXTURE_TOKEN\"\n");
        assertThat(helper.toFile().setExecutable(true, true))
                .as("the helper fixture is executable")
                .isTrue();
    }

    /** Ends only the homeserver this test started. */
    @AfterEach
    void tearDown() throws Exception {
        if (unit != null) {
            unit.close();
        }
    }

    @Test
    void firstReadStartsNowAndPostAndWaitRequireIt() throws Exception {
        send(matrix, "All agents: old", null);
        assertThat(room("post", text("new").toString()).exit())
                .as("post needs a read position")
                .isEqualTo(Exit.CONFIG);
        assertThat(room("wait").exit()).as("wait needs a read position").isEqualTo(Exit.CONFIG);
        assertThat(success("read").out()).as("first read never replays history").isEmpty();
        assertThat(success("read").out()).as("the first read saves a position").isEmpty();
    }

    @Test
    void readShowsOnlyAddressedLinesAndMovesPastEveryLine() throws Exception {
        success("read");
        mixedLines();
        final Transport.Result read = success("read");
        assertThat(read.exit()).as(read.err()).isZero();
        assertThat(read.out())
                .as("head, pill and broadcast are read in order")
                .containsSubsequence(
                        "Agent Core: headed", "Agent Matrix: pill", "All agents: broadcast")
                .doesNotContain("body-only", "third-only", "own-line");
        assertThat(read.out().lines().filter(line -> line.startsWith("## ")).toList())
                .as("three UTC headings")
                .hasSize(3)
                .allMatch(
                        line ->
                                line.matches(
                                        "## \\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}Z — Agent Matrix"));
        assertThat(success("read").out()).as("every event is now past the position").isEmpty();
    }

    @Test
    void allReadsEveryOtherMembersTextAndAdvances() throws Exception {
        success("read");
        mixedLines();
        final Transport.Result read = success("read", "--all");
        assertThat(read.out())
                .as("all other members' text is shown")
                .contains("headed", "pill", "body-only", "third-only", "broadcast")
                .doesNotContain("own-line");
        assertThat(read.out().lines().filter(line -> line.startsWith("## ")).count())
                .as("five other lines")
                .isEqualTo(5);
        assertThat(success("read", "--all").out()).as("all also advances").isEmpty();
    }

    @Test
    void postCountsEveryUnreadAddressedLineWithoutMovingThePosition() throws Exception {
        success("read");
        mixedLines();
        final Path file = text("Agent Matrix: answer");
        final Transport.Result refused = room("post", file.toString());
        assertThat(refused.exit())
                .as("unread addressed work holds the post")
                .isEqualTo(Exit.TEMPORARY);
        assertThat(refused.err()).as("all three unread lines are counted").contains("3");
        assertThat(success("read").out())
                .as("a refused post never advances")
                .contains("headed", "pill", "broadcast");
        assertThat(room("post", file.toString()).exit()).as("reading releases the post").isZero();
    }

    @Test
    void otherAgentsLinesDoNotHoldAPostAndRetrySendsOneEventWithMentions() throws Exception {
        success("read");
        send(matrix, "Agent Smith: third-only", null);
        final String since = core.get("/_matrix/client/v3/sync").path("next_batch").asText();
        final Path file = text("Agent Matrix, Agent Smith, ALL AGENTS: answer");
        assertThat(room("post", file.toString()).exit())
                .as("another agent's unread work does not hold the post")
                .isZero();
        assertThat(room("post", file.toString()).exit()).as("a retry is accepted").isZero();
        final List<JsonNode> sent = new ArrayList<>();
        core.get("/_matrix/client/v3/sync?since=" + MatrixClient.segment(since))
                .path("rooms")
                .path("join")
                .path(room)
                .path("timeline")
                .path("events")
                .forEach(
                        event -> {
                            if (self.equals(event.path("sender").asText())
                                    && "m.room.message".equals(event.path("type").asText())) {
                                sent.add(event);
                            }
                        });
        assertThat(sent).as("both attempts produce one event").hasSize(1);
        final JsonNode content = sent.getFirst().path("content");
        assertThat(content.path("body").asText())
                .as("the post is the file's exact text")
                .isEqualTo(Files.readString(file));
        assertThat(content.path("m.mentions").path("user_ids"))
                .as("the named members are pills")
                .isEqualTo(
                        MatrixClient.JSON
                                .createArrayNode()
                                .add("@matrix:localhost")
                                .add("@smith:localhost"));
        assertThat(content.path("m.mentions").path("room").asBoolean())
                .as("All agents mentions the room")
                .isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"head", "pill", "everybody"})
    void waitIgnoresOtherLinesAndWakesOnlyForAnAddressedLine(final String kind) throws Exception {
        final Path conf = LocalHomeserver.directory(env).resolve("homeserver.conf");
        final String configuration = Files.readString(conf);
        try (SyncGate gate = new SyncGate(matrix.config().homeserver())) {
            Files.writeString(conf, LocalHomeserver.PORT + "=" + gate.port() + "\n");
            waitsForTheExpectedSender(kind, gate);
        } finally {
            Files.writeString(conf, configuration);
        }
    }

    /** Distinct senders and gated requests prevent an unrelated early wake from passing. */
    private void waitsForTheExpectedSender(final String kind, final SyncGate gate)
            throws Exception {
        success("read");
        final Path position = position();
        final String saved = Files.readString(position);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            final CompletableFuture<Transport.Result> waiting =
                    CompletableFuture.supplyAsync(() -> room("wait"), executor);
            try {
                final CountDownLatch first = gate.awaitSync();
                first.countDown();
                matrix.put(
                        "/_matrix/client/v3/rooms/"
                                + MatrixClient.segment(room)
                                + "/typing/"
                                + MatrixClient.segment(Accounts.whoami(matrix)),
                        MatrixClient.JSON
                                .createObjectNode()
                                .put("typing", true)
                                .put("timeout", 1000));
                gate.awaitSync();
                assertThat(waiting.isDone())
                        .as("the typing-only wake did not end the wait")
                        .isFalse();
                gate.open();
                send(smith, "Agent Matrix: Agent Core body-only", null);
                send(smith, "Agent Matrix: third-only", null);
                send(core, "All agents: own-line", null);
                assertThatThrownBy(() -> waiting.get(1, TimeUnit.SECONDS))
                        .as("unaddressed and own lines leave the wait open")
                        .isInstanceOf(java.util.concurrent.TimeoutException.class);
                send(
                        matrix,
                        "head".equals(kind)
                                ? "Agent Core: wake"
                                : "pill".equals(kind) ? "Agent Matrix: wake" : "All agents: wake",
                        "pill".equals(kind) ? self : null);
                final Transport.Result result = waiting.get(10, TimeUnit.SECONDS);
                assertThat(result.exit()).as(result.err()).isZero();
                assertThat(result.out().lines().toList())
                        .as("wait prints only one heading")
                        .hasSize(1)
                        .allMatch(
                                line -> line.startsWith("## ") && line.endsWith(" — Agent Matrix"));
                assertThat(Files.readString(position))
                        .as("wait never saves its in-memory progress")
                        .isEqualTo(saved);
                assertThat(success("read").out())
                        .as("the waking line stays unread")
                        .contains("wake");
            } finally {
                if (!waiting.isDone()) {
                    executor.shutdownNow();
                }
            }
        }
    }

    /** A writer's name must neither hide an announcement nor notify the writer on a post. */
    @Test
    void signaturesAddressEverybodyElseAndPostsMentionOnlyOtherMembers() throws Exception {
        success("read");
        send(matrix, "Agent Matrix: announcement", null);
        assertThat(success("read").out())
                .as("a signature is not an explicit other addressee")
                .contains("announcement");
        success("post", text("Agent Core, Agent Matrix: reply").toString());
        final JsonNode event =
                core.get(
                                "/_matrix/client/v3/rooms/"
                                        + MatrixClient.segment(room)
                                        + "/messages?dir=b&limit=1")
                        .path("chunk")
                        .get(0);
        assertThat(event.path("content").path("m.mentions").path("user_ids"))
                .as("the writer's name never creates a self mention")
                .isEqualTo(MatrixClient.JSON.createArrayNode().add(Accounts.whoami(matrix)));
    }

    /** An edit must not wake a reader, appear as a new line or hold a post. */
    @Test
    void anEditDoesNotBecomeUnreadWork() throws Exception {
        success("read");
        final String path =
                "/_matrix/client/v3/rooms/" + MatrixClient.segment(room) + "/send/m.room.message/";
        final String original =
                matrix.put(
                                path + "original",
                                MatrixClient.JSON
                                        .createObjectNode()
                                        .put("msgtype", "m.text")
                                        .put("body", "Agent Core: original"))
                        .path("event_id")
                        .asText();
        assertThat(success("read").out()).as("the original is read first").contains("original");
        final ObjectNode edit =
                MatrixClient.JSON
                        .createObjectNode()
                        .put("msgtype", "m.text")
                        .put("body", "Agent Core: corrected");
        edit.putObject("m.relates_to").put("rel_type", "m.replace").put("event_id", original);
        edit.putObject("m.new_content")
                .put("msgtype", "m.text")
                .put("body", "Agent Core: corrected");
        matrix.put(path + "edit", edit);
        success("post", text("Agent Matrix: reply").toString());
        assertThat(success("read").out()).as("the edit is not a new line").isEmpty();
    }

    /** A transparent local proxy proves the waiter has entered each long sync. */
    private static final class SyncGate implements AutoCloseable {
        private final URI backend;
        private final HttpServer server;
        private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        private final HttpClient client = HttpClient.newHttpClient();
        private final BlockingQueue<CountDownLatch> arrivals = new LinkedBlockingQueue<>();
        private final AtomicBoolean open = new AtomicBoolean();
        private final List<CountDownLatch> releases =
                new java.util.concurrent.CopyOnWriteArrayList<>();

        /** Runs only in this test's account, forwarding to its own Tuwunel. */
        SyncGate(final URI backend) throws IOException {
            this.backend = backend;
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(executor);
            server.createContext("/", this::forward);
            server.start();
        }

        /** The configured local port the Room client actually reaches. */
        int port() {
            return server.getAddress().getPort();
        }

        /** Fails rather than mistaking an early return for a waiter in sync. */
        CountDownLatch awaitSync() throws InterruptedException {
            final CountDownLatch release = arrivals.poll(5, TimeUnit.SECONDS);
            assertThat(release).as("the waiter has entered its next long sync").isNotNull();
            return release;
        }

        /** Lets later requests reach Tuwunel while the test sends ordinary room activity. */
        void open() {
            open.set(true);
            releases.forEach(CountDownLatch::countDown);
        }

        /** Holds each long sync until the test has placed the intended events. */
        private void forward(final HttpExchange exchange) throws IOException {
            try {
                final String path = exchange.getRequestURI().toString();
                if (path.startsWith("/_matrix/client/v3/sync?timeout=30000&")) {
                    final CountDownLatch release = new CountDownLatch(1);
                    releases.add(release);
                    arrivals.add(release);
                    if (!open.get() && !release.await(10, TimeUnit.SECONDS)) {
                        throw new IOException("the test did not release its long sync");
                    }
                }
                final HttpRequest request =
                        HttpRequest.newBuilder(URI.create(backend + path))
                                .header(
                                        "Authorization",
                                        exchange.getRequestHeaders().getFirst("Authorization"))
                                .GET()
                                .build();
                final HttpResponse<byte[]> response =
                        client.send(request, HttpResponse.BodyHandlers.ofByteArray());
                exchange.sendResponseHeaders(response.statusCode(), response.body().length);
                try (var output = exchange.getResponseBody()) {
                    output.write(response.body());
                }
            } catch (final InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IOException("the sync gate was interrupted");
            } finally {
                exchange.close();
            }
        }

        /** Releases only this test's requests and server. */
        @Override
        public void close() {
            releases.forEach(CountDownLatch::countDown);
            server.stop(0);
            executor.shutdownNow();
            client.close();
        }
    }

    @Test
    void truncatedTimelinesArePagedForReadAndTheUnreadCount() throws Exception {
        success("read");
        for (int i = 0; i < 125; i++) {
            send(matrix, "Agent Core: line-" + i, null);
        }
        final Transport.Result refused = room("post", text("reply").toString());
        assertThat(refused.exit())
                .as("oldest unread lines still hold a post")
                .isEqualTo(Exit.TEMPORARY);
        assertThat(refused.err()).as("the entire limited timeline is counted").contains("125");
        final Transport.Result read = success("read");
        assertThat(read.out().lines().filter(line -> line.startsWith("## ")).count())
                .as("no paged line is lost or duplicated")
                .isEqualTo(125);
        assertThat(read.out())
                .as("the first and last line survive paging")
                .contains("line-0", "line-124");
    }

    @Test
    void onlyTextEventsInTheChosenRoomCount() throws Exception {
        success("read");
        final String path = "/_matrix/client/v3/rooms/" + MatrixClient.segment(room) + "/send/";
        matrix.put(
                path + "m.room.message/notice",
                MatrixClient.JSON
                        .createObjectNode()
                        .put("msgtype", "m.notice")
                        .put("body", "All agents: notice"));
        matrix.put(path + "m.reaction/reaction", MatrixClient.JSON.createObjectNode());
        final String elsewhere = anotherRoom();
        matrix.put(
                "/_matrix/client/v3/rooms/"
                        + MatrixClient.segment(elsewhere)
                        + "/send/m.room.message/elsewhere",
                MatrixClient.JSON
                        .createObjectNode()
                        .put("msgtype", "m.text")
                        .put("body", "All agents: another room"));
        assertThat(success("read").out()).as("non-text events are ignored").isEmpty();
    }

    /** A position must never be borrowed from another person or another room. */
    @Test
    void eachPersonAndRoomNeedsItsOwnFirstRead() throws Exception {
        success("read");
        env.put("FIXTURE_TOKEN", matrix.config().accessToken());
        assertThat(room("post", text("hello").toString()).exit())
                .as("another person's position is no authority to post")
                .isEqualTo(Exit.CONFIG);
        success("read");
        env.put("FIXTURE_TOKEN", core.config().accessToken());
        room = anotherRoom();
        assertThat(room("wait").exit())
                .as("another room needs a read of its own")
                .isEqualTo(Exit.CONFIG);
        success("read");
        try (var files = Files.list(Poll.stateDirectory(env).resolve("room"))) {
            assertThat(files.filter(path -> path.toString().endsWith(".since")).count())
                    .as("two people and two rooms have three independent positions")
                    .isEqualTo(3);
        }
    }

    @Test
    void invalidTextAndOversizedSerializedContentAreRefusedWithoutPosting() throws Exception {
        success("read");
        final Path file = text("unused");
        Files.write(file, new byte[] {(byte) 0xc3, 0x28});
        assertThat(room("post", file.toString()).exit())
                .as("malformed UTF-8 is refused")
                .isEqualTo(Exit.DATA);
        Files.writeString(file, "\u0001".repeat(Send.CONTENT_BYTES / 2));
        assertThat(room("post", file.toString()).exit())
                .as("JSON escaping counts toward the event limit")
                .isEqualTo(Exit.DATA);
    }

    @Test
    void aPositionFromAnotherDatabaseIsRefused() throws Exception {
        success("read");
        Files.writeString(position(), "old-position\n!old-database\n");
        assertThat(room("read").exit())
                .as("a stale database position cannot silently lose messages")
                .isEqualTo(Exit.CONFIG);
    }

    @Test
    void malformedCredentialOutputNeverAppearsOnStderr() throws Exception {
        Files.writeString(
                helper,
                "#!/bin/sh\ncat >/dev/null\nprintf '{\"password\":\"sensitive-marker\"}'\n");
        final Transport.Result result = room("read");
        assertThat(result.exit())
                .as("a malformed helper answer fails closed")
                .isEqualTo(Exit.CONFIG);
        assertThat(result.err())
                .as("no stdout value or parser snippet escapes")
                .contains("agent-core")
                .doesNotContain("sensitive-marker", "password", "Source:");
    }

    /** The first three kinds are meant for Core; the remaining kinds must be skipped. */
    private void mixedLines() throws Exception {
        send(matrix, "Agent Core: headed", null);
        send(matrix, "Agent Matrix: pill", self);
        send(matrix, "Agent Smith: Agent Core body-only", null);
        send(matrix, "Agent Smith: third-only", null);
        send(matrix, "All agents: broadcast", null);
        send(core, "All agents: own-line", null);
    }

    /** Sends real events with optional Matrix pills. */
    private void send(final MatrixClient client, final String body, final String mention)
            throws Exception {
        final ObjectNode content =
                MatrixClient.JSON.createObjectNode().put("msgtype", "m.text").put("body", body);
        if (mention != null) {
            content.putObject("m.mentions").putArray("user_ids").add(mention);
        }
        client.put(
                "/_matrix/client/v3/rooms/"
                        + MatrixClient.segment(room)
                        + "/send/m.room.message/fixture-"
                        + transaction++,
                content);
    }

    /** Makes a second real room the two people share. */
    private String anotherRoom() throws Exception {
        final ObjectNode request =
                MatrixClient.JSON.createObjectNode().put("preset", "private_chat");
        request.putArray("invite").add(self);
        final String id =
                matrix.post("/_matrix/client/v3/createRoom", request).path("room_id").asText();
        core.post(
                "/_matrix/client/v3/join/" + MatrixClient.segment(id),
                MatrixClient.JSON.createObjectNode());
        return id;
    }

    /** A person joins through the token lifecycle used by Porelo. */
    private MatrixClient person(final String name, final String display) throws Exception {
        final JsonNode answer =
                lifecycle(
                        kept,
                        "join",
                        "--project",
                        "demo",
                        "--person",
                        name,
                        "--token",
                        "--display",
                        display);
        return new MatrixClient(
                new Config(
                        java.net.URI.create(kept.get(Config.HOMESERVER)),
                        answer.path("secrets").path(Config.ACCESS_TOKEN).asText(),
                        null,
                        true));
    }

    /** Runs the lifecycle with the test's own process unit. */
    private JsonNode lifecycle(final Map<String, String> environment, final String... args)
            throws Exception {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        Lifecycle.run(
                args[0],
                List.of(args).subList(1, args.length),
                environment,
                new ByteArrayInputStream("{}".getBytes(StandardCharsets.UTF_8)),
                new PrintStream(out),
                System.err,
                unit);
        return MatrixClient.JSON.readTree(out.toByteArray());
    }

    /** Runs the credential pipe and conversation in this JVM with a stand-in helper path. */
    private Transport.Result room(final String... args) {
        final List<String> command = new ArrayList<>(List.of(args));
        command.addAll(List.of("--entry", "agent-core", "--room", room));
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        final ByteArrayOutputStream err = new ByteArrayOutputStream();
        int exit = Exit.OK;
        try {
            new Room(helper).run(command, env, new PrintStream(out), new PrintStream(err));
        } catch (final Failure failure) {
            exit = Main.reportFailure("room", failure, new PrintStream(err));
        }
        return new Transport.Result(
                exit, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    /** Empty output is evidence only after the command has succeeded. */
    private Transport.Result success(final String... args) {
        final Transport.Result result = room(args);
        assertThat(result.exit()).as("%s: %s", String.join(" ", args), result.err()).isZero();
        return result;
    }

    /** The post's text stays in a test-owned file, without credentials. */
    private Path text(final String body) throws Exception {
        return Files.writeString(dir.resolve("post.txt"), body);
    }

    /** Finds the sole person-and-room position without depending on its hash. */
    private Path position() throws Exception {
        try (var files = Files.list(Poll.stateDirectory(env).resolve("room"))) {
            return files.filter(path -> path.toString().endsWith(".since"))
                    .findFirst()
                    .orElseThrow();
        }
    }
}
