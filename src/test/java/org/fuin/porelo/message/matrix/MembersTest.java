package org.fuin.porelo.message.matrix;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * Harm guarded: a window that shows a helper as a person, another login's task as one of the
 * person's own, a member who left as still in, or a name that is not the room's misleads the person
 * about who reads the room and whom a head reaches.
 */
class MembersTest {

    private static final String SERVER = "s.example";

    /** A member event as {@code /rooms/{id}/members} answers it. */
    private static JsonNode event(
            final String id, final String membership, final @Nullable String name) {
        final ObjectNode event = MatrixClient.JSON.createObjectNode();
        event.put("type", "m.room.member").put("state_key", id);
        final ObjectNode content = event.putObject("content").put("membership", membership);
        if (name != null) {
            content.put("displayname", name);
        }
        return event;
    }

    private static List<String> rows(final JsonNode answer) {
        final List<String> rows = new java.util.ArrayList<>();
        for (final JsonNode member : answer.path("members")) {
            rows.add(member.toString());
        }
        return rows;
    }

    @Test
    void onTheAccountsOwnHomeserverEachMemberHasItsKindStateAndName() {
        final JsonNode answer =
                Members.answer(
                        "#porelo-demo:" + SERVER,
                        "demo",
                        SERVER,
                        null,
                        List.of(
                                event("@porelo:" + SERVER, "join", "Porelo (administrator)"),
                                event("@porelo-relay-demo:" + SERVER, "join", "Porelo (delivery)"),
                                event("@porelo-demo-pi:" + SERVER, "join", "Agent Pi"),
                                event("@alice:" + SERVER, "join", "Alice"),
                                event("@bob:" + SERVER, "invite", null),
                                event("@carol:other.example", "join", "Carol")));

        assertThat(answer.path("room").asText()).as("the room").isEqualTo("#porelo-demo:" + SERVER);
        assertThat(rows(answer))
                .as("task, persons, helpers, then others; each with its kind")
                .containsExactly(
                        "{\"id\":\"@porelo-demo-pi:s.example\",\"shown\":\"Agent Pi\",\"state\":\"join\",\"kind\":\"task\",\"task\":\"porelo-demo-pi\"}",
                        "{\"id\":\"@bob:s.example\",\"shown\":\"@bob:s.example\",\"state\":\"invite\",\"kind\":\"person\"}",
                        "{\"id\":\"@alice:s.example\",\"shown\":\"Alice\",\"state\":\"join\",\"kind\":\"person\"}",
                        "{\"id\":\"@porelo:s.example\",\"shown\":\"Porelo (administrator)\",\"state\":\"join\",\"kind\":\"helper\"}",
                        "{\"id\":\"@porelo-relay-demo:s.example\",\"shown\":\"Porelo (delivery)\",\"state\":\"join\",\"kind\":\"helper\"}",
                        "{\"id\":\"@carol:other.example\",\"shown\":\"Carol\",\"state\":\"join\",\"kind\":\"other\"}");
    }

    @Test
    void onACentralHomeserverATaskNamesItsLoginAndNoHelperPassesForAPerson() {
        final JsonNode answer =
                Members.answer(
                        "#porelo-demo:" + SERVER,
                        "demo",
                        SERVER,
                        "ada",
                        List.of(
                                event("@porelo-demo-pi.bea:" + SERVER, "join", "Agent Pi"),
                                event("@porelo-demo-pi.ada:" + SERVER, "join", "Agent Pi"),
                                event("@porelo.bea:" + SERVER, "join", "Porelo (administrator)"),
                                event("@porelo-relay-demo.bea:" + SERVER, "join", "Porelo"),
                                event("@ada:" + SERVER, "join", "Ada"),
                                event("@jane.doe:" + SERVER, "join", "Jane"),
                                event("@porelo-other-x.ada:" + SERVER, "join", "Agent X")));

        assertThat(rows(answer))
                .as(
                        "each login's task with its login; helpers of every login; a dotted name no person")
                .containsExactly(
                        "{\"id\":\"@porelo-demo-pi.ada:s.example\",\"shown\":\"Agent Pi\",\"state\":\"join\",\"kind\":\"task\",\"task\":\"porelo-demo-pi\",\"login\":\"ada\"}",
                        "{\"id\":\"@porelo-demo-pi.bea:s.example\",\"shown\":\"Agent Pi\",\"state\":\"join\",\"kind\":\"task\",\"task\":\"porelo-demo-pi\",\"login\":\"bea\"}",
                        "{\"id\":\"@ada:s.example\",\"shown\":\"Ada\",\"state\":\"join\",\"kind\":\"person\"}",
                        "{\"id\":\"@porelo-relay-demo.bea:s.example\",\"shown\":\"Porelo\",\"state\":\"join\",\"kind\":\"helper\"}",
                        "{\"id\":\"@porelo.bea:s.example\",\"shown\":\"Porelo (administrator)\",\"state\":\"join\",\"kind\":\"helper\"}",
                        "{\"id\":\"@porelo-other-x.ada:s.example\",\"shown\":\"Agent X\",\"state\":\"join\",\"kind\":\"other\"}",
                        "{\"id\":\"@jane.doe:s.example\",\"shown\":\"Jane\",\"state\":\"join\",\"kind\":\"other\"}");
    }

