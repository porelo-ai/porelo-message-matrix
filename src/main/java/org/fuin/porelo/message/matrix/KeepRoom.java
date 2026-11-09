package org.fuin.porelo.message.matrix;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/**
 * A project's room behind the host's gate, kept by the host's helper as the homeserver's
 * administrator: no person's Porelo needs to run, and no client can make or take the room.
 */
final class KeepRoom {

    /** As many persons as a room is kept to, each with its accounts beside it. */
    private static final int MAX_PERSONS = 500;

    /** How often the room is read again while an account taken out is still in. */
    private static final int ROUNDS = 5;

    private static final String REASON = "The project left this host";

    private static final String NOT_NAMED = "No longer among the project's persons";

    private KeepRoom() {}

    private record Options(String project, List<String> persons) {}

    private record Administrator(MatrixClient client, String id, String server) {
        String alias(final String project) {
            return Lifecycle.alias(project, server);
        }

        @Nullable String room(final String project) throws Failure {
            final JsonNode found =
                    client.find(
                            "/_matrix/client/v3/directory/room/"
                                    + MatrixClient.segment(alias(project)));
            return found == null
                    ? null
                    : Lifecycle.ours(client, id, alias(project), found.path("room_id").asText());
        }
    }

    static void run(
            final String verb,
            final List<String> args,
            final Map<String, String> env,
            final InputStream settings,
            final PrintStream out,
            final PrintStream err)
            throws Failure {
        final Options options = options(verb, args);
        // Checked before anything is looked at, so a bad name changes nothing.
        final Map<String, String> names =
                "keep-room".equals(verb) ? Names.read(settings, options.persons()) : Map.of();
        // Elsewhere nothing is started: the transport's package needs no homeserver of its own.
        if (!Expel.gated(env)) {
            throw new Failure(
                    Exit.NOT_PERMITTED, verb + " keeps a room behind a host's gate; none is here");
        }
        final String project = options.project();
        if (!GateRules.project(project)) {
            throw new Failure(Exit.CONFIG, verb + ": '" + project + "' is no project's name");
        }
        if (Lifecycle.DEFAULT_PROJECT.equals(project) && !"drop-room".equals(verb)) {
            throw new Failure(
                    Exit.CONFIG,
                    verb + " refused: the project " + project + " has no conversation, so no room");
        }
        final String token = env.get(Config.ADMIN_TOKEN);
        if (token == null || token.isBlank()) {
            throw new Failure(
                    Exit.CONFIG,
                    verb
                            + " needs the homeserver administrator's token among the secrets on stdin");
        }
        final Administrator admin = administrator(Expel.behindGate(env), env, err);
        out.println(
                "keep-room".equals(verb)
                        ? keep(
                                admin,
                                project,
                                options.persons(),
                                names,
                                LocalHomeserver.directory(env))
                        : drop(admin, project));
    }

    private static Options options(final String verb, final List<String> args) throws Failure {
        final boolean keeps = "keep-room".equals(verb);
        final int size = keeps ? 4 : 2;
        if (args.size() != size
                || !"--project".equals(args.get(0))
                || keeps && !"--persons".equals(args.get(2))) {
            throw new Failure(
                    Exit.USAGE,
                    "usage: "
                            + verb
                            + " --project <project>"
                            + (keeps ? " --persons <login>,…" : ""));
        }
        return new Options(args.get(1), keeps ? persons(args.get(3)) : List.of());
    }

    /** The sorted logins kept in the room; an empty value is a project with no members. */
    private static List<String> persons(final String value) throws Failure {
        final Set<String> persons = new TreeSet<>();
        if (!value.isEmpty()) {
            for (final String name : value.split(",", -1)) {
                persons.add(Login.name(name));
            }
        }
        if (persons.size() > MAX_PERSONS) {
            throw new Failure(
                    Exit.USAGE,
                    "--persons names at most " + MAX_PERSONS + " persons, not " + persons.size());
        }
        return List.copyOf(persons);
    }

    private static Administrator administrator(
            final MatrixClient nobody, final Map<String, String> env, final PrintStream err)
            throws Failure {
        final Accounts.Session session = Lifecycle.admin(nobody, env, "", null, err);
        final String server = AdminRoom.server(session.userId());
        if (!session.userId().equals("@" + Lifecycle.ADMIN + ":" + server)) {
            throw new Failure(
                    Exit.CONFIG, "the token on stdin is not the homeserver administrator's");
        }
        return new Administrator(nobody.as(session.accessToken()), session.userId(), server);
    }

    private static ObjectNode keep(
            final Administrator admin,
            final String project,
            final List<String> persons,
            final Map<String, String> names,
            final Path home)
            throws Failure {
        final String alias = admin.alias(project);
        final String existing = admin.room(project);
        final String room =
                existing == null
                        ? Lifecycle.room(admin.client(), admin.id(), project, admin.server())
                        : existing;
        final ObjectNode kept =
                keepTo(admin.client(), room, alias, admin.server(), persons, names, home);
        final ObjectNode answer =
                MatrixClient.JSON
                        .createObjectNode()
                        .put("room", alias)
                        .put("conversation", room)
                        .put("created_room", existing == null);
        answer.setAll(kept);
        return answer;
    }

