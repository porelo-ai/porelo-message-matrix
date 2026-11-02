package org.fuin.porelo.message.matrix;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

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

    /** Harm guarded: another person's task opens a direct chat that reads as the operator's. */
    @ParameterizedTest
    @ValueSource(strings = {"@t.bob:x", "@porelo:x", "@porelo-relay-p.bob:x", "@bob:other"})
    @DisplayName("On a central homeserver a chat from no person's own account is declined unasked")
    void centralInvitationFromATaskIsDeclined(final String inviter) throws IOException, Failure {
        try (FakeHomeserver homeserver = new FakeHomeserver()) {
            homeserver.answer(200, "{}");
            final MatrixClient client =
                    new MatrixClient(
                            Config.from(
                                    Map.of(
                                            Config.HOMESERVER,
                                            homeserver.url(),
                                            Config.ACCESS_TOKEN,
                                            "t")));
            final JsonNode invites =
                    MatrixClient.JSON.readTree(
                            """
                            {"!d:x":{"invite_state":{"events":[{"type":"m.room.member",
                             "state_key":"@t.alice:x","sender":"%s",
                             "content":{"membership":"invite","is_direct":true}}]}}}
                            """
                                    .formatted(inviter));

            final List<String> declined =
                    Direct.answerInvitations(client, "@t.alice:x", "x", invites);

            assertThat(declined)
                    .as("what was declined")
                    .singleElement()
                    .asString()
                    .contains(inviter);
            assertThat(homeserver.requests())
                    .as("left at once, without asking which rooms the inviter shares")
                    .singleElement()
                    .satisfies(r -> assertThat(r.rawPath()).endsWith("/leave"));
        }
    }

    /** Harm guarded: an encrypted chat joined and never answered, its person left waiting. */
    @ParameterizedTest
    @ValueSource(strings = {"first", "last"})
    void anEncryptedInvitationIsLeftWithItsReasonWhereverTheEventStands(final String where)
            throws IOException, Failure {
        try (FakeHomeserver homeserver = new FakeHomeserver()) {
            homeserver.answer(200, "{}");
            final MatrixClient client =
                    new MatrixClient(
                            Config.from(
                                    Map.of(
                                            Config.HOMESERVER,
                                            homeserver.url(),
                                            Config.ACCESS_TOKEN,
                                            "t")));
            final String encryption =
                    "{\"type\":\"m.room.encryption\",\"state_key\":\"\",\"sender\":\"@p:x\","
                            + "\"content\":{\"algorithm\":\"m.megolm.v1.aes-sha2\"}}";
            final String member =
                    "{\"type\":\"m.room.member\",\"state_key\":\"@t:x\",\"sender\":\"@p:x\","
                            + "\"content\":{\"membership\":\"invite\",\"is_direct\":true}}";
            final String name =
                    "{\"type\":\"m.room.name\",\"state_key\":\"\",\"sender\":\"@p:x\","
                            + "\"content\":{\"name\":\"chat\"}}";
            final String events =
                    "first".equals(where)
                            ? encryption + "," + member + "," + name
                            : name + "," + member + "," + encryption;
            final JsonNode invites =
                    MatrixClient.JSON.readTree(
                            "{\"!d:x\":{\"invite_state\":{\"events\":[" + events + "]}}}");

            final List<String> declined = Direct.answerInvitations(client, "@t:x", null, invites);

            assertThat(declined)
                    .as("what was declined")
                    .singleElement()
                    .asString()
                    .contains("@p:x");
            final FakeHomeserver.Request leave = homeserver.requests().getFirst();
            assertThat(leave.rawPath()).as("left").endsWith("/leave");
            assertThat(MatrixClient.JSON.readTree(leave.body()).path("reason").asText())
                    .as("the reason the person's client shows")
                    .isEqualTo(Direct.ENCRYPTED);
            assertThat(homeserver.requests()).as("nothing asked first").hasSize(1);
        }
    }
}
