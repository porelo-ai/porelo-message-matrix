package org.fuin.porelo.message.matrix;

import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.net.URI;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * The verbs by which Porelo has the transport make what a project's conversation needs - {@code
 * setup}, {@code enroll}, {@code retire}, {@code join} and {@code part} - and take it away again,
 * {@code clear}. Porelo hands each the project's settings and the secrets it kept on stdin, which
 * {@link Input} turns into the settings and the environment read here, and keeps what it prints
 * without reading it: all that is Matrix is here.
 */
final class Lifecycle {

    static final List<String> VERBS =
            List.of(
                    "setup", "enroll", "retire", "join", "part", "admit", "dismiss", "members",
                    "clear");

    /** As many machines as {@code members} keeps in a room, each with its accounts beside it. */
    static final int MAX_MACHINES = 500;

    /** The provisioning account: the first registered, so the homeserver's administrator. */
    static final String ADMIN = "porelo";

    /**
     * What a project's, a task's, a machine's or a person's name may be, as part of a Matrix id.
     */
    private static final Pattern NAME = Pattern.compile("[a-z0-9][a-z0-9._=-]{0,120}");

    private Lifecycle() {}

    private record Options(
            String project,
            @Nullable String task,
            @Nullable String person,
            boolean reset,
            boolean token,
            boolean loopbackOnly,
            @Nullable String display,
            @Nullable String machine,
            boolean createRoom,
            @Nullable List<String> machines) {}

    /**
     * A nickname a person gives a task: shown in the room and the direct chat, what @ completes to.
     */
    private static final Pattern DISPLAY = Pattern.compile("[^\\p{Cc}\\p{Cf}@:]{1,64}");

    static void run(
            final String verb,
            final List<String> args,
            final Map<String, String> env,
            final InputStream in,
            final PrintStream out,
            final PrintStream err,
            final Unit unit)
            throws Failure {
        if ("clear".equals(verb) && !args.contains("--project")) {
            if (!args.isEmpty()) {
                if (args.size() != 2 || !"--machine".equals(args.getFirst())) {
                    throw usage(verb);
                }
                machine(args.get(1));
            }
            // Porelo names the machine, but the account's own homeserver has no machine name.
            print(out, LocalHomeserver.clear(env).answer());
            return;
        }
        final Options options = options(verb, args);
        final Settings settings = own(Settings.read(in).materialized(env), env);
        // As loud as the environment's switch: said on every start, so it never goes unnoticed.
        if (!settings.verifyTls()) {
            err.println(Main.NAME + " " + verb + ": " + Settings.VERIFY_OFF_WARNING);
        }
        // An offline project may reach this machine's loopback and nothing else - refused before
        // any request.
        if (options.loopbackOnly()
                && settings.homeserver() != null
                && !loopback(settings.homeserver())) {
            throw new Failure(
                    Exit.CONFIG,
                    "the project may reach only this machine's loopback, and its homeserver is "
                            + settings.homeserver());
        }
        // After setup, the verbs reach the homeserver Porelo kept from it, which must be loopback
        // too.
        final String kept = env.get(Config.HOMESERVER);
        if (options.loopbackOnly() && !"setup".equals(verb) && kept != null) {
            final URI url = Config.homeserver(kept);
            if (!loopback(url)) {
                throw new Failure(
                        Exit.CONFIG,
                        "the project may reach only this machine's loopback, and its homeserver is "
                                + url);
            }
        }
        final ObjectNode answer =
                switch (verb) {
                    case "setup" -> setup(options, settings, env, unit);
                    case "enroll" -> enroll(options, settings, env);
                    case "retire" -> retire(options, settings, env);
                    case "join" -> join(options, settings, env);
                    case "part" -> part(options, settings, env);
                    case "admit" -> admit(options, settings, env);
                    case "dismiss" -> dismiss(options, settings, env);
                    case "members" -> members(options, settings, env);
                    case "clear" -> clear(options, settings, env);
                    default -> throw new IllegalArgumentException(verb);
                };
        print(out, answer);
    }

    private static void print(final PrintStream out, final ObjectNode answer) {
        try {
            out.println(MatrixClient.JSON.writeValueAsString(answer));
        } catch (final JacksonException ex) {
            throw new IllegalStateException("A JSON tree could not be written", ex);
        }
    }

    // --- setup
    // ------------------------------------------------------------------------------------------

    /** The central room kept to the listed machines, by the machine that made it. */
    private static ObjectNode members(
            final Options options, final Settings settings, final Map<String, String> env)
            throws Failure {
        final URI url = settings.homeserver();
        if (url == null) {
            throw new Failure(
                    Exit.NOT_PERMITTED,
                    "members keeps a central homeserver's room; on the account's own homeserver"
                            + " admit and dismiss keep its machines");
        }
        final String machine = centralMachine(options, env);
        final String token =
                required(env, Config.ADMIN_TOKEN, "members runs as the machine that made the room");
        return CentralRoom.members(
                client(url, token, settings),
                machine,
                options.project(),
                Objects.requireNonNull(settings.room()),
                required(options.machines()));
    }

    private static ObjectNode setup(
            final Options options,
            final Settings settings,
            final Map<String, String> env,
            final Unit unit)
            throws Failure {
        if (settings.homeserver() != null) {
            return setupCentral(options, settings, env);
        }
        if (options.createRoom()) {
            throw new Failure(
                    Exit.NOT_PERMITTED,
                    "--create-room makes a central homeserver's room; on the account's own"
                            + " homeserver setup makes the project's room itself");
        }
        final LocalHomeserver.Running local = LocalHomeserver.ensure(env, unit);
        // The relay is named after the project, so the project's name is held to the same rule.
        notAMachines(env, options.project());
        final URI url = local.url();
        final String reaches = "127.0.0.1:" + local.port();
        final String registrationToken = local.registrationToken();
        final MatrixClient nobody = client(url, "", settings);

        final Accounts.Session admin = admin(nobody, env, registrationToken, unit);
        displayName(nobody, admin, "Porelo (administrator)");
        final MatrixClient asAdmin = nobody.as(admin.accessToken());
        AdminRoom.of(asAdmin, admin.userId());
        final String server = AdminRoom.server(admin.userId());
        final String room = room(asAdmin, admin.userId(), options.project(), server);
        // Named in the settings, as the signed file names it for every machine: this one's own.
        if (settings.room() != null
                && !settings.room().equals(room)
                && !settings.room().equals(alias(options.project(), server))) {
            throw new Failure(
                    Exit.CONFIG,
                    "the project's settings name the room "
                            + settings.room()
                            + ", and this homeserver's room for the project is "
                            + room);
        }

        final Accounts.Session relay =
                account(
                        nobody,
                        asAdmin,
                        admin.userId(),
                        "porelo-relay-" + options.project(),
                        env.get(Config.ACCESS_TOKEN),
                        registrationToken);
        displayName(nobody, relay, "Porelo (delivery)");
        admit(asAdmin, room, relay, nobody);

        final ObjectNode answer = MatrixClient.JSON.createObjectNode();
        answer.putObject("account").put(Config.ADMIN_TOKEN, admin.accessToken());
        put(answer.putObject("secrets"), settings.environment(url, relay.accessToken()));
        answer.put("conversation", room);
        answer.putArray("reaches").add(reaches);
        return answer;
    }

    /**
     * Central {@code setup}: registers (or logs in as) this machine's provisioning account {@code
     * @porelo.<machine>} with its derived password. On the first setup it joins only a room its
     * name was invited into already, and answers not yet admitted on anything else, so the account
     * is kept before anything can fail; on later setups it joins the room named in the settings,
     * checks that it is invite-only and forbids guests, and admits this machine's relay.
     */
    private static ObjectNode setupCentral(
            final Options options, final Settings settings, final Map<String, String> env)
            throws Failure {
        final String machine = centralMachine(options, env);
        final URI url = Objects.requireNonNull(settings.homeserver());
        final String configuredRoom = Objects.requireNonNull(settings.room());
        final String registrationToken = elsewhereToken(settings, env);
        final String keptSecret = env.get(Config.MACHINE_SECRET);
        final String secret =
                keptSecret != null && !keptSecret.isBlank() ? keptSecret : Accounts.secret();
        final MatrixClient nobody = client(url, "", settings);

        final Accounts.Session machineAccount =
                machineAccount(
                        nobody, machine, secret, env.get(Config.ADMIN_TOKEN), registrationToken);
        final MatrixClient asMachine = nobody.as(machineAccount.accessToken());

        final ObjectNode answer = MatrixClient.JSON.createObjectNode();
        answer.putObject("account")
                .put(Config.ADMIN_TOKEN, machineAccount.accessToken())
                .put(Config.MACHINE_SECRET, secret)
                .put(Config.MACHINE, machine);

        if (options.createRoom()) {
            // Whatever fails once the account is made, its generated secret must reach Porelo.
            try {
                final String room =
                        CentralRoom.make(
                                asMachine,
                                machineAccount.userId(),
                                options.project(),
                                configuredRoom);
                answer.put("created_room", true);
                return admittedIn(
                        options,
                        settings,
                        env,
                        answer,
                        nobody,
                        asMachine,
                        machineAccount,
                        secret,
                        registrationToken,
                        room);
            } catch (final Failure failure) {
                if (keptSecret == null || keptSecret.isBlank()) {
                    return notAdmitted(answer, machine, machineAccount, configuredRoom);
                }
                throw failure;
            }
        }

        // A machine just registered joins only where its name was invited before it existed, as
        // `admit` does. Whatever fails then, its generated secret must reach Porelo, or the account
        // is lost to it: so it answers not yet admitted, and the next setup, with the secret kept,
        // joins or refuses the room in words.
        if (keptSecret == null || keptSecret.isBlank()) {
            try {
                return invited(asMachine, configuredRoom)
                        ? admitted(
                                options,
                                settings,
                                env,
                                answer,
                                nobody,
                                asMachine,
                                machineAccount,
                                secret,
                                registrationToken,
                                false)
                        : notAdmitted(answer, machine, machineAccount, configuredRoom);
            } catch (final Failure failure) {
                return notAdmitted(answer, machine, machineAccount, configuredRoom);
            }
        }
        return admitted(
                options,
                settings,
                env,
                answer,
                nobody,
                asMachine,
                machineAccount,
                secret,
                registrationToken,
                true);
    }

