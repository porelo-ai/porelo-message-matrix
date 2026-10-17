package org.fuin.porelo.message.matrix;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * {@code poll --into <inbound>}: one sync, and every new Porelo message in the account's rooms
 * written into {@code inbound} as {@code <name>.json} beside its {@code <name>.json.sig}, each
 * whole or not at all. Where the last poll stopped is the transport's own state, saved only once
 * the files are written: a crash delivers a message twice, which Porelo drops by its id, and never
 * skips one.
 */
final class Poll {

    /**
     * Only messages, and nothing Porelo has no use for: no presence, no account data, no typing.
     */
    private static final String FILTER =
            """
            {"room":{"timeline":{"types":["m.room.message","m.room.encrypted"],"limit":100},"state":{"types":[]},\
            "ephemeral":{"types":[]},"account_data":{"types":[]}},\
            "presence":{"types":[]},"account_data":{"types":[]}}""";

    private static final int PAGE = 100;

    /**
     * The longest a poll waits on the homeserver for something new: well inside the client's
     * request limit.
     */
    static final int MAX_WAIT_SECONDS = 30;

    private Poll() {}

    static void run(
            final List<String> args,
            final Config config,
            final Map<String, String> env,
            final PrintStream err)
            throws Failure {
        // --persons: Porelo takes a person's words in the room too. --direct: run with a task's
        // token, only its
        // direct chats with people. Without either they are skipped as before, so a Porelo that
        // does not know
        // such files never finds one in its inbound.
        // --wait <seconds>: the homeserver holds the sync open until something arrives, so it is
        // handed over at
        // once rather than at the next poll.
        final String usage =
                "usage: poll --into <inbound> [--persons | --direct] [--wait <seconds, at most "
                        + MAX_WAIT_SECONDS
                        + ">]";
        String mode = "";
        int wait = 0;
        if (args.size() < 2 || !"--into".equals(args.get(0))) {
            throw new Failure(Exit.USAGE, usage);
        }
        for (int i = 2; i < args.size(); i++) {
            final String arg = args.get(i);
            if (mode.isEmpty() && ("--persons".equals(arg) || "--direct".equals(arg))) {
                mode = arg;
            } else if (wait == 0
                    && "--wait".equals(arg)
                    && i + 1 < args.size()
                    && args.get(i + 1).matches("[1-9][0-9]?")
                    && Integer.parseInt(args.get(i + 1)) <= MAX_WAIT_SECONDS) {
                wait = Integer.parseInt(args.get(++i));
            } else {
                throw new Failure(Exit.USAGE, usage);
            }
        }
        final Path inbound = Path.of(args.get(1));
        if (!Files.isDirectory(inbound)) {
            throw new Failure(Exit.USAGE, "--into " + inbound + " is not a directory");
        }
        final MatrixClient client = new MatrixClient(config);
        final String userId =
                client.get("/_matrix/client/v3/account/whoami").path("user_id").asText("");
        if (userId.isEmpty()) {
            throw new Failure(Exit.PROTOCOL, "the homeserver did not say whose token this is");
        }
        final Path stateDir = stateDirectory(env);
        final String key = key(config, userId);
        try {
            Files.createDirectories(stateDir);
        } catch (final IOException ex) {
            throw new Failure(
                    Exit.CONFIG,
                    "cannot create the state directory " + stateDir + ": " + ex.getMessage(),
                    ex);
        }
        try (FileChannel lockFile =
                        FileChannel.open(
                                stateDir.resolve(key + ".lock"),
                                StandardOpenOption.CREATE,
                                StandardOpenOption.WRITE);
                FileLock lock = tryLock(lockFile)) {
            final List<String> skipped =
                    poll(
                            client,
                            userId,
                            mode,
                            wait,
                            inbound,
                            stateDir,
                            stateDir.resolve(key + ".since"));
            if (!skipped.isEmpty()) {
                err.println(
                        "poll: skipped "
                                + skipped.size()
                                + " room message(s): "
                                + String.join("; ", skipped));
            }
        } catch (final IOException ex) {
            throw new Failure(
                    Exit.CONFIG,
                    "cannot use the state in " + stateDir + ": " + ex.getMessage(),
                    ex);
        }
    }

