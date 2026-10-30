package org.fuin.porelo.message.matrix;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** The host's preparation of its homeserver's private gate, without account takeover. */
final class GatePreparation {
    private GatePreparation() {}

    static void run(final Map<String, String> env, final PrintStream out) throws Failure {
        try {
            final Path dir = GateFiles.directory(env);
            GateFiles.privateDirectory(dir);
            GateFiles.configure(env);
            GateFiles.privateDirectory(GateFiles.runtime(env));
            final boolean administrator;
            synchronized (GatePreparation.class) {
                try (var channel = GateFiles.lock(dir.resolve(".prepare.lock"));
                        var held = channel.lock()) {
                    administrator = administrator(env);
                }
            }
            out.println(
                    MatrixClient.JSON
                            .createObjectNode()
                            .put("gate", true)
                            .put("administrator", administrator));
        } catch (final IOException ex) {
            throw new Failure(Exit.CONFIG, "cannot prepare the gate's private files", ex);
        }
    }

    private static boolean administrator(final Map<String, String> env)
            throws IOException, Failure {
        final Path dir = GateFiles.directory(env);
        final Path socket = GateFiles.runtime(env).resolve("homeserver.sock");
        if (Files.notExists(socket, LinkOption.NOFOLLOW_LINKS)) {
            return false;
        }
        final MatrixClient nobody = new GateHomeserver(env).client("");
        try {
            if (nobody.exchange("GET", "/_matrix/client/versions", null).status() != 200) {
                return false;
            }
        } catch (final Failure failure) {
            if (failure.exitCode() == Exit.TEMPORARY) {
                return false;
            }
            throw failure;
        }
        final Path file = dir.resolve("admin-token");
        if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
            final String token = GateFiles.readToken(file);
            final MatrixClient.Answer who =
                    nobody.as(token).exchange("GET", "/_matrix/client/v3/account/whoami", null);
            if (who.status() == 200
                    && who.body().path("user_id").asText("").startsWith("@porelo:")) {
                verifyAdministrator(nobody.as(token));
                return true;
            }
        }
        if (!Accounts.available(nobody, "porelo")) {
            throw new Failure(
                    Exit.CONFIG,
                    "@porelo exists but the gate keeps no accepted administrator token; restore its original token to gate/admin-token with mode 0600 and run gate --prepare again; an existing account is never taken over");
        }
        final Accounts.Session admin =
                Accounts.register(
                        nobody,
                        "porelo",
                        Accounts.secret(),
                        LocalHomeserver.registrationToken(env));
        // Publish immediately: a later permission check failing must not lose the only token.
        GateFiles.write(file, admin.accessToken(), Files.exists(file, LinkOption.NOFOLLOW_LINKS));
        verifyAdministrator(nobody.as(admin.accessToken()));
        return true;
    }

    private static void verifyAdministrator(final MatrixClient client) throws Failure {
        final MatrixClient.Answer answer =
                client.exchange(
                        "GET", "/_synapse/admin/v1/username_available?username=porelo", null);
        if (!List.of(200, 400).contains(answer.status())
                || answer.status() == 400 && !answer.errcode().equals("M_USER_IN_USE")) {
            throw new Failure(
                    Exit.CONFIG,
                    "@porelo is not the homeserver administrator; prepare a homeserver whose first registered account is @porelo");
        }
    }
}
