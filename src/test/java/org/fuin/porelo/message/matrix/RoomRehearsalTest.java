package org.fuin.porelo.message.matrix;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * A room scenario of the rehearsal says OK only when the tasks' records and answers bear it out.
 */
class RoomRehearsalTest {

    private static final String PERSON = "@bob:x";
    private static final RoomRehearsal.Task PI =
            new RoomRehearsal.Task("pi", "@pi.bob:x", "Agent Pi");
    private static final RoomRehearsal.Task PI_TWO =
            new RoomRehearsal.Task("pi-two", "@pi-two.bob:x", "Agent Pi-Two");
    private static final RoomRehearsal.Task CORE =
            new RoomRehearsal.Task("core", "@core.bob:x", "Agent Core");
    private static final List<RoomRehearsal.Task> TASKS = List.of(PI, PI_TWO, CORE);
    private static final Pattern ASKED = Pattern.compile("answer with (\\S+)");

    /** What a line is: its text, its marks, and to whom it went directly, if to anyone. */
    private record Line(String text, List<String> mentions, String directTo) {}

    /** Which tasks answer a line; by default those porelo wakes for it. */
    @FunctionalInterface
    private interface Answering {
        List<RoomRehearsal.Task> answer(Line line);
    }

    /** Which tasks' records get a line; by default every task, or the one a direct chat is with. */
    @FunctionalInterface
    private interface Delivering {
        boolean reaches(RoomRehearsal.Task task, Line line);
    }

    /**
     * A room whose tasks answer as {@code answering} says, after {@code lag} reads of the room,
     * with the nonce the line asked for; each task's record holds what {@code delivering} lets
     * reach it.
     */
    private static class FakeRoom implements RoomRehearsal.Room {

        final Map<String, List<JsonNode>> rooms = new HashMap<>();
        final Map<String, Set<String>> logs = new HashMap<>();
        final Set<String> members = new HashSet<>(Set.of(PERSON));
        Answering answering = FakeRoom::woken;
        Delivering delivering =
                (task, line) -> line.directTo().isEmpty() || line.directTo().equals(task.account());
        int lag;
        private final List<Runnable> pending = new ArrayList<>();
        private int reads;
        private int next;

        FakeRoom() {
            TASKS.forEach(task -> members.add(task.account()));
        }

        /** The tasks porelo wakes for a line. */
        static List<RoomRehearsal.Task> woken(final Line line) {
            if (!line.directTo().isEmpty()) {
                return TASKS.stream().filter(t -> t.account().equals(line.directTo())).toList();
            }
            if (line.text().startsWith("order ")) {
                return List.of();
            }
            final String head =
                    line.text().contains(":")
                            ? line.text().substring(0, line.text().indexOf(':'))
                            : "";
            if (line.mentions().contains("@room") || head.contains("All agents")) {
                return TASKS;
            }
            final List<RoomRehearsal.Task> pilled =
                    TASKS.stream().filter(t -> line.mentions().contains(t.account())).toList();
            if (!pilled.isEmpty()) {
                return pilled;
            }
            final List<RoomRehearsal.Task> named =
                    TASKS.stream()
                            .filter(task -> List.of(head.split(", ")).contains(task.display()))
                            .toList();
            return named.isEmpty() ? TASKS : named;
        }

        @Override
        public String say(final String room, final String text, final List<String> mentions) {
            return line(room, new Line(text, mentions, ""));
        }

        final List<String> left = new ArrayList<>();

        @Override
        public void leave(final String room) throws Failure {
            left.add(room);
        }

        @Override
        public RoomRehearsal.Posted direct(final String user, final String text) {
            return new RoomRehearsal.Posted(
                    "!dm:x", line("!dm:x", new Line(text, List.of(), user)));
        }

        private String line(final String room, final Line line) {
            final String event = add(room, PERSON, line.text());
            for (final RoomRehearsal.Task task : TASKS) {
                if (delivering.reaches(task, line)) {
                    logs.computeIfAbsent(task.name(), n -> new HashSet<>())
                            .add(RoomRehearsal.messageId(event));
                }
            }
            final Matcher asked = ASKED.matcher(line.text());
            final String nonce = asked.find() ? asked.group(1) : "";
            for (final RoomRehearsal.Task task : answering.answer(line)) {
                pending.add(() -> add(room, task.account(), "done " + nonce));
            }
            return event;
        }

        String add(final String room, final String sender, final String text) {
            final String event = "$e" + next++;
            rooms.computeIfAbsent(room, r -> new ArrayList<>())
                    .add(
                            MatrixClient.JSON
                                    .createObjectNode()
                                    .put("type", "m.room.message")
                                    .put("event_id", event)
                                    .put("sender", sender)
                                    .set(
                                            "content",
                                            MatrixClient.JSON
                                                    .createObjectNode()
                                                    .put("msgtype", "m.text")
                                                    .put("body", text)));
            return event;
        }

        @Override
        public List<JsonNode> messages(final String room, final String back) {
            if (++reads > lag) {
                pending.forEach(Runnable::run);
                pending.clear();
                reads = 0;
            }
            return List.copyOf(rooms.getOrDefault(room, List.of()));
        }

        @Override
        public Set<String> members(final String room) {
            return Set.copyOf(members);
        }

        String talkLog(final String task) {
            final StringBuilder log = new StringBuilder("AT EVENT PEER MESSAGE DETAIL\n");
            for (final String id : logs.getOrDefault(task, Set.of())) {
                log.append("2026-11-09T08:00:00Z delivered bob 20261109T080000000")
                        .append(id)
                        .append(" -\n");
            }
            return log.toString();
        }
    }

    private record Outcome(boolean held, String said) {}

    private static Outcome rehearse(final FakeRoom room) {
        return rehearse(room, TASKS);
    }

    private static Outcome rehearse(final FakeRoom room, final List<RoomRehearsal.Task> tasks) {
        return rehearse(room, tasks, Set.of());
    }

    private static Outcome rehearse(
            final FakeRoom room, final List<RoomRehearsal.Task> tasks, final Set<String> persons) {
        final long[] now = {0};
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        final boolean held =
                new RoomRehearsal(
                                room,
                                "!room:x",
                                tasks,
                                persons,
                                room::talkLog,
                                Duration.ofSeconds(10),
                                Duration.ofSeconds(4),
                                () -> now[0],
                                () -> now[0] += Duration.ofSeconds(2).toNanos(),
                                new PrintStream(out, true, StandardCharsets.UTF_8))
                        .runAll();
        return new Outcome(held, out.toString(StandardCharsets.UTF_8));
    }

    /** Harm guarded: a rehearsal that fails a room that behaves, so a real fault hides in noise. */
    @Test
    void aRoomThatBehavesHoldsEveryScenario() {
        final FakeRoom room = new FakeRoom();
        room.lag = 2;

        final Outcome outcome = rehearse(room);

        assertThat(outcome.held()).as("the run, said: " + outcome.said()).isTrue();
        assertThat(outcome.said().lines())
                .as("one OK line per scenario")
                .hasSize(10)
                .allMatch(line -> line.startsWith("OK   "));
        assertThat(outcome.said())
                .as("each display named whole beside the other")
                .contains("OK   a head names Agent Pi-Two whole, beside Agent Pi")
                .contains("OK   a head names Agent Pi whole, beside Agent Pi-Two");
    }

