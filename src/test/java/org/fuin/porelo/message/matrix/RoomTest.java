package org.fuin.porelo.message.matrix;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Guards credentials, timeline identity and positions with local stand-ins. */
class RoomTest {

    @TempDir private Path dir;

    /** A per-account porelo must hand over its own vault's login, not the machine-wide one's. */
    @Test
    void theHelperIsThePoreloTheSearchPathFindsFirst() throws Exception {
        final Path own = Files.createDirectories(dir.resolve("own"));
        final Path machine = Files.createDirectories(dir.resolve("machine"));
        final Path notExecutable = Files.createDirectories(dir.resolve("plain"));
        Files.writeString(notExecutable.resolve("porelo"), "");
        for (final Path bin : List.of(own, machine)) {
            final Path porelo = Files.writeString(bin.resolve("porelo"), "#!/bin/sh\n");
            porelo.toFile().setExecutable(true);
        }
        assertThat(
                        Room.porelo(
                                Map.of(
                                        "PATH",
                                        "relative/bin:"
                                                + notExecutable
                                                + ":"
                                                + own
                                                + ":"
                                                + machine)))
                .as("a relative entry and a file that cannot run are passed over")
                .isEqualTo(own.resolve("porelo"));
        assertThatThrownBy(() -> Room.porelo(Map.of("PATH", notExecutable.toString())))
                .as("without a porelo to run, room stops before any request")
                .isInstanceOfSatisfying(
                        Failure.class,
                        failure ->
                                assertThat(failure.exitCode())
                                        .as("a missing helper is configuration")
                                        .isEqualTo(Exit.CONFIG));
    }

    /** A malformed event must never silently consume a new message's identity. */
    @ParameterizedTest
    @ValueSource(strings = {"m.text", "m.notice"})
    void aTextMessageNeedsAnEventId(final String type) throws Exception {
        final var event =
                MatrixClient.JSON.readTree(
                        "{\"type\":\"m.room.message\",\"content\":{\"msgtype\":\""
                                + type
                                + "\",\"body\":\"hello\"}}");
        assertThatThrownBy(() -> Room.messages("!room", List.of(event)))
                .as("a message without a server identity is a protocol failure")
                .isInstanceOfSatisfying(
                        Failure.class,
                        failure ->
                                assertThat(failure.exitCode())
                                        .as("protocol refusal")
                                        .isEqualTo(Exit.PROTOCOL));
    }

    /** An edit must not become unread work or wake a waiting account. */
    @Test
    void anEditIsNotANewLine() throws Exception {
        final var event =
                MatrixClient.JSON.readTree(
                        "{\"event_id\":\"$edit\",\"type\":\"m.room.message\",\"content\":{\"msgtype\":\"m.text\",\"body\":\"hello\",\"m.relates_to\":{\"rel_type\":\"m.replace\"}}}");
        assertThat(Room.messages("!room", List.of(event))).as("a replacement is skipped").isEmpty();
    }

    /** The exclusion rules must retain every distinct normal text event in order. */
    @Test
    void textEventsAreKeptOnceWhileOtherRoomsAndNonTextAreSkipped() throws Exception {
        final var first =
                MatrixClient.JSON
                        .createObjectNode()
                        .put("event_id", "$one")
                        .put("type", "m.room.message");
        first.putObject("content").put("msgtype", "m.text").put("body", "one");
        final var second = first.deepCopy().put("event_id", "$two");
        final var foreign =
                first.deepCopy().put("event_id", "$foreign").put("room_id", "!elsewhere");
        final var notice = first.deepCopy().put("event_id", "$notice");
        notice.withObject("content").put("msgtype", "m.notice");
        assertThat(Room.messages("!room", List.of(first, foreign, first, notice, second)))
                .as("only distinct normal text in this room is retained")
                .containsExactly(first, second);
    }

