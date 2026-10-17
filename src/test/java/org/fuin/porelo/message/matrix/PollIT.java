package org.fuin.porelo.message.matrix;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code poll} against the real homeserver the transport is built for, on loopback in podman. Each
 * test gets its own homeserver, so what one delivers cannot leak into another.
 */
class PollIT {

    private static final byte[] SIGNATURE = {0, 1, 2, (byte) 0xfe, (byte) 0xff, '\n', '-', '-'};

    @TempDir private Path dir;

    private Tuwunel tuwunel;

    private Tuwunel.Account alice;

    private Tuwunel.Account bob;

    private String room;

    private Path inbound;

    private final ByteArrayOutputStream errBytes = new ByteArrayOutputStream();

    @BeforeEach
    void setUp() throws IOException, InterruptedException {
        tuwunel = Tuwunel.start();
        alice = tuwunel.register("alice");
        bob = tuwunel.register("bob");
        room = tuwunel.createRoom(alice, bob);
        inbound = Files.createDirectories(dir.resolve("inbound"));
    }

    @AfterEach
    void tearDown() throws IOException, InterruptedException {
        // A start that failed leaves none to close, and must not hide why it failed.
        if (tuwunel != null) {
            tuwunel.close();
        }
    }

    private Map<String, String> env(final Tuwunel.Account account) {
        return Map.of(
                Config.HOMESERVER,
                tuwunel.url(),
                Config.ACCESS_TOKEN,
                account.accessToken(),
                "XDG_STATE_HOME",
                dir.resolve("state").toString());
    }

    private void send(final String message) throws IOException {
        send(message, SIGNATURE);
    }

    private void send(final String message, final byte[] signature) throws IOException {
        final Path file = Files.writeString(dir.resolve("out"), message, StandardCharsets.UTF_8);
        final Path sig = Files.write(dir.resolve("out.sig"), signature);
        assertThat(
                        Transport.run(
                                new String[] {
                                    "send", file.toString(), sig.toString(), "--to", room
                                },
                                env(alice),
                                new PrintStream(OutputStream.nullOutputStream()),
                                new PrintStream(errBytes, true, StandardCharsets.UTF_8)))
                .as(this::stderr)
                .isEqualTo(Exit.OK);
    }

    private int poll() {
        return poll(false);
    }

