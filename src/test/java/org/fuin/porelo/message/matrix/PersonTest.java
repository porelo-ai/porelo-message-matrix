package org.fuin.porelo.message.matrix;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.BooleanNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
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

    /** Harm guarded: Porelo cannot tell which developer of the access file wrote a line. */
    @Test
    void aLoginsOwnAccountNamesItsLogin() throws Exception {
        final JsonNode written = deliverFrom("@ann:x");

        assertThat(written.path("account").asText())
                .as("the login the person's own account is named after")
                .isEqualTo("ann");
    }

    /** Harm guarded: a task's, a helper's or another server's account passes as a developer. */
    @ParameterizedTest
    @ValueSource(
            strings = {"@t.ann:x", "@porelo.ann:x", "@porelo:x", "@porelo-relay-demo:x", "@ann:y"})
    void anyOtherAccountNamesNoLogin(final String sender) throws Exception {
        final JsonNode written = deliverFrom(sender);

        assertThat(written.has("account")).as("absent means no known person").isFalse();
    }

    private JsonNode deliverMentions(final String... mentions) throws Exception {
        return deliverFrom("@ann:x", mentions);
    }

    private JsonNode deliverFrom(final String sender, final String... mentions) throws Exception {
        final var event =
                MatrixClient.JSON
                        .createObjectNode()
                        .put("event_id", "$helper")
                        .put("sender", sender)
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

    /** The room's members, as {@code joined_members} answers, with the names they show. */
    private static final String MEMBERS =
            """
            {"joined":{"@pi:x":{"display_name":"Agent Pi"},"@pine:x":{"display_name":"Agent Pine"},\
            "@omp:x":{"display_name":"omp"},"@ann:x":{"display_name":"ann"}}}
            """;

    /** Whom a person's typed line is meant for, in a room whose members show {@link #MEMBERS}. */
    private List<String> toOfTyped(final FakeHomeserver homeserver, final String body)
            throws Exception {
        return typed(homeserver, body, null, "@ann:x")
                .path("to")
                .valueStream()
                .map(JsonNode::asText)
                .toList();
    }

    /** The person file a typed line gives, from {@code sender}, in the room or a direct chat. */
    private JsonNode typed(
            final FakeHomeserver homeserver,
            final String body,
            final String directTo,
            final String sender,
            final String... mentions)
            throws Exception {
        final ObjectNode event = typedEvent(body, sender);
        if (mentions.length > 0) {
            final var ids =
                    ((ObjectNode) event.path("content"))
                            .putObject("m.mentions")
                            .putArray("user_ids");
            for (final String mention : mentions) {
                ids.add(mention);
            }
        }
        return handedOver(homeserver, event, directTo);
    }

    /** A typed line's event, from {@code sender}, mentioning nobody. */
    private static ObjectNode typedEvent(final String body, final String sender) {
        final ObjectNode event =
                MatrixClient.JSON
                        .createObjectNode()
                        .put("event_id", "$typed")
                        .put("sender", sender)
                        .put("origin_server_ts", 1790000000000L);
        event.putObject("content").put("msgtype", "m.text").put("body", body);
        return event;
    }

    /** The one person file {@code event} gives, in the room or a direct chat. */
    private JsonNode handedOver(
            final FakeHomeserver homeserver, final ObjectNode event, final String directTo)
            throws Exception {
        final MatrixClient client =
                new MatrixClient(
                        Config.from(
                                Map.of(
                                        Config.HOMESERVER,
                                        homeserver.url(),
                                        Config.ACCESS_TOKEN,
                                        "t")));

        assertThat(
                        Person.deliver(
                                client, "@relay:x", "!room", event, inbound, state, null, directTo))
                .as("the person's words are handed over")
                .isNull();

        final List<Path> files;
        try (Stream<Path> list = Files.list(inbound)) {
            files = list.toList();
        }
        assertThat(files).as("one person file is handed over").hasSize(1);
        return MatrixClient.JSON.readTree(Files.readString(files.getFirst()));
    }

    /**
     * Harm guarded: a person who writes "All agents: ..." as the agents do reaches no task that
     * happens not to look, though everybody was meant.
     */
    @ParameterizedTest
    @ValueSource(
            strings = {"All agents: the Build is green", "all agents: hi", "Hi, All Agents: x"})
    void aHeadForAllAgentsMarksTheLineForAll(final String body) throws Exception {
        try (FakeHomeserver homeserver = new FakeHomeserver()) {
            final JsonNode file = typed(homeserver, body, null, "@ann:x");

            assertThat(file.path("all").isBoolean() && file.path("all").booleanValue())
                    .as("marked as meant for all agents, a JSON true")
                    .isTrue();
            assertThat(homeserver.requests()).as("requests to the homeserver").isEmpty();
        }
    }

    /** Harm guarded: a line for somebody else wakes every task of the project. */
    @ParameterizedTest
    @ValueSource(
            strings = {
                "All agentsX: hi",
                "recall agents: hi",
                "hello: All agents",
                "All agents without a colon",
                "All agents\nnow: hi"
            })
    void onlyAWholeBroadcastInTheHeadIsForAll(final String body) throws Exception {
        try (FakeHomeserver homeserver = new FakeHomeserver()) {
            final JsonNode file = typed(homeserver, body, null, "@ann:x");

            assertThat(file.has("all")).as("marked for all agents").isFalse();
        }
    }

    /**
     * Harm guarded: a long line that mentions all agents somewhere wakes every task, though only a
     * head within 300 code points addresses anyone.
     */
    @Test
    void aBroadcastBeyondTheHeadsReachIsNotForAll() throws Exception {
        try (FakeHomeserver homeserver = new FakeHomeserver()) {
            final JsonNode within =
                    typed(homeserver, "x".repeat(288) + " All agents: hi", null, "@ann:x");
            assertThat(within.path("all").booleanValue())
                    .as("colon at code point 299, marked for all agents")
                    .isTrue();
        }
        try (FakeHomeserver homeserver = new FakeHomeserver()) {
            final JsonNode beyond =
                    typed(homeserver, "x".repeat(289) + " All agents: hi", null, "@ann:x");
            assertThat(beyond.has("all"))
                    .as("colon at code point 300, marked for all agents")
                    .isFalse();
        }
    }

    /** Harm guarded: a line for every task is answered by the relay instead, and nobody wakes. */
    @Test
    void aLineForAllAgentsIsNeverForTheHelpersAlone() throws Exception {
        try (FakeHomeserver homeserver = new FakeHomeserver()) {
            final JsonNode file = typed(homeserver, "All agents: hi", null, "@ann:x", "@relay:x");

            assertThat(file.path("all").isBoolean() && file.path("all").booleanValue())
                    .as("marked as meant for all agents, a JSON true")
                    .isTrue();
            assertThat(file.has("helpers")).as("marked for the helpers alone").isFalse();
        }
    }

    /**
     * Harm guarded: a line for all agents that also names a task loses that task, or keeps a helper
     * that Porelo takes for a task.
     */
    @Test
    void aLineForAllAgentsKeepsWhomElseItNames() throws Exception {
        try (FakeHomeserver homeserver = new FakeHomeserver()) {
            homeserver.answer(200, MEMBERS);

            final JsonNode file =
                    typed(homeserver, "All agents, Agent Pi: hi", null, "@ann:x", "@relay:x");

            assertThat(file.path("to").valueStream().map(JsonNode::asText).toList())
                    .as("whom else it is meant for")
                    .containsExactly("@pi:x");
        }
    }

    /** Harm guarded: one task's direct chat wakes every task of the project. */
    @Test
    void aDirectChatIsNeverForAll() throws Exception {
        try (FakeHomeserver homeserver = new FakeHomeserver()) {
            final JsonNode file = typed(homeserver, "All agents: hi", "@pi:x", "@ann:x");

            assertThat(file.has("all")).as("marked for all agents").isFalse();
        }
    }

    /**
     * Harm guarded: a person who mentions the whole room with {@code @room}, beside the relay,
     * reaches no task that happens not to look, or is answered by the relay instead.
     */
    @Test
    void aRoomMentionMarksTheLineForAll() throws Exception {
        try (FakeHomeserver homeserver = new FakeHomeserver()) {
            final ObjectNode event = roomMention(BooleanNode.TRUE);
            ((ObjectNode) event.path("content").path("m.mentions"))
                    .putArray("user_ids")
                    .add("@relay:x");

            final JsonNode file = handedOver(homeserver, event, null);

            assertThat(file.path("all").isBoolean() && file.path("all").booleanValue())
                    .as("marked as meant for all agents, a JSON true")
                    .isTrue();
            assertThat(file.has("helpers")).as("marked for the helpers alone").isFalse();
            assertThat(homeserver.requests()).as("requests to the homeserver").isEmpty();
        }
    }

    /** Harm guarded: a line for somebody else wakes every task of the project. */
    @ParameterizedTest
    @ValueSource(strings = {"false", "\"true\"", "1"})
    void onlyARoomMentionThatIsTrueIsForAll(final String room) throws Exception {
        try (FakeHomeserver homeserver = new FakeHomeserver()) {
            final JsonNode file =
                    handedOver(homeserver, roomMention(MatrixClient.JSON.readTree(room)), null);

            assertThat(file.has("all")).as("marked for all agents").isFalse();
        }
    }

    /** Harm guarded: one task's direct chat wakes every task of the project. */
    @Test
    void aDirectChatMentioningTheRoomIsNeverForAll() throws Exception {
        try (FakeHomeserver homeserver = new FakeHomeserver()) {
            final JsonNode file = handedOver(homeserver, roomMention(BooleanNode.TRUE), "@pi:x");

            assertThat(file.has("all")).as("marked for all agents").isFalse();
        }
    }

    /** A plain line from a person whose client marked {@code m.mentions.room} with {@code room}. */
    private static ObjectNode roomMention(final JsonNode room) {
        final ObjectNode event = typedEvent("hi everyone", "@ann:x");
        ((ObjectNode) event.path("content")).putObject("m.mentions").set("room", room);
        return event;
    }

    /**
     * Harm guarded: a person who types "Agent Pi: ..." as the agents do, with no pill, wakes no
     * task, and the line is read only when the task happens to look.
     */
    @Test
    void aHeadNamingATasksShownNameIsMeantForIt() throws Exception {
        try (FakeHomeserver homeserver = new FakeHomeserver()) {
            homeserver.answer(200, MEMBERS);

            assertThat(toOfTyped(homeserver, "Agent Pi: the Build is green, please answer here"))
                    .as("whom the typed line is meant for")
                    .containsExactly("@pi:x");
        }
    }

    /** Harm guarded: a name inside a longer one, or in the words, wakes a task not meant. */
    @ParameterizedTest
    @ValueSource(
            strings = {
                "Agent Pine: hello",
                "Agent Pi2: hello",
                "hello: Agent Pi, are you there",
                "Agent Pi without a colon",
                "agent pi: hello"
            })
    void onlyAWholeShownNameInTheHeadNamesATask(final String body) throws Exception {
        try (FakeHomeserver homeserver = new FakeHomeserver()) {
            homeserver.answer(200, MEMBERS);

            assertThat(toOfTyped(homeserver, body))
                    .as("whom the typed line is meant for")
                    .doesNotContain("@pi:x");
        }
    }

    /** Harm guarded: a task shown by its own name is woken by every line that happens to say it. */
    @Test
    void aShownNameThatIsNoAgentsIsNeverNamedByAHead() throws Exception {
        try (FakeHomeserver homeserver = new FakeHomeserver()) {
            homeserver.answer(200, MEMBERS);

            assertThat(toOfTyped(homeserver, "omp, Agent Smith: hello"))
                    .as("whom it is meant for")
                    .isEmpty();
        }
    }

    /** Harm guarded: every line a person types costs the homeserver a members request. */
    @ParameterizedTest
    @ValueSource(strings = {"hello everybody", "status: green", "agent pi: hello", "Agent : hi"})
    void aLineWhoseHeadNamesNoAgentAsksNothing(final String body) throws Exception {
        try (FakeHomeserver homeserver = new FakeHomeserver()) {
            assertThat(toOfTyped(homeserver, body)).as("whom it is meant for").isEmpty();
            assertThat(homeserver.requests()).as("requests to the homeserver").isEmpty();
        }
    }

    /** Harm guarded: a sync with many headed lines in a room asks for its members each time. */
    @Test
    void aRoomsMembersAreAskedOncePerSync() throws Exception {
        try (FakeHomeserver homeserver = new FakeHomeserver()) {
            homeserver.answer(200, MEMBERS);
            final MatrixClient client =
                    new MatrixClient(
                            Config.from(
                                    Map.of(
                                            Config.HOMESERVER,
                                            homeserver.url(),
                                            Config.ACCESS_TOKEN,
                                            "t")));
            final Map<String, Map<String, String>> shown = new java.util.HashMap<>();

            for (final String id : List.of("$one", "$two")) {
                final var event =
                        MatrixClient.JSON
                                .createObjectNode()
                                .put("event_id", id)
                                .put("sender", "@ann:x")
                                .put("origin_server_ts", 1790000000000L);
                event.putObject("content").put("msgtype", "m.text").put("body", "Agent Pi: hi");
                assertThat(
                                Person.deliver(
                                        client,
                                        "@relay:x",
                                        "!room",
                                        event,
                                        inbound,
                                        state,
                                        null,
                                        null,
                                        shown))
                        .as("the person's words are handed over")
                        .isNull();
            }

            assertThat(homeserver.requests()).as("requests for the room's members").hasSize(1);
        }
    }

    /**
     * Harm guarded: the relay taken out of a room after its sync ends every poll 77, and no other
     * room's words reach the tasks either.
     */
    @Test
    void aRoomThatNoLongerLetsTheRelayAskIsHandedOverNamedAsBefore() throws Exception {
        try (FakeHomeserver homeserver = new FakeHomeserver()) {
            homeserver.answer(403, "{\"errcode\":\"M_FORBIDDEN\",\"error\":\"not in the room\"}");

            assertThat(toOfTyped(homeserver, "Agent Pi: hello"))
                    .as("whom it is meant for")
                    .isEmpty();
        }
    }
}
