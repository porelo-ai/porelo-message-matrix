package org.fuin.porelo.message.matrix;

import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * {@code send <file> <sig> --to <room> [--shown <line>]}: one message and its detached signature
 * become one room event. The message travels whole in a field of its own, the signature beside it,
 * both untouched. The event's body - what a Matrix client shows - is the line Porelo made for a
 * person, carried unread.
 */
final class Send {

    /** Namespaced, as the Matrix specification asks of a field it does not define. */
    static final String SIGNATURE_FIELD = "org.fuin.porelo.signature";

    /** The message itself, byte for byte as a JSON string; never the body, which is a person's. */
    static final String MESSAGE_FIELD = "org.fuin.porelo.message";

    /** The body when Porelo passed no line: never the message, which a person cannot read. */
    static final String NO_LINE = "A Porelo message.";

    /**
     * The most an event's content may take, in bytes as JSON. An event is 64 KiB in all, and the
     * homeserver adds its own fields; measured against Tuwunel, the largest message {@link
     * Describe#MAX_BYTES} allows, escaped at its worst, with a 2 KiB signature, still leaves room
     * for the start of a line.
     */
    static final int CONTENT_BYTES = 64_512;

    /**
     * A room id - never an alias, which the homeserver would resolve to a room of its choosing. Its
     * form is otherwise opaque: from room version 12 on it carries no server name.
     */
    private static final Pattern ROOM_ID = Pattern.compile("![^\\s\\p{Cntrl}]+");

    /** A person, for a direct chat with them: a user id, whose server part may carry a port. */
    private static final Pattern USER_ID =
            Pattern.compile("@[a-z0-9._=/+-]+:[A-Za-z0-9.-]+(:[0-9]{1,5})?");

    /** The longest identifier the Matrix specification allows, in bytes. */
    private static final int MAX_ID_BYTES = 255;

    private Send() {}

    static void run(final List<String> args, final Config config, final PrintStream out)
            throws Failure {
        @Nullable String room = null;
        @Nullable Path shown = null;
        final Set<String> mentions = new LinkedHashSet<>();
        boolean wrong = false;
        for (int i = 2; i + 1 < args.size() && args.size() % 2 == 0; i += 2) {
            if ("--to".equals(args.get(i)) && room == null) {
                room = args.get(i + 1);
            } else if ("--shown".equals(args.get(i)) && shown == null) {
                shown = Path.of(args.get(i + 1));
            } else if ("--mention".equals(args.get(i))
                    && USER_ID.matcher(args.get(i + 1)).matches()
                    && args.get(i + 1).getBytes(StandardCharsets.UTF_8).length <= MAX_ID_BYTES) {
                mentions.add(args.get(i + 1));
            } else {
                wrong = true;
                break;
            }
        }
        if (room == null || wrong) {
            throw new Failure(
                    Exit.USAGE,
                    "usage: send <file> <sig> --to <!room-id | @user:server> [--shown <file>]"
                            + " [--mention <@user:server>]...");
        }
        final boolean person = USER_ID.matcher(room).matches();
        if (!(person || ROOM_ID.matcher(room).matches())
                || room.getBytes(StandardCharsets.UTF_8).length > MAX_ID_BYTES) {
            throw new Failure(
                    Exit.USAGE,
                    "--to must be a room id, starting with '!', or a person's user id, starting with"
                            + " '@', not '"
                            + room
                            + "'");
        }
        final byte[] message = read(Path.of(args.get(0)));
        final byte[] signature = read(Path.of(args.get(1)));
        // What describe promises is what send does: a larger message might fit, depending on its
        // content,
        // and a limit that depends on content is no limit Porelo can rely on.
        if (message.length > Describe.MAX_BYTES) {
            throw new Failure(
                    Exit.DATA,
                    "the message is "
                            + message.length
                            + " bytes, more than the "
                            + Describe.MAX_BYTES
                            + " this transport carries");
        }
        final ObjectNode content = MatrixClient.JSON.createObjectNode();
        content.put("msgtype", "m.text");
        content.put(MESSAGE_FIELD, utf8(message, "the message"));
        content.put(SIGNATURE_FIELD, Base64.getEncoder().encodeToString(signature));
        final String line = shown == null ? NO_LINE : utf8(read(shown), "the line to show");
        final MatrixClient client = new MatrixClient(config);
        if (person) {
            // A direct chat is the two of them: whom it is for goes without saying.
            fit(content, line);
        } else {
            final Map<String, String> names = new LinkedHashMap<>();
            for (final String id : mentions) {
                names.put(id, displayName(client, id));
            }
            mention(content, names, line);
        }

        // To a person: into this account's direct chat with them, opened now if there is none.
        final String target =
                person ? Direct.chatWith(client, Accounts.whoami(client), room) : room;
        final String path =
                "/_matrix/client/v3/rooms/"
                        + MatrixClient.segment(target)
                        + "/send/m.room.message/"
                        + transactionId(message, signature);
        final JsonNode answer = client.put(path, content);
        if (!answer.path("event_id").isTextual()) {
            throw new Failure(
                    Exit.PROTOCOL, "the homeserver accepted the message but named no event");
        }
        // Porelo keeps this beside the sent message and hands the reference back to receipt,
        // unread.
        try {
            out.println(
                    MatrixClient.JSON.writeValueAsString(
                            MatrixClient.JSON
                                    .createObjectNode()
                                    .put("reference", answer.path("event_id").asText())));
        } catch (final JacksonException ex) {
            throw new IllegalStateException("A JSON tree could not be written", ex);
        }
    }

