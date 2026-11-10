package org.fuin.porelo.message.matrix;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.LongSupplier;
import org.jspecify.annotations.Nullable;

/**
 * A person's Matrix client for a rehearsal on a host, which nobody sits at: it signs in through the
 * gate's SSO exactly as a browser's navigation does, then posts, reads and logs out. Ships in no
 * package.
 *
 * <p>Run with the test classpath as {@code RehearsalClient --socket <path>}, the path being the
 * local end of an ssh forward to the gate's socket made as the person's host login. The steps come
 * one per line on standard input, so the session's token lives only in this process; each step
 * answers one JSON line on standard output. A step that fails ends the run non-zero, naming what
 * was asked and what came back, after the session is logged out.
 */
final class RehearsalClient implements AutoCloseable, RoomRehearsal.Room {

    private static final String CLIENT = "/_matrix/client/v3/";

    /** The forward's own page: a client on a Unix socket is reached as {@code localhost}. */
    private static final String OWN_PAGE = "http://localhost/";

    private static final Duration POLL = Duration.ofSeconds(2);

    /** Only the room's messages are read back, so other events never crowd a line out. */
    private static final String MESSAGES_ONLY = "{\"types\":[\"m.room.message\"]}";

    private static final int PAGE = 100;

    /** How many pages a read goes back at most, so a room's long history cannot hold the run. */
    static final int PAGES = 100;

    private final MatrixClient gate;
    private final PrintStream out;
    private final LongSupplier clock;
    private @Nullable MatrixClient session;
    private final Map<String, String> lastPosted = new HashMap<>();
    private final java.util.Set<String> directs = new java.util.LinkedHashSet<>();
    private String sessionToken = "";
    private String loginToken = "";
    private boolean sayLogOut = true;

    /** The longest a step waits for an answer, so a mistyped limit cannot hold the run for days. */
    static final long LONGEST_WAIT_SECONDS = 3600;

    RehearsalClient(final Path socket, final PrintStream out) throws Failure {
        this(socket, out, System::nanoTime, MatrixClient::runningAccount);
    }

    RehearsalClient(
            final Path socket,
            final PrintStream out,
            final LongSupplier clock,
            final MatrixClient.ListenerOwner owner)
            throws Failure {
        this.gate =
                new MatrixClient(
                                new Config(
                                        URI.create("unix:" + socket.toUri().getRawPath()),
                                        "",
                                        null,
                                        true))
                        .listenedToBy(owner);
        this.out = out;
        this.clock = clock;
    }

    /**
     * Runs the steps on {@code args}' socket.
     *
     * @param args {@code --socket <path>}
     */
    public static void main(final String[] args) {
        System.exit(run(args, System.in, System.out, System.err));
    }

    static int run(
            final String[] args,
            final InputStream in,
            final PrintStream out,
            final PrintStream err) {
        if (args.length != 2 || !args[0].equals("--socket")) {
            err.println("usage: RehearsalClient --socket <path>");
            return Exit.USAGE;
        }
        String step = "";
        try (RehearsalClient client = new RehearsalClient(Path.of(args[1]), out);
                BufferedReader steps =
                        new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            for (String line = steps.readLine(); line != null; line = steps.readLine()) {
                final String given = line.strip();
                // A line is named by its keyword only: its rest may be anything a caller typed.
                step = given.split("\\s+")[0];
                if (!given.isEmpty() && !given.startsWith("#")) {
                    try {
                        client.step(given);
                    } catch (final Failure ex) {
                        throw new Failure(ex.exitCode(), client.scrub(ex.getMessage()), ex);
                    }
                }
            }
            step = "log-out at the end of the steps";
            try {
                client.endSession();
            } catch (final Failure ex) {
                throw new Failure(ex.exitCode(), client.scrub(ex.getMessage()), ex);
            }
            return Exit.OK;
        } catch (final Failure ex) {
            err.println("FAILED: " + step + ": " + said(ex.getMessage()));
            return ex.exitCode();
        } catch (final IOException ex) {
            err.println("FAILED: " + step + ": standard input: " + said(ex.getMessage()));
            return Exit.TEMPORARY;
        }
    }

    /** What a failure says, on one line. */
    private static String said(final @Nullable String text) {
        return String.valueOf(text).replaceAll("\\p{Cc}+", " ");
    }

