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
import java.util.Map;
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
 * [--answer-within <s>] [--quiet-for <s>] --task <task>=<@account>=<display> ... [--person
 * <@account> ...] --talk-log <command...>}; the task's name is appended to the {@code talk log}
 * command, which runs on the host as the person (for instance {@code ssh <person>@<host> porelo
 * talk log}). {@code --person} names a further person of the room, whose lines and membership are
 * no stranger's; with {@code --project}, {@code --person-login <login>} names one by the login
 * alone, as that login's account on the signed-in person's server, with that login's helpers. One
 * line per scenario, {@code OK} or {@code FAIL}, with what was asked and what came back; exit 0
 * only when every scenario held.
 */
final class RoomRehearsal {

    /** What the rehearsal does through the person's client. */
    interface Room {

        /** Posts {@code text} in {@code room}, each of {@code mentions} marked; its event id. */
        String say(String room, String text, List<String> mentions) throws Failure;

        /** Opens an unencrypted direct chat with {@code user} and posts {@code text} there. */
        Posted direct(String user, String text) throws Failure;

        /**
         * The room's text messages, oldest first, read back until {@code back} is among them, or to
         * the room's start when it is null.
         */
        List<JsonNode> messages(String room, @Nullable String back) throws Failure;

        /** The accounts joined to the room or invited to it. */
        Set<String> members(String room) throws Failure;

        /** {@code text} with nothing of the person's session in it, as the run may print it. */
        default String scrub(final String text) {
            return text;
        }

        /** Leaves the room, as a person closes a chat. */
        default void leave(final String room) throws Failure {}
    }

    /** A line posted, and the room it went to. */
    record Posted(String room, String event) {}

