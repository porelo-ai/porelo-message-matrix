package org.fuin.porelo.message.matrix;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Which logins were in the project's room, and which accounts are a dropped login's. */
class CentralRoomTest {

    /**
     * Harm guarded: a dropped person keeps reading the room, or the remaining person's accounts go
     * too.
     */
    @Test
    void membersInvitesPersonsAndDropsOnlyTheRemovedLoginIdempotently() throws Exception {
        try (FakeHomeserver homeserver = new FakeHomeserver()) {
            final Map<String, String> members = new LinkedHashMap<>();
            members.put("@porelo.admin:example.org", "join");
            queueRoom(homeserver, members);
            for (int i = 0; i < 4; i++) {
                homeserver.answer(200, "{}");
            }
            final JsonNode first = members(homeserver, "x-bob,bob");
            assertThat(first.path("invited"))
                    .as("both accounts of each login are invited in login order")
                    .extracting(JsonNode::asText)
                    .containsExactly(
                            "@bob:example.org",
                            "@porelo.bob:example.org",
                            "@x-bob:example.org",
                            "@porelo.x-bob:example.org");
            assertThat(first.path("removed"))
                    .as("the maker stays when absent from the list")
                    .isEmpty();
            for (final String login : List.of("bob", "x-bob")) {
                members.put("@" + login + ":example.org", "join");
                members.put("@porelo." + login + ":example.org", "invite");
                members.put("@porelo-relay-demo." + login + ":example.org", "join");
                members.put("@task.part." + login + ":example.org", "join");
            }
            queueRoom(homeserver, members);
            final JsonNode again = members(homeserver, "bob,x-bob");
            assertThat(again.path("invited"))
                    .as("already joined or invited accounts need no new invite")
                    .isEmpty();
            assertThat(again.path("removed")).as("the same list removes nobody").isEmpty();

            queueRoom(homeserver, members);
            for (int i = 0; i < 4; i++) {
                homeserver.answer(200, "{}");
            }
            members.replaceAll(
                    (id, membership) ->
                            id.endsWith(".bob:example.org") || id.equals("@bob:example.org")
                                    ? "leave"
                                    : membership);
            homeserver.answer(200, membership(members).toString());
            final JsonNode dropped = members(homeserver, "x-bob");
            assertThat(dropped.path("removed"))
                    .as("bob is out, with provisioning removed before all other accounts")
                    .extracting(JsonNode::asText)
                    .containsExactly(
                            "@porelo.bob:example.org",
                            "@bob:example.org",
                            "@porelo-relay-demo.bob:example.org",
                            "@task.part.bob:example.org");
            final List<String> kicked =
                    homeserver.requests().stream()
                            .filter(request -> request.rawPath().endsWith("/kick"))
                            .map(FakeHomeserver.Request::body)
                            .toList();
            assertThat(kicked)
                    .as("only the dropped login's accounts are kicked on the wire")
                    .hasSize(4)
                    .noneMatch(body -> body.contains("x-bob") || body.contains("admin"));
            queueRoom(homeserver, members);
            final JsonNode droppedAgain = members(homeserver, "x-bob");
            assertThat(droppedAgain.path("invited"))
                    .as("no repeated invites after removal")
                    .isEmpty();
            assertThat(droppedAgain.path("removed"))
                    .as("no repeated kicks after removal")
                    .isEmpty();
        }
    }

    private static JsonNode members(final FakeHomeserver homeserver, final String persons)
            throws Exception {
        final var input = MatrixClient.JSON.createObjectNode();
        input.putObject("settings")
                .put("homeserver", homeserver.url())
                .put("room", "#porelo-demo:example.org");
        input.putObject("secrets")
                .put(Config.ADMIN_TOKEN, "admin-token")
                .put(Config.LOGIN, "admin");
        final Transport.Result result =
                Transport.runWithInput(
                        Map.of(),
                        input.toString(),
                        "members",
                        "--project",
                        "demo",
                        "--persons",
                        persons,
                        "--login",
                        "admin");
        assertThat(result.exit()).as("members succeeds: %s", result.err()).isEqualTo(Exit.OK);
        return MatrixClient.JSON.readTree(result.out());
    }

    private static void queueRoom(
            final FakeHomeserver homeserver, final Map<String, String> members) {
        homeserver.answer(200, "{\"user_id\":\"@porelo.admin:example.org\"}");
        homeserver.answer(200, "{\"room_id\":\"!room:example.org\"}");
        homeserver.answer(
                200,
                "{\"chunk\":[{\"type\":\"m.room.create\",\"sender\":\"@porelo.admin:example.org\"}]}");
        homeserver.answer(200, "{\"join_rule\":\"invite\"}");
        homeserver.answer(200, "{\"guest_access\":\"forbidden\"}");
        homeserver.answer(200, membership(members).toString());
    }

