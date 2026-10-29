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
 * its {@code @porelo.<machine>} as the account's own homeserver makes its room, and kept to the
 * machines of the project's signed developer list. Each listed machine's {@code @porelo.<machine>}
 * is invited, and every account of a machine the list no longer names is taken out of this one
 * room; nothing else in the room or on the server is touched.
 */
final class CentralRoom {

    /** How often {@code members} reads the room again while an account it took out is still in. */
    private static final int ROUNDS = 5;

    private static final String REASON = "No longer on the project's developer list";

    private CentralRoom() {}

    /**
     * The room under the project's alias, made by {@code maker} or taken only if {@code maker} made
     * it. The project's settings name that alias, so every developer's machine joins the room made
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
     * Declarative and idempotent: invites each listed machine's {@code @porelo.<machine>} not yet
     * invited or joined, then, for every machine ever in the room the list no longer names, kicks
     * every {@code @….<machine>} from this room, {@code @porelo.<machine>} first, reading the room
     * again until none of them is left, so a dropped machine invites no other back. The maker's own
     * machine is never taken out, nor any account a listed machine's name could claim. Refused
     * before anything is done for a listed machine banned from the room.
     */
    static ObjectNode members(
            final MatrixClient asMaker,
            final String machine,
            final String project,
            final String configuredRoom,
            final List<String> machines)
            throws Failure {
        final String maker = Accounts.whoami(asMaker);
        final String server = AdminRoom.server(maker);
        if (!maker.equals(porelo(machine, server))) {
            throw new Failure(
                    Exit.CONFIG,
                    "members runs as "
                            + porelo(machine, server)
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

        final List<String> listed = new ArrayList<>(machines);
        listed.remove(machine);
        final Set<String> kept = new TreeSet<>(listed);
        kept.add(machine);
        Map<String, String> members = members(asMaker, path);
        for (final String other : listed) {
            final String id = porelo(other, server);
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

        final ObjectNode answer = MatrixClient.JSON.createObjectNode();
        final var invited = answer.putArray("invited");
        for (final String other : listed) {
            final String id = porelo(other, server);
            if (!in(members.getOrDefault(id, ""))) {
                asMaker.post(
                        path + "/invite", MatrixClient.JSON.createObjectNode().put("user_id", id));
                invited.add(id);
            }
        }

        final var removed = answer.putArray("removed");
        List<String> left = left(members, kept, project, server);
        for (int round = 0; !left.isEmpty(); round++) {
            if (round == ROUNDS) {
                throw new Failure(
                        Exit.TEMPORARY,
                        "accounts of machines dropped are still in "
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
            left = left(members, kept, project, server);
        }
        return answer;
    }

    /**
     * The accounts in the room of every machine ever in it that is not kept, read anew each round,
     * so a machine a dropped one invited meanwhile is taken out too.
     */
    private static List<String> left(
            final Map<String, String> members,
            final Set<String> kept,
            final String project,
            final String server) {
        final Set<String> dropped = machines(members, server);
        dropped.removeAll(kept);
        return accounts(members, dropped, kept, project, server);
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
     * Every machine ever in the room by its {@code @porelo.<machine>} of any membership, a left one
     * too, so a machine whose {@code @porelo} went while its tasks stayed is still seen. A relay's
     * name is not read, since a project's name may hold a dot.
     */
    static Set<String> machines(final Map<String, String> members, final String server) {
        final Set<String> machines = new TreeSet<>();
        final String prefix = "@" + Lifecycle.ADMIN + ".";
        final String suffix = ":" + server;
        for (final String id : members.keySet()) {
            if (id.startsWith(prefix) && id.endsWith(suffix)) {
                final String name = id.substring(prefix.length(), id.length() - suffix.length());
                if (!name.isEmpty()) {
                    machines.add(name);
                }
            }
        }
        return machines;
    }

    /**
     * The dropped machines' accounts invited to or joined in the room, their
     * {@code @porelo.<machine>} first, so none invites its tasks back while they go. A machine's
     * {@code @porelo} and its relay in this project are its own by name. Any other
     * {@code @….<machine>} is the machine's, a person taking no name ending as a machine's, unless
     * its name ends as a kept machine's too ({@code @job.x.hosta} with {@code x.hosta} kept and
     * {@code hosta} dropped): a kept machine's account is never touched.
     */
    static List<String> accounts(
            final Map<String, String> members,
            final Set<String> dropped,
            final Set<String> kept,
            final String project,
            final String server) {
        final Set<String> porelos = new LinkedHashSet<>();
        final Set<String> others = new LinkedHashSet<>();
        for (final String machine : dropped) {
            final String own = porelo(machine, server);
            final String relay = "@porelo-relay-" + project + "." + machine + ":" + server;
            final String ending = "." + machine + ":" + server;
            for (final Map.Entry<String, String> member : members.entrySet()) {
                final String id = member.getKey();
                if (!in(member.getValue()) || !id.endsWith(ending)) {
                    continue;
                }
                if (id.equals(own)) {
                    porelos.add(id);
                } else if (id.equals(relay)
                        || kept.stream()
                                .noneMatch(other -> id.endsWith("." + other + ":" + server))) {
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

    private static String porelo(final String machine, final String server) {
        return "@" + Lifecycle.ADMIN + "." + machine + ":" + server;
    }
}
