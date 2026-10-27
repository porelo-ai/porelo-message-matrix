package org.fuin.porelo.message.matrix;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.net.URI;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.jspecify.annotations.Nullable;

/** A person's conversation in one local room, with its login held only in the vault. */
final class Room {

    private static final int CREDENTIAL_BYTES = 65_536;
    private static final Duration CREDENTIAL_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration READER_TIMEOUT = Duration.ofSeconds(1);
    private static final DateTimeFormatter UTC =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm'Z'").withZone(ZoneOffset.UTC);
    private final Path porelo;

    /** The credential program is replaceable by a test's stand-in. */
    Room(final Path porelo) {
        this.porelo = porelo;
    }

    /**
     * The {@code porelo} the caller's search path finds first, so an account's own install shadows
     * the machine's as it does for every other command; it runs with the caller's own rights.
     */
    static Path porelo(final Map<String, String> env) throws Failure {
        for (final String entry : env.getOrDefault("PATH", "").split(":")) {
            if (!entry.startsWith("/")) {
                continue;
            }
            final Path candidate = Path.of(entry, "porelo");
            if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) {
                return candidate;
            }
        }
        throw new Failure(Exit.CONFIG, "no porelo on the search path to read the vault entry with");
    }

    /**
     * Runs a room verb as the caller asked it, its arguments checked before the helper is found.
     */
    static void start(
            final List<String> args,
            final Map<String, String> env,
            final PrintStream out,
            final PrintStream err)
            throws Failure {
        options(args);
        new Room(porelo(env)).run(args, env, out, err);
    }

    /** The checked command, before any helper or homeserver is called. */
    private record Options(
            String verb, String entry, String room, @Nullable Path file, boolean all) {}

    /** One sync's complete text timeline and its next position. */
    private record Batch(String next, List<JsonNode> lines) {}

    /** Reads, posts or waits in the named room. */
    void run(
            final List<String> args,
            final Map<String, String> env,
            final PrintStream out,
            final PrintStream err)
            throws Failure {
        final Options options = options(args);
        for (final String variable :
                List.of(
                        Config.ACCESS_TOKEN,
                        Config.ADMIN_TOKEN,
                        Config.REGISTRATION_TOKEN,
                        Config.MACHINE_SECRET)) {
            if (env.containsKey(variable)) {
                throw new Failure(
                        Exit.CONFIG, variable + " must be unset for room; use the vault entry");
            }
        }
        final int port = port(env);
        final String token = credential(options.entry(), port, env, err);
        final MatrixClient client =
                new MatrixClient(
                        new Config(URI.create("http://127.0.0.1:" + port), token, null, true));
        final String self =
                get(client, "/_matrix/client/v3/account/whoami").path("user_id").asText("");
        if (self.isEmpty()) {
            throw new Failure(Exit.PROTOCOL, "the homeserver did not say whose token this is");
        }
        final Path stateDir = Poll.stateDirectory(env);
        final Path dir = stateDir.resolve("room");
        final String key = Poll.key(client.config(), self + "\n" + options.room());
        final Path state = dir.resolve(key + ".since");
        if ("wait".equals(options.verb())) {
            final Poll.Position position = Poll.readPosition(state);
            final String since = requirePosition(position);
            membership(client, options.room(), position);
            waitFor(client, options.room(), self, since, out);
            return;
        }
        try {
            Files.createDirectories(dir);
            try (FileChannel channel =
                            FileChannel.open(
                                    dir.resolve(key + ".lock"),
                                    StandardOpenOption.CREATE,
                                    StandardOpenOption.WRITE);
                    FileLock held = lock(channel)) {
                final Poll.Position position = Poll.readPosition(state);
                membership(client, options.room(), position);
                if ("read".equals(options.verb())) {
                    read(client, options, self, position, state, out);
                } else {
                    post(client, options, self, requirePosition(position));
                }
            }
        } catch (final IOException ex) {
            throw new Failure(Exit.CONFIG, "cannot use the room position");
        }
    }

    /** Reads only an existing local configuration; a room command never chooses a new port. */
    private static int port(final Map<String, String> env) throws Failure {
        try {
            final String value =
                    LocalHomeserver.setting(
                            LocalHomeserver.directory(env).resolve("homeserver.conf"),
                            LocalHomeserver.PORT);
            if (value != null && value.matches("[0-9]{1,5}")) {
                final int port = Integer.parseInt(value);
                if (port > 0 && port <= 65_535) {
                    return port;
                }
            }
        } catch (final IOException ex) {
            throw new Failure(Exit.CONFIG, "cannot read the local homeserver's port");
        }
        throw new Failure(Exit.CONFIG, "the local homeserver's configuration must name a port");
    }

    /** Reads one token over git's credential pipe, without retaining helper output in errors. */
    String credential(
            final String entry,
            final int port,
            final Map<String, String> env,
            final PrintStream err)
            throws Failure {
        final ProcessBuilder builder =
                new ProcessBuilder(
                        porelo.toString(), "vault", "credential", "--entry", entry, "get");
        builder.environment().clear();
        builder.environment().putAll(env);
        try {
            return credential(builder.start(), entry, port, err);
        } catch (final InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw credentialFailure(entry);
        } catch (final IOException | ExecutionException | TimeoutException | Failure ex) {
            throw credentialFailure(entry);
        }
    }

    /**
     * Inherited pipes may outlive the helper, so neither readers nor cleanup may hold the caller.
     */
    private static String credential(
            final Process process, final String entry, final int port, final PrintStream err)
            throws IOException,
                    InterruptedException,
                    ExecutionException,
                    TimeoutException,
                    Failure {
        final long deadline = System.nanoTime() + CREDENTIAL_TIMEOUT.toNanos();
        final Set<ProcessHandle> descendants = new HashSet<>();
        final FutureTask<byte[]> output =
                new FutureTask<>(
                        () -> {
                            try (InputStream input = process.getInputStream()) {
                                return credentialAnswer(input);
                            }
                        });
        final FutureTask<Boolean> errors =
                new FutureTask<>(
                        () -> {
                            try (InputStream input = process.getErrorStream()) {
                                final byte[] buffer = new byte[4096];
                                int remaining = CREDENTIAL_BYTES;
                                int read;
                                while ((read = input.read(buffer)) >= 0) {
                                    if (Thread.currentThread().isInterrupted()) {
                                        return false;
                                    }
                                    final int shown = Math.min(read, remaining);
                                    err.write(buffer, 0, shown);
                                    remaining -= shown;
                                }
                                return true;
                            }
                        });
        Thread.ofPlatform().daemon().name("matrix-credential-stdout").start(output);
        Thread.ofPlatform().daemon().name("matrix-credential-stderr").start(errors);
        try {
            boolean requested = true;
            try (var input = process.getOutputStream()) {
                input.write(
                        ("protocol=http\nhost=127.0.0.1:" + port + "\n\n")
                                .getBytes(StandardCharsets.UTF_8));
            } catch (final IOException ex) {
                requested = false;
            }
            while (process.isAlive()) {
                descendants.addAll(process.descendants().toList());
                if (requested && output.isDone() && output.get().length > CREDENTIAL_BYTES) {
                    throw credentialFailure(entry);
                }
                final long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    throw credentialFailure(entry);
                }
                process.waitFor(
                        Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(50)),
                        TimeUnit.NANOSECONDS);
            }
            final long readDeadline =
                    Math.min(deadline, System.nanoTime() + READER_TIMEOUT.toNanos());
            // The answer first: it ends at its empty line, while stderr may stay open in a
            // descendant.
            final byte[] bytes =
                    requested && process.exitValue() == 0
                            ? output.get(
                                    Math.max(0, readDeadline - System.nanoTime()),
                                    TimeUnit.NANOSECONDS)
                            : new byte[0];
            try {
                errors.get(Math.max(0, readDeadline - System.nanoTime()), TimeUnit.NANOSECONDS);
            } catch (final TimeoutException ex) {
                // A descendant may retain stderr after the helper has finished its answer.
            }
            if (!requested || process.exitValue() != 0) {
                throw credentialFailure(entry);
            }
            if (bytes.length > CREDENTIAL_BYTES) {
                throw credentialFailure(entry);
            }
            return password(Send.utf8(bytes, "the credential answer"), entry);
        } finally {
            descendants.addAll(process.descendants().toList());
            descendants.forEach(ProcessHandle::destroyForcibly);
            // Process.destroyForcibly closes streams and can wait on a blocked reader.
            process.toHandle().destroyForcibly();
            output.cancel(true);
            errors.cancel(true);
        }
    }

    /** Git's empty line ends the answer even when an inherited pipe has no EOF. */
    private static byte[] credentialAnswer(final InputStream input) throws IOException {
        final byte[] bytes = new byte[CREDENTIAL_BYTES + 1];
        int length = 0;
        int start = 0;
        while (length < bytes.length) {
            final int value = input.read();
            if (value < 0) {
                break;
            }
            bytes[length++] = (byte) value;
            if (value == '\n') {
                if (length - start == 1 || (length - start == 2 && bytes[length - 2] == '\r')) {
                    break;
                }
                start = length;
            }
        }
        return Arrays.copyOf(bytes, length);
    }

    /** The helper's values never become a diagnostic, including for an invalid header token. */
    private static String password(final String answer, final String entry) throws Failure {
        String password = null;
        for (final String field : answer.split("\n", -1)) {
            final String line =
                    field.endsWith("\r") ? field.substring(0, field.length() - 1) : field;
            if (line.isEmpty()) {
                break;
            }
            final int equals = line.indexOf('=');
            if (equals < 1) {
                throw credentialFailure(entry);
            }
            if ("password".equals(line.substring(0, equals))) {
                if (password != null) {
                    throw credentialFailure(entry);
                }
                password = line.substring(equals + 1);
            }
        }
        if (password == null || !password.matches("[\\x21-\\x7e]+")) {
            throw credentialFailure(entry);
        }
        return password;
    }

    /** A fixed refusal with no helper output or exception cause. */
    private static Failure credentialFailure(final String entry) {
        return new Failure(Exit.CONFIG, "cannot read vault entry '" + entry + "'");
    }

    /** A missing or stale position must not silently skip the person's conversation. */
    private static void membership(
            final MatrixClient client, final String room, final Poll.Position position)
            throws Failure {
        final Set<String> joined = new HashSet<>();
        get(client, "/_matrix/client/v3/joined_rooms")
                .path("joined_rooms")
                .forEach(id -> joined.add(id.asText()));
        if (!position.rooms().isEmpty() && position.rooms().stream().noneMatch(joined::contains)) {
            throw new Failure(
                    Exit.CONFIG,
                    "the room position belongs to rooms this account is in no longer; the homeserver was reset or the account left them; remove the room position and read again");
        }
        if (!joined.contains(room)) {
            throw new Failure(Exit.NOT_PERMITTED, "the account is not in the requested room");
        }
    }

    /** The current room members and the names their room profiles show. */
    private static Map<String, String> members(final MatrixClient client, final String room)
            throws Failure {
        final Map<String, String> members = new LinkedHashMap<>();
        get(client, "/_matrix/client/v3/rooms/" + MatrixClient.segment(room) + "/joined_members")
                .path("joined")
                .properties()
                .forEach(
                        member ->
                                members.put(
                                        member.getKey(),
                                        member.getValue()
                                                .path("display_name")
                                                .asText(member.getKey())));
        return members;
    }

    /** A sync cut short by the server is completed before any decision about unread work. */
    private static Batch sync(
            final MatrixClient client,
            final String room,
            @Nullable final String since,
            final int timeout)
            throws Failure {
        final ObjectNode filter = MatrixClient.JSON.createObjectNode();
        final ObjectNode rooms = filter.putObject("room");
        rooms.putArray("rooms").add(room);
        rooms.putObject("timeline").put("limit", 100).putArray("types").add("m.room.message");
        rooms.putObject("state").putArray("types");
        rooms.putObject("ephemeral").putArray("types");
        final String path =
                "/_matrix/client/v3/sync?timeout="
                        + timeout
                        + "&filter="
                        + MatrixClient.segment(filter.toString())
                        + (since == null ? "" : "&since=" + MatrixClient.segment(since));
        final JsonNode answer = get(client, path);
        final String next = answer.path("next_batch").asText("");
        if (!next.matches("[\\x21-\\x7e]{1,4096}")) {
            throw new Failure(Exit.PROTOCOL, "the homeserver's sync named no usable next position");
        }
        final List<JsonNode> lines = new ArrayList<>();
        if (since != null) {
            final List<JsonNode> events;
            try {
                events =
                        Poll.timeline(
                                client,
                                room,
                                answer.path("rooms").path("join").path(room).path("timeline"),
                                since);
            } catch (final Failure failure) {
                throw serverFailure(failure);
            }
            lines.addAll(messages(room, events));
        }
        return new Batch(next, lines);
    }

    /** The complete timeline's text lines, counted once by their server identity. */
    static List<JsonNode> messages(final String room, final List<JsonNode> events) throws Failure {
        final List<JsonNode> lines = new ArrayList<>();
        final Set<String> seen = new HashSet<>();
        for (final JsonNode event : events) {
            if (!"m.room.message".equals(event.path("type").asText())
                    || (event.has("room_id") && !room.equals(event.path("room_id").asText()))) {
                continue;
            }
            final JsonNode identity = event.path("event_id");
            if (!identity.isTextual() || identity.asText().isEmpty()) {
                throw new Failure(Exit.PROTOCOL, "a room message has no event id");
            }
            if ("m.text".equals(event.path("content").path("msgtype").asText())
                    && event.path("content").path("body").isTextual()
                    && !"m.replace"
                            .equals(
                                    event.path("content")
                                            .path("m.relates_to")
                                            .path("rel_type")
                                            .asText())
                    && seen.add(identity.asText())) {
                lines.add(event);
            }
        }
        return lines;
    }

    /** Prints before saving, so a failed output never consumes unread work. */
    private static void read(
            final MatrixClient client,
            final Options options,
            final String self,
            final Poll.Position position,
            final Path state,
            final PrintStream out)
            throws Failure {
        final Batch batch = sync(client, options.room(), position.since(), 0);
        if (position.since() != null) {
            final Map<String, String> members = members(client, options.room());
            for (final JsonNode event : batch.lines()) {
                if (options.all()
                        ? !self.equals(event.path("sender").asText())
                        : MeantFor.line(event, self, members)) {
                    out.println(heading(event, members));
                    out.println(event.path("content").path("body").asText());
                    out.println();
                }
            }
        }
        if (out.checkError()) {
            throw new Failure(Exit.TEMPORARY, "cannot write the room lines; position was kept");
        }
        Poll.writeAtomically(
                state,
                (batch.next() + "\n" + options.room() + "\n").getBytes(StandardCharsets.UTF_8),
                java.util.Objects.requireNonNull(state.getParent()));
    }

    /** Posts only after a complete check for unread work, without consuming that work. */
    private static void post(
            final MatrixClient client, final Options options, final String self, final String since)
            throws Failure {
        final Batch batch = sync(client, options.room(), since, 0);
        final Map<String, String> members = members(client, options.room());
        final long unread =
                batch.lines().stream().filter(line -> MeantFor.line(line, self, members)).count();
        if (unread != 0) {
            throw new Failure(
                    Exit.TEMPORARY,
                    unread
                            + " unread room line(s) are meant for this account; read before posting");
        }
        final String body;
        try {
            body =
                    Send.utf8(
                            Files.readAllBytes(java.util.Objects.requireNonNull(options.file())),
                            "the room post");
        } catch (final IOException ex) {
            throw new Failure(Exit.USAGE, "cannot read the room post file");
        }
        final ObjectNode content =
                MatrixClient.JSON.createObjectNode().put("msgtype", "m.text").put("body", body);
        final MeantFor head = MeantFor.post(body, members, self);
        final ObjectNode mentions = content.putObject("m.mentions");
        final var users = mentions.putArray("user_ids");
        head.users().stream().sorted().forEach(users::add);
        if (head.room()) {
            mentions.put("room", true);
        }
        if (Send.size(content) > Send.CONTENT_BYTES) {
            throw new Failure(
                    Exit.DATA, "the serialized room post exceeds " + Send.CONTENT_BYTES + " bytes");
        }
        final String transaction =
                Send.transactionId(
                        body.getBytes(StandardCharsets.UTF_8),
                        (self + "\n" + options.room() + "\n" + since)
                                .getBytes(StandardCharsets.UTF_8));
        final JsonNode answer;
        try {
            answer =
                    client.put(
                            "/_matrix/client/v3/rooms/"
                                    + MatrixClient.segment(options.room())
                                    + "/send/m.room.message/"
                                    + transaction,
                            content);
        } catch (final Failure failure) {
            throw serverFailure(failure);
        }
        if (!answer.path("event_id").isTextual()) {
            throw new Failure(Exit.PROTOCOL, "the homeserver accepted the post but named no event");
        }
    }

    /** Keeps progress in memory so the waking lines remain unread. */
    private static void waitFor(
            final MatrixClient client,
            final String room,
            final String self,
            final String since,
            final PrintStream out)
            throws Failure {
        String from = since;
        while (true) {
            final Batch batch = sync(client, room, from, 30_000);
            final Map<String, String> members = members(client, room);
            boolean found = false;
            for (final JsonNode event : batch.lines()) {
                if (MeantFor.line(event, self, members)) {
                    out.println(heading(event, members));
                    found = true;
                }
            }
            if (found) {
                if (out.checkError()) {
                    throw new Failure(Exit.TEMPORARY, "cannot write the room headings");
                }
                return;
            }
            from = batch.next();
        }
    }

    /** The server's timestamp and room display name are the line's attribution. */
    private static String heading(final JsonNode event, final Map<String, String> members) {
        final String sender = event.path("sender").asText();
        return "## "
                + UTC.format(Instant.ofEpochMilli(event.path("origin_server_ts").asLong()))
                + " — "
                + members.getOrDefault(sender, sender);
    }

    /** A first read must establish a position before a post or wait. */
    private static String requirePosition(final Poll.Position position) throws Failure {
        final String since = position.since();
        if (since == null) {
            throw new Failure(Exit.CONFIG, "no room position; run room read first");
        }
        return since;
    }

    /** Concurrent readers and posters must not overwrite each other's position. */
    private static FileLock lock(final FileChannel channel) throws IOException, Failure {
        try {
            final FileLock lock = channel.tryLock();
            if (lock != null) {
                return lock;
            }
        } catch (final OverlappingFileLockException ex) {
            throw new Failure(
                    Exit.TEMPORARY, "another room read or post for this account is running");
        }
        throw new Failure(Exit.TEMPORARY, "another room read or post for this account is running");
    }

    /** Server error text is untrusted and may echo the bearer credential. */
    private static JsonNode get(final MatrixClient client, final String path) throws Failure {
        try {
            return client.get(path);
        } catch (final Failure failure) {
            throw serverFailure(failure);
        }
    }

    /** Keeps the contract's exit code without an untrusted message or cause. */
    private static Failure serverFailure(final Failure failure) {
        return new Failure(
                failure.exitCode(),
                "the room homeserver request failed (exit " + failure.exitCode() + ")");
    }

    /** Rejects ambiguous command shapes before any credential can be read. */
    private static Options options(final List<String> args) throws Failure {
        if (args.isEmpty() || !List.of("read", "post", "wait").contains(args.getFirst())) {
            throw usage();
        }
        final String verb = args.getFirst();
        String entry = null;
        String room = null;
        Path file = null;
        boolean all = false;
        for (int i = 1; i < args.size(); i++) {
            final String arg = args.get(i);
            if ("--all".equals(arg) && "read".equals(verb) && !all) {
                all = true;
            } else if ("--entry".equals(arg) && entry == null && i + 1 < args.size()) {
                entry = args.get(++i);
            } else if ("--room".equals(arg) && room == null && i + 1 < args.size()) {
                room = args.get(++i);
            } else if ("post".equals(verb) && file == null && !arg.startsWith("--")) {
                try {
                    file = Path.of(arg);
                } catch (final InvalidPathException ex) {
                    throw usage();
                }
            } else {
                throw usage();
            }
        }
        if (entry == null
                || entry.isBlank()
                || entry.startsWith("-")
                || entry.codePoints().anyMatch(Character::isISOControl)
                || room == null
                || !room.matches("![^\\s\\p{Cntrl}]+")
                || room.getBytes(StandardCharsets.UTF_8).length > 255
                || ("post".equals(verb) && file == null)) {
            throw usage();
        }
        return new Options(verb, entry, room, file, all);
    }

    /** The room group's calling shape. */
    private static Failure usage() {
        return new Failure(
                Exit.USAGE,
                "usage: room read [--all] | post <file> | wait --entry <vault entry> --room <room id>");
    }
}