    void step(final String line) throws Failure {
        final String[] words = line.split("\\s+");
        switch (words[0]) {
            case "sign-in" -> signIn(words);
            case "join" -> join(words);
            case "post" -> post(words);
            case "read" -> read(words);
            case "direct" -> direct(words);
            case "wait-answer" -> waitAnswer(words);
            case "joined" -> joined(words);
            case "leave" -> leave(words);
            case "log-out" -> logOut(words);
            default -> throw new Failure(Exit.USAGE, "no such step");
        }
    }

    /** Asks the gate's SSO for a login token, as a navigation sends it, and trades it in. */
    private void signIn(final String[] words) throws Failure {
        arguments(words, 0);
        if (session != null) {
            throw new Failure(Exit.USAGE, "already signed in");
        }
        final Wire.Reply redirect =
                gate.rawExchange(
                        "GET",
                        CLIENT
                                + "login/sso/redirect/porelo?redirectUrl="
                                + MatrixClient.segment(OWN_PAGE),
                        null);
        final String location = header(redirect.raw(), "Location");
        final String token =
                redirect.statusCode() == 302 && location != null ? loginToken(location) : null;
        loginToken = token == null ? "" : token;
        if (token == null || token.isEmpty()) {
            throw new Failure(
                    Exit.PROTOCOL,
                    "asked the gate's SSO for a login token, got "
                            + redirect.statusCode()
                            + (location == null ? " with no Location" : " with no loginToken"));
        }
        final MatrixClient.Answer login =
                gate.exchange(
                        "POST",
                        CLIENT + "login",
                        MatrixClient.JSON
                                .createObjectNode()
                                .put("type", "m.login.token")
                                .put("token", token));
        final String access = login.body().path("access_token").asText("");
        final String user = login.body().path("user_id").asText("");
        if (!access.isEmpty()) {
            // Kept before it is judged, so that a session issued with a wrong answer is logged out.
            session = gate.as(access);
            sessionToken = access;
        }
        if (login.status() != 200 || access.isEmpty() || user.isEmpty()) {
            throw new Failure(
                    Exit.PROTOCOL,
                    "traded the login token, got "
                            + login.status()
                            + (login.errcode().isEmpty()
                                    ? " and no session"
                                    : " " + login.errcode()));
        }
        answer("sign-in").put("user", user);
        flush();
    }

    private void join(final String[] words) throws Failure {
        arguments(words, 1);
        answer("join").put("room", join(words[1]));
        flush();
    }

    private String join(final String room) throws Failure {
        final String joined =
                signedIn()
                        .post(
                                CLIENT + "join/" + MatrixClient.segment(room),
                                MatrixClient.JSON.createObjectNode())
                        .path("room_id")
                        .asText("");
        required(joined, "join", "room_id");
        return joined;
    }

    /**
     * {@code post <room> <file> [@user...] [@room]}: the file's text as one {@code m.text}; each
     * named member, and the whole room, marked in {@code m.mentions} as a client's pill marks it.
     */
    private void post(final String[] words) throws Failure {
        if (words.length < 3) {
            throw new Failure(Exit.USAGE, "post <room> <file> [@user...] [@room]");
        }
        final String event = say(words[1], text(words[2]), List.of(words).subList(3, words.length));
        answer("post").put("room", words[1]).put("event", event);
        flush();
    }

    @Override
    public String say(final String room, final String text, final List<String> mentions)
            throws Failure {
        final ObjectNode content =
                MatrixClient.JSON.createObjectNode().put("msgtype", "m.text").put("body", text);
        if (!mentions.isEmpty()) {
            final ObjectNode marked = content.putObject("m.mentions");
            for (final String mention : mentions) {
                if (mention.equals("@room")) {
                    marked.put("room", true);
                } else {
                    marked.withArray("user_ids").add(mention);
                }
            }
        }
        final String event = send(room, content);
        lastPosted.put(room, event);
        return event;
    }

