package org.fuin.porelo.message.matrix;

import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.PrintStream;
import java.util.List;

/**
 * {@code describe}: what the transport is, as one JSON object. It reads nothing - no configuration,
 * no network - and claims nothing it does not do: a fact claimed in {@code attests} whose evidence
 * is missing makes Porelo hold the message.
 */
final class Describe {

    /**
     * The largest message carried whatever its content. An event is 64 KiB in all, and JSON
     * escaping grows a message by up to six times (a control character becomes {@code \u0001});
     * measured against Tuwunel, the worst content fits 10,727 bytes and plain ASCII 64,397. So this
     * is a promise for every message, with room left for the envelope and a signature of up to 2
     * KiB.
     */
    static final int MAX_BYTES = 10_240;

    private Describe() {}

    static void run(final List<String> args, final PrintStream out) throws Failure {
        if (!args.isEmpty()) {
            throw new Failure(Exit.USAGE, "usage: describe");
        }
        final ObjectNode description =
                MatrixClient.JSON
                        .createObjectNode()
                        .put("scheme", "matrix")
                        .put("waitsOn", "the room's homeserver")
                        .put("poll", true)
                        // receipt answers from the room's read receipts.
                        .put("confirms", "read")
                        .put("max_bytes", MAX_BYTES)
                        // send takes --shown, the line a person reads in the room; the message
                        // itself travels beside it.
                        .put("shown", true)
                        // poll takes --persons: a person's words in the room, handed over as their
                        // own kind of file.
                        .put("persons", true)
                        // poll takes --direct, with a task's token: its direct chats with people;
                        // send --to @user writes there.
                        .put("direct", true)
                        // poll takes --wait <seconds>: the homeserver holds the sync open until
                        // something arrives.
                        .put("waits", true)
                        // send takes --mention @user:server: a pill and m.mentions in the room, so
                        // that client highlights it.
                        .put("mention", true)
                        // enroll takes --display <nickname>, and rename --display <nickname>
                        // changes it with the task's
                        // own token: the name the room and the direct chat show for the task.
                        .put("display", true);
        // One environment variable per credential, named per credential.
        description
                .putArray("credentials")
                .addObject()
                .put("name", Config.ACCESS_TOKEN)
                .put("as", "env");
        // The homeserver the package installs, once it does; until then no host is claimed.
        description.putArray("hosts");
        // "sender" once Porelo defines the evidence that proves it; claimed without it, every
        // message is held.
        description.putArray("attests");
        // What Porelo runs to make a project's conversation: the homeserver, the room and the
        // accounts.
        final var lifecycle = description.putArray("lifecycle");
        Lifecycle.VERBS.forEach(lifecycle::add);
        // Offered so a project file's draft can be checked against this transport before it is
        // committed.
        lifecycle.add("settings");
        // --machine scopes the accounts made on a central homeserver to one machine.
        description.putArray("takes").add("machine");
        try {
            out.println(MatrixClient.JSON.writeValueAsString(description));
        } catch (final JacksonException ex) {
            throw new IllegalStateException("A JSON tree could not be written", ex);
        }
    }
}
