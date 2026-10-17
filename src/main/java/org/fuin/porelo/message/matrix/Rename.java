package org.fuin.porelo.message.matrix;

import java.util.List;

/**
 * {@code rename --display <nickname>}, run with a task's own token: the name its account shows in
 * the room and in its direct chats - the task's label, changed. Only the name a client shows
 * changes; the account's id, and so every address Porelo keeps, stays, and so does the token, which
 * enrolling again would replace.
 */
final class Rename {

    private Rename() {}

    static void run(final List<String> args, final Config config) throws Failure {
        if (args.size() != 2 || !"--display".equals(args.get(0))) {
            throw new Failure(Exit.USAGE, "usage: rename --display <nickname>");
        }
        final String nickname = Lifecycle.display(args.get(1));
        final MatrixClient client = new MatrixClient(config);
        client.put(
                "/_matrix/client/v3/profile/"
                        + MatrixClient.segment(Accounts.whoami(client))
                        + "/displayname",
                MatrixClient.JSON.createObjectNode().put("displayname", nickname));
    }
}