    /**
     * Every member's text in the room, oldest first, with who sent it and as whom they show, as one
     * answer.
     */
    private void read(final String[] words) throws Failure {
        arguments(words, 1);
        final Map<String, String> names = displayNames(words[1]);
        final ObjectNode answer = answer("read").put("room", words[1]);
        final var read = answer.putArray("messages");
        for (final JsonNode event : messages(words[1], null)) {
            if (!plainText(event)) {
                continue;
            }
            final String sender = event.path("sender").asText();
            read.addObject()
                    .put("event", event.path("event_id").asText())
                    .put("sender", sender)
                    .put("display", names.getOrDefault(sender, sender))
                    .put("body", event.path("content").path("body").asText());
        }
        flush();
    }

    /** {@code direct <user> <file>}: a direct chat with that member, unencrypted, and one line. */
    private void direct(final String[] words) throws Failure {
        arguments(words, 2);
        final RoomRehearsal.Posted posted = direct(words[1], text(words[2]));
        answer("direct").put("room", posted.room()).put("event", posted.event());
        flush();
    }

    @Override
    public RoomRehearsal.Posted direct(final String user, final String text) throws Failure {
        final ObjectNode request =
                MatrixClient.JSON
                        .createObjectNode()
                        .put("preset", "trusted_private_chat")
                        .put("is_direct", true);
        request.putArray("invite").add(user);
        final String room =
                signedIn().post(CLIENT + "createRoom", request).path("room_id").asText("");
        required(room, "createRoom", "room_id");
        directs.add(room);
        try {
            return new RoomRehearsal.Posted(room, say(room, text, List.of()));
        } catch (final Failure | RuntimeException failure) {
            // Not left open, so no later answer of the task goes into it.
            try {
                leave(room);
            } catch (final Failure ignored) {
                // The line's failure is what the run reports; close() tries the leave again.
            }
            throw failure;
        }
    }

    /**
     * {@code wait-answer <room> <user> <seconds>}: waits until {@code user} writes in the room
     * after this run's last line there, and names that answer.
     */
    private void waitAnswer(final String[] words) throws Failure {
        arguments(words, 3);
        final String room = words[1];
        final String line = lastPosted.get(room);
        if (line == null) {
            throw new Failure(Exit.USAGE, "nothing posted in " + room + " to wait on");
        }
        final long seconds;
        try {
            seconds = Long.parseLong(words[3]);
        } catch (final NumberFormatException ex) {
            throw new Failure(Exit.USAGE, "seconds must be a number");
        }
        if (seconds < 0 || seconds > LONGEST_WAIT_SECONDS) {
            throw new Failure(Exit.USAGE, "seconds must be 0 to " + LONGEST_WAIT_SECONDS);
        }
        final long deadline = clock.getAsLong() + Duration.ofSeconds(seconds).toNanos();
        while (true) {
            final JsonNode answer = answerAfter(messages(room, line), line, words[2]);
            if (answer != null) {
                answer("wait-answer")
                        .put("room", room)
                        .put("event", answer.path("event_id").asText())
                        .put("sender", words[2])
                        .put("body", answer.path("content").path("body").asText());
                flush();
                return;
            }
            if (clock.getAsLong() - deadline >= 0) {
                throw new Failure(
                        Exit.TEMPORARY,
                        "no answer from "
                                + words[2]
                                + " after "
                                + line
                                + " within "
                                + seconds
                                + " s");
            }
            pause();
        }
    }

    /** The first line {@code sender} wrote after {@code line}, or null when there is none. */
    static @Nullable JsonNode answerAfter(
            final List<JsonNode> events, final String line, final String sender) {
        boolean after = false;
        for (final JsonNode event : events) {
            if (after && event.path("sender").asText().equals(sender) && plainText(event)) {
                return event;
            }
            after |= event.path("event_id").asText("").equals(line);
        }
        return null;
    }

    /**
     * A new text line of the room's timeline: not redacted (no text left), not an edit of an older
     * one, not a line of a thread.
     */
    static boolean plainText(final JsonNode event) {
        final JsonNode content = event.path("content");
        final String relation = content.path("m.relates_to").path("rel_type").asText("");
        return "m.text".equals(content.path("msgtype").asText())
                && !content.path("body").asText("").isEmpty()
                && !relation.equals("m.replace")
                && !relation.equals("m.thread");
    }

