package org.fuin.porelo.message.matrix;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ExpelTest {

    @TempDir private Path dir;

    /**
     * Harm guarded: a purged person stays active, another login is deactivated, or retry expels
     * twice.
     */
    @Test
    void expelDeactivatesOnlyTheLoginAndIsIdempotent() throws Exception {
        try (FakeHomeserver homeserver = new FakeHomeserver()) {
            homeserver.answer(200, "{\"joined_rooms\":[\"!admins:example.org\"]}");
            homeserver.answer(200, "{\"alias\":\"#admins:example.org\"}");
            final MatrixClient admin =
                    new MatrixClient(
                            new Config(URI.create(homeserver.url()), "admin-token", null, true));
            final AdminRoom room = AdminRoom.of(admin, "@porelo:example.org");
            final List<String> keep =
                    List.of("@porelo:example.org", "@x-bob:example.org", "@t.x-bob:example.org");
            final List<String> all = new java.util.ArrayList<>(keep);
            all.addAll(
                    List.of("@bob:example.org", "@t.bob:example.org", "@porelo.bob:example.org"));
            reply(homeserver, users(all));
            for (int i = 0; i < 3; i++) {
                reply(homeserver, "The user has been deactivated");
            }
            reply(homeserver, users(keep));
            final Map<String, String> env = Map.of("XDG_CONFIG_HOME", dir.toString());
            final var first = Expel.expel(env, "bob", "example.org", room);
            assertThat(first.properties())
                    .as("the answer has only the contracted keys")
                    .extracting(Map.Entry::getKey)
                    .containsExactly("login", "known", "expelled");
            assertThat(first.path("login").asText())
                    .as("the expelled login is named")
                    .isEqualTo("bob");
            assertThat(first.path("known").asBoolean()).as("the server knew the login").isTrue();
            assertThat(first.path("expelled"))
                    .as("only bob's accounts went, provisioning first")
                    .extracting(com.fasterxml.jackson.databind.JsonNode::asText)
                    .containsExactly(
                            "@porelo.bob:example.org", "@bob:example.org", "@t.bob:example.org");
            assertThat(
                            homeserver.requests().stream()
                                    .map(FakeHomeserver.Request::body)
                                    .filter(body -> body.contains("!admin users deactivate"))
                                    .toList())
                    .as("the admin-room commands touch only bob's accounts")
                    .hasSize(3)
                    .noneMatch(body -> body.contains("x-bob"));
            reply(homeserver, users(keep));
            final var again = Expel.expel(env, "bob", "example.org", room);
            assertThat(again.path("known").asBoolean())
                    .as("the record survives deactivation")
                    .isTrue();
            assertThat(again.path("expelled")).as("a repeated expel deactivates nobody").isEmpty();
        }
    }

    private static String users(final List<String> ids) {
        return "Found "
                + ids.size()
                + " local user account(s):\n```\n"
                + String.join("\n", ids)
                + "\n```";
    }

    private static void reply(final FakeHomeserver homeserver, final String text) {
        homeserver.answer(200, "{\"event_id\":\"$command\"}");
        final var response = MatrixClient.JSON.createObjectNode();
        final var event =
                response.putArray("chunk")
                        .addObject()
                        .put("type", "m.room.message")
                        .put("sender", "@server:example.org")
                        .put("event_id", "$reply");
        final var content = event.putObject("content").put("body", text);
        content.putObject("m.relates_to").putObject("m.in_reply_to").put("event_id", "$command");
        homeserver.answer(200, response.toString());
    }

    /** Harm guarded: expelling bob deactivates another login's task or misses bob's own account. */
    @Test
    void expelSelectsOnlyTheLoginsAccountsWithProvisioningFirst() {
        assertThat(
                        Expel.accounts(
                                "bob",
                                "example.org",
                                List.of(
                                        "@bob:example.org",
                                        "@t.x-bob:example.org",
                                        "@task.part.bob:example.org",
                                        "@porelo.bob:example.org",
                                        "@porelo-relay-demo.bob:example.org",
                                        "@porelo.x-bob:example.org",
                                        "@x-bob:example.org",
                                        "@t.bob:elsewhere",
                                        "@porelo:example.org",
                                        "@bobby:example.org")))
                .as("only bob's accounts are deactivated, @porelo.bob before the rest")
                .containsExactly(
                        "@porelo.bob:example.org",
                        "@bob:example.org",
                        "@task.part.bob:example.org",
                        "@porelo-relay-demo.bob:example.org");
    }

    /** Harm guarded: a mistyped unknown login becomes permanently unavailable. */
    @Test
    void anUnknownLoginIsLeftAvailable() throws Exception {
        final Map<String, String> env = Map.of("XDG_CONFIG_HOME", dir.toString());
        assertThat(Expel.record(env, "bob", false)).as("no record or accounts are known").isFalse();
        assertThat(dir.resolve("porelo/matrix/expelled-logins/bob"))
                .as("an unknown login is not marked as expelled")
                .doesNotExist();
    }

    /** Harm guarded: an expelled login can register again or a repeated expel loses its record. */
    @Test
    void anExpelledLoginStaysKnownWithoutARegistrationToken() throws Exception {
        final Map<String, String> env = Map.of("XDG_CONFIG_HOME", dir.toString());
        assertThat(Expel.record(env, "bob", true)).as("accounts make the login known").isTrue();
        final Path record = dir.resolve("porelo/matrix/expelled-logins/bob");
        assertThat(record)
                .as("the gate can read the expelled login beside the homeserver data")
                .isRegularFile()
                .isEmptyFile();
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(record)))
                .as("only the homeserver's account can read the record")
                .isEqualTo("rw-------");
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(record.getParent())))
                .as("only the homeserver's account can enter the records directory")
                .isEqualTo("rwx------");
        assertThat(Expel.record(env, "bob", false)).as("a repeated expel stays known").isTrue();
        assertThat(dir.resolve("porelo/matrix/tokens"))
                .as("expelling a login needs no registration token")
                .doesNotExist();
        assertThat(dir.resolve("porelo/matrix/expelled-logins/x-bob"))
                .as("the longer login stays available")
                .doesNotExist();
    }

    /** Harm guarded: a malformed login deactivates accounts of another person. */
    @ParameterizedTest
    @ValueSource(
            strings = {"bob.dev", "Bob", "porelo", "porelo-x", "a12345678901234567890123456789012"})
    void expelRefusesInvalidLogins(final String login) {
        final ByteArrayOutputStream error = new ByteArrayOutputStream();
        final int exit =
                Main.run(
                        new String[] {"expel", login},
                        Map.of(),
                        new PrintStream(PrintStream.nullOutputStream()),
                        new PrintStream(error, true, StandardCharsets.UTF_8));
        assertThat(exit)
                .as("invalid login '%s' cannot expel anybody", login)
                .isEqualTo(Exit.CONFIG);
        assertThat(error.toString(StandardCharsets.UTF_8))
                .as("the refusal names the login rule")
                .contains("login", "lower-case", "32", "porelo");
    }
}
