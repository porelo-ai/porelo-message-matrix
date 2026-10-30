package org.fuin.porelo.message.matrix;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * What a person writes in the room reaches the tasks, whatever the person's client put into it.
 * Harm guarded: a message the transport cannot take apart ends every later poll of the room the
 * same way, since the position is saved after the files, and nothing in the room reaches Porelo
 * again.
 */
class PersonTest {

    @TempDir Path inbound;

    @TempDir Path state;

    /** Harm guarded: a line addressed only to helpers must not wake every task. */
    @ParameterizedTest
    @ValueSource(strings = {"@porelo:x", "@porelo-relay-demo:x"})
    void aLineNamingOnlyAHelperIsMarkedForPorelo(final String helper) throws Exception {
        final JsonNode written = deliverMentions(helper);

        assertThat(written.path("to")).as("helpers are not task addressees").isEmpty();
        assertThat(written.path("helpers"))
                .as("Porelo must answer without waking a task")
                .isEqualTo(MatrixClient.JSON.getNodeFactory().booleanNode(true));
    }

    /** Harm guarded: a helper beside a task must neither receive nor divert the task's words. */
    @ParameterizedTest
    @ValueSource(strings = {"@porelo:x", "@porelo-relay-demo:x"})
    void aLineNamingATaskAndAHelperKeepsOnlyTheTask(final String helper) throws Exception {
        final JsonNode written = deliverMentions("@task:x", helper);

        assertThat(written.path("to").valueStream().map(JsonNode::asText).toList())
                .as("the task remains the sole addressee")
                .containsExactly("@task:x");
        assertThat(written.has("helpers")).as("a task's message must not be diverted").isFalse();
    }

    /** Harm guarded: a person naming themselves beside a helper must not wake every task. */
    @Test
    void aLineNamingItsWriterAndAHelperIsMarkedForPorelo() throws Exception {
        final JsonNode written = deliverMentions("@ann:x", "@porelo:x");

        assertThat(written.path("to")).as("neither the writer nor a helper is a task").isEmpty();
        assertThat(written.path("helpers"))
                .as("the writer naming themselves names nobody else, so only a helper was meant")
                .isEqualTo(MatrixClient.JSON.getNodeFactory().booleanNode(true));
    }

    /** Harm guarded: a line with no addressee must still reach the project's tasks. */
    @Test
    void aLineNamingNobodyIsNotMarkedForHelpers() throws Exception {
        final JsonNode written = deliverMentions();

        assertThat(written.path("to")).as("the line names nobody").isEmpty();
        assertThat(written.has("helpers")).as("an unaddressed line is for the tasks").isFalse();
    }

    private JsonNode deliverMentions(final String... mentions) throws Exception {
        final var event =
                MatrixClient.JSON
                        .createObjectNode()
                        .put("event_id", "$helper")
                        .put("sender", "@ann:x")
                        .put("origin_server_ts", 1790000000000L);
        final var content =
                event.putObject("content").put("msgtype", "m.text").put("body", "hello");
        final var ids = content.putObject("m.mentions").putArray("user_ids");
        for (final String mention : mentions) {
            ids.add(mention);
        }
        final MatrixClient client =
                new MatrixClient(
                        Config.from(
                                Map.of(
                                        Config.HOMESERVER,
                                        "http://127.0.0.1:8008",
                                        Config.ACCESS_TOKEN,
                                        "t")));

        assertThat(
                        Person.deliver(
                                client,
                                "@porelo-relay-demo:x",
                                "!room",
                                event,
                                inbound,
                                state,
                                null,
                                null))
                .as("the person's words are handed over")
                .isNull();

        final List<Path> files;
        try (Stream<Path> list = Files.list(inbound)) {
            files = list.toList();
        }
        assertThat(files).as("one person file is handed over").hasSize(1);
        return MatrixClient.JSON.readTree(Files.readString(files.getFirst()));
    }

    @Test
    @DisplayName("A mention link that is no valid escape is passed over, and the message delivered")
    void malformedMentionLinkIsPassedOver() throws Exception {
        try (FakeHomeserver homeserver = new FakeHomeserver()) {
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
                    Person.deliver(client, "@relay:x", "!room", event, inbound, state, null, null);

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

    /** Harm guarded: another person's task speaks in a central homeserver's room as a person. */
    @ParameterizedTest
    @ValueSource(strings = {"@t.bob:x", "@porelo.bob:x", "@porelo-relay-demo.bob:x", "@porelo:x"})
    void onACentralHomeserverOnlyALoginsOwnAccountIsAPerson(final String sender) throws Exception {
        final var event =
                MatrixClient.JSON
                        .createObjectNode()
                        .put("event_id", "$other")
                        .put("sender", sender)
                        .put("origin_server_ts", 1790000000000L);
        event.putObject("content").put("msgtype", "m.text").put("body", "hello");
        final MatrixClient client =
                new MatrixClient(
                        Config.from(
                                Map.of(
                                        Config.HOMESERVER,
                                        "http://127.0.0.1:8008",
                                        Config.ACCESS_TOKEN,
                                        "t")));

        final String skipped =
                Person.deliver(
                        client,
                        "@porelo-relay-demo.alice:x",
                        "!room",
                        event,
                        inbound,
                        state,
                        "x",
                        null);

        assertThat(skipped).as("why the words were not handed over").contains(sender);
        try (Stream<Path> list = Files.list(inbound)) {
            assertThat(list.toList()).as("nothing is handed over").isEmpty();
        }
        event.put("sender", "@bob:x");
        assertThat(
                        Person.deliver(
                                client,
                                "@porelo-relay-demo.alice:x",
                                "!room",
                                event,
                                inbound,
                                state,
                                "x",
                                null))
                .as("a login's own account is a person")
                .isNull();
    }
}