    /** {@code joined}: the rooms the person is in, so chats an earlier run left can go. */
    private void joined(final String[] words) throws Failure {
        arguments(words, 0);
        final ObjectNode answer = answer("joined");
        final var rooms = answer.putArray("rooms");
        signedIn().get(CLIENT + "joined_rooms").path("joined_rooms").forEach(rooms::add);
        flush();
    }

    /** {@code leave <room>}: the person leaves it, as a person closes a chat. */
    private void leave(final String[] words) throws Failure {
        arguments(words, 1);
        leave(words[1]);
        answer("leave").put("room", words[1]);
        flush();
    }

    private void logOut(final String[] words) throws Failure {
        arguments(words, 0);
        logOut();
    }

    /** Logs a session the steps left open out, checked as the step checks it, said by no line. */
    void endSession() throws Failure {
        if (session != null) {
            sayLogOut = false;
            logOut();
        }
    }

    private void logOut() throws Failure {
        final MatrixClient signedIn = signedIn();
        // A chat this session opened and did not leave would take a later run's answer.
        for (final String room : List.copyOf(directs)) {
            leave(room);
        }
        signedIn.post(CLIENT + "logout", MatrixClient.JSON.createObjectNode());
        final int after = signedIn.exchange("GET", CLIENT + "account/whoami", null).status();
        if (after != 401) {
            throw new Failure(Exit.PROTOCOL, "logged out, but whoami answered " + after);
        }
        session = null;
        if (sayLogOut) {
            answer("log-out").put("whoami", after);
            flush();
        }
    }