    private int poll(final String... extra) {
        final String[] args = new String[extra.length + 3];
        args[0] = "poll";
        args[1] = "--into";
        args[2] = inbound.toString();
        System.arraycopy(extra, 0, args, 3, extra.length);
        return Transport.run(
                args,
                env(bob),
                new PrintStream(OutputStream.nullOutputStream()),
                new PrintStream(errBytes, true, StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName(
            "poll --wait answers as soon as a message arrives, and waits as long as asked when none does")
    void waitingPollAnswersWhenAMessageArrives() throws Exception {
        assertThat(poll("--persons")).as(this::stderr).isEqualTo(Exit.OK);

        final long quiet = System.nanoTime();
        assertThat(poll("--persons", "--wait", "3")).as(this::stderr).isEqualTo(Exit.OK);
        assertThat(java.time.Duration.ofNanos(System.nanoTime() - quiet))
                .as("waited for news that never came")
                .isGreaterThanOrEqualTo(java.time.Duration.ofMillis(2500));
        assertThat(persons()).isEmpty();

        final Thread person =
                Thread.ofVirtual()
                        .start(
                                () -> {
                                    try {
                                        Thread.sleep(1500);
                                        tuwunel.say(alice, room, "said while the poll waits");
                                    } catch (final Exception ex) {
                                        throw new IllegalStateException(ex);
                                    }
                                });
        final long start = System.nanoTime();
        assertThat(poll("--wait", "25", "--persons")).as(this::stderr).isEqualTo(Exit.OK);
        final java.time.Duration took = java.time.Duration.ofNanos(System.nanoTime() - start);
        person.join();
        assertThat(took)
                .as("answered when the message came, not when the wait ran out")
                .isLessThan(java.time.Duration.ofSeconds(15));
        assertThat(persons())
                .singleElement()
                .satisfies(
                        p ->
                                assertThat(p.path("body").asText())
                                        .isEqualTo("said while the poll waits"));
    }

    @Test
    @DisplayName(
            "poll --wait goes on waiting through an answer with nothing to hand over, such as a read receipt")
    void waitingPollIsNotEndedByAReadReceipt() throws Exception {
        tuwunel.say(alice, room, "said before the wait");
        assertThat(poll("--persons")).as(this::stderr).isEqualTo(Exit.OK);
        final String read = tuwunel.messages(bob, room).getLast().path("event_id").asText();
        empty();

        // A read receipt is filtered out of the answer, yet it ends the homeserver's wait: the
        // answer comes empty.
        final Thread others =
                Thread.ofVirtual()
                        .start(
                                () -> {
                                    try {
                                        Thread.sleep(1000);
                                        tuwunel.read(bob, room, read);
                                        Thread.sleep(3000);
                                        tuwunel.say(alice, room, "said after the receipt");
                                    } catch (final Exception ex) {
                                        throw new IllegalStateException(ex);
                                    }
                                });
        final long start = System.nanoTime();
        assertThat(poll("--wait", "25", "--persons")).as(this::stderr).isEqualTo(Exit.OK);
        final java.time.Duration took = java.time.Duration.ofNanos(System.nanoTime() - start);
        others.join();
        assertThat(persons())
                .as("answered with the message, not with the receipt's empty answer after %s", took)
                .singleElement()
                .satisfies(
                        p ->
                                assertThat(p.path("body").asText())
                                        .isEqualTo("said after the receipt"));
        assertThat(took)
                .as("answered when the message came, not when the wait ran out")
                .isLessThan(java.time.Duration.ofSeconds(15));
    }

    private int poll(final boolean persons) {
        return Transport.run(
                persons
                        ? new String[] {"poll", "--into", inbound.toString(), "--persons"}
                        : new String[] {"poll", "--into", inbound.toString()},
                env(bob),
                new PrintStream(OutputStream.nullOutputStream()),
                new PrintStream(errBytes, true, StandardCharsets.UTF_8));
    }

    /**
     * What is in inbound: each message by its text, mapped to its signature's bytes, or null
     * without one.
     */
    private Map<String, byte @Nullable []> delivered() throws IOException {
        final Map<String, byte @Nullable []> messages = new TreeMap<>();
        try (Stream<Path> files = Files.list(inbound)) {
            for (final Path file : files.toList()) {
                final String name = file.getFileName().toString();
                assertThat(name)
                        .as(
                                "every file in inbound is a message or its signature, named as the filter takes names")
                        .matches(
                                "[0-9]{8}T[0-9]{9}--matrix-(person-)?[0-9a-f-]{36}\\.json(\\.sig|\\.sender)?");
                if (name.endsWith(".json") && !name.contains("--matrix-person-")) {
                    assertThat(file.resolveSibling(name + ".sender"))
                            .as("every Porelo message names its sender's account")
                            .content(StandardCharsets.UTF_8)
                            .isEqualTo(alice.userId());
                    final Path sig = file.resolveSibling(name + ".sig");
                    messages.put(
                            Files.readString(file, StandardCharsets.UTF_8),
                            Files.exists(sig) ? Files.readAllBytes(sig) : null);
                }
            }
        }
        return messages;
    }

    private void empty() throws IOException {
        try (Stream<Path> files = Files.list(inbound)) {
            for (final Path file : files.toList()) {
                Files.delete(file);
            }
        }
    }

    private String stderr() {
        return errBytes.toString(StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("Messages arrive in inbound byte for byte, each beside its signature")
    void deliversMessagesWithSignatures() throws IOException {
        send("Grüße ✓\n\tline two\r\n");
        send("second\n");

        assertThat(poll()).as(this::stderr).isEqualTo(Exit.OK);

        assertThat(delivered())
                .containsOnlyKeys("Grüße ✓\n\tline two\r\n", "second\n")
                .allSatisfy((message, signature) -> assertThat(signature).isEqualTo(SIGNATURE));
    }

    /** The position files poll keeps, one per account and homeserver. */
    private List<Path> positions() throws IOException {
        try (Stream<Path> files =
                Files.list(dir.resolve("state").resolve("porelo/transports/matrix"))) {
            return files.filter(f -> f.getFileName().toString().endsWith(".since")).toList();
        }
    }

    @Test
    @DisplayName(
            "A position from before the homeserver was reset is refused with 78, not answered with nothing;"
                    + " removed, the rooms are polled from their start")
    void resetHomeserverIsSaid() throws Exception {
        send("before\n");
        assertThat(poll()).as(this::stderr).isEqualTo(Exit.OK);
        empty();

        tuwunel.reset();
        alice = tuwunel.register("alice");
        bob = tuwunel.register("bob");
        room = tuwunel.createRoom(alice, bob);
        send("after the reset\n");

        assertThat(poll()).isEqualTo(Exit.CONFIG);
        assertThat(stderr()).contains("the homeserver was reset");
        assertThat(delivered()).isEmpty();

        for (final Path position : positions()) {
            Files.delete(position);
        }
        assertThat(poll()).as(this::stderr).isEqualTo(Exit.OK);
        assertThat(delivered()).containsOnlyKeys("after the reset\n");
    }

    @Test
    @DisplayName(
            "A position kept before the rooms were kept beside it is taken, and the rooms are added")
    void positionWithoutRoomsIsTaken() throws IOException {
        send("first\n");
        assertThat(poll()).as(this::stderr).isEqualTo(Exit.OK);
        final Path position = positions().getFirst();
        assertThat(Files.readAllLines(position, StandardCharsets.UTF_8))
                .as("the position, then the room")
                .hasSize(2)
                .last()
                .isEqualTo(room);
        Files.writeString(
                position, Files.readAllLines(position, StandardCharsets.UTF_8).getFirst() + "\n");
        empty();
        send("second\n");

        assertThat(poll()).as(this::stderr).isEqualTo(Exit.OK);

        assertThat(delivered()).containsOnlyKeys("second\n");
        assertThat(Files.readAllLines(position, StandardCharsets.UTF_8)).last().isEqualTo(room);
    }

    @Test
    @DisplayName("A second poll delivers only what arrived since the first")
    void deliversOnlyWhatIsNew() throws IOException {
        send("first\n");
        assertThat(poll()).as(this::stderr).isEqualTo(Exit.OK);
        empty();

        assertThat(poll()).as(this::stderr).isEqualTo(Exit.OK);
        assertThat(delivered()).isEmpty();

        send("later\n");
        assertThat(poll()).as(this::stderr).isEqualTo(Exit.OK);
        assertThat(delivered()).containsOnlyKeys("later\n");
    }

    @Test
    @DisplayName(
            "Without --persons a person's chat is skipped as before, and stderr says how to have it")
    void withoutPersonsChatIsSkipped() throws IOException, InterruptedException {
        tuwunel.say(alice, room, "typed in a client");

        assertThat(poll()).as(this::stderr).isEqualTo(Exit.OK);

        assertThat(persons()).isEmpty();
        assertThat(stderr())
                .contains(
                        "skipped 1 room message(s): not a Porelo message (poll --persons",
                        "from " + alice.userId());
    }

    @Test
    @DisplayName(
            "With --persons, a person's chat that names nobody is handed over all the same, meant for nobody in particular")
    void chatIsSkippedAndSaidSo() throws IOException, InterruptedException {
        tuwunel.say(alice, room, "typed in a client");
        send("{\"a\":\"porelo message\"}");

        assertThat(poll(true)).as(this::stderr).isEqualTo(Exit.OK);

        assertThat(delivered()).containsOnlyKeys("{\"a\":\"porelo message\"}");
        assertThat(persons())
                .singleElement()
                .satisfies(
                        p -> {
                            assertThat(p.path("sender").asText()).isEqualTo(alice.userId());
                            assertThat(p.path("body").asText()).isEqualTo("typed in a client");
                            assertThat(p.path("to")).isEmpty();
                        });
        assertThat(stderr()).doesNotContain("skipped");
    }

    private ObjectNode text(final String body) {
        return MatrixClient.JSON.createObjectNode().put("msgtype", "m.text").put("body", body);
    }

    @Test
    @DisplayName(
            "A person's message is handed over for the accounts it mentions, and for the sender of what it replies to")
    void personsMessageForWhomItNames() throws IOException, InterruptedException {
        final Tuwunel.Account carol = tuwunel.register("carol");
        final String talk = tuwunel.createRoom(alice, bob, carol);
        final String alicesWords = tuwunel.sayContent(alice, talk, text("from alice"));
        final ObjectNode mention = text("alice: please look at this");
        mention.putObject("m.mentions").putArray("user_ids").add(alice.userId());
        final String mentioned = tuwunel.sayContent(carol, talk, mention);
        final ObjectNode reply = text("> from alice\n\nand an answer to it");
        reply.putObject("m.relates_to").putObject("m.in_reply_to").put("event_id", alicesWords);
        final String replied = tuwunel.sayContent(carol, talk, reply);
        final ObjectNode pill = text("alice: a pill");
        pill.put("format", "org.matrix.custom.html")
                .put(
                        "formatted_body",
                        "<a href=\"https://matrix.to/#/" + alice.userId() + "\">alice</a>: a pill");
        tuwunel.sayContent(carol, talk, pill);

        assertThat(poll(true)).as(this::stderr).isEqualTo(Exit.OK);

        assertThat(persons())
                .filteredOn(p -> !p.path("to").isEmpty())
                .hasSize(3)
                .allSatisfy(
                        p -> {
                            assertThat(p.path("sender").asText()).isEqualTo(carol.userId());
                            assertThat(p.path("to"))
                                    .extracting(JsonNode::asText)
                                    .containsExactly(alice.userId());
                            assertThat(p.path("at").asText()).endsWith("Z");
                        });
        assertThat(persons())
                .anySatisfy(
                        p -> {
                            assertThat(p.path("eventId").asText()).isEqualTo(mentioned);
                            assertThat(p.path("body").asText())
                                    .isEqualTo("alice: please look at this");
                            assertThat(p.has("replyTo")).isFalse();
                        })
                .anySatisfy(
                        p -> {
                            assertThat(p.path("eventId").asText()).isEqualTo(replied);
                            assertThat(p.path("replyTo").asText()).isEqualTo(alicesWords);
                        });
        assertThat(persons())
                .filteredOn(p -> p.path("to").isEmpty())
                .singleElement()
                .satisfies(p -> assertThat(p.path("sender").asText()).isEqualTo(alice.userId()));
    }

    @Test
    @DisplayName(
            "A notice and an edit are not handed over, each said why; naming only its writer and the poller names nobody")
    void notAPersonsMessageForAnybody() throws IOException, InterruptedException {
        final Tuwunel.Account carol = tuwunel.register("carol");
        final String talk = tuwunel.createRoom(alice, bob, carol);
        final ObjectNode notice =
                MatrixClient.JSON
                        .createObjectNode()
                        .put("msgtype", "m.notice")
                        .put("body", "a bot");
        notice.putObject("m.mentions").putArray("user_ids").add(alice.userId());
        tuwunel.sayContent(carol, talk, notice);
        final String first = tuwunel.sayContent(carol, talk, text("first"));
        final ObjectNode edit = text("* second");
        edit.putObject("m.relates_to").put("rel_type", "m.replace").put("event_id", first);
        edit.putObject("m.mentions").putArray("user_ids").add(alice.userId());
        tuwunel.sayContent(carol, talk, edit);
        final ObjectNode selves = text("me and the poller");
        selves.putObject("m.mentions").putArray("user_ids").add(carol.userId()).add(bob.userId());
        tuwunel.sayContent(carol, talk, selves);

        assertThat(poll(true)).as(this::stderr).isEqualTo(Exit.OK);

        assertThat(persons())
                .extracting(p -> p.path("body").asText())
                .containsExactly("first", "me and the poller");
        assertThat(persons()).allSatisfy(p -> assertThat(p.path("to")).isEmpty());
        assertThat(stderr())
                .contains(
                        "skipped 2 room message(s)",
                        "no text (m.notice)",
                        "an edit of an earlier message");
    }

    /** The persons' messages handed over, each as the JSON Porelo reads. */
    private List<JsonNode> persons() throws IOException {
        final List<JsonNode> found = new java.util.ArrayList<>();
        try (Stream<Path> files = Files.list(inbound)) {
            for (final Path file : files.sorted().toList()) {
                if (file.getFileName()
                        .toString()
                        .matches("[0-9]{8}T[0-9]{9}--matrix-person-[0-9a-f-]{36}\\.json")) {
                    assertThat(Files.exists(file.resolveSibling(file.getFileName() + ".sig")))
                            .as("a person's message has no .sig")
                            .isFalse();
                    found.add(MatrixClient.JSON.readTree(file.toFile()));
                }
            }
        }
        return found;
    }

    @Test
    @DisplayName(
            "An event of the earlier form, the message in its body, is not delivered, and is said on stderr")
    void earlierFormIsSkipped() throws IOException, InterruptedException {
        tuwunel.sayInTheEarlierForm(alice, room, "{\"a\":\"earlier\"}");
        send("{\"a\":\"now\"}");

        assertThat(poll(true)).as(this::stderr).isEqualTo(Exit.OK);

        assertThat(delivered()).containsOnlyKeys("{\"a\":\"now\"}");
        assertThat(persons()).isEmpty();
        assertThat(stderr())
                .contains(
                        "skipped 1 room message(s): a Porelo message of the earlier form",
                        "from " + alice.userId());
    }

    @Test
    @DisplayName(
            "A Porelo message that came without a signature arrives without a .sig, for Porelo to hold")
    void unsignedPoreloMessageHasNoSig() throws IOException {
        send("unsigned\n", new byte[0]);

        assertThat(poll()).as(this::stderr).isEqualTo(Exit.OK);

        assertThat(delivered())
                .containsOnlyKeys("unsigned\n")
                .allSatisfy((message, signature) -> assertThat(signature).isNull());
    }

    /**
     * Harm guarded: a sync cut short is paged back to the last position, and no further; paged past
     * it, every message the room ever had is delivered again.
     */
    @Test
    @DisplayName(
            "More messages than one sync returns are all delivered, none skipped and none from before")
    void nothingSkippedWhenTheSyncIsCutShort() throws IOException {
        for (int i = 0; i < 5; i++) {
            send("before " + i + "\n");
        }
        assertThat(poll()).as(this::stderr).isEqualTo(Exit.OK);
        empty();
        final java.util.Set<String> sent = new java.util.HashSet<>();
        for (int i = 0; i < 150; i++) {
            send("message " + i + "\n");
            sent.add("message " + i + "\n");
        }

        assertThat(poll()).as(this::stderr).isEqualTo(Exit.OK);

        assertThat(delivered().keySet()).as("what was delivered").isEqualTo(sent);
    }
}