    private static JsonNode membership(final Map<String, String> members) {
        final var answer = MatrixClient.JSON.createObjectNode();
        final var chunk = answer.putArray("chunk");
        members.forEach(
                (id, membership) ->
                        chunk.addObject()
                                .put("state_key", id)
                                .putObject("content")
                                .put("membership", membership));
        return answer;
    }

    private static Map<String, String> members() {
        final Map<String, String> members = new LinkedHashMap<>();
        members.put("@carol:x", "join");
        members.put("@hosta:x", "join");
        members.put("@task.hosta:x", "join");
        members.put("@porelo.hosta:x", "join");
        members.put("@gone.hosta:x", "leave");
        members.put("@porelo-relay-demo.hostb:x", "invite");
        members.put("@porelo.hostc:x", "leave");
        members.put("@porelo.hoste:x", "leave");
        members.put("@porelo-relay-demo.hoste:x", "leave");
        members.put("@t.hoste:x", "join");
        members.put("@job.x-hosta:x", "join");
        members.put("@porelo.x-hosta:x", "leave");
        members.put("@porelo-relay-demo.v2.hostf:x", "join");
        members.put("@porelo.hostd:elsewhere", "join");
        return members;
    }

    /**
     * Harm guarded: a login whose {@code @porelo} and relay left while its tasks stayed is not
     * seen, so the list never takes its tasks out; a dot in a project or task name must not be read
     * as part of the login.
     */
    @Test
    @DisplayName(
            "Any account identifies its login on this server, including a left provisioning account")
    void loginsEverInTheRoom() {
        assertThat(CentralRoom.logins(members(), "x"))
                .as("the logins ever in the room")
                .containsExactly("carol", "hosta", "hostb", "hostc", "hoste", "hostf", "x-hosta");
    }

    /**
     * Harm guarded: a dropped login's tasks stay once its {@code @porelo} left, or dropping one
     * login kicks an account that left already or another login's.
     */
    @Test
    @DisplayName(
            "Dropped logins' accounts in the room are theirs alone, each login's @porelo first")
    void droppedLoginsAccounts() {
        assertThat(
                        CentralRoom.accounts(
                                members(),
                                new TreeSet<>(Set.of("hosta", "hoste")),
                                Set.of("admin", "x-hosta"),
                                "x"))
                .as("the accounts of hosta and hoste, never one the kept x-hosta could claim")
                .containsExactly("@porelo.hosta:x", "@hosta:x", "@task.hosta:x", "@t.hoste:x");
    }

    /**
     * Harm guarded: with {@code hosta} and {@code x-hosta} both dropped, {@code x-hosta}'s task is
     * kicked while its {@code @porelo} is still in to invite it back.
     */
    @Test
    @DisplayName("Every dropped login's @porelo goes before any of their other accounts")
    void everyDroppedPoreloFirst() {
        final Map<String, String> members = new LinkedHashMap<>();
        members.put("@job.x-hosta:x", "join");
        members.put("@porelo.x-hosta:x", "join");
        members.put("@porelo.hosta:x", "join");

        assertThat(
                        CentralRoom.accounts(
                                members,
                                new TreeSet<>(Set.of("hosta", "x-hosta")),
                                Set.of("admin"),
                                "x"))
                .as("both provisioning accounts are removed before the task")
                .containsExactly("@porelo.hosta:x", "@porelo.x-hosta:x", "@job.x-hosta:x");
    }

    /**
     * Harm guarded: a login dropped whose name ends as a kept one's ({@code x-dev} beside {@code
     * dev}) takes the kept login's tasks and relay out, or keeps its own @porelo in the room.
     */
    @Test
    @DisplayName(
            "A kept login's accounts are never touched, and a dropped one's @porelo and relay are its own")
    void aKeptLoginsAccountsAreNeverTouched() {
        final Map<String, String> members = new LinkedHashMap<>();
        members.put("@porelo.x-dev:x", "invite");
        members.put("@porelo-relay-demo.x-dev:x", "join");
        members.put("@t.x-dev:x", "join");

        assertThat(CentralRoom.accounts(members, Set.of("x-dev"), Set.of("dev"), "x"))
                .as("x-dev owns its provisioning account, relay and task")
                .containsExactly("@porelo.x-dev:x", "@porelo-relay-demo.x-dev:x", "@t.x-dev:x");
        assertThat(CentralRoom.accounts(members, Set.of("dev"), Set.of("x-dev"), "x"))
                .as("dropping dev leaves every account of the kept x-dev")
                .isEmpty();
    }
}
