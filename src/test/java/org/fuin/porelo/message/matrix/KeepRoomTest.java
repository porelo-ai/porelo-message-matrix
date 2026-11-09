package org.fuin.porelo.message.matrix;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class KeepRoomTest {

    private static final String TOKEN_INPUT =
            "{\"secrets\":{\"" + Config.ADMIN_TOKEN + "\":\"the-administrators-token\"}}";

    @TempDir private Path dir;

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();

    private final ByteArrayOutputStream err = new ByteArrayOutputStream();

    private int run(final String input, final String... args) {
        return Main.run(
                args,
                Map.of("XDG_CONFIG_HOME", dir.toString()),
                new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)),
                new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8));
    }

    private void gate() throws IOException {
        Files.createDirectories(dir.resolve("porelo/matrix"));
        Files.writeString(dir.resolve("porelo/matrix/homeserver.conf"), GateFiles.MODE + "=1\n");
    }

    private String error() {
        return err.toString(StandardCharsets.UTF_8);
    }

    private static String[] verb(final String verb) {
        return "keep-room".equals(verb)
                ? new String[] {verb, "--project", "demo", "--persons", "alice"}
                : new String[] {verb, "--project", "demo"};
    }

    /**
     * Harm guarded: on a machine with no gate a verb starts or registers a homeserver of the
     * person's, which is what expel does there.
     */
    @ParameterizedTest
    @ValueSource(strings = {"keep-room", "drop-room"})
    void withoutAGateItIsRefusedAndNothingStarts(final String verb) {
        assertThat(run(TOKEN_INPUT, verb(verb))).as(error()).isEqualTo(Exit.NOT_PERMITTED);
        assertThat(out.toString(StandardCharsets.UTF_8)).as("no answer").isEmpty();
        assertThat(dir).as("no file of a homeserver was made").isEmptyDirectory();
    }

    /** Harm guarded: a verb acts with whatever token it finds, or with none. */
    @ParameterizedTest
    @ValueSource(strings = {"keep-room", "drop-room"})
    void withoutTheTokenOnTheInputItIsRefused(final String verb) throws IOException {
        gate();
        assertThat(run("{\"secrets\":{}}", verb(verb))).as(error()).isEqualTo(Exit.CONFIG);
        assertThat(error()).as("says what it needs").contains("administrator's token");
    }

    /** Harm guarded: the project without a conversation gets a room made for it. */
    @Test
    void theDefaultProjectHasNoRoom() throws IOException {
        gate();
        final String[] args = verb("keep-room");
        args[2] = Lifecycle.DEFAULT_PROJECT;
        assertThat(run(TOKEN_INPUT, args)).as(error()).isEqualTo(Exit.CONFIG);
        assertThat(error()).as("says why").contains("no conversation");
    }

    /** Harm guarded: a list mistyped by the helper drops or invites the wrong account. */
    @Test
    void aPersonWhoCannotBeALoginIsRefusedBeforeAnything() throws IOException {
        gate();
        for (final String persons : List.of("porelo", "alice,", "porelo-x", "a.b", "Alice")) {
            assertThat(run(TOKEN_INPUT, "keep-room", "--project", "demo", "--persons", persons))
                    .as(persons)
                    .isEqualTo(Exit.CONFIG);
            assertThat(error()).as("%s: why", persons).contains("a login must begin");
            err.reset();
        }
    }

    /** Harm guarded: an alias under a name no project has reaches the homeserver. */
    @Test
    void aNameNoProjectCanHaveIsRefused() throws IOException {
        gate();
        assertThat(run(TOKEN_INPUT, "drop-room", "--project", "../other"))
                .as(error())
                .isEqualTo(Exit.CONFIG);
        assertThat(error()).as("says why").contains("is no project's name");
    }

    /** Harm guarded: a usage mistake is taken for an empty list of persons. */
    @Test
    void aMissingListIsAUsageError() {
        assertThat(run(TOKEN_INPUT, "keep-room", "--project", "demo"))
                .as(error())
                .isEqualTo(Exit.USAGE);
    }

    /**
     * Harm guarded: a list far beyond a room's size reaches the homeserver one invite at a time.
     */
    @Test
    void aListTooLongIsAUsageError() throws IOException {
        gate();
        final String list =
                java.util.stream.IntStream.rangeClosed(1, 501)
                        .mapToObj(i -> "host" + i)
                        .collect(java.util.stream.Collectors.joining(","));
        assertThat(run(TOKEN_INPUT, "keep-room", "--project", "demo", "--persons", list))
                .as(error())
                .isEqualTo(Exit.USAGE);
    }

    /**
     * Harm guarded: the same list changes the room, or dropping a login takes out the administrator
     * or a kept login's account.
     */
    @Test
    void theListInvitesTheMissingAndDropsOnlyWhoItNoLongerNames() throws Exception {
        try (FakeHomeserver homeserver = new FakeHomeserver()) {
            final MatrixClient admin =
                    new MatrixClient(new Config(URI.create(homeserver.url()), "token", null, true));
            final Map<String, String> members = new LinkedHashMap<>();
            members.put("@porelo:example.org", "join");
            members.put("@bob:example.org", "join");
            members.put("@porelo.bob:example.org", "invite");
            members.put("@task.part.bob:example.org", "join");
            members.put("@t.x-bob:example.org", "join");
            homeserver.answer(200, membership(members).toString());
            for (int i = 0; i < 2; i++) {
                homeserver.answer(200, "{}");
            }
            for (final String kicked : List.of("@porelo.bob", "@bob", "@task.part.bob")) {
                members.put(kicked + ":example.org", "leave");
                homeserver.answer(200, "{}");
            }
            homeserver.answer(200, membership(members).toString());

            final var answer =
                    KeepRoom.keepTo(
                            admin, "!r", "#porelo-p:example.org", "example.org", List.of("x-bob"));

            assertThat(answer.path("invited"))
                    .as("the kept login's two accounts")
                    .extracting(com.fasterxml.jackson.databind.JsonNode::asText)
                    .containsExactly("@x-bob:example.org", "@porelo.x-bob:example.org");
            assertThat(answer.path("removed"))
                    .as("bob alone, provisioning first; neither the administrator nor x-bob's")
                    .extracting(com.fasterxml.jackson.databind.JsonNode::asText)
                    .containsExactly(
                            "@porelo.bob:example.org",
                            "@bob:example.org",
                            "@task.part.bob:example.org");
        }
    }

    /** Harm guarded: someone who cannot be taken out keeps the helper in a loop for good. */
    @Test
    void whoStaysInAfterTheLastRoundIsATemporaryFailure() throws Exception {
        try (FakeHomeserver homeserver = new FakeHomeserver()) {
            final MatrixClient admin =
                    new MatrixClient(new Config(URI.create(homeserver.url()), "token", null, true));
            final Map<String, String> members = new LinkedHashMap<>();
            members.put("@porelo:example.org", "join");
            members.put("@bob:example.org", "join");
            homeserver.answer(200, membership(members).toString());
            for (int round = 0; round < 5; round++) {
                homeserver.answer(200, "{}").answer(200, membership(members).toString());
            }

            assertThatThrownBy(
                            () ->
                                    KeepRoom.keepTo(
                                            admin,
                                            "!r",
                                            "#porelo-p:example.org",
                                            "example.org",
                                            List.of()))
                    .isInstanceOfSatisfying(
                            Failure.class,
                            failure -> assertThat(failure.exitCode()).isEqualTo(Exit.TEMPORARY))
                    .hasMessageContaining("after 5 rounds");
        }
    }

    /**
     * Harm guarded: an account is named on a run refused for a ban, or named after its invitation
     * went out, so the invitation shows the Matrix id.
     */
    @Test
    void aNameIsGivenAfterTheBanCheckAndBeforeTheInvitation(@TempDir final Path home)
            throws Exception {
        Files.createDirectories(home.resolve("gate"));
        try (FakeHomeserver homeserver = new FakeHomeserver()) {
            final MatrixClient admin =
                    new MatrixClient(new Config(URI.create(homeserver.url()), "token", null, true));
            homeserver.answer(200, membership(Map.of("@ada:example.org", "ban")).toString());

            assertThatThrownBy(
                            () ->
                                    KeepRoom.keepTo(
                                            admin,
                                            "!r",
                                            "#porelo-p:example.org",
                                            "example.org",
                                            List.of("ada"),
                                            Map.of("ada", "Ada"),
                                            home))
                    .as("a banned login")
                    .isInstanceOf(Failure.class);
            assertThat(homeserver.requests())
                    .as("nothing named on a refused run")
                    .noneMatch(r -> r.rawPath().contains("/_synapse/"));

            homeserver.clearAnswers();
            homeserver
                    .answer(200, membership(Map.of()).toString())
                    .answer(200, "{\"available\":true}")
                    .answer(200, "{}")
                    .answer(200, "{}")
                    .answer(200, "{}")
                    .answer(200, membership(Map.of()).toString());
            KeepRoom.keepTo(
                    admin,
                    "!r",
                    "#porelo-p:example.org",
                    "example.org",
                    List.of("ada"),
                    Map.of("ada", "Ada"),
                    home);

            final List<String> writes =
                    homeserver.requests().stream()
                            .filter(r -> !r.method().equals("GET"))
                            .map(r -> r.method() + " " + r.rawPath())
                            .toList();
            assertThat(writes)
                    .as("made with the name first, then both invitations")
                    .startsWith(
                            "PUT /_synapse/admin/v2/users/%40ada%3Aexample.org",
                            "POST /_matrix/client/v3/rooms/%21r/invite");
        }
    }

    private static com.fasterxml.jackson.databind.JsonNode membership(
            final Map<String, String> members) {
        final var answer = MatrixClient.JSON.createObjectNode();
        final var chunk = answer.putArray("chunk");
        members.forEach(
                (id, membership) ->
                        chunk.addObject()
                                .put("state_key", id)
                                .putObject("content")
                                .put("membership", membership));
        return answer;
    }

    /** Harm guarded: a valid membership list cannot reconcile. */
    @Test
    void exactlyFiveHundredPersonsReachTheCredentialCheck() throws Exception {
        gate();
        final String persons =
                java.util.stream.IntStream.rangeClosed(1, 500)
                        .mapToObj(i -> "host" + i)
                        .collect(java.util.stream.Collectors.joining(","));
        assertThat(run("{\"secrets\":{}}", "keep-room", "--project", "demo", "--persons", persons))
                .as("valid maximum-size list reaches the missing-token check")
                .isEqualTo(Exit.CONFIG);
        assertThat(error())
                .as("the list was accepted before credential validation")
                .contains("administrator's token");
    }
}