    /** Harm guarded: a line meant for one task reported as such while others take it too. */
    @Test
    void aNamedLineOtherTasksAnswerFailsAndARepeatStops() {
        final FakeRoom room = new FakeRoom();
        room.answering = line -> TASKS;

        final Outcome outcome = rehearse(room);

        assertThat(outcome.held()).as("the run").isFalse();
        assertThat(outcome.said())
                .as("who answered beside the task named")
                .contains(
                        "FAIL a head names one task: asked Agent Pi alone, got answers within 4 s"
                                + " from Agent Pi-Two, Agent Core");
        assertThat(outcome.said().strip())
                .as("nothing run after the stop")
                .endsWith("STOP the same failure twice in a row");
    }

    /** Harm guarded: a line reported as reaching its task when the task never answered. */
    @Test
    void aNamedTaskThatStaysSilentFails() {
        final FakeRoom room = new FakeRoom();
        room.answering = line -> List.of();

        final Outcome outcome = rehearse(room);

        assertThat(outcome.said())
                .as("the silent task")
                .contains(
                        "FAIL a head names one task: asked Agent Pi in the room, got no answer"
                                + " within 10 s");
    }

    /** Harm guarded: an answer to an earlier line taken for the answer to this one. */
    @Test
    void anAnswerWithoutTheLinesNonceDoesNotCount() {
        final FakeRoom room = new FakeRoom();
        room.answering =
                line -> {
                    if (line.text().startsWith("Agent Pi:")) {
                        room.add("!room:x", PI.account(), "done with the earlier one");
                        return List.of();
                    }
                    return FakeRoom.woken(line);
                };

        final Outcome outcome = rehearse(room);

        assertThat(outcome.said())
                .as("a late answer without the nonce")
                .contains("FAIL a head names one task: asked Agent Pi in the room, got no answer");
    }

    /** Harm guarded: a line reported as reaching every task when one task's record lacks it. */
    @Test
    void aLineATasksRecordLacksFails() {
        final FakeRoom room = new FakeRoom();
        room.delivering = (task, line) -> line.directTo().isEmpty() ? task != CORE : task == PI;

        final Outcome outcome = rehearse(room);

        assertThat(outcome.held()).as("the run").isFalse();
        assertThat(outcome.said())
                .as("the task whose record lacks the line")
                .containsPattern(
                        "FAIL a head names one task: posted --matrix-person-[0-9a-f-]{36}\\.json, talk log lacks it"
                                + " delivered within 10 s in core");
    }

    /** Harm guarded: a line for all reported as waking every task when one stays silent. */
    @Test
    void aTaskSilentOnALineForAllFails() {
        final FakeRoom room = new FakeRoom();
        room.answering = line -> FakeRoom.woken(line).stream().filter(t -> t != CORE).toList();

        final Outcome outcome = rehearse(room);

        assertThat(outcome.said())
                .as("the silent task")
                .contains(
                        "FAIL All agents: wakes every task: asked every task, got no answer within"
                                + " 10 s from Agent Core");
    }

    /** Harm guarded: a direct chat reported as private when it reached another task. */
    @Test
    void aDirectLineInAnotherTasksRecordFails() {
        final FakeRoom room = new FakeRoom();
        room.delivering = (task, line) -> true;

        final Outcome outcome = rehearse(room);

        assertThat(outcome.said())
                .as("the task that should not have it")
                .containsPattern(
                        "FAIL a direct chat reaches one task: asked Agent Pi directly, talk log of"
                                + " pi-two has --matrix-person-[0-9a-f-]{36}\\.json");
    }

    /**
     * Harm guarded: each run leaves a direct chat open behind it, so a later run's task answers
     * into one of those and the step fails for the run before.
     */
    @Test
    void theDirectChatIsLeftAfterItsStep() {
        final FakeRoom room = new FakeRoom();

        rehearse(room);

        assertThat(room.left).as("the chat the step opened").containsExactly("!dm:x");
    }

    /** Harm guarded: a direct chat reported as reaching its task when its record lacks it. */
    @Test
    void aDirectLineItsTasksRecordLacksFails() {
        final FakeRoom room = new FakeRoom();
        room.delivering = (task, line) -> line.directTo().isEmpty();

        final Outcome outcome = rehearse(room);

        assertThat(outcome.said())
                .as("the task's own record")
                .containsPattern(
                        "FAIL a direct chat reaches one task: posted --matrix-person-[0-9a-f-]{36}\\.json, talk log lacks"
                                + " it delivered within 10 s in pi");
    }

    /**
     * Harm guarded: a room that works is reported failed for the helpers that make it work: the
     * account that made it, the login's, which invites, and the relay, which reads it.
     */
    @Test
    void theRoomsHelpersAmongTheMembersHold() {
        final FakeRoom room = new FakeRoom();
        room.members.addAll(Set.of("@porelo:x", "@porelo.bob:x", "@porelo-relay-demo.bob:x"));

        final Outcome outcome = rehearse(room);

        assertThat(outcome.said()).as("the helpers").contains("OK   the room reads in order");
    }

    /** Harm guarded: an account joined to the room unnoticed, though it said nothing. */
    @Test
    void aSilentStrangerAmongTheMembersFails() {
        final FakeRoom room = new FakeRoom();
        room.members.addAll(Set.of("@eve:x", "@porelo-relay-demo.bob:elsewhere"));

        final Outcome outcome = rehearse(room);

        assertThat(outcome.said())
                .as("the strangers, a helper's name on another server among them")
                .contains(
                        "FAIL the room reads in order, with no stranger: read members no task,"
                                + " person or helper is: [@eve:x, @porelo-relay-demo.bob:elsewhere]");
    }

    /**
     * Harm guarded: a helper that writes into the room unnoticed, where only persons, tasks and the
     * relay answering for a local agent do.
     */
    @Test
    void aHelpersLineSinceTheRunBeganFails() {
        final FakeRoom room = new FakeRoom();
        room.members.add("@porelo.bob:x");
        room.answering =
                line -> {
                    if (line.text().startsWith("order one")) {
                        room.add("!room:x", "@porelo.bob:x", "a line");
                    }
                    return FakeRoom.woken(line);
                };

        final Outcome outcome = rehearse(room);

        assertThat(outcome.said())
                .as("the helper's line")
                .contains(
                        "FAIL the room reads in order, with no stranger: read lines of accounts no"
                                + " task or person holds: [@porelo.bob:x]");
    }

