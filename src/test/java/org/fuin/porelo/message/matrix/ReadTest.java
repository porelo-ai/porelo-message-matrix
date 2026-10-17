package org.fuin.porelo.message.matrix;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * {@code read}'s refusals, and where it posts. That the sender then sees it is proven in {@code
 * ReceiptIT}.
 */
class ReadTest {

    private static final String EVENT = "$e1";

    private FakeHomeserver homeserver;

    private final ByteArrayOutputStream errBytes = new ByteArrayOutputStream();

    @BeforeEach
    void setUp() throws IOException {
        homeserver = new FakeHomeserver();
    }

    @AfterEach
    void tearDown() {
        homeserver.close();
    }

    private int read(final String... args) {
        final String[] all = new String[args.length + 1];
        all[0] = "read";
        System.arraycopy(args, 0, all, 1, args.length);
        return Main.run(
                all,
                Map.of(
                        Config.HOMESERVER,
                        homeserver.url(),
                        Config.ACCESS_TOKEN,
                        "t",
                        "XDG_STATE_HOME",
                        System.getProperty("java.io.tmpdir") + "/no-refs-here"),
                new PrintStream(OutputStream.nullOutputStream()),
                new PrintStream(errBytes, true, StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("It posts the read receipt for the event in the room the event says it is in")
    void postsTheReceipt() {
        homeserver
                .answer(200, "{\"joined_rooms\":[\"!admins:x\",\"!project\"]}")
                .answer(200, "{\"event_id\":\"$e1\",\"room_id\":\"!project\"}")
                .answer(200, "{\"event_id\":\"$e1\",\"room_id\":\"!project\"}")
                .answer(200, "{}");

        assertThat(read(EVENT)).as(errBytes.toString(StandardCharsets.UTF_8)).isEqualTo(Exit.OK);

        assertThat(homeserver.requests().getLast())
                .satisfies(
                        request -> {
                            assertThat(request.method()).isEqualTo("POST");
                            assertThat(request.rawPath())
                                    .isEqualTo(
                                            "/_matrix/client/v3/rooms/%21project/receipt/m.read/%24e1");
                        });
    }

    /**
     * Harm guarded: Porelo names a message by the file the poll wrote, a person's too; a name not
     * taken ends {@code read} with 64, and the person never sees their message read.
     */
    @ParameterizedTest(name = "a person''s: {0}")
    @ValueSource(booleans = {false, true})
    @DisplayName(
            "A name poll gave a message, a task's or a person's, stands for the event it recorded")
    void referenceByName(final boolean person, @TempDir final java.nio.file.Path state)
            throws Exception {
        final String name =
                person
                        ? Reference.ofPerson(EVENT, 1_790_000_000_000L)
                        : Reference.of(EVENT, 1_790_000_000_000L);
        Reference.remember(
                Poll.stateDirectory(Map.of("XDG_STATE_HOME", state.toString())), name, EVENT);
        homeserver
                .answer(200, "{\"joined_rooms\":[\"!project\"]}")
                .answer(200, "{\"event_id\":\"$e1\",\"room_id\":\"!project\"}")
                .answer(200, "{}");

        final int exit =
                Main.run(
                        new String[] {"read", name},
                        Map.of(
                                Config.HOMESERVER,
                                homeserver.url(),
                                Config.ACCESS_TOKEN,
                                "t",
                                "XDG_STATE_HOME",
                                state.toString()),
                        new PrintStream(OutputStream.nullOutputStream()),
                        new PrintStream(errBytes, true, StandardCharsets.UTF_8));

        assertThat(exit).as(errBytes.toString(StandardCharsets.UTF_8)).isEqualTo(Exit.OK);
        assertThat(homeserver.requests().getLast().rawPath()).endsWith("/receipt/m.read/%24e1");
    }

    @Test
    @DisplayName("A name no poll recorded here is 77: which event is meant cannot be told")
    void unknownName() {
        assertThat(read(Reference.of(EVENT, 1_790_000_000_000L))).isEqualTo(Exit.NOT_PERMITTED);
        assertThat(homeserver.requests()).isEmpty();
    }

    @Test
    @DisplayName("An event no room of the account holds is 77, and nothing is posted")
    void noSuchEvent() {
        homeserver
                .answer(200, "{\"joined_rooms\":[\"!project\"]}")
                .answer(404, "{\"errcode\":\"M_NOT_FOUND\"}");

        assertThat(read(EVENT)).isEqualTo(Exit.NOT_PERMITTED);
        assertThat(homeserver.requests())
                .extracting(FakeHomeserver.Request::method)
                .doesNotContain("POST");
    }

    @Test
    @DisplayName("A homeserver that cannot take it now is 75")
    void temporary() {
        homeserver
                .answer(200, "{\"joined_rooms\":[\"!project\"]}")
                .answer(200, "{\"event_id\":\"$e1\",\"room_id\":\"!project\"}")
                .answer(503, "{}");

        assertThat(read(EVENT)).isEqualTo(Exit.TEMPORARY);
    }

    @Test
    @DisplayName("Arguments not in the contract's shape are 64, and nothing is asked")
    void wrongArguments() {
        assertThat(read()).isEqualTo(Exit.USAGE);
        assertThat(read("$e1 not an id")).isEqualTo(Exit.USAGE);
        assertThat(read(EVENT, "extra")).isEqualTo(Exit.USAGE);
        assertThat(homeserver.requests()).isEmpty();
    }
}
