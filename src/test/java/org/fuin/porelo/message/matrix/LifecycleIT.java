package org.fuin.porelo.message.matrix;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
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
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The lifecycle Porelo drives - setup, enroll, retire, join, clear - against the real homeserver
 * run as the account's own: each test is a directory of its own, with its own configuration, state
 * and database.
 */
class LifecycleIT {

    private static final String PROJECT = "demo";

    private static final String SERVER_NAME = "localhost";

    @TempDir private static Path bin;

    private static Path binary;

    @TempDir private Path dir;

    private ProcessUnit unit;

    private String settings;

    @BeforeAll
    static void extractBinary() throws IOException, InterruptedException {
        binary = ProcessUnit.binary(bin);
    }

    @BeforeEach
    void setUp() {
        settings = "{}";
        unit = new ProcessUnit(firstRun(), binary, dir.resolve("unit"));
    }

    @AfterEach
    void tearDown() throws IOException, InterruptedException, Failure {
        if (unit != null) {
            unit.close();
        }
    }

    /**
     * Runs a verb as Porelo does: settings on stdin, the kept secrets as the environment; answers
     * its JSON.
     */
    private JsonNode run(final Map<String, String> env, final String... args) throws IOException {
        final Transport.Result result = attempt(env, args);
        assertThat(result.exit())
                .as("%s: %s", String.join(" ", args), result.err())
                .isEqualTo(Exit.OK);
        return MatrixClient.JSON.readTree(result.out());
    }

    private Transport.Result attempt(final Map<String, String> env, final String... args) {
        if (!Lifecycle.VERBS.contains(args[0])) {
            return Transport.runWithInput(env, settings, args);
        }
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        final ByteArrayOutputStream err = new ByteArrayOutputStream();
        final PrintStream errStream = new PrintStream(err, true, StandardCharsets.UTF_8);
        try {
            Lifecycle.run(
                    args[0],
                    List.of(args).subList(1, args.length),
                    env,
                    new ByteArrayInputStream(settings.getBytes(StandardCharsets.UTF_8)),
                    new PrintStream(out, true, StandardCharsets.UTF_8),
                    errStream,
                    unit);
            return new Transport.Result(
                    Exit.OK,
                    out.toString(StandardCharsets.UTF_8),
                    err.toString(StandardCharsets.UTF_8));
        } catch (final Failure failure) {
            errStream.println(Main.NAME + " " + args[0] + ": " + failure.getMessage());
            return new Transport.Result(
                    failure.exitCode(),
                    out.toString(StandardCharsets.UTF_8),
                    err.toString(StandardCharsets.UTF_8));
        }
    }

    private Map<String, String> firstRun() {
        final Map<String, String> env = new HashMap<>();
        env.put("HOME", dir.resolve("home").toString());
        env.put("XDG_CONFIG_HOME", dir.resolve("config").toString());
        env.put("XDG_STATE_HOME", dir.resolve("state").toString());
        return env;
    }

    private String url() throws Failure {
        return "http://127.0.0.1:"
                + LocalHomeserver.port(
                        LocalHomeserver.directory(firstRun()).resolve("homeserver.conf"));
    }