    /** A missing event id must not let an invalid timeline consume the saved position. */
    @Test
    void aMessageWithoutAnEventIdKeepsThePositionAndFailsProtocol() throws Exception {
        try (FakeHomeserver server = new FakeHomeserver()) {
            final Map<String, String> env = conversationEnvironment(server);
            queue(server, "start", "[]");
            assertThat(conversation(env, "read").exit()).as("the initial read succeeds").isZero();
            final Path position;
            try (var files = Files.list(Poll.stateDirectory(env).resolve("room"))) {
                position =
                        files.filter(path -> path.toString().endsWith(".since"))
                                .findFirst()
                                .orElseThrow();
            }
            final String saved = Files.readString(position);
            server.clearAnswers();
            queue(
                    server,
                    "next",
                    "[{\"type\":\"m.room.message\",\"sender\":\"@matrix:localhost\",\"content\":{\"msgtype\":\"m.text\",\"body\":\"Agent Core: hello\"}}]");
            final Transport.Result result = conversation(env, "read");
            assertThat(result.exit())
                    .as("an event without an identity is refused")
                    .isEqualTo(Exit.PROTOCOL);
            assertThat(Files.readString(position))
                    .as("the invalid batch remains unread")
                    .isEqualTo(saved);
        }
    }

    /** Backups must neither refuse nor wait on a person's independent position. */
    @Test
    void aRoomReadAndPostSucceedWhileABackupHoldsTheKeepLock() throws Exception {
        try (FakeHomeserver server = new FakeHomeserver()) {
            final Map<String, String> env = conversationEnvironment(server);
            Files.createDirectories(Poll.stateDirectory(env));
            try (FileChannel channel = Keep.lockFile(Poll.stateDirectory(env));
                    FileLock held = channel.lock()) {
                queue(server, "start", "[]");
                assertThat(conversation(env, "read").exit())
                        .as("a backup cannot refuse the first room read")
                        .isZero();
                server.clearAnswers();
                queue(server, "next", "[]");
                server.answer(200, "{\"event_id\":\"$post\"}");
                final Path file = Files.writeString(dir.resolve("post.txt"), "Agent Matrix: hello");
                assertThat(conversation(env, "post", file.toString()).exit())
                        .as("a backup cannot refuse a room post")
                        .isZero();
                assertThat(server.requests())
                        .as("the post actually reaches the server")
                        .anyMatch(
                                request ->
                                        request.method().equals("PUT")
                                                && request.rawPath()
                                                        .contains("/send/m.room.message/"));
            }
        }
    }

    /** Replacements are not new work and must not block a response. */
    @Test
    void anEditIsSkippedWithoutHoldingAPost() throws Exception {
        try (FakeHomeserver server = new FakeHomeserver()) {
            final Map<String, String> env = conversationEnvironment(server);
            queue(server, "start", "[]");
            assertThat(conversation(env, "read").exit()).as("the initial read succeeds").isZero();
            server.clearAnswers();
            queue(
                    server,
                    "next",
                    "[{\"event_id\":\"$edit\",\"type\":\"m.room.message\",\"sender\":\"@matrix:localhost\",\"content\":{\"msgtype\":\"m.text\",\"body\":\"Agent Core: correction\",\"m.relates_to\":{\"rel_type\":\"m.replace\",\"event_id\":\"$old\"}}}]");
            server.answer(200, "{\"event_id\":\"$post\"}");
            final Path file = Files.writeString(dir.resolve("post.txt"), "Agent Matrix: hello");
            assertThat(conversation(env, "post", file.toString()).exit())
                    .as("an edit is not an unread line")
                    .isZero();
        }
    }

