package org.fuin.porelo.message.matrix;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class SendTest {

    private static final String TOKEN = "syt_test_token_that_must_never_be_printed";

    private static final String ROOM = "!project:matrix.test";

    private static final String ROOM_IN_PATH = "%21project%3Amatrix.test";

    /**
     * The bytes that most often do not survive a text transport: non-ASCII, a tab, a CRLF, a final
     * newline.
     */
    private static final String MESSAGE = "Grüße aus dem Raum ✓\n\tline two\r\nline three\n";

    private static final byte[] SIGNATURE = {0, 1, 2, (byte) 0xfe, (byte) 0xff, '\n', '-', '-'};

    @TempDir private Path dir;

    private FakeHomeserver homeserver;

    private final ByteArrayOutputStream errBytes = new ByteArrayOutputStream();

    private final ByteArrayOutputStream outBytes = new ByteArrayOutputStream();

    private Path message;

    private Path signature;

    @BeforeEach
    void setUp() throws IOException {
        homeserver = new FakeHomeserver();
        message = Files.writeString(dir.resolve("message"), MESSAGE, StandardCharsets.UTF_8);
        signature = Files.write(dir.resolve("message.sig"), SIGNATURE);
    }

    @AfterEach
    void tearDown() {
        homeserver.close();
    }

    private int send(final String... args) {
        return send(Map.of(Config.HOMESERVER, homeserver.url(), Config.ACCESS_TOKEN, TOKEN), args);
    }

    private int send(final Map<String, String> env, final String... args) {
        final String[] all = new String[args.length + 1];
        all[0] = "send";
        System.arraycopy(args, 0, all, 1, args.length);
        return Main.run(
                all,
                env,
                new PrintStream(outBytes, true, StandardCharsets.UTF_8),
                new PrintStream(errBytes, true, StandardCharsets.UTF_8));
    }

    private int sendMessage() {
        return send(message.toString(), signature.toString(), "--to", ROOM);
    }

    private String stderr() {
        return errBytes.toString(StandardCharsets.UTF_8);
    }

    @Nested
    @DisplayName("When the line and the message would not fit one event")
    class Cut {

        private ObjectNode content(final String message) {
            return MatrixClient.JSON
                    .createObjectNode()
                    .put("msgtype", "m.text")
                    .put(Send.MESSAGE_FIELD, message)
                    .put(Send.SIGNATURE_FIELD, Base64.getEncoder().encodeToString(new byte[2048]));
        }

        @Test
        @DisplayName("a line that fits is carried as it is")
        void lineThatFits() {
            final ObjectNode content = content(MESSAGE);
            Send.fit(content, "foo to alice: hello");
            assertThat(content.path("body").asText()).isEqualTo("foo to alice: hello");
        }

        /**
         * Harm guarded: the pills repeat the line as HTML, where a quote or an angle bracket grows
         * to six characters; kept past the limit, the homeserver refuses the event and Porelo the
         * message, for good.
         */
        @Test
        @DisplayName("a mention on a line that fills the event as HTML is sent without its pill")
        void pillsLeftOutWhereTheyWouldNotFit() throws IOException {
            final ObjectNode content = content(MESSAGE);

            Send.mention(content, java.util.Map.of("@a:x", "Ann"), "<\"".repeat(20_000));

            assertThat(MatrixClient.JSON.writeValueAsBytes(content))
                    .as("the event")
                    .hasSizeLessThanOrEqualTo(Send.CONTENT_BYTES);
            assertThat(content.has("formatted_body")).as("a pill").isFalse();
            assertThat(content.path("body").asText()).as("the line").startsWith("Ann: <\"<\"");
        }

        /**
         * Harm guarded: a person chooses their own display name; one near 64 KiB ended every send
         * that mentions them with 1, and the message was never sent.
         */
        @Test
        @DisplayName("a mention whose name fills the event is sent without its pill")
        void mentionWithAHugeNameIsSent() throws IOException {
            final ObjectNode content = content(MESSAGE);

            Send.mention(content, java.util.Map.of("@a:x", "x".repeat(70_000)), "hello");

            assertThat(MatrixClient.JSON.writeValueAsBytes(content))
                    .as("the event")
                    .hasSizeLessThanOrEqualTo(Send.CONTENT_BYTES);
            assertThat(content.has("formatted_body")).as("a pill").isFalse();
            assertThat(content.path("m.mentions").path("user_ids").get(0).asText())
                    .as("whom it mentions")
                    .isEqualTo("@a:x");
        }

        @Test
        @DisplayName(
                "the line is cut to what fits and says how long it was; the message stays whole")
        void lineIsCut() throws IOException {
            final String message = "\u0001".repeat(Describe.MAX_BYTES);
            final ObjectNode content = content(message);
            final String line = "Grüße ✓ 😀 ".repeat(10_000);

            Send.fit(content, line);

            assertThat(MatrixClient.JSON.writeValueAsBytes(content))
                    .hasSizeLessThanOrEqualTo(Send.CONTENT_BYTES);
            assertThat(content.path(Send.MESSAGE_FIELD).asText()).isEqualTo(message);
            final String body = content.path("body").asText();
            assertThat(body)
                    .endsWith(
                            "… (cut; "
                                    + line.codePointCount(0, line.length())
                                    + " characters in all)");
            final String kept = body.substring(0, body.lastIndexOf("… (cut; "));
            assertThat(line).startsWith(kept);
            assertThat(kept).isNotEmpty();
            assertThat(Character.isHighSurrogate(kept.charAt(kept.length() - 1)))
                    .as("no character split")
                    .isFalse();
        }
    }

    @Nested
    @DisplayName("When the homeserver takes the event")
    class HandedOver {

        @Test
        @DisplayName("it exits 0, having put one m.text event into the room named by --to")
        void putsOneEvent() {
            homeserver.answer(200, "{\"event_id\":\"$abc\"}");

            assertThat(sendMessage()).isEqualTo(Exit.OK);

            assertThat(homeserver.requests())
                    .singleElement()
                    .satisfies(
                            request -> {
                                assertThat(request.method()).isEqualTo("PUT");
                                assertThat(request.rawPath())
                                        .matches(
                                                "/_matrix/client/v3/rooms/"
                                                        + ROOM_IN_PATH
                                                        + "/send/m\\.room\\.message/porelo-[0-9a-f]{64}");
                                assertThat(request.authorization()).isEqualTo("Bearer " + TOKEN);
                            });
        }

        @Test
        @DisplayName(
                "it prints the event as its reference, the one line on stdout, for Porelo to keep")
        void printsReference() throws IOException {
            homeserver.answer(200, "{\"event_id\":\"$abc\"}");

            sendMessage();

            assertThat(outBytes.toString(StandardCharsets.UTF_8))
                    .isEqualTo("{\"reference\":\"$abc\"}" + System.lineSeparator());
        }

        @Test
        @DisplayName(
                "the message travels in its own field, character for character, and the signature beside it byte for byte")
        void carriesMessageAndSignature() throws IOException {
            homeserver.answer(200, "{\"event_id\":\"$abc\"}");

            sendMessage();

            final JsonNode content =
                    MatrixClient.JSON.readTree(homeserver.requests().getFirst().body());
            assertThat(content.path("msgtype").asText()).isEqualTo("m.text");
            assertThat(content.path(Send.MESSAGE_FIELD).asText()).isEqualTo(MESSAGE);
            assertThat(Base64.getDecoder().decode(content.path(Send.SIGNATURE_FIELD).asText()))
                    .isEqualTo(SIGNATURE);
        }

        @Test
        @DisplayName("the body a client shows is the line --shown names, never the message")
        void bodyIsTheLine() throws IOException {
            homeserver.answer(200, "{\"event_id\":\"$abc\"}");
            final Path line =
                    Files.writeString(
                            dir.resolve("line"), "foo to alice: Grüße ✓", StandardCharsets.UTF_8);

            assertThat(
                            send(
                                    message.toString(),
                                    signature.toString(),
                                    "--shown",
                                    line.toString(),
                                    "--to",
                                    ROOM))
                    .as(SendTest.this::stderr)
                    .isEqualTo(Exit.OK);

            final JsonNode content =
                    MatrixClient.JSON.readTree(homeserver.requests().getFirst().body());
            assertThat(content.path("body").asText()).isEqualTo("foo to alice: Grüße ✓");
            assertThat(content.path(Send.MESSAGE_FIELD).asText()).isEqualTo(MESSAGE);
        }

        @Test
        @DisplayName(
                "--mention names the person in m.mentions and as a pill before the line, so their client highlights it")
        void mentionIsAPill() throws IOException {
            homeserver
                    .answer(200, "{\"displayname\":\"Alice <M>\"}")
                    .answer(200, "{\"event_id\":\"$abc\"}");
            final Path line =
                    Files.writeString(
                            dir.resolve("line"), "a <b> & \"c\"\nnext", StandardCharsets.UTF_8);

            assertThat(
                            send(
                                    message.toString(),
                                    signature.toString(),
                                    "--to",
                                    ROOM,
                                    "--shown",
                                    line.toString(),
                                    "--mention",
                                    "@alice:localhost"))
                    .as(SendTest.this::stderr)
                    .isEqualTo(Exit.OK);

            assertThat(homeserver.requests().getFirst().rawPath())
                    .as("the name the person's client shows is asked")
                    .endsWith("/profile/%40alice%3Alocalhost/displayname");
            final JsonNode content =
                    MatrixClient.JSON.readTree(homeserver.requests().get(1).body());
            assertThat(content.path("m.mentions").path("user_ids"))
                    .extracting(JsonNode::asText)
                    .containsExactly("@alice:localhost");
            assertThat(content.path("body").asText()).isEqualTo("Alice <M>: a <b> & \"c\"\nnext");
            assertThat(content.path("format").asText()).isEqualTo("org.matrix.custom.html");
            assertThat(content.path("formatted_body").asText())
                    .isEqualTo(
                            "<a href=\"https://matrix.to/#/@alice:localhost\">"
                                    + "Alice &lt;M&gt;</a>: a &lt;b&gt; &amp; &quot;c&quot;<br>next");
            assertThat(content.path(Send.MESSAGE_FIELD).asText()).isEqualTo(MESSAGE);
        }

        @Test
        @DisplayName(
                "without --mention a room message mentions nobody, so no word in it notifies anybody")
        void noMentionIsNobody() throws IOException {
            homeserver.answer(200, "{\"event_id\":\"$abc\"}");

            sendMessage();

            final JsonNode content =
                    MatrixClient.JSON.readTree(homeserver.requests().getFirst().body());
            assertThat(content.path("m.mentions").path("user_ids").isArray()).isTrue();
            assertThat(content.path("m.mentions").path("user_ids")).isEmpty();
            assertThat(content.has("formatted_body")).isFalse();
        }

        @Test
        @DisplayName(
                "a --mention that is no user id, or a lone one, is 64, and nothing reaches the homeserver")
        void wrongMention() {
            assertThat(
                            send(
                                    message.toString(),
                                    signature.toString(),
                                    "--to",
                                    ROOM,
                                    "--mention",
                                    "alice"))
                    .isEqualTo(Exit.USAGE);
            assertThat(send(message.toString(), signature.toString(), "--to", ROOM, "--mention"))
                    .isEqualTo(Exit.USAGE);
            assertThat(homeserver.requests()).isEmpty();
        }

        @Test
        @DisplayName(
                "without --shown the body says it is a Porelo message, and is never the message")
        void bodyWithoutALine() throws IOException {
            homeserver.answer(200, "{\"event_id\":\"$abc\"}");

            sendMessage();

            assertThat(
                            MatrixClient.JSON
                                    .readTree(homeserver.requests().getFirst().body())
                                    .path("body")
                                    .asText())
                    .isEqualTo(Send.NO_LINE);
        }

        @Test
        @DisplayName(
                "the same message with another line is the same transaction, so a retry is never a second event")
        void lineIsNoPartOfTheTransaction() throws IOException {
            homeserver
                    .answer(200, "{\"event_id\":\"$abc\"}")
                    .answer(200, "{\"event_id\":\"$abc\"}");
            final Path one = Files.writeString(dir.resolve("one"), "one", StandardCharsets.UTF_8);
            final Path two = Files.writeString(dir.resolve("two"), "two", StandardCharsets.UTF_8);

            send(message.toString(), signature.toString(), "--to", ROOM, "--shown", one.toString());
            send(message.toString(), signature.toString(), "--to", ROOM, "--shown", two.toString());

            assertThat(homeserver.requests())
                    .extracting(FakeHomeserver.Request::rawPath)
                    .hasSize(2)
                    .containsOnly(homeserver.requests().getFirst().rawPath());
        }

        @Test
        @DisplayName(
                "sending the same message again uses the same transaction, so a retry is not a second event")
        void retryIsTheSameTransaction() throws IOException {
            homeserver.answer(500, "{}").answer(200, "{\"event_id\":\"$abc\"}");

            assertThat(sendMessage()).isEqualTo(Exit.TEMPORARY);
            assertThat(sendMessage()).isEqualTo(Exit.OK);

            assertThat(homeserver.requests())
                    .hasSize(2)
                    .extracting(FakeHomeserver.Request::rawPath)
                    .containsOnly(homeserver.requests().getFirst().rawPath());
        }
    }

    @ParameterizedTest(name = "--to {0}")
    @CsvSource({
        "!XDYBXYJ9hTiMgwkQGrndR-koodv1BcKZF09HSz55H4k, %21XDYBXYJ9hTiMgwkQGrndR-koodv1BcKZF09HSz55H4k",
        "!project:matrix.test, %21project%3Amatrix.test"
    })
    @DisplayName("a room id with or without a server name is used as given, encoded into the path")
    void roomIdForms(final String to, final String inPath) {
        homeserver.answer(200, "{\"event_id\":\"$abc\"}");

        assertThat(send(message.toString(), signature.toString(), "--to", to)).isEqualTo(Exit.OK);
        assertThat(homeserver.requests())
                .singleElement()
                .satisfies(
                        request ->
                                assertThat(request.rawPath())
                                        .startsWith(
                                                "/_matrix/client/v3/rooms/" + inPath + "/send/"));
    }

    /** Harm guarded: a question for the operator goes to another person's task or relay. */
    @ParameterizedTest(name = "--to {0}")
    @CsvSource({
        "@t.bob:matrix.test",
        "@porelo.bob:matrix.test",
        "@porelo:matrix.test",
        "@bob:other.test"
    })
    @DisplayName("on a central homeserver a direct chat goes only to a person's own account")
    void centralDirectChatOnlyToAPerson(final String to) {
        homeserver.answer(200, "{\"user_id\":\"@t.alice:matrix.test\"}");

        final int exit =
                send(
                        Map.of(
                                Config.HOMESERVER,
                                homeserver.url(),
                                Config.ACCESS_TOKEN,
                                TOKEN,
                                Config.LOGIN,
                                "alice"),
                        message.toString(),
                        signature.toString(),
                        "--to",
                        to);

        assertThat(exit).as("refused").isEqualTo(Exit.CONFIG);
        assertThat(stderr()).as("why").contains(to);
        assertThat(homeserver.requests())
                .as("only whoami was asked, no chat opened and nothing sent")
                .singleElement()
                .satisfies(r -> assertThat(r.rawPath()).contains("whoami"));
    }

    /** Harm guarded: the check refuses the person the question is for. */
    @Test
    @DisplayName("on a central homeserver a person's own account gets the message in its chat")
    void centralDirectChatToAPerson() {
        homeserver
                .answer(200, "{\"user_id\":\"@t.alice:matrix.test\"}")
                .answer(200, "{\"@bob:matrix.test\":[\"!d:matrix.test\"]}")
                .answer(200, "{\"joined_rooms\":[\"!d:matrix.test\"]}")
                .answer(200, "{\"membership\":\"join\"}")
                .answer(200, "{\"event_id\":\"$e\"}");

        final int exit =
                send(
                        Map.of(
                                Config.HOMESERVER,
                                homeserver.url(),
                                Config.ACCESS_TOKEN,
                                TOKEN,
                                Config.LOGIN,
                                "alice"),
                        message.toString(),
                        signature.toString(),
                        "--to",
                        "@bob:matrix.test");

        assertThat(exit).as("sent: %s", stderr()).isEqualTo(Exit.OK);
        assertThat(homeserver.requests().getLast().rawPath())
                .as("into the chat with the person")
                .startsWith("/_matrix/client/v3/rooms/%21d%3Amatrix.test/send/");
    }

    /** A page of a room's messages, newest first: each pair is a sender and its time. */
    private static String page(final Object... senderAndTime) {
        final var answer = MatrixClient.JSON.createObjectNode();
        final var chunk = answer.putArray("chunk");
        for (int i = 0; i < senderAndTime.length; i += 2) {
            final var event =
                    chunk.addObject()
                            .put("type", "m.room.message")
                            .put("sender", (String) senderAndTime[i])
                            .put("origin_server_ts", (Long) senderAndTime[i + 1]);
            event.putObject("content").put("msgtype", "m.text").put("body", "a line");
        }
        return answer.toString();
    }

    private static final String WHOAMI = "{\"user_id\":\"@t.alice:matrix.test\"}";

    private static final String JOIN = "{\"membership\":\"join\"}";

    private static final String BOB = "@bob:matrix.test";

    private static final String TASK = "@t.alice:matrix.test";

    /** {@code m.direct} with these chats with Bob, oldest first. */
    private static String chats(final String... rooms) {
        final var answer = MatrixClient.JSON.createObjectNode();
        final var list = answer.putArray(BOB);
        for (final String room : rooms) {
            list.add(room + ":matrix.test");
        }
        return answer.toString();
    }

    /** {@code joined_rooms} of the task's account: the chats it is still in. */
    private static String joined(final String... rooms) {
        final var answer = MatrixClient.JSON.createObjectNode();
        final var list = answer.putArray("joined_rooms");
        list.add("!project:matrix.test");
        for (final String room : rooms) {
            list.add(room + ":matrix.test");
        }
        return answer.toString();
    }

    private int sendToBob() {
        return send(
                Map.of(
                        Config.HOMESERVER,
                        homeserver.url(),
                        Config.ACCESS_TOKEN,
                        TOKEN,
                        Config.LOGIN,
                        "alice"),
                message.toString(),
                signature.toString(),
                "--to",
                BOB);
    }

    private String sentInto() {
        final String path = homeserver.requests().getLast().rawPath();
        assertThat(path).contains("/send/m.room.message/");
        return path.substring("/_matrix/client/v3/rooms/".length(), path.indexOf("/send/"));
    }

    /** The chats whose messages were read, in order. */
    private List<String> read() {
        return homeserver.requests().stream()
                .filter(r -> r.rawPath().endsWith("/messages"))
                .map(
                        r -> {
                            assertThat(r.query())
                                    .as("newest first, only the person's lines")
                                    .startsWith("dir=b&limit=")
                                    .contains(
                                            "&filter="
                                                    + MatrixClient.segment(
                                                            "{\"types\":[\"m.room.message\"],"
                                                                    + "\"senders\":[\""
                                                                    + BOB
                                                                    + "\"]}"));
                            return r.rawPath()
                                    .substring(
                                            "/_matrix/client/v3/rooms/".length(),
                                            r.rawPath().indexOf("/messages"));
                        })
                .toList();
    }

    /**
     * Harm guarded: a person waits in the chat they wrote in for an answer that went into an older
     * chat with the task they still keep open.
     */
    @ParameterizedTest
    @CsvSource({
        "100, 200, %21b%3Amatrix.test",
        "300, 200, %21a%3Amatrix.test",
        "200, 200, %21b%3Amatrix.test"
    })
    void theAnswerGoesIntoTheChatThePersonLastWroteIn(
            final long inA, final long inB, final String expected) {
        homeserver
                .answer(200, WHOAMI)
                .answer(200, chats("!a", "!b"))
                .answer(200, joined("!a", "!b"))
                .answer(200, JOIN)
                .answer(200, JOIN)
                .answer(200, page(BOB, inA, BOB, 10L))
                .answer(200, page(BOB, inB, BOB, 20L))
                .answer(200, "{\"event_id\":\"$e\"}");

        assertThat(sendToBob()).as("sent: %s", stderr()).isEqualTo(Exit.OK);
        assertThat(sentInto()).isEqualTo(expected);
        assertThat(read()).containsExactly("%21a%3Amatrix.test", "%21b%3Amatrix.test");
    }

    /**
     * Harm guarded: the person's joining, an edit of an old line or a line they took back is taken
     * for where they asked last; none is handed to the task as a line either.
     */
    @Test
    void onlyALineThePersonSaysCounts() {
        final String old =
                "{\"chunk\":[{\"type\":\"m.room.member\",\"sender\":\"@bob:matrix.test\","
                        + "\"origin_server_ts\":999,\"content\":{\"membership\":\"join\"}},"
                        + "{\"type\":\"m.room.message\",\"sender\":\"@bob:matrix.test\","
                        + "\"origin_server_ts\":500,\"content\":{\"msgtype\":\"m.text\","
                        + "\"body\":\"* fixed\",\"m.relates_to\":{\"rel_type\":\"m.replace\"}}},"
                        + "{\"type\":\"m.room.message\",\"sender\":\"@bob:matrix.test\","
                        + "\"origin_server_ts\":400,\"content\":{}},"
                        + "{\"type\":\"m.room.message\",\"sender\":\"@bob:matrix.test\","
                        + "\"origin_server_ts\":350,\"content\":{\"msgtype\":\"m.image\","
                        + "\"body\":\"a.png\"}},"
                        + "{\"type\":\"m.room.message\",\"sender\":\"@t.alice:matrix.test\","
                        + "\"origin_server_ts\":900,\"content\":{\"msgtype\":\"m.text\","
                        + "\"body\":\"x\"}}]}";
        homeserver
                .answer(200, WHOAMI)
                .answer(200, chats("!a", "!b"))
                .answer(200, joined("!a", "!b"))
                .answer(200, JOIN)
                .answer(200, JOIN)
                .answer(200, page(BOB, 300L))
                .answer(200, old)
                .answer(200, "{\"event_id\":\"$e\"}");

        assertThat(sendToBob()).as("sent: %s", stderr()).isEqualTo(Exit.OK);
        assertThat(sentInto()).isEqualTo("%21a%3Amatrix.test");
    }

    /**
     * Harm guarded: with no line of the person's to go by, the answer goes to the oldest chat, or
     * into one the task cannot read, where it fails.
     */
    @Test
    void withNoLineOfThePersonsTheAnswerGoesIntoTheReadableChatRememberedLast() {
        homeserver
                .answer(200, WHOAMI)
                .answer(200, chats("!a", "!b", "!c"))
                .answer(200, joined("!a", "!b", "!c"))
                .answer(200, JOIN)
                .answer(200, "{\"membership\":\"invite\"}")
                .answer(200, JOIN)
                .answer(200, page(TASK, 900L))
                .answer(200, "{\"chunk\":[]}")
                .answer(403, "{\"errcode\":\"M_FORBIDDEN\"}")
                .answer(200, "{\"event_id\":\"$e\"}");

        assertThat(sendToBob()).as("sent: %s", stderr()).isEqualTo(Exit.OK);
        assertThat(sentInto()).isEqualTo("%21b%3Amatrix.test");
    }

    /**
     * Harm guarded: an answer written into chats the task cannot read, never reaching the person.
     */
    @Test
    void whenNoOpenChatCanBeReadANewOneIsOpened() {
        homeserver
                .answer(200, WHOAMI)
                .answer(200, chats("!a", "!b"))
                .answer(200, joined("!a", "!b"))
                .answer(200, JOIN)
                .answer(200, JOIN)
                .answer(403, "{\"errcode\":\"M_FORBIDDEN\"}")
                .answer(404, "{\"errcode\":\"M_NOT_FOUND\"}")
                .answer(200, "{\"room_id\":\"!n:matrix.test\"}")
                .answer(200, chats("!a", "!b"))
                .answer(200, "{}")
                .answer(200, chats("!a", "!b", "!n"))
                .answer(200, "{\"event_id\":\"$e\"}");

        assertThat(sendToBob()).as("sent: %s", stderr()).isEqualTo(Exit.OK);
        assertThat(sentInto()).isEqualTo("%21n%3Amatrix.test");
    }

    /** Harm guarded: an answer written into a chat the person has left, where nobody reads it. */
    @Test
    void aChatThePersonLeftIsNeverWrittenNorRead() {
        homeserver
                .answer(200, WHOAMI)
                .answer(200, chats("!a", "!b", "!c"))
                .answer(200, joined("!a", "!b", "!c"))
                .answer(200, "{\"membership\":\"leave\"}")
                .answer(200, JOIN)
                .answer(200, JOIN)
                .answer(200, page(BOB, 100L))
                .answer(200, page(TASK, 900L))
                .answer(200, "{\"event_id\":\"$e\"}");

        assertThat(sendToBob()).as("sent: %s", stderr()).isEqualTo(Exit.OK);
        assertThat(sentInto()).isEqualTo("%21b%3Amatrix.test");
        assertThat(read())
                .as("the left chat's messages are never read")
                .containsExactly("%21b%3Amatrix.test", "%21c%3Amatrix.test");
    }

    /**
     * Harm guarded: an answer written into a chat the task left or was taken out of, failing there
     * with 77 while another chat with the person is open.
     */
    @Test
    void aChatTheTaskIsNoLongerInIsNeverWrittenNorRead() {
        homeserver
                .answer(200, WHOAMI)
                .answer(200, chats("!a", "!b"))
                .answer(200, joined("!a"))
                .answer(200, JOIN)
                .answer(200, "{\"event_id\":\"$e\"}");

        assertThat(sendToBob()).as("sent: %s", stderr()).isEqualTo(Exit.OK);
        assertThat(sentInto()).isEqualTo("%21a%3Amatrix.test");
        assertThat(homeserver.requests())
                .as("nothing asked of the chat the task left")
                .noneMatch(r -> r.rawPath().contains("%21b%3Amatrix.test"));
    }

    /**
     * Harm guarded: one chat whose member state the task may not read stopping the answer before
     * the other chats with the person are looked at.
     */
    @ParameterizedTest
    @CsvSource({"403, M_FORBIDDEN", "404, M_NOT_FOUND"})
    void aChatWhoseMembersCannotBeReadIsPassedOver(final int status, final String errcode) {
        homeserver
                .answer(200, WHOAMI)
                .answer(200, chats("!a", "!b"))
                .answer(200, joined("!a", "!b"))
                .answer(status, "{\"errcode\":\"" + errcode + "\"}")
                .answer(200, JOIN)
                .answer(200, "{\"event_id\":\"$e\"}");

        assertThat(sendToBob()).as("sent: %s", stderr()).isEqualTo(Exit.OK);
        assertThat(sentInto()).isEqualTo("%21b%3Amatrix.test");
    }

    /** Harm guarded: a busy, refusing or garbled homeserver taken for a chat nobody wrote in. */
    @ParameterizedTest
    @CsvSource(
            delimiter = '|',
            value = {
                "429 | {\"errcode\":\"M_LIMIT_EXCEEDED\"} | 75",
                "503 | {\"errcode\":\"M_UNKNOWN\"} | 75",
                "401 | {\"errcode\":\"M_UNKNOWN_TOKEN\"} | 77",
                "400 | {\"errcode\":\"M_BAD_JSON\"} | 76",
                "200 | {} | 76",
                "200 | {\"chunk\":[ | 76"
            })
    void anUnclearChatStopsTheSendUnsent(final int status, final String body, final int exit) {
        homeserver
                .answer(200, WHOAMI)
                .answer(200, chats("!a", "!b"))
                .answer(200, joined("!a", "!b"))
                .answer(200, JOIN)
                .answer(200, JOIN)
                .answer(200, page(BOB, 100L))
                .answer(status, body);

        assertThat(sendToBob()).as(this::stderr).isEqualTo(exit);
        assertThat(homeserver.requests()).noneMatch(r -> r.rawPath().contains("/send/"));
    }

    /** Harm guarded: a central send blames the person for a homeserver that named nobody. */
    @Test
    void centralSendWithoutWhoseTokenIsAProtocolFault() {
        homeserver.answer(200, "{}");

        final int exit =
                send(
                        Map.of(
                                Config.HOMESERVER,
                                homeserver.url(),
                                Config.ACCESS_TOKEN,
                                TOKEN,
                                Config.LOGIN,
                                "alice"),
                        message.toString(),
                        signature.toString(),
                        "--to",
                        "@bob:matrix.test");

        assertThat(exit).isEqualTo(Exit.PROTOCOL);
    }

    @Nested
    @DisplayName("When the homeserver cannot take it now")
    class Temporary {

        @ParameterizedTest(name = "status {0}")
        @CsvSource({"429", "500", "502", "503"})
        @DisplayName("it exits 75, so Porelo keeps the message and retries")
        void temporaryStatus(final int status) {
            homeserver.answer(status, "{\"errcode\":\"M_LIMIT_EXCEEDED\",\"error\":\"slow down\"}");

            assertThat(sendMessage()).isEqualTo(Exit.TEMPORARY);
        }

        @Test
        @DisplayName("an unreachable homeserver is temporary too")
        void unreachable() throws IOException {
            final int closedPort;
            try (ServerSocket socket = new ServerSocket(0)) {
                closedPort = socket.getLocalPort();
            }
            final int exit =
                    send(
                            Map.of(
                                    Config.HOMESERVER,
                                    "http://127.0.0.1:" + closedPort,
                                    Config.ACCESS_TOKEN,
                                    TOKEN),
                            message.toString(),
                            signature.toString(),
                            "--to",
                            ROOM);

            assertThat(exit).isEqualTo(Exit.TEMPORARY);
            assertThat(stderr()).contains("not reachable");
        }
    }

    @Nested
    @DisplayName("When the message is refused")
    class Refused {

        @ParameterizedTest(name = "status {0}")
        @CsvSource({"401, M_UNKNOWN_TOKEN", "403, M_FORBIDDEN"})
        @DisplayName(
                "a refused token or a room the account is not in is 77, with the homeserver's reason and never the token")
        void notPermitted(final int status, final String errcode) {
            homeserver.answer(status, "{\"errcode\":\"" + errcode + "\",\"error\":\"no\"}");

            assertThat(sendMessage()).isEqualTo(Exit.NOT_PERMITTED);
            assertThat(stderr()).contains(errcode).doesNotContain(TOKEN);
            assertThat(outBytes.size())
                    .as("nothing on stdout when nothing was handed over")
                    .isZero();
        }

        @Test
        @DisplayName("a message too large for one event is 65")
        void tooLarge() {
            homeserver.answer(413, "{\"errcode\":\"M_TOO_LARGE\",\"error\":\"too large\"}");

            assertThat(sendMessage()).isEqualTo(Exit.DATA);
        }

        @Test
        @DisplayName(
                "a message larger than describe's max_bytes is 65, and nothing reaches the homeserver; one at the limit goes")
        void largerThanDescribed() throws IOException {
            homeserver.answer(200, "{\"event_id\":\"$abc\"}");
            Files.writeString(message, "a".repeat(Describe.MAX_BYTES), StandardCharsets.UTF_8);
            assertThat(sendMessage()).isEqualTo(Exit.OK);

            Files.writeString(message, "a".repeat(Describe.MAX_BYTES + 1), StandardCharsets.UTF_8);
            assertThat(sendMessage()).isEqualTo(Exit.DATA);
            assertThat(homeserver.requests()).hasSize(1);
        }

        @Test
        @DisplayName("a room id longer than the specification allows is 64")
        void roomIdTooLong() {
            assertThat(
                            send(
                                    message.toString(),
                                    signature.toString(),
                                    "--to",
                                    "!" + "a".repeat(255)))
                    .isEqualTo(Exit.USAGE);
            assertThat(homeserver.requests()).isEmpty();
        }

        @Test
        @DisplayName("an answer naming no event is 76")
        void noEventId() {
            homeserver.answer(200, "{}");

            assertThat(sendMessage()).isEqualTo(Exit.PROTOCOL);
        }

        @Test
        @DisplayName("a message that is not UTF-8 is 65, and nothing reaches the homeserver")
        void notUtf8() throws IOException {
            Files.write(message, new byte[] {'h', 'i', (byte) 0xc3, (byte) 0x28});

            assertThat(sendMessage()).isEqualTo(Exit.DATA);
            assertThat(homeserver.requests()).isEmpty();
        }

        @ParameterizedTest(name = "--to {0}")
        @CsvSource({"#alias:matrix.test", "room:matrix.test", "!", "'!room with space'", "''"})
        @DisplayName("an address that is not a room id is 64, and nothing reaches the homeserver")
        void notARoomId(final String to) {
            assertThat(send(message.toString(), signature.toString(), "--to", to))
                    .isEqualTo(Exit.USAGE);
            assertThat(homeserver.requests()).isEmpty();
        }

        @Test
        @DisplayName("arguments not in the contract's shape are 64")
        void wrongArguments() {
            assertThat(send(message.toString(), "--to", ROOM)).isEqualTo(Exit.USAGE);
            assertThat(send(message.toString(), signature.toString(), "--at", ROOM))
                    .isEqualTo(Exit.USAGE);
            assertThat(
                            send(
                                    message.toString(),
                                    signature.toString(),
                                    "--shown",
                                    message.toString()))
                    .isEqualTo(Exit.USAGE);
            assertThat(send(message.toString(), signature.toString(), "--to", ROOM, "--to", ROOM))
                    .isEqualTo(Exit.USAGE);
            assertThat(send(message.toString(), signature.toString(), "--to", ROOM, "--shown"))
                    .isEqualTo(Exit.USAGE);
            assertThat(homeserver.requests()).isEmpty();
        }

        @Test
        @DisplayName("without the homeserver or the token in the environment it is 78")
        void missingConfiguration() {
            assertThat(
                            send(
                                    Map.of(Config.HOMESERVER, homeserver.url()),
                                    message.toString(),
                                    signature.toString(),
                                    "--to",
                                    ROOM))
                    .isEqualTo(Exit.CONFIG);
            assertThat(
                            send(
                                    Map.of(Config.ACCESS_TOKEN, TOKEN),
                                    message.toString(),
                                    signature.toString(),
                                    "--to",
                                    ROOM))
                    .isEqualTo(Exit.CONFIG);
            assertThat(stderr())
                    .contains(Config.HOMESERVER, Config.ACCESS_TOKEN)
                    .doesNotContain(TOKEN);
        }
    }

    private static byte[] bytes(final String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    /** Harm guarded: another message under the same id is dropped by the homeserver as a retry. */
    @Test
    void aTransactionIsThePairAndNothingElse() {
        final String id = Send.transactionId(bytes("message"), bytes("signature"));

        assertThat(Send.transactionId(bytes("message"), bytes("signature")))
                .as("the same pair")
                .isEqualTo(id);
        assertThat(Send.transactionId(bytes("massage"), bytes("signature")))
                .as("another message of the same length")
                .isNotEqualTo(id);
        assertThat(Send.transactionId(bytes("message"), bytes("other")))
                .as("another signature")
                .isNotEqualTo(id);
        assertThat(Send.transactionId(bytes("ab"), bytes("c")))
                .as("where the message ends counts")
                .isNotEqualTo(Send.transactionId(bytes("a"), bytes("bc")));
    }

    /** Harm guarded: a long line to a person is refused by the homeserver, and the message lost. */
    @Test
    void aDirectMessageWithALongLineStillFits() throws IOException {
        homeserver
                .answer(200, "{\"user_id\":\"@t.alice:matrix.test\"}")
                .answer(200, "{\"@bob:matrix.test\":[\"!d:matrix.test\"]}")
                .answer(200, "{\"joined_rooms\":[\"!d:matrix.test\"]}")
                .answer(200, "{\"membership\":\"join\"}")
                .answer(200, "{\"event_id\":\"$e\"}");
        final Path full =
                Files.writeString(dir.resolve("full"), "\u0001".repeat(Describe.MAX_BYTES));
        final Path line = Files.writeString(dir.resolve("line"), "line ".repeat(20_000));

        final int exit =
                send(
                        Map.of(
                                Config.HOMESERVER,
                                homeserver.url(),
                                Config.ACCESS_TOKEN,
                                TOKEN,
                                Config.LOGIN,
                                "alice"),
                        full.toString(),
                        signature.toString(),
                        "--to",
                        "@bob:matrix.test",
                        "--shown",
                        line.toString());

        assertThat(exit).as("sent: %s", stderr()).isEqualTo(Exit.OK);
        final byte[] sent = homeserver.requests().getLast().body().getBytes(StandardCharsets.UTF_8);
        assertThat(sent).as("the event").hasSizeLessThanOrEqualTo(Send.CONTENT_BYTES);
        assertThat(MatrixClient.JSON.readTree(sent).path("body").asText())
                .as("the line, cut")
                .startsWith("line line")
                .contains("(cut; ");
    }

    /** Harm guarded: a room id the homeserver can hold is refused, or one it cannot is sent. */
    @ParameterizedTest(name = "{0} bytes")
    @CsvSource({"255, 0", "256, 64"})
    void aRoomIdIsTakenUpToItsLimit(final int bytes, final int exit) {
        homeserver.answer(200, "{\"event_id\":\"$abc\"}");
        final String id = "!" + "a".repeat(bytes - 1);

        assertThat(send(message.toString(), signature.toString(), "--to", id))
                .as("%d bytes: %s", bytes, stderr())
                .isEqualTo(exit);
    }
}
