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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A task's direct chats with the people of its project, against the real homeserver: a chat a
 * person opens is taken only from somebody who shares a room with the task's account, what is said
 * there is handed over as meant for the task alone, and the task writes back into it - or opens
 * one.
 */
class DirectIT {

    @TempDir private Path dir;

    private Tuwunel tuwunel;

    /** The task's account. */
    private Tuwunel.Account task;

    /** A person in the project's room. */
    private Tuwunel.Account alice;

    private String project;

    private Path inbound;

    private final ByteArrayOutputStream errBytes = new ByteArrayOutputStream();

    @BeforeEach
    void setUp() throws IOException, InterruptedException {
        tuwunel = Tuwunel.start();
        task = tuwunel.register("task");
        alice = tuwunel.register("alice");
        project = tuwunel.createRoom(task, alice);
        inbound = Files.createDirectories(dir.resolve("inbound"));
    }

    @AfterEach
    void tearDown() throws IOException, InterruptedException {
        tuwunel.close();
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

    private String stderr() {
        return errBytes.toString(StandardCharsets.UTF_8);
    }

    private int pollDirect() {
        return Transport.run(
                new String[] {"poll", "--into", inbound.toString(), "--direct"},
                env(task),
                new PrintStream(OutputStream.nullOutputStream()),
                new PrintStream(errBytes, true, StandardCharsets.UTF_8));
    }

    private ObjectNode text(final String body) {
        return MatrixClient.JSON.createObjectNode().put("msgtype", "m.text").put("body", body);
    }

    private List<JsonNode> persons() throws IOException {
        final List<JsonNode> found = new ArrayList<>();
        try (Stream<Path> files = Files.list(inbound)) {
            for (final Path file : files.sorted().toList()) {
                if (file.getFileName()
                        .toString()
                        .matches("[0-9]{8}T[0-9]{9}--matrix-person-[0-9a-f-]{36}\\.json")) {
                    found.add(MatrixClient.JSON.readTree(file.toFile()));
                }
            }
        }
        return found;
    }

    @Test
    @DisplayName(
            "A chat a person of the project opens is joined, and what they say there is handed over as direct, for the task alone")
    void personOpensAChat() throws IOException, InterruptedException {
        final String chat = tuwunel.openDirect(alice, task);
        tuwunel.sayContent(alice, project, text("said in the project's room"));

        assertThat(pollDirect()).as(this::stderr).isEqualTo(Exit.OK);
        assertThat(tuwunel.membership(alice, chat, task.userId())).isEqualTo("join");

        final ObjectNode words = text("writer, a word for you alone");
        words.putObject("m.mentions").putArray("user_ids").add("@someone:else");
        tuwunel.sayContent(alice, chat, words);
        assertThat(pollDirect()).as(this::stderr).isEqualTo(Exit.OK);

        assertThat(persons())
                .singleElement()
                .satisfies(
                        p -> {
                            assertThat(p.path("sender").asText()).isEqualTo(alice.userId());
                            assertThat(p.path("direct").asBoolean(false)).isTrue();
                            assertThat(p.path("to"))
                                    .extracting(JsonNode::asText)
                                    .containsExactly(task.userId());
                            assertThat(p.path("body").asText())
                                    .isEqualTo("writer, a word for you alone");
                        });
    }

    @Test
    @DisplayName(
            "A chat opened by somebody who shares no room with the task's account is declined, and said so")
    void strangerIsDeclined() throws IOException, InterruptedException {
        final Tuwunel.Account stranger = tuwunel.register("stranger");
        final String chat = tuwunel.openDirect(stranger, task);

        assertThat(pollDirect()).as(this::stderr).isEqualTo(Exit.OK);

        assertThat(tuwunel.membership(stranger, chat, task.userId())).isEqualTo("leave");
        assertThat(stderr())
                .contains(
                        "from " + stranger.userId() + " who shares no room with this account",
                        "declined");
        assertThat(persons()).isEmpty();
    }

    @Test
    @DisplayName(
            "An encrypted chat, which a task cannot read, is declined with a reason the person sees, and said so")
    void encryptedChatIsDeclined() throws IOException, InterruptedException {
        final String chat = tuwunel.openDirect(alice, task, true);

        assertThat(pollDirect()).as(this::stderr).isEqualTo(Exit.OK);

        assertThat(tuwunel.membership(alice, chat, task.userId())).isEqualTo("leave");
        assertThat(stderr()).contains("an encrypted chat from " + alice.userId(), Direct.ENCRYPTED);
        assertThat(persons()).isEmpty();
    }

    @Test
    @DisplayName(
            "An encrypted message, which nobody here can read, is named on stderr and not handed over")
    void encryptedMessageIsNamed() throws IOException, InterruptedException {
        final String chat = tuwunel.openDirect(alice, task);
        assertThat(pollDirect()).as(this::stderr).isEqualTo(Exit.OK);
        tuwunel.sendEvent(
                alice,
                chat,
                "m.room.encrypted",
                MatrixClient.JSON
                        .createObjectNode()
                        .put("algorithm", "m.megolm.v1.aes-sha2")
                        .put("ciphertext", "AwgAEnAC")
                        .put("session_id", "s"));

        assertThat(pollDirect()).as(this::stderr).isEqualTo(Exit.OK);

        assertThat(stderr())
                .contains(
                        "an encrypted message, which Porelo cannot read", "from " + alice.userId());
        assertThat(persons()).isEmpty();
    }

    private int sendTo(final String to, final String message) throws IOException {
        final Path file = Files.writeString(dir.resolve("out"), message, StandardCharsets.UTF_8);
        final Path sig = Files.write(dir.resolve("out.sig"), new byte[] {1, 2, 3});
        return Transport.run(
                new String[] {"send", file.toString(), sig.toString(), "--to", to},
                env(task),
                new PrintStream(OutputStream.nullOutputStream()),
                new PrintStream(errBytes, true, StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName(
            "send --to a person writes into the chat they opened, and opens one where there is none")
    void taskWritesToAPerson() throws IOException, InterruptedException, Failure {
        final String chat = tuwunel.openDirect(alice, task);
        assertThat(pollDirect()).as(this::stderr).isEqualTo(Exit.OK);

        assertThat(sendTo(alice.userId(), "{\"answer\":1}")).as(this::stderr).isEqualTo(Exit.OK);
        assertThat(tuwunel.messages(alice, chat))
                .anySatisfy(
                        e -> {
                            assertThat(e.path("content").path(Send.MESSAGE_FIELD).asText())
                                    .isEqualTo("{\"answer\":1}");
                            assertThat(e.path("content").has("m.mentions"))
                                    .as("a direct chat is the two of them: nobody to mention")
                                    .isFalse();
                        });

        final Tuwunel.Account other = tuwunel.register("other");
        assertThat(sendTo(other.userId(), "{\"first\":1}")).as(this::stderr).isEqualTo(Exit.OK);
        assertThat(sendTo(other.userId(), "{\"second\":1}")).as(this::stderr).isEqualTo(Exit.OK);
        assertThat(
                        Direct.rooms(new MatrixClient(Config.from(env(task))), task.userId())
                                .get(other.userId()))
                .as("one chat with them, opened by the first message and used by the second")
                .hasSize(1)
                .allSatisfy(room -> assertThat(room).startsWith("!"));
    }
}