    /** An inherited pipe must not hold the CLI after its credential helper exits. */
    @Test
    void aHelpersDescendantCannotHoldTheCredentialReadOpen() throws Exception {
        final Path helper =
                script(
                        "cat >/dev/null\n/bin/sleep 60 &\nprintf '%s' \"$!\" > \"$CHILD_PID\"\nprintf 'password=fixture-value\\n\\n'\nexit 0\n");
        final long started = System.nanoTime();
        final Transport.Result result = credentialProbe(helper, 4);
        assertThat(result.exit())
                .as("an empty line completes a valid credential despite inherited pipes")
                .isEqualTo(Exit.OK);
        assertThat(System.nanoTime() - started)
                .as("a descendant cannot extend the credential request past four seconds")
                .isLessThan(TimeUnit.SECONDS.toNanos(4));
        assertThat(result.out()).as("the credential never reaches the probe's stdout").isEmpty();
    }

    /** Bytes after the protocol terminator cannot change or invalidate the answer. */
    @Test
    void theFirstEmptyLineEndsTheCredentialAnswer() throws Exception {
        final Path helper = script("cat >/dev/null\nprintf 'password=fixture-value\\n\\n\\377'\n");
        assertThat(new Room(helper).credential("agent", 8123, Map.of(), System.err))
                .as("trailing bytes are outside the credential response")
                .isEqualTo("fixture-value");
    }

    /** A helper's output cannot fill memory or stderr, or postpone a failed read. */
    @Test
    void credentialStderrIsBoundedAndExcessIsDrained() throws Exception {
        final Path helper =
                script(
                        "cat >/dev/null\n/bin/dd if=/dev/zero bs=1024 count=128 >&2 2>/dev/null\nprintf 'password=fixture-value\\n'\n");
        final ByteArrayOutputStream err = new ByteArrayOutputStream();
        assertThat(new Room(helper).credential("agent", 8123, Map.of(), new PrintStream(err)))
                .as("stderr overflow does not block a valid answer")
                .isEqualTo("fixture-value");
        assertThat(err.size()).as("only the first 64 KiB of stderr is passed on").isEqualTo(65_536);
    }

    /** An oversized writer must not hold the caller until the process timeout. */
    @Test
    void oversizedCredentialStdoutIsClosedPromptly() throws Exception {
        final Path helper =
                script(
                        "cat >/dev/null\n/bin/dd if=/dev/zero bs=1024 count=256 2>/dev/null\nexec /bin/sleep 60\n");
        final Transport.Result result = credentialProbe(helper, 4);
        assertThat(result.exit())
                .as("an overlong answer is a bounded credential refusal")
                .isEqualTo(Exit.CONFIG);
        assertThat(result.err()).as("overlong stdout remains private").contains("agent");
    }

    @Test
    void anInvalidFilePathIsAnArgumentRefusal() {
        assertThatThrownBy(
                        () ->
                                new Room(dir.resolve("unused"))
                                        .run(
                                                List.of(
                                                        "post",
                                                        "bad\u0000path",
                                                        "--entry",
                                                        "agent",
                                                        "--room",
                                                        "!room"),
                                                Map.of(),
                                                System.out,
                                                System.err))
                .as("a malformed path must not crash the command")
                .isInstanceOfSatisfying(
                        Failure.class,
                        failure ->
                                assertThat(failure.exitCode())
                                        .as("argument refusal")
                                        .isEqualTo(Exit.USAGE));
    }

