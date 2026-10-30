package org.fuin.porelo.message.matrix;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * The project's room on a central homeserver, made and kept by the project admin's Porelo: made by
 * its {@code @porelo.<login>} as the account's own homeserver makes its room, and kept to the
 * logins of the project's signed developer list. Each listed login's {@code @porelo.<login>} and
 * its person's own account are invited, and every account of a login the list no longer names is
 * taken out of this one room; nothing else in the room or on the server is touched.
 */
final class CentralRoom {

    /** How often {@code members} reads the room again while an account it took out is still in. */
    private static final int ROUNDS = 5;

    private static final String REASON = "No longer on the project's developer list";

    private CentralRoom() {}

    /**
     * The room under the project's alias, made by {@code maker} or taken only if {@code maker} made
     * it. The project's settings name that alias, so every developer's login joins the room made
     * here.
     */
    static String make(
            final MatrixClient asMaker,
            final String maker,
            final String project,
            final String configuredRoom)
            throws Failure {
        final String server = AdminRoom.server(maker);
        final String alias = Lifecycle.alias(project, server);
        if (!alias.equals(configuredRoom)) {
            throw new Failure(
                    Exit.CONFIG,
                    "--create-room makes "
                            + alias
                            + ", and the project's settings name the room "
                            + configuredRoom
                            + "; name "
                            + alias
                            + " as the project's room");
        }
        return Lifecycle.room(asMaker, maker, project, server);
    }

    /**
     * Declarative and idempotent: invites each listed login's {@code @porelo.<login>} not yet
     * invited or joined, then, for every login ever in the room the list no longer names, kicks
     * every {@code @….<login>} from this room, {@code @porelo.<login>} first, reading the room
     * again until none of them is left, so a dropped login invites no other back. The maker's own
     * login is never taken out, nor any account a listed login's name could claim. Refused before
     * anything is done for a listed login banned from the room.
     */
    static ObjectNode members(
            final MatrixClient asMaker,
            final String login,
            final String project,
            final String configuredRoom,
            final List<String> logins)
            throws Failure {
        final String maker = Accounts.whoami(asMaker);
        final String server = AdminRoom.server(maker);
        if (!maker.equals(porelo(login, server))) {
            throw new Failure(
                    Exit.CONFIG,
                    "members runs as "
                            + porelo(login, server)
                            + ", and the token Porelo kept is "
                            + maker
                            + "'s");
        }
        final String alias = Lifecycle.alias(project, server);
        if (!alias.equals(configuredRoom)) {
            throw new Failure(
                    Exit.CONFIG,
                    "members keeps the room "
                            + alias
                            + " that setup --create-room made, and the project's settings name "
                            + configuredRoom);
        }
        final JsonNode found =
                asMaker.find("/_matrix/client/v3/directory/room/" + MatrixClient.segment(alias));
        if (found == null) {
            throw new Failure(
                    Exit.CONFIG,
                    "there is no room " + alias + " yet; setup --create-room makes it first");
        }
        final String room = Lifecycle.ours(asMaker, maker, alias, found.path("room_id").asText());
        final String path = "/_matrix/client/v3/rooms/" + MatrixClient.segment(room);

        final List<String> listed = new ArrayList<>(new TreeSet<>(logins));
        final Set<String> kept = new TreeSet<>(listed);
        kept.add(login);
        Map<String, String> members = members(asMaker, path);
        for (final String other : listed) {
            for (final String id : List.of("@" + other + ":" + server, porelo(other, server))) {
                if ("ban".equals(members.get(id))) {
                    throw new Failure(
                            Exit.CONFIG,
                            id
                                    + " is banned from "
                                    + alias
                                    + "; a person with the power to do so lifts the ban, then members"
                                    + " invites it");
                }
            }
        }

        final ObjectNode answer = MatrixClient.JSON.createObjectNode();
        final var invited = answer.putArray("invited");
        for (final String other : listed) {
            for (final String id : List.of("@" + other + ":" + server, porelo(other, server))) {
                if (!in(members.getOrDefault(id, ""))) {
                    asMaker.post(
                            path + "/invite",
                            MatrixClient.JSON.createObjectNode().put("user_id", id));
                    invited.add(id);
                }
            }
        }

        final var removed = answer.putArray("removed");
        List<String> left = left(members, kept, server);
        for (int round = 0; !left.isEmpty(); round++) {
            if (round == ROUNDS) {
                throw new Failure(
                        Exit.TEMPORARY,
                        "accounts of logins dropped are still in "
                                + alias
                                + " after "
                                + ROUNDS
                                + " rounds: "
                                + String.join(", ", left));
            }
            for (final String id : left) {
                try {
                    asMaker.post(
                            path + "/kick",
                            MatrixClient.JSON
                                    .createObjectNode()
                                    .put("user_id", id)
                                    .put("reason", REASON));
                    removed.add(id);
                } catch (final Failure failure) {
                    // One that left by itself meanwhile is refused a kick; the room read next says.
                    if (failure.exitCode() != Exit.NOT_PERMITTED) {
                        throw failure;
                    }
                }
            }
            members = members(asMaker, path);
            left = left(members, kept, server);
        }
        return answer;
    }

    /**
     * The accounts in the room of every login ever in it that is not kept, read anew each round, so
     * a login a dropped one invited meanwhile is taken out too.
     */
    private static List<String> left(
            final Map<String, String> members, final Set<String> kept, final String server) {
        final Set<String> dropped = logins(members, server);
        dropped.removeAll(kept);
        return accounts(members, dropped, kept, server);
    }

    /** Every member of the room by id, with its membership. */
    private static Map<String, String> members(final MatrixClient asMaker, final String path)
            throws Failure {
        final Map<String, String> members = new LinkedHashMap<>();
        for (final JsonNode event : asMaker.get(path + "/members").path("chunk")) {
            members.put(
                    event.path("state_key").asText(""),
                    event.path("content").path("membership").asText(""));
        }
        return members;
    }

    /**
     * Every login ever in the room, including one whose provisioning account already left. The last
     * dot separates the login from a task or relay name that may itself hold dots.
     */
    static Set<String> logins(final Map<String, String> members, final String server) {
        final Set<String> logins = new TreeSet<>();
        for (final String id : members.keySet()) {
            final String login = Login.owner(id, server);
            if (login != null) {
                logins.add(login);
            }
        }
        return logins;
    }

    /**
     * The dropped logins' own accounts, relays and tasks in the room, every provisioning account
     * first, so none invites its tasks back. A kept login's accounts are never touched.
     */
    static List<String> accounts(
            final Map<String, String> members,
            final Set<String> dropped,
            final Set<String> kept,
            final String server) {
        final Set<String> porelos = new LinkedHashSet<>();
        final Set<String> others = new LinkedHashSet<>();
        for (final String login : dropped) {
            final String own = porelo(login, server);
            if (kept.contains(login)) {
                continue;
            }
            for (final Map.Entry<String, String> member : members.entrySet()) {
                final String id = member.getKey();
                if (!in(member.getValue()) || !Login.owns(login, id, server)) {
                    continue;
                }
                if (id.equals(own)) {
                    porelos.add(id);
                } else {
                    others.add(id);
                }
            }
        }
        others.removeAll(porelos);
        final List<String> found = new ArrayList<>(porelos);
        found.addAll(others);
        return found;
    }

    private static boolean in(final String membership) {
        return "join".equals(membership) || "invite".equals(membership);
    }

    private static String porelo(final String login, final String server) {
        return "@" + Lifecycle.ADMIN + "." + login + ":" + server;
    }
}
