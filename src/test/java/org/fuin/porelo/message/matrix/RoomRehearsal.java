package org.fuin.porelo.message.matrix;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * The room's scenarios of a rehearsal on a host: a person's lines posted through {@link
 * RehearsalClient}, each judged by the tasks' records ({@code porelo talk log}) and by who answers
 * in the room. Ships in no package.
 *
 * <p>Run with the test classpath as {@code RoomRehearsal --socket <path> --room <!room-id>
 * [--answer-within <s>] [--quiet-for <s>] --task <task>=<@account>=<display> ... --talk-log
 * <command...>}; the task's name is appended to the {@code talk log} command, which runs on the
 * host as the person (for instance {@code ssh <person>@<host> porelo talk log}). One line per
 * scenario, {@code OK} or {@code FAIL}, with what was asked and what came back; exit 0 only when
 * every scenario held.
 */
final class RoomRehearsal {

    /** What the rehearsal does through the person's client. */
    interface Room {

        /** Posts {@code text} in {@code room}, each of {@code mentions} marked; its event id. */
        String say(String room, String text, List<String> mentions) throws Failure;

        /** Opens an unencrypted direct chat with {@code user} and posts {@code text} there. */
        Posted direct(String user, String text) throws Failure;

        /** The room's text messages, oldest first. */
        List<JsonNode> messages(String room) throws Failure;

        /** The accounts joined to the room. */
        Set<String> members(String room) throws Failure;

        /** Leaves the room, as a person closes a chat. */
        default void leave(final String room) throws Failure {}
    }

    /** A line posted, and the room it went to. */
    record Posted(String room, String event) {}

    /** The record a task keeps of what reached it, as {@code porelo talk log} prints it. */
    @FunctionalInterface
    interface TalkLog {
        String of(String task) throws Failure;
    }

    /** A task of the project: its name on the host, its Matrix account, its shown name. */
    record Task(String name, String account, String display) {

        static Task parse(final String spec) throws Failure {
            final String[] parts = spec.split("=", 3);
            // The name goes onto the host's command line, where ssh hands it to a shell.
            if (parts.length != 3
                    || !TASK_NAME.matcher(parts[0]).matches()
                    || !ACCOUNT.matcher(parts[1]).matches()
                    || parts[2].isBlank()
                    || parts[2].chars().anyMatch(Character::isISOControl)) {
                throw new Failure(
                        Exit.USAGE,
                        "--task takes <task>=<@account>=<display>, the task named by"
                                + " [a-z0-9][a-z0-9._-]*");
            }
            return new Task(parts[0], parts[1], parts[2]);
        }
    }

    private static final Pattern TASK_NAME = Pattern.compile("[a-z0-9][a-z0-9._-]{0,127}");

    private static final Pattern ACCOUNT =
            Pattern.compile("@[a-z0-9._=/+-]+:[A-Za-z0-9.:\\[\\]-]+");

    private static final long LONGEST_LOG = 64L << 20;

    private static final long POLL_NANOS = TimeUnit.SECONDS.toNanos(2);

    private final Room room;
    private final String roomId;
    private final List<Task> tasks;
    private final TalkLog talkLog;
    private final Duration answerWithin;
    private final Duration quietFor;
    private final LongSupplier clock;
    private final Runnable pause;
    private final PrintStream out;
    private @Nullable String lastFailure;
    private boolean held = true;

    RoomRehearsal(
            final Room room,
            final String roomId,
            final List<Task> tasks,
            final TalkLog talkLog,
            final Duration answerWithin,
            final Duration quietFor,
            final LongSupplier clock,
            final Runnable pause,
            final PrintStream out) {
        this.room = room;
        this.roomId = roomId;
        this.tasks = List.copyOf(tasks);
        this.talkLog = talkLog;
        this.answerWithin = answerWithin;
        this.quietFor = quietFor;
        this.clock = clock;
        this.pause = pause;
        this.out = out;
    }

    /**
     * Runs the scenarios against a host.
     *
     * @param args see the class's description
     */
    public static void main(final String[] args) {
        System.exit(run(args, System.out, System.err));
    }