    /** Waits between two reads of the room. */
    @FunctionalInterface
    interface Pause {
        void run() throws Failure;
    }

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
                    || !DISPLAY.matcher(parts[2]).matches()) {
                throw new Failure(
                        Exit.USAGE,
                        "--task takes <task>=<@account>=<display>, the task named by"
                                + " [a-z0-9][a-z0-9._-]*, the display as the room shows a task"
                                + " (Agent <Name> or Agent <Name> (<short>))");
            }
            return new Task(parts[0], parts[1], parts[2]);
        }
    }

    /** A task's display as porelo reads it in a head, so a line naming it names it alone. */
    private static final Pattern DISPLAY =
            Pattern.compile(
                    "Agent [\\p{L}\\p{N}_]+(?:-[\\p{L}\\p{N}_]+)*(?: \\([\\p{L}\\p{N}_.-]+\\))?");

    /** A project's name, as the gate's rules read one. */
    private static final Pattern PROJECT = Pattern.compile("[a-z0-9][a-z0-9._=-]{0,120}");

    private static final Pattern TASK_NAME = Pattern.compile("[a-z0-9][a-z0-9._-]{0,127}");

    private static final Pattern ACCOUNT =
            Pattern.compile("@[a-z0-9._=/+-]+:[A-Za-z0-9.:\\[\\]-]+");

    private static final long LONGEST_LOG = 64L << 20;

    private static final long POLL_NANOS = TimeUnit.SECONDS.toNanos(2);

    /** A Matrix event id, or a talk log's name for one: what differs between two runs of a step. */
    private static final Pattern EVENT =
            Pattern.compile("\\$[A-Za-z0-9._~+/=:-]+|--matrix-person-[0-9a-f-]{36}\\.json");

    private final Room room;
    private final String roomId;
    private final List<Task> tasks;
    private final Set<String> persons;
    private final TalkLog talkLog;
    private final Duration answerWithin;
    private final Duration quietFor;
    private final LongSupplier clock;
    private final Pause pause;
    private final PrintStream out;
    private @Nullable String lastFailure;
    private @Nullable String firstLine;
    private boolean held = true;

    RoomRehearsal(
            final Room room,
            final String roomId,
            final List<Task> tasks,
            final Set<String> persons,
            final TalkLog talkLog,
            final Duration answerWithin,
            final Duration quietFor,
            final LongSupplier clock,
            final Pause pause,
            final PrintStream out) {
        this.room = room;
        this.roomId = roomId;
        this.tasks = List.copyOf(tasks);
        this.persons = Set.copyOf(persons);
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
                try {
                    return rehearse(client, given, out);
                } catch (final Failure ex) {
                    err.println(
                            "FAILED: " + client.scrub(ex.getMessage()).replaceAll("\\p{Cc}+", " "));
                    return ex.exitCode();
                }
            }
        } catch (final Failure ex) {
            err.println("FAILED: " + String.valueOf(ex.getMessage()).replaceAll("\\p{Cc}+", " "));
            return ex.exitCode();
        }
    }

    private static int rehearse(
            final RehearsalClient client, final Arguments given, final PrintStream out)
            throws Failure {
        client.step("sign-in");
        final String room;
        final List<Task> tasks;
        final String project = given.project();
        Set<String> persons = given.persons();
        if (project == null) {
            room = java.util.Objects.requireNonNull(given.room());
            tasks = given.tasks();
        } else {
            final String user = client.user();
            final String alias = "#porelo-" + project + ":" + user.substring(user.indexOf(':') + 1);
            room = client.roomNamed(alias);
            // Joined first: a person only invited may not read who is in the room.
            client.step("join " + room);
            tasks = running(client.shown(room), project, user);
            persons = persons(given, user);
            Arguments.distinct(tasks, persons);
            out.println("ROOM " + room + " (" + alias + ")");
            tasks.forEach(
                    task ->
                            out.println(
                                    "TASK "
                                            + task.name()
                                            + " "
                                            + task.account()
                                            + " "
                                            + task.display()));
        }
        if (project == null) {
            client.step("join " + room);
        }
        final boolean held =
                new RoomRehearsal(
                                client,
                                room,
                                tasks,
                                persons,
                                command(given.talkLog(), Duration.ofSeconds(60)),
                                given.answerWithin(),
                                given.quietFor(),
                                System::nanoTime,
                                RoomRehearsal::sleep,
                                out)
                        .runAll();
        if (Thread.currentThread().isInterrupted()) {
            // close() logs out with the interrupt set aside; a step would fail on it first.
            return Exit.TEMPORARY;
        }
        client.step("log-out");
        return held ? Exit.OK : Exit.DATA;
    }

    /**
     * The project's running tasks of the signed-in person among the room's joined members, by name:
     * each {@code @porelo-<project>-<task>.<login>:<server>} of the person's login and server, as
     * {@code members} reads a task, with the display the room shows. A stopped task has left the
     * room and is not among them.
     *
     * @param joined each joined member and the display the room shows for it
     * @param project the project
     * @param user the signed-in person
     */
    static List<Task> running(
            final Map<String, String> joined, final String project, final String user)
            throws Failure {
        final int colon = user.indexOf(':');
        final String suffix = "." + user.substring(1, colon) + user.substring(colon);
        final String prefix = "@porelo-" + project + "-";
        final List<Task> tasks = new ArrayList<>();
        for (final Map.Entry<String, String> member : new java.util.TreeMap<>(joined).entrySet()) {
            final String id = member.getKey();
            if (!id.startsWith(prefix)
                    || !id.endsWith(suffix)
                    || id.length() <= prefix.length() + suffix.length()) {
                continue;
            }
            final String name = id.substring(prefix.length(), id.length() - suffix.length());
            if (!TASK_NAME.matcher(name).matches() || !ACCOUNT.matcher(id).matches()) {
                continue;
            }
            if (!DISPLAY.matcher(member.getValue()).matches()) {
                throw new Failure(
                        Exit.DATA,
                        "the room shows the task "
                                + name
                                + " as \""
                                + member.getValue().replaceAll("\\p{Cc}+", " ")
                                + "\", which no head can name");
            }
            tasks.add(new Task(name, id, member.getValue()));
        }
        tasks.sort(java.util.Comparator.comparing(Task::name));
        if (tasks.size() < 2) {
            throw new Failure(
                    Exit.DATA,
                    "the room holds "
                            + tasks.size()
                            + " running task"
                            + (tasks.size() == 1 ? "" : "s")
                            + " of "
                            + project
                            + " for "
                            + user
                            + ", the rehearsal needs two: "
                            + tasks.stream().map(Task::name).toList());
        }
        return List.copyOf(tasks);
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
        scenarios.add(
                new Scenario(
                        "a pill alone names one task",
                        nonce -> named(first, ask(nonce), List.of(first.account()), nonce)));
        boolean prefixed = false;
        for (final Task longer : tasks) {
            for (final Task shorter : tasks) {
                if (!longer.equals(shorter) && longer.display().startsWith(shorter.display())) {
                    prefixed = true;
                    for (final Task meant : List.of(longer, shorter)) {
                        scenarios.add(
                                new Scenario(
                                        "a head names "
                                                + meant.display()
                                                + " whole, beside "
                                                + (meant.equals(longer) ? shorter : longer)
                                                        .display(),
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
                        nonce ->
                                every(
                                        first.display() + ", All agents: " + ask(nonce),
                                        List.of(),
                                        nonce)));
        scenarios.add(
                new Scenario(
                        "@room wakes every task",
                        nonce ->
                                every(
                                        first.display() + ": @room " + ask(nonce),
                                        List.of("@room"),
                                        nonce)));
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
        // One line per scenario, and nothing of the session even when the homeserver echoed it.
        failure = room.scrub(failure).replaceAll("\\p{Cc}+", " ");
        out.println("FAIL " + scenario.name() + ": " + failure);
        held = false;
        if (Thread.currentThread().isInterrupted()) {
            out.println("STOP interrupted");
            return false;
        }
        final String kind = EVENT.matcher(failure).replaceAll("<event>");
        final boolean repeated = kind.equals(lastFailure);
        lastFailure = kind;
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
        final String event = say(text, mentions);
        if (awaitAnswer(roomId, event, task, nonce) == null) {
            return "asked "
                    + task.display()
                    + " in the room, got no answer within "
                    + seconds(answerWithin);
        }
        // Every task has the line before the quiet time starts, so a late poll's answer is seen.
        final String lacking = undelivered(event, tasks);
        if (lacking != null) {
            return lacking;
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
        final long answers = answers(room.messages(roomId, event), event, task, nonce);
        return answers == 1
                ? null
                : "asked " + task.display() + " once, it answered " + answers + " times";
    }

    /**
     * Every task answers the line within one answer time, and every task's record shows it
     * delivered.
     */
    private @Nullable String every(
            final String text, final List<String> mentions, final String nonce) throws Failure {
        final String event = say(text, mentions);
        final List<Task> silent = new ArrayList<>(tasks);
        final long deadline = clock.getAsLong() + answerWithin.toNanos();
        List<JsonNode> messages;
        while (true) {
            final List<JsonNode> read = room.messages(roomId, event);
            messages = read;
            silent.removeIf(task -> answer(read, event, task, nonce) != null);
            if (silent.isEmpty() || clock.getAsLong() - deadline >= 0) {
                break;
            }
            pause.run();
        }
        if (!silent.isEmpty()) {
            return "asked every task, got no answer within "
                    + seconds(answerWithin)
                    + " from "
                    + String.join(", ", silent.stream().map(Task::display).toList());
        }
        final String wrong = onceEach(answersTo(messages, event, nonce), tasks);
        return wrong != null ? "asked every task, " + wrong : undelivered(event, tasks);
    }

    /** Null when each of {@code these} answered once and nobody else answered, else who did not. */
    private static @Nullable String onceEach(final List<JsonNode> answers, final List<Task> these) {
        final List<String> twice = new ArrayList<>();
        final List<String> strangers = new ArrayList<>();
        for (final Task task : these) {
            if (answers.stream()
                            .filter(a -> a.path("sender").asText().equals(task.account()))
                            .count()
                    > 1) {
                twice.add(task.display());
            }
        }
        for (final JsonNode answer : answers) {
            final String sender = answer.path("sender").asText();
            if (these.stream().noneMatch(task -> task.account().equals(sender))
                    && !strangers.contains(sender)) {
                strangers.add(sender);
            }
        }
        if (!twice.isEmpty()) {
            return "answered twice by " + String.join(", ", twice);
        }
        return strangers.isEmpty() ? null : "answered by no task asked: " + strangers;
    }

    /** Posts a line in the project's room, the first of them marking where the run began. */
    private String say(final String text, final List<String> mentions) throws Failure {
        final String event = room.say(roomId, text, mentions);
        if (firstLine == null) {
            firstLine = event;
        }
        return event;
    }

    /** {@code task} answers in the direct chat, and only its record has the line. */
    private @Nullable String direct(final Task task, final String nonce) throws Failure {
        final Posted posted =
                room.direct(task.account(), "Only for " + task.display() + ": " + ask(nonce));
        boolean held = false;
        try {
            final String failure = directAnswered(task, nonce, posted);
            held = failure == null;
            return failure;
        } finally {
            // Closed as a person closes it, so no later run's answer goes into this chat; an
            // interrupt would stop the leave before it reached the homeserver.
            final boolean interrupted = Thread.interrupted();
            try {
                room.leave(posted.room());
            } catch (final Failure ex) {
                // A step that failed reports its own failure, not the leave's.
                if (held) {
                    throw ex;
                }
            } finally {
                if (interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
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
        final String wrong =
                onceEach(
                        answersTo(
                                room.messages(posted.room(), posted.event()),
                                posted.event(),
                                nonce),
                        List.of(task));
        if (wrong != null) {
            return "asked " + task.display() + " directly, " + wrong;
        }
        if (firstLine != null) {
            final List<String> aloud =
                    answersTo(room.messages(roomId, firstLine), firstLine, nonce).stream()
                            .map(m -> m.path("sender").asText())
                            .toList();
            if (!aloud.isEmpty()) {
                return "asked "
                        + task.display()
                        + " directly, the room got its answer from "
                        + aloud;
            }
        }
        final String id = messageId(posted.event());
        for (final Task other : tasks) {
            if (!other.equals(task) && delivered(talkLog.of(other.name()), id)) {
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
     * The posted lines in the order they were sent, each once; since the run's first line every
     * sender a task, a person of the room or a relay of a person's login with a local agent's
     * signed line, and every member one of them or a helper of a person's login.
     */
    private @Nullable String readsInOrder(final String nonce) throws Failure {
        final String one = say("order one " + nonce, List.of());
        final String two = room.say(roomId, "order two " + nonce, List.of());
        final String since = firstLine == null ? one : firstLine;
        final List<JsonNode> messages = room.messages(roomId, since);
        final List<String> ids = messages.stream().map(m -> m.path("event_id").asText()).toList();
        if (ids.indexOf(since) < 0
                || ids.indexOf(one) < 0
                || ids.indexOf(two) <= ids.indexOf(one)
                || new java.util.HashSet<>(ids).size() != ids.size()) {
            return "posted " + one + " then " + two + ", read " + ids;
        }
        final String person = messages.get(ids.indexOf(one)).path("sender").asText();
        final String server = person.substring(person.indexOf(':') + 1);
        final Set<String> known = new java.util.HashSet<>(persons);
        known.add(person);
        tasks.forEach(task -> known.add(task.account()));
        final Set<String> logins = new java.util.HashSet<>();
        persons.forEach(id -> logins.add(id.substring(1, id.indexOf(':'))));
        logins.add(person.substring(1, person.indexOf(':')));
        final Set<String> senders = new java.util.TreeSet<>();
        for (final JsonNode line : messages.subList(ids.indexOf(since), messages.size())) {
            final String sender = line.path("sender").asText();
            if (!known.contains(sender)
                    && !(signed(line) && logins.stream().anyMatch(l -> relay(sender, server, l)))) {
                senders.add(sender);
            }
        }
        if (!senders.isEmpty()) {
            return "read lines of accounts no task or person holds: " + senders;
        }
        final Set<String> strangers = new java.util.TreeSet<>(room.members(roomId));
        strangers.removeAll(known);
        strangers.removeIf(id -> logins.stream().anyMatch(l -> helper(id, server, l)));
        return strangers.isEmpty()
                ? null
                : "read members no task, person or helper is: " + strangers;
    }

    /** The room's own accounts of the person's login, as the transport names them. */
    private static boolean helper(final String id, final String server, final String login) {
        return id.equals("@porelo:" + server)
                || id.equals("@porelo." + login + ":" + server)
                || relay(id, server, login);
    }

    /** A line a local agent signed, as the relay writes it into the room. */
    private static boolean signed(final JsonNode line) {
        return !line.path("content").path(Send.MESSAGE_FIELD).asText("").isEmpty()
                && !line.path("content").path(Send.SIGNATURE_FIELD).asText("").isEmpty();
    }

    /** A relay of a login, which writes a local agent's signed line into the room. */
    private static boolean relay(final String id, final String server, final String login) {
        final String suffix = "." + login + ":" + server;
        return id.startsWith("@porelo-relay-")
                && id.endsWith(suffix)
                && id.length() > "@porelo-relay-".length() + suffix.length();
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
            final JsonNode answer = answer(room.messages(in, line), line, task, nonce);
            if (answer != null || clock.getAsLong() - deadline >= 0) {
                return answer;
            }
            pause.run();
        }
    }

    private static @Nullable JsonNode answer(
            final List<JsonNode> messages, final String line, final Task task, final String nonce) {
        return answersTo(messages, line, nonce).stream()
                .filter(event -> event.path("sender").asText().equals(task.account()))
                .findFirst()
                .orElse(null);
    }

    private static long answers(
            final List<JsonNode> messages, final String line, final Task task, final String nonce) {
        return answersTo(messages, line, nonce).stream()
                .filter(event -> event.path("sender").asText().equals(task.account()))
                .count();
    }

    /**
     * The lines after {@code line} that carry the nonce, each a new text of the room's timeline: an
     * edit, a thread's line or a redacted one answers nothing.
     */
    private static List<JsonNode> answersTo(
            final List<JsonNode> messages, final String line, final String nonce) {
        final List<JsonNode> answers = new ArrayList<>();
        boolean after = false;
        for (final JsonNode event : messages) {
            if (after && RehearsalClient.plainText(event)) {
                if (event.path("content").path("body").asText("").contains(nonce)) {
                    answers.add(event);
                }
            }
            after |= event.path("event_id").asText("").equals(line);
        }
        return answers;
    }

    /** The other tasks that answered the line within the quiet time, read at its end. */
    private List<String> answeredWithin(
            final String in, final String line, final Task asked, final String nonce)
            throws Failure {
        waitOut(quietFor);
        final List<String> others = new ArrayList<>();
        for (final JsonNode answer : answersTo(room.messages(in, line), line, nonce)) {
            final String sender = answer.path("sender").asText();
            final String shown =
                    tasks.stream()
                            .filter(task -> task.account().equals(sender))
                            .map(Task::display)
                            .findFirst()
                            .orElse(sender);
            if (!sender.equals(asked.account()) && !others.contains(shown)) {
                others.add(shown);
            }
        }
        return others;
    }

    private void waitOut(final Duration window) throws Failure {
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

    static void sleep() throws Failure {
        try {
            Thread.sleep(Duration.ofNanos(POLL_NANOS));
        } catch (final InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new Failure(Exit.TEMPORARY, "interrupted while waiting for an answer", ex);
        }
    }

    /** {@code talk log} as a program on the host: the command given, the task's name appended. */
    static TalkLog command(final List<String> prefix, final Duration limit) {
        return task -> {
            final List<String> argv = new ArrayList<>(prefix);
            argv.add(task);
            Path said = null;
            Process process = null;
            try {
                said = Files.createTempFile("talk-log", ".txt");
                process =
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
                if (process != null) {
                    process.destroyForcibly();
                }
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

    /**
     * The further persons of the room: each {@code --person}, and each {@code --person-login} as
     * that login's account on the signed-in person's server, which a project's run learns only at
     * the sign-in.
     *
     * @param given the command line
     * @param user the signed-in person
     */
    static Set<String> persons(final Arguments given, final String user) {
        final String server = user.substring(user.indexOf(':'));
        final Set<String> persons = new java.util.LinkedHashSet<>(given.persons());
        given.personLogins().forEach(login -> persons.add("@" + login + server));
        persons.remove(user);
        return Set.copyOf(persons);
    }

    /** The command line, read whole before anything is asked of the host. */
    record Arguments(
            Path socket,
            @Nullable String room,
            @Nullable String project,
            List<Task> tasks,
            Set<String> persons,
            Set<String> personLogins,
            List<String> talkLog,
            Duration answerWithin,
            Duration quietFor) {

        static Arguments parse(final String[] args) throws Failure {
            Path socket = null;
            String room = null;
            String project = null;
            final List<Task> tasks = new ArrayList<>();
            final Set<String> persons = new java.util.LinkedHashSet<>();
            final Set<String> personLogins = new java.util.LinkedHashSet<>();
            final List<String> talkLog = new ArrayList<>();
            long answer = 300;
            long quiet = 60;
            boolean answerGiven = false;
            boolean quietGiven = false;
            for (int i = 0; i < args.length; i++) {
                switch (args[i]) {
                    case "--socket" -> socket = Path.of(once(socket, args, ++i));
                    case "--room" -> room = once(room, args, ++i);
                    case "--project" -> project = once(project, args, ++i);
                    case "--task" -> tasks.add(Task.parse(value(args, ++i)));
                    case "--person" -> {
                        final String person = value(args, ++i);
                        if (!ACCOUNT.matcher(person).matches()
                                || !Login.valid(person.substring(1, person.indexOf(':')))
                                || !persons.add(person)) {
                            throw new Failure(
                                    Exit.USAGE, "--person takes an @account, each once: " + person);
                        }
                    }
                    case "--person-login" -> {
                        final String login = value(args, ++i);
                        if (!Login.valid(login) || !personLogins.add(login)) {
                            throw new Failure(
                                    Exit.USAGE,
                                    "--person-login takes a login, each once: " + login);
                        }
                    }
                    case "--answer-within" -> {
                        answer = bounded(once(answerGiven ? "" : null, args, ++i));
                        answerGiven = true;
                    }
                    case "--quiet-for" -> {
                        quiet = bounded(once(quietGiven ? "" : null, args, ++i));
                        quietGiven = true;
                    }
                    case "--talk-log" -> {
                        talkLog.addAll(List.of(args).subList(i + 1, args.length));
                        i = args.length;
                    }
                    default -> throw new Failure(Exit.USAGE, "unknown argument " + args[i]);
                }
            }
            final boolean byHand =
                    project == null
                            && room != null
                            && room.startsWith("!")
                            && tasks.size() >= 2
                            && personLogins.isEmpty();
            final boolean byProject =
                    project != null
                            && PROJECT.matcher(project).matches()
                            && room == null
                            && tasks.isEmpty();
            if (socket == null || !(byHand || byProject) || talkLog.isEmpty()) {
                throw new Failure(
                        Exit.USAGE,
                        "usage: RoomRehearsal --socket <path> (--room <!room-id> --task"
                                + " <task>=<@account>=<display> (two or more) | --project <project>)"
                                + " [--answer-within <s>] [--quiet-for <s>] [--person <@account>...]"
                                + " [--person-login <login>...] (with --project)"
                                + " --talk-log <command...>");
            }
            if (byHand) {
                distinct(tasks, persons);
            }
            return new Arguments(
                    socket,
                    room,
                    project,
                    tasks,
                    persons,
                    personLogins,
                    talkLog,
                    Duration.ofSeconds(answer),
                    Duration.ofSeconds(quiet));
        }

        /**
         * No task named twice by name, account or display, no person who is a task, and every
         * account on one server.
         */
        static void distinct(final List<Task> tasks, final Set<String> persons) throws Failure {
            final Set<String> servers = new java.util.HashSet<>();
            tasks.forEach(
                    task -> servers.add(task.account().substring(task.account().indexOf(':'))));
            persons.forEach(person -> servers.add(person.substring(person.indexOf(':'))));
            if (servers.size() != 1) {
                throw new Failure(Exit.USAGE, "--task and --person name accounts of one server");
            }
            final Set<String> names = new java.util.HashSet<>();
            final Set<String> accounts = new java.util.HashSet<>(persons);
            final Set<String> displays = new java.util.HashSet<>();
            for (final Task task : tasks) {
                if (!names.add(task.name())
                        || !accounts.add(task.account())
                        || !displays.add(task.display())) {
                    throw new Failure(
                            Exit.USAGE,
                            "--task names "
                                    + task.name()
                                    + " a second time, by its name, account or display, or"
                                    + " as a --person");
                }
            }
        }

        /** The option's value, refused when the option was given before. */
        private static String once(final @Nullable Object before, final String[] args, final int i)
                throws Failure {
            if (before != null) {
                throw new Failure(Exit.USAGE, args[i - 1] + " is given once");
            }
            return value(args, i);
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