    /**
     * Harm guarded: a busy room failed for what it held before the run, for its other persons, or
     * for the login's relay writing a local agent's signed line.
     */
    @Test
    void aBusyRoomHolds() {
        final FakeRoom room = new FakeRoom();
        room.add("!room:x", "@retired.bob:x", "a retired task's old line");
        room.add("!room:x", "@ada:x", "an earlier line of another person");
        room.members.addAll(Set.of("@ada:x", "@porelo-relay-demo.bob:x"));
        room.answering =
                line -> {
                    if (line.text().startsWith("order one")) {
                        room.add("!room:x", "@ada:x", "a line of hers meanwhile");
                        room.rooms
                                .get("!room:x")
                                .add(
                                        MatrixClient.JSON
                                                .createObjectNode()
                                                .put("type", "m.room.message")
                                                .put("event_id", "$signed")
                                                .put("sender", "@porelo-relay-demo.bob:x")
                                                .set(
                                                        "content",
                                                        MatrixClient.JSON
                                                                .createObjectNode()
                                                                .put("msgtype", "m.text")
                                                                .put("body", "a local agent's line")
                                                                .put(Send.MESSAGE_FIELD, "{}")
                                                                .put(Send.SIGNATURE_FIELD, "s")));
                    }
                    return FakeRoom.woken(line);
                };

        final Outcome outcome = rehearse(room, TASKS, Set.of("@ada:x"));

        assertThat(outcome.held()).as("the run, said: " + outcome.said()).isTrue();
    }

    /** Harm guarded: another person's line in the room passed without being named. */
    @Test
    void aPersonNotNamedWhoWritesDuringTheRunFails() {
        final FakeRoom room = new FakeRoom();
        room.members.add("@ada:x");
        room.answering =
                line -> {
                    if (line.text().startsWith("order one")) {
                        room.add("!room:x", "@ada:x", "a line of hers");
                    }
                    return FakeRoom.woken(line);
                };

        final Outcome outcome = rehearse(room);

        assertThat(outcome.said())
                .as("the person not named")
                .contains("read lines of accounts no task or person holds: [@ada:x]");
    }

    /** Harm guarded: another login's helper in the room passed as one of the room's own. */
    @Test
    void anotherLoginsHelperIsAStranger() {
        final FakeRoom room = new FakeRoom();
        room.members.addAll(
                Set.of("@porelo.eve:x", "@porelo-relay-demo.eve:x", "@porelo-relay-.bob:x"));

        final Outcome outcome = rehearse(room);

        assertThat(outcome.said())
                .as("the other login's helpers")
                .contains(
                        "read members no task, person or helper is: [@porelo-relay-.bob:x,"
                                + " @porelo-relay-demo.eve:x, @porelo.eve:x]");
    }

    /** Harm guarded: a pill dropped on the way, so the line wakes every task, reported as held. */
    @Test
    void aPillThatWakesEveryTaskFails() {
        final FakeRoom room = new FakeRoom();
        room.answering = line -> FakeRoom.woken(new Line(line.text(), List.of(), line.directTo()));

        final Outcome outcome = rehearse(room);

        assertThat(outcome.said())
                .as("the pill's line")
                .contains(
                        "FAIL a pill alone names one task: asked Agent Pi alone, got answers"
                                + " within 4 s from Agent Pi-Two, Agent Core");
    }

    /** Harm guarded: a line read back twice taken for lines in order. */
    @Test
    void aLineReadTwiceFails() {
        final FakeRoom room =
                new FakeRoom() {
                    @Override
                    public List<JsonNode> messages(final String in, final String back) {
                        final List<JsonNode> read = new ArrayList<>(super.messages(in, back));
                        if (!read.isEmpty()
                                && read.getLast()
                                        .path("content")
                                        .path("body")
                                        .asText()
                                        .startsWith("order two")) {
                            read.add(read.getLast());
                        }
                        return read;
                    }
                };

        final Outcome outcome = rehearse(room);

        assertThat(outcome.said())
                .as("the line read twice")
                .containsPattern(
                        "FAIL the room reads in order, with no stranger: posted \\$e\\d+ then");
    }

    /**
     * Harm guarded: a run that fails one way for every line goes on to the end, since each failure
     * names its own event.
     */
    @Test
    void theSameFailureForAnotherLineStopsTheRun() {
        final FakeRoom room = new FakeRoom();
        room.delivering = (task, line) -> task != CORE;

        final Outcome outcome = rehearse(room);

        assertThat(outcome.said().lines().filter(l -> l.startsWith("FAIL ")))
                .as("one failure, then its repeat")
                .hasSize(2);
        assertThat(outcome.said().strip()).endsWith("STOP the same failure twice in a row");
    }

    /** Harm guarded: a line for every task waiting once per task, so a run takes hours. */
    @Test
    void aLineForEveryTaskWaitsOnce() {
        final FakeRoom room = new FakeRoom();
        final long[] now = {0};
        final long[] asked = {-1};
        room.answering =
                line -> {
                    final boolean forAll =
                            line.text().contains("All agents")
                                    || line.mentions().contains("@room")
                                    || (line.text().startsWith("answer with")
                                            && line.mentions().isEmpty());
                    if (forAll && asked[0] < 0) {
                        asked[0] = now[0];
                    }
                    return forAll ? List.of() : FakeRoom.woken(line);
                };

        new RoomRehearsal(
                        room,
                        "!room:x",
                        TASKS,
                        Set.of(),
                        room::talkLog,
                        Duration.ofSeconds(10),
                        Duration.ofSeconds(4),
                        () -> now[0],
                        () -> now[0] += Duration.ofSeconds(2).toNanos(),
                        new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8))
                .runAll();