    static int run(final String[] args, final PrintStream out, final PrintStream err) {
        try {
            final Arguments given = Arguments.parse(args);
            try (RehearsalClient client = new RehearsalClient(given.socket(), out)) {
                client.step("sign-in");
                final boolean held =
                        new RoomRehearsal(
                                        client,
                                        given.room(),
                                        given.tasks(),
                                        command(given.talkLog(), Duration.ofSeconds(60)),
                                        given.answerWithin(),
                                        given.quietFor(),
                                        System::nanoTime,
                                        RoomRehearsal::sleep,
                                        out)
                                .runAll();
                client.step("log-out");
                return held ? Exit.OK : Exit.DATA;
            }
        } catch (final Failure ex) {
            err.println("FAILED: " + ex.getMessage());
            return ex.exitCode();
        }
    }

    /** Every scenario, in order; false when any did not hold or the run stopped on a repeat. */
    boolean runAll() {
        final List<Scenario> scenarios = new ArrayList<>();
        final Task first = tasks.getFirst();
        scenarios.add(
                new Scenario(
                        "a head names one task",
                        nonce ->
                                named(
                                        first,
                                        first.display() + ": " + ask(nonce),
                                        List.of(),
                                        nonce)));
        scenarios.add(
                new Scenario(
                        "a head with a pill names one task",
                        nonce ->
                                named(
                                        first,
                                        first.display() + ": " + ask(nonce),
                                        List.of(first.account()),
                                        nonce)));
        boolean prefixed = false;
        for (final Task longer : tasks) {
            for (final Task shorter : tasks) {
                if (longer != shorter && longer.display().startsWith(shorter.display())) {
                    prefixed = true;
                    for (final Task meant : List.of(longer, shorter)) {
                        scenarios.add(
                                new Scenario(
                                        "a head names "
                                                + meant.display()
                                                + " whole, beside "
                                                + (meant == longer ? shorter : longer).display(),
                                        nonce ->
                                                named(
                                                        meant,
                                                        meant.display() + ": " + ask(nonce),
                                                        List.of(),
                                                        nonce)));
                    }
                }
            }
        }
        scenarios.add(
                new Scenario(
                        "All agents: wakes every task",
                        nonce -> every("All agents: " + ask(nonce), List.of(), nonce)));
        scenarios.add(
                new Scenario(
                        "@room wakes every task",
                        nonce -> every("@room " + ask(nonce), List.of("@room"), nonce)));
        scenarios.add(
                new Scenario(
                        "a line naming nobody wakes every task",
                        nonce -> every(ask(nonce), List.of(), nonce)));
        scenarios.add(
                new Scenario("a direct chat reaches one task", nonce -> direct(first, nonce)));
        scenarios.add(
                new Scenario("the room reads in order, with no stranger", this::readsInOrder));
        if (!prefixed) {
            out.println(
                    "SKIP a head names a display whole: no two tasks whose displays share a start");
        }
        for (final Scenario scenario : scenarios) {
            if (!judge(scenario)) {
                return false;
            }
        }
        return held;
    }

    private record Scenario(String name, Check check) {}

    @FunctionalInterface
    private interface Check {
        /** Null when it held, otherwise what was asked and what came back. */
        @Nullable String failure(String nonce) throws Failure;
    }

    /** Reports one scenario; false when the run must stop because a failure repeated. */
    private boolean judge(final Scenario scenario) {
        String failure;
        try {
            failure = scenario.check().failure(nonce());
        } catch (final Failure ex) {
            failure = ex.getMessage();
        }
        if (failure == null) {
            out.println("OK   " + scenario.name());
            lastFailure = null;
            return true;
        }
        out.println("FAIL " + scenario.name() + ": " + failure);
        held = false;
        final boolean repeated = failure.equals(lastFailure);
        lastFailure = failure;
        if (repeated) {
            out.println("STOP the same failure twice in a row");
        }
        return !repeated;
    }

    /** The words that make a task's answer to this line its own, told apart from a late one. */
    private static String ask(final String nonce) {
        return "answer with " + nonce;
    }

