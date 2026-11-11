package org.fuin.porelo.message.matrix;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Who is in a project's room, as {@code members} answers it: each joined or invited account with
 * the name the room shows for it and what it is, read from its id alone.
 */
final class Members {

    /** The kinds in the order the answer lists them. */
    private static final List<String> KINDS = List.of("task", "person", "helper", "other");

    private static final Pattern UNSHOWN = Pattern.compile("[\\p{Cc}\\p{Cf}]");

    private static final int SHOWN_LENGTH = 64;

    /**
     * A user id as Matrix allows one: printable ASCII, a localpart, a server, 255 bytes at most.
     */
    private static final Pattern USER_ID =
            Pattern.compile("@[\\x21-\\x39\\x3b-\\x7e]+:[\\x21-\\x7e]+");

    private static final int USER_ID_LENGTH = 255;

    private record Member(String id, String shown, String state, Kind kind) {}

    private record Kind(String name, @Nullable String task, @Nullable String login) {}

    private Members() {}

    /**
     * The answer for {@code project}'s room on {@code server}, from its member events; {@code
     * login} is the caller's on a central homeserver, {@code null} on the account's own.
     */
    static ObjectNode answer(
            final String alias,
            final String project,
            final String server,
            final @Nullable String login,
            final List<JsonNode> events) {
        final Map<String, Member> members = new LinkedHashMap<>();
        for (final JsonNode event : events) {
            final String id = event.path("state_key").asText("");
            final String state = event.path("content").path("membership").asText("");
            if (id.length() > USER_ID_LENGTH || !USER_ID.matcher(id).matches()) {
                // Not an id a homeserver gives; nothing of it is passed on to be shown.
                continue;
            }
            if (!"join".equals(state) && !"invite".equals(state)) {
                continue;
            }
            final Member earlier = members.get(id);
            if (earlier != null && "join".equals(earlier.state())) {
                continue;
            }
            members.put(
                    id,
                    new Member(
                            id,
                            shown(id, event.path("content").path("displayname").asText("")),
                            state,
                            kind(id, project, server, login != null)));
        }
        final List<Member> ordered = new ArrayList<>(members.values());
        ordered.sort(
                Comparator.comparingInt((Member m) -> KINDS.indexOf(m.kind().name()))
                        .thenComparing(Member::shown)
                        .thenComparing(Member::id));
        final ObjectNode answer = MatrixClient.JSON.createObjectNode().put("room", alias);
        final ArrayNode list = answer.putArray("members");
        for (final Member member : ordered) {
            final ObjectNode row =
                    list.addObject()
                            .put("id", member.id())
                            .put("shown", member.shown())
                            .put("state", member.state())
                            .put("kind", member.kind().name());
            if (member.kind().task() != null) {
                row.put("task", member.kind().task());
            }
            if (member.kind().login() != null) {
                row.put("login", member.kind().login());
            }
        }
        return answer;
    }

    /** The room's name for a member: cleaned of what no reader sees, never longer, never empty. */
    static String shown(final String id, final String displayName) {
        final String clean = UNSHOWN.matcher(displayName).replaceAll("").strip();
        if (clean.isEmpty()) {
            return id;
        }
        return clean.codePointCount(0, clean.length()) <= SHOWN_LENGTH
                ? clean
                : clean.substring(0, clean.offsetByCodePoints(0, SHOWN_LENGTH));
    }

    /** What an account is, read from its id: helpers first, so none passes for a task or person. */
    private static Kind kind(
            final String id, final String project, final String server, final boolean central) {
        final int colon = id.indexOf(':');
        if (!server.equals(id.substring(colon + 1))) {
            return new Kind("other", null, null);
        }
        final String localpart = id.substring(1, colon);
        if (Lifecycle.ADMIN.equals(localpart)
                || localpart.startsWith(Lifecycle.ADMIN + ".")
                || localpart.startsWith("porelo-relay-")) {
            return new Kind("helper", null, null);
        }
        if (central) {
            final int dot = localpart.lastIndexOf('.');
            if (dot > 0) {
                final String task = localpart.substring(0, dot);
                final String owner = localpart.substring(dot + 1);
                if (task(task, project) && Login.valid(owner)) {
                    return new Kind("task", task, owner);
                }
            }
            return Login.person(id, server)
                    ? new Kind("person", null, null)
                    : new Kind("other", null, null);
        }
        if (task(localpart, project)) {
            return new Kind("task", localpart, null);
        }
        // join gives a person a name of the transport's, never one starting so.
        return named(localpart) && !localpart.startsWith(Lifecycle.ADMIN)
                ? new Kind("person", null, null)
                : new Kind("other", null, null);
    }

    /**
     * Whether {@code name} is a task of {@code project} as this class reads one, {@code
     * porelo-<project>-<task>}: the one rule {@code enroll} and {@code members} share.
     */
    static boolean task(final String name, final String project) {
        final String prefix = "porelo-" + project + "-";
        return name.startsWith(prefix)
                && name.length() > prefix.length()
                && !name.startsWith("porelo-relay-")
                // The host's gate takes no task ending in a dot before its login.
                && !name.endsWith(".")
                && named(name);
    }

    private static boolean named(final String name) {
        return Lifecycle.NAME.matcher(name).matches();
    }
}
