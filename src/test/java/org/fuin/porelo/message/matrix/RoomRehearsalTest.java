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
            final List<RoomRehearsal.Task> named =
                    TASKS.stream().filter(task -> head.equals(task.display())).toList();
            return named.isEmpty() ? TASKS : named;
        }

        @Override
        public String say(final String room, final String text, final List<String> mentions) {
            return line(room, new Line(text, mentions, ""));
        }

        final List<String> left = new ArrayList<>();

        @Override
        public void leave(final String room) {
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
        public List<JsonNode> messages(final String room) {
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
        final long[] now = {0};
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        final boolean held =
                new RoomRehearsal(
                                room,
                                "!room:x",
                                tasks,
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
                .hasSize(9)
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
     * Harm guarded: a helper that writes into the room unnoticed, where only persons and tasks do.
     */
    @Test
    void aHelpersLineFails() {
        final FakeRoom room = new FakeRoom();
        room.members.add("@porelo-relay-demo.bob:x");
        room.add("!room:x", "@porelo-relay-demo.bob:x", "a line");

        final Outcome outcome = rehearse(room);

        assertThat(outcome.said())
                .as("the helper's line")
                .contains(
                        "FAIL the room reads in order, with no stranger: read lines of accounts no"
                                + " task or person holds: [@porelo-relay-demo.bob:x]");
    }

    /** Harm guarded: lines read back in another order than they were sent. */
    @Test
    void linesReadOutOfOrderFail() {
        final FakeRoom room =
                new FakeRoom() {
                    @Override
                    public List<JsonNode> messages(final String in) {
                        final List<JsonNode> read = new ArrayList<>(super.messages(in));
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
}