    /**
     * Joins the room named in the settings, checks that it is invite-only and forbids guests, and
     * admits this machine's relay; not yet invited, it answers not yet admitted. A room found wrong
     * is left when {@code leaveWhenWrong} is true.
     */
    private static ObjectNode admitted(
            final Options options,
            final Settings settings,
            final Map<String, String> env,
            final ObjectNode answer,
            final MatrixClient nobody,
            final MatrixClient asMachine,
            final Accounts.Session machineAccount,
            final String secret,
            final String registrationToken,
            final boolean leaveWhenWrong)
            throws Failure {
        final String machine = centralMachine(options, env);
        final String configuredRoom = Objects.requireNonNull(settings.room());
        final String room = joinCentralRoom(asMachine, configuredRoom);
        if (room == null) {
            return notAdmitted(answer, machine, machineAccount, configuredRoom);
        }
        checkCentralRoom(asMachine, configuredRoom, room, leaveWhenWrong);
        return admittedIn(
                options,
                settings,
                env,
                answer,
                nobody,
                asMachine,
                machineAccount,
                secret,
                registrationToken,
                room);
    }

    /** In the project's room: this machine's relay admitted, and the room answered. */
    private static ObjectNode admittedIn(
            final Options options,
            final Settings settings,
            final Map<String, String> env,
            final ObjectNode answer,
            final MatrixClient nobody,
            final MatrixClient asMachine,
            final Accounts.Session machineAccount,
            final String secret,
            final String registrationToken,
            final String room)
            throws Failure {
        final String machine = centralMachine(options, env);
        final URI url = Objects.requireNonNull(settings.homeserver());
        displayName(nobody, machineAccount, "Porelo (administrator)");

        final Accounts.Session relay =
                derivedAccount(
                        nobody,
                        "porelo-relay-" + options.project() + "." + machine,
                        secret,
                        env.get(Config.ACCESS_TOKEN),
                        registrationToken);
        displayName(nobody, relay, "Porelo (delivery)");
        admit(asMachine, room, relay, nobody);

        put(answer.putObject("secrets"), settings.environment(url, relay.accessToken()));
        answer.put("conversation", room);
        answer.putArray("reaches").add(reaches(url));
        answer.put("machine", machine);
        answer.put("admitted", true);
        return answer;
    }

    /** Host and port, as the contract says, the scheme's own port where the URL names none. */
    private static String reaches(final URI url) {
        return url.getHost()
                + ":"
                + (url.getPort() >= 0 ? url.getPort() : "https".equals(url.getScheme()) ? 443 : 80);
    }

    /** Keeps an account recognizable when setup recovers it or reuses its room. */
    static void displayName(
            final MatrixClient nobody, final Accounts.Session account, final String name)
            throws Failure {
        nobody.as(account.accessToken())
                .put(
                        "/_matrix/client/v3/profile/"
                                + MatrixClient.segment(account.userId())
                                + "/displayname",
                        MatrixClient.JSON.createObjectNode().put("displayname", name));
    }

    /**
     * This machine's provisioning account {@code @porelo.<machine>}, registered or taken back
     * through {@link #derivedAccount}.
     */
    private static Accounts.Session machineAccount(
            final MatrixClient nobody,
            final String machine,
            final String secret,
            @Nullable final String keptToken,
            final String registrationToken)
            throws Failure {
        return derivedAccount(nobody, ADMIN + "." + machine, secret, keptToken, registrationToken);
    }

    /**
     * Not yet in the project's room: the account a person lets in, and the room, so Porelo can name
     * both, as its contract has a transport on a shared server answer.
     */
    private static ObjectNode notAdmitted(
            final ObjectNode answer,
            final String machine,
            final Accounts.Session machineAccount,
            final String configuredRoom) {
        answer.put("machine", machine);
        answer.put("address", machineAccount.userId());
        answer.put("room", configuredRoom);
        answer.put("admitted", false);
        return answer;
    }

    /** Whether the machine's account holds an invitation into the configured room. */
    private static boolean invited(final MatrixClient asMachine, final String configuredRoom)
            throws Failure {
        final String roomId;
        if (configuredRoom.startsWith("#")) {
            final JsonNode found =
                    asMachine.find(
                            "/_matrix/client/v3/directory/room/"
                                    + MatrixClient.segment(configuredRoom));
            if (found == null) {
                return false;
            }
            roomId = found.path("room_id").asText("");
        } else {
            roomId = configuredRoom;
        }
        final String filter =
                "{\"room\":{\"timeline\":{\"limit\":0},\"state\":{\"types\":[]},"
                        + "\"ephemeral\":{\"types\":[]},\"account_data\":{\"types\":[]}},"
                        + "\"presence\":{\"types\":[]},\"account_data\":{\"types\":[]}}";
        final JsonNode sync =
                asMachine.get(
                        "/_matrix/client/v3/sync?timeout=0&filter=" + MatrixClient.segment(filter));
        return sync.path("rooms").path("invite").has(roomId);
    }

    /**
     * Resolves {@code configuredRoom} if it is an alias and attempts to join it; returns {@code
     * null} when the alias does not resolve or the machine's account is not invited yet.
     */
    private static @Nullable String joinCentralRoom(
            final MatrixClient asMachine, final String configuredRoom) throws Failure {
        final String roomId;
        if (configuredRoom.startsWith("#")) {
            final JsonNode found =
                    asMachine.find(
                            "/_matrix/client/v3/directory/room/"
                                    + MatrixClient.segment(configuredRoom));
            if (found == null) {
                return null;
            }
            roomId = found.path("room_id").asText("");
        } else {
            roomId = configuredRoom;
        }
        final MatrixClient.Answer join =
                asMachine.exchange(
                        "POST",
                        "/_matrix/client/v3/join/" + MatrixClient.segment(roomId),
                        MatrixClient.JSON.createObjectNode());
        if (join.status() == 200) {
            return roomId;
        }
        if ("M_FORBIDDEN".equals(join.errcode()) || join.status() == 403 || join.status() == 404) {
            return null;
        }
        throw new Failure(
                join.status() == 429 || join.status() >= 500 ? Exit.TEMPORARY : Exit.PROTOCOL,
                "joining " + configuredRoom + " answered " + join.status() + " " + join.errcode());
    }

    /**
     * Checks that {@code roomId} is invite-only and forbids guests; when {@code leaveWhenWrong} is
     * true, leaves the room before refusing with 78.
     */
    private static void checkCentralRoom(
            final MatrixClient asMachine,
            final String configuredRoom,
            final String roomId,
            final boolean leaveWhenWrong)
            throws Failure {
        final String path = "/_matrix/client/v3/rooms/" + MatrixClient.segment(roomId);
        final @Nullable JsonNode joinRules;
        final @Nullable JsonNode guestAccess;
        try {
            joinRules = asMachine.find(path + "/state/m.room.join_rules");
            guestAccess = asMachine.find(path + "/state/m.room.guest_access");
        } catch (final Failure failure) {
            if (failure.exitCode() != Exit.NOT_PERMITTED) {
                throw failure;
            }
            throw new Failure(
                    Exit.CONFIG,
                    "the machine's account is not in the project's room "
                            + configuredRoom
                            + ": invite it and run setup again",
                    failure);
        }
        final String joinRule = joinRules == null ? "" : joinRules.path("join_rule").asText("");
        final List<String> wrong = new ArrayList<>();
        if (!"invite".equals(joinRule)) {
            wrong.add("its join rule is " + joinRule + ", not invite");
        }
        if (guestAccess == null
                || !"forbidden".equals(guestAccess.path("guest_access").asText(""))) {
            wrong.add("guests are not forbidden");
        }
        if (!wrong.isEmpty()) {
            if (leaveWhenWrong) {
                asMachine.post(path + "/leave", MatrixClient.JSON.createObjectNode());
            }
            throw new Failure(
                    Exit.CONFIG,
                    configuredRoom
                            + " cannot be used as the project's room: "
                            + String.join("; ", wrong));
        }
    }

