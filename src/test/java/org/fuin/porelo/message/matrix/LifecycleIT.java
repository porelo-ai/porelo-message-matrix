package org.fuin.porelo.message.matrix;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
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
 * The lifecycle Porelo drives - setup, enroll, retire, join - against the real homeserver, as a
 * project's homeserver that is not the account's own: named in the settings, registered on with its
 * token. Each test has a fresh homeserver, since setup's account must be the first one registered.
 */
class LifecycleIT {

    private static final String PROJECT = "demo";

    @TempDir private Path dir;

    private Tuwunel tuwunel;

    private String settings;

    @BeforeEach
    void setUp() throws IOException, InterruptedException {
        tuwunel = Tuwunel.start();
        settings = "{\"homeserver\":\"" + tuwunel.url() + "\"}";
    }

    @AfterEach
    void tearDown() throws IOException, InterruptedException {
        // A start that failed leaves none to close, and must not hide why it failed.
        if (tuwunel != null) {
            tuwunel.close();
        }
    }

    /**
     * Runs a verb as Porelo does: settings on stdin, the kept secrets as the environment; answers
     * its JSON.
     */
    private JsonNode run(final Map<String, String> env, final String... args) throws IOException {
        final Transport.Result result = Transport.runWithInput(env, settings, args);
        assertThat(result.exit())
                .as("%s: %s", String.join(" ", args), result.err())
                .isEqualTo(Exit.OK);
        return MatrixClient.JSON.readTree(result.out());
    }

    private Transport.Result attempt(final Map<String, String> env, final String... args) {
        return Transport.runWithInput(env, settings, args);
    }

    private Map<String, String> firstRun() {
        final Map<String, String> env = new HashMap<>();
        env.put(Config.REGISTRATION_TOKEN, tuwunel.registrationToken());
        return env;
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
        assertThat(secrets.path(Config.HOMESERVER).asText()).isEqualTo(tuwunel.url());
        assertThat(secrets.path(Config.ACCESS_TOKEN).asText()).isNotEmpty();
        assertThat(answer.path("conversation").asText()).startsWith("!");
        assertThat(answer.path("reaches").get(0).asText())
                .isEqualTo(URI.create(tuwunel.url()).getAuthority());
        assertThat(status(tuwunel.url(), secrets.path(Config.ACCESS_TOKEN).asText()))
                .isEqualTo(200);
    }

    /**
     * Harm guarded: Porelo hands a transport no variable of its own choosing, so without the file a
     * homeserver elsewhere could not be set up through Porelo at all.
     */
    @Test
    @DisplayName(
            "setup on a homeserver elsewhere takes the registration token from the file the settings name")
    void setupTakesTheTokenFromItsFile() throws Exception {
        final Path token = Files.writeString(dir.resolve("token"), tuwunel.registrationToken());
        Files.setPosixFilePermissions(token, PosixFilePermissions.fromString("rw-------"));
        settings =
                "{\"homeserver\":\""
                        + tuwunel.url()
                        + "\",\"registration_token_file\":\""
                        + token
                        + "\"}";

        final JsonNode answer = run(new HashMap<>(), "setup", "--project", PROJECT);

        assertThat(answer.path("account").path(Config.ADMIN_TOKEN).asText()).isNotEmpty();
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
        assertThat(status(tuwunel.url(), alice.path("secrets").path(Config.ACCESS_TOKEN).asText()))
                .isEqualTo(401);
        assertThat(status(tuwunel.url(), setup.path("account").path(Config.ADMIN_TOKEN).asText()))
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
        assertThat(
                        status(
                                tuwunel.url(),
                                aliceAgain.path("secrets").path(Config.ACCESS_TOKEN).asText()))
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
        final String server = Tuwunel.SERVER_NAME;

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

    /** An access token for a person, as their Matrix client would log in; empty when refused. */
    private String login(final String user, final String password)
            throws IOException, InterruptedException {
        final HttpResponse<String> response =
                HttpClient.newHttpClient()
                        .send(
                                HttpRequest.newBuilder(
                                                URI.create(
                                                        tuwunel.url() + "/_matrix/client/v3/login"))
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

    private List<String> joinedRooms(final String token) throws IOException, InterruptedException {
        final HttpResponse<String> response =
                HttpClient.newHttpClient()
                        .send(
                                HttpRequest.newBuilder(
                                                URI.create(
                                                        tuwunel.url()
                                                                + "/_matrix/client/v3/joined_rooms"))
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
        final Tuwunel.Account mallory = tuwunel.register("mallory");
        final String theirs = tuwunel.claimAlias(mallory, "porelo-" + PROJECT);

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
        assertThat(status(tuwunel.url(), mallory.accessToken())).isEqualTo(200);
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
                                                        tuwunel.url()
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
                                                                tuwunel.url()
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
                                                            tuwunel.url()
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

        tuwunel.reset();

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

        final String by = "@porelo-demo-bob:" + Tuwunel.SERVER_NAME;
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
        assertThat(again.path("address").asText())
                .isEqualTo("@porelo-demo-alice:" + Tuwunel.SERVER_NAME);

        assertThat(second).isNotEqualTo(first);
        assertThat(status(tuwunel.url(), second)).isEqualTo(200);
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
        assertThat(status(tuwunel.url(), alice.path("secrets").path(Config.ACCESS_TOKEN).asText()))
                .isEqualTo(200);
        assertThat(attempt(task, "rename", "--display", "a@b").exit()).isEqualTo(Exit.USAGE);
        assertThat(attempt(task, "rename").exit()).isEqualTo(Exit.USAGE);
    }

    private String displayName(final String userId) throws IOException, InterruptedException {
        final java.net.http.HttpResponse<String> answer =
                java.net.http.HttpClient.newHttpClient()
                        .send(
                                java.net.http.HttpRequest.newBuilder(
                                                java.net.URI.create(
                                                        tuwunel.url()
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

        assertThat(status(tuwunel.url(), task.path(Config.ACCESS_TOKEN).asText())).isEqualTo(401);
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
        assertThat(login.path("user").asText()).isEqualTo("@carol:" + Tuwunel.SERVER_NAME);
        assertThat(login.path("room").asText()).isEqualTo("#porelo-demo:" + Tuwunel.SERVER_NAME);
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
        settings = "{\"homeserver\":\"" + tuwunel.url() + "\",\"federation\":true}";
        assertThat(attempt(firstRun(), "setup", "--project", PROJECT).exit())
                .isEqualTo(Exit.CONFIG);

        settings = "{\"homeserver\":\"" + tuwunel.url() + "\",\"ca_file\":\"certs/ca.pem\"}";
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
