package org.fuin.porelo.message.matrix;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.PrintStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Harm guarded: a person shows in the room as their whole Matrix id though the host gave a name, a
 * name lands on another login's account, or a name a person chose is overwritten.
 */
class NamesTest {

    private static final String NOT_FOUND = "{\"errcode\":\"M_NOT_FOUND\",\"error\":\"none\"}";

    private static final String IN_USE = "{\"errcode\":\"M_USER_IN_USE\",\"error\":\"taken\"}";

    @TempDir private Path dir;

    private static InputStream settings(final String json) {
        return new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void aNameMayHoldAnyCharacterADisplayMayAndGoesOnlyToItsLogin() throws Failure {
        final Map<String, String> names =
                Names.read(
                        settings("{\"names\":{\"ada\":\"Lovelace, Ada\",\"bea\":\"bea=Mallory\"}}"),
                        List.of("ada", "bea", "cid"));

        assertThat(new TreeMap<>(names))
                .as("a comma or = stays inside the one login's name")
                .containsExactly(
                        Map.entry("ada", "Lovelace, Ada"), Map.entry("bea", "bea=Mallory"));
        assertThat(Names.read(settings("{}"), List.of("ada")))
                .as("no names: today's behavior")
                .isEmpty();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "{\"names\":{\"carol\":\"Carol\"}}",
                "{\"names\":[\"ada\"]}",
                "{\"names\":{\"ada\":1}}",
                "{\"names\":{},\"homeserver\":\"x\"}",
                "[]",
                "[1]",
                "1",
                "null",
                "\"ada\"",
                "{}\n{\"names\":{\"ada\":\"Ada\"}}",
                "{\"names\":{\"ada\":\"Ada\"}} trailing"
            })
    void namesForNoListedLoginOrOfAnotherShapeAreRefused(final String json) {
        assertThatThrownBy(() -> Names.read(settings(json), List.of("ada")))
                .as(json)
                .isInstanceOfSatisfying(
                        Failure.class,
                        failure -> assertThat(failure.exitCode()).isEqualTo(Exit.CONFIG));
    }

    @Test
    void settingsLongerThanTheInputMayBeAreRefusedNotCut() {
        final String json = "{}" + " ".repeat(Input.LIMIT);

        assertThatThrownBy(() -> Names.read(settings(json), List.of("ada")))
                .isInstanceOfSatisfying(
                        Failure.class,
                        failure -> assertThat(failure.exitCode()).isEqualTo(Exit.CONFIG));
    }

    @Test
    void aNameForAnUnlistedLoginSaysWhoseButNeverAKeyThatIsNoLogin() {
        assertThatThrownBy(
                        () -> Names.read(settings("{\"names\":{\"carol\":\"C\"}}"), List.of("ada")))
                .hasMessageContaining("carol");
        assertThatThrownBy(
                        () ->
                                Names.read(
                                        settings("{\"names\":{\"syt_secret\":\"C\"}}"),
                                        List.of("ada")))
                .hasMessageNotContaining("syt_secret");
    }

    @Test
    void aNameOf64CharactersIsTakenAndOf65Refused() throws Failure {
        final String longest = "A".repeat(64);
        assertThat(
                        Names.read(
                                settings("{\"names\":{\"ada\":\"" + longest + "\"}}"),
                                List.of("ada")))
                .containsEntry("ada", longest);
        assertThatThrownBy(
                        () ->
                                Names.read(
                                        settings("{\"names\":{\"ada\":\"" + longest + "B\"}}"),
                                        List.of("ada")))
                .isInstanceOfSatisfying(
                        Failure.class,
                        failure -> assertThat(failure.exitCode()).isEqualTo(Exit.USAGE));
    }

    @ParameterizedTest
    @ValueSource(strings = {"Ada: admin", "@ada", "Ad\u0007a", "A‮da", "", "  "})
    void aNameBreakingTheDisplayRuleIsRefusedAndNeverRepeated(final String name) {
        final String json =
                MatrixClient.JSON
                        .createObjectNode()
                        .set("names", MatrixClient.JSON.createObjectNode().put("ada", name))
                        .toString();

        assertThatThrownBy(() -> Names.read(settings(json), List.of("ada")))
                .as("'%s'", name)
                .isInstanceOfSatisfying(
                        Failure.class,
                        failure -> {
                            assertThat(failure.exitCode()).isEqualTo(Exit.USAGE);
                            assertThat(failure.getMessage())
                                    .contains("ada")
                                    .doesNotContain("--display");
                            if (!name.isBlank()) {
                                assertThat(failure.getMessage()).doesNotContain(name);
                            }
                        });
    }

