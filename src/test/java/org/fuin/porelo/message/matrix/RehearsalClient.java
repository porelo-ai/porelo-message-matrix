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

    private final MatrixClient gate;
    private final PrintStream out;
    private final LongSupplier clock;
    private @Nullable MatrixClient session;
    private final Map<String, String> lastPosted = new HashMap<>();

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
                step = line.strip();
                if (!step.isEmpty() && !step.startsWith("#")) {
                    client.step(step);
                }
            }
            return Exit.OK;
        } catch (final Failure ex) {
            err.println("FAILED: " + step + ": " + ex.getMessage());
            return ex.exitCode();
        } catch (final IOException ex) {
            err.println("FAILED: " + step + ": standard input: " + ex.getMessage());
            return Exit.TEMPORARY;
        }
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
        final String room =
                signedIn()
                        .post(
                                CLIENT + "join/" + MatrixClient.segment(words[1]),
                                MatrixClient.JSON.createObjectNode())
                        .path("room_id")
                        .asText("");
        required(room, "join", "room_id");
        answer("join").put("room", room);
        flush();
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

    /** Every member's text in the room, oldest first, with who sent it and as whom they show. */
    private void read(final String[] words) throws Failure {
        arguments(words, 1);
        final Map<String, String> names = displayNames(words[1]);
        for (final JsonNode event : messages(words[1])) {
            final String sender = event.path("sender").asText();
            answer("read")
                    .put("room", words[1])
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
        try {
            return new RoomRehearsal.Posted(room, say(room, text, List.of()));
        } catch (final Failure failure) {
            // Not left open, so no later answer of the task goes into it.
            try {
                leave(room);
            } catch (final Failure ignored) {
                // The line's failure is what the run reports.
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
            final JsonNode answer = answerAfter(messages(room), line, words[2]);
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
            // A redacted message has no text, and says nothing back.
            if (after
                    && event.path("sender").asText().equals(sender)
                    && !event.path("content").path("body").asText("").isEmpty()) {
                return event;
            }
            after |= event.path("event_id").asText("").equals(line);
        }
        return null;
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
        final MatrixClient signedIn = signedIn();
        signedIn.post(CLIENT + "logout", MatrixClient.JSON.createObjectNode());
        final int after = signedIn.exchange("GET", CLIENT + "account/whoami", null).status();
        if (after != 401) {
            throw new Failure(Exit.PROTOCOL, "logged out, but whoami answered " + after);
        }
        session = null;
        answer("log-out").put("whoami", after);
        flush();
    }

    /** Logs a session still open out, so a failed run leaves none behind. */
    @Override
    public void close() {
        final MatrixClient open = session;
        session = null;
        if (open != null) {
            try {
                open.exchange("POST", CLIENT + "logout", MatrixClient.JSON.createObjectNode());
            } catch (final Failure ex) {
                // The run already failed or ended; an unreachable gate keeps the session's expiry.
            }
        }
    }

    @Override
    public List<JsonNode> messages(final String room) throws Failure {
        final JsonNode chunk =
                signedIn()
                        .get(
                                CLIENT
                                        + "rooms/"
                                        + MatrixClient.segment(room)
                                        + "/messages?dir=b&limit=200")
                        .path("chunk");
        if (!chunk.isArray()) {
            throw new Failure(Exit.PROTOCOL, "the room's messages came with no chunk");
        }
        final List<JsonNode> events = new ArrayList<>();
        for (final JsonNode event : chunk) {
            if (event.path("type").asText().equals("m.room.message")) {
                events.addFirst(event);
            }
        }
        return events;
    }

    @Override
    public void leave(final String room) throws Failure {
        signedIn()
                .post(
                        CLIENT + "rooms/" + MatrixClient.segment(room) + "/leave",
                        MatrixClient.JSON.createObjectNode());
    }

    @Override
    public java.util.Set<String> members(final String room) throws Failure {
        return java.util.Set.copyOf(displayNames(room).keySet());
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