    /**
     * Logs a session still open out, so a failed or interrupted run leaves none behind: an
     * interrupt would close the socket before the logout reached the gate, so it waits until after.
     */
    @Override
    public void close() {
        final MatrixClient open = session;
        if (open != null) {
            final boolean interrupted = Thread.interrupted();
            try {
                for (final String room : List.copyOf(directs)) {
                    try {
                        leave(room);
                    } catch (final Failure | RuntimeException ex) {
                        // The logout still follows; the chat stays, as a person's would.
                    }
                }
                session = null;
                open.exchange("POST", CLIENT + "logout", MatrixClient.JSON.createObjectNode());
            } catch (final Failure ex) {
                // The run already failed or ended; an unreachable gate keeps the session's expiry.
            } finally {
                if (interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    @Override
    public String scrub(final String text) {
        String scrubbed = text;
        for (final String token : List.of(sessionToken, loginToken)) {
            if (!token.isEmpty()) {
                scrubbed = scrubbed.replace(token, "<token>");
            }
        }
        return scrubbed;
    }

    @Override
    public List<JsonNode> messages(final String room, final @Nullable String back) throws Failure {
        final List<JsonNode> events = new ArrayList<>();
        String from = "";
        for (int page = 0; page < PAGES; page++) {
            final JsonNode answer =
                    signedIn()
                            .get(
                                    CLIENT
                                            + "rooms/"
                                            + MatrixClient.segment(room)
                                            + "/messages?dir=b&limit="
                                            + PAGE
                                            + "&filter="
                                            + MatrixClient.segment(MESSAGES_ONLY)
                                            + (from.isEmpty()
                                                    ? ""
                                                    : "&from=" + MatrixClient.segment(from)));
            final JsonNode chunk = answer.path("chunk");
            if (!chunk.isArray()) {
                throw new Failure(Exit.PROTOCOL, "the room's messages came with no chunk");
            }
            boolean reached = false;
            for (final JsonNode event : chunk) {
                if (event.path("type").asText().equals("m.room.message")) {
                    events.addFirst(event);
                }
                reached |= back != null && back.equals(event.path("event_id").asText());
            }
            final String end = answer.path("end").asText("");
            // A page that names itself as the next would be read again for ever.
            final boolean start = chunk.isEmpty() || end.isEmpty() || end.equals(from);
            from = end;
            if (reached || start) {
                if (back != null && !reached) {
                    throw new Failure(
                            Exit.DATA,
                            "read " + room + " back to its start, " + back + " not in it");
                }
                return events;
            }
        }
        throw new Failure(
                Exit.DATA,
                "read "
                        + PAGES * PAGE
                        + " events of "
                        + room
                        + " back"
                        + (back == null ? ", not to its start" : " without " + back));
    }

    @Override
    public void leave(final String room) throws Failure {
        signedIn()
                .post(
                        CLIENT + "rooms/" + MatrixClient.segment(room) + "/leave",
                        MatrixClient.JSON.createObjectNode());
        directs.remove(room);
    }

    @Override
    public java.util.Set<String> members(final String room) throws Failure {
        final java.util.Set<String> members = new java.util.HashSet<>(displayNames(room).keySet());
        final JsonNode invited =
                signedIn()
                        .get(
                                CLIENT
                                        + "rooms/"
                                        + MatrixClient.segment(room)
                                        + "/members?membership=invite")
                        .path("chunk");
        if (!invited.isArray()) {
            throw new Failure(Exit.PROTOCOL, "the room's invited came with no chunk");
        }
        invited.forEach(member -> members.add(member.path("state_key").asText()));
        return java.util.Set.copyOf(members);
    }

    private Map<String, String> displayNames(final String room) throws Failure {
        final Map<String, String> names = new HashMap<>();
        final JsonNode joined =
                signedIn()
                        .get(CLIENT + "rooms/" + MatrixClient.segment(room) + "/joined_members")
                        .path("joined");
        if (!joined.isObject()) {
            throw new Failure(Exit.PROTOCOL, "the room's members came with no joined");
        }
        joined.properties()
                .forEach(
                        member ->
                                names.put(
                                        member.getKey(),
                                        member.getValue()
                                                .path("display_name")
                                                .asText(member.getKey())));
        return names;
    }

    private String send(final String room, final ObjectNode content) throws Failure {
        final String event =
                signedIn()
                        .put(
                                CLIENT
                                        + "rooms/"
                                        + MatrixClient.segment(room)
                                        + "/send/m.room.message/"
                                        + UUID.randomUUID(),
                                content)
                        .path("event_id")
                        .asText("");
        required(event, "send", "event_id");
        return event;
    }

    private static void required(final String value, final String call, final String field)
            throws Failure {
        if (value.isEmpty()) {
            throw new Failure(Exit.PROTOCOL, call + " answered with no " + field);
        }
    }

    private static String text(final String file) throws Failure {
        try {
            return Files.readString(Path.of(file), StandardCharsets.UTF_8);
        } catch (final IOException ex) {
            throw new Failure(Exit.DATA, "cannot read " + file + ": " + ex.getMessage(), ex);
        }
    }

    private MatrixClient signedIn() throws Failure {
        final MatrixClient open = session;
        if (open == null) {
            throw new Failure(Exit.USAGE, "not signed in");
        }
        return open;
    }

    private ObjectNode answer(final String step) {
        pending.add(MatrixClient.JSON.createObjectNode().put("step", step));
        return pending.getLast();
    }

    private final List<ObjectNode> pending = new ArrayList<>();

    private void flush() throws Failure {
        pending.forEach(out::println);
        pending.clear();
        out.flush();
        // A step whose line never reached the reader did not happen for it.
        if (out.checkError()) {
            throw new Failure(Exit.TEMPORARY, "standard output cannot be written");
        }
    }

    private static void arguments(final String[] words, final int count) throws Failure {
        if (words.length != count + 1) {
            throw new Failure(Exit.USAGE, words[0] + " takes " + count + " argument(s)");
        }
    }

    private static void pause() throws Failure {
        try {
            Thread.sleep(POLL);
        } catch (final InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new Failure(Exit.TEMPORARY, "interrupted while waiting for an answer", ex);
        }
    }

    private static @Nullable String loginToken(final String location) {
        try {
            final String query = URI.create(location).getRawQuery();
            return query == null ? null : GateRequest.query(query).get("loginToken");
        } catch (final IOException | IllegalArgumentException ex) {
            return null;
        }
    }

    private static @Nullable String header(final byte[] raw, final String name) {
        final String head = new String(raw, StandardCharsets.ISO_8859_1);
        final int end = head.indexOf("\r\n\r\n");
        for (final String line : (end < 0 ? head : head.substring(0, end)).split("\r\n")) {
            final int colon = line.indexOf(':');
            if (colon > 0
                    && line.substring(0, colon)
                            .strip()
                            .toLowerCase(Locale.ROOT)
                            .equals(name.toLowerCase(Locale.ROOT))) {
                return line.substring(colon + 1).strip();
            }
        }
        return null;
    }
}
