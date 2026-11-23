package org.fuin.porelo.message.matrix;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.node.ArrayNode;
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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** What a person said in a direct chat before the task joined it, read by the poll over a fake. */
class DirectCatchUpTest {

    private static final String SELF = "@t:x";

    private static final String PERSON = "@alice:x";

    private static final String CHAT = "!chat:x";

    @TempDir private Path dir;

    private FakeHomeserver homeserver;

    private Path inbound;

    private final ByteArrayOutputStream errBytes = new ByteArrayOutputStream();

    @BeforeEach
    void setUp() throws IOException {
        homeserver = new FakeHomeserver();
        inbound = Files.createDirectories(dir.resolve("inbound"));
    }

    @AfterEach
    void tearDown() {
        homeserver.close();
    }

    private int pollDirect() {
        return Main.run(
                new String[] {"poll", "--into", inbound.toString(), "--direct"},
                Map.of(
                        Config.HOMESERVER,
                        homeserver.url(),
                        Config.ACCESS_TOKEN,
                        "token",
                        "XDG_STATE_HOME",
                        dir.resolve("state").toString()),
                new PrintStream(OutputStream.nullOutputStream()),
                new PrintStream(errBytes, true, StandardCharsets.UTF_8));
    }

    private String stderr() {
        return errBytes.toString(StandardCharsets.UTF_8);
    }

    private List<String> bodies() throws IOException {
        final List<String> found = new ArrayList<>();
        try (Stream<Path> files = Files.list(inbound)) {
            for (final Path file : files.sorted().toList()) {
                if (file.getFileName().toString().contains("--matrix-person-")) {
                    found.add(MatrixClient.JSON.readTree(file.toFile()).path("body").asText());
                }
            }
        }
        return found;
    }

    /** The answers of a first direct poll up to the chat's history: invited, joined, remembered. */
    private void answerTheJoin() {
        homeserver
                .answer(200, "{\"user_id\":\"" + SELF + "\"}")
                .answer(200, "{\"joined_rooms\":[\"!project:x\"]}")
                .answer(200, invited())
                .answer(200, "{\"joined_rooms\":[\"!project:x\"]}")
                .answer(200, "{\"joined\":{\"" + PERSON + "\":{}}}")
                .answer(200, "{\"room_id\":\"" + CHAT + "\"}")
                .answer(404, "{\"errcode\":\"M_NOT_FOUND\",\"error\":\"no m.direct\"}")
                .answer(200, "{}")
                .answer(200, direct())
                .answer(200, direct());
    }

    private static String invited() {
        final ObjectNode sync = MatrixClient.JSON.createObjectNode().put("next_batch", "s1");
        sync.putObject("rooms")
                .putObject("invite")
                .putObject(CHAT)
                .putObject("invite_state")
                .putArray("events")
                .addObject()
                .put("type", "m.room.member")
                .put("state_key", SELF)
                .put("sender", PERSON)
                .putObject("content")
                .put("membership", "invite")
                .put("is_direct", true);
        return sync.toString();
    }

    private static String direct() {
        return "{\"" + PERSON + "\":[\"" + CHAT + "\"]}";
    }

    private static String page(final int lines, final String end) {
        final ObjectNode page = MatrixClient.JSON.createObjectNode().put("start", "s1");
        if (!end.isEmpty()) {
            page.put("end", end);
        }
        final ArrayNode chunk = page.putArray("chunk");
        // Newest first, as a page read backwards comes.
        for (int i = lines; i >= 1; i--) {
            final ObjectNode event =
                    chunk.addObject()
                            .put("type", "m.room.message")
                            .put("event_id", "$line" + i)
                            .put("sender", PERSON)
                            .put("origin_server_ts", 1_000L + i);
            event.putObject("content").put("msgtype", "m.text").put("body", "line " + i);
        }
        return page.toString();
    }

    /**
     * Harm guarded: a poll that fails after joining a chat, before reading what was said in it,
     * leaves the person's first line unread for good, since the next poll sees no invitation.
     */
    @Test
    void aFailedReadAfterTheJoinIsReadByTheNextPoll() throws IOException {
        answerTheJoin();
        homeserver.answer(502, "{\"errcode\":\"M_UNKNOWN\",\"error\":\"gateway\"}");

        assertThat(pollDirect()).as("the history read failed: %s", stderr()).isNotEqualTo(Exit.OK);
        assertThat(bodies()).as("nothing handed over yet").isEmpty();

        final ObjectNode joined = MatrixClient.JSON.createObjectNode().put("next_batch", "s2");
        joined.putObject("rooms")
                .putObject("join")
                .putObject(CHAT)
                .putObject("timeline")
                .put("limited", false)
                .putArray("events");
        homeserver
                .answer(200, "{\"user_id\":\"" + SELF + "\"}")
                .answer(200, "{\"joined_rooms\":[\"!project:x\",\"" + CHAT + "\"]}")
                .answer(200, joined.toString())
                .answer(200, direct())
                .answer(200, page(1, ""));

        assertThat(pollDirect()).as(stderr()).isEqualTo(Exit.OK);
        assertThat(bodies())
                .as("the line said before the join, read now")
                .containsExactly("line 1");
    }

    /**
     * Harm guarded: a chat marked direct with a long history is read to its start in one poll,
     * holding the poll and flooding the task with what was said long before it was asked.
     */
    @Test
    void aLongHistoryIsReadOnePageBack() throws IOException {
        answerTheJoin();
        homeserver.answer(200, page(100, "older"));

        assertThat(pollDirect()).as(stderr()).isEqualTo(Exit.OK);
        assertThat(bodies()).as("the newest page, handed over").hasSize(100).contains("line 100");
        assertThat(stderr()).as("what lay before it is named").contains(CHAT).contains("100");
        assertThat(homeserver.requests())
                .filteredOn(r -> r.rawPath().endsWith("/messages"))
                .as("one page asked for")
                .hasSize(1);
    }
}
