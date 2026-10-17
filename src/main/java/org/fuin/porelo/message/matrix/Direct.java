package org.fuin.porelo.message.matrix;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.jspecify.annotations.Nullable;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * A task's direct chats with the people of its project. Which rooms are direct chats is what Matrix keeps for
 * every client, the account's {@code m.direct}: a person's client writes it on their side, this transport on
 * the task's. An invitation is taken only from somebody who already shares a room with the task's account -
 * the project's room - so nobody else can open a way in.
 */
final class Direct {

    /** Why an encrypted chat is declined, as the person's client shows it. */
    static final String ENCRYPTED = "Porelo's tasks cannot read an encrypted chat: open it again with encryption off";

    private Direct() {
    }

    /** The account's direct chats, by the other person: as {@code m.direct} has them, empty when it has none. */
    static Map<String, Set<String>> rooms(final MatrixClient client, final String self) throws Failure {
        final JsonNode data = client.find("/_matrix/client/v3/user/" + MatrixClient.segment(self) + "/account_data/m.direct");
        final Map<String, Set<String>> rooms = new java.util.LinkedHashMap<>();
        if (data != null) {
            for (final Map.Entry<String, JsonNode> entry : data.properties()) {
                final Set<String> ids = new LinkedHashSet<>();
                entry.getValue().forEach(id -> ids.add(id.asText("")));
                rooms.put(entry.getKey(), ids);
            }
        }
        return rooms;
    }

    /** Adds a room to the account's {@code m.direct} for that person, keeping what is there. */
    static void remember(final MatrixClient client, final String self, final String person, final String room)
            throws Failure {
        final Map<String, Set<String>> rooms = rooms(client, self);
        rooms.computeIfAbsent(person, p -> new LinkedHashSet<>()).add(room);
        final ObjectNode data = MatrixClient.JSON.createObjectNode();
        rooms.forEach((who, ids) -> {
            final ArrayNode list = data.putArray(who);
            ids.forEach(list::add);
        });
        client.put("/_matrix/client/v3/user/" + MatrixClient.segment(self) + "/account_data/m.direct", data);
    }

    /**
     * Answers the invitations of a sync: a direct chat from somebody who shares a room with this account is
     * joined and remembered; any other invitation is declined. Answers what was declined, for stderr.
     */
    static List<String> answerInvitations(final MatrixClient client, final String self, final JsonNode invites)
            throws Failure {
        final List<String> declined = new ArrayList<>();
        for (final Map.Entry<String, JsonNode> invite : invites.properties()) {
            final String room = invite.getKey();
            String inviter = null;
            boolean direct = false;
            boolean encrypted = false;
            for (final JsonNode event : invite.getValue().path("invite_state").path("events")) {
                if ("m.room.member".equals(event.path("type").asText()) && self.equals(event.path("state_key").asText())) {
                    inviter = event.path("sender").asText("");
                    direct = event.path("content").path("is_direct").asBoolean(false);
                }
                encrypted |= "m.room.encryption".equals(event.path("type").asText());
            }
            final String path = "/_matrix/client/v3/rooms/" + MatrixClient.segment(room);
            // A task reads no encrypted chat - it holds no keys - so it is declined with a reason the person's
            // client shows, rather than joined and never answered.
            if (encrypted && inviter != null) {
                client.post(path + "/leave", MatrixClient.JSON.createObjectNode().put("reason", ENCRYPTED));
                declined.add("an encrypted chat from " + inviter + " to " + room + ", declined: " + ENCRYPTED);
                continue;
            }
            if (inviter == null || !direct || !sharesARoom(client, inviter)) {
                client.post(path + "/leave", MatrixClient.JSON.createObjectNode());
                declined.add("an invitation " + (direct ? "" : "to a room that is no direct chat ") + "from "
                        + (inviter == null ? "?" : inviter) + (direct ? " who shares no room with this account" : "")
                        + " to " + room + ", declined");
                continue;
            }
            client.post("/_matrix/client/v3/join/" + MatrixClient.segment(room), MatrixClient.JSON.createObjectNode());
            remember(client, self, inviter, room);
        }
        return declined;
    }

    /** Whether somebody is joined in a room this account is joined in - the project's, for a task's account. */
    private static boolean sharesARoom(final MatrixClient client, final String user) throws Failure {
        for (final JsonNode room : client.get("/_matrix/client/v3/joined_rooms").path("joined_rooms")) {
            final JsonNode members = client.find("/_matrix/client/v3/rooms/" + MatrixClient.segment(room.asText())
                    + "/joined_members");
            if (members != null && members.path("joined").has(user)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The direct chat between this account and a person, to send into: the one in {@code m.direct} where the
     * person is still joined or invited, or a new one, invite-only and marked direct, inviting them.
     */
    static String chatWith(final MatrixClient client, final String self, final String person) throws Failure {
        final Set<String> known = rooms(client, self).getOrDefault(person, Set.of());
        for (final String room : known) {
            final JsonNode member = client.find("/_matrix/client/v3/rooms/" + MatrixClient.segment(room)
                    + "/state/m.room.member/" + MatrixClient.segment(person));
            final String membership = member == null ? "" : member.path("membership").asText("");
            if ("join".equals(membership) || "invite".equals(membership)) {
                return room;
            }
        }
        final ObjectNode request = MatrixClient.JSON.createObjectNode().put("preset", "private_chat")
                .put("is_direct", true).put("visibility", "private");
        request.putArray("invite").add(person);
        request.putArray("initial_state").addObject().put("type", "m.room.guest_access").put("state_key", "")
                .putObject("content").put("guest_access", "forbidden");
        final String room = client.post("/_matrix/client/v3/createRoom", request).path("room_id").asText();
        remember(client, self, person, room);
        return room;
    }

    /** Whether a room is one of this account's direct chats, and with whom. */
    static @Nullable String person(final Map<String, Set<String>> rooms, final String room) {
        for (final Map.Entry<String, Set<String>> entry : rooms.entrySet()) {
            if (entry.getValue().contains(room)) {
                return entry.getKey();
            }
        }
        return null;
    }

}