    /**
     * Reuses {@code keptToken} when {@code /account/whoami} confirms it belongs to
     * {@code @<localpart>}, else registers {@code localpart} with its derived password or takes it
     * back with {@link Accounts#alone}.
     */
    private static Accounts.Session derivedAccount(
            final MatrixClient nobody,
            final String localpart,
            final String secret,
            @Nullable final String keptToken,
            final String registrationToken)
            throws Failure {
        if (keptToken != null && !keptToken.isBlank()) {
            final MatrixClient.Answer whoami =
                    nobody.as(keptToken).exchange("GET", "/_matrix/client/v3/account/whoami", null);
            final String userId = whoami.body().path("user_id").asText("");
            if (whoami.status() == 200 && userId.startsWith("@" + localpart + ":")) {
                return new Accounts.Session(userId, keptToken);
            }
        }
        final String password = Accounts.password(secret, localpart);
        if (Accounts.available(nobody, localpart)) {
            return Accounts.register(nobody, localpart, password, registrationToken);
        }
        try {
            return Accounts.alone(nobody, localpart, password);
        } catch (final Failure failure) {
            if (failure.exitCode() == Exit.NOT_PERMITTED) {
                throw new Failure(
                        Exit.CONFIG,
                        "the account @"
                                + localpart
                                + " exists on "
                                + nobody.config().homeserver()
                                + " with another password",
                        failure);
            }
            throw failure;
        }
    }

    /**
     * The provisioning account: the one Porelo holds, the first one registered now, or - on the
     * account's own homeserver just restored from a copy, whose vault did not come with it - the
     * restored one taken back. Never a second.
     */
    static Accounts.Session admin(
            final MatrixClient nobody,
            final Map<String, String> env,
            final String registrationToken,
            @Nullable final Unit local)
            throws Failure {
        final String token = env.get(Config.ADMIN_TOKEN);
        if (token != null && !token.isBlank()) {
            return new Accounts.Session(adminId(nobody, nobody.as(token)), token);
        }
        if (!Accounts.available(nobody, ADMIN)) {
            if (local != null && Keep.restored(env)) {
                return Keep.retake(nobody, nobody.config().homeserver(), env, local);
            }
            // Which token was lost cannot be told from here, and a second administrator is not what
            // was meant.
            throw new Failure(
                    Exit.CONFIG,
                    "the homeserver's provisioning account '"
                            + ADMIN
                            + "' exists, but Porelo"
                            + " holds no token for it");
        }
        return Accounts.register(nobody, ADMIN, Accounts.password(), registrationToken);
    }

    // --- enroll, retire, join, part
    // ----------------------------------------------------------------------------

    private static ObjectNode enroll(
            final Options options, final Settings settings, final Map<String, String> env)
            throws Failure {
        if (settings.homeserver() != null) {
            return enrollCentral(options, settings, env);
        }
        final String task = required(options.task(), "--task");
        final Place place = place(settings, env);
        notAMachines(env, task);
        refuseAnotherProjects(place, options.project(), task);
        final Accounts.Session account =
                account(
                        place.nobody(),
                        place.admin(),
                        place.adminId(),
                        task,
                        null,
                        place.registrationToken(settings, env));
        // A person reading the room sees the task's nickname, or its name - not an id. Enrolled
        // again with
        // another, the account is renamed: its id, and so every address Porelo keeps, stays.
        place.nobody()
                .as(account.accessToken())
                .put(
                        "/_matrix/client/v3/profile/"
                                + MatrixClient.segment(account.userId())
                                + "/displayname",
                        MatrixClient.JSON
                                .createObjectNode()
                                .put(
                                        "displayname",
                                        options.display() == null ? task : options.display()));
        admit(place.admin(), place.room(options.project()), account, place.nobody());
        final ObjectNode answer = MatrixClient.JSON.createObjectNode();
        put(answer.putObject("secrets"), settings.environment(place.url(), account.accessToken()));
        // Not a secret: whom Porelo names with receipt --by, for what another task sent this one.
        answer.put("address", account.userId());
        return answer;
    }

    /**
     * Central {@code enroll}: registers (or logs in alone as) {@code @<task>.<machine>} with its
     * derived password, sets its display name, and invites and joins it into the project's room
     * after checking that no other room this machine's account is in holds it.
     */
    private static ObjectNode enrollCentral(
            final Options options, final Settings settings, final Map<String, String> env)
            throws Failure {
        final String task = centralTask(options);
        final String machine = centralMachine(options, env);
        final String secret =
                required(
                        env, Config.MACHINE_SECRET, "the account's secrets from setup are missing");
        final String adminToken =
                required(env, Config.ADMIN_TOKEN, "the account's secrets from setup are missing");
        final String registrationToken = elsewhereToken(settings, env);
        final URI url = Objects.requireNonNull(settings.homeserver());
        final String configuredRoom = Objects.requireNonNull(settings.room());
        final MatrixClient nobody = client(url, "", settings);
        final MatrixClient asMachine = nobody.as(adminToken);
        final String machineId = machineId(nobody, asMachine, machine);
        final String server = AdminRoom.server(machineId);

        final String room = resolveCentralRoom(asMachine, configuredRoom);
        final String localpart = task + "." + machine;
        refuseAnotherProjectsCentral(asMachine, server, room, options.project(), localpart);
        checkCentralRoom(asMachine, configuredRoom, room, false);

        final Accounts.Session account =
                derivedAccount(nobody, localpart, secret, null, registrationToken);
        nobody.as(account.accessToken())
                .put(
                        "/_matrix/client/v3/profile/"
                                + MatrixClient.segment(account.userId())
                                + "/displayname",
                        MatrixClient.JSON
                                .createObjectNode()
                                .put(
                                        "displayname",
                                        options.display() == null ? task : options.display()));
        admit(asMachine, room, account, nobody);
        final ObjectNode answer = MatrixClient.JSON.createObjectNode();
        put(answer.putObject("secrets"), settings.environment(url, account.accessToken()));
        answer.put("address", account.userId());
        return answer;
    }

    private static String centralTask(final Options options) throws Failure {
        final String task = required(options.task(), "--task");
        // Kept out of the machine's own provisioning and relay localparts.
        if (ADMIN.equals(task) || task.startsWith("porelo-relay-")) {
            throw new Failure(
                    Exit.CONFIG, "'" + task + "' is a transport account name, not a task's");
        }
        return task;
    }

    private static String centralMachine(final Options options, final Map<String, String> env)
            throws Failure {
        final String keptMachine = env.get(Config.MACHINE);
        if (options.machine() != null
                && keptMachine != null
                && !keptMachine.isBlank()
                && !options.machine().equals(keptMachine)) {
            throw new Failure(
                    Exit.CONFIG,
                    "--machine "
                            + options.machine()
                            + " does not match "
                            + Config.MACHINE
                            + " ("
                            + keptMachine
                            + ") in the environment");
        }
        return options.machine() != null
                ? options.machine()
                : required(
                        env,
                        Config.MACHINE,
                        "a central homeserver needs --machine to name this machine");
    }

    private static String machineId(
            final MatrixClient nobody, final MatrixClient asMachine, final String machine)
            throws Failure {
        final String id;
        try {
            id = Accounts.whoami(asMachine);
        } catch (final Failure failure) {
            if (failure.exitCode() == Exit.TEMPORARY) {
                // Not reached, or busy: not a token that is wrong, and worth trying again.
                throw failure;
            }
            throw new Failure(
                    Exit.CONFIG,
                    "the machine's token Porelo holds is not valid on "
                            + nobody.config().homeserver()
                            + ": "
                            + failure.getMessage(),
                    failure);
        }
        final String prefix = "@" + ADMIN + "." + machine + ":";
        if (!id.startsWith(prefix) || id.length() == prefix.length()) {
            throw new Failure(
                    Exit.CONFIG,
                    "the machine's token Porelo holds is for "
                            + id
                            + ", not @"
                            + ADMIN
                            + "."
                            + machine);
        }
        return id;
    }

    private static String resolveCentralRoom(
            final MatrixClient asMachine, final String configuredRoom) throws Failure {
        if (!configuredRoom.startsWith("#")) {
            return configuredRoom;
        }
        final JsonNode found =
                asMachine.find(
                        "/_matrix/client/v3/directory/room/"
                                + MatrixClient.segment(configuredRoom));
        if (found == null) {
            throw new Failure(
                    Exit.CONFIG,
                    "the project's room " + configuredRoom + " does not exist on the homeserver");
        }
        return found.path("room_id").asText("");
    }

    private static void refuseAnotherProjectsCentral(
            final MatrixClient asMachine,
            final String server,
            final String room,
            final String project,
            final String localpart)
            throws Failure {
        final String userId = "@" + localpart + ":" + server;
        final String other = membersElsewhereCentral(asMachine, room).get(userId);
        if (other != null) {
            throw new Failure(
                    Exit.CONFIG,
                    userId
                            + " is in another room ("
                            + other
                            + "), not only project "
                            + project
                            + "'s: two projects give one task name, and it is left as it is");
        }
    }

    /**
     * Who is joined or invited in another room {@code asMachine} is in than {@code room}, each with
     * that room's id.
     */
    private static Map<String, String> membersElsewhereCentral(
            final MatrixClient asMachine, final String room) throws Failure {
        final Map<String, String> members = new HashMap<>();
        for (final JsonNode joined :
                asMachine.get("/_matrix/client/v3/joined_rooms").path("joined_rooms")) {
            final String id = joined.asText("");
            if (id.isEmpty() || id.equals(room)) {
                continue;
            }
            final String path = "/_matrix/client/v3/rooms/" + MatrixClient.segment(id);
            for (final JsonNode member : asMachine.get(path + "/members").path("chunk")) {
                final String membership = member.path("content").path("membership").asText("");
                if ("join".equals(membership) || "invite".equals(membership)) {
                    members.putIfAbsent(member.path("state_key").asText(""), id);
                }
            }
        }
        return members;
    }