    /**
     * Everybody out, then the room with its alias and conversation. Nobody's account goes: a
     * deactivated name stays taken, and a person keeps their accounts for other projects.
     */
    private static ObjectNode drop(final Administrator admin, final String project) throws Failure {
        final String alias = admin.alias(project);
        final ObjectNode answer = MatrixClient.JSON.createObjectNode().put("room", alias);
        final String room = admin.room(project);
        if (room == null) {
            answer.put("deleted", false).putArray("removed");
            return answer;
        }
        final String path = "/_matrix/client/v3/rooms/" + MatrixClient.segment(room);
        final var removed =
                takeOut(
                        admin.client(),
                        path,
                        members(admin.client(), path),
                        alias,
                        REASON,
                        "members",
                        members -> everybodyBut(admin.id(), members));
        AdminRoom.of(admin.client(), admin.id()).deleteRoom(room);
        answer.put("deleted", true).set("removed", removed);
        return answer;
    }

    /** The provisioning accounts first, so none invites its tasks back. */
    private static List<String> everybodyBut(
            final String administrator, final Map<String, String> members) {
        final List<String> first = new ArrayList<>();
        final List<String> then = new ArrayList<>();
        members.forEach(
                (id, membership) -> {
                    if (in(membership) && !id.equals(administrator)) {
                        (id.startsWith("@" + Lifecycle.ADMIN + ".") ? first : then).add(id);
                    }
                });
        first.addAll(then);
        return first;
    }

    /**
     * Declarative and idempotent: invites each listed login's own account and
     * {@code @porelo.<login>} not yet invited or joined, then kicks every account of each login
     * ever in the room the list no longer names, {@code @porelo.<login>} first. Refused before
     * anything is done for a listed login banned from the room.
     */
    static ObjectNode keepTo(
            final MatrixClient asMaker,
            final String room,
            final String alias,
            final String server,
            final List<String> logins)
            throws Failure {
        return keepTo(asMaker, room, alias, server, logins, Map.of(), Path.of("/nonexistent"));
    }

    /**
     * As {@link #keepTo(MatrixClient, String, String, String, List)}, each named login's account
     * given its name after the ban check and before it is invited, so its invitation shows it.
     */
    static ObjectNode keepTo(
            final MatrixClient asMaker,
            final String room,
            final String alias,
            final String server,
            final List<String> logins,
            final Map<String, String> names,
            final Path home)
            throws Failure {
        final String path = "/_matrix/client/v3/rooms/" + MatrixClient.segment(room);
        final List<String> listed = new ArrayList<>(new TreeSet<>(logins));
        final Set<String> kept = new TreeSet<>(listed);
        final Map<String, String> members = members(asMaker, path);
        for (final String other : listed) {
            for (final String id : List.of("@" + other + ":" + server, porelo(other, server))) {
                if ("ban".equals(members.get(id))) {
                    throw new Failure(
                            Exit.CONFIG,
                            id
                                    + " is banned from "
                                    + alias
                                    + "; a person with the power to do so lifts the ban, and the"
                                    + " room's next keeping invites it");
                }
            }
        }

        Names.give(asMaker, server, names, home);

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
        answer.set(
                "removed",
                takeOut(
                        asMaker,
                        path,
                        members,
                        alias,
                        NOT_NAMED,
                        "accounts of logins dropped",
                        now -> left(now, kept, server)));
        return answer;
    }

    /**
     * Kicks whoever {@code left} says of {@code read} is still in, reading the room again until
     * nobody is, so an account one of them invited meanwhile goes too; 75 after {@value #ROUNDS}
     * rounds.
     */
    static ArrayNode takeOut(
            final MatrixClient asMaker,
            final String path,
            final Map<String, String> read,
            final String alias,
            final String reason,
            final String who,
            final Function<Map<String, String>, List<String>> left)
            throws Failure {
        final ArrayNode removed = MatrixClient.JSON.createArrayNode();
        List<String> still = left.apply(read);
        for (int round = 0; !still.isEmpty(); round++) {
            if (round == ROUNDS) {
                throw new Failure(
                        Exit.TEMPORARY,
                        who
                                + " are still in "
                                + alias
                                + " after "
                                + ROUNDS
                                + " rounds: "
                                + String.join(", ", still));
            }
            for (final String id : still) {
                try {
                    asMaker.post(
                            path + "/kick",
                            MatrixClient.JSON
                                    .createObjectNode()
                                    .put("user_id", id)
                                    .put("reason", reason));
                    removed.add(id);
                } catch (final Failure failure) {
                    // One that left by itself meanwhile is refused a kick; the room read next says.
                    if (failure.exitCode() != Exit.NOT_PERMITTED) {
                        throw failure;
                    }
                }
            }
            still = left.apply(members(asMaker, path));
        }
        return removed;
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
    static Map<String, String> members(final MatrixClient asMaker, final String path)
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

    static boolean in(final String membership) {
        return "join".equals(membership) || "invite".equals(membership);
    }

    private static String porelo(final String login, final String server) {
        return "@" + Lifecycle.ADMIN + "." + login + ":" + server;
    }
}
