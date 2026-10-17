package org.fuin.porelo.message.matrix;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;

import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * A person's words in the room, handed to Porelo, which gives them to every task of the project. Whom they are
 * meant for is what Matrix states, never read out of the text: the accounts the client marked as mentioned,
 * and the sender of the message they reply to; that may be nobody. Porelo maps those accounts to its tasks,
 * checks the sender is in the room, and puts the words through the filter on the way in; the transport only
 * says who wrote what, and to whom it was meant.
 */
final class Person {

    /** A mention as clients write it into the formatted body, where they set no {@code m.mentions}. */
    private static final Pattern MATRIX_TO = Pattern.compile("https://matrix\\.to/#/(@[^\"'<>\\s?/]+)");

    private Person() {
    }

    /**
     * Writes a person's message for the accounts it names, and answers null; or answers why it was not, for
     * stderr. Only text a person typed is taken: a notice is a bot's, an edit changes words already
     * delivered, and an image or a file is no text to hand over.
     */
    static @Nullable String deliver(final MatrixClient client, final String self, final String room, final JsonNode event,
            final Path inbound, final Path stateDir, @Nullable final String directTo) throws Failure {
        final String sender = event.path("sender").asText("?");
        final JsonNode content = event.path("content");
        final String type = content.path("msgtype").asText("");
        // Signed but without the message field: Porelo's own, in the form before the person's line - no person's.
        if (content.has(Send.SIGNATURE_FIELD)) {
            return "a Porelo message of the earlier form, the message in its body: from " + sender + " in " + room;
        }
        if (!("m.text".equals(type) || "m.emote".equals(type)) || !content.path("body").isTextual()) {
            return "a message that is no text (" + (type.isEmpty() ? "redacted or empty" : type) + "): from " + sender
                    + " in " + room;
        }
        if ("m.replace".equals(content.path("m.relates_to").path("rel_type").asText(""))) {
            return "an edit of an earlier message: from " + sender + " in " + room;
        }
        final Set<String> to = new LinkedHashSet<>();
        final String replyTo = content.path("m.relates_to").path("m.in_reply_to").path("event_id").asText("");
        if (directTo != null) {
            // A direct chat is meant for the task whose account it is with, whatever it mentions.
            to.add(directTo);
        } else {
            content.path("m.mentions").path("user_ids").forEach(id -> to.add(id.asText("")));
            if (to.isEmpty()) {
                final Matcher m = MATRIX_TO.matcher(content.path("formatted_body").asText(""));
                while (m.find()) {
                    to.add(java.net.URLDecoder.decode(m.group(1), StandardCharsets.UTF_8));
                }
            }
            if (!replyTo.isEmpty()) {
                final JsonNode original = client.find("/_matrix/client/v3/rooms/" + MatrixClient.segment(room) + "/event/"
                        + MatrixClient.segment(replyTo));
                if (original != null) {
                    to.add(original.path("sender").asText(""));
                }
            }
            // Whom it is meant for, not who reads it: every task of the project reads every message in the room,
            // and one that names nobody is handed over all the same.
            to.removeIf(id -> id.isEmpty() || id.equals(sender) || id.equals(self) || !id.startsWith("@"));
        }
        final String eventId = event.path("event_id").asText("");
        if (eventId.isEmpty()) {
            throw new Failure(Exit.PROTOCOL, "the sync returned a message without an event id");
        }
        final long ts = event.path("origin_server_ts").asLong(0);
        final ObjectNode file = MatrixClient.JSON.createObjectNode().put("sender", sender);
        to.forEach(file.putArray("to")::add);
        file.put("body", content.path("body").asText());
        if (directTo != null) {
            file.put("direct", true);
        }
        file.put("eventId", eventId);
        if (!replyTo.isEmpty()) {
            file.put("replyTo", replyTo);
        }
        file.put("at", Instant.ofEpochMilli(ts).toString());
        final String reference = Reference.ofPerson(eventId, ts);
        Reference.remember(stateDir, reference, eventId);
        try {
            Poll.writeAtomically(inbound.resolve(reference + ".json"), MatrixClient.JSON.writeValueAsBytes(file), inbound);
        } catch (final JacksonException ex) {
            throw new IllegalStateException("A JSON tree could not be written", ex);
        }
        return null;
    }

}