    /**
     * The same message and signature always give the same id, so a send Porelo retries after a
     * temporary failure lands as the event the first attempt may already have made, not as a second
     * one. The target is not part of it: Porelo never sends one message to two targets, since each
     * queued message has one destination and a deferred one is sent again to the same.
     */
    static String transactionId(final byte[] message, final byte[] signature) {
        final MessageDigest sha256;
        try {
            sha256 = MessageDigest.getInstance("SHA-256");
        } catch (final NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is required of every Java platform", ex);
        }
        sha256.update(ByteBuffer.allocate(Long.BYTES).putLong(message.length).array());
        sha256.update(message);
        sha256.update(signature);
        return "porelo-" + HexFormat.of().formatHex(sha256.digest());
    }

    /**
     * In the room, the people the message is for are mentioned as Matrix means it: named in {@code
     * m.mentions}, so their client highlights it, and shown as pills before the line. A message to
     * nobody in particular says so with an empty {@code m.mentions}, so no word in it notifies
     * anybody.
     */
    static void mention(
            final ObjectNode content, final Map<String, String> mentions, final String line) {
        final ArrayNode ids = content.putObject("m.mentions").putArray("user_ids");
        mentions.keySet().forEach(ids::add);
        final StringBuilder prefix = new StringBuilder();
        final StringBuilder pills = new StringBuilder();
        for (final Map.Entry<String, String> mention : mentions.entrySet()) {
            final String id = mention.getKey();
            final String name = mention.getValue();
            prefix.append(prefix.isEmpty() ? "" : ", ").append(name);
            pills.append(pills.isEmpty() ? "" : ", ")
                    .append("<a href=\"https://matrix.to/#/")
                    .append(id)
                    .append("\">")
                    .append(html(name))
                    .append("</a>");
        }
        final String head = mentions.isEmpty() ? "" : prefix + ": ";
        fit(content, head + line);
        if (mentions.isEmpty()) {
            return;
        }
        // The pills are the formatted twin of the body as it was fitted; left out where they would
        // not fit too.
        final String body = content.path("body").asText();
        // A name that fills the event leaves the body cut inside the names: no pill matches it.
        if (!body.startsWith(head)) {
            return;
        }
        content.put("format", "org.matrix.custom.html");
        content.put("formatted_body", pills + ": " + html(body.substring(head.length())));
        if (size(content) > CONTENT_BYTES) {
            content.remove("format");
            content.remove("formatted_body");
        }
    }

    /**
     * The name a client shows for an account - a task's nickname, a person's chosen name - or its
     * local part.
     */
    private static String displayName(final MatrixClient client, final String id) throws Failure {
        final JsonNode profile =
                client.find(
                        "/_matrix/client/v3/profile/" + MatrixClient.segment(id) + "/displayname");
        final String name = profile == null ? "" : profile.path("displayname").asText("").strip();
        return name.isEmpty() ? id.substring(1, id.indexOf(':')) : name;
    }

    private static String html(final String text) {
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("\n", "<br>");
    }

    /**
     * Puts the line into the body, cut where the whole event would not fit; the message is never
     * cut, and the cut says how long the line was.
     */
    static void fit(final ObjectNode content, final String line) {
        content.put("body", line);
        if (size(content) <= CONTENT_BYTES) {
            return;
        }
        final int length = line.codePointCount(0, line.length());
        final String note = "… (cut; " + length + " characters in all)";
        int low = 0;
        int high = length;
        while (low < high) {
            final int mid = (low + high + 1) >>> 1;
            content.put("body", cut(line, mid) + note);
            if (size(content) <= CONTENT_BYTES) {
                low = mid;
            } else {
                high = mid - 1;
            }
        }
        content.put("body", cut(line, low) + note);
    }

    private static String cut(final String line, final int codePoints) {
        return line.substring(0, line.offsetByCodePoints(0, codePoints));
    }

    static int size(final ObjectNode content) {
        try {
            return MatrixClient.JSON.writeValueAsBytes(content).length;
        } catch (final JacksonException ex) {
            throw new IllegalStateException("A JSON tree could not be written", ex);
        }
    }

    /**
     * A JSON string holds text only; bytes that are not UTF-8 are refused rather than silently
     * replaced.
     */
    static String utf8(final byte[] message, final String what) throws Failure {
        try {
            return StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(message))
                    .toString();
        } catch (final CharacterCodingException ex) {
            throw new Failure(
                    Exit.DATA,
                    what + " is not valid UTF-8, and a room event can carry only text",
                    ex);
        }
    }

    private static byte[] read(final Path file) throws Failure {
        try {
            return Files.readAllBytes(file);
        } catch (final IOException ex) {
            throw new Failure(Exit.USAGE, "cannot read " + file + ": " + ex.getMessage(), ex);
        }
    }
}
