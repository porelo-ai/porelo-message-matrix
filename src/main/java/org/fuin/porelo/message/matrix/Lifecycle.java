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
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * The verbs by which Porelo has the transport make what a project's conversation needs - {@code
 * setup}, {@code enroll}, {@code retire} and {@code join} - and take it away again, {@code clear}.
 * Porelo hands each the project's settings on stdin and the secrets it kept as the environment, and
 * keeps what it prints without reading it: all that is Matrix is here.
 */
final class Lifecycle {

    static final List<String> VERBS = List.of("setup", "enroll", "retire", "join", "clear");

    /** The provisioning account: the first registered, so the homeserver's administrator. */
    static final String ADMIN = "porelo";

    /** What a project's, a task's or a person's name may be, as part of a Matrix id. */
    private static final Pattern NAME = Pattern.compile("[a-z0-9][a-z0-9._=-]{0,120}");

    private Lifecycle() {}

    private record Options(
            String project,
            @Nullable String task,
            @Nullable String person,
            boolean reset,
            boolean loopbackOnly,
            @Nullable String display) {}

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
        if ("clear".equals(verb) && args.isEmpty()) {
            // The account's own homeserver and what the transport keeps: no project, so no settings
            // to read.
            print(out, LocalHomeserver.clear(env).answer());
            return;
        }
        final Options options = options(verb, args);
        final Settings settings = Settings.read(in);
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

    private static ObjectNode setup(
            final Options options,
            final Settings settings,
            final Map<String, String> env,
            final Unit unit)
            throws Failure {
        final URI url;
        final String reaches;
        final String registrationToken;
        if (settings.homeserver() == null) {
            final LocalHomeserver.Running local = LocalHomeserver.ensure(env, unit);
            url = local.url();
            reaches = "127.0.0.1:" + local.port();
            registrationToken = local.registrationToken();
        } else {
            url = settings.homeserver();
            // Host and port, as the contract says, the scheme's own port where the URL names none.
            reaches =
                    url.getHost()
                            + ":"
                            + (url.getPort() >= 0
                                    ? url.getPort()
                                    : "https".equals(url.getScheme()) ? 443 : 80);
            registrationToken = elsewhereToken(settings, env);
        }
        final MatrixClient nobody = client(url, "", settings);

        final Accounts.Session admin =
                admin(nobody, env, registrationToken, settings.homeserver() == null ? unit : null);
        final MatrixClient asAdmin = nobody.as(admin.accessToken());
        AdminRoom.of(asAdmin, admin.userId());
        final String server = AdminRoom.server(admin.userId());
        final String room = room(asAdmin, admin.userId(), options.project(), server);

        final Accounts.Session relay =
                account(
                        nobody,
                        asAdmin,
                        admin.userId(),
                        "porelo-relay-" + options.project(),
                        env.get(Config.ACCESS_TOKEN),
                        registrationToken);
        admit(asAdmin, room, relay, nobody);

        final ObjectNode answer = MatrixClient.JSON.createObjectNode();
        answer.putObject("account").put(Config.ADMIN_TOKEN, admin.accessToken());
        put(answer.putObject("secrets"), settings.environment(url, relay.accessToken()));
        answer.put("conversation", room);
        answer.putArray("reaches").add(reaches);
        return answer;
    }

    /**
     * The provisioning account: the one Porelo holds, the first one registered now, or - on the
     * account's own homeserver just restored from a copy, whose vault did not come with it - the
     * restored one taken back. Never a second.
     */
    private static Accounts.Session admin(
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

    // --- enroll, retire, join
    // ----------------------------------------------------------------------------

    private static ObjectNode enroll(
            final Options options, final Settings settings, final Map<String, String> env)
            throws Failure {
        final String task = required(options.task(), "--task");
        final Place place = place(settings, env);
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

    private static ObjectNode retire(
            final Options options, final Settings settings, final Map<String, String> env)
            throws Failure {
        final String task = required(options.task(), "--task");
        final Place place = place(settings, env);
        // Asked twice, the homeserver answers the same: retiring again after a failure is harmless.
        AdminRoom.of(place.admin(), place.adminId())
                .deactivate("@" + task + ":" + AdminRoom.server(place.adminId()));
        return MatrixClient.JSON.createObjectNode();
    }

    private static ObjectNode join(
            final Options options, final Settings settings, final Map<String, String> env)
            throws Failure {
        final String person = required(options.person(), "--person");
        if (person.startsWith(ADMIN)) {
            throw new Failure(
                    Exit.USAGE,
                    "a person's name must not begin with '"
                            + ADMIN
                            + "', which the machinery's accounts use");
        }
        final Place place = place(settings, env);
        final String password = Accounts.password();
        final Accounts.Session account;
        if (Accounts.available(place.nobody(), person)) {
            account =
                    Accounts.register(
                            place.nobody(),
                            person,
                            password,
                            place.registrationToken(settings, env));
        } else if (options.reset()) {
            final String id = "@" + person + ":" + AdminRoom.server(place.adminId());
            AdminRoom.of(place.admin(), place.adminId()).setPassword(id, password);
            account = Accounts.login(place.nobody(), person, password);
        } else {
            throw new Failure(
                    Exit.NOT_PERMITTED,
                    "'" + person + "' has an account already; --reset gives it a new password");
        }
        final String room = place.room(options.project());
        admit(place.admin(), room, account, place.nobody());

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
    private static String room(
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
        final List<String> wrong = new java.util.ArrayList<>();
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
        boolean loopbackOnly = false;
        String display = null;
        for (int i = 0; i < args.size(); i++) {
            final String arg = args.get(i);
            if ("--reset".equals(arg) && "join".equals(verb)) {
                reset = true;
                continue;
            }
            if ("--loopback-only".equals(arg) && !"retire".equals(verb) && !"clear".equals(verb)) {
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
                case "--display" -> {
                    if (!"enroll".equals(verb) || display != null) {
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
                    case "clear" -> task == null && person == null;
                    default -> false;
                };
        if (project == null || !ok) {
            throw usage(verb);
        }
        return new Options(project, task, person, reset, loopbackOnly, display);
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

    private static Failure usage(final String verb) {
        return new Failure(
                Exit.USAGE,
                "usage: "
                        + switch (verb) {
                            case "setup" -> "setup --project <project> [--loopback-only]";
                            case "enroll" ->
                                    "enroll --project <project> --task <task> [--display <nickname>] [--loopback-only]";
                            case "retire" -> "retire --project <project> --task <task>";
                            case "clear" -> "clear [--project <project>]";
                            default ->
                                    "join --project <project> --person <person> [--reset] [--loopback-only]";
                        });
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