    /** Answers the room messages that were not delivered, each with why, by sender and room. */
    private static List<String> poll(
            final MatrixClient client,
            final String self,
            final String mode,
            final int wait,
            final Path inbound,
            final Path stateDir,
            final Path state)
            throws Failure {
        final boolean persons = "--persons".equals(mode);
        final boolean direct = "--direct".equals(mode);
        final Position position = readPosition(state);
        final String since = position.since();
        final List<String> joined = new ArrayList<>();
        client.get("/_matrix/client/v3/joined_rooms")
                .path("joined_rooms")
                .forEach(room -> joined.add(room.asText()));
        // A homeserver answers a position from an earlier database as if it were current, with
        // nothing new
        // in it: whatever was sent there before this poll would be lost without a word.
        if (!position.rooms().isEmpty() && position.rooms().stream().noneMatch(joined::contains)) {
            throw new Failure(
                    Exit.CONFIG,
                    "the sync position in "
                            + state
                            + " was taken in rooms this account is in"
                            + " no longer ("
                            + String.join(", ", position.rooms())
                            + "): the homeserver was reset, or the account"
                            + " was taken out of them. Remove "
                            + state
                            + " to poll the rooms it is in now from their start");
        }
        final List<String> skipped = new ArrayList<>();
        // A first sync answers at once whatever it is asked; only one from a position can wait for
        // news.
        final long deadline = System.nanoTime() + wait * 1_000_000_000L;
        String from = since;
        while (true) {
            final long remaining =
                    from == null ? 0 : Math.max(0, (deadline - System.nanoTime()) / 1_000_000L);
            final int handed =
                    once(
                            client, self, persons, direct, remaining, from, joined, inbound,
                            stateDir, state, skipped);
            // A homeserver ends its wait on anything new for the account, a read receipt too,
            // though the filter
            // leaves it out of the answer: an answer with nothing in it is no news, and the wait
            // goes on.
            if (handed > 0
                    || !skipped.isEmpty()
                    || from == null
                    || wait == 0
                    || deadline - System.nanoTime() <= 0) {
                return skipped;
            }
            from = readPosition(state).since();
        }
    }

    /**
     * One sync from a position, waiting up to the given milliseconds: what it brings is written
     * into inbound and the position saved after it. Answers how many messages were handed over.
     */
    private static int once(
            final MatrixClient client,
            final String self,
            final boolean persons,
            final boolean direct,
            final long timeoutMillis,
            @Nullable final String since,
            final List<String> joined,
            final Path inbound,
            final Path stateDir,
            final Path state,
            final List<String> skipped)
            throws Failure {
        final StringBuilder path =
                new StringBuilder("/_matrix/client/v3/sync?timeout=")
                        .append(timeoutMillis)
                        .append("&filter=")
                        .append(MatrixClient.segment(FILTER));
        if (since != null) {
            path.append("&since=").append(MatrixClient.segment(since));
        }
        final JsonNode sync = client.get(path.toString());
        final String next = sync.path("next_batch").asText("");
        if (next.isEmpty()) {
            throw new Failure(Exit.PROTOCOL, "the sync answered no next_batch");
        }
        int handed = 0;
        // A task's direct chats: answer the invitations first, so a chat a person just opened is
        // joined now and
        // read from the next poll on.
        final Map<String, Set<String>> directRooms = new java.util.HashMap<>();
        if (direct) {
            skipped.addAll(
                    Direct.answerInvitations(client, self, sync.path("rooms").path("invite")));
            directRooms.putAll(Direct.rooms(client, self));
        }
        // Paging back from a cut-short timeline can return events the timeline holds too: each is
        // handled once.
        final Set<String> seen = new HashSet<>();
        for (final Map.Entry<String, JsonNode> room :
                sync.path("rooms").path("join").properties()) {
            // With --direct only the task's direct chats are read: the project's room is the
            // relay's to read.
            final String person = direct ? Direct.person(directRooms, room.getKey()) : null;
            if (direct && person == null) {
                continue;
            }
            for (final JsonNode event :
                    timeline(client, room.getKey(), room.getValue().path("timeline"), since)) {
                if (!seen.add(event.path("event_id").asText(""))) {
                    continue;
                }
                // A client that encrypts - nheko in a chat it opens - writes words nobody here can
                // read: said, not lost.
                if ("m.room.encrypted".equals(event.path("type").asText())) {
                    if (!self.equals(event.path("sender").asText(""))) {
                        skipped.add(
                                "an encrypted message, which Porelo cannot read (write in a chat without encryption):"
                                        + " from "
                                        + event.path("sender").asText("?")
                                        + " in "
                                        + room.getKey());
                    }
                    continue;
                }
                // Other event types are room business - joins, names - and never a message to
                // deliver.
                if (!"m.room.message".equals(event.path("type").asText())) {
                    continue;
                }
                if (direct) {
                    // What the task's own account said there is its own, not a person's to hand
                    // back.
                    if (!self.equals(event.path("sender").asText(""))) {
                        final String why =
                                Person.deliver(
                                        client,
                                        self,
                                        room.getKey(),
                                        event,
                                        inbound,
                                        stateDir,
                                        self);
                        if (why == null) {
                            handed++;
                        } else {
                            skipped.add(why);
                        }
                    }
                    continue;
                }
                if (deliver(event, inbound, stateDir)) {
                    handed++;
                    continue;
                }
                // Not Porelo's own: a person's words, for the tasks it names - or said here why
                // not.
                final String why =
                        persons
                                ? Person.deliver(
                                        client, self, room.getKey(), event, inbound, stateDir, null)
                                : "not a Porelo message (poll --persons hands a person's words over): from "
                                        + event.path("sender").asText("?")
                                        + " in "
                                        + room.getKey();
                if (why == null) {
                    handed++;
                } else {
                    skipped.add(why);
                }
            }
        }
        writeAtomically(
                state,
                (next + "\n" + String.join("\n", joined))
                        .strip()
                        .concat("\n")
                        .getBytes(StandardCharsets.UTF_8),
                stateDir);
        return handed;
    }

