package org.fuin.porelo.message.matrix;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Deactivates a person's accounts and keeps their login taken on this host. */
final class Expel {

    static final String DIRECTORY = "expelled-logins";

    private static final int ROUNDS = 5;

    private Expel() {}

    static void run(
            final List<String> args,
            final Map<String, String> env,
            final PrintStream out,
            final Unit unit)
            throws Failure {
        if (args.size() != 1) {
            throw new Failure(Exit.USAGE, "usage: expel <login>");
        }
        final String login = Login.name(args.getFirst());
        final String token = env.get(Config.ADMIN_TOKEN);
        if (token == null || token.isBlank()) {
            throw new Failure(
                    Exit.CONFIG,
                    "expel needs the homeserver administrator's token among the secrets on stdin");
        }
        final LocalHomeserver.Running local = LocalHomeserver.ensure(env, unit);
        final MatrixClient nobody = new MatrixClient(new Config(local.url(), "", null, true));
        final Accounts.Session admin = Lifecycle.admin(nobody, env, "", unit);
        final AdminRoom room = AdminRoom.of(nobody.as(token), admin.userId());
        out.println(expel(env, login, AdminRoom.server(admin.userId()), room));
    }

    /** Record first, so the gate refuses new accounts while the existing ones are taken out. */
    static ObjectNode expel(
            final Map<String, String> env,
            final String login,
            final String server,
            final AdminRoom room)
            throws Failure {
        Login.name(login);
        List<String> left = accounts(login, server, room.users());
        final boolean known = record(env, login, !left.isEmpty());
        final ObjectNode answer =
                MatrixClient.JSON.createObjectNode().put("login", login).put("known", known);
        final var expelled = answer.putArray("expelled");
        final List<String> done = new ArrayList<>();
        for (int round = 0; !left.isEmpty(); round++) {
            if (round == ROUNDS) {
                throw new Failure(
                        Exit.TEMPORARY,
                        "accounts of "
                                + login
                                + " are still listed after "
                                + ROUNDS
                                + " rounds: "
                                + String.join(", ", left));
            }
            for (final String id : left) {
                room.deactivate(id);
                if (!done.contains(id)) {
                    done.add(id);
                    expelled.add(id);
                }
            }
            left = accounts(login, server, room.users());
        }
        return answer;
    }

    static List<String> accounts(final String login, final String server, final List<String> ids) {
        final List<String> found = new ArrayList<>();
        final String provisioning = "@porelo." + login + ":" + server;
        for (final String id : ids) {
            if (!Login.owns(login, id, server)) {
                continue;
            }
            if (id.equals(provisioning)) {
                found.addFirst(id);
            } else {
                found.add(id);
            }
        }
        return found;
    }

    /** Unknown logins stay available; a previous record makes a repeated expel known. */
    static boolean record(final Map<String, String> env, final String login, final boolean listed)
            throws Failure {
        final Path dir = LocalHomeserver.directory(env).resolve(DIRECTORY);
        final Path record = dir.resolve(Login.name(login));
        if (Files.exists(record)) {
            return true;
        }
        if (!listed) {
            return false;
        }
        try {
            Files.createDirectories(
                    dir,
                    PosixFilePermissions.asFileAttribute(
                            PosixFilePermissions.fromString("rwx------")));
            // A directory made earlier, or by hand, is narrowed too.
            Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwx------"));
            try {
                Files.createFile(
                        record,
                        PosixFilePermissions.asFileAttribute(
                                PosixFilePermissions.fromString("rw-------")));
            } catch (final FileAlreadyExistsException ex) {
                // Another expel recorded the same login first.
            }
        } catch (final IOException ex) {
            throw new Failure(
                    Exit.CONFIG,
                    "cannot record " + login + " as expelled in " + dir + ": " + ex.getMessage(),
                    ex);
        }
        return true;
    }
}