    /** {@code task} alone answers the line, and every task's record shows it delivered. */
    private @Nullable String named(
            final Task task, final String text, final List<String> mentions, final String nonce)
            throws Failure {
        final String event = room.say(roomId, text, mentions);
        if (awaitAnswer(roomId, event, task, nonce) == null) {
            return "asked "
                    + task.display()
                    + " in the room, got no answer within "
                    + seconds(answerWithin);
        }
        final List<String> others = answeredWithin(roomId, event, task, nonce);
        if (!others.isEmpty()) {
            return "asked "
                    + task.display()
                    + " alone, got answers within "
                    + seconds(quietFor)
                    + " from "
                    + String.join(", ", others);
        }
        return undelivered(event, tasks);
    }

    /** Every task answers the line, and every task's record shows it delivered. */
    private @Nullable String every(
            final String text, final List<String> mentions, final String nonce) throws Failure {
        final String event = room.say(roomId, text, mentions);
        final List<String> silent = new ArrayList<>();
        for (final Task task : tasks) {
            if (awaitAnswer(roomId, event, task, nonce) == null) {
                silent.add(task.display());
            }
        }
        if (!silent.isEmpty()) {
            return "asked every task, got no answer within "
                    + seconds(answerWithin)
                    + " from "
                    + String.join(", ", silent);
        }
        return undelivered(event, tasks);
    }

    /** {@code task} answers in the direct chat, and only its record has the line. */
    private @Nullable String direct(final Task task, final String nonce) throws Failure {
        final Posted posted =
                room.direct(task.account(), "Only for " + task.display() + ": " + ask(nonce));
        try {
            return directAnswered(task, nonce, posted);
        } finally {
            // Closed as a person closes it, so no later run's answer goes into this chat.
            room.leave(posted.room());
        }
    }

    private @Nullable String directAnswered(
            final Task task, final String nonce, final Posted posted) throws Failure {
        if (awaitAnswer(posted.room(), posted.event(), task, nonce) == null) {
            return "asked "
                    + task.display()
                    + " directly, got no answer within "
                    + seconds(answerWithin);
        }
        final String lacking = undelivered(posted.event(), List.of(task));
        if (lacking != null) {
            return lacking;
        }
        // Another task's poll may come later than the answer; it is read once the quiet time
        // passed.
        waitOut(quietFor);
        final String id = messageId(posted.event());
        for (final Task other : tasks) {
            if (other != task && delivered(talkLog.of(other.name()), id)) {
                return "asked "
                        + task.display()
                        + " directly, talk log of "
                        + other.name()
                        + " has "
                        + id;
            }
        }
        return null;
    }

    /**
     * The posted lines in the order they were sent; every sender a task or the person, every member
     * one of them or a helper of the room on the person's server, which reads but never writes.
     */
    private @Nullable String readsInOrder(final String nonce) throws Failure {
        final String one = room.say(roomId, "order one " + nonce, List.of());
        final String two = room.say(roomId, "order two " + nonce, List.of());
        final List<JsonNode> messages = room.messages(roomId);
        final List<String> ids = messages.stream().map(m -> m.path("event_id").asText()).toList();
        if (ids.indexOf(one) < 0 || ids.indexOf(two) < ids.indexOf(one)) {
            return "posted " + one + " then " + two + ", read " + ids;
        }
        final String person = messages.get(ids.indexOf(one)).path("sender").asText();
        final Set<String> known = new java.util.HashSet<>(Set.of(person));
        tasks.forEach(task -> known.add(task.account()));
        final Set<String> senders = new java.util.TreeSet<>();
        messages.forEach(m -> senders.add(m.path("sender").asText()));
        senders.removeAll(known);
        if (!senders.isEmpty()) {
            return "read lines of accounts no task or person holds: " + senders;
        }
        final String server = person.substring(person.indexOf(':') + 1);
        final Set<String> strangers = new java.util.TreeSet<>(room.members(roomId));
        strangers.removeAll(known);
        strangers.removeIf(id -> helper(id, server));
        return strangers.isEmpty()
                ? null
                : "read members no task, person or helper is: " + strangers;
    }

