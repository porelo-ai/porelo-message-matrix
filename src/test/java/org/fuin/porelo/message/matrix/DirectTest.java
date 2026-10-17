package org.fuin.porelo.message.matrix;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A task's direct chats as its account's {@code m.direct} keeps them. Harm guarded: a {@code send
 * --to} and a {@code poll --direct} of one task each write {@code m.direct} whole; the one written
 * first is lost, and what the person writes in that chat is then never read.
 */
class DirectTest {

    @Test
    @DisplayName("A chat another writer's m.direct left out is written again with it")
    void rememberedAgainstAnotherWriter() throws IOException, Failure {
        try (FakeHomeserver homeserver = new FakeHomeserver()) {
            homeserver
                    // read before writing: nothing yet
                    .answer(200, "{}")
                    .answer(200, "{}")
                    // read back: another writer's chat landed over this one
                    .answer(200, "{\"@q:x\":[\"!q\"]}")
                    // read again before writing, then written, then read back with both
                    .answer(200, "{\"@q:x\":[\"!q\"]}")
                    .answer(200, "{}")
                    .answer(200, "{\"@q:x\":[\"!q\"],\"@p:x\":[\"!p\"]}");
            final MatrixClient client =
                    new MatrixClient(
                            Config.from(
                                    Map.of(
                                            Config.HOMESERVER,
                                            homeserver.url(),
                                            Config.ACCESS_TOKEN,
                                            "t")));

            Direct.remember(client, "@task:x", "@p:x", "!p");

            final List<FakeHomeserver.Request> writes =
                    homeserver.requests().stream().filter(r -> "PUT".equals(r.method())).toList();
            assertThat(writes).as("the writes of m.direct").hasSize(2);
            final JsonNode last = MatrixClient.JSON.readTree(writes.getLast().body());
            assertThat(last.path("@q:x").toString())
                    .as("the other writer's chat")
                    .isEqualTo("[\"!q\"]");
            assertThat(last.path("@p:x").toString()).as("this chat").isEqualTo("[\"!p\"]");
        }
    }
}