    private static ObjectNode retire(
            final Options options, final Settings settings, final Map<String, String> env)
            throws Failure {
        if (settings.homeserver() != null) {
            return retireCentral(options, settings, env);
        }
        final String task = required(options.task(), "--task");
        final Place place = place(settings, env);
        refuseAnotherProjects(place, options.project(), task);
        // Asked twice, the homeserver answers the same: retiring again after a failure is harmless.
        AdminRoom.of(place.admin(), place.adminId())
                .deactivate("@" + task + ":" + AdminRoom.server(place.adminId()));
        return MatrixClient.JSON.createObjectNode();
    }

    /**
     * Central {@code retire}: checks that {@code @<task>.<machine>} is in no other room this
     * machine's account is in, then logs in as it with its derived password, leaves the project's
     * room, and logs out every session.
     */
    private static ObjectNode retireCentral(
            final Options options, final Settings settings, final Map<String, String> env)
            throws Failure {
        final String task = centralTask(options);
        final String machine = centralMachine(options, env);
        final String secret =
                required(
                        env, Config.MACHINE_SECRET, "the account's secrets from setup are missing");
        final String adminToken =
                required(env, Config.ADMIN_TOKEN, "the account's secrets from setup are missing");
        final URI url = Objects.requireNonNull(settings.homeserver());
        final String configuredRoom = Objects.requireNonNull(settings.room());
        final MatrixClient nobody = client(url, "", settings);
        final MatrixClient asMachine = nobody.as(adminToken);
        final String machineId = machineId(nobody, asMachine, machine);
        final String server = AdminRoom.server(machineId);

        final String room = resolveCentralRoom(asMachine, configuredRoom);
        final String localpart = task + "." + machine;
        refuseAnotherProjectsCentral(asMachine, server, room, options.project(), localpart);
        try {
            Accounts.retire(nobody, localpart, Accounts.password(secret, localpart), room);
        } catch (final Failure failure) {
            if (failure.exitCode() == Exit.NOT_PERMITTED) {
                throw new Failure(
                        Exit.CONFIG,
                        "the account @"
                                + localpart
                                + " on "
                                + nobody.config().homeserver()
                                + " is not this machine's: "
                                + failure.getMessage(),
                        failure);
            }
            throw failure;
        }
        return MatrixClient.JSON.createObjectNode();
    }

    /**
     * Refuses a task account another project's room has: two projects whose names run together give
     * two tasks one name, and enrolling or retiring it here would take that task's account from it.
     */
    private static void refuseAnotherProjects(
            final Place place, final String project, final String task) throws Failure {
        final String server = AdminRoom.server(place.adminId());
        // A project cleared already has no room; then every project's room is another's.
        final JsonNode own =
                place.admin()
                        .find(
                                "/_matrix/client/v3/directory/room/"
                                        + MatrixClient.segment(alias(project, server)));
        final String other =
                membersElsewhere(
                                place.admin(),
                                server,
                                own == null ? "" : own.path("room_id").asText(""))
                        .get("@" + task + ":" + server);
        if (other != null) {
            throw new Failure(
                    Exit.CONFIG,
                    "@"
                            + task
                            + ":"
                            + server
                            + " is a task of "
                            + other
                            + ", not of project "
                            + project
                            + ": two projects give one task name, and it is left as it is");
        }
    }

    private static ObjectNode join(
            final Options options, final Settings settings, final Map<String, String> env)
            throws Failure {
        final String person = localPerson(options, settings);
        final Place place = place(settings, env);
        notAMachines(env, person);
        final String server = AdminRoom.server(place.adminId());
        final String id = "@" + person + ":" + server;
        final String password = Accounts.password();
        final String room = place.room(options.project());
        final Accounts.Session account;
        if (Accounts.available(place.nobody(), person)) {
            account =
                    Accounts.register(
                            place.nobody(),
                            person,
                            password,
                            place.registrationToken(settings, env));
        } else if (options.reset()
                || (partedFrom(place, room, id)
                        && !membersElsewhere(place.admin(), server, null).containsKey(id))) {
            AdminRoom.of(place.admin(), place.adminId()).setPassword(id, password);
            account =
                    options.token()
                            ? Accounts.alone(place.nobody(), person, password)
                            : Accounts.login(place.nobody(), person, password);
        } else {
            throw new Failure(
                    Exit.NOT_PERMITTED,
                    "'" + person + "' has an account already; --reset gives it a new password");
        }
        if (options.display() != null) {
            displayName(place.nobody(), account, options.display());
        }
        admit(place.admin(), room, account, place.nobody());

        if (options.token()) {
            final ObjectNode answer = MatrixClient.JSON.createObjectNode();
            answer.putObject("login").put("user", account.userId()).put("room", room);
            answer.putObject("secrets").put(Config.ACCESS_TOKEN, account.accessToken());
            return answer;
        }

        final boolean loopback = loopback(place.url());
        final ObjectNode answer = MatrixClient.JSON.createObjectNode();
        final ObjectNode login = answer.putObject("login");
        login.put("homeserver", place.url().toString())
                .put("user", account.userId())
                .put("room", alias(options.project(), AdminRoom.server(place.adminId())))
                .put("password", password)
                .put("loopback", Boolean.toString(loopback));
        if (loopback) {
            login.put("port", Integer.toString(place.url().getPort()));
        }
        answer.put(
                "shown",
                "Matrix login for "
                        + person
                        + ": homeserver "
                        + place.url()
                        + (loopback
                                ? " (on this machine's loopback - forward port "
                                        + place.url().getPort()
                                        + " to reach it)"
                                : "")
                        + ", user "
                        + account.userId()
                        + ", password "
                        + password
                        + ", room "
                        + alias(options.project(), AdminRoom.server(place.adminId()))
                        + ". The password is shown once.");
        return answer;
    }

    /** Removes a person from this project, locking the account only if no other project uses it. */
    private static ObjectNode part(
            final Options options, final Settings settings, final Map<String, String> env)
            throws Failure {
        final String person = localPerson(options, settings);
        final Place place = place(settings, env);
        final String server = AdminRoom.server(place.adminId());
        final String id = "@" + person + ":" + server;
        final String room = place.room(options.project());
        final String path = "/_matrix/client/v3/rooms/" + MatrixClient.segment(room);
        final JsonNode member =
                place.admin().find(path + "/state/m.room.member/" + MatrixClient.segment(id));
        final String membership = member == null ? "" : member.path("membership").asText("");
        final ObjectNode answer = MatrixClient.JSON.createObjectNode();
        if (!("join".equals(membership) || "invite".equals(membership))) {
            return answer;
        }
        final String other = membersElsewhere(place.admin(), server, room).get(id);
        // Locked before the kick: a run that fails between the two still finds the membership.
        if (other == null) {
            final String password = Accounts.password();
            AdminRoom.of(place.admin(), place.adminId()).setPassword(id, password);
            Accounts.logoutAll(place.nobody(), person, password);
        }
        place.admin().post(path + "/kick", MatrixClient.JSON.createObjectNode().put("user_id", id));
        return other == null
                ? answer.put("removed", id)
                : answer.put("kept", id).put("why", "a member of " + other + " too");
    }

    /**
     * Settings that name this account's own homeserver by the name it is published under, as the
     * signed settings name it for every machine: this machine keeps its own homeserver's path, its
     * administrator and its room, while every other machine reaches it as a central one.
     */
    private static Settings own(final Settings settings, final Map<String, String> env)
            throws Failure {
        final @Nullable URI url = settings.homeserver();
        if (url == null || !"https".equals(url.getScheme())) {
            return settings;
        }
        final Path conf;
        try {
            conf = LocalHomeserver.directory(env).resolve("homeserver.conf");
        } catch (final Failure failure) {
            // No home to hold a homeserver of the account's own: the settings' is another's.
            return settings;
        }
        final @Nullable String name = Publish.name(conf);
        // A host name has no case; the published one is kept in lower case.
        if (name == null
                || !name.equalsIgnoreCase(url.getHost())
                || Publish.published(conf) == null) {
            return settings;
        }
        final int port;
        try {
            final @Nullable String kept = LocalHomeserver.setting(conf, LocalHomeserver.PORT);
            if (kept == null) {
                // Which URL is this homeserver's cannot be told: refused, never guessed.
                throw new Failure(
                        Exit.CONFIG,
                        conf
                                + " publishes the homeserver as "
                                + name
                                + " and names no port for it; set a project up first");
            }
            port = Integer.parseInt(kept);
        } catch (final IOException | NumberFormatException ex) {
            throw new Failure(Exit.CONFIG, "cannot read the homeserver's port in " + conf, ex);
        }
        if (url.getPort() != port) {
            throw new Failure(
                    Exit.CONFIG,
                    "the project's homeserver "
                            + url
                            + " is this machine's published name, and its homeserver listens on port "
                            + port
                            + "; the settings must name that port");
        }
        return new Settings(null, null, true, null, settings.room(), null);
    }