    /** Harm guarded: a bad name found only after the room was changed. */
    @Test
    void aBadNameStopsKeepRoomBeforeTheGateIsEvenLookedFor() {
        final ByteArrayOutputStream err = new ByteArrayOutputStream();
        final int exit =
                Main.run(
                        new String[] {"keep-room", "--project", "demo", "--persons", "ada"},
                        Map.of("XDG_CONFIG_HOME", dir.toString()),
                        settings(
                                "{\"settings\":{\"names\":{\"ada\":\"Ada: x\"}},\"secrets\":{\""
                                        + Config.ADMIN_TOKEN
                                        + "\":\"syt_admin_secret\"}}"),
                        new PrintStream(new ByteArrayOutputStream()),
                        new PrintStream(err, true, StandardCharsets.UTF_8));

        assertThat(exit).as(err.toString(StandardCharsets.UTF_8)).isEqualTo(Exit.USAGE);
        assertThat(err.toString(StandardCharsets.UTF_8))
                .doesNotContain("Ada: x")
                .doesNotContain("syt_admin_secret");
    }

    @Test
    void eachNamedAccountGetsItsNameOnlyWhereItShowsNone() throws Exception {
        Files.createDirectories(dir.resolve("gate"));
        Files.createDirectories(dir.resolve(Expel.DIRECTORY));
        Files.createFile(dir.resolve(Expel.DIRECTORY).resolve("dan"));
        try (FakeHomeserver homeserver = new FakeHomeserver()) {
            homeserver
                    // ada: not there, made with her name.
                    .answer(200, "{\"available\":true}")
                    .answer(200, "{}")
                    // bea: there, showing no name.
                    .answer(400, IN_USE)
                    .answer(404, NOT_FOUND)
                    .answer(200, "{}")
                    // cid: there, showing an empty name.
                    .answer(200, "{\"available\":false}")
                    .answer(200, "{\"displayname\":\"\"}")
                    .answer(200, "{}")
                    // eve: there, showing her bare login, her own choice.
                    .answer(400, IN_USE)
                    .answer(200, "{\"displayname\":\"eve\"}");
            final MatrixClient admin =
                    new MatrixClient(new Config(URI.create(homeserver.url()), "admin", null, true));

            Names.give(
                    admin,
                    "example.org",
                    Map.of("ada", "Ada", "bea", "Bea", "cid", "Cid", "dan", "Dan", "eve", "Eve"),
                    dir);

            final List<String> writes =
                    homeserver.requests().stream()
                            .filter(r -> !r.method().equals("GET"))
                            .map(r -> r.method() + " " + r.rawPath() + " " + body(r.body()))
                            .toList();
            assertThat(writes)
                    .as("ada made named, bea and cid named, dan expelled and eve's own kept")
                    .containsExactly(
                            "PUT /_synapse/admin/v2/users/%40ada%3Aexample.org"
                                    + " displayname=Ada password",
                            "PUT /_synapse/admin/v2/users/%40bea%3Aexample.org displayname=Bea",
                            "PUT /_synapse/admin/v2/users/%40cid%3Aexample.org displayname=Cid");
            assertThat(homeserver.requests())
                    .as("nothing asked about the expelled login")
                    .noneMatch(r -> (r.rawPath() + r.query()).contains("dan"));
            assertThat(
                            homeserver.requests().stream()
                                    .filter(r -> r.method().equals("GET"))
                                    .map(r -> r.rawPath() + "?" + r.query())
                                    .toList())
                    .as("each question about its own login")
                    .containsExactly(
                            "/_synapse/admin/v1/username_available?username=ada",
                            "/_synapse/admin/v1/username_available?username=bea",
                            "/_matrix/client/v3/profile/%40bea%3Aexample.org/displayname?",
                            "/_synapse/admin/v1/username_available?username=cid",
                            "/_matrix/client/v3/profile/%40cid%3Aexample.org/displayname?",
                            "/_synapse/admin/v1/username_available?username=eve",
                            "/_matrix/client/v3/profile/%40eve%3Aexample.org/displayname?");
        }
    }

