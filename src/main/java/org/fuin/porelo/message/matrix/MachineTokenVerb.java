package org.fuin.porelo.message.matrix;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.PrintStream;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * {@code machine-token issue|revoke|expel <machine>}: the host administrator's helper admits each
 * person's machine to the account's own homeserver and takes it out again, with no project and no
 * room. Not a verb of Porelo's transport contract, so {@code describe} names none of it. The
 * administrator's token comes on stdin among the secrets, as Porelo hands it to {@code setup}; the
 * first {@code issue} registers the administrator and answers its token under {@code account}, to
 * be kept and handed back.
 */
final class MachineTokenVerb {

    /**
     * How often {@code expel} reads the accounts again while one it deactivated is still listed.
     */
    private static final int ROUNDS = 5;

    private MachineTokenVerb() {}

    static void run(
            final List<String> args,
            final Map<String, String> env,
            final PrintStream out,
            final Unit unit)
            throws Failure {
        if (args.size() != 2) {
            throw new Failure(Exit.USAGE, "usage: machine-token issue|revoke|expel <machine>");
        }
        final String machine = args.get(1);
        final ObjectNode answer = MatrixClient.JSON.createObjectNode().put("machine", machine);
        try {
            switch (args.getFirst()) {
                case "issue" -> issue(env, machine, unit, answer);
                case "revoke" -> answer.put("revoked", MachineTokens.revoke(env, machine));
                case "expel" -> expel(env, machine, unit, answer);
                default ->
                        throw new Failure(
                                Exit.USAGE, "usage: machine-token issue|revoke|expel <machine>");
            }
        } catch (final Failure failure) {
            // The administrator registered just now has no other copy of its token: answered even
            // so, for Porelo to keep, beside the refusal.
            if (answer.has("account")) {
                out.println(answer);
            }
            throw failure;
        }
        out.println(answer);
    }

    /**
     * The administrator made before any machine gets a token: the first account registered is the
     * homeserver's administrator, and a person's would be otherwise. What {@code issue} refuses is
     * refused before that, so a refusal registers no administrator whose token is never answered.
     */
    private static void issue(
            final Map<String, String> env,
            final String machine,
            final Unit unit,
            final ObjectNode answer)
            throws Failure {
        MachineTokens.admissible(env, machine);
        final LocalHomeserver.Running local = LocalHomeserver.ensure(env, unit);
        final String given = env.get(Config.ADMIN_TOKEN);
        final MatrixClient nobody = client(local.url(), "");
        final Accounts.Session admin =
                Lifecycle.admin(nobody, env, local.registrationToken(), unit);
        if (given == null || given.isBlank()) {
            answer.putObject("account").put(Config.ADMIN_TOKEN, admin.accessToken());
        }
        final AdminRoom room = administrator(nobody, admin);
        answer.put(
                "token",
                MachineTokens.issue(env, machine, AdminRoom.server(admin.userId()), room.users()));
    }

    /**
     * Every account of the machine deactivated, {@code @porelo.<machine>} first, and the machine
     * recorded as expelled and its token revoked first of all, so no token is issued for it while
     * this runs. The accounts are read after that, and again until none of the machine's is left,
     * since Tuwunel lists a deactivated account no more. A machine of which nothing is known is
     * answered {@code "known": false} and left as it is: for a lock, nothing to take away.
     */
    private static void expel(
            final Map<String, String> env,
            final String machine,
            final Unit unit,
            final ObjectNode answer)
            throws Failure {
        final String given = env.get(Config.ADMIN_TOKEN);
        if (given == null || given.isBlank()) {
            throw new Failure(
                    Exit.CONFIG,
                    "expel needs the administrator's token among the secrets on stdin, as issue"
                            + " answered it");
        }
        MachineTokens.name(machine);
        final LocalHomeserver.Running local = LocalHomeserver.ensure(env, unit);
        final MatrixClient nobody = client(local.url(), "");
        final Accounts.Session admin =
                Lifecycle.admin(nobody, env, local.registrationToken(), unit);
        final AdminRoom room = administrator(nobody, admin);
        final String server = AdminRoom.server(admin.userId());
        MachineTokens.Expel found = MachineTokens.expel(env, machine, false);
        if (found == MachineTokens.Expel.UNKNOWN) {
            // Accounts of a name the host never issued: from before its records, or a restore.
            if (MachineTokens.accounts(env, machine, server, room.users()).isEmpty()) {
                answer.put("known", false).put("revoked", false).putArray("expelled");
                return;
            }
            found = MachineTokens.expel(env, machine, true);
        }
        answer.put("known", true).put("revoked", found == MachineTokens.Expel.REVOKED);
        List<String> left = MachineTokens.accounts(env, machine, server, room.users());
        final List<String> expelled = new ArrayList<>();
        for (int round = 0; !left.isEmpty(); round++) {
            if (round == ROUNDS) {
                throw new Failure(
                        Exit.TEMPORARY,
                        "accounts of "
                                + machine
                                + " are still listed after "
                                + ROUNDS
                                + " rounds: "
                                + String.join(", ", left));
            }
            for (final String id : left) {
                room.deactivate(id);
                if (!expelled.contains(id)) {
                    expelled.add(id);
                }
            }
            left = MachineTokens.accounts(env, machine, server, room.users());
        }
        expelled.forEach(answer.putArray("expelled")::add);
    }

    /**
     * The administrator named and its admin room: only the first account registered is the
     * homeserver's administrator.
     */
    private static AdminRoom administrator(final MatrixClient nobody, final Accounts.Session admin)
            throws Failure {
        Lifecycle.displayName(nobody, admin, "Porelo (administrator)");
        return AdminRoom.of(nobody.as(admin.accessToken()), admin.userId());
    }

    private static MatrixClient client(final URI url, final String token) throws Failure {
        return new MatrixClient(new Config(url, token, null, true));
    }
}