    /**
     * The room's new events, oldest first. A sync may cut a busy timeline short; what it left out
     * is paged backwards down to where the last poll stopped, so nothing is skipped.
     */
    private static List<JsonNode> timeline(
            final MatrixClient client,
            final String room,
            final JsonNode timeline,
            @Nullable final String since)
            throws Failure {
        final List<JsonNode> events = new ArrayList<>();
        if (timeline.path("limited").asBoolean(false) && timeline.path("prev_batch").isTextual()) {
            final List<JsonNode> older = new ArrayList<>();
            String from = timeline.path("prev_batch").asText();
            while (true) {
                final StringBuilder path =
                        new StringBuilder("/_matrix/client/v3/rooms/")
                                .append(MatrixClient.segment(room))
                                .append("/messages?dir=b&limit=")
                                .append(PAGE)
                                .append("&filter=")
                                .append(
                                        MatrixClient.segment(
                                                "{\"types\":[\"m.room.message\",\"m.room.encrypted\"]}"))
                                .append("&from=")
                                .append(MatrixClient.segment(from));
                if (since != null) {
                    path.append("&to=").append(MatrixClient.segment(since));
                }
                final JsonNode page = client.get(path.toString());
                page.path("chunk").forEach(older::add);
                final String end = page.path("end").asText("");
                if (end.isEmpty() || end.equals(from) || page.path("chunk").isEmpty()) {
                    break;
                }
                from = end;
            }
            events.addAll(older.reversed());
        }
        timeline.path("events").forEach(events::add);
        return events;
    }

    /**
     * Writes one room message if it is a Porelo message, and answers whether it was one. What makes
     * it one is the signature field this transport puts beside every message it sends - never the
     * message's own content, which the transport does not read. A person's chat in the room has no
     * such field; whether and how it reaches a task is Porelo's to decide, not the transport's. The
     * message is its own field, never the body, which is the line for a person; an event of the
     * earlier form, the message in the body, is skipped too.
     */
    private static boolean deliver(final JsonNode event, final Path inbound, final Path stateDir)
            throws Failure {
        final JsonNode content = event.path("content");
        // A redacted message keeps its event and loses its content, so it is no Porelo message
        // either.
        if (!content.has(Send.SIGNATURE_FIELD) || !content.path(Send.MESSAGE_FIELD).isTextual()) {
            return false;
        }
        final String eventId = event.path("event_id").asText("");
        if (eventId.isEmpty()) {
            throw new Failure(Exit.PROTOCOL, "the sync returned a message without an event id");
        }
        final String reference = Reference.of(eventId, event.path("origin_server_ts").asLong(0));
        final String name = reference + ".json";
        // What the name stands for, before the message appears: read is handed the name back.
        Reference.remember(stateDir, reference, eventId);
        final byte[] signature = signature(content);
        // The signature first, so the message never appears without the one it came with. A message
        // that
        // came with none gets no .sig at all, and Porelo holds it as having arrived without a
        // signature.
        if (signature.length > 0) {
            writeAtomically(inbound.resolve(name + ".sig"), signature, inbound);
        }
        // Whose account sent it, as the homeserver says: every task reads the room, and Porelo
        // leaves the sender
        // out. Written before the message too, so the message never appears without it.
        writeAtomically(
                inbound.resolve(name + ".sender"),
                event.path("sender").asText("").getBytes(StandardCharsets.UTF_8),
                inbound);
        writeAtomically(
                inbound.resolve(name),
                content.path(Send.MESSAGE_FIELD).asText().getBytes(StandardCharsets.UTF_8),
                inbound);
        return true;
    }