    @Test
    void roomIsDispatchedBeforeReadingConfiguration() {
        final ByteArrayOutputStream err = new ByteArrayOutputStream();
        final int exit =
                Main.run(
                        new String[] {"room", "read", "--entry", "agent", "--room", "!room"},
                        Map.of(),
                        System.out,
                        new PrintStream(err));
        assertThat(exit).as("the verb reaches missing local configuration").isEqualTo(Exit.CONFIG);
        assertThat(err.toString(StandardCharsets.UTF_8))
                .as("room is a known verb")
                .doesNotContain("unknown verb");
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                Config.ACCESS_TOKEN,
                Config.ADMIN_TOKEN,
                Config.REGISTRATION_TOKEN,
                Config.MACHINE_SECRET
            })
    void refusesEveryInheritedSecretEvenWhenEmpty(final String variable) {
        assertThatThrownBy(
                        () ->
                                new Room(dir.resolve("unused"))
                                        .run(
                                                List.of(
                                                        "read", "--entry", "agent", "--room",
                                                        "!room"),
                                                Map.of(variable, ""),
                                                System.out,
                                                System.err))
                .as("an inherited credential must never override the vault")
                .isInstanceOfSatisfying(
                        Failure.class,
                        failure -> {
                            assertThat(failure.exitCode())
                                    .as("configuration refusal")
                                    .isEqualTo(Exit.CONFIG);
                            assertThat(failure.getMessage())
                                    .as("the variable is named without its value")
                                    .contains(variable);
                        });
    }

    @Test
    void helperReceivesTheExactProtocolAndArgumentVector() throws Exception {
        final Path helper =
                script(
                        """
                test "$#" = 5 && test "$1" = vault && test "$2" = credential && test "$3" = --entry && test "$4" = 'agent core' && test "$5" = get || exit 9
                IFS= read -r protocol
                IFS= read -r host
                IFS= read -r blank
                test "$protocol" = protocol=http && test "$host" = host=127.0.0.1:8123 && test -z "$blank" || exit 10
                printf 'username=x-access-token\npassword=fixture-value\n'
                """);
        assertThat(new Room(helper).credential("agent core", 8123, Map.of(), System.err))
                .as("the password field alone is the token")
                .isEqualTo("fixture-value");
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "",
                "password=",
                "password=one\npassword=two",
                "{\"password\":\"sensitive-marker\"}",
                "username=sensitive-marker",
                "password=bad value",
                "password=value\nbroken-sensitive-marker"
            })
    void malformedHelperAnswersNeverReachAnException(final String answer) throws Exception {
        final Path helper = script("cat >/dev/null\nprintf '%s' '" + answer + "'\n");
        assertThatThrownBy(() -> new Room(helper).credential("agent", 8123, Map.of(), System.err))
                .as("untrusted helper output cannot become a diagnostic")
                .isInstanceOfSatisfying(
                        Failure.class,
                        failure -> {
                            assertThat(failure.exitCode())
                                    .as("the credential is unusable")
                                    .isEqualTo(Exit.CONFIG);
                            assertThat(failure.getMessage())
                                    .as("only the entry identifies a failed credential")
                                    .isEqualTo("cannot read vault entry 'agent'");
                            assertThat(failure.getCause())
                                    .as("a parser's source snippet is not attached")
                                    .isNull();
                        });
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 4})
    void passesHelperStderrUnchangedAndDiscardsItsFailedStdout(final int status) throws Exception {
        final Path helper =
                script(
                        "cat >/dev/null\n"
                                + (status == 0 ? "" : "printf 'password=sensitive-marker\\n'\n")
                                + "printf 'porelo: the vault is not open\\n' >&2\nexit "
                                + status
                                + "\n");
        final ByteArrayOutputStream err = new ByteArrayOutputStream();
        assertThatThrownBy(
                        () ->
                                new Room(helper)
                                        .credential("agent", 8123, Map.of(), new PrintStream(err)))
                .as("a shut vault or nonzero helper exit stops the room")
                .isInstanceOfSatisfying(
                        Failure.class,
                        failure -> {
                            assertThat(failure.exitCode())
                                    .as("configuration refusal")
                                    .isEqualTo(Exit.CONFIG);
                            assertThat(failure.getMessage())
                                    .as("stdout stays private")
                                    .isEqualTo("cannot read vault entry 'agent'");
                        });
        assertThat(err.toString(StandardCharsets.UTF_8))
                .as("the vault explains the failure itself")
                .isEqualTo("porelo: the vault is not open\n");
    }

    /** An executable fixture with no shell interpolation by the production code. */
    private Path script(final String body) throws Exception {
        final Path helper = dir.resolve("helper");
        Files.writeString(helper, "#!/bin/sh\n" + body, StandardCharsets.UTF_8);
        assertThat(helper.toFile().setExecutable(true, true))
                .as("the fixture is executable")
                .isTrue();
        return helper;
    }

    /** Uses only the fake server's local configuration and a pipe credential fixture. */
    private Map<String, String> conversationEnvironment(final FakeHomeserver server)
            throws Exception {
        final Map<String, String> env =
                Map.of(
                        "XDG_CONFIG_HOME",
                        dir.resolve("config").toString(),
                        "XDG_STATE_HOME",
                        dir.resolve("state").toString());
        final Path config =
                Files.createDirectories(LocalHomeserver.directory(env)).resolve("homeserver.conf");
        Files.writeString(
                config,
                LocalHomeserver.PORT + "=" + java.net.URI.create(server.url()).getPort() + "\n");
        script("cat >/dev/null\nprintf 'password=fixture-value\\n'\n");
        return env;
    }

    /** Queues the identity, membership, sync and display-name calls for a room command. */
    private static void queue(final FakeHomeserver server, final String next, final String events) {
        server.answer(200, "{\"user_id\":\"@core:localhost\"}")
                .answer(200, "{\"joined_rooms\":[\"!room\"]}")
                .answer(
                        200,
                        "{\"next_batch\":\""
                                + next
                                + "\",\"rooms\":{\"join\":{\"!room\":{\"timeline\":{\"events\":"
                                + events
                                + "}}}}}")
                .answer(
                        200,
                        "{\"joined\":{\"@core:localhost\":{\"display_name\":\"Agent Core\"},\"@matrix:localhost\":{\"display_name\":\"Agent Matrix\"}}}");
    }

    /** Captures the same exit and diagnostics Main reports. */
    private Transport.Result conversation(final Map<String, String> env, final String... args) {
        final List<String> command = new java.util.ArrayList<>(List.of(args));
        command.addAll(List.of("--entry", "agent", "--room", "!room"));
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        final ByteArrayOutputStream err = new ByteArrayOutputStream();
        int exit = Exit.OK;
        try {
            new Room(dir.resolve("helper"))
                    .run(command, env, new PrintStream(out), new PrintStream(err));
        } catch (final Failure failure) {
            exit = Main.reportFailure("room", failure, new PrintStream(err));
        }
        return new Transport.Result(
                exit, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    /** Isolates a potentially hung reader and cleans up only the fixture's processes. */
    private Transport.Result credentialProbe(final Path helper, final int seconds)
            throws Exception {
        final Path out = dir.resolve("probe.out");
        final Path err = dir.resolve("probe.err");
        final Path pid = dir.resolve("child.pid");
        final ProcessBuilder builder =
                new ProcessBuilder(
                                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                                "-cp",
                                System.getProperty("surefire.test.class.path"),
                                CredentialProbe.class.getName(),
                                helper.toString())
                        .redirectOutput(out.toFile())
                        .redirectError(err.toFile());
        builder.environment().clear();
        builder.environment().put("CHILD_PID", pid.toString());
        final Process process = builder.start();
        try {
            assertThat(process.waitFor(seconds, TimeUnit.SECONDS))
                    .as(
                            "credential command ends within %s seconds despite inherited or overflowing pipes",
                            seconds)
                    .isTrue();
            return new Transport.Result(
                    process.exitValue(), Files.readString(out), Files.readString(err));
        } finally {
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            if (Files.exists(pid)) {
                ProcessHandle.of(Long.parseLong(Files.readString(pid)))
                        .ifPresent(ProcessHandle::destroyForcibly);
            }
            process.toHandle().destroyForcibly();
        }
    }

    /** Calls the actual credential path without printing its returned value. */
    public static final class CredentialProbe {
        private CredentialProbe() {}

        /** Runs in a separate JVM so a regression cannot hang the test harness. */
        public static void main(final String[] args) {
            try {
                new Room(Path.of(args[0])).credential("agent", 8123, System.getenv(), System.err);
            } catch (final Failure failure) {
                System.exit(Main.reportFailure("room", failure, System.err));
            }
        }
    }
}