    /** The room's own accounts, as the transport names them: its maker, a login's, a relay. */
    private static boolean helper(final String id, final String server) {
        final String suffix = ":" + server;
        if (!id.startsWith("@") || !id.endsWith(suffix)) {
            return false;
        }
        final String local = id.substring(1, id.length() - suffix.length());
        return "porelo".equals(local)
                || local.startsWith("porelo.")
                || local.startsWith("porelo-relay-");
    }

    /**
     * Null when each of {@code these} shows the line delivered in its {@code talk log} within the
     * answer time, else which lack it.
     */
    private @Nullable String undelivered(final String event, final List<Task> these)
            throws Failure {
        final String id = messageId(event);
        final long deadline = clock.getAsLong() + answerWithin.toNanos();
        while (true) {
            final List<String> lacking = new ArrayList<>();
            for (final Task task : these) {
                if (!delivered(talkLog.of(task.name()), id)) {
                    lacking.add(task.name());
                }
            }
            if (lacking.isEmpty()) {
                return null;
            }
            if (clock.getAsLong() - deadline >= 0) {
                return "posted "
                        + id
                        + ", talk log lacks it delivered within "
                        + seconds(answerWithin)
                        + " in "
                        + String.join(", ", lacking);
            }
            pause.run();
        }
    }

    static boolean delivered(final String log, final String id) {
        return log.lines()
                .map(line -> line.strip().split("\\s+"))
                .anyMatch(
                        words ->
                                words.length >= 4
                                        && words[1].equals("delivered")
                                        && words[3].endsWith(id));
    }

    /**
     * How a task's talk log names a person's line with this Matrix event id: the file the transport
     * wrote for it, whose time part this side cannot know.
     */
    static String messageId(final String event) {
        return "--matrix-person-"
                + UUID.nameUUIDFromBytes(event.getBytes(StandardCharsets.UTF_8))
                + ".json";
    }

    /**
     * The first line of {@code task} after {@code line} that carries the nonce, if it came in time.
     */
    private @Nullable JsonNode awaitAnswer(
            final String in, final String line, final Task task, final String nonce)
            throws Failure {
        final long deadline = clock.getAsLong() + answerWithin.toNanos();
        while (true) {
            final JsonNode answer = answer(room.messages(in), line, task, nonce);
            if (answer != null || clock.getAsLong() - deadline >= 0) {
                return answer;
            }
            pause.run();
        }
    }

    private static @Nullable JsonNode answer(
            final List<JsonNode> messages, final String line, final Task task, final String nonce) {
        boolean after = false;
        for (final JsonNode event : messages) {
            if (after
                    && event.path("sender").asText().equals(task.account())
                    && event.path("content").path("body").asText("").contains(nonce)) {
                return event;
            }
            after |= event.path("event_id").asText("").equals(line);
        }
        return null;
    }

    /** The other tasks that answered the line within the quiet time, read at its end. */
    private List<String> answeredWithin(
            final String in, final String line, final Task asked, final String nonce)
            throws Failure {
        waitOut(quietFor);
        final List<JsonNode> messages = room.messages(in);
        return tasks.stream()
                .filter(task -> task != asked)
                .filter(task -> answer(messages, line, task, nonce) != null)
                .map(Task::display)
                .toList();
    }

    private void waitOut(final Duration window) {
        final long end = clock.getAsLong() + window.toNanos();
        while (clock.getAsLong() - end < 0) {
            pause.run();
        }
    }

    private static String nonce() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private static String seconds(final Duration duration) {
        return duration.toSeconds() + " s";
    }

    private static void sleep() {
        try {
            Thread.sleep(Duration.ofNanos(POLL_NANOS));
        } catch (final InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while waiting for an answer", ex);
        }
    }

