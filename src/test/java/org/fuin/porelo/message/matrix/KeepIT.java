package org.fuin.porelo.message.matrix;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The conversation copied off one machine and put back on another, against the real homeserver run
 * as the account's own: each machine is a directory of its own, with its own configuration, state
 * and database, and Porelo's vault does not come with the copy.
 */
class KeepIT {

    private static final String PROJECT = "demo";

    @TempDir private Path dir;

    private Path binary;

    private final List<ProcessUnit> units = new ArrayList<>();

    @BeforeEach
    void setUp() throws IOException, InterruptedException {
        binary = ProcessUnit.binary(Files.createDirectories(dir.resolve("bin")));
    }

    @AfterEach
    void tearDown() throws Failure {
        for (final ProcessUnit unit : units) {
            unit.close();
        }
    }

    /** A machine of its own: what the account keeps lives under one directory. */
    private Map<String, String> machine(final String name) throws IOException {
        final Path root = Files.createDirectories(dir.resolve(name));
        final Map<String, String> env = new HashMap<>();
        env.put("HOME", root.resolve("home").toString());
        env.put("XDG_CONFIG_HOME", root.resolve("config").toString());
        env.put("XDG_STATE_HOME", root.resolve("state").toString());
        return env;
    }

    private ProcessUnit unit(final Map<String, String> env, final String name) {
        final ProcessUnit unit = new ProcessUnit(env, binary, dir.resolve(name));
        units.add(unit);
        return unit;
    }

    /**
     * A lifecycle verb as Porelo runs it on this machine, with no settings: the local homeserver.
     */
    private static JsonNode lifecycle(
            final Map<String, String> env, final Unit unit, final String... args)
            throws Failure, IOException {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        Lifecycle.run(
                args[0],
                List.of(args).subList(1, args.length),
                env,
                new ByteArrayInputStream(new byte[0]),
                new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(OutputStream.nullOutputStream()),
                unit);
        return MatrixClient.JSON.readTree(out.toByteArray());
    }

    private static Map<String, String> with(
            final Map<String, String> base, final JsonNode... maps) {
        final Map<String, String> env = new HashMap<>(base);
        for (final JsonNode map : maps) {
            map.properties().forEach(entry -> env.put(entry.getKey(), entry.getValue().asText()));
        }
        return env;
    }

    private void send(final Map<String, String> task, final String room, final String message)
            throws IOException {
        final Path file = Files.writeString(dir.resolve("out"), message, StandardCharsets.UTF_8);
        final Path sig = Files.write(dir.resolve("out.sig"), new byte[] {1, 2, 3});
        final Transport.Result result =
                Transport.run(task, "send", file.toString(), sig.toString(), "--to", room);
        assertThat(result.exit()).as("send: %s", result.err()).isEqualTo(Exit.OK);
    }

    /** One poll of the relay into a fresh directory; answers the messages, oldest first. */
    private List<String> poll(final Map<String, String> relay, final String name)
            throws IOException {
        final Path inbound = Files.createDirectories(dir.resolve(name));
        final Transport.Result result = Transport.run(relay, "poll", "--into", inbound.toString());
        assertThat(result.exit()).as("poll: %s", result.err()).isEqualTo(Exit.OK);
        try (Stream<Path> files = Files.list(inbound)) {
            final List<String> messages = new ArrayList<>();
            for (final Path file :
                    files.filter(f -> f.getFileName().toString().endsWith(".json"))
                            .sorted()
                            .toList()) {
                messages.add(Files.readString(file, StandardCharsets.UTF_8));
            }
            return messages;
        }
    }

    private static int status(final String url, final String token)
            throws IOException, InterruptedException {
        return HttpClient.newHttpClient()
                .send(
                        HttpRequest.newBuilder(
                                        URI.create(url + "/_matrix/client/v3/account/whoami"))
                                .header("Authorization", "Bearer " + token)
                                .build(),
                        HttpResponse.BodyHandlers.discarding())
                .statusCode();
    }

    /** The first machine: the room set up, a task in it, one message polled and one not yet. */
    private record First(
            Map<String, String> env,
            ProcessUnit unit,
            JsonNode setup,
            Map<String, String> task,
            String room) {}

