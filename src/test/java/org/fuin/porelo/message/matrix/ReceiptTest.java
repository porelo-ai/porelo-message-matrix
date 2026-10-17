package org.fuin.porelo.message.matrix;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@code receipt} where the answer depends on what a homeserver does that the real one only does
 * sometimes. What it answers against Tuwunel is proven in {@code ReceiptIT}.
 */
class ReceiptTest {

    private static final String PROJECT = "!project";

    private static final String OTHER = "!admins:matrix.test";

    private static final String EVENT = "$e1";

    private FakeHomeserver homeserver;

    private final ByteArrayOutputStream outBytes = new ByteArrayOutputStream();

    private final ByteArrayOutputStream errBytes = new ByteArrayOutputStream();

    @BeforeEach
    void setUp() throws IOException {
        homeserver = new FakeHomeserver();
    }

    @AfterEach
    void tearDown() {
        homeserver.close();
    }

    private String receipt() throws IOException {
        final int exit = Main.run(new String[] {"receipt", EVENT, "--by", "@bob:matrix.test"},
                Map.of(Config.HOMESERVER, homeserver.url(), Config.ACCESS_TOKEN, "t"),
                new PrintStream(outBytes, true, StandardCharsets.UTF_8), new PrintStream(errBytes, true, StandardCharsets.UTF_8));
        assertThat(exit).as(errBytes.toString(StandardCharsets.UTF_8)).isEqualTo(Exit.OK);
        return MatrixClient.JSON.readTree(outBytes.toByteArray()).path("state").asText();
    }

    @Test
    @DisplayName("An event a homeserver answers under the wrong room is looked for in its own room, as its room_id says")
    void trustsTheEventsOwnRoom() throws IOException {
        final String event = "{\"event_id\":\"" + EVENT + "\",\"room_id\":\"" + PROJECT + "\",\"type\":\"m.room.message\"}";
        homeserver
                .answer(200, "{\"joined_rooms\":[\"" + OTHER + "\",\"" + PROJECT + "\"]}")
                // Asked under the admin room, the event comes back anyway - naming the room it is really in.
                .answer(200, event)
                .answer(200, event)
                .answer(200, "{\"next_batch\":\"s1\",\"rooms\":{\"join\":{}}}")
                .answer(200, "{\"membership\":\"join\"}");

        assertThat(receipt()).as(errBytes.toString(StandardCharsets.UTF_8)).isEqualTo("delivered");
        assertThat(homeserver.requests().getLast().rawPath())
                .isEqualTo("/_matrix/client/v3/rooms/%21project/state/m.room.member/%40bob%3Amatrix.test");
    }

}