    // --- admit, dismiss
    // ------------------------------------------------------------------------------------------

    /**
     * Another machine admitted to the project's room on the account's own homeserver, published on
     * an address of this machine: a registration token of the machine's own, and {@code
     * @porelo.<machine>} invited, so the machine's own {@code setup} registers and joins it. Run
     * again, the same token.
     */
    private static ObjectNode admit(
            final Options options, final Settings settings, final Map<String, String> env)
            throws Failure {
        final String machine = local(options, settings, "admit");
        if (options.loopbackOnly()) {
            throw new Failure(
                    Exit.CONFIG,
                    "an offline project admits no other machine: its homeserver is this machine's"
                            + " loopback alone");
        }
        final Path dir = LocalHomeserver.directory(env);
        final Publish.@Nullable Address published =
                Publish.published(dir.resolve("homeserver.conf"));
        if (published == null) {
            throw new Failure(
                    Exit.CONFIG,
                    "the homeserver is published on no address another machine reaches; run"
                            + " publish --address first");
        }
        // Everything that can fail before the token is made, so a refused admit leaves none.
        final String caCertificate = Certificate.caCertificate(dir);
        final List<String> admitted = MachineTokens.admitted(env);
        for (final String other : admitted) {
            if (!other.equals(machine)
                    && (other.endsWith("." + machine) || machine.endsWith("." + other))) {
                throw new Failure(
                        Exit.CONFIG,
                        "the machine "
                                + other
                                + " is admitted already, and its accounts' names and "
                                + machine
                                + "'s would end alike; admit a machine of another name");
            }
        }
        final Place place = place(settings, env);
        final String room = place.room(options.project());
        final String admin = machineAdmin(machine, place);
        final String ending = ending(machine, place);
        final String path = "/_matrix/client/v3/rooms/" + MatrixClient.segment(room);
        // Before the first admission none of the machine's accounts exists: one named so, in any
        // project's room here, is somebody else's, and dismissing the machine would put it out.
        if (!admitted.contains(machine)) {
            for (final String id :
                    membersElsewhere(place.admin(), AdminRoom.server(place.adminId()), null)
                            .keySet()) {
                if (id.endsWith(ending) && !admin.equals(id)) {
                    throw new Failure(
                            Exit.CONFIG,
                            id
                                    + " is in a project's room and ends as "
                                    + machine
                                    + "'s accounts would; admit a machine of another name");
                }
            }
        }
        // Only what dismiss banned is lifted, once every check has passed.
        for (final JsonNode member : place.admin().get(path + "/members").path("chunk")) {
            final String id = member.path("state_key").asText("");
            final JsonNode content = member.path("content");
            if ((admin.equals(id) || id.endsWith(ending))
                    && "ban".equals(content.path("membership").asText(""))
                    && place.adminId().equals(member.path("sender").asText(""))
                    && DISMISSED.equals(content.path("reason").asText(""))) {
                place.admin()
                        .post(
                                path + "/unban",
                                MatrixClient.JSON
                                        .createObjectNode()
                                        .put("user_id", id)
                                        .put("reason", READMITTED));
            }
        }
        invite(place, room, admin);
        final String token = MachineTokens.issue(env, machine);

        final ObjectNode answer = MatrixClient.JSON.createObjectNode();
        answer.putObject("admission")
                .put("machine", machine)
                .put("homeserver", "https://" + published.text())
                .put("room", room)
                .put("ca_certificate", caCertificate);
        answer.putObject("secrets").put("registration_token", token);
        return answer;
    }

    /**
     * A machine admitted before put out again: its registration token refused from now on, and
     * every account of it in the project's room - {@code @porelo.<machine>}, its relay and its
     * tasks - banned from every project's room here. Run again, it finds nothing.
     */
    private static ObjectNode dismiss(
            final Options options, final Settings settings, final Map<String, String> env)
            throws Failure {
        final String machine = local(options, settings, "dismiss");
        final Place place = place(settings, env);
        place.room(options.project());
        final ObjectNode answer = MatrixClient.JSON.createObjectNode();
        final var removed = answer.putArray("removed");
        // A machine never admitted has no accounts here: names that end so are somebody else's.
        if (!MachineTokens.admitted(env).contains(machine)) {
            return answer;
        }
        final String admin = machineAdmin(machine, place);
        final String ending = ending(machine, place);
        final String server = AdminRoom.server(place.adminId());
        final Set<String> banned = new LinkedHashSet<>();
        boolean revoked = false;
        // The token is the homeserver's, so the machine goes from every project's room. Banned,
        // not kicked: a member of it not yet put out could invite another back, so the rooms are
        // read again until none is left; the token goes last, so a dismiss that stops half way
        // finds the machine still admitted when run again.
        for (int round = 0; round < ROUNDS; round++) {
            boolean found = false;
            for (final String room : projectRooms(place.admin(), server)) {
                final String path = "/_matrix/client/v3/rooms/" + MatrixClient.segment(room);
                final List<String> left = new ArrayList<>();
                for (final JsonNode member : place.admin().get(path + "/members").path("chunk")) {
                    final String id = member.path("state_key").asText("");
                    if (present(member) && (admin.equals(id) || id.endsWith(ending))) {
                        left.add(id);
                    }
                }
                // Its administrator first, which invites the others.
                left.sort((a, b) -> Boolean.compare(!admin.equals(a), !admin.equals(b)));
                for (final String id : left) {
                    place.admin()
                            .post(
                                    path + "/ban",
                                    MatrixClient.JSON
                                            .createObjectNode()
                                            .put("user_id", id)
                                            .put("reason", DISMISSED));
                    banned.add(id);
                    found = true;
                }
            }
            if (!found && revoked) {
                banned.forEach(removed::add);
                return answer;
            }
            if (!found && MachineTokens.revoke(env, machine)) {
                removed.add("the registration token of " + machine);
            }
            revoked = revoked || !found;
        }
        throw new Failure(
                Exit.TEMPORARY,
                "accounts of "
                        + machine
                        + " came back into a project's room "
                        + ROUNDS
                        + " times; run dismiss again");
    }

    /** The rooms here that are projects': the administrator's, under {@code #porelo-*:server}. */
    private static List<String> projectRooms(final MatrixClient admin, final String server)
            throws Failure {
        final List<String> rooms = new ArrayList<>();
        for (final JsonNode joined :
                admin.get("/_matrix/client/v3/joined_rooms").path("joined_rooms")) {
            final String id = joined.asText("");
            final JsonNode canonical =
                    id.isEmpty()
                            ? null
                            : admin.find(
                                    "/_matrix/client/v3/rooms/"
                                            + MatrixClient.segment(id)
                                            + "/state/m.room.canonical_alias");
            final String alias = canonical == null ? "" : canonical.path("alias").asText("");
            if (alias.startsWith("#porelo-") && alias.endsWith(":" + server)) {
                rooms.add(id);
            }
        }
        return rooms;
    }

    /** The reason {@code dismiss} bans with, so {@code admit} lifts only its own bans. */
    private static final String DISMISSED = "its machine was dismissed";

    /**
     * The reason an unban gives: a leave with a reason is never one {@code part} made, so no {@code
     * join} takes such an account without {@code --reset}.
     */
    private static final String READMITTED = "its machine was admitted again";

    private static String machineAdmin(final String machine, final Place place) {
        return "@" + ADMIN + "." + machine + ":" + AdminRoom.server(place.adminId());
    }

    private static boolean present(final JsonNode member) {
        final String membership = member.path("content").path("membership").asText("");
        return "join".equals(membership) || "invite".equals(membership);
    }

    /** How often dismiss reads the room again for accounts of the machine that came back. */
    private static final int ROUNDS = 5;

    /** The ending every account of the machine has: {@code .<machine>:<server>}. */
    private static String ending(final String machine, final Place place) {
        return "." + machine + ":" + AdminRoom.server(place.adminId());
    }

    /**
     * A name of this machine's own refused while it would end as an admitted machine's accounts do,
     * since dismissing that machine would put it out with them.
     */
    private static void notAMachines(final Map<String, String> env, final String name)
            throws Failure {
        for (final String machine : MachineTokens.admitted(env)) {
            if (name.endsWith("." + machine)) {
                throw new Failure(
                        Exit.CONFIG,
                        "'"
                                + name
                                + "' ends as the accounts of the admitted machine "
                                + machine
                                + " do; take another name");
            }
        }
    }

    /** The machine named, on the account's own homeserver alone: a central one admits nobody. */
    private static String local(final Options options, final Settings settings, final String verb)
            throws Failure {
        if (settings.homeserver() != null) {
            throw new Failure(
                    Exit.NOT_PERMITTED,
                    verb
                            + " acts on the account's own homeserver; machines are admitted to a"
                            + " central one at the homeserver itself");
        }
        return required(options.machine(), "--machine");
    }

    /** Invited unless joined or invited already; an account not registered yet may be invited. */
    private static void invite(final Place place, final String room, final String id)
            throws Failure {
        final String path = "/_matrix/client/v3/rooms/" + MatrixClient.segment(room);
        final JsonNode member =
                place.admin().find(path + "/state/m.room.member/" + MatrixClient.segment(id));
        final String membership = member == null ? "" : member.path("membership").asText("");
        if (!"join".equals(membership) && !"invite".equals(membership)) {
            place.admin()
                    .post(
                            path + "/invite",
                            MatrixClient.JSON.createObjectNode().put("user_id", id));
        }
    }

