package org.fuin.porelo.message.matrix;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * A task's direct chats with the people of its project. Which rooms are direct chats is what Matrix
 * keeps for every client, the account's {@code m.direct}: a person's client writes it on their
 * side, this transport on the task's. An invitation is taken only from somebody who already shares
 * a room with the task's account - the project's room - and on a central homeserver only from a
 * person's own account, so nobody else can open a way in.
 */
final class Direct {

    /** Why an encrypted chat is declined, as the person's client shows it. */
    static final String ENCRYPTED =
            "Porelo's tasks cannot read an encrypted chat: open it again with encryption off";

    private Direct() {}

    /**
     * The account's direct chats, by the other person: as {@code m.direct} has them, empty when it
     * has none.
     */
    static Map<String, Set<String>> rooms(final MatrixClient client, final String self)
            throws Failure {
        final JsonNode data =
                client.find(
                        "/_matrix/client/v3/user/"
                                + MatrixClient.segment(self)
                                + "/account_data/m.direct");
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

    /** How often a chat left out by another writer of {@code m.direct} is written again. */
    private static final int REMEMBER_ATTEMPTS = 5;

    /**
     * Adds a room to the account's {@code m.direct} for that person, keeping what is there. The
     * account data is written whole, so another writer at the same moment - the task's {@code send}
     * beside its {@code poll} - can write over it: it is read back, and written again with what the
     * other wrote, until it holds the room.
     */
    static void remember(
            final MatrixClient client, final String self, final String person, final String room)
            throws Failure {
        final String path =
                "/_matrix/client/v3/user/" + MatrixClient.segment(self) + "/account_data/m.direct";
        for (int attempt = 0; attempt < REMEMBER_ATTEMPTS; attempt++) {
            final Map<String, Set<String>> rooms = rooms(client, self);
            rooms.computeIfAbsent(person, p -> new LinkedHashSet<>()).add(room);
            final ObjectNode data = MatrixClient.JSON.createObjectNode();
            rooms.forEach(
                    (who, ids) -> {
                        final ArrayNode list = data.putArray(who);
                        ids.forEach(list::add);
                    });
            client.put(path, data);
            if (rooms(client, self).getOrDefault(person, Set.of()).contains(room)) {
                return;
            }
        }
        throw new Failure(
                Exit.TEMPORARY,
                "the direct chat "
                        + room
                        + " with "
                        + person
                        + " was written over in m.direct "
                        + REMEMBER_ATTEMPTS
                        + " times by another writer; the chat is joined but not read until it is there");
    }

    /**
     * Answers the invitations of a sync: a direct chat from somebody who shares a room with this
     * account is joined and remembered; any other invitation is declined. With {@code central}, the
     * server of a central homeserver, only a login's own account there may open one. Answers what
     * was declined, for stderr.
     */
    static List<String> answerInvitations(
            final MatrixClient client,
            final String self,
            @Nullable final String central,
            final JsonNode invites)
            throws Failure {
        final List<String> declined = new ArrayList<>();
        for (final Map.Entry<String, JsonNode> invite : invites.properties()) {
            final String room = invite.getKey();
            String inviter = null;
            boolean direct = false;
            boolean encrypted = false;
            for (final JsonNode event : invite.getValue().path("invite_state").path("events")) {
                if ("m.room.member".equals(event.path("type").asText())
                        && self.equals(event.path("state_key").asText())) {
                    inviter = event.path("sender").asText("");
                    direct = event.path("content").path("is_direct").asBoolean(false);
                }
                encrypted |= "m.room.encryption".equals(event.path("type").asText());
            }
            final String path = "/_matrix/client/v3/rooms/" + MatrixClient.segment(room);
            // A task reads no encrypted chat - it holds no keys - so it is declined with a reason
            // the person's
            // client shows, rather than joined and never answered.
            if (encrypted && inviter != null) {
                client.post(
                        path + "/leave",
                        MatrixClient.JSON.createObjectNode().put("reason", ENCRYPTED));
                declined.add(
                        "an encrypted chat from "
                                + inviter
                                + " to "
                                + room
                                + ", declined: "
                                + ENCRYPTED);
                continue;
            }
            if (central != null && inviter != null && !Login.person(inviter, central)) {
                client.post(path + "/leave", MatrixClient.JSON.createObjectNode());
                declined.add(
                        "an invitation from "
                                + inviter
                                + ", which is no person's own account on this homeserver, to "
                                + room
                                + ", declined");
                continue;
            }
            if (inviter == null || !direct || !sharesARoom(client, inviter)) {
                client.post(path + "/leave", MatrixClient.JSON.createObjectNode());
                declined.add(
                        "an invitation "
                                + (direct ? "" : "to a room that is no direct chat ")
                                + "from "
                                + (inviter == null ? "?" : inviter)
                                + (direct ? " who shares no room with this account" : "")
                                + " to "
                                + room
                                + ", declined");
                continue;
            }
            client.post(
                    "/_matrix/client/v3/join/" + MatrixClient.segment(room),
                    MatrixClient.JSON.createObjectNode());
            remember(client, self, inviter, room);
        }
        return declined;
    }

    /**
     * Whether somebody is joined in a room this account is joined in - the project's, for a task's
     * account.
     */
    private static boolean sharesARoom(final MatrixClient client, final String user)
            throws Failure {
        for (final JsonNode room :
                client.get("/_matrix/client/v3/joined_rooms").path("joined_rooms")) {
            final JsonNode members =
                    client.find(
                            "/_matrix/client/v3/rooms/"
                                    + MatrixClient.segment(room.asText())
                                    + "/joined_members");
            if (members != null && members.path("joined").has(user)) {
                return true;
            }
        }
        return false;
    }

    /** How many of the person's newest lines in a chat are read to find one that counts. */
    static final int PAGE = 10;

    /** A chat the task cannot read: never chosen. */
    private static final long UNREADABLE = Long.MIN_VALUE;

    /** A chat the person has said no line in among {@link #PAGE}: chosen only failing others. */
    private static final long NO_LINE = Long.MIN_VALUE + 1;

    /**
     * The direct chat between this account and a person, to send into: of the chats in {@code
     * m.direct} this account is still in and the person still joined or invited - one whose members
     * it may not see passed over - the one the person last said a line in, so the answer comes
     * where they asked, the one remembered later on a tie; when they said none, the readable one
     * remembered last; when none is open or none can be read, a new one, invite-only and marked
     * direct, inviting them.
     */
    static String chatWith(final MatrixClient client, final String self, final String person)
            throws Failure {
        final Set<String> known = rooms(client, self).getOrDefault(person, Set.of());
        // Only a chat the task is still in: one it left or was taken out of fails the answer.
        final Set<String> in = known.isEmpty() ? Set.of() : joinedRooms(client);
        final List<String> open = new ArrayList<>();
        for (final String room : known) {
            if (!in.contains(room)) {
                continue;
            }
            final String membership = membership(client, room, person);
            if ("join".equals(membership) || "invite".equals(membership)) {
                open.add(room);
            }
        }
        if (open.size() == 1) {
            return open.getFirst();
        }
        @Nullable String chosen = null;
        long newest = UNREADABLE;
        for (final String room : open) {
            final long said = lastLine(client, room, person);
            if (said != UNREADABLE && said >= newest) {
                newest = said;
                chosen = room;
            }
        }
        if (chosen != null) {
            return chosen;
        }
        final ObjectNode request =
                MatrixClient.JSON
                        .createObjectNode()
                        .put("preset", "private_chat")
                        .put("is_direct", true)
                        .put("visibility", "private");
        request.putArray("invite").add(person);
        request.putArray("initial_state")
                .addObject()
                .put("type", "m.room.guest_access")
                .put("state_key", "")
                .putObject("content")
                .put("guest_access", "forbidden");
        final String room =
                client.post("/_matrix/client/v3/createRoom", request).path("room_id").asText();
        remember(client, self, person, room);
        return room;
    }

    /** The rooms this account is joined in, read once for a send. */
    private static Set<String> joinedRooms(final MatrixClient client) throws Failure {
        final JsonNode rooms = client.get("/_matrix/client/v3/joined_rooms").path("joined_rooms");
        if (!rooms.isArray()) {
            throw new Failure(Exit.PROTOCOL, "the homeserver named no joined rooms");
        }
        final Set<String> joined = new java.util.HashSet<>();
        rooms.forEach(room -> joined.add(room.asText()));
        return joined;
    }

    /**
     * The person's membership in a chat; empty when the task may not read it or the chat holds
     * none, so that chat is passed over and the others are still looked at.
     */
    private static String membership(
            final MatrixClient client, final String room, final String person) throws Failure {
        final MatrixClient.Answer answer =
                client.exchange(
                        "GET",
                        "/_matrix/client/v3/rooms/"
                                + MatrixClient.segment(room)
                                + "/state/m.room.member/"
                                + MatrixClient.segment(person),
                        null);
        final int status = answer.status();
        if (status == 403 || status == 404) {
            return "";
        }
        if (status != 200 || !answer.body().isObject()) {
            throw new Failure(
                    status == 401
                            ? Exit.NOT_PERMITTED
                            : status == 429 || status >= 500 ? Exit.TEMPORARY : Exit.PROTOCOL,
                    "reading who is in the direct chat " + room + " answered " + status);
        }
        return answer.body().path("membership").asText("");
    }

    /**
     * When the person last said a line in a chat, by the homeserver's clock: a text the task would
     * be handed, never an edit, a line taken back or their joining; {@link #NO_LINE} when none is
     * among their newest {@link #PAGE} messages, {@link #UNREADABLE} when the chat cannot be read.
     */
    private static long lastLine(final MatrixClient client, final String room, final String person)
            throws Failure {
        final ObjectNode filter = MatrixClient.JSON.createObjectNode();
        filter.putArray("types").add("m.room.message");
        filter.putArray("senders").add(person);
        final MatrixClient.Answer answer =
                client.exchange(
                        "GET",
                        "/_matrix/client/v3/rooms/"
                                + MatrixClient.segment(room)
                                + "/messages?dir=b&limit="
                                + PAGE
                                + "&filter="
                                + MatrixClient.segment(filter.toString()),
                        null);
        final int status = answer.status();
        if (status == 403 || status == 404) {
            return UNREADABLE;
        }
        if (status != 200 || !answer.body().path("chunk").isArray()) {
            throw new Failure(
                    status == 401
                            ? Exit.NOT_PERMITTED
                            : status == 429 || status >= 500 ? Exit.TEMPORARY : Exit.PROTOCOL,
                    "reading the direct chat "
                            + room
                            + " answered "
                            + status
                            + (status == 200 ? " without its messages" : " " + answer.errcode()));
        }
        for (final JsonNode event : answer.body().path("chunk")) {
            final JsonNode content = event.path("content");
            final String type = content.path("msgtype").asText("");
            if ("m.room.message".equals(event.path("type").asText())
                    && person.equals(event.path("sender").asText())
                    && ("m.text".equals(type) || "m.emote".equals(type))
                    && content.path("body").isTextual()
                    && !"m.replace".equals(content.path("m.relates_to").path("rel_type").asText(""))
                    && event.path("origin_server_ts").isIntegralNumber()) {
                // Newest first: the first that counts is the last said.
                return event.path("origin_server_ts").asLong();
            }
        }
        return NO_LINE;
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
