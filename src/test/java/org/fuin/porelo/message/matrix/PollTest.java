package org.fuin.porelo.message.matrix;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code poll}'s refusals, against a homeserver that answers what each test needs. What it delivers
 * is proven against the real one in {@code PollIT}.
 */
class PollTest {

    private static final String TOKEN = "syt_test_token_that_must_never_be_printed";

    private static final String WHOAMI = "{\"user_id\":\"@bob:matrix.test\"}";

    private static final String JOINED = "{\"joined_rooms\":[\"!room:matrix.test\"]}";

    @TempDir private Path dir;

    private FakeHomeserver homeserver;

    private Path inbound;

    private final ByteArrayOutputStream errBytes = new ByteArrayOutputStream();

    @BeforeEach
    void setUp() throws IOException {
        homeserver = new FakeHomeserver();
        inbound = Files.createDirectories(dir.resolve("inbound"));
    }

    @AfterEach
    void tearDown() {
        homeserver.close();
    }

    private Map<String, String> env() {
        final Map<String, String> env = new HashMap<>();
        env.put(Config.HOMESERVER, homeserver.url());
        env.put(Config.ACCESS_TOKEN, TOKEN);
        env.put("XDG_STATE_HOME", dir.resolve("state").toString());
        return env;
    }

    private int poll(final Map<String, String> env, final String... args) {
        final String[] all = new String[args.length + 1];
        all[0] = "poll";
        System.arraycopy(args, 0, all, 1, args.length);
        return Main.run(
                all,
                env,
                new PrintStream(OutputStream.nullOutputStream()),
                new PrintStream(errBytes, true, StandardCharsets.UTF_8));
    }

    private int poll() {
        return poll(env(), "--into", inbound.toString());
    }

    private String stderr() {
        return errBytes.toString(StandardCharsets.UTF_8);
    }

    private long filesIn(final Path directory) throws IOException {
        try (Stream<Path> files = Files.list(directory)) {
            return files.count();
        }
    }

    @Test
    @DisplayName("Arguments not in the contract's shape are 64, and nothing is asked")
    void wrongArguments() {
        assertThat(poll(env())).isEqualTo(Exit.USAGE);
        assertThat(poll(env(), "--to", inbound.toString())).isEqualTo(Exit.USAGE);
        assertThat(poll(env(), "--into", dir.resolve("missing").toString())).isEqualTo(Exit.USAGE);
        for (final String[] wrong :
                new String[][] {
                    {"--wait"},
                    {"--wait", "0"},
                    {"--wait", "31"},
                    {"--wait", "x"},
                    {"--persons", "--direct"},
                    {"--persons", "--persons"},
                    {"--wait", "5", "--wait", "5"},
                    {"--later"}
                }) {
            final String[] args = new String[wrong.length + 2];
            args[0] = "--into";
            args[1] = inbound.toString();
            System.arraycopy(wrong, 0, args, 2, wrong.length);
            assertThat(poll(env(), args)).as(String.join(" ", wrong)).isEqualTo(Exit.USAGE);
        }
        assertThat(homeserver.requests()).isEmpty();
    }

    @Test
    @DisplayName("A token the homeserver refuses is 77, with its reason and never the token")
    void refusedToken() {
        homeserver.answer(401, "{\"errcode\":\"M_UNKNOWN_TOKEN\",\"error\":\"unknown\"}");

        assertThat(poll()).isEqualTo(Exit.NOT_PERMITTED);
        assertThat(stderr()).contains("M_UNKNOWN_TOKEN").doesNotContain(TOKEN);
    }

    @Test
    @DisplayName("An unreachable or failing homeserver is 75, and nothing is written")
    void temporary() throws IOException {
        homeserver.answer(200, WHOAMI).answer(503, "{}");

        assertThat(poll()).isEqualTo(Exit.TEMPORARY);
        assertThat(filesIn(inbound)).isZero();
    }

    @Test
    @DisplayName("A sync without a position to continue from is 76, and no position is saved")
    void syncWithoutNextBatch() throws IOException {
        homeserver.answer(200, WHOAMI).answer(200, JOINED).answer(200, "{\"rooms\":{}}");

        assertThat(poll()).isEqualTo(Exit.PROTOCOL);
        try (Stream<Path> files = Files.list(dir.resolve("state/porelo/transports/matrix"))) {
            assertThat(files.map(f -> f.getFileName().toString()))
                    .noneMatch(name -> name.endsWith(".since"));
        }
    }

    @Test
    @DisplayName("While another poll for the same account runs, it is 75, so Porelo tries again")
    void anotherPollRuns() throws IOException {
        homeserver.answer(200, WHOAMI).answer(200, JOINED).answer(200, "{\"next_batch\":\"s1\"}");
        assertThat(poll()).as(stderr()).isEqualTo(Exit.OK);
        final Path lockFile;
        try (Stream<Path> files = Files.list(dir.resolve("state/porelo/transports/matrix"))) {
            lockFile = files.filter(PollTest::accountLock).findFirst().orElseThrow();
        }
        homeserver.answer(200, WHOAMI);

        try (FileChannel channel = FileChannel.open(lockFile, StandardOpenOption.WRITE);
                FileLock held = channel.lock()) {
            assertThat(poll()).isEqualTo(Exit.TEMPORARY);
        }
        assertThat(stderr()).contains("another poll for this account is running");
    }

