package org.fuin.porelo.message.matrix;

import java.util.List;

/**
 * {@code read <reference>}: this account has read the message, said in the room as its read
 * receipt, so the sender's {@code receipt} answers {@code read}. No task touches Matrix: Porelo
 * runs it with the reading task's own token when that task's agent takes the message.
 */
final class Read {

    private Read() {}

    static void run(
            final List<String> args, final Config config, final java.util.Map<String, String> env)
            throws Failure {
        if (args.size() != 1) {
            throw new Failure(Exit.USAGE, "usage: read <reference>");
        }
        final String eventId = Reference.eventId(args.get(0), env);
        final MatrixClient client = new MatrixClient(config);
        final String room = Receipt.roomOf(client, eventId);
        if (room == null) {
            throw new Failure(Exit.NOT_PERMITTED, "no room this account is in holds " + eventId);
        }
        // A read receipt marks everything up to the event; posting it again changes nothing.
        client.post(
                "/_matrix/client/v3/rooms/"
                        + MatrixClient.segment(room)
                        + "/receipt/m.read/"
                        + MatrixClient.segment(eventId),
                MatrixClient.JSON.createObjectNode());
    }
}
