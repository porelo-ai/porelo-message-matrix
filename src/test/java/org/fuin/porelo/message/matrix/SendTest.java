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
}