    /**
     * Whether the administrator put {@code id} out of {@code room}, as {@code part} does. An
     * account that left by itself - a retired task's, deactivated - is not one, so no person takes
     * it over.
     */
    private static boolean partedFrom(final Place place, final String room, final String id)
            throws Failure {
        for (final JsonNode member :
                place.admin()
                        .get("/_matrix/client/v3/rooms/" + MatrixClient.segment(room) + "/members")
                        .path("chunk")) {
            if (id.equals(member.path("state_key").asText(""))) {
                return "leave".equals(member.path("content").path("membership").asText(""))
                        && place.adminId().equals(member.path("sender").asText(""))
                        // part kicks without a reason; dismiss and admit always give one.
                        && member.path("content").path("reason").asText("").isEmpty();
            }
        }
        return false;
    }

    private static String localPerson(final Options options, final Settings settings)
            throws Failure {
        final String person = required(options.person(), "--person");
        if (person.startsWith(ADMIN)) {
            throw new Failure(
                    Exit.USAGE,
                    "a person's name must not begin with '"
                            + ADMIN
                            + "', which the machinery's accounts use");
        }
        if (settings.homeserver() != null) {
            throw new Failure(Exit.NOT_PERMITTED, "people are managed at the homeserver itself");
        }
        return person;
    }

    // --- clear
    // ------------------------------------------------------------------------------------------

    /**
     * Everything the project's conversation had on the homeserver: each account in its room
     * deactivated, the room deleted with its alias, and the relay's poll position forgotten. What
     * could not go is said with why, and the rest still goes; run again, it finds less and says so.
     */
    private static ObjectNode clear(
            final Options options, final Settings settings, final Map<String, String> env)
            throws Failure {
        if (settings.homeserver() != null) {
            return clearCentral(options, settings, env);
        }
        final String project = options.project();
        final Report report = new Report();
        if (env.get(Config.HOMESERVER) == null || env.get(Config.ADMIN_TOKEN) == null) {
            report.kept(
                    "the project's room and accounts",
                    "Porelo passed no secrets from setup, so none were made");
            return report.answer();
        }
        final Place place;
        try {
            place = place(settings, env);
        } catch (final Failure failure) {
            report.kept(
                    "the project's room and accounts on " + env.get(Config.HOMESERVER),
                    failure.getMessage());
            return report.answer();
        }
        final String server = AdminRoom.server(place.adminId());
        final String relay = "@porelo-relay-" + project + ":" + server;
        try {
            final AdminRoom adminRoom = AdminRoom.of(place.admin(), place.adminId());
            final JsonNode found =
                    place.admin()
                            .find(
                                    "/_matrix/client/v3/directory/room/"
                                            + MatrixClient.segment(alias(project, server)));
            if (found != null) {
                // Somebody else's room under the project's alias is not Porelo's to empty: refused,
                // said in kept.
                final String room =
                        ours(
                                place.admin(),
                                place.adminId(),
                                alias(project, server),
                                found.path("room_id").asText());
                final Map<String, String> elsewhere = membersElsewhere(place.admin(), server, room);
                final List<String> machines = MachineTokens.admitted(env);
                for (final JsonNode member :
                        place.admin()
                                .get(
                                        "/_matrix/client/v3/rooms/"
                                                + MatrixClient.segment(room)
                                                + "/members")
                                .path("chunk")) {
                    final String id = member.path("state_key").asText("");
                    final String membership = member.path("content").path("membership").asText("");
                    if (id.isEmpty()
                            || id.equals(place.adminId())
                            || !id.endsWith(":" + server)
                            || !("join".equals(membership) || "invite".equals(membership))) {
                        continue;
                    }
                    // An admitted machine's accounts are that machine's: deactivated, it could not
                    // come back to any project.
                    final @Nullable String machine =
                            machines.stream()
                                    .filter(name -> id.endsWith("." + name + ":" + server))
                                    .findFirst()
                                    .orElse(null);
                    if (machine != null) {
                        report.kept(
                                "the account " + id,
                                "an account of the admitted machine " + machine);
                        continue;
                    }
                    // One account per person on the homeserver: deactivated, it would lose every
                    // other project too.
                    final String other = elsewhere.get(id);
                    if (other != null) {
                        report.kept("the account " + id, "a member of " + other + " too");
                        continue;
                    }
                    try {
                        adminRoom.deactivate(id);
                        report.removed("the account " + id);
                    } catch (final Failure failure) {
                        report.kept("the account " + id, failure.getMessage());
                    }
                }
                adminRoom.deleteRoom(room);
                report.removed("the room " + alias(project, server));
            }
        } catch (final Failure failure) {
            report.kept("the room " + alias(project, server), failure.getMessage());
        }
        final Path stateDir;
        try {
            stateDir = Poll.stateDirectory(env);
        } catch (final Failure failure) {
            report.kept("the poll position of " + relay, failure.getMessage());
            return report.answer();
        }
        forgetPosition(
                stateDir, Poll.key(place.nobody().config(), relay), relay, report, POLL_WAIT);
        return report.answer();
    }

    /**
     * Central {@code clear --project}: has this machine's relay and tasks in the project's room
     * that are in no other room this machine's account is in leave the room and log out every
     * session, each as itself with its derived password, and forgets the relay's poll position;
     * leaves {@code @porelo.<machine>}, the room, people and other machines' accounts alone.
     */
    private static ObjectNode clearCentral(
            final Options options, final Settings settings, final Map<String, String> env)
            throws Failure {
        final String project = options.project();
        final String machine = centralMachine(options, env);
        final String secret =
                required(
                        env, Config.MACHINE_SECRET, "the account's secrets from setup are missing");
        final String adminToken =
                required(env, Config.ADMIN_TOKEN, "the account's secrets from setup are missing");
        final URI url = Objects.requireNonNull(settings.homeserver());
        final String configuredRoom = Objects.requireNonNull(settings.room());
        final MatrixClient nobody = client(url, "", settings);
        final MatrixClient asMachine = nobody.as(adminToken);
        final Report report = new Report();
        final String machineId;
        try {
            machineId = machineId(nobody, asMachine, machine);
        } catch (final Failure failure) {
            // A token the homeserver no longer knows: it let this machine go, dismissed or reset,
            // and nothing of it is left there to clear.
            if (!(failure.getCause() instanceof Failure refused
                    && refused.exitCode() == Exit.NOT_PERMITTED)) {
                throw failure;
            }
            report.kept(
                    "this machine's accounts on " + url,
                    "the homeserver had let this machine go already: " + refused.getMessage());
            return report.answer();
        }
        final String server = AdminRoom.server(machineId);
        final String relay = "@porelo-relay-" + project + "." + machine + ":" + server;
        final String suffix = ":" + server;
        final String machineSuffix = "." + machine + suffix;
        try {
            final String room = resolveCentralRoom(asMachine, configuredRoom);
            final Map<String, String> elsewhere = membersElsewhereCentral(asMachine, room);
            for (final JsonNode member :
                    asMachine
                            .get(
                                    "/_matrix/client/v3/rooms/"
                                            + MatrixClient.segment(room)
                                            + "/members")
                            .path("chunk")) {
                final String id = member.path("state_key").asText("");
                final String membership = member.path("content").path("membership").asText("");
                // Only this machine's relay and tasks (porelo-*.M), never @porelo.M or a person
                // such as @carol.M.
                if (!id.startsWith("@porelo-")
                        || !id.endsWith(machineSuffix)
                        || !("join".equals(membership) || "invite".equals(membership))) {
                    continue;
                }
                final String localpart = id.substring(1, id.length() - suffix.length());
                final String other = elsewhere.get(id);
                if (other != null) {
                    report.kept("the account " + id, "a member of " + other + " too");
                    continue;
                }
                try {
                    Accounts.retire(nobody, localpart, Accounts.password(secret, localpart), room);
                    report.removed("the account " + id);
                } catch (final Failure failure) {
                    if (failure.exitCode() == Exit.TEMPORARY) {
                        throw failure;
                    }
                    report.kept("the account " + id, failure.getMessage());
                }
            }
        } catch (final Failure failure) {
            if (failure.exitCode() == Exit.TEMPORARY) {
                throw failure;
            }
            report.kept("the room " + configuredRoom, failure.getMessage());
        }
        final Path stateDir;
        try {
            stateDir = Poll.stateDirectory(env);
        } catch (final Failure failure) {
            report.kept("the poll position of " + relay, failure.getMessage());
            return report.answer();
        }
        forgetPosition(stateDir, Poll.key(nobody.config(), relay), relay, report, POLL_WAIT);
        return report.answer();
    }

