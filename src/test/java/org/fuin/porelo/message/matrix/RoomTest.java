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

    /** Harm guarded: the credential size boundary locks out a valid login or admits excess data. */
    @ParameterizedTest
    @ValueSource(ints = {65_536, 65_537})
    void credentialAnswersRespectTheExactByteLimit(final int bytes) throws Exception {
        final String token = "a".repeat(bytes - "password=\n\n".length());
        final Path answer = Files.writeString(dir.resolve("answer"), "password=" + token + "\n\n");
        final Path helper = script("cat >/dev/null\n/bin/cat '" + answer + "'\n");
        final ByteArrayOutputStream err = new ByteArrayOutputStream();
        final Room room = new Room(helper);
        if (bytes == 65_536) {
            assertThat(room.credential("agent", 8123, Map.of(), new PrintStream(err)))
                    .as("an answer exactly at the byte limit yields its credential")
                    .isEqualTo(token);
        } else {
            assertThatThrownBy(() -> room.credential("agent", 8123, Map.of(), new PrintStream(err)))
                    .as("one excess byte is a private credential refusal")
                    .isInstanceOfSatisfying(
                            Failure.class,
                            failure -> {
                                assertThat(failure.exitCode())
                                        .as("credential refusal")
                                        .isEqualTo(Exit.CONFIG);
                                assertThat(failure.getMessage())
                                        .as("the refusal identifies the entry without its token")
                                        .isEqualTo("cannot read vault entry 'agent'");
                            })
                    .hasMessageNotContaining(token);
        }
        assertThat(err.toString(StandardCharsets.UTF_8))
                .as("credential stdout never becomes stderr")
                .doesNotContain(token);
    }

    /** Harm guarded: an incomplete option reaches the credential helper. */
    @ParameterizedTest
    @ValueSource(strings = {"read --room !room --entry", "read --entry agent --room"})
    void anOptionWithoutAValueIsRefused(final String command) {
        assertThatThrownBy(
                        () ->
                                Room.start(
                                        List.of(command.split(" ")),
                                        Map.of(),
                                        System.out,
                                        System.err))
                .as("missing values are refused before helper discovery")
                .isInstanceOfSatisfying(
                        Failure.class,
                        failure -> {
                            assertThat(failure.exitCode())
                                    .as("argument refusal")
                                    .isEqualTo(Exit.USAGE);
                            assertThat(failure.getMessage())
                                    .as("the room command shape is the reason")
                                    .startsWith("usage: room ");
                        });
    }

    /** Harm guarded: an oversized room identifier reaches the helper and homeserver. */
    @Test
    void aRoomIdOneByteOverItsLimitIsRefused() {
        final String id = "!" + "é".repeat(127) + "a";
        assertThat(id.getBytes(StandardCharsets.UTF_8))
                .as("fixture is one byte over the Matrix limit")
                .hasSize(256);
        assertThatThrownBy(
                        () ->
                                Room.start(
                                        List.of("read", "--entry", "agent", "--room", id),
                                        Map.of(),
                                        System.out,
                                        System.err))
                .as("byte length is checked before helper discovery")
                .isInstanceOfSatisfying(
                        Failure.class,
                        failure -> {
                            assertThat(failure.exitCode())
                                    .as("argument refusal")
                                    .isEqualTo(Exit.USAGE);
                            assertThat(failure.getMessage())
                                    .as("the identifier violates the room command shape")
                                    .startsWith("usage: room ");
                        });
    }

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
        final Transport.Result result = credentialProbe(helper, PROBE_SECONDS);
        assertThat(result.exit())
                .as("an empty line completes a valid credential despite inherited pipes")
                .isEqualTo(Exit.OK);
        assertThat(probeMillis(result))
                .as("a descendant does not hold the request until the helper's timeout")
                .isLessThan(PROMPT_MILLIS);
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
        final Transport.Result result = credentialProbe(helper, PROBE_SECONDS);
        assertThat(result.exit())
                .as("an overlong answer is a bounded credential refusal")
                .isEqualTo(Exit.CONFIG);
        assertThat(result.err()).as("overlong stdout remains private").contains("agent");
        assertThat(probeMillis(result))
                .as("an overlong answer is refused without waiting for the helper's timeout")
                .isLessThan(PROMPT_MILLIS);
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

    /** Well below the helper's timeout of ten seconds, and timed inside the probe's JVM. */
    private static final long PROMPT_MILLIS = 5_000;

    /** How long the credential call took, without the probe's JVM start. */
    private static long probeMillis(final Transport.Result result) {
        final var line =
                java.util.regex.Pattern.compile("probe-millis=([0-9]+)").matcher(result.err());
        assertThat(line.find()).as("the probe says how long it took").isTrue();
        return Long.parseLong(line.group(1));
    }

    /** Longer than a slow machine's JVM start and the timeouts, shorter than the helpers' sleep. */
    private static final int PROBE_SECONDS = 40;

    /** Isolates a potentially hung reader and cleans up only the fixture's processes. */
    private Transport.Result credentialProbe(final Path helper, final int seconds)
            throws Exception {
        return credentialProbe(helper, seconds, false);
    }

    /** Checks descendant termination before fixture cleanup can conceal a leak. */
    private Transport.Result credentialProbe(
            final Path helper, final int seconds, final boolean checkDescendant) throws Exception {
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
            if (checkDescendant) {
                final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(PROBE_SECONDS);
                while ((!Files.exists(pid) || Files.size(pid) == 0)
                        && process.isAlive()
                        && System.nanoTime() < deadline) {
                    Thread.sleep(10);
                }
                assertThat(pid).as("the helper recorded a real descendant").isNotEmptyFile();
                assertThat(ProcessHandle.of(Long.parseLong(Files.readString(pid))))
                        .as("the fixture child exists before the credential deadline")
                        .hasValueSatisfying(
                                child ->
                                        assertThat(child.info().command())
                                                .as("the fixture child is executing before refusal")
                                                .isPresent());
            }
            assertThat(process.waitFor(seconds, TimeUnit.SECONDS))
                    .as(
                            "credential command ends within %s seconds despite inherited or overflowing pipes",
                            seconds)
                    .isTrue();
            if (checkDescendant) {
                final var child = ProcessHandle.of(Long.parseLong(Files.readString(pid)));
                if (child.isPresent()) {
                    try {
                        child.orElseThrow().onExit().get(2, TimeUnit.SECONDS);
                    } catch (final java.util.concurrent.TimeoutException ignored) {
                        // A terminated orphan can remain a zombie until its reaper runs.
                    }
                    assertThat(child.orElseThrow().info().command())
                            .as(
                                    "the credential helper's descendant no longer executes after refusal")
                            .isEmpty();
                }
            }
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
            final long started = System.nanoTime();
            try {
                new Room(Path.of(args[0]))
                        .credential(
                                "agent",
                                8123,
                                args.length > 1
                                        ? Map.of("EXPECTED_MARKER", "supplied")
                                        : System.getenv(),
                                System.err);
                reportDuration(started);
            } catch (final Failure failure) {
                reportDuration(started);
                System.exit(Main.reportFailure("room", failure, System.err));
            }
        }

        private static void reportDuration(final long started) {
            System.err.println(
                    "probe-millis=" + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
        }
    }

    /** Harm guarded: wrong port bounds prevent vault-backed room access. */
    @ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"0, false", "65535, true"})
    void localPortBoundariesAreCheckedBeforeCredentials(final int port, final boolean accepted)
            throws Exception {
        final Map<String, String> env = Map.of("XDG_CONFIG_HOME", dir.toString());
        final Path conf = LocalHomeserver.directory(env).resolve("homeserver.conf");
        Files.createDirectories(conf.getParent());
        Files.writeString(conf, LocalHomeserver.PORT + "=" + port + "\n");
        final Path helper =
                script("cat >/dev/null\nprintf 'fixture helper reached\\n' >&2\nexit 4\n");
        final ByteArrayOutputStream err = new ByteArrayOutputStream();
        assertThatThrownBy(
                        () ->
                                new Room(helper)
                                        .run(
                                                List.of(
                                                        "read", "--entry", "agent", "--room",
                                                        "!room"),
                                                env,
                                                System.out,
                                                new PrintStream(err)))
                .isInstanceOfSatisfying(
                        Failure.class,
                        failure -> {
                            assertThat(failure.exitCode())
                                    .as("port or credential configuration refusal")
                                    .isEqualTo(Exit.CONFIG);
                            assertThat(failure.getMessage())
                                    .as("the failure identifies which boundary was reached")
                                    .contains(
                                            accepted
                                                    ? "cannot read vault entry"
                                                    : "must name a port");
                        });
        assertThat(err.toString(StandardCharsets.UTF_8).contains("fixture helper reached"))
                .as("only an accepted port reaches the vault helper")
                .isEqualTo(accepted);
    }

    /** Harm guarded: a valid credential extension locks out the account. */
    @Test
    void aOneCharacterExtensionFieldDoesNotDiscardThePassword() throws Exception {
        final Path helper =
                script("cat >/dev/null\nprintf 'x=extension\\npassword=fixture-value\\n'\n");
        assertThat(new Room(helper).credential("agent", 8123, Map.of(), System.err))
                .as("unknown nonempty keys are permitted by the credential protocol")
                .isEqualTo("fixture-value");
    }

    /** Harm guarded: CRLF termination consumes private trailing bytes. */
    @Test
    void aCrLfEmptyLineEndsTheCredentialAnswer() throws Exception {
        final Path helper =
                script(
                        "cat >/dev/null\nprintf 'username=fixture-user\\r\\npassword=fixture-value\\r\\n\\r\\n\\377'\n");
        assertThat(new Room(helper).credential("agent", 8123, Map.of(), System.err))
                .as("CRLF terminates the answer before invalid trailing bytes")
                .isEqualTo("fixture-value");
    }

    /** Harm guarded: a NUL truncates a malformed answer into a valid login. */
    @Test
    void aNulByteDoesNotActAsEndOfFile() throws Exception {
        final Path helper =
                script("cat >/dev/null\nprintf 'password=fixture-value\\n\\000broken\\n'\n");
        assertThatThrownBy(() -> new Room(helper).credential("agent", 8123, Map.of(), System.err))
                .isInstanceOfSatisfying(
                        Failure.class,
                        failure -> {
                            assertThat(failure.exitCode())
                                    .as("malformed helper output is refused")
                                    .isEqualTo(Exit.CONFIG);
                            assertThat(failure.getMessage())
                                    .as("helper output remains private")
                                    .isEqualTo("cannot read vault entry 'agent'");
                        });
    }

    /** Harm guarded: the vault helper inherits secrets or loses its environment. */
    @Test
    void theCredentialHelperReceivesOnlyTheExplicitEnvironment() throws Exception {
        final Path helper =
                script(
                        "cat >/dev/null\ntest -z \"${INHERITED_MARKER+x}\" && test \"$EXPECTED_MARKER\" = supplied || exit 9\nprintf 'password=fixture-value\\n'\n");
        final Path err = dir.resolve("environment.err");
        final ProcessBuilder builder =
                new ProcessBuilder(
                                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                                "-cp",
                                System.getProperty("surefire.test.class.path"),
                                CredentialProbe.class.getName(),
                                helper.toString(),
                                "explicit-environment")
                        .redirectError(err.toFile())
                        .redirectOutput(ProcessBuilder.Redirect.DISCARD);
        builder.environment().clear();
        builder.environment().put("INHERITED_MARKER", "must-not-reach-helper");
        final Process process = builder.start();
        try {
            assertThat(process.waitFor(5, TimeUnit.SECONDS))
                    .as("the isolated credential probe ends")
                    .isTrue();
            assertThat(process.exitValue())
                    .as(
                            "the helper accepted precisely its explicit environment: %s",
                            Files.readString(err))
                    .isEqualTo(Exit.OK);
        } finally {
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
        }
    }

    /** Harm guarded: a valid room id is rejected before vault access. */
    @Test
    void aRoomIdAtItsUtf8LimitReachesTheCredentialCheck() throws Exception {
        final String id = "!" + "é".repeat(127);
        final Map<String, String> env = Map.of("XDG_CONFIG_HOME", dir.toString());
        final Path conf = LocalHomeserver.directory(env).resolve("homeserver.conf");
        Files.createDirectories(conf.getParent());
        Files.writeString(conf, LocalHomeserver.PORT + "=8123\n");
        final Path helper = script("cat >/dev/null\nexit 4\n");
        assertThat(id.getBytes(StandardCharsets.UTF_8))
                .as("fixture occupies the complete byte limit")
                .hasSize(255);
        assertThatThrownBy(
                        () ->
                                new Room(helper)
                                        .run(
                                                List.of("read", "--entry", "agent", "--room", id),
                                                env,
                                                System.out,
                                                System.err))
                .isInstanceOfSatisfying(
                        Failure.class,
                        failure -> {
                            assertThat(failure.exitCode())
                                    .as("valid room id reaches credentials")
                                    .isEqualTo(Exit.CONFIG);
                            assertThat(failure.getMessage())
                                    .as("refusal is from the fixture vault, not argument parsing")
                                    .contains("cannot read vault entry");
                        });
    }

    /** Harm guarded: a silent vault helper hangs the room command indefinitely. */
    @Test
    void aSilentCredentialHelperCannotHangTheCaller() throws Exception {
        final Path helper =
                script("cat >/dev/null\nprintf '%s' \"$$\" > \"$CHILD_PID\"\nexec /bin/sleep 60\n");
        final Transport.Result result = credentialProbe(helper, PROBE_SECONDS);
        assertThat(result.exit())
                .as("an unresponsive helper is a bounded configuration refusal")
                .isEqualTo(Exit.CONFIG);
        assertThat(result.err())
                .as("the stalled helper is identified without credential output")
                .contains("cannot read vault entry 'agent'");
        assertThat(result.out()).as("no credential is printed").isEmpty();
    }

    /** Harm guarded: an inherited pipe hangs a credential read without a protocol terminator. */
    @Test
    void anUnterminatedInheritedCredentialPipeCannotHangTheCaller() throws Exception {
        final Path helper =
                script(
                        "cat >/dev/null\n/bin/sleep 60 &\nprintf '%s' \"$!\" > \"$CHILD_PID\"\nprintf 'password=fixture-value\\n'\nexit 0\n");
        final Transport.Result result = credentialProbe(helper, PROBE_SECONDS);
        // Whether the end of the helper reaches the reader as an end of the answer is the JDK's
        // scheduling: a whole line then counts as given, an open pipe as refused. Neither hangs.
        assertThat(result.exit()).as("the answer's end").isIn(Exit.OK, Exit.CONFIG);
        if (result.exit() == Exit.CONFIG) {
            assertThat(result.err())
                    .as("only the entry names the incomplete answer")
                    .contains("cannot read vault entry 'agent'");
        }
        assertThat(result.out()).as("partial credentials stay private").isEmpty();
        assertThat(result.err()).as("the value is never shown").doesNotContain("fixture-value");
    }

    /** Harm guarded: a timed-out vault helper leaves its child process running. */
    @Test
    void aTimedOutCredentialHelperLeavesNoRunningDescendant() throws Exception {
        final Path helper =
                script(
                        "cat >/dev/null\n/bin/sleep 60 &\nprintf '%s' \"$!\" > \"$CHILD_PID\"\nwait\n");
        final Transport.Result result = credentialProbe(helper, PROBE_SECONDS, true);
        assertThat(result.exit())
                .as("the helper that never finishes is refused")
                .isEqualTo(Exit.CONFIG);
        assertThat(result.err())
                .as("the timeout is a credential refusal")
                .contains("cannot read vault entry 'agent'");
    }
}
