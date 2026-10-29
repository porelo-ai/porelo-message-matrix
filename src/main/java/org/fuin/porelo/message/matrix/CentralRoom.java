package org.fuin.porelo.message.matrix;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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
     * invited or joined, then, for each machine in the room the list no longer names, kicks every
     * {@code @….<machine>} from this room, {@code @porelo.<machine>} first, reading the room again
     * until none is left. The maker's own machine is never taken out.
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
        Map<String, String> members = members(asMaker, path);
        final ObjectNode answer = MatrixClient.JSON.createObjectNode();
        final var invited = answer.putArray("invited");
        for (final String other : listed) {
            final String id = porelo(other, server);
            final String membership = members.getOrDefault(id, "");
            if ("join".equals(membership) || "invite".equals(membership)) {
                continue;
            }
            if ("ban".equals(membership)) {
                throw new Failure(
                        Exit.CONFIG,
                        id
                                + " is banned from "
                                + alias
                                + "; a person with the power to do so lifts the ban, then members"
                                + " invites it");
            }
            asMaker.post(path + "/invite", MatrixClient.JSON.createObjectNode().put("user_id", id));
            invited.add(id);
        }

        final Set<String> present = present(members, project, server);
        final Set<String> known = new TreeSet<>(present);
        known.addAll(listed);
        known.add(machine);
        final var removed = answer.putArray("removed");
        for (final String dropped : present) {
            if (listed.contains(dropped) || dropped.equals(machine)) {
                continue;
            }
            List<String> left = accounts(members, dropped, server, known);
            for (int round = 0; !left.isEmpty(); round++) {
                if (round == ROUNDS) {
                    throw new Failure(
                            Exit.TEMPORARY,
                            "accounts of "
                                    + dropped
                                    + " are still in "
                                    + alias
                                    + " after "
                                    + ROUNDS
                                    + " rounds: "
                                    + String.join(", ", left));
                }
                for (final String id : left) {
                    asMaker.post(
                            path + "/kick",
                            MatrixClient.JSON
                                    .createObjectNode()
                                    .put("user_id", id)
                                    .put("reason", REASON));
                    removed.add(id);
                }
                members = members(asMaker, path);
                left = accounts(members, dropped, server, known);
            }
        }
        return answer;
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
     * The machines in the room: those whose {@code @porelo.<machine>} or relay is invited or
     * joined.
     */
    static Set<String> present(
            final Map<String, String> members, final String project, final String server) {
        final Set<String> present = new TreeSet<>();
        final String suffix = ":" + server;
        for (final Map.Entry<String, String> member : members.entrySet()) {
            final String id = member.getKey();
            if (!in(member.getValue()) || !id.endsWith(suffix)) {
                continue;
            }
            for (final String prefix :
                    List.of("@" + Lifecycle.ADMIN + ".", "@porelo-relay-" + project + ".")) {
                if (id.startsWith(prefix)) {
                    final String name =
                            id.substring(prefix.length(), id.length() - suffix.length());
                    if (!name.isEmpty()) {
                        present.add(name);
                    }
                }
            }
        }
        return present;
    }

    /**
     * The dropped machine's accounts invited to or joined in the room, {@code @porelo.<machine>}
     * first. An account whose name ends as a longer machine's that is known in the room is that
     * machine's.
     */
    static List<String> accounts(
            final Map<String, String> members,
            final String dropped,
            final String server,
            final Set<String> known) {
        final String ending = "." + dropped + ":" + server;
        final List<String> longer =
                known.stream()
                        .filter(other -> other.endsWith("." + dropped))
                        .map(other -> "." + other + ":" + server)
                        .toList();
        final List<String> found = new ArrayList<>();
        for (final Map.Entry<String, String> member : members.entrySet()) {
            final String id = member.getKey();
            if (!in(member.getValue())
                    || !id.endsWith(ending)
                    || longer.stream().anyMatch(id::endsWith)) {
                continue;
            }
            if (id.equals(porelo(dropped, server))) {
                found.addFirst(id);
            } else {
                found.add(id);
            }
        }
        return found;
    }

    private static boolean in(final String membership) {
        return "join".equals(membership) || "invite".equals(membership);
    }

    private static String porelo(final String machine, final String server) {
        return "@" + Lifecycle.ADMIN + "." + machine + ":" + server;
    }
}
