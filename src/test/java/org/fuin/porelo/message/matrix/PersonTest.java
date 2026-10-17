package org.fuin.porelo.message.matrix;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * What a person writes in the room reaches the tasks, whatever the person's client put into it.
 * Harm guarded: a message the transport cannot take apart ends every later poll of the room the
 * same way, since the position is saved after the files, and nothing in the room reaches Porelo
 * again.
 */
class PersonTest {

    private FakeHomeserver homeserver;

    @TempDir Path inbound;

    @TempDir Path state;

    @BeforeEach
    void setUp() throws IOException {
        homeserver = new FakeHomeserver();
    }

    @AfterEach
    void tearDown() {
        homeserver.close();
    }

    @Test
    @DisplayName("A mention link that is no valid escape is passed over, and the message delivered")
    void malformedMentionLinkIsPassedOver() throws Exception {
        final JsonNode event =
                MatrixClient.JSON.readTree(
                        """
                        {"event_id":"$e1","sender":"@ann:x","origin_server_ts":1790000000000,
                         "content":{"msgtype":"m.text","body":"hi",
                          "formatted_body":"<a href=\\"https://matrix.to/#/@50%zz:x\\">a</a>\
                         <a href=\\"https://matrix.to/#/@task%2Done:x\\">b</a>"}}
                        """);
        final MatrixClient client =
                new MatrixClient(
                        Config.from(
                                Map.of(
                                        Config.HOMESERVER,
                                        homeserver.url(),
                                        Config.ACCESS_TOKEN,
                                        "t")));

        final String skipped =
                Person.deliver(client, "@relay:x", "!room", event, inbound, state, null);

        assertThat(skipped).as("why the message was not delivered").isNull();
        final List<Path> files;
        try (Stream<Path> list = Files.list(inbound)) {
            files = list.toList();
        }
        assertThat(files).as("the files written").hasSize(1);
        final JsonNode written = MatrixClient.JSON.readTree(Files.readString(files.getFirst()));
        assertThat(written.path("to").valueStream().map(JsonNode::asText).toList())
                .as("whom it is meant for")
                .containsExactly("@task-one:x");
        assertThat(homeserver.requests()).as("requests to the homeserver").isEmpty();
    }
}