    private First first() throws Exception {
        final Map<String, String> env = machine("first");
        final ProcessUnit unit = unit(env, "first");
        final JsonNode setup = lifecycle(env, unit, "setup", "--project", PROJECT);
        final Map<String, String> admin = with(env, setup.path("account"), setup.path("secrets"));
        final JsonNode enrolled =
                lifecycle(admin, unit, "enroll", "--project", PROJECT, "--task", "worker");
        final Map<String, String> task = with(env, enrolled.path("secrets"));
        final String room = setup.path("conversation").asText();
        send(task, room, "handed over before the copy");
        assertThat(poll(with(env, setup.path("secrets")), "first-inbound"))
                .as("the relay's poll on the first machine")
                .containsExactly("handed over before the copy");
        send(task, room, "sent before the copy, not yet handed over");
        return new First(env, unit, setup, task, room);
    }

    @Test
    @DisplayName(
            "restored on a new machine, the room goes on: setup takes it back with nothing in the vault, and the relay hands over only what it had not")
    void theRoomOutlivesTheMachine() throws Exception {
        final First first = first();
        final Path copy = Files.createDirectories(dir.resolve("copy"));
        final JsonNode backup = Keep.backup(copy, first.env(), first.unit(), Keep.BACKUP_WAIT);
        assertThat(backup.path("backup").asLong()).as("the backup's id").isPositive();
        send(first.task(), first.room(), "sent after the copy");
        first.unit().close();

        final Map<String, String> env = machine("second");
        final ProcessUnit unit = unit(env, "second");
        final JsonNode restored = Keep.restore(copy, env, unit);
        assertThat(restored.path("restore").asLong())
                .as("the backup restored")
                .isEqualTo(backup.path("backup").asLong());
        assertThat(Keep.restored(env)).as("the marker left for setup").isTrue();

        final JsonNode setup = lifecycle(env, unit, "setup", "--project", PROJECT);
        assertThat(setup.path("conversation").asText())
                .as("the same room, found again")
                .isEqualTo(first.room());
        assertThat(Keep.restored(env)).as("the marker, gone with its setup").isFalse();
        assertThat(poll(with(env, setup.path("secrets")), "second-inbound"))
                .as("only what the first machine's relay had not handed over, once")
                .containsExactly("sent before the copy, not yet handed over");

        final String url = setup.path("secrets").path(Config.HOMESERVER).asText();
        assertThat(status(url, first.setup().path("account").path(Config.ADMIN_TOKEN).asText()))
                .as("the first machine's administrator token, restored with the copy")
                .isEqualTo(401);
        assertThat(status(url, first.setup().path("secrets").path(Config.ACCESS_TOKEN).asText()))
                .as("the first machine's relay token, restored with the copy")
                .isEqualTo(401);
    }

    @Test
    @DisplayName(
            "without the restore, the new machine's setup makes a new room and the old one is gone")
    void withoutTheRestoreTheRoomIsGone() throws Exception {
        final First first = first();
        first.unit().close();

        final Map<String, String> env = machine("second");
        final ProcessUnit unit = unit(env, "second");
        final JsonNode setup = lifecycle(env, unit, "setup", "--project", PROJECT);
        assertThat(setup.path("conversation").asText())
                .as("a room of the new machine's own")
                .isNotEqualTo(first.room());
        assertThat(poll(with(env, setup.path("secrets")), "second-inbound"))
                .as("nothing of the old room")
                .isEmpty();
    }

    @Test
    @DisplayName("a restore where a database is already leaves it as it is and says present")
    void restoreOverADatabaseRestoresNothing() throws Exception {
        final First first = first();
        final Path copy = Files.createDirectories(dir.resolve("copy"));
        Keep.backup(copy, first.env(), first.unit(), Keep.BACKUP_WAIT);
        send(first.task(), first.room(), "sent after the copy");
        first.unit().stop();

        assertThat(Keep.restore(copy, first.env(), first.unit()).path("restore").asText())
                .isEqualTo("present");
        assertThat(first.unit().runs()).as("runs of the homeserver's own").isZero();
        assertThat(Keep.restored(first.env())).as("a marker").isFalse();

        first.unit().start();
        LocalHomeserver.awaitUp(
                URI.create(first.setup().path("secrets").path(Config.HOMESERVER).asText()));
        assertThat(poll(with(first.env(), first.setup().path("secrets")), "again"))
                .as("the database as it was, the message after the copy in it")
                .containsExactly(
                        "sent before the copy, not yet handed over", "sent after the copy");
    }
}