    /**
     * What travelled beside the body, byte for byte - or nothing, when it is empty or not base64.
     */
    private static byte[] signature(final JsonNode content) {
        final JsonNode field = content.path(Send.SIGNATURE_FIELD);
        if (!field.isTextual()) {
            return new byte[0];
        }
        try {
            return Base64.getDecoder().decode(field.asText());
        } catch (final IllegalArgumentException ex) {
            return new byte[0];
        }
    }

    static void writeAtomically(final Path target, final byte[] bytes, final Path dir)
            throws Failure {
        final Path temporary = dir.resolve("." + target.getFileName() + ".part");
        try {
            Files.write(temporary, bytes);
            try {
                Files.move(
                        temporary,
                        target,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (final AtomicMoveNotSupportedException ex) {
                Files.deleteIfExists(temporary);
                throw new Failure(
                        Exit.CONFIG,
                        dir + " cannot rename atomically, so a half file could be read",
                        ex);
            }
        } catch (final IOException ex) {
            throw new Failure(
                    Exit.TEMPORARY, "cannot write " + target + ": " + ex.getMessage(), ex);
        }
    }

    /** Where the last poll stopped, and the rooms the account was in then. */
    private record Position(@Nullable String since, List<String> rooms) {}

    /**
     * The first line is the position; each further line a room - absent in a file from before they
     * were kept.
     */
    private static Position readPosition(final Path state) throws Failure {
        if (!Files.exists(state)) {
            return new Position(null, List.of());
        }
        try {
            final List<String> lines =
                    Files.readAllLines(state, StandardCharsets.UTF_8).stream()
                            .map(String::strip)
                            .filter(line -> !line.isEmpty())
                            .toList();
            return lines.isEmpty()
                    ? new Position(null, List.of())
                    : new Position(lines.getFirst(), lines.subList(1, lines.size()));
        } catch (final IOException ex) {
            throw new Failure(
                    Exit.CONFIG,
                    "cannot read the sync position " + state + ": " + ex.getMessage(),
                    ex);
        }
    }

    private static FileLock tryLock(final FileChannel channel) throws IOException, Failure {
        final FileLock lock;
        try {
            lock = channel.tryLock();
        } catch (final OverlappingFileLockException ex) {
            throw new Failure(Exit.TEMPORARY, "another poll for this account is running", ex);
        }
        if (lock == null) {
            throw new Failure(Exit.TEMPORARY, "another poll for this account is running");
        }
        return lock;
    }

    static Path stateDirectory(final Map<String, String> env) throws Failure {
        final String xdg = env.get("XDG_STATE_HOME");
        if (xdg != null && Path.of(xdg).isAbsolute()) {
            return Path.of(xdg, "porelo", "transports", "matrix");
        }
        final String home = env.get("HOME");
        if (home == null || !Path.of(home).isAbsolute()) {
            throw new Failure(
                    Exit.CONFIG,
                    "neither XDG_STATE_HOME nor HOME names an absolute directory for the sync position");
        }
        return Path.of(home, ".local", "state", "porelo", "transports", "matrix");
    }

    /** One position per account and homeserver, named so that no id has to be a safe file name. */
    /** What names an account's position and lock: the homeserver and the account, together. */
    static String key(final Config config, final String userId) {
        return sha256(config.homeserver() + "\n" + userId);
    }

    private static String sha256(final String value) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (final NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is required of every Java platform", ex);
        }
    }
}