    /**
     * Harm guarded: two polls of one account at once - two porelod, or a poll started by hand -
     * both write the position, and a message is delivered twice or the position goes back. The lock
     * is held by another process here, as it would be, not by this one.
     */
    @Test
    @DisplayName("While a poll in another process holds the account, it is 75")
    void anotherProcessPolls() throws Exception {
        homeserver.answer(200, WHOAMI).answer(200, JOINED).answer(200, "{\"next_batch\":\"s1\"}");
        assertThat(poll()).as(stderr()).isEqualTo(Exit.OK);
        final Path lockFile;
        try (Stream<Path> files = Files.list(dir.resolve("state/porelo/transports/matrix"))) {
            lockFile = files.filter(PollTest::accountLock).findFirst().orElseThrow();
        }
        homeserver.answer(200, WHOAMI);
        final Process holder =
                new ProcessBuilder(
                                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                                "-cp",
                                System.getProperty("java.class.path"),
                                LockHolder.class.getName(),
                                lockFile.toString())
                        .redirectError(ProcessBuilder.Redirect.INHERIT)
                        .start();
        try {
            final java.io.BufferedReader said =
                    new java.io.BufferedReader(
                            new java.io.InputStreamReader(
                                    holder.getInputStream(), StandardCharsets.UTF_8));
            assertThat(said.readLine()).as("the other process").isEqualTo("held");

            assertThat(poll()).as(stderr()).isEqualTo(Exit.TEMPORARY);
        } finally {
            holder.getOutputStream().close();
            holder.waitFor();
        }
        assertThat(stderr()).contains("another poll for this account is running");
    }

    /**
     * Harm guarded: a backup that hangs while it holds the positions would hang every poll of the
     * account, and Porelo would wait on each without end. The backup's lock is held by another
     * process here, as a running backup would hold it.
     */
    @Test
    @DisplayName("While a backup holds the positions, the poll is 75 at once rather than waiting")
    void aBackupHoldsThePositions() throws Exception {
        homeserver.answer(200, WHOAMI).answer(200, JOINED).answer(200, "{\"next_batch\":\"s1\"}");
        assertThat(poll()).as(stderr()).isEqualTo(Exit.OK);
        final Path keepLock = dir.resolve("state/porelo/transports/matrix").resolve(Keep.LOCK);
        assertThat(keepLock).as("the lock every poll shares with a backup").exists();
        homeserver.answer(200, WHOAMI);
        final Process holder =
                new ProcessBuilder(
                                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                                "-cp",
                                System.getProperty("java.class.path"),
                                LockHolder.class.getName(),
                                keepLock.toString())
                        .redirectError(ProcessBuilder.Redirect.INHERIT)
                        .start();
        try {
            final java.io.BufferedReader said =
                    new java.io.BufferedReader(
                            new java.io.InputStreamReader(
                                    holder.getInputStream(), StandardCharsets.UTF_8));
            assertThat(said.readLine()).as("the other process").isEqualTo("held");

            final int exit = assertTimeoutPreemptively(Duration.ofSeconds(20), () -> poll());
            assertThat(exit).as(stderr()).isEqualTo(Exit.TEMPORARY);
        } finally {
            holder.getOutputStream().close();
            holder.waitFor();
        }
        assertThat(stderr()).contains("a backup is copying the poll positions");
    }

