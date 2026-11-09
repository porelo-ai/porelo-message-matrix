package org.fuin.porelo.message.matrix;

import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * A person's words in the room, handed to Porelo, which gives them to every task of the project.
 * Whom they are meant for is what Matrix states, and the one rule the room's agents keep: the
 * accounts the client marked as mentioned, the sender of the message they reply to, and a member
 * shown as {@code Agent <Word>} or {@code Agent <Word> (<short>)} that the first line's head names
 * by that whole display, as the room tool reads it; that may be nobody. A head that says {@code All
 * agents}, or a mention of the whole room, marks the words as meant for every task. Porelo maps
 * those accounts to its tasks, checks the sender is in the room, and puts the words through the
 * filter on the way in; the transport only says who wrote what, and to whom it was meant.
 */
final class Person {

    /**
     * A mention as clients write it into the formatted body, where they set no {@code m.mentions}.
     */
    private static final Pattern MATRIX_TO =
            Pattern.compile("https://matrix\\.to/#/(@[^\"'<>\\s?/]+)");

    private Person() {}

    /**
     * Writes a person's message for the accounts it names, and answers null; or answers why it was
     * not, for stderr. Only text a person typed is taken: a notice is a bot's, an edit changes
     * words already delivered, and an image or a file is no text to hand over. With {@code
     * central}, the server of a central homeserver, only a login's own account there is a person.
     */
    static @Nullable String deliver(
            final MatrixClient client,
            final String self,
            final String room,
            final JsonNode event,
            final Path inbound,
            final Path stateDir,
            @Nullable final String central,
            @Nullable final String directTo)
            throws Failure {
        return deliver(
                client, self, room, event, inbound, stateDir, central, directTo, new HashMap<>());
    }