        assertThat(Duration.ofNanos(now[0] - asked[0]))
                .as("two lines for every task waited, then stopped: one answer time each")
                .isLessThanOrEqualTo(Duration.ofSeconds(24));
    }

    /** Harm guarded: the chat a direct step opened left open when its step fails. */
    @Test
    void theDirectChatIsLeftWhenItsStepFails() {
        final FakeRoom room = new FakeRoom();
        room.delivering = (task, line) -> line.directTo().isEmpty();

        rehearse(room);

        assertThat(room.left).as("the chat the failed step opened").containsExactly("!dm:x");
    }

    /** Harm guarded: the chat a direct step opened left open when its step throws. */
    @Test
    void theDirectChatIsLeftWhenItsStepThrows() {
        final FakeRoom room = new FakeRoom();
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        final long[] now = {0};

        new RoomRehearsal(
                        room,
                        "!room:x",
                        TASKS,
                        Set.of(),
                        task -> {
                            if (room.rooms.containsKey("!dm:x")) {
                                throw new Failure(Exit.TEMPORARY, "talk log of " + task + " down");
                            }
                            return room.talkLog(task);
                        },
                        Duration.ofSeconds(10),
                        Duration.ofSeconds(4),
                        () -> now[0],
                        () -> now[0] += Duration.ofSeconds(2).toNanos(),
                        new PrintStream(out, true, StandardCharsets.UTF_8))
                .runAll();

        assertThat(room.left).as("the chat the step opened").containsExactly("!dm:x");
        assertThat(out.toString(StandardCharsets.UTF_8))
                .as("what the step said")
                .contains("FAIL a direct chat reaches one task: talk log of pi down");
    }

    /** Harm guarded: an interrupted wait going on, or ending the run without a failure said. */
    @Test
    void anInterruptedPauseIsAFailure() {
        Thread.currentThread().interrupt();
        try {
            final Failure stopped = catchThrowableOfType(Failure.class, RoomRehearsal::sleep);

            assertThat(stopped).as("the stop").isNotNull();
            assertThat(stopped.exitCode()).as("its exit").isEqualTo(Exit.TEMPORARY);
            assertThat(Thread.currentThread().isInterrupted()).as("the interrupt kept").isTrue();
        } finally {
            Thread.interrupted();
        }
    }

    /** Harm guarded: a task named twice, so the rehearsal judges it against itself. */
    @Test
    void aTaskNamedTwiceIsRefused() {
        for (final String second :
                List.of(
                        "pi=@pi-other.bob:x=Agent Other",
                        "other=@pi.bob:x=Agent Other",
                        "other=@other.bob:x=Agent Pi")) {
            final Failure refused =
                    catchThrowableOfType(
                            Failure.class,
                            () ->
                                    RoomRehearsal.Arguments.parse(
                                            new String[] {
                                                "--socket",
                                                "/s",
                                                "--room",
                                                "!r:x",
                                                "--task",
                                                "pi=@pi.bob:x=Agent Pi",
                                                "--task",
                                                second,
                                                "--talk-log",
                                                "true"
                                            }));
            assertThat(refused).as("refused: " + second).isNotNull();
            assertThat(refused.exitCode()).as("the refusal of " + second).isEqualTo(Exit.USAGE);
        }
        final Failure person =
                catchThrowableOfType(
                        Failure.class,
                        () ->
                                RoomRehearsal.Arguments.parse(
                                        new String[] {
                                            "--socket",
                                            "/s",
                                            "--room",
                                            "!r:x",
                                            "--task",
                                            "pi=@pi.bob:x=Agent Pi",
                                            "--task",
                                            "core=@core.bob:x=Agent Core",
                                            "--person",
                                            "@pi.bob:x",
                                            "--talk-log",
                                            "true"
                                        }));
        assertThat(person).as("a task named as a person").isNotNull();
    }

    /** Harm guarded: one task given twice judged as two, a head named beside itself. */
    @Test
    void tasksCompareByValue() {
        final FakeRoom room = new FakeRoom();
        final List<RoomRehearsal.Task> twice =
                List.of(
                        PI,
                        new RoomRehearsal.Task(PI.name(), PI.account(), PI.display()),
                        PI_TWO,
                        CORE);

        final Outcome outcome = rehearse(room, twice, Set.of());

        assertThat(outcome.held()).as("the run, said: " + outcome.said()).isTrue();
        assertThat(outcome.said())
                .as("no head named beside itself")
                .doesNotContain("Agent Pi whole, beside Agent Pi\n");
    }

    /** Harm guarded: lines read back in another order than they were sent. */
    @Test
    void linesReadOutOfOrderFail() {
        final FakeRoom room =
                new FakeRoom() {
                    @Override
                    public List<JsonNode> messages(final String in, final String back) {
                        final List<JsonNode> read = new ArrayList<>(super.messages(in, back));
                        if (read.size() >= 2
                                && read.getLast()
                                        .path("content")
                                        .path("body")
                                        .asText()
                                        .startsWith("order two")) {
                            read.add(read.size() - 2, read.removeLast());
                        }
                        return read;
                    }
                };

        final Outcome outcome = rehearse(room);

        assertThat(outcome.said())
                .as("the order")
                .containsPattern(
                        "FAIL the room reads in order, with no stranger: posted \\$e\\d+ then");
    }

    /**
     * Harm guarded: a run without two tasks of a shared start saying nothing of the missing step.
     */
    @Test
    void aMissingPairOfDisplaysIsNamedAsSkipped() {
        final Outcome outcome = rehearse(new FakeRoom(), List.of(PI, CORE));

        assertThat(outcome.said())
                .as("the skipped step")
                .contains(
                        "SKIP a head names a display whole: no two tasks whose displays share a"
                                + " start");
    }

    /** Harm guarded: a held or refused line counted as delivered. */
    @Test
    void onlyADeliveredRowOfTheLineCounts() {
        final String log =
                """
                AT                       EVENT        PEER                 MESSAGE                              DETAIL
                2026-11-09T08:00:00Z     held         bob                  20261109T075959000--matrix-person-a.json held for a person
                2026-11-09T08:00:01Z     delivered    bob                  20261109T080000000--matrix-person-b.json -
                """;

        assertThat(RoomRehearsal.delivered(log, "--matrix-person-a.json"))
                .as("held only")
                .isFalse();
        assertThat(RoomRehearsal.delivered(log, "--matrix-person-b.json")).as("delivered").isTrue();
    }

    /**
     * Harm guarded: the rehearsal looking for another record than porelo keeps of a person's line.
     */
    @Test
    void aLinesRecordIsTheOnePoreloKeepsOfIt() {
        // As porelo 0.5.1 printed it on a host for a person's line with this event id.
        final String log =
                """
                AT                       EVENT        PEER                 MESSAGE                              DETAIL
                2026-11-09T21:16:49.371594691Z delivered    ada                  20261109T211647906--matrix-person-b17ea879-a344-3734-b1cc-d6b9f3b04165.json -
                """;

        assertThat(
                        RoomRehearsal.delivered(
                                log,
                                RoomRehearsal.messageId(
                                        "$gcdDnG3mEijT4Y8jcRhYXOXutsJ3wKcmVoAbjCbkSjY")))
                .as("the line's own record")
                .isTrue();
        assertThat(RoomRehearsal.delivered(log, RoomRehearsal.messageId("$another")))
                .as("another line's record")
                .isFalse();
    }

    /** Harm guarded: a task's name running a command of its own on the host. */
    @Test
    void aTaskNameThatIsNoNameIsRefused() {
        for (final String name : List.of("pi;id", "pi id", "$(id)", "-o", "Pi", "pi`id`", "")) {
            final Failure refused =
                    catchThrowableOfType(
                            Failure.class,
                            () -> RoomRehearsal.Task.parse(name + "=@pi.bob:x=Agent Pi"));
            assertThat(refused).as("the task named " + name).isNotNull();
            assertThat(refused.exitCode()).as("the refusal of " + name).isEqualTo(Exit.USAGE);
        }
    }

    /** Harm guarded: a talk log that never ends holding the rehearsal past its hard stop. */
    @Test
    @Timeout(20)
    void aTalkLogThatDoesNotEndIsStoppedAtItsLimit() {
        final RoomRehearsal.TalkLog hanging =
                RoomRehearsal.command(List.of("sh", "-c", "sleep 30", "sh"), Duration.ofSeconds(1));

        final Failure stopped = catchThrowableOfType(Failure.class, () -> hanging.of("pi"));

        assertThat(stopped).as("the stop").isNotNull();
        assertThat(stopped.getMessage())
                .as("what was said")
                .isEqualTo("talk log of pi did not end within 1 s");
    }

    /** Harm guarded: a talk log read only in part, so a delivered row past the cut goes unseen. */
    @Test
    void aTalkLogIsReadWhole() throws Exception {
        final RoomRehearsal.TalkLog log =
                RoomRehearsal.command(
                        List.of(
                                "sh",
                                "-c",
                                "head -c 5000000 /dev/zero | tr '\\0' x; echo; echo \"$1\"",
                                "sh"),
                        Duration.ofSeconds(15));

        assertThat(log.of("pi").strip()).as("the last line").endsWith("pi");
    }

    /** A line of {@code sender} with {@code content} added to the room. */
    private static void line(
            final FakeRoom room, final String sender, final String id, final JsonNode content) {
        room.rooms
                .computeIfAbsent("!room:x", r -> new ArrayList<>())
                .add(
                        MatrixClient.JSON
                                .createObjectNode()
                                .put("type", "m.room.message")
                                .put("event_id", id)
                                .put("sender", sender)
                                .set("content", content));
    }

    private static com.fasterxml.jackson.databind.node.ObjectNode text(final String body) {
        return MatrixClient.JSON.createObjectNode().put("msgtype", "m.text").put("body", body);
    }

    /**
     * Harm guarded: a room where "All agents" or @room is lost on the way passes, since a line that
     * names nobody wakes every task anyway.
     */
    @Test
    void aLineForAllThatWakesOnlyTheTaskNamedFails() {
        final FakeRoom room = new FakeRoom();
        room.answering =
                line ->
                        FakeRoom.woken(
                                new Line(
                                        line.text().replace("All agents", "x"),
                                        line.mentions().stream()
                                                .filter(m -> !m.equals("@room"))
                                                .toList(),
                                        line.directTo()));

        final Outcome outcome = rehearse(room);

        assertThat(outcome.said())
                .as("the lines for all, their heads naming one task")
                .contains(
                        "FAIL All agents: wakes every task: asked every task, got no answer within"
                                + " 10 s from Agent Pi-Two, Agent Core")
                .contains(
                        "FAIL @room wakes every task: asked every task, got no answer within"
                                + " 10 s from Agent Pi-Two, Agent Core");
    }

    /** Harm guarded: a relay's unsigned line passed as a local agent's. */
    @Test
    void aRelaysUnsignedLineFails() {
        final FakeRoom room = new FakeRoom();
        room.members.add("@porelo-relay-demo.bob:x");
        room.answering =
                line -> {
                    if (line.text().startsWith("order one")) {
                        room.add("!room:x", "@porelo-relay-demo.bob:x", "unsigned");
                    }
                    return FakeRoom.woken(line);
                };

        final Outcome outcome = rehearse(room);

        assertThat(outcome.said())
                .contains(
                        "read lines of accounts no task or person holds: [@porelo-relay-demo.bob:x]");
    }

    /** Harm guarded: a second person's own helpers failed as strangers. */
    @Test
    void aNamedPersonsHelpersHold() {
        final FakeRoom room = new FakeRoom();
        room.members.addAll(Set.of("@ada:x", "@porelo.ada:x", "@porelo-relay-demo.ada:x"));

        final Outcome outcome = rehearse(room, TASKS, Set.of("@ada:x"));

        assertThat(outcome.held()).as("the run, said: " + outcome.said()).isTrue();
    }

    /** Harm guarded: an account that is no task answering a line meant for one task, unnoticed. */
    @Test
    void anAccountNoTaskHoldsAnsweringALineFails() {
        final FakeRoom room = new FakeRoom();
        room.answering =
                line -> {
                    final java.util.regex.Matcher asked = ASKED.matcher(line.text());
                    if (asked.find()) {
                        room.add("!room:x", "@retired.bob:x", "done " + asked.group(1));
                    }
                    return FakeRoom.woken(line);
                };

        final Outcome outcome = rehearse(room);

        assertThat(outcome.said())
                .contains(
                        "FAIL a head names one task: asked Agent Pi alone, got answers within 4 s"
                                + " from @retired.bob:x");
    }

    /** Harm guarded: a task answering one line twice reported as answering it. */
    @Test
    void aTaskAnsweringTwiceFails() {
        final FakeRoom room = new FakeRoom();
        room.answering =
                line -> {
                    final List<RoomRehearsal.Task> woken = FakeRoom.woken(line);
                    return woken.size() == 1 ? List.of(woken.getFirst(), woken.getFirst()) : woken;
                };

        final Outcome outcome = rehearse(room);

        assertThat(outcome.said())
                .contains("FAIL a head names one task: asked Agent Pi once, it answered 2 times");
    }

    /**
     * Harm guarded: another task's answer missed because its poll took the line late, after the
     * quiet time had passed.
     */
    @Test
    void anotherTasksLateAnswerIsWaitedFor() {
        final FakeRoom room = new FakeRoom();
        room.delivering = (task, line) -> task != CORE || !line.directTo().isEmpty();
        final int[] asked = {0};
        final Outcome outcome =
                rehearseWith(
                        room,
                        task -> {
                            if (task.equals("core") && ++asked[0] >= 3) {
                                // Core's poll takes every line now, and answers the one named.
                                for (final JsonNode line : List.copyOf(room.rooms.get("!room:x"))) {
                                    final String id =
                                            RoomRehearsal.messageId(line.path("event_id").asText());
                                    final java.util.regex.Matcher said =
                                            ASKED.matcher(
                                                    line.path("content").path("body").asText());
                                    if (room.logs
                                                    .computeIfAbsent("core", n -> new HashSet<>())
                                                    .add(id)
                                            && said.find()) {
                                        room.add(
                                                "!room:x", CORE.account(), "done " + said.group(1));
                                    }
                                }
                            }
                            return room.talkLog(task);
                        });

        assertThat(outcome.said())
                .contains(
                        "FAIL a head names one task: asked Agent Pi alone, got answers within 4 s"
                                + " from Agent Core");
    }

    /** Harm guarded: an edit, a thread's line or a redacted line taken for the task's answer. */
    @Test
    void onlyANewTextLineAnswers() {
        for (final String kind : List.of("m.replace", "m.thread", "redacted")) {
            final FakeRoom room = new FakeRoom();
            room.answering =
                    line -> {
                        final java.util.regex.Matcher asked = ASKED.matcher(line.text());
                        if (line.text().startsWith("Agent Pi:") && asked.find()) {
                            final var content =
                                    kind.equals("redacted")
                                            ? MatrixClient.JSON.createObjectNode()
                                            : text("done " + asked.group(1));
                            if (!kind.equals("redacted")) {
                                content.putObject("m.relates_to")
                                        .put("rel_type", kind)
                                        .put("event_id", "$older");
                            }
                            line(room, PI.account(), "$" + kind, content);
                            return List.of();
                        }
                        return FakeRoom.woken(line);
                    };

            final Outcome outcome = rehearse(room);

            assertThat(outcome.said())
                    .as(kind)
                    .contains(
                            "FAIL a head names one task: asked Agent Pi in the room, got no answer");
        }
    }

    /** Harm guarded: a direct line answered aloud in the project's room, unnoticed. */
    @Test
    void aDirectLineAnsweredInTheRoomFails() {
        final FakeRoom room = new FakeRoom();
        room.answering =
                line -> {
                    if (!line.directTo().isEmpty()) {
                        final java.util.regex.Matcher asked = ASKED.matcher(line.text());
                        if (asked.find()) {
                            room.add("!room:x", PI.account(), "done " + asked.group(1));
                        }
                    }
                    return FakeRoom.woken(line);
                };

        final Outcome outcome = rehearse(room);

        assertThat(outcome.said())
                .contains(
                        "FAIL a direct chat reaches one task: asked Agent Pi directly, the room got"
                                + " its answer from [@pi.bob:x]");
    }

    /** Harm guarded: an interrupted run going on through the scenarios left. */
    @Test
    void anInterruptStopsTheRun() {
        final FakeRoom room = new FakeRoom();
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            new RoomRehearsal(
                            room,
                            "!room:x",
                            TASKS,
                            Set.of(),
                            room::talkLog,
                            Duration.ofSeconds(10),
                            Duration.ofSeconds(4),
                            () -> 0L,
                            () -> {
                                Thread.currentThread().interrupt();
                                throw new Failure(Exit.TEMPORARY, "interrupted while waiting");
                            },
                            new PrintStream(out, true, StandardCharsets.UTF_8))
                    .runAll();
        } finally {
            Thread.interrupted();
        }

        assertThat(out.toString(StandardCharsets.UTF_8).lines())
                .as("one failure, then the stop")
                .containsExactly(
                        "FAIL a head names one task: interrupted while waiting",
                        "STOP interrupted");
    }

    /** Harm guarded: a failure printing the session's token, or spilling over several lines. */
    @Test
    void aFailureIsOneLineWithNothingOfTheSession() {
        final FakeRoom room =
                new FakeRoom() {
                    @Override
                    public String scrub(final String text) {
                        return text.replace("syt_secret", "<token>");
                    }
                };

        final Outcome outcome =
                rehearseWith(
                        room,
                        task -> {
                            throw new Failure(
                                    Exit.TEMPORARY, "talk log said\nBearer syt_secret\nand more");
                        });

        assertThat(outcome.said())
                .contains("FAIL a head names one task: talk log said Bearer <token> and more\n")
                .doesNotContain("syt_secret");
    }

    private static Outcome rehearseWith(final FakeRoom room, final RoomRehearsal.TalkLog log) {
        final long[] now = {0};
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        final boolean held =
                new RoomRehearsal(
                                room,
                                "!room:x",
                                TASKS,
                                Set.of(),
                                log,
                                Duration.ofSeconds(10),
                                Duration.ofSeconds(4),
                                () -> now[0],
                                () -> now[0] += Duration.ofSeconds(2).toNanos(),
                                new PrintStream(out, true, StandardCharsets.UTF_8))
                        .runAll();
        return new Outcome(held, out.toString(StandardCharsets.UTF_8));
    }

    private static Failure refusal(final String... extra) {
        final List<String> args =
                new ArrayList<>(
                        List.of(
                                "--socket",
                                "/s",
                                "--room",
                                "!r:x",
                                "--task",
                                "pi=@pi.bob:x=Agent Pi",
                                "--task",
                                "core=@core.bob:x=Agent Core"));
        args.addAll(List.of(extra));
        args.addAll(List.of("--talk-log", "true"));
        return catchThrowableOfType(
                Failure.class, () -> RoomRehearsal.Arguments.parse(args.toArray(String[]::new)));
    }

    /**
     * Harm guarded: an argument given twice silently replacing the first, or a display or person
     * the room cannot hold, so a room that behaves fails.
     */
    @Test
    void argumentsTheRunCannotUseAreRefused() {
        for (final List<String> extra :
                List.of(
                        List.of("--room", "!other:x"),
                        List.of("--socket", "/t"),
                        List.of("--answer-within", "5", "--answer-within", "6"),
                        List.of("--quiet-for", "5", "--quiet-for", "6"),
                        List.of("--person", "@ada:x", "--person", "@ada:x"),
                        List.of("--person", "@porelo.bob:x"),
                        List.of("--person", "@porelo:x"),
                        List.of("--person", "@pi.bob:x"),
                        List.of("--person", "@ada:elsewhere"),
                        List.of("--task", "x=@x.bob:x=Agent: X"),
                        List.of("--task", "x=@x.bob:x= Agent X"),
                        List.of("--task", "x=@x.bob:x=Agent X\u200b"),
                        List.of("--task", "x=@x.bob:x=Pi"))) {
            final Failure refused = refusal(extra.toArray(String[]::new));
            assertThat(refused).as("refused: " + extra).isNotNull();
            assertThat(refused.exitCode()).as("its exit: " + extra).isEqualTo(Exit.USAGE);
        }
        assertThat(refusal("--person", "@ada:x", "--task", "x=@x.bob:x=Agent X (short)"))
                .as("a person and a display with its short name")
                .isNull();
    }

    /**
     * Harm guarded: a line for every task answered twice by one, or by an account no task holds.
     */
    @Test
    void aLineForEveryTaskAnsweredTwiceOrByAStrangerFails() {
        for (final boolean stranger : List.of(false, true)) {
            final FakeRoom room = new FakeRoom();
            room.answering =
                    line -> {
                        final List<RoomRehearsal.Task> woken = FakeRoom.woken(line);
                        if (woken.size() > 1) {
                            final java.util.regex.Matcher asked = ASKED.matcher(line.text());
                            if (asked.find()) {
                                room.add(
                                        "!room:x",
                                        stranger ? "@retired.bob:x" : CORE.account(),
                                        "done " + asked.group(1));
                            }
                        }
                        return woken;
                    };

            final Outcome outcome = rehearse(room);

            assertThat(outcome.said())
                    .as(stranger ? "a stranger" : "twice")
                    .contains(
                            stranger
                                    ? "FAIL All agents: wakes every task: asked every task,"
                                            + " answered by no task asked: [@retired.bob:x]"
                                    : "FAIL All agents: wakes every task: asked every task,"
                                            + " answered twice by Agent Core");
        }
    }

    /** Harm guarded: a direct line answered twice, or by someone else in the chat, held. */
    @Test
    void aDirectLineAnsweredTwiceFails() {
        final FakeRoom room = new FakeRoom();
        room.answering =
                line -> {
                    final List<RoomRehearsal.Task> woken = FakeRoom.woken(line);
                    return line.directTo().isEmpty()
                            ? woken
                            : List.of(woken.getFirst(), woken.getFirst());
                };

        final Outcome outcome = rehearse(room);

        assertThat(outcome.said())
                .contains(
                        "FAIL a direct chat reaches one task: asked Agent Pi directly, answered"
                                + " twice by Agent Pi");
    }

    /** Harm guarded: the room's history holding the nonce's letters failing a direct line. */
    @Test
    void aDirectLinesNonceInTheRoomsHistoryDoesNotCount() {
        final FakeRoom room = new FakeRoom();
        room.answering =
                line -> {
                    if (!line.directTo().isEmpty()) {
                        // Written before the run's first line, as an old line of the room.
                        final java.util.regex.Matcher asked = ASKED.matcher(line.text());
                        asked.find();
                        room.rooms
                                .get("!room:x")
                                .addFirst(
                                        MatrixClient.JSON
                                                .createObjectNode()
                                                .put("type", "m.room.message")
                                                .put("event_id", "$old")
                                                .put("sender", "@ada:x")
                                                .set("content", text("an old " + asked.group(1))));
                    }
                    return FakeRoom.woken(line);
                };

        final Outcome outcome = rehearse(room);

        assertThat(outcome.said()).contains("OK   a direct chat reaches one task");
    }

    /** Harm guarded: a relay's line with empty signature fields passed as signed. */
    @Test
    void aRelaysLineWithEmptySignatureFieldsFails() {
        final FakeRoom room = new FakeRoom();
        room.members.add("@porelo-relay-demo.bob:x");
        room.answering =
                line -> {
                    if (line.text().startsWith("order one")) {
                        line(
                                room,
                                "@porelo-relay-demo.bob:x",
                                "$empty",
                                text("signed?")
                                        .put(Send.MESSAGE_FIELD, "")
                                        .put(Send.SIGNATURE_FIELD, ""));
                    }
                    return FakeRoom.woken(line);
                };

        final Outcome outcome = rehearse(room);

        assertThat(outcome.said())
                .contains(
                        "read lines of accounts no task or person holds: [@porelo-relay-demo.bob:x]");
    }

    /** Harm guarded: the room's helpers failed on a server whose name holds a dot. */
    @Test
    void aServerNameWithADotHoldsItsHelpers() {
        final java.util.function.UnaryOperator<String> dotted = id -> id.replace(":x", ":foo.org");
        final List<RoomRehearsal.Task> tasks =
                TASKS.stream()
                        .map(
                                task ->
                                        new RoomRehearsal.Task(
                                                task.name(),
                                                dotted.apply(task.account()),
                                                task.display()))
                        .toList();
        final FakeRoom room = new FakeRoom();
        final RoomRehearsal.Room onDotted =
                new RoomRehearsal.Room() {
                    @Override
                    public String say(final String in, final String text, final List<String> m) {
                        return room.say(
                                in,
                                text,
                                m.stream().map(id -> id.replace(":foo.org", ":x")).toList());
                    }

                    @Override
                    public RoomRehearsal.Posted direct(final String user, final String text) {
                        return room.direct(user.replace(":foo.org", ":x"), text);
                    }

                    @Override
                    public void leave(final String in) throws Failure {
                        room.leave(in);
                    }

                    @Override
                    public List<JsonNode> messages(final String in, final String back) {
                        return room.messages(in, back).stream()
                                .map(
                                        m ->
                                                (JsonNode)
                                                        ((com.fasterxml.jackson.databind.node
                                                                                .ObjectNode)
                                                                        m.deepCopy())
                                                                .put(
                                                                        "sender",
                                                                        dotted.apply(
                                                                                m.path("sender")
                                                                                        .asText())))
                                .toList();
                    }

                    @Override
                    public Set<String> members(final String in) {
                        final Set<String> members = new HashSet<>();
                        room.members(in).forEach(id -> members.add(dotted.apply(id)));
                        members.addAll(
                                Set.of(
                                        "@ada:foo.org",
                                        "@porelo:foo.org",
                                        "@porelo.bob:foo.org",
                                        "@porelo.ada:foo.org",
                                        "@porelo-relay-demo.ada:foo.org"));
                        return members;
                    }
                };
        final long[] now = {0};
        final ByteArrayOutputStream out = new ByteArrayOutputStream();

        final boolean held =
                new RoomRehearsal(
                                onDotted,
                                "!room:x",
                                tasks,
                                Set.of("@ada:foo.org"),
                                room::talkLog,
                                Duration.ofSeconds(10),
                                Duration.ofSeconds(4),
                                () -> now[0],
                                () -> now[0] += Duration.ofSeconds(2).toNanos(),
                                new PrintStream(out, true, StandardCharsets.UTF_8))
                        .runAll();

        assertThat(held).as("the run, said: " + out.toString(StandardCharsets.UTF_8)).isTrue();
    }

    /** Harm guarded: two lines answered with one event id taken for two in order. */
    @Test
    void twoLinesWithOneIdFail() {
        final FakeRoom room =
                new FakeRoom() {
                    @Override
                    public String say(final String in, final String text, final List<String> m) {
                        if (text.startsWith("order two")) {
                            return rooms.get(in).getLast().path("event_id").asText();
                        }
                        return super.say(in, text, m);
                    }
                };

        final Outcome outcome = rehearse(room);

        assertThat(outcome.said())
                .containsPattern("FAIL the room reads in order, with no stranger: posted");
    }

    /** Harm guarded: a direct chat left open when the thread was interrupted during its step. */
    @Test
    void theDirectChatIsLeftWhenTheStepIsInterrupted() {
        final FakeRoom room =
                new FakeRoom() {
                    @Override
                    public void leave(final String in) throws Failure {
                        if (!Thread.currentThread().isInterrupted()) {
                            super.leave(in);
                        }
                    }
                };
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        final long[] now = {0};
        try {
            new RoomRehearsal(
                            room,
                            "!room:x",
                            TASKS,
                            Set.of(),
                            task -> {
                                if (room.rooms.containsKey("!dm:x")) {
                                    Thread.currentThread().interrupt();
                                    throw new Failure(Exit.TEMPORARY, "interrupted");
                                }
                                return room.talkLog(task);
                            },
                            Duration.ofSeconds(10),
                            Duration.ofSeconds(4),
                            () -> now[0],
                            () -> now[0] += Duration.ofSeconds(2).toNanos(),
                            new PrintStream(out, true, StandardCharsets.UTF_8))
                    .runAll();

            assertThat(Thread.currentThread().isInterrupted()).as("the interrupt kept").isTrue();
        } finally {
            Thread.interrupted();
        }

        assertThat(room.left).as("the chat left").containsExactly("!dm:x");
        assertThat(out.toString(StandardCharsets.UTF_8)).endsWith("STOP interrupted\n");
    }

    /** Harm guarded: a login named porelo-something taken for a helper and refused as a person. */
    @Test
    void aPersonsLoginIsTheLoginRule() {
        assertThat(refusal("--person", "@porelo2:x")).as("a login like any other").isNull();
        assertThat(refusal("--person", "@porelo-x:x")).as("Porelo's own").isNotNull();
    }

    /** Harm guarded: the leave's failure hiding why the direct step failed. */
    @Test
    void aFailedDirectStepSaysItsOwnFailureWhenTheLeaveFailsToo() {
        final FakeRoom room =
                new FakeRoom() {
                    @Override
                    public void leave(final String in) throws Failure {
                        throw new Failure(Exit.TEMPORARY, "leave refused");
                    }
                };
        room.delivering = (task, line) -> line.directTo().isEmpty();

        final Outcome outcome = rehearse(room);

        assertThat(outcome.said())
                .containsPattern(
                        "FAIL a direct chat reaches one task: posted --matrix-person-[0-9a-f-]{36}"
                                + "\\.json, talk log lacks it")
                .doesNotContain("leave refused");
    }

    /** Harm guarded: a talk log left running on the host side when the run is interrupted. */
    @Test
    @Timeout(20)
    void anInterruptedTalkLogIsEnded() throws Exception {
        final java.nio.file.Path pid = java.nio.file.Files.createTempFile("talk-log", ".pid");
        try {
            final RoomRehearsal.TalkLog log =
                    RoomRehearsal.command(
                            List.of("sh", "-c", "echo $$ > " + pid + "; exec sleep 30", "sh"),
                            Duration.ofSeconds(15));
            final Thread caller = Thread.currentThread();
            Thread.ofVirtual()
                    .start(
                            () -> {
                                try {
                                    while (java.nio.file.Files.size(pid) == 0) {
                                        Thread.sleep(20);
                                    }
                                    Thread.sleep(100);
                                } catch (final Exception ex) {
                                    return;
                                }
                                caller.interrupt();
                            });

            final Failure stopped = catchThrowableOfType(Failure.class, () -> log.of("pi"));
            Thread.interrupted();

            assertThat(stopped).as("the stop").isNotNull();
            final long id = Long.parseLong(java.nio.file.Files.readString(pid).strip());
            final var process = ProcessHandle.of(id);
            if (process.isPresent()) {
                process.get().onExit().get(5, java.util.concurrent.TimeUnit.SECONDS);
            }
            assertThat(ProcessHandle.of(id).map(ProcessHandle::isAlive).orElse(false))
                    .as("the talk log ended")
                    .isFalse();
        } finally {
            java.nio.file.Files.deleteIfExists(pid);
        }
    }

    private static Failure projectRefusal(final String... args) {
        return catchThrowableOfType(Failure.class, () -> RoomRehearsal.Arguments.parse(args));
    }

    /**
     * Harm guarded: a project's rehearsal that also takes a room or tasks given by hand, so the one
     * call and a hand-typed list could disagree about what is rehearsed.
     */
    @Test
    void aProjectTakesNoRoomOrTaskBesideIt() throws Exception {
        final String[] project = {"--socket", "/s", "--project", "foo", "--talk-log", "true"};
        final RoomRehearsal.Arguments given = RoomRehearsal.Arguments.parse(project);
        assertThat(given.project()).as("the project").isEqualTo("foo");
        assertThat(given.room()).as("no room yet").isNull();
        assertThat(given.tasks()).as("no tasks yet").isEmpty();
        for (final List<String> wrong :
                List.of(
                        List.of("--project", "foo", "--room", "!r:x"),
                        List.of("--project", "foo", "--task", "pi=@pi.bob:x=Agent Pi"),
                        List.of("--project", "foo", "--project", "foo"),
                        List.of("--project", "Foo"),
                        List.of("--project", "-foo"),
                        List.of("--project", "foo bar"))) {
            final List<String> args = new ArrayList<>(List.of("--socket", "/s"));
            args.addAll(wrong);
            args.addAll(List.of("--talk-log", "true"));
            final Failure refused = projectRefusal(args.toArray(String[]::new));
            assertThat(refused).as("refused: " + wrong).isNotNull();
            assertThat(refused.exitCode()).as("its exit: " + wrong).isEqualTo(Exit.USAGE);
        }
    }

    /**
     * Harm guarded: another person of the project, the host's own admin among them, counted as a
     * stranger because the run knows only the signed-in person, so a room that behaves fails; or a
     * login no host has, or one named by hand beside a room given by hand, taken for a person.
     */
    @Test
    void aProjectsOtherPersonsAreNamedByTheirLogins() throws Exception {
        final RoomRehearsal.Arguments given =
                RoomRehearsal.Arguments.parse(
                        new String[] {
                            "--socket",
                            "/s",
                            "--project",
                            "foo",
                            "--person-login",
                            "carol",
                            "--person-login",
                            "bob",
                            "--talk-log",
                            "true"
                        });
        assertThat(RoomRehearsal.persons(given, "@bob:matrix.example"))
                .as("each login on the signed-in person's server, the person itself left out")
                .containsExactlyInAnyOrder("@carol:matrix.example");
        for (final List<String> wrong :
                List.of(
                        List.of("--project", "foo", "--person-login", "Carol"),
                        List.of("--project", "foo", "--person-login", "carol.x"),
                        List.of("--project", "foo", "--person-login", "porelo"),
                        List.of("--project", "foo", "--person-login", "c", "--person-login", "c"),
                        List.of(
                                "--room",
                                "!r:x",
                                "--task",
                                "pi=@pi.bob:x=Agent Pi",
                                "--task",
                                "core=@core.bob:x=Agent Core",
                                "--person-login",
                                "carol"))) {
            final List<String> args = new ArrayList<>(List.of("--socket", "/s"));
            args.addAll(wrong);
            args.addAll(List.of("--talk-log", "true"));
            final Failure refused = projectRefusal(args.toArray(String[]::new));
            assertThat(refused).as("refused: " + wrong).isNotNull();
            assertThat(refused.exitCode()).as("its exit: " + wrong).isEqualTo(Exit.USAGE);
        }
    }

    /**
     * Harm guarded: a rehearsal that takes another person's task, another project's, a helper, a
     * person or a stopped task's left account for a running task of the project, so a run judges
     * accounts nobody started for it.
     */
    @Test
    void theRunningTasksAreThisLoginsTasksOfTheProjectInTheRoom() throws Exception {
        final Map<String, String> joined = new HashMap<>();
        joined.put("@porelo-foo-pi.bob:x", "Agent Pi");
        joined.put("@porelo-foo-pi-two.bob:x", "Agent Pi (two)");
        joined.put("@porelo-foo-core.eve:x", "Agent Core");
        joined.put("@porelo-bar-core.bob:x", "Agent Core");
        joined.put("@porelo-foo-core.bob:elsewhere", "Agent Core");
        joined.put("@porelo-relay-foo.bob:x", "relay");
        joined.put("@porelo.bob:x", "bob's machine");
        joined.put("@porelo-foo-.bob:x", "Agent Nameless");
        joined.put("@bob:x", "Bob");

        assertThat(RoomRehearsal.running(joined, "foo", "@bob:x"))
                .as("the tasks, by name")
                .containsExactly(
                        new RoomRehearsal.Task("pi", "@porelo-foo-pi.bob:x", "Agent Pi"),
                        new RoomRehearsal.Task(
                                "pi-two", "@porelo-foo-pi-two.bob:x", "Agent Pi (two)"));
    }

    /**
     * Harm guarded: a run over fewer than two tasks, which cannot tell a line for one from a line
     * for all, or over a task whose display no head can name, passing for a room that behaves.
     */
    @Test
    void tooFewTasksOrAnUnnameableDisplayAreRefused() {
        final Failure few =
                catchThrowableOfType(
                        Failure.class,
                        () ->
                                RoomRehearsal.running(
                                        Map.of("@porelo-foo-pi.bob:x", "Agent Pi"),
                                        "foo",
                                        "@bob:x"));
        assertThat(few).as("one task").isNotNull();
        assertThat(few.exitCode()).as("its exit").isEqualTo(Exit.DATA);
        assertThat(few.getMessage())
                .as("what was found")
                .isEqualTo(
                        "the room holds 1 running task of foo for @bob:x, the rehearsal needs two:"
                                + " [pi]");

        final Failure unnamed =
                catchThrowableOfType(
                        Failure.class,
                        () ->
                                RoomRehearsal.running(
                                        Map.of(
                                                "@porelo-foo-pi.bob:x", "Agent Pi",
                                                "@porelo-foo-core.bob:x", "core"),
                                        "foo",
                                        "@bob:x"));
        assertThat(unnamed).as("a display no head names").isNotNull();
        assertThat(unnamed.exitCode()).as("its exit").isEqualTo(Exit.DATA);
        assertThat(unnamed.getMessage())
                .as("which")
                .isEqualTo("the room shows the task core as \"core\", which no head can name");
    }
}