    /**
     * The environment Porelo builds from what the verbs printed: the kept maps, one over the other.
     */
    private static Map<String, String> with(
            final Map<String, String> base, final JsonNode... maps) {
        final Map<String, String> env = new HashMap<>(base);
        for (final JsonNode map : maps) {
            map.properties().forEach(entry -> env.put(entry.getKey(), entry.getValue().asText()));
        }
        return env;
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

    @Test
    @DisplayName(
            "setup makes the provisioning account, the project's room and its relay, and says what it reaches")
    void setupMakesTheConversation() throws Exception {
        final JsonNode answer = run(firstRun(), "setup", "--project", PROJECT);

        assertThat(answer.path("account").path(Config.ADMIN_TOKEN).asText()).isNotEmpty();
        final JsonNode secrets = answer.path("secrets");
        assertThat(secrets.path(Config.HOMESERVER).asText()).isEqualTo(url());
        assertThat(secrets.path(Config.ACCESS_TOKEN).asText()).isNotEmpty();
        assertThat(answer.path("conversation").asText()).startsWith("!");
        assertThat(answer.path("reaches").get(0).asText())
                .isEqualTo(URI.create(url()).getAuthority());
        assertThat(status(url(), secrets.path(Config.ACCESS_TOKEN).asText())).isEqualTo(200);
    }

    @Test
    @DisplayName("setup run again with what it printed keeps the account, the room and the relay")
    void setupAgainKeepsEverything() throws Exception {
        final JsonNode first = run(firstRun(), "setup", "--project", PROJECT);

        final JsonNode second =
                run(
                        with(firstRun(), first.path("account"), first.path("secrets")),
                        "setup",
                        "--project",
                        PROJECT);

        assertThat(second).isEqualTo(first);
    }

    /** Harm guarded: people must distinguish the room's helpers from task accounts. */
    @Test
    void setupNamesBothHelpersOnANewAndAnExistingRoom() throws Exception {
        final JsonNode first = run(firstRun(), "setup", "--project", PROJECT);
        final Map<String, String> kept =
                with(firstRun(), first.path("account"), first.path("secrets"));
        final MatrixClient relay = new MatrixClient(Config.from(kept));
        final String relayId =
                relay.get("/_matrix/client/v3/account/whoami").path("user_id").asText();
        final String adminId = "@porelo:" + AdminRoom.server(relayId);
        final MatrixClient admin = relay.as(kept.get(Config.ADMIN_TOKEN));

        assertThat(displayName(adminId))
                .as("the administrator is recognizable")
                .isEqualTo("Porelo (administrator)");
        assertThat(displayName(relayId))
                .as("the delivery account is recognizable")
                .isEqualTo("Porelo (delivery)");
        admin.put(
                "/_matrix/client/v3/profile/" + MatrixClient.segment(adminId) + "/displayname",
                MatrixClient.JSON.createObjectNode().put("displayname", "changed administrator"));
        relay.put(
                "/_matrix/client/v3/profile/" + MatrixClient.segment(relayId) + "/displayname",
                MatrixClient.JSON.createObjectNode().put("displayname", "changed relay"));

        final JsonNode second = run(kept, "setup", "--project", PROJECT);

        assertThat(second.path("conversation"))
                .as("setup reuses the existing room")
                .isEqualTo(first.path("conversation"));
        assertThat(displayName(adminId))
                .as("setup restores the administrator's name")
                .isEqualTo("Porelo (administrator)");
        assertThat(displayName(relayId))
                .as("setup restores the delivery account's name")
                .isEqualTo("Porelo (delivery)");
    }

    @Test
    @DisplayName(
            "clear --project deactivates every account in the room and deletes the room; run again it finds nothing, and setup makes it anew")
    void clearTakesTheConversationAway() throws Exception {
        final JsonNode setup = run(firstRun(), "setup", "--project", PROJECT);
        final Map<String, String> kept =
                with(firstRun(), setup.path("account"), setup.path("secrets"));
        kept.put("XDG_STATE_HOME", dir.resolve("state").toString());
        final JsonNode alice =
                run(kept, "enroll", "--project", PROJECT, "--task", "porelo-demo-alice");
        run(kept, "join", "--project", PROJECT, "--person", "carol");
        final String server = AdminRoom.server(alice.path("address").asText());
        // The relay has polled, so it keeps a position: clear must forget it, or the project set
        // up again ends every poll with 78, its position naming rooms it is no longer in.
        final Path inbound = Files.createDirectories(dir.resolve("inbound"));
        assertThat(attempt(kept, "poll", "--into", inbound.toString(), "--persons").exit())
                .as("the relay's poll before clear")
                .isEqualTo(Exit.OK);

        final JsonNode cleared = run(kept, "clear", "--project", PROJECT);

        assertThat(cleared.path("removed"))
                .extracting(JsonNode::asText)
                .containsExactlyInAnyOrder(
                        "the account @porelo-relay-demo:" + server,
                        "the account @porelo-demo-alice:" + server,
                        "the account @carol:" + server,
                        "the room #porelo-demo:" + server,
                        "the poll position of @porelo-relay-demo:" + server);
        assertThat(cleared.path("kept")).isEmpty();
        assertThat(status(url(), alice.path("secrets").path(Config.ACCESS_TOKEN).asText()))
                .isEqualTo(401);
        assertThat(status(url(), setup.path("account").path(Config.ADMIN_TOKEN).asText()))
                .isEqualTo(200);

        final JsonNode again = run(kept, "clear", "--project", PROJECT);
        assertThat(again.path("removed")).isEmpty();
        assertThat(again.path("kept")).isEmpty();

        final JsonNode anew = run(kept, "setup", "--project", PROJECT);
        assertThat(anew.path("conversation").asText())
                .isNotEqualTo(setup.path("conversation").asText());
        final JsonNode aliceAgain =
                run(
                        with(kept, anew.path("secrets")),
                        "enroll",
                        "--project",
                        PROJECT,
                        "--task",
                        "porelo-demo-alice");
        assertThat(status(url(), aliceAgain.path("secrets").path(Config.ACCESS_TOKEN).asText()))
                .isEqualTo(200);
        final Transport.Result polled =
                attempt(
                        with(kept, anew.path("secrets")),
                        "poll",
                        "--into",
                        inbound.toString(),
                        "--persons");
        assertThat(polled.exit())
                .as("the relay's poll after setup anew: %s", polled.err())
                .isEqualTo(Exit.OK);
    }

    /**
     * Harm guarded: a person's account is one on the homeserver, not one per project, so clearing
     * one project deactivated it and cut the person off from every other project too, with no way
     * back.
     */
    @Test
    @DisplayName(
            "clear --project leaves active a member who is in another project's room too, and says so in kept")
    void clearKeepsAMemberOfAnotherProject() throws Exception {
        final JsonNode setup = run(firstRun(), "setup", "--project", PROJECT);
        final Map<String, String> kept =
                with(firstRun(), setup.path("account"), setup.path("secrets"));
        final JsonNode other = run(kept, "setup", "--project", "other");
        run(kept, "join", "--project", PROJECT, "--person", "ann");
        final String password =
                run(kept, "join", "--project", "other", "--person", "ann", "--reset")
                        .path("login")
                        .path("password")
                        .asText();
        final String carolPassword =
                run(kept, "join", "--project", PROJECT, "--person", "carol")
                        .path("login")
                        .path("password")
                        .asText();
        final String server = SERVER_NAME;

        final JsonNode cleared = run(kept, "clear", "--project", PROJECT);

        assertThat(cleared.path("removed"))
                .extracting(JsonNode::asText)
                .as("only the project's own accounts go")
                .contains("the account @carol:" + server)
                .doesNotContain("the account @ann:" + server);
        assertThat(cleared.path("kept"))
                .as("the member kept, with the room that keeps her")
                .anySatisfy(
                        entry -> {
                            assertThat(entry.path("what").asText())
                                    .isEqualTo("the account @ann:" + server);
                            assertThat(entry.path("why").asText())
                                    .contains("#porelo-other:" + server);
                        });
        assertThat(login("carol", carolPassword))
                .as("carol, only in the cleared project, is deactivated")
                .isEmpty();
        final String token = login("ann", password);
        assertThat(token).as("ann still logs in").isNotEmpty();
        assertThat(joinedRooms(token))
                .as("ann is still in the other project's room, and no longer in the cleared one")
                .contains(other.path("conversation").asText())
                .doesNotContain(setup.path("conversation").asText());
    }

    /**
     * Harm guarded: two projects whose names run together give two tasks one name, and the second
     * project's enroll took the first task's account over - a new password, every other session
     * logged out - or its retire deactivated it.
     */
    @Test
    @DisplayName(
            "enroll and retire through another project refuse a task account that is in a project's room, and change nothing")
    void anotherProjectsTaskIsLeftAlone() throws Exception {
        final JsonNode setup = run(firstRun(), "setup", "--project", "a");
        final Map<String, String> kept =
                with(firstRun(), setup.path("account"), setup.path("secrets"));
        run(kept, "setup", "--project", "a-b");
        final String token =
                run(kept, "enroll", "--project", "a", "--task", "porelo-a-b-c")
                        .path("secrets")
                        .path(Config.ACCESS_TOKEN)
                        .asText();

        final Transport.Result enrolled =
                attempt(kept, "enroll", "--project", "a-b", "--task", "porelo-a-b-c");
        final Transport.Result retired =
                attempt(kept, "retire", "--project", "a-b", "--task", "porelo-a-b-c");

        assertThat(enrolled.exit()).as("enroll: %s", enrolled.err()).isEqualTo(Exit.CONFIG);
        assertThat(retired.exit()).as("retire: %s", retired.err()).isEqualTo(Exit.CONFIG);
        assertThat(status(url(), token))
                .as("the first project's task keeps its token")
                .isEqualTo(200);

        run(kept, "clear", "--project", "a");
        final Transport.Result afterClear =
                attempt(kept, "retire", "--project", "a", "--task", "porelo-a-b-c");
        assertThat(afterClear.exit())
                .as("retire of a cleared project's task stays harmless: %s", afterClear.err())
                .isEqualTo(Exit.OK);
    }

    /** An access token for a person, as their Matrix client would log in; empty when refused. */
    private String login(final String user, final String password)
            throws IOException, InterruptedException, Failure {
        final HttpResponse<String> response =
                HttpClient.newHttpClient()
                        .send(
                                HttpRequest.newBuilder(
                                                URI.create(url() + "/_matrix/client/v3/login"))
                                        .header("Content-Type", "application/json")
                                        .POST(
                                                HttpRequest.BodyPublishers.ofString(
                                                        MatrixClient.JSON
                                                                .createObjectNode()
                                                                .put("type", "m.login.password")
                                                                .put("user", user)
                                                                .put("password", password)
                                                                .toString()))
                                        .build(),
                                HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            return "";
        }
        return MatrixClient.JSON.readTree(response.body()).path("access_token").asText();
    }

    private List<String> joinedRooms(final String token)
            throws IOException, InterruptedException, Failure {
        final HttpResponse<String> response =
                HttpClient.newHttpClient()
                        .send(
                                HttpRequest.newBuilder(
                                                URI.create(
                                                        url() + "/_matrix/client/v3/joined_rooms"))
                                        .header("Authorization", "Bearer " + token)
                                        .build(),
                                HttpResponse.BodyHandlers.ofString());
        final List<String> rooms = new ArrayList<>();
        MatrixClient.JSON
                .readTree(response.body())
                .path("joined_rooms")
                .forEach(room -> rooms.add(room.asText()));
        return rooms;
    }

    @Test
    @DisplayName(
            "A room somebody else made under the project's alias is refused by setup and enroll with 78, and clear leaves it and its people alone")
    void foreignAliasIsRefused() throws Exception {
        final JsonNode first = run(firstRun(), "setup", "--project", "other");
        final Map<String, String> kept =
                with(firstRun(), first.path("account"), first.path("secrets"));
        kept.put("XDG_STATE_HOME", dir.resolve("state").toString());
        final MatrixClient nobody = new MatrixClient(new Config(URI.create(url()), "", null, true));
        final Accounts.Session mallory =
                Accounts.register(
                        nobody,
                        "mallory",
                        Accounts.password(),
                        LocalHomeserver.registrationToken(firstRun()));
        final String theirs =
                nobody.as(mallory.accessToken())
                        .post(
                                "/_matrix/client/v3/createRoom",
                                MatrixClient.JSON
                                        .createObjectNode()
                                        .put("preset", "public_chat")
                                        .put("room_alias_name", "porelo-" + PROJECT))
                        .path("room_id")
                        .asText();

        final Transport.Result setup = attempt(kept, "setup", "--project", PROJECT);
        assertThat(setup.exit()).isEqualTo(Exit.CONFIG);
        assertThat(setup.err()).contains(theirs, "not the room Porelo made", "is not in it");

        final Transport.Result enroll =
                attempt(kept, "enroll", "--project", PROJECT, "--task", "porelo-demo-alice");
        assertThat(enroll.exit()).isEqualTo(Exit.CONFIG);
        assertThat(enroll.err()).contains("not the room Porelo made");

        final JsonNode cleared = run(kept, "clear", "--project", PROJECT);
        assertThat(cleared.path("removed")).isEmpty();
        assertThat(cleared.path("kept"))
                .singleElement()
                .satisfies(
                        k ->
                                assertThat(k.path("why").asText())
                                        .contains("not the room Porelo made"));
        assertThat(status(url(), mallory.accessToken())).isEqualTo(200);
    }

    @Test
    @DisplayName(
            "Porelo's own room, opened to anyone since, is refused by enroll with 78, naming what is wrong")
    void openedRoomIsRefused() throws Exception {
        final JsonNode setup = run(firstRun(), "setup", "--project", PROJECT);
        final Map<String, String> kept =
                with(firstRun(), setup.path("account"), setup.path("secrets"));
        final String room = setup.path("conversation").asText();
        final HttpResponse<Void> opened =
                HttpClient.newHttpClient()
                        .send(
                                HttpRequest.newBuilder(
                                                URI.create(
                                                        url()
                                                                + "/_matrix/client/v3/rooms/"
                                                                + MatrixClient.segment(room)
                                                                + "/state/m.room.join_rules/"))
                                        .header(
                                                "Authorization",
                                                "Bearer "
                                                        + setup.path("account")
                                                                .path(Config.ADMIN_TOKEN)
                                                                .asText())
                                        .PUT(
                                                HttpRequest.BodyPublishers.ofString(
                                                        "{\"join_rule\":\"public\"}"))
                                        .build(),
                                HttpResponse.BodyHandlers.discarding());
        assertThat(opened.statusCode()).isEqualTo(200);

        final Transport.Result enroll =
                attempt(kept, "enroll", "--project", PROJECT, "--task", "porelo-demo-alice");

        assertThat(enroll.exit()).isEqualTo(Exit.CONFIG);
        assertThat(enroll.err())
                .contains("not the room Porelo made", "its join rule is public, not invite");
    }

    @Test
    @DisplayName(
            "A password reset leaves the password in no event of the admin room: command and reply are redacted")
    void resetPasswordIsNotKept() throws Exception {
        final JsonNode setup = run(firstRun(), "setup", "--project", PROJECT);
        final Map<String, String> kept =
                with(firstRun(), setup.path("account"), setup.path("secrets"));
        run(kept, "join", "--project", PROJECT, "--person", "carol");

        final String password =
                run(kept, "join", "--project", PROJECT, "--person", "carol", "--reset")
                        .path("login")
                        .path("password")
                        .asText();
        final String adminToken = setup.path("account").path(Config.ADMIN_TOKEN).asText();

        assertThat(password).hasSize(32);
        final HttpClient http = HttpClient.newHttpClient();
        final JsonNode rooms =
                MatrixClient.JSON.readTree(
                        http.send(
                                        HttpRequest.newBuilder(
                                                        URI.create(
                                                                url()
                                                                        + "/_matrix/client/v3/joined_rooms"))
                                                .header("Authorization", "Bearer " + adminToken)
                                                .build(),
                                        HttpResponse.BodyHandlers.ofString())
                                .body());
        final StringBuilder all = new StringBuilder();
        for (final JsonNode room : rooms.path("joined_rooms")) {
            final String history =
                    http.send(
                                    HttpRequest.newBuilder(
                                                    URI.create(
                                                            url()
                                                                    + "/_matrix/client/v3/rooms/"
                                                                    + MatrixClient.segment(
                                                                            room.asText())
                                                                    + "/messages?dir=b&limit=100"))
                                            .header("Authorization", "Bearer " + adminToken)
                                            .build(),
                                    HttpResponse.BodyHandlers.ofString())
                            .body();
            assertThat(history).as("the history of %s", room.asText()).doesNotContain(password);
            all.append(history);
        }
        assertThat(all)
                .as("the admin room was read, and the reset's two events are redacted in it")
                .contains("m.room.redaction", "\"reason\":\"a password\"");
    }

    @Test
    @DisplayName(
            "clear --project without the secrets of a setup says that nothing was made, and exits 0")
    void clearWithoutSetup() throws Exception {
        final JsonNode cleared = run(Map.of(), "clear", "--project", PROJECT);

        assertThat(cleared.path("removed")).isEmpty();
        assertThat(cleared.path("kept"))
                .singleElement()
                .satisfies(
                        kept ->
                                assertThat(kept.path("why").asText())
                                        .contains("no secrets from setup"));
    }

    @Test
    @DisplayName(
            "setup refuses with 78 a homeserver whose provisioning account exists while Porelo holds no token for it")
    void lostAccountIsRefused() throws Exception {
        run(firstRun(), "setup", "--project", PROJECT);

        final Transport.Result again = attempt(firstRun(), "setup", "--project", PROJECT);

        assertThat(again.exit()).isEqualTo(Exit.CONFIG);
        assertThat(again.err()).contains("holds no token");
    }

    @Test
    @DisplayName(
            "A homeserver reset since setup is said as that, with 78, by setup and by the verbs after it")
    void resetHomeserverIsSaid() throws Exception {
        final JsonNode setup = run(firstRun(), "setup", "--project", PROJECT);
        final Map<String, String> kept =
                with(firstRun(), setup.path("account"), setup.path("secrets"));

        unit.stop();
        unit.removeDatabase();
        unit.start();
        LocalHomeserver.awaitUp(URI.create(url()));

        for (final String[] args :
                new String[][] {
                    {"setup", "--project", PROJECT},
                    {"enroll", "--project", PROJECT, "--task", "porelo-demo-alice"},
                    {"join", "--project", PROJECT, "--person", "carol"}
                }) {
            final Transport.Result result = attempt(kept, args);
            assertThat(result.exit()).as(args[0]).isEqualTo(Exit.CONFIG);
            assertThat(result.err()).as(args[0]).contains("was reset");
        }
    }

    @Test
    @DisplayName(
            "A provisioning token the homeserver refuses while @porelo exists is not taken for a reset")
    void wrongTokenIsNotAReset() throws Exception {
        final JsonNode setup = run(firstRun(), "setup", "--project", PROJECT);
        final Map<String, String> wrong = with(firstRun(), setup.path("secrets"));
        wrong.put(Config.ADMIN_TOKEN, "not-a-token");

        final Transport.Result result = attempt(wrong, "setup", "--project", PROJECT);

        assertThat(result.exit()).isEqualTo(Exit.CONFIG);
        assertThat(result.err()).contains("is not valid").doesNotContain("was reset");
    }

    @Test
    @DisplayName("A second project on the same homeserver gets its own room and its own relay")
    void secondProject() throws Exception {
        final JsonNode first = run(firstRun(), "setup", "--project", PROJECT);

        final JsonNode other =
                run(with(firstRun(), first.path("account")), "setup", "--project", "other");

        assertThat(other.path("conversation").asText())
                .isNotEqualTo(first.path("conversation").asText());
        assertThat(other.path("secrets").path(Config.ACCESS_TOKEN).asText())
                .isNotEqualTo(first.path("secrets").path(Config.ACCESS_TOKEN).asText());
    }

    @Test
    @DisplayName(
            "Enrolled tasks talk through the project's room: send, poll by the relay, read, receipt")
    void enrolledTasksTalk() throws Exception {
        final JsonNode setup = run(firstRun(), "setup", "--project", PROJECT);
        final Map<String, String> lifecycle =
                with(firstRun(), setup.path("account"), setup.path("secrets"));
        final Map<String, String> alice =
                with(
                        Map.of(),
                        run(
                                        lifecycle,
                                        "enroll",
                                        "--project",
                                        PROJECT,
                                        "--task",
                                        "porelo-demo-alice")
                                .path("secrets"));
        // Poll and read run in the same account, so they share its state - where poll keeps what a
        // name stands for.
        final Map<String, String> bob =
                with(
                        Map.of("XDG_STATE_HOME", dir.resolve("state").toString()),
                        run(lifecycle, "enroll", "--project", PROJECT, "--task", "porelo-demo-bob")
                                .path("secrets"));

        final Path message =
                Files.writeString(
                        dir.resolve("m.json"), "{\"to\":\"bob\"}", StandardCharsets.UTF_8);
        final Path sig = Files.write(dir.resolve("m.json.sig"), new byte[] {1, 2, 3});
        final String reference =
                run(
                                alice,
                                "send",
                                message.toString(),
                                sig.toString(),
                                "--to",
                                setup.path("conversation").asText())
                        .path("reference")
                        .asText();

        final Path inbound = Files.createDirectories(dir.resolve("in"));
        final Map<String, String> relay =
                with(
                        Map.of("XDG_STATE_HOME", dir.resolve("state").toString()),
                        setup.path("secrets"));
        assertThat(Transport.run(relay, "poll", "--into", inbound.toString()).exit())
                .isEqualTo(Exit.OK);
        final String delivered;
        try (Stream<Path> files = Files.list(inbound)) {
            delivered =
                    files.map(f -> f.getFileName().toString())
                            .filter(n -> n.endsWith(".json"))
                            .findFirst()
                            .orElseThrow();
        }
        assertThat(delivered)
                .as("a name the filter takes: no event id in it")
                .doesNotContain(reference.substring(1));

        final String by = "@porelo-demo-bob:" + SERVER_NAME;
        assertThat(run(alice, "receipt", reference, "--by", by).path("state").asText())
                .isEqualTo("delivered");
        assertThat(
                        Transport.run(
                                        bob,
                                        "read",
                                        delivered.substring(
                                                0, delivered.length() - ".json".length()))
                                .exit())
                .isEqualTo(Exit.OK);
        assertThat(run(alice, "receipt", reference, "--by", by).path("state").asText())
                .isEqualTo("read");
    }

    @Test
    @DisplayName("Enrolling a task again - its token lost - gives the same account a new token")
    void enrollAgain() throws Exception {
        final JsonNode setup = run(firstRun(), "setup", "--project", PROJECT);
        final Map<String, String> lifecycle =
                with(firstRun(), setup.path("account"), setup.path("secrets"));
        final String first =
                run(lifecycle, "enroll", "--project", PROJECT, "--task", "porelo-demo-alice")
                        .path("secrets")
                        .path(Config.ACCESS_TOKEN)
                        .asText();

        final JsonNode again =
                run(lifecycle, "enroll", "--project", PROJECT, "--task", "porelo-demo-alice");
        final String second = again.path("secrets").path(Config.ACCESS_TOKEN).asText();
        assertThat(again.path("address").asText()).isEqualTo("@porelo-demo-alice:" + SERVER_NAME);

        assertThat(second).isNotEqualTo(first);
        assertThat(status(url(), second)).isEqualTo(200);
    }

    @Test
    @DisplayName(
            "A task is shown by the nickname enroll --display gives it, by its name without one, and renamed by enrolling again")
    void nickname() throws Exception {
        final JsonNode setup = run(firstRun(), "setup", "--project", PROJECT);
        final Map<String, String> lifecycle =
                with(firstRun(), setup.path("account"), setup.path("secrets"));
        final String plain =
                run(lifecycle, "enroll", "--project", PROJECT, "--task", "porelo-demo-alice")
                        .path("address")
                        .asText();
        assertThat(displayName(plain)).isEqualTo("porelo-demo-alice");

        final String named =
                run(
                                lifecycle,
                                "enroll",
                                "--project",
                                PROJECT,
                                "--task",
                                "porelo-demo-alice",
                                "--display",
                                " Schreiberin Ä ")
                        .path("address")
                        .asText();
        assertThat(named).as("the id stays, so every address Porelo keeps does").isEqualTo(plain);
        assertThat(displayName(named)).isEqualTo("Schreiberin Ä");

        for (final String wrong :
                new String[] {"", "  ", "a@b", "a:b", "x".repeat(65), "line\nbreak"}) {
            assertThat(
                            attempt(
                                            lifecycle,
                                            "enroll",
                                            "--project",
                                            PROJECT,
                                            "--task",
                                            "porelo-demo-alice",
                                            "--display",
                                            wrong)
                                    .exit())
                    .as("'%s'", wrong)
                    .isEqualTo(Exit.USAGE);
        }
        assertThat(attempt(lifecycle, "setup", "--project", PROJECT, "--display", "x").exit())
                .isEqualTo(Exit.USAGE);
    }

    @Test
    @DisplayName("rename, with the task's own token, changes the name it shows and keeps its token")
    void rename() throws Exception {
        final JsonNode setup = run(firstRun(), "setup", "--project", PROJECT);
        final Map<String, String> lifecycle =
                with(firstRun(), setup.path("account"), setup.path("secrets"));
        final JsonNode alice =
                run(
                        lifecycle,
                        "enroll",
                        "--project",
                        PROJECT,
                        "--task",
                        "porelo-demo-alice",
                        "--display",
                        "Ada");
        final Map<String, String> task = with(Map.of(), alice.path("secrets"));

        run(task, "rename", "--display", "Grace");

        assertThat(displayName(alice.path("address").asText())).isEqualTo("Grace");
        assertThat(status(url(), alice.path("secrets").path(Config.ACCESS_TOKEN).asText()))
                .isEqualTo(200);
        assertThat(attempt(task, "rename", "--display", "a@b").exit()).isEqualTo(Exit.USAGE);
        assertThat(attempt(task, "rename").exit()).isEqualTo(Exit.USAGE);
    }

    private String displayName(final String userId)
            throws IOException, InterruptedException, Failure {
        final java.net.http.HttpResponse<String> answer =
                java.net.http.HttpClient.newHttpClient()
                        .send(
                                java.net.http.HttpRequest.newBuilder(
                                                java.net.URI.create(
                                                        url()
                                                                + "/_matrix/client/v3/profile/"
                                                                + MatrixClient.segment(userId)
                                                                + "/displayname"))
                                        .build(),
                                java.net.http.HttpResponse.BodyHandlers.ofString());
        return MatrixClient.JSON.readTree(answer.body()).path("displayname").asText();
    }

    @Test
    @DisplayName("A retired task's token is refused from then on; retiring it again is harmless")
    void retire() throws Exception {
        final JsonNode setup = run(firstRun(), "setup", "--project", PROJECT);
        final Map<String, String> lifecycle =
                with(firstRun(), setup.path("account"), setup.path("secrets"));
        final JsonNode task =
                run(lifecycle, "enroll", "--project", PROJECT, "--task", "porelo-demo-alice")
                        .path("secrets");
        final Map<String, String> all = with(lifecycle, task);

        run(all, "retire", "--project", PROJECT, "--task", "porelo-demo-alice");
        run(all, "retire", "--project", PROJECT, "--task", "porelo-demo-alice");

        assertThat(status(url(), task.path(Config.ACCESS_TOKEN).asText())).isEqualTo(401);
    }

    @Test
    @DisplayName(
            "A person joins once, with a login shown once; a second join needs --reset, which gives a new password")
    void join() throws Exception {
        final JsonNode setup = run(firstRun(), "setup", "--project", PROJECT);
        final Map<String, String> lifecycle =
                with(firstRun(), setup.path("account"), setup.path("secrets"));

        final JsonNode login =
                run(lifecycle, "join", "--project", PROJECT, "--person", "carol").path("login");
        assertThat(login.path("user").asText()).isEqualTo("@carol:" + SERVER_NAME);
        assertThat(login.path("room").asText()).isEqualTo("#porelo-demo:" + SERVER_NAME);
        assertThat(login.path("password").asText()).isNotEmpty();

        final Transport.Result again =
                attempt(lifecycle, "join", "--project", PROJECT, "--person", "carol");
        assertThat(again.exit()).isEqualTo(Exit.NOT_PERMITTED);
        assertThat(again.err()).contains("--reset");

        final JsonNode reset =
                run(lifecycle, "join", "--project", PROJECT, "--person", "carol", "--reset")
                        .path("login");
        assertThat(reset.path("password").asText()).isNotEqualTo(login.path("password").asText());
    }

    @Test
    @DisplayName(
            "Settings the transport does not know, or a relative CA file, are refused with 78 before anything is made")
    void badSettings() {
        settings = "{\"federation\":true}";
        assertThat(attempt(firstRun(), "setup", "--project", PROJECT).exit())
                .isEqualTo(Exit.CONFIG);

        settings = "{\"ca_file\":\"certs/ca.pem\"}";
        assertThat(attempt(firstRun(), "setup", "--project", PROJECT).exit())
                .isEqualTo(Exit.CONFIG);
    }

    @Test
    @DisplayName(
            "Arguments not in the lifecycle's shape are 64, and so is a person named like the machinery")
    void badArguments() {
        assertThat(attempt(firstRun(), "setup").exit()).isEqualTo(Exit.USAGE);
        assertThat(attempt(firstRun(), "enroll", "--project", PROJECT).exit())
                .isEqualTo(Exit.USAGE);
        assertThat(attempt(firstRun(), "setup", "--project", "Has Spaces").exit())
                .isEqualTo(Exit.USAGE);
        assertThat(
                        attempt(
                                        firstRun(),
                                        "join",
                                        "--project",
                                        PROJECT,
                                        "--person",
                                        "porelo-admin")
                                .exit())
                .isEqualTo(Exit.USAGE);
    }
}