    /**
     * As {@link #deliver(MatrixClient, String, String, JsonNode, Path, Path, String, String)}, with
     * the names each room's members show kept in {@code shown} across one poll, so a room is asked
     * for them once however many heads its people type.
     */
    static @Nullable String deliver(
            final MatrixClient client,
            final String self,
            final String room,
            final JsonNode event,
            final Path inbound,
            final Path stateDir,
            @Nullable final String central,
            @Nullable final String directTo,
            final Map<String, Map<String, String>> shown)
            throws Failure {
        final String sender = event.path("sender").asText("?");
        // On a central homeserver the room holds every other person's tasks and relays too, whose
        // plain text would otherwise read as this project's person speaking.
        if (central != null && !Login.person(sender, central)) {
            return "words from "
                    + sender
                    + ", which is no person's own account on this homeserver, in "
                    + room;
        }
        final JsonNode content = event.path("content");
        final String type = content.path("msgtype").asText("");
        // Signed but without the message field: Porelo's own, in the form before the person's line
        // - no person's.
        if (content.has(Send.SIGNATURE_FIELD)) {
            return "a Porelo message of the earlier form, the message in its body: from "
                    + sender
                    + " in "
                    + room;
        }
        if (!("m.text".equals(type) || "m.emote".equals(type))
                || !content.path("body").isTextual()) {
            return "a message that is no text ("
                    + (type.isEmpty() ? "redacted or empty" : type)
                    + "): from "
                    + sender
                    + " in "
                    + room;
        }
        if ("m.replace".equals(content.path("m.relates_to").path("rel_type").asText(""))) {
            return "an edit of an earlier message: from " + sender + " in " + room;
        }
        final Set<String> to = new LinkedHashSet<>();
        boolean helpers = false;
        boolean all = false;
        final String replyTo =
                content.path("m.relates_to").path("m.in_reply_to").path("event_id").asText("");
        if (directTo != null) {
            // A direct chat is meant for the task whose account it is with, whatever it mentions.
            to.add(directTo);
        } else {
            content.path("m.mentions").path("user_ids").forEach(id -> to.add(id.asText("")));
            if (to.isEmpty()) {
                final Matcher m = MATRIX_TO.matcher(content.path("formatted_body").asText(""));
                while (m.find()) {
                    try {
                        to.add(java.net.URLDecoder.decode(m.group(1), StandardCharsets.UTF_8));
                    } catch (final IllegalArgumentException ex) {
                        // An escape that is none names nobody; the message is delivered all the
                        // same.
                    }
                }
            }
            if (!replyTo.isEmpty()) {
                final JsonNode original =
                        client.find(
                                "/_matrix/client/v3/rooms/"
                                        + MatrixClient.segment(room)
                                        + "/event/"
                                        + MatrixClient.segment(replyTo));
                if (original != null) {
                    to.add(original.path("sender").asText(""));
                }
            }
            // A head typed as the agents write it, "Agent Pi: ...", names the member shown so.
            final String body = content.path("body").asText();
            if (MeantFor.mayNameAnAgent(body)) {
                Map<String, String> names = shown.get(room);
                if (names == null) {
                    names = shownOrNone(client, room);
                    shown.put(room, names);
                }
                to.addAll(MeantFor.head(body, names).users());
            }
            final String administrator = "@" + Lifecycle.ADMIN + ":" + AdminRoom.server(self);
            // The writer naming themselves names nobody else, so it neither marks nor hides a
            // helper.
            to.removeIf(id -> id.isEmpty() || id.equals(sender) || !id.startsWith("@"));
            // "All agents" in the head, as the agents write it, or the client's @room, is meant for
            // every task; whether, asks nobody.
            final JsonNode roomMention = content.path("m.mentions").path("room");
            all =
                    MeantFor.head(body, Map.of()).room()
                            || (roomMention.isBoolean() && roomMention.booleanValue());
            helpers =
                    !all
                            && !to.isEmpty()
                            && to.stream()
                                    .allMatch(id -> id.equals(self) || id.equals(administrator));
            // Whom it is meant for, not who reads it: every task of the project reads every message
            // in the room,
            // and one that names nobody is handed over all the same.
            to.removeIf(id -> id.equals(self) || id.equals(administrator));
        }
        final String eventId = event.path("event_id").asText("");
        if (eventId.isEmpty()) {
            throw new Failure(Exit.PROTOCOL, "the sync returned a message without an event id");
        }
        final long ts = event.path("origin_server_ts").asLong(0);
        final ObjectNode file = MatrixClient.JSON.createObjectNode().put("sender", sender);
        // Only a login's own account names a person Porelo can find in the access file; any other
        // account names nobody, and the field is left out rather than guessed.
        final String server = central != null ? central : AdminRoom.server(self);
        if (Login.person(sender, server)) {
            file.put("account", sender.substring(1, sender.length() - server.length() - 1));
        }
        to.forEach(file.putArray("to")::add);
        file.put("body", content.path("body").asText());
        if (directTo != null) {
            file.put("direct", true);
        }
        if (helpers) {
            file.put("helpers", true);
        }
        if (all) {
            file.put("all", true);
        }
        file.put("eventId", eventId);
        if (!replyTo.isEmpty()) {
            file.put("replyTo", replyTo);
        }
        file.put("at", Instant.ofEpochMilli(ts).toString());
        final String reference = Reference.ofPerson(eventId, ts);
        Reference.remember(stateDir, reference, eventId);
        try {
            Poll.writeAtomically(
                    inbound.resolve(reference + ".json"),
                    MatrixClient.JSON.writeValueAsBytes(file),
                    inbound);
        } catch (final JacksonException ex) {
            throw new IllegalStateException("A JSON tree could not be written", ex);
        }
        return null;
    }

    /**
     * The names the room's members show; none where the polling account is no longer let ask, as
     * when it was taken out after the sync, so the words are handed over as before, named by what
     * the client marked. A homeserver not reached still ends the poll, and the line is read again.
     */
    private static Map<String, String> shownOrNone(final MatrixClient client, final String room)
            throws Failure {
        try {
            return members(client, room);
        } catch (final Failure failure) {
            if (failure.exitCode() == Exit.NOT_PERMITTED) {
                return Map.of();
            }
            throw failure;
        }
    }

    /** The room's members and the names their room profiles show. */
    static Map<String, String> members(final MatrixClient client, final String room)
            throws Failure {
        final Map<String, String> members = new LinkedHashMap<>();
        client.get("/_matrix/client/v3/rooms/" + MatrixClient.segment(room) + "/joined_members")
                .path("joined")
                .properties()
                .forEach(
                        member ->
                                members.put(
                                        member.getKey(),
                                        member.getValue()
                                                .path("display_name")
                                                .asText(member.getKey())));
        return members;
    }
}