    /**
     * Who is joined or invited in another project's room than {@code room}, each with that room's
     * alias: the rooms the administrator is in whose alias is a project's. A null {@code room}
     * includes every project.
     */
    private static Map<String, String> membersElsewhere(
            final MatrixClient admin, final String server, @Nullable final String room)
            throws Failure {
        final Map<String, String> members = new HashMap<>();
        for (final JsonNode joined :
                admin.get("/_matrix/client/v3/joined_rooms").path("joined_rooms")) {
            final String id = joined.asText("");
            if (id.isEmpty() || id.equals(room)) {
                continue;
            }
            final String path = "/_matrix/client/v3/rooms/" + MatrixClient.segment(id);
            final JsonNode canonical = admin.find(path + "/state/m.room.canonical_alias");
            final String alias = canonical == null ? "" : canonical.path("alias").asText("");
            if (!alias.startsWith("#porelo-") || !alias.endsWith(":" + server)) {
                continue;
            }
            for (final JsonNode member : admin.get(path + "/members").path("chunk")) {
                final String membership = member.path("content").path("membership").asText("");
                if ("join".equals(membership) || "invite".equals(membership)) {
                    members.putIfAbsent(member.path("state_key").asText(""), alias);
                }
            }
        }
        return members;
    }

    /** How long {@code clear} waits for the relay's poll to end: its long sync and a margin. */
    private static final Duration POLL_WAIT = Duration.ofSeconds(45);

    /**
     * Forgets the relay's poll position and its lock, holding the lock the poll takes: a poll still
     * in its long sync would otherwise write the position back once it answers. It waits for such a
     * poll to end, and past the wait keeps the position and says why.
     */
    static void forgetPosition(
            final Path stateDir,
            final String key,
            final String relay,
            final Report report,
            final Duration wait) {
        final Path lockFile = stateDir.resolve(key + ".lock");
        if (!Files.exists(lockFile) && !Files.exists(stateDir.resolve(key + ".since"))) {
            return;
        }
        try (FileChannel channel =
                FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
            final FileLock held = awaitLock(channel, wait);
            if (held == null) {
                report.kept(
                        "the poll position of " + relay,
                        "a poll of it was still running after "
                                + wait.toSeconds()
                                + " s; clear again once it has ended");
                return;
            }
            try (held) {
                if (Files.deleteIfExists(stateDir.resolve(key + ".since"))) {
                    report.removed("the poll position of " + relay);
                }
                Files.deleteIfExists(lockFile);
            }
        } catch (final IOException ex) {
            report.kept("the poll position of " + relay, ex.getMessage());
        }
    }