    @Test
    void onlyJoinedAndInvitedMembersCountEachOnce() {
        final JsonNode answer =
                Members.answer(
                        "#porelo-demo:" + SERVER,
                        "demo",
                        SERVER,
                        null,
                        List.of(
                                event("@left:" + SERVER, "leave", "Left"),
                                event("@banned:" + SERVER, "ban", "Banned"),
                                event("@knocking:" + SERVER, "knock", "Knocking"),
                                event("@twice:" + SERVER, "invite", "Invited"),
                                event("@twice:" + SERVER, "join", "Joined"),
                                event("@thrice:" + SERVER, "join", "Joined"),
                                event("@thrice:" + SERVER, "invite", "Invited"),
                                event("not-an-id", "join", "x")));

        assertThat(rows(answer))
                .as("no one who left, was banned or only knocks; a joined member once, as joined")
                .containsExactly(
                        "{\"id\":\"@thrice:s.example\",\"shown\":\"Joined\",\"state\":\"join\",\"kind\":\"person\"}",
                        "{\"id\":\"@twice:s.example\",\"shown\":\"Joined\",\"state\":\"join\",\"kind\":\"person\"}");
    }

    @Test
    void aShownNameIsCleanedCutAndNeverEmpty() {
        final JsonNode answer =
                Members.answer(
                        "#porelo-demo:" + SERVER,
                        "demo",
                        SERVER,
                        null,
                        List.of(
                                event("@a:" + SERVER, "join", "Ag\u0007ent‮ Pi"),
                                event("@b:" + SERVER, "join", "x".repeat(200)),
                                event("@d:" + SERVER, "join", "y".repeat(63) + "\uD83D\uDE00z"),
                                event("@c:" + SERVER, "join", " ​ ")));

        assertThat(rows(answer))
                .as(
                        "control and format characters gone, 64 code points at most, the id for nothing")
                .containsExactly(
                        "{\"id\":\"@c:s.example\",\"shown\":\"@c:s.example\",\"state\":\"join\",\"kind\":\"person\"}",
                        "{\"id\":\"@a:s.example\",\"shown\":\"Agent Pi\",\"state\":\"join\",\"kind\":\"person\"}",
                        "{\"id\":\"@b:s.example\",\"shown\":\""
                                + "x".repeat(64)
                                + "\",\"state\":\"join\",\"kind\":\"person\"}",
                        "{\"id\":\"@d:s.example\",\"shown\":\""
                                + "y".repeat(63)
                                + "\uD83D\uDE00\",\"state\":\"join\",\"kind\":\"person\"}");
    }

    /**
     * Harm guarded: an id no homeserver gives carries characters that turn a window's text around,
     * or a name of another server passes for this one's.
     */
    @Test
    void anIdNoHomeserverGivesIsLeftOutAndAnotherServersIsOther() {
        final JsonNode answer =
                Members.answer(
                        "#porelo-demo:" + SERVER,
                        "demo",
                        SERVER,
                        null,
                        List.of(
                                event("@spoof\u202E\u0007:" + SERVER, "join", null),
                                event("@ab:", "join", null),
                                event("@:" + SERVER, "join", null),
                                event("@" + "a".repeat(250) + ":" + SERVER, "join", null),
                                event("@alice:evil:" + SERVER, "join", "Alice"),
                                event("@alice/bob:" + SERVER, "join", "Bob"),
                                event("@porelo-demo-x/y:" + SERVER, "join", "Agent X")));

        assertThat(rows(answer))
                .as(
                        "no unreadable id; another server's, or a name the transport never gives, other")
                .containsExactly(
                        "{\"id\":\"@porelo-demo-x/y:s.example\",\"shown\":\"Agent X\",\"state\":\"join\",\"kind\":\"other\"}",
                        "{\"id\":\"@alice:evil:s.example\",\"shown\":\"Alice\",\"state\":\"join\",\"kind\":\"other\"}",
                        "{\"id\":\"@alice/bob:s.example\",\"shown\":\"Bob\",\"state\":\"join\",\"kind\":\"other\"}");
    }

    /** Harm guarded: a central task's name that enroll never gives is taken for one. */
    @Test
    void aCentralTaskOutsideTheNameRuleIsOther() {
        final JsonNode answer =
                Members.answer(
                        "#porelo-demo:" + SERVER,
                        "demo",
                        SERVER,
                        "ada",
                        List.of(event("@porelo-demo-x/y.ada:" + SERVER, "join", "Agent X")));

        assertThat(rows(answer))
                .as("other, with no task named")
                .containsExactly(
                        "{\"id\":\"@porelo-demo-x/y.ada:s.example\",\"shown\":\"Agent X\",\"state\":\"join\",\"kind\":\"other\"}");
    }
}
