package org.fuin.porelo.message.matrix;

import java.io.PrintStream;
import java.time.Instant;
import java.util.List;

import org.jspecify.annotations.Nullable;

import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * {@code receipt <event id> --by <account>}: what became of a message this account sent, as seen by the
 * account it was for. Porelo names that account, because the transport does not read the message to find
 * out. A Matrix read receipt means "read up to here", so a receipt on a later event counts as reading this
 * one too.
 */
final class Receipt {

    /** Only the receipts of one room, and nothing else a sync could carry. */
    private static final String RECEIPTS_FILTER = """
            {"room":{"rooms":[%s],"timeline":{"limit":0},"state":{"types":[]},\
            "ephemeral":{"types":["m.receipt"]},"account_data":{"types":[]}},\
            "presence":{"types":[]},"account_data":{"types":[]}}""";

    private static final int PAGE = 100;

    /** How far past the event a receipt is looked for, before the answer is "cannot say". */
    private static final int MAX_PAGES = 20;

    private Receipt() {
    }

    static void run(final List<String> args, final Config config, final java.util.Map<String, String> env, final PrintStream out,
            final PrintStream err)
            throws Failure {
        if (args.size() != 3 || !"--by".equals(args.get(1))) {
            throw new Failure(Exit.USAGE, "usage: receipt <event id> --by <account>");
        }
        final String eventId = Reference.eventId(args.get(0), env);
        final String by = args.get(2);
        if (!by.startsWith("@") || !by.contains(":")) {
            throw new Failure(Exit.USAGE, "--by must be an account like @name:server, not '" + by + "'");
        }
        final State state = state(new MatrixClient(config), eventId, by);
        if (state.why() != null) {
            err.println("receipt: cannot say: " + state.why());
        }
        print(out, state);
    }

    private record State(String state, @Nullable Long at, @Nullable String why) {

        static State unknown(final String why) {
            return new State("unknown", null, why);
        }

    }

    private static State state(final MatrixClient client, final String eventId, final String by) throws Failure {
        final String room = roomOf(client, eventId);
        if (room == null) {
            return State.unknown("no room this account is in holds " + eventId);
        }
        final String roomPath = "/_matrix/client/v3/rooms/" + MatrixClient.segment(room);
        final JsonNode receipt = receiptOf(client, room, by);
        if (receipt != null) {
            final String readUpTo = receipt.path("event").asText();
            final long at = receipt.path("ts").asLong();
            if (readUpTo.equals(eventId) || isAfter(client, roomPath, eventId, readUpTo)) {
                return new State("read", at, null);
            }
        }
        final JsonNode member = client.find(roomPath + "/state/m.room.member/" + MatrixClient.segment(by));
        if (member != null && "join".equals(member.path("membership").asText())) {
            return new State("delivered", null, null);
        }
        return State.unknown(by + " is not in the room"
                + (member == null ? "" : " (membership: " + member.path("membership").asText() + ")"));
    }

    /**
     * The joined room that holds the event - the project's one room - or null when none does. The room is
     * taken from the event's own {@code room_id}, never from the path it was asked under: Tuwunel answers
     * an event asked for under any room the account is in.
     */
    static @Nullable String roomOf(final MatrixClient client, final String eventId) throws Failure {
        for (final JsonNode room : client.get("/_matrix/client/v3/joined_rooms").path("joined_rooms")) {
            final String roomId = room.asText();
            final JsonNode event = client.find("/_matrix/client/v3/rooms/" + MatrixClient.segment(roomId) + "/event/"
                    + MatrixClient.segment(eventId));
            if (event != null && roomId.equals(event.path("room_id").asText())) {
                return roomId;
            }
        }
        return null;
    }

    /**
     * The account's public read receipt in the room: the event it has read up to and when, or null. A
     * separate sync without a position, so it never moves what {@code poll} has seen.
     */
    private static @Nullable JsonNode receiptOf(final MatrixClient client, final String room, final String by)
            throws Failure {
        final String filter;
        try {
            filter = String.format(RECEIPTS_FILTER, MatrixClient.JSON.writeValueAsString(room));
        } catch (final JacksonException ex) {
            throw new IllegalStateException("A room id could not be written as JSON", ex);
        }
        final JsonNode sync = client.get("/_matrix/client/v3/sync?timeout=0&filter=" + MatrixClient.segment(filter));
        for (final JsonNode ephemeral : sync.path("rooms").path("join").path(room).path("ephemeral").path("events")) {
            if (!"m.receipt".equals(ephemeral.path("type").asText())) {
                continue;
            }
            for (final var entry : ephemeral.path("content").properties()) {
                final JsonNode receipt = entry.getValue().path("m.read").path(by);
                // A receipt in a thread says nothing about the room's main timeline, where messages are.
                final String thread = receipt.path("thread_id").asText("main");
                if (receipt.isObject() && "main".equals(thread)) {
                    return MatrixClient.JSON.createObjectNode().put("event", entry.getKey())
                            .put("ts", receipt.path("ts").asLong());
                }
            }
        }
        return null;
    }

    /** Whether {@code later} comes after {@code eventId} in the room, looked for a bounded way forward. */
    private static boolean isAfter(final MatrixClient client, final String roomPath, final String eventId,
            final String later) throws Failure {
        final JsonNode context = client.find(roomPath + "/context/" + MatrixClient.segment(eventId) + "?limit=0");
        if (context == null) {
            return false;
        }
        String from = context.path("end").asText("");
        for (int page = 0; page < MAX_PAGES && !from.isEmpty(); page++) {
            final JsonNode messages = client.get(roomPath + "/messages?dir=f&limit=" + PAGE + "&from="
                    + MatrixClient.segment(from));
            for (final JsonNode event : messages.path("chunk")) {
                if (later.equals(event.path("event_id").asText())) {
                    return true;
                }
            }
            if (messages.path("chunk").isEmpty()) {
                return false;
            }
            from = messages.path("end").asText("");
        }
        return false;
    }

    private static void print(final PrintStream out, final State state) {
        final ObjectNode answer = MatrixClient.JSON.createObjectNode().put("state", state.state());
        final Long at = state.at();
        if (at != null) {
            answer.put("at", Instant.ofEpochMilli(at).toString());
        }
        try {
            out.println(MatrixClient.JSON.writeValueAsString(answer));
        } catch (final JacksonException ex) {
            throw new IllegalStateException("A JSON tree could not be written", ex);
        }
    }

}
