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
        switch (args.getFirst()) {
            case "issue" -> issue(env, machine, unit, answer);
            case "revoke" -> answer.put("revoked", MachineTokens.revoke(env, machine));
            case "expel" -> expel(env, machine, unit, answer);
            default ->
                    throw new Failure(
                            Exit.USAGE, "usage: machine-token issue|revoke|expel <machine>");
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
        final Accounts.Session admin = administrator(local, env, unit);
        if (given == null || given.isBlank()) {
            answer.putObject("account").put(Config.ADMIN_TOKEN, admin.accessToken());
        }
        if (!MachineTokens.admitted(env, machine)) {
            final List<String> taken = accounts(room(local, admin), ending(admin, machine));
            if (!taken.isEmpty()) {
                throw new Failure(
                        Exit.CONFIG,
                        "the homeserver has accounts of "
                                + machine
                                + " already ("
                                + String.join(", ", taken)
                                + "), whose names a new Porelo cannot take; expel "
                                + machine
                                + ", or add the person under another machine name");
            }
        }
        answer.put("token", MachineTokens.issue(env, machine));
    }

    /**
     * Every account of the machine deactivated, {@code @porelo.<machine>} first, and the machine
     * recorded as expelled and its token revoked first of all, so no token is issued for it while
     * this runs. Tuwunel lists a deactivated account no more; the list is read again until none of
     * the machine's is left. A machine of which the homeserver knows nothing is refused, so a name
     * mistyped takes no name for good.
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
        final Accounts.Session admin = administrator(local, env, unit);
        final AdminRoom room = room(local, admin);
        final String ending = ending(admin, machine);
        List<String> left = accounts(room, ending);
        if (left.isEmpty()
                && !MachineTokens.admitted(env, machine)
                && !MachineTokens.expelled(env, machine)) {
            throw new Failure(
                    Exit.CONFIG,
                    machine
                            + " was never admitted to this homeserver, and it has no account of"
                            + " it; name the machine as issue was given it");
        }
        answer.put("revoked", MachineTokens.expel(env, machine));
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
            left = accounts(room, ending);
        }
        expelled.forEach(answer.putArray("expelled")::add);
    }

    /** What every account of the machine's name ends with on this homeserver. */
    private static String ending(final Accounts.Session admin, final String machine) {
        return "." + machine + ":" + AdminRoom.server(admin.userId());
    }

    /** The machine's accounts the homeserver lists, {@code @porelo.<machine>} first. */
    private static List<String> accounts(final AdminRoom room, final String ending) throws Failure {
        final List<String> found = new ArrayList<>();
        for (final String id : room.users()) {
            if (id.equals("@" + Lifecycle.ADMIN + ending)) {
                found.addFirst(id);
            } else if (id.endsWith(ending)) {
                found.add(id);
            }
        }
        return found;
    }

    private static AdminRoom room(final LocalHomeserver.Running local, final Accounts.Session admin)
            throws Failure {
        return AdminRoom.of(client(local.url(), admin.accessToken()), admin.userId());
    }

    private static Accounts.Session administrator(
            final LocalHomeserver.Running local, final Map<String, String> env, final Unit unit)
            throws Failure {
        final MatrixClient nobody = client(local.url(), "");
        final Accounts.Session admin =
                Lifecycle.admin(nobody, env, local.registrationToken(), unit);
        Lifecycle.displayName(nobody, admin, "Porelo (administrator)");
        // Only the first account registered is the homeserver's administrator.
        AdminRoom.of(nobody.as(admin.accessToken()), admin.userId());
        return admin;
    }

    private static MatrixClient client(final URI url, final String token) throws Failure {
        return new MatrixClient(new Config(url, token, null, true));
    }
}