    /**
     * Harm guarded: a new poll must not deliver or advance its position ahead of a waiting backup.
     */
    @Test
    @Timeout(15)
    void aBackupHoldingWantedRefusesThePollAndTheNextPollDelivers() throws Exception {
        final String sync =
                """
                {"next_batch":"s1","rooms":{"join":{"!room:matrix.test":{"timeline":{"events":[
                  {"event_id":"$waiting","origin_server_ts":1000,"sender":"@alice:matrix.test",
                   "type":"m.room.message","content":{"org.fuin.porelo.signature":"c2ln",
                   "org.fuin.porelo.message":"message held for the next poll"}}
                ]}}}}}
                """;
        final Map<String, String> env = env();
        final Path state = Files.createDirectories(Poll.stateDirectory(env));
        final Path wanted = Files.createFile(state.resolve(Keep.WANTED));
        final Process backup =
                new ProcessBuilder(
                                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                                "-cp",
                                System.getProperty("java.class.path"),
                                LockHolder.class.getName(),
                                wanted.toString())
                        .redirectError(ProcessBuilder.Redirect.INHERIT)
                        .start();
        try {
            assertThat(backup.inputReader(StandardCharsets.UTF_8).readLine())
                    .as("the backup holds keep.wanted while keep.lock is available")
                    .isEqualTo("held");
            homeserver.answer(200, WHOAMI).answer(200, JOINED).answer(200, sync);

            final int exit =
                    assertTimeoutPreemptively(
                            Duration.ofSeconds(1), () -> poll(env, "--into", inbound.toString()));
            assertThat(exit)
                    .as(
                            "the real poll command must honor the waiting backup's wanted gate: %s",
                            stderr())
                    .isEqualTo(Exit.TEMPORARY);
            assertThat(filesIn(inbound))
                    .as("a refused poll delivers no message or sidecar")
                    .isZero();
            assertThat(state.resolve(Poll.key(Config.from(env), "@bob:matrix.test") + ".since"))
                    .as("a refused poll must not advance the position")
                    .doesNotExist();
            assertThat(homeserver.requests())
                    .as("a refused poll asks only whose token it has")
                    .extracting(FakeHomeserver.Request::rawPath)
                    .containsExactly("/_matrix/client/v3/account/whoami");
        } finally {
            backup.getOutputStream().close();
            if (!backup.waitFor(5, TimeUnit.SECONDS)) {
                backup.destroyForcibly();
                assertThat(backup.waitFor(5, TimeUnit.SECONDS))
                        .as("the backup lock holder must be reaped")
                        .isTrue();
            }
        }
        homeserver.clearAnswers();
        homeserver.answer(200, WHOAMI).answer(200, JOINED).answer(200, sync);

        assertThat(poll(env, "--into", inbound.toString()))
                .as("the next poll succeeds once the backup has finished: %s", stderr())
                .isEqualTo(Exit.OK);
        final Path message = inbound.resolve(Reference.of("$waiting", 1000) + ".json");
        assertThat(message)
                .as("the refused poll leaves the message available to the next poll")
                .hasContent("message held for the next poll");
        assertThat(Path.of(message + ".sig"))
                .as("the signature arrives with the message")
                .hasContent("sig");
    }

    /** The account's own lock, never the one every poll shares with a backup. */
    private static boolean accountLock(final Path file) {
        final String name = file.getFileName().toString();
        return name.endsWith(".lock") && !name.equals(Keep.LOCK);
    }

    @Test
    @DisplayName("Without XDG_STATE_HOME or HOME there is nowhere to keep the position: 78")
    void noStateDirectory() {
        homeserver.answer(200, WHOAMI);
        final Map<String, String> env = env();
        env.remove("XDG_STATE_HOME");
        env.remove("HOME");

        assertThat(poll(env, "--into", inbound.toString())).isEqualTo(Exit.CONFIG);
    }

    @Test
    @DisplayName("The position is kept under XDG_STATE_HOME, and sent back on the next sync")
    void positionIsKept() {
        homeserver
                .answer(200, WHOAMI)
                .answer(200, JOINED)
                .answer(200, "{\"next_batch\":\"s42\"}")
                .answer(200, WHOAMI)
                .answer(200, JOINED)
                .answer(200, "{\"next_batch\":\"s43\"}");

        assertThat(poll()).as(stderr()).isEqualTo(Exit.OK);
        assertThat(poll()).as(stderr()).isEqualTo(Exit.OK);

        assertThat(homeserver.requests().get(1).rawPath())
                .isEqualTo("/_matrix/client/v3/joined_rooms");
        assertThat(homeserver.requests().get(2).rawPath()).isEqualTo("/_matrix/client/v3/sync");
        assertThat(homeserver.requests())
                .extracting(FakeHomeserver.Request::query)
                .satisfies(
                        queries -> {
                            assertThat(queries.get(2)).doesNotContain("since=");
                            assertThat(queries.get(5)).contains("since=s42");
                        });
    }

    /**
     * Harm guarded: a central relay's poll hands another person's task's text over as a person's,
     * because the mark in its secrets never reaches the check.
     */
    @Test
    void aCentralPollTakesOnlyAPersonsOwnAccountForAPerson() throws IOException {
        homeserver
                .answer(200, "{\"user_id\":\"@porelo-relay-p.alice:matrix.test\"}")
                .answer(200, JOINED)
                .answer(
                        200,
                        """
                        {"next_batch":"s1","rooms":{"join":{"!room:matrix.test":{"timeline":{
                         "limited":false,"events":[
                          {"type":"m.room.message","event_id":"$1","sender":"@t.bob:matrix.test",
                           "origin_server_ts":1790000000000,"content":{"msgtype":"m.text","body":"a"}},
                          {"type":"m.room.message","event_id":"$2","sender":"@carol:matrix.test",
                           "origin_server_ts":1790000000001,"content":{"msgtype":"m.text","body":"b"}}
                        ]}}}}}
                        """);
        final Map<String, String> env = env();
        env.put(Config.LOGIN, "alice");

        assertThat(poll(env, "--into", inbound.toString(), "--persons")).isEqualTo(Exit.OK);

        assertThat(filesIn(inbound)).as("only the person's words are handed over").isEqualTo(1);
        assertThat(stderr()).as("the task's words are named").contains("@t.bob:matrix.test");
    }
}
