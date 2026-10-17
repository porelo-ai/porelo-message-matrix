package org.fuin.porelo.message.matrix;

import static org.assertj.core.api.Assertions.assertThat;

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
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code poll}'s refusals, against a homeserver that answers what each test needs. What it delivers is
 * proven against the real one in {@code PollIT}.
 */
class PollTest {

    private static final String TOKEN = "syt_test_token_that_must_never_be_printed";

    private static final String WHOAMI = "{\"user_id\":\"@bob:matrix.test\"}";

    private static final String JOINED = "{\"joined_rooms\":[\"!room:matrix.test\"]}";

    @TempDir
    private Path dir;

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
        return Main.run(all, env, new PrintStream(OutputStream.nullOutputStream()), new PrintStream(errBytes, true, StandardCharsets.UTF_8));
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
        for (final String[] wrong : new String[][] {{"--wait"}, {"--wait", "0"}, {"--wait", "31"}, {"--wait", "x"},
                {"--persons", "--direct"}, {"--persons", "--persons"}, {"--wait", "5", "--wait", "5"}, {"--later"}}) {
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
            assertThat(files.map(f -> f.getFileName().toString())).noneMatch(name -> name.endsWith(".since"));
        }
    }

    @Test
    @DisplayName("While another poll for the same account runs, it is 75, so Porelo tries again")
    void anotherPollRuns() throws IOException {
        homeserver.answer(200, WHOAMI).answer(200, JOINED).answer(200, "{\"next_batch\":\"s1\"}");
        assertThat(poll()).as(stderr()).isEqualTo(Exit.OK);
        final Path lockFile;
        try (Stream<Path> files = Files.list(dir.resolve("state/porelo/transports/matrix"))) {
            lockFile = files.filter(f -> f.toString().endsWith(".lock")).findFirst().orElseThrow();
        }
        homeserver.answer(200, WHOAMI);

        try (FileChannel channel = FileChannel.open(lockFile, StandardOpenOption.WRITE);
                FileLock held = channel.lock()) {
            assertThat(poll()).isEqualTo(Exit.TEMPORARY);
        }
        assertThat(stderr()).contains("another poll for this account is running");
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
        homeserver.answer(200, WHOAMI).answer(200, JOINED).answer(200, "{\"next_batch\":\"s42\"}")
                .answer(200, WHOAMI).answer(200, JOINED).answer(200, "{\"next_batch\":\"s43\"}");

        assertThat(poll()).as(stderr()).isEqualTo(Exit.OK);
        assertThat(poll()).as(stderr()).isEqualTo(Exit.OK);

        assertThat(homeserver.requests().get(1).rawPath()).isEqualTo("/_matrix/client/v3/joined_rooms");
        assertThat(homeserver.requests().get(2).rawPath()).isEqualTo("/_matrix/client/v3/sync");
        assertThat(homeserver.requests()).extracting(FakeHomeserver.Request::query)
                .satisfies(queries -> {
                    assertThat(queries.get(2)).doesNotContain("since=");
                    assertThat(queries.get(5)).contains("since=s42");
                });
    }

}