    private static @Nullable FileLock awaitLock(final FileChannel channel, final Duration wait)
            throws IOException {
        final Instant deadline = Instant.now().plus(wait);
        while (true) {
            try {
                final FileLock lock = channel.tryLock();
                if (lock != null) {
                    return lock;
                }
            } catch (final OverlappingFileLockException ex) {
                // A poll in this process holds it: waited for like any other.
            }
            if (!Instant.now().isBefore(deadline)) {
                return null;
            }
            try {
                Thread.sleep(250);
            } catch (final InterruptedException ex) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
    }

    /** What {@code clear} took away, and what it could not, each with why. */
    static final class Report {

        private final ObjectNode answer = MatrixClient.JSON.createObjectNode();

        Report() {
            answer.putArray("removed");
            answer.putArray("kept");
        }

        void removed(final String what) {
            answer.withArray("removed").add(what);
        }

        void kept(final String what, @Nullable final String why) {
            answer.withArray("kept")
                    .addObject()
                    .put("what", what)
                    .put("why", why == null ? "" : why);
        }

        ObjectNode answer() {
            return answer;
        }
    }

    // --- shared
    // ------------------------------------------------------------------------------------------

    /**
     * Where the verbs after {@code setup} act: the homeserver and the provisioning account Porelo
     * holds.
     */
    private record Place(URI url, MatrixClient nobody, MatrixClient admin, String adminId) {

        String room(final String project) throws Failure {
            final JsonNode found =
                    admin.find(
                            "/_matrix/client/v3/directory/room/"
                                    + MatrixClient.segment(
                                            alias(project, AdminRoom.server(adminId))));
            if (found == null) {
                throw new Failure(
                        Exit.CONFIG,
                        "the project's room "
                                + alias(project, AdminRoom.server(adminId))
                                + " does not exist: setup has not run for it");
            }
            return ours(
                    admin,
                    adminId,
                    alias(project, AdminRoom.server(adminId)),
                    found.path("room_id").asText());
        }

        String registrationToken(final Settings settings, final Map<String, String> env)
                throws Failure {
            return settings.homeserver() == null
                    ? LocalHomeserver.registrationToken(env)
                    : elsewhereToken(settings, env);
        }
    }

    /**
     * A homeserver elsewhere's registration token: from the file the settings name, which is how a
     * person gives it through Porelo, else from the environment.
     */
    private static String elsewhereToken(final Settings settings, final Map<String, String> env)
            throws Failure {
        final Path file = settings.registrationTokenFile();
        final String given = env.get(Config.REGISTRATION_TOKEN);
        if (file != null && given != null && !given.isBlank()) {
            // Which of the two was meant cannot be told, so neither is guessed.
            throw new Failure(
                    Exit.CONFIG,
                    "the project names registration_token_file and "
                            + Config.REGISTRATION_TOKEN
                            + " is set too; give the token one way");
        }
        return file != null
                ? RegistrationTokenFile.read(file)
                : required(
                        env,
                        Config.REGISTRATION_TOKEN,
                        "the project's homeserver is not this account's own; name its token's file"
                                + " as registration_token_file");
    }

    private static Place place(final Settings settings, final Map<String, String> env)
            throws Failure {
        final String homeserver =
                required(env, Config.HOMESERVER, "the project's secrets from setup are missing");
        final URI url = Config.homeserver(homeserver);
        final MatrixClient nobody = client(url, "", settings);
        final MatrixClient admin =
                nobody.as(
                        required(
                                env,
                                Config.ADMIN_TOKEN,
                                "the account's secrets from setup are missing"));
        return new Place(url, nobody, admin, adminId(nobody, admin));
    }

    /**
     * Whose the provisioning token Porelo holds is. A token the homeserver does not know, while no
     * {@code @porelo} exists there, means the homeserver's database was made anew since setup: said
     * as that, since what Porelo and poll keep from before has to go, and guessing past it would
     * lose messages.
     */
    private static String adminId(final MatrixClient nobody, final MatrixClient admin)
            throws Failure {
        try {
            return Accounts.whoami(admin);
        } catch (final Failure failure) {
            if (failure.exitCode() == Exit.NOT_PERMITTED && Accounts.available(nobody, ADMIN)) {
                throw new Failure(
                        Exit.CONFIG,
                        "the homeserver "
                                + nobody.config().homeserver()
                                + " was reset: its"
                                + " provisioning account '"
                                + ADMIN
                                + "' is gone, and the token Porelo holds belongs to it. Remove"
                                + " what Porelo keeps from this transport's setup - its account and every project's secrets - and"
                                + " this account's poll positions, then set up again",
                        failure);
            }
            throw new Failure(
                    Exit.CONFIG,
                    "the provisioning account's token Porelo holds is not valid on "
                            + nobody.config().homeserver()
                            + ": "
                            + failure.getMessage(),
                    failure);
        }
    }

    /**
     * An account for {@code localpart}: the one whose token Porelo holds, a new one, or - when it
     * exists and its token is gone - the same one with a fresh password, used once to log in.
     */
    private static Accounts.Session account(
            final MatrixClient nobody,
            final MatrixClient admin,
            final String adminId,
            final String localpart,
            @Nullable final String token,
            final String registrationToken)
            throws Failure {
        final String id = "@" + localpart + ":" + AdminRoom.server(adminId);
        if (token != null && !token.isBlank()) {
            final MatrixClient.Answer answer =
                    nobody.as(token).exchange("GET", "/_matrix/client/v3/account/whoami", null);
            if (answer.status() == 200 && id.equals(answer.body().path("user_id").asText())) {
                return new Accounts.Session(id, token);
            }
        }
        final String password = Accounts.password();
        if (Accounts.available(nobody, localpart)) {
            return Accounts.register(nobody, localpart, password, registrationToken);
        }
        AdminRoom.of(admin, adminId).setPassword(id, password);
        // Whoever held its lost token - a copy restored here holds every token it had - holds
        // nothing now.
        return Accounts.alone(nobody, localpart, password);
    }

    /**
     * The project's room: found by its alias, or made invite-only, closed to guests and unlisted.
     */
    static String room(
            final MatrixClient admin,
            final String adminId,
            final String project,
            final String server)
            throws Failure {
        final String alias = alias(project, server);
        final JsonNode found =
                admin.find("/_matrix/client/v3/directory/room/" + MatrixClient.segment(alias));
        if (found != null) {
            return ours(admin, adminId, alias, found.path("room_id").asText());
        }
        final ObjectNode request =
                MatrixClient.JSON
                        .createObjectNode()
                        .put("preset", "private_chat")
                        .put("visibility", "private")
                        .put("room_alias_name", "porelo-" + project)
                        .put("name", "Porelo: " + project);
        request.putArray("initial_state")
                .addObject()
                .put("type", "m.room.guest_access")
                .put("state_key", "")
                .putObject("content")
                .put("guest_access", "forbidden");
        return admin.post("/_matrix/client/v3/createRoom", request).path("room_id").asText();
    }

    /**
     * The room an alias names, taken only if it is the one Porelo would have made: created by
     * Porelo's own administrator, invite-only and closed to guests. Anybody else able to claim the
     * alias first - a person's account, or anyone on a shared homeserver - would otherwise have the
     * project's tasks and people put into a room of their making.
     */
    static String ours(
            final MatrixClient admin, final String adminId, final String alias, final String roomId)
            throws Failure {
        final String path = "/_matrix/client/v3/rooms/" + MatrixClient.segment(roomId);
        String creator = null;
        @Nullable JsonNode joinRules;
        @Nullable JsonNode guestAccess;
        try {
            // The room's first event is its creation; who sent it made the room, in every room
            // version.
            for (final JsonNode event : admin.get(path + "/messages?dir=f&limit=5").path("chunk")) {
                if ("m.room.create".equals(event.path("type").asText(""))) {
                    creator = event.path("sender").asText("");
                }
            }
            joinRules = admin.find(path + "/state/m.room.join_rules");
            guestAccess = admin.find(path + "/state/m.room.guest_access");
        } catch (final Failure failure) {
            if (failure.exitCode() != Exit.NOT_PERMITTED) {
                throw failure;
            }
            throw new Failure(
                    Exit.CONFIG,
                    alias
                            + " names "
                            + roomId
                            + ", which is not the room Porelo made: "
                            + adminId
                            + " is not in it. Nobody is put into it. Remove the alias, or name another project",
                    failure);
        }
        final String joinRule = joinRules == null ? "" : joinRules.path("join_rule").asText("");
        final List<String> wrong = new ArrayList<>();
        if (!adminId.equals(creator)) {
            wrong.add("it was made by " + creator + ", not " + adminId);
        }
        if (!"invite".equals(joinRule)) {
            wrong.add("its join rule is " + joinRule + ", not invite");
        }
        if (guestAccess == null
                || !"forbidden".equals(guestAccess.path("guest_access").asText(""))) {
            wrong.add("guests are not forbidden");
        }
        if (!wrong.isEmpty()) {
            throw new Failure(
                    Exit.CONFIG,
                    alias
                            + " names "
                            + roomId
                            + ", which is not the room Porelo made: "
                            + String.join("; ", wrong)
                            + ". Nobody is put into it. Remove the alias, or name another project");
        }
        return roomId;
    }

    static boolean loopback(final URI url) {
        final String host = url.getHost();
        return "127.0.0.1".equals(host)
                || "localhost".equals(host)
                || "[::1]".equals(host)
                || "::1".equals(host);
    }

    static String alias(final String project, final String server) {
        return "#porelo-" + project + ":" + server;
    }

    /** Into the room: invited by the administrator and joined, unless it is in already. */
    private static void admit(
            final MatrixClient admin,
            final String room,
            final Accounts.Session account,
            final MatrixClient nobody)
            throws Failure {
        final String path = "/_matrix/client/v3/rooms/" + MatrixClient.segment(room);
        final JsonNode member =
                admin.find(path + "/state/m.room.member/" + MatrixClient.segment(account.userId()));
        final String membership = member == null ? "" : member.path("membership").asText("");
        if ("join".equals(membership)) {
            return;
        }
        if (!"invite".equals(membership)) {
            admin.post(
                    path + "/invite",
                    MatrixClient.JSON.createObjectNode().put("user_id", account.userId()));
        }
        nobody.as(account.accessToken())
                .post(
                        "/_matrix/client/v3/join/" + MatrixClient.segment(room),
                        MatrixClient.JSON.createObjectNode());
    }

    private static MatrixClient client(final URI url, final String token, final Settings settings)
            throws Failure {
        return new MatrixClient(new Config(url, token, settings.caFile(), settings.verifyTls()));
    }

    private static void put(final ObjectNode node, final Map<String, String> values) {
        values.forEach(node::put);
    }

    private static Options options(final String verb, final List<String> args) throws Failure {
        String project = null;
        String task = null;
        String person = null;
        boolean reset = false;
        boolean token = false;
        boolean loopbackOnly = false;
        String display = null;
        String machine = null;
        boolean createRoom = false;
        List<String> machines = null;
        for (int i = 0; i < args.size(); i++) {
            final String arg = args.get(i);
            if ("--create-room".equals(arg) && "setup".equals(verb) && !createRoom) {
                createRoom = true;
                continue;
            }
            if ("--reset".equals(arg) && "join".equals(verb)) {
                reset = true;
                continue;
            }
            if ("--token".equals(arg) && "join".equals(verb) && !token) {
                token = true;
                continue;
            }
            if ("--loopback-only".equals(arg)
                    && !"retire".equals(verb)
                    && !"clear".equals(verb)
                    && !"dismiss".equals(verb)) {
                loopbackOnly = true;
                continue;
            }
            if (i + 1 >= args.size()) {
                throw usage(verb);
            }
            final String value = args.get(++i);
            switch (arg) {
                case "--project" -> project = name(value, "--project");
                case "--task" -> task = name(value, "--task");
                case "--person" -> person = name(value, "--person");
                case "--machine" -> machine = machine(value);
                case "--machines" -> {
                    if (!"members".equals(verb) || machines != null) {
                        throw usage(verb);
                    }
                    machines = machines(value);
                }
                case "--display" -> {
                    if (!("enroll".equals(verb) || "join".equals(verb)) || display != null) {
                        throw usage(verb);
                    }
                    display = display(value);
                }
                default -> throw usage(verb);
            }
        }
        final boolean ok =
                switch (verb) {
                    case "setup" -> task == null && person == null;
                    case "enroll", "retire" -> task != null && person == null;
                    case "join" -> person != null && task == null;
                    case "part" -> person != null && task == null && machine == null;
                    case "admit", "dismiss" -> machine != null && task == null && person == null;
                    case "members" -> machines != null && task == null && person == null;
                    case "clear" -> task == null && person == null;
                    default -> false;
                };
        if (project == null || !ok) {
            throw usage(verb);
        }
        return new Options(
                project,
                task,
                person,
                reset,
                token,
                loopbackOnly,
                display,
                machine,
                createRoom,
                machines);
    }

    /**
     * The machines {@code members} keeps in the room, comma-separated, each named as {@code
     * --machine} is; none at all is an empty value.
     */
    private static List<String> machines(final String value) throws Failure {
        if (value.isEmpty()) {
            return List.of();
        }
        final List<String> machines = new ArrayList<>();
        for (final String name : value.split(",", -1)) {
            final String machine = machine(name);
            if (!machines.contains(machine)) {
                machines.add(machine);
            }
        }
        if (machines.size() > MAX_MACHINES) {
            throw new Failure(
                    Exit.USAGE,
                    "--machines names at most "
                            + MAX_MACHINES
                            + " machines, not "
                            + machines.size());
        }
        return List.copyOf(machines);
    }

    static String display(final String value) throws Failure {
        final String nickname = value.strip();
        if (!DISPLAY.matcher(nickname).matches()) {
            throw new Failure(
                    Exit.USAGE,
                    "--display must be 1 to 64 characters, with no control characters, '@' or"
                            + " ':', not '"
                            + value
                            + "'");
        }
        return nickname;
    }

    private static String name(final String value, final String option) throws Failure {
        if (!NAME.matcher(value).matches()) {
            throw new Failure(
                    Exit.USAGE,
                    option
                            + " must be lower-case letters, digits and . _ = - , not '"
                            + value
                            + "'");
        }
        return value;
    }

    // A host name is case-insensitive and a Matrix localpart is lower case. Only ASCII is folded,
    // so
    // no other character can become one a name allows.
    private static String machine(final String value) throws Failure {
        final StringBuilder folded = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            final char c = value.charAt(i);
            folded.append(c >= 'A' && c <= 'Z' ? (char) (c + ('a' - 'A')) : c);
        }
        if (!NAME.matcher(folded).matches()) {
            throw new Failure(
                    Exit.USAGE,
                    "--machine must be letters, digits and . _ = - , not '" + value + "'");
        }
        return folded.toString();
    }

    private static Failure usage(final String verb) {
        return new Failure(
                Exit.USAGE,
                "usage: "
                        + switch (verb) {
                            case "setup" ->
                                    "setup --project <project> [--machine <machine>] [--create-room] [--loopback-only]";
                            case "members" ->
                                    "members --project <project> --machines <machine>,… [--machine <machine>]";
                            case "enroll" ->
                                    "enroll --project <project> --task <task> [--display <nickname>] [--machine <machine>] [--loopback-only]";
                            case "retire" ->
                                    "retire --project <project> --task <task> [--machine <machine>]";
                            case "clear" -> "clear [--project <project>] [--machine <machine>]";
                            case "part" ->
                                    "part --project <project> --person <person> [--loopback-only]";
                            case "admit" ->
                                    "admit --project <project> --machine <machine> [--loopback-only]";
                            case "dismiss" -> "dismiss --project <project> --machine <machine>";
                            default ->
                                    "join --project <project> --person <person> [--reset] [--token] [--display <nickname>] [--machine <machine>] [--loopback-only]";
                        });
    }

    private static List<String> required(@Nullable final List<String> machines) {
        // The options make members only with --machines.
        return Objects.requireNonNull(machines);
    }

    private static String required(@Nullable final String value, final String option)
            throws Failure {
        if (value == null) {
            throw new Failure(Exit.USAGE, option + " is missing");
        }
        return value;
    }

    private static String required(
            final Map<String, String> env, final String key, final String why) throws Failure {
        final String value = env.get(key);
        if (value == null || value.isBlank()) {
            throw new Failure(Exit.CONFIG, key + " is not set: " + why);
        }
        return value;
    }
}
