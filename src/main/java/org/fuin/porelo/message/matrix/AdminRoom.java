package org.fuin.porelo.message.matrix;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * The homeserver's admin room, where its administrator - the first account registered - gives
 * commands and the server answers. Tuwunel offers no HTTP call for removing an account or setting
 * its password; this is how it is done.
 */
final class AdminRoom {

    private static final Duration ANSWER_TIMEOUT = Duration.ofSeconds(20);

    /**
     * What a command's argument may be: a user or room id, or a password made here - one word of
     * visible characters. A command is a line of text; an argument with a space or a line break in
     * it, read from a room's members say, would be a second command.
     */
    private static final Pattern ARGUMENT = Pattern.compile("[!@A-Za-z0-9][\\x21-\\x7e]{0,254}");

    private final MatrixClient admin;

    private final String adminId;

    private final String room;

    private AdminRoom(final MatrixClient admin, final String adminId, final String room) {
        this.admin = admin;
        this.adminId = adminId;
        this.room = room;
    }

    /** The admin room the account is in - which it is only if it is the administrator. */
    static AdminRoom of(final MatrixClient admin, final String adminId) throws Failure {
        final String alias = "#admins:" + server(adminId);
        for (final JsonNode joined :
                admin.get("/_matrix/client/v3/joined_rooms").path("joined_rooms")) {
            final JsonNode name =
                    admin.find(
                            "/_matrix/client/v3/rooms/"
                                    + MatrixClient.segment(joined.asText())
                                    + "/state/m.room.canonical_alias");
            if (name != null && alias.equals(name.path("alias").asText())) {
                return new AdminRoom(admin, adminId, joined.asText());
            }
        }
        throw new Failure(
                Exit.CONFIG,
                adminId + " is not in " + alias + ": it is not this homeserver's administrator");
    }

    static String server(final String userId) {
        return userId.substring(userId.indexOf(':') + 1);
    }

    /** A command given and the server's reply to it, each by its event. */
    record Reply(String sent, String answer, String body) {}

    /** Gives the command and answers the server's reply to it. */
    String command(final String text) throws Failure {
        return reply(text).body();
    }

    private Reply reply(final String text) throws Failure {
        final String txn = "porelo-admin-" + Accounts.password();
        final String sent =
                admin.put(
                                "/_matrix/client/v3/rooms/"
                                        + MatrixClient.segment(room)
                                        + "/send/m.room.message/"
                                        + txn,
                                MatrixClient.JSON
                                        .createObjectNode()
                                        .put("msgtype", "m.text")
                                        .put("body", text))
                        .path("event_id")
                        .asText("");
        final Instant deadline = Instant.now().plus(ANSWER_TIMEOUT);
        while (Instant.now().isBefore(deadline)) {
            // The newest first, down to the command. The server's answer names the command it
            // answers; another command's answer, or a notice of its own, is passed over.
            final JsonNode chunk =
                    admin.get(
                                    "/_matrix/client/v3/rooms/"
                                            + MatrixClient.segment(room)
                                            + "/messages?dir=b&limit=20")
                            .path("chunk");
            for (final JsonNode event : chunk) {
                if (sent.equals(event.path("event_id").asText())) {
                    break;
                }
                if ("m.room.message".equals(event.path("type").asText())
                        && !adminId.equals(event.path("sender").asText())
                        && sent.equals(
                                event.path("content")
                                        .path("m.relates_to")
                                        .path("m.in_reply_to")
                                        .path("event_id")
                                        .asText())) {
                    return new Reply(
                            sent,
                            event.path("event_id").asText(""),
                            event.path("content").path("body").asText(""));
                }
            }
            try {
                Thread.sleep(250);
            } catch (final InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new Failure(
                        Exit.TEMPORARY,
                        "interrupted while waiting for the homeserver's answer",
                        ex);
            }
        }
        throw new Failure(
                Exit.TEMPORARY,
                "the homeserver did not answer '"
                        + text.split(" ")[0]
                        + " "
                        + text.split(" ")[1]
                        + "' within "
                        + ANSWER_TIMEOUT.toSeconds()
                        + " s");
    }

    /**
     * Sets a password of our choosing - so nothing has to be read out of the server's reply. The
     * command and the reply both carry the password, and the room keeps its events, so both are
     * redacted at once: the password is then in no event the homeserver serves, to anybody who
     * reads the admin room later.
     */
    void setPassword(final String userId, final String password) throws Failure {
        argument(userId);
        argument(password);
        final Reply reply = reply("!admin users reset-password " + userId + " " + password);
        redact(reply.sent());
        redact(reply.answer());
        if (!reply.body().startsWith("Successfully reset the password")) {
            throw new Failure(
                    Exit.PROTOCOL,
                    "setting the password of "
                            + userId
                            + " answered: "
                            + firstLine(reply.body()).replace(password, "<password>"));
        }
    }

    /** Strips an event of its content; refused loudly, since what it held would stay readable. */
    private void redact(final String eventId) throws Failure {
        if (eventId.isEmpty()) {
            return;
        }
        final MatrixClient.Answer answer =
                admin.exchange(
                        "PUT",
                        "/_matrix/client/v3/rooms/"
                                + MatrixClient.segment(room)
                                + "/redact/"
                                + MatrixClient.segment(eventId)
                                + "/porelo-redact-"
                                + Accounts.password(),
                        MatrixClient.JSON.createObjectNode().put("reason", "a password"));
        if (answer.status() != 200) {
            throw new Failure(
                    Exit.PROTOCOL,
                    "redacting the password from the admin room ("
                            + eventId
                            + ") answered "
                            + answer.status()
                            + " "
                            + answer.errcode()
                            + ": the password set is readable there; reset it again");
        }
    }

    /**
     * Every local account the homeserver lists, by id; a deactivated one it lists no more. The
     * answer holds them one a line in a code block, and a line that is no user id is passed over.
     */
    List<String> users() throws Failure {
        final String answer = command("!admin users list-users");
        if (!answer.startsWith("Found ")) {
            throw new Failure(Exit.PROTOCOL, "listing the accounts answered: " + firstLine(answer));
        }
        final List<String> ids = new ArrayList<>();
        for (final String line : answer.lines().toList()) {
            final String id = line.strip();
            if (id.startsWith("@") && ARGUMENT.matcher(id).matches()) {
                ids.add(id);
            }
        }
        return ids;
    }

    void deactivate(final String userId) throws Failure {
        argument(userId);
        final String answer = command("!admin users deactivate " + userId);
        if (!answer.contains("has been deactivated")) {
            throw new Failure(
                    Exit.PROTOCOL, "deactivating " + userId + " answered: " + firstLine(answer));
        }
    }

    /** Deletes a room from the server's database: its events, its members and its alias with it. */
    void deleteRoom(final String roomId) throws Failure {
        argument(roomId);
        final String answer = command("!admin rooms delete " + roomId);
        if (!answer.startsWith("Successfully deleted")) {
            throw new Failure(
                    Exit.PROTOCOL,
                    "deleting the room " + roomId + " answered: " + firstLine(answer));
        }
    }

    static void argument(final String value) throws Failure {
        if (!ARGUMENT.matcher(value).matches()) {
            // Never the value itself: one of them is a password.
            throw new Failure(
                    Exit.PROTOCOL,
                    "refused to put an argument of "
                            + value.length()
                            + " characters into an admin command: it is not one word");
        }
    }

    private static String firstLine(final String text) {
        final int end = text.indexOf('\n');
        return end < 0 ? text : text.substring(0, end);
    }
}