    /** {@code talk log} as a program on the host: the command given, the task's name appended. */
    static TalkLog command(final List<String> prefix, final Duration limit) {
        return task -> {
            final List<String> argv = new ArrayList<>(prefix);
            argv.add(task);
            Path said = null;
            try {
                said = Files.createTempFile("talk-log", ".txt");
                final Process process =
                        new ProcessBuilder(argv)
                                .redirectErrorStream(true)
                                .redirectOutput(said.toFile())
                                .redirectInput(
                                        ProcessBuilder.Redirect.from(new java.io.File("/dev/null")))
                                .start();
                if (!process.waitFor(limit.toMillis(), TimeUnit.MILLISECONDS)) {
                    process.destroyForcibly();
                    throw new Failure(
                            Exit.TEMPORARY,
                            "talk log of " + task + " did not end within " + seconds(limit));
                }
                if (Files.size(said) > LONGEST_LOG) {
                    throw new Failure(
                            Exit.DATA,
                            "talk log of " + task + " is longer than " + LONGEST_LOG + " bytes");
                }
                final String text = Files.readString(said, StandardCharsets.UTF_8);
                if (process.exitValue() != 0) {
                    throw new Failure(
                            Exit.TEMPORARY,
                            "talk log of "
                                    + task
                                    + " exited "
                                    + process.exitValue()
                                    + ": "
                                    + text.strip());
                }
                return text;
            } catch (final IOException ex) {
                throw new Failure(
                        Exit.TEMPORARY, "talk log of " + task + ": " + ex.getMessage(), ex);
            } catch (final InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new Failure(Exit.TEMPORARY, "interrupted during talk log of " + task, ex);
            } finally {
                if (said != null) {
                    try {
                        Files.deleteIfExists(said);
                    } catch (final IOException ex) {
                        // A file of this run's own in the temporary directory; nothing else depends
                        // on it.
                    }
                }
            }
        };
    }

    /** The command line, read whole before anything is asked of the host. */
    record Arguments(
            Path socket,
            String room,
            List<Task> tasks,
            List<String> talkLog,
            Duration answerWithin,
            Duration quietFor) {

        static Arguments parse(final String[] args) throws Failure {
            Path socket = null;
            String room = null;
            final List<Task> tasks = new ArrayList<>();
            final List<String> talkLog = new ArrayList<>();
            long answer = 300;
            long quiet = 60;
            for (int i = 0; i < args.length; i++) {
                switch (args[i]) {
                    case "--socket" -> socket = Path.of(value(args, ++i));
                    case "--room" -> room = value(args, ++i);
                    case "--task" -> tasks.add(Task.parse(value(args, ++i)));
                    case "--answer-within" -> answer = bounded(value(args, ++i));
                    case "--quiet-for" -> quiet = bounded(value(args, ++i));
                    case "--talk-log" -> {
                        talkLog.addAll(List.of(args).subList(i + 1, args.length));
                        i = args.length;
                    }
                    default -> throw new Failure(Exit.USAGE, "unknown argument " + args[i]);
                }
            }
            if (socket == null
                    || room == null
                    || !room.startsWith("!")
                    || tasks.size() < 2
                    || talkLog.isEmpty()) {
                throw new Failure(
                        Exit.USAGE,
                        "usage: RoomRehearsal --socket <path> --room <!room-id> [--answer-within <s>]"
                                + " [--quiet-for <s>] --task <task>=<@account>=<display> (two or more)"
                                + " --talk-log <command...>");
            }
            return new Arguments(
                    socket,
                    room,
                    tasks,
                    talkLog,
                    Duration.ofSeconds(answer),
                    Duration.ofSeconds(quiet));
        }

        private static String value(final String[] args, final int i) throws Failure {
            if (i >= args.length) {
                throw new Failure(Exit.USAGE, args[i - 1] + " takes a value");
            }
            return args[i];
        }

        private static long bounded(final String text) throws Failure {
            try {
                final long seconds = Long.parseLong(text);
                if (seconds >= 0 && seconds <= RehearsalClient.LONGEST_WAIT_SECONDS) {
                    return seconds;
                }
            } catch (final NumberFormatException ex) {
                // Said below, with the bound.
            }
            throw new Failure(
                    Exit.USAGE, "seconds must be 0 to " + RehearsalClient.LONGEST_WAIT_SECONDS);
        }
    }
}