    /** Harm guarded: an account made for a login expelled while the run waited for the gate. */
    @Test
    void aLoginExpelledWhileTheGateMakesAnAccountIsNeitherMadeNorNamed() throws Exception {
        Files.createDirectories(dir.resolve("gate"));
        Files.createDirectories(dir.resolve(Expel.DIRECTORY));
        try (FakeHomeserver homeserver = new FakeHomeserver()) {
            homeserver.answer(200, "{\"available\":true}").answer(200, "{}");
            final MatrixClient admin =
                    new MatrixClient(new Config(URI.create(homeserver.url()), "admin", null, true));
            final java.util.concurrent.atomic.AtomicReference<Throwable> failed =
                    new java.util.concurrent.atomic.AtomicReference<>();
            final Thread run;
            synchronized (Gate.class) {
                run =
                        Thread.ofPlatform()
                                .start(
                                        () -> {
                                            try {
                                                Names.give(
                                                        admin,
                                                        "example.org",
                                                        Map.of("dan", "Dan"),
                                                        dir);
                                            } catch (final Throwable ex) {
                                                failed.set(ex);
                                            }
                                        });
                Thread.sleep(300);
                assertThat(homeserver.requests())
                        .as("nothing asked while the gate holds its lock")
                        .isEmpty();
                Files.createFile(dir.resolve(Expel.DIRECTORY).resolve("dan"));
            }
            run.join(10_000);
            assertThat(failed.get()).isNull();
            assertThat(homeserver.requests()).as("dan was expelled meanwhile").isEmpty();
        }
    }

    @Test
    void aLoginExpelledByALinkIsNeitherMadeNorNamed() throws Exception {
        Files.createDirectories(dir.resolve("gate"));
        Files.createDirectories(dir.resolve(Expel.DIRECTORY));
        Files.createSymbolicLink(
                dir.resolve(Expel.DIRECTORY).resolve("dan"), dir.resolve("nowhere"));
        try (FakeHomeserver homeserver = new FakeHomeserver()) {
            final MatrixClient admin =
                    new MatrixClient(new Config(URI.create(homeserver.url()), "admin", null, true));

            Names.give(admin, "example.org", Map.of("dan", "Dan"), dir);

            assertThat(homeserver.requests()).isEmpty();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"../gate", "porelo", "Ada"})
    void aKeyThatIsNoLoginIsNeverAskedFor(final String login) throws Exception {
        Files.createDirectories(dir.resolve("gate"));
        try (FakeHomeserver homeserver = new FakeHomeserver()) {
            final MatrixClient admin =
                    new MatrixClient(new Config(URI.create(homeserver.url()), "admin", null, true));

            assertThatThrownBy(() -> Names.give(admin, "example.org", Map.of(login, "X"), dir))
                    .isInstanceOfSatisfying(
                            Failure.class,
                            failure -> assertThat(failure.exitCode()).isEqualTo(Exit.CONFIG));
            assertThat(homeserver.requests()).isEmpty();
        }
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({
        "429, '{\"errcode\":\"M_LIMIT_EXCEEDED\"}', 75",
        "503, '{}', 75",
        "401, '{\"errcode\":\"M_UNKNOWN_TOKEN\"}', 77",
        "403, '{\"errcode\":\"M_FORBIDDEN\"}', 77",
        "200, '{}', 76",
        "200, '{\"available\":\"yes\"}', 76",
        "404, '{\"errcode\":\"M_UNRECOGNIZED\"}', 76"
    })
    void anUnclearAnswerAboutALoginStopsTheRunUnnamed(
            final int status, final String body, final int exit) throws Exception {
        Files.createDirectories(dir.resolve("gate"));
        try (FakeHomeserver homeserver = new FakeHomeserver()) {
            homeserver.answer(status, body).answer(404, NOT_FOUND).answer(200, "{}");
            final MatrixClient admin =
                    new MatrixClient(new Config(URI.create(homeserver.url()), "admin", null, true));

            assertThatThrownBy(() -> Names.give(admin, "example.org", Map.of("ada", "Ada"), dir))
                    .isInstanceOfSatisfying(
                            Failure.class,
                            failure -> assertThat(failure.exitCode()).isEqualTo(exit));
            assertThat(homeserver.requests()).noneMatch(r -> r.method().equals("PUT"));
        }
    }

    /** Which fields a request set, the password's value left out. */
    private static String body(final String json) {
        try {
            final JsonNode node = MatrixClient.JSON.readTree(json);
            final StringBuilder fields = new StringBuilder();
            node.properties()
                    .forEach(
                            e ->
                                    fields.append(fields.isEmpty() ? "" : " ")
                                            .append(
                                                    e.getKey().equals("password")
                                                            ? (e.getValue().isTextual()
                                                                            && e.getValue()
                                                                                            .asText()
                                                                                            .length()
                                                                                    >= 16
                                                                    ? "password"
                                                                    : "weak-password")
                                                            : e.getKey()
                                                                    + "="
                                                                    + e.getValue().asText()));
            return fields.toString();
        } catch (final java.io.IOException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
