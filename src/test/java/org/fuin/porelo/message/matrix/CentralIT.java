package org.fuin.porelo.message.matrix;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The central lifecycle mode - {@code setup}, {@code enroll}, {@code retire}, {@code join}, {@code
 * part} and {@code clear --project} when the project's settings name a {@code homeserver} and a
 * {@code room} - against one real homeserver shared by two machines, with a room made and
 * administered by a person.
 */
class CentralIT {

    private static final String PROJECT = "demo";

    private static final String MACHINE_ONE = "alpha";

    private static final String MACHINE_TWO = "beta";

    @TempDir private Path dir;

    private Tuwunel tuwunel;

    private Tuwunel.Account person;

    private String room;

    private String settings;

    private final HttpClient http = HttpClient.newHttpClient();

    /** Starts the central homeserver and has a person create the project's room. */
    @BeforeEach
    void setUp() throws IOException, InterruptedException {
        tuwunel = Tuwunel.start();
        person = tuwunel.register("carol");
        room = createCentralRoom(person, true);
        settings = settingsJson(room);
    }

    /** Stops the homeserver after the test. */
    @AfterEach
    void tearDown() throws IOException, InterruptedException {
        if (tuwunel != null) {
            tuwunel.close();
        }
    }

    /**
     * Runs a verb through {@link Transport#runWithInput} and returns its parsed JSON output after
     * asserting exit code 0.
     */
    private JsonNode run(final Map<String, String> env, final String... args) throws IOException {
        final Transport.Result result = attempt(env, args);
        assertThat(result.exit())
                .as("%s: %s", String.join(" ", args), result.err())
                .isEqualTo(Exit.OK);
        return MatrixClient.JSON.readTree(result.out());
    }

    /**
     * Runs a verb through {@link Transport#runWithInput} with the current settings on standard
     * input.
     */
    private Transport.Result attempt(final Map<String, String> env, final String... args) {
        return Transport.runWithInput(env, settings, args);
    }

    /** The base environment carrying the homeserver's registration token and a local state dir. */
    private Map<String, String> baseEnv() {
        final Map<String, String> env = new HashMap<>();
        env.put(Config.REGISTRATION_TOKEN, tuwunel.registrationToken());
        env.put("XDG_STATE_HOME", dir.resolve("state").toString());
        return env;
    }

    /** Builds the project's settings JSON naming the central homeserver and {@code roomRef}. */
    private String settingsJson(final String roomRef) {
        return "{\"homeserver\":\"" + tuwunel.url() + "\",\"room\":\"" + roomRef + "\"}";
    }

    /** Merges what earlier verbs printed over {@code base}, as Porelo builds the environment. */
    private static Map<String, String> with(
            final Map<String, String> base, final JsonNode... maps) {
        final Map<String, String> env = new HashMap<>(base);
        for (final JsonNode map : maps) {
            map.properties().forEach(entry -> env.put(entry.getKey(), entry.getValue().asText()));
        }
        return env;
    }

    /**
     * Creates a private-chat room as {@code owner} and sets {@code m.room.guest_access} to {@code
     * forbidden} or {@code can_join}.
     */
    private String createCentralRoom(final Tuwunel.Account owner, final boolean forbidGuests)
            throws IOException, InterruptedException {
        final String roomId = tuwunel.createRoom(owner);
        final ObjectNode guestAccess =
                MatrixClient.JSON
                        .createObjectNode()
                        .put("guest_access", forbidGuests ? "forbidden" : "can_join");
        final HttpResponse<String> response =
                http.send(
                        HttpRequest.newBuilder(
                                        URI.create(
                                                tuwunel.url()
                                                        + "/_matrix/client/v3/rooms/"
                                                        + MatrixClient.segment(roomId)
                                                        + "/state/m.room.guest_access/"))
                                .header("Authorization", "Bearer " + owner.accessToken())
                                .header("Content-Type", "application/json")
                                .PUT(HttpRequest.BodyPublishers.ofString(guestAccess.toString()))
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode())
                .as("setting guest_access on %s: %s", roomId, response.body())
                .isEqualTo(200);
        return roomId;
    }

    /** Invites {@code userId} into {@code roomId} as {@code inviter}. */
    private void invite(final Tuwunel.Account inviter, final String roomId, final String userId)
            throws IOException, InterruptedException {
        final ObjectNode body = MatrixClient.JSON.createObjectNode().put("user_id", userId);
        final HttpResponse<String> response =
                http.send(
                        HttpRequest.newBuilder(
                                        URI.create(
                                                tuwunel.url()
                                                        + "/_matrix/client/v3/rooms/"
                                                        + MatrixClient.segment(roomId)
                                                        + "/invite"))
                                .header("Authorization", "Bearer " + inviter.accessToken())
                                .header("Content-Type", "application/json")
                                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode())
                .as("inviting %s to %s: %s", userId, roomId, response.body())
                .isEqualTo(200);
    }

    /** Queries {@code /account/whoami} with {@code token} and returns the HTTP status code. */
    private int status(final String token) throws IOException, InterruptedException {
        return http.send(
                        HttpRequest.newBuilder(
                                        URI.create(
                                                tuwunel.url()
                                                        + "/_matrix/client/v3/account/whoami"))
                                .header("Authorization", "Bearer " + token)
                                .build(),
                        HttpResponse.BodyHandlers.discarding())
                .statusCode();
    }

    /**
     * Harm guarded: Porelo hands a transport no variable of its own choosing, so without reading
     * {@code registration_token_file} a machine on a central homeserver could not register at all.
     */
    @Test
    @DisplayName(
            "setup on a central homeserver takes the machine's registration token from registration_token_file")
    void setupTakesTheTokenFromItsFile() throws Exception {
        final Path token = Files.writeString(dir.resolve("token"), tuwunel.registrationToken());
        Files.setPosixFilePermissions(token, PosixFilePermissions.fromString("rw-------"));
        settings =
                "{\"homeserver\":\""
                        + tuwunel.url()
                        + "\",\"room\":\""
                        + room
                        + "\",\"registration_token_file\":\""
                        + token
                        + "\"}";

        final JsonNode answer =
                run(new HashMap<>(), "setup", "--project", PROJECT, "--machine", MACHINE_ONE);

        final String adminToken = answer.path("account").path(Config.ADMIN_TOKEN).asText();
        assertThat(adminToken).as("machine account token").isNotEmpty();
        assertThat(answer.path("account").path(Config.MACHINE_SECRET).asText())
                .as("machine secret kept in account")
                .isNotEmpty();
        assertThat(answer.path("machine").asText())
                .as("machine reported by setup")
                .isEqualTo(MACHINE_ONE);
        assertThat(status(adminToken)).as("machine token accepted by homeserver").isEqualTo(200);
    }

    /**
     * Harm guarded: before a person invites a machine into the project's room, setup must still
     * succeed and hand back the machine's account secrets so the registered account is kept, while
     * reporting admitted false so Porelo starts no task yet; once invited, setup must join the room
     * and admit the machine's relay.
     */
    @Test
    @DisplayName(
            "setup before the person invites the machine prints admitted false; after the invite it prints admitted true and the room, and the relay is in the room")
    void setupBeforeAndAfterInviteAdmitsMachineAndRelay() throws Exception {
        final JsonNode before =
                run(baseEnv(), "setup", "--project", PROJECT, "--machine", MACHINE_ONE);

        assertThat(before.path("admitted").isBoolean()).as("admitted is a boolean").isTrue();
        assertThat(before.path("admitted").asBoolean())
                .as("not admitted before the person invites the machine")
                .isFalse();
        assertThat(before.path("machine").asText()).as("machine name").isEqualTo(MACHINE_ONE);
        assertThat(before.path("account").path(Config.ADMIN_TOKEN).asText())
                .as("machine token kept before admission")
                .isNotEmpty();
        assertThat(before.path("account").path(Config.MACHINE_SECRET).asText())
                .as("machine secret kept before admission")
                .isNotEmpty();
        assertThat(before.path("account").path(Config.MACHINE).asText())
                .as("machine name kept in account")
                .isEqualTo(MACHINE_ONE);
        assertThat(before.has("conversation")).as("no conversation while not admitted").isFalse();
        assertThat(before.has("secrets")).as("no relay secrets while not admitted").isFalse();

        invite(person, room, "@porelo." + MACHINE_ONE + ":" + Tuwunel.SERVER_NAME);

        final JsonNode after =
                run(
                        with(baseEnv(), before.path("account")),
                        "setup",
                        "--project",
                        PROJECT,
                        "--machine",
                        MACHINE_ONE);

        assertThat(after.path("admitted").asBoolean())
                .as("admitted after the person invited the machine")
                .isTrue();
        assertThat(after.path("machine").asText()).as("machine name").isEqualTo(MACHINE_ONE);
        assertThat(after.path("conversation").asText()).as("the room id").isEqualTo(room);
        assertThat(after.path("reaches").get(0).asText())
                .as("the central homeserver's host and port")
                .isEqualTo(URI.create(tuwunel.url()).getAuthority());
        final String relayToken = after.path("secrets").path(Config.ACCESS_TOKEN).asText();
        assertThat(relayToken).as("relay token").isNotEmpty();
        assertThat(status(relayToken)).as("relay token accepted by homeserver").isEqualTo(200);
        assertThat(
                        tuwunel.membership(
                                person,
                                room,
                                "@porelo-relay-"
                                        + PROJECT
                                        + "."
                                        + MACHINE_ONE
                                        + ":"
                                        + Tuwunel.SERVER_NAME))
                .as("relay membership in the project's room")
                .isEqualTo("join");
    }

    /**
     * Harm guarded: upper-case host names must not prevent a machine and its tasks from joining
     * their project's conversation or disagree with the machine name kept in the account.
     */
    @Test
    @DisplayName("A machine named in upper case is taken in lower case for setup and enroll")
    void aMachineNamedInUpperCaseIsTakenInLowerCase() throws Exception {
        final JsonNode before =
                run(baseEnv(), "setup", "--project", PROJECT, "--machine", "Host-A");

        assertThat(before.path("machine").asText())
                .as("setup reports the host name in lower case")
                .isEqualTo("host-a");
        assertThat(before.path("account").path(Config.MACHINE).asText())
                .as("the kept machine name agrees with the normalized host name")
                .isEqualTo("host-a");

        final String machineUserId = "@porelo.host-a:" + Tuwunel.SERVER_NAME;
        final String adminToken = before.path("account").path(Config.ADMIN_TOKEN).asText();
        final HttpResponse<String> identity =
                http.send(
                        HttpRequest.newBuilder(
                                        URI.create(
                                                tuwunel.url()
                                                        + "/_matrix/client/v3/account/whoami"))
                                .header("Authorization", "Bearer " + adminToken)
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
        assertThat(identity.statusCode())
                .as("the homeserver accepts the registered machine account's token")
                .isEqualTo(200);
        assertThat(MatrixClient.JSON.readTree(identity.body()).path("user_id").asText())
                .as("the homeserver registered the machine with a lower-case localpart")
                .isEqualTo(machineUserId);

        invite(person, room, machineUserId);
        final Map<String, String> account = with(baseEnv(), before.path("account"));
        final JsonNode setup = run(account, "setup", "--project", PROJECT, "--machine", "Host-A");
        assertThat(setup.path("admitted").asBoolean(false))
                .as("the machine invited as @porelo.host-a is admitted to the project's room")
                .isTrue();
        final JsonNode enrolled =
                run(
                        with(account, setup.path("account"), setup.path("secrets")),
                        "enroll",
                        "--project",
                        PROJECT,
                        "--task",
                        "porelo-demo-alice",
                        "--machine",
                        "HOST-A");

        assertThat(enrolled.path("address").asText())
                .as("enroll accepts the same host in upper case with the kept account secrets")
                .endsWith(".host-a:" + Tuwunel.SERVER_NAME);
    }

    /**
     * Harm guarded: a second machine given the same machine name holds a different secret and must
     * be refused with 78 rather than acting on or overwriting the first machine's account.
     */
    @Test
    @DisplayName("setup with a machine name another machine holds is refused with 78")
    void setupWithAMachineNameAnotherMachineHoldsIsRefused() throws Exception {
        final JsonNode first =
                run(baseEnv(), "setup", "--project", PROJECT, "--machine", MACHINE_ONE);
        final String firstToken = first.path("account").path(Config.ADMIN_TOKEN).asText();

        final Transport.Result otherMachine =
                attempt(baseEnv(), "setup", "--project", PROJECT, "--machine", MACHINE_ONE);

        assertThat(otherMachine.exit())
                .as("second machine claiming %s: %s", MACHINE_ONE, otherMachine.err())
                .isEqualTo(Exit.CONFIG);
        assertThat(status(firstToken)).as("the first machine's token stays valid").isEqualTo(200);
    }

    /**
     * Harm guarded: a room that lets guests in exposes the project's messages to unauthenticated
     * guests, so setup must leave the room and refuse it with 78.
     */
    @Test
    @DisplayName(
            "A room that lets guests in is refused by setup with 78, and the machine leaves it")
    void roomAllowingGuestsIsRefused() throws Exception {
        final String openToGuests = createCentralRoom(person, false);
        settings = settingsJson(openToGuests);
        final JsonNode before =
                run(baseEnv(), "setup", "--project", PROJECT, "--machine", MACHINE_ONE);
        final String machineUserId = "@porelo." + MACHINE_ONE + ":" + Tuwunel.SERVER_NAME;
        invite(person, openToGuests, machineUserId);

        final Transport.Result result =
                attempt(
                        with(baseEnv(), before.path("account")),
                        "setup",
                        "--project",
                        PROJECT,
                        "--machine",
                        MACHINE_ONE);

        assertThat(result.exit())
                .as("setup against a room allowing guests: %s", result.err())
                .isEqualTo(Exit.CONFIG);
        assertThat(tuwunel.membership(person, openToGuests, machineUserId))
                .as("the machine's account left the room")
                .isEqualTo("leave");
    }

    /** Sets {@code m.room.join_rules} on {@code roomId} as {@code owner}. */
    private void setJoinRule(
            final Tuwunel.Account owner, final String roomId, final String joinRule)
            throws IOException, InterruptedException {
        final ObjectNode body = MatrixClient.JSON.createObjectNode().put("join_rule", joinRule);
        final HttpResponse<String> response =
                http.send(
                        HttpRequest.newBuilder(
                                        URI.create(
                                                tuwunel.url()
                                                        + "/_matrix/client/v3/rooms/"
                                                        + MatrixClient.segment(roomId)
                                                        + "/state/m.room.join_rules/"))
                                .header("Authorization", "Bearer " + owner.accessToken())
                                .header("Content-Type", "application/json")
                                .PUT(HttpRequest.BodyPublishers.ofString(body.toString()))
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode())
                .as("setting join_rule on %s: %s", roomId, response.body())
                .isEqualTo(200);
    }

    /**
     * Harm guarded: if the first setup attempts to join a public room and fails its room check
     * before printing the newly registered machine account, the generated secret and token are lost
     * and the machine can never set up under its name again.
     */
    @Test
    @DisplayName(
            "A first setup against a public room exits 0 with admitted false and the account; a second refuses with 78; once invite-only and invited, a third is admitted")
    void firstSetupAgainstPublicRoomPrintsAccountBeforeRoomIsChecked() throws Exception {
        setJoinRule(person, room, "public");

        final JsonNode first =
                run(baseEnv(), "setup", "--project", PROJECT, "--machine", MACHINE_ONE);

        assertThat(first.path("admitted").asBoolean())
                .as("first setup is not admitted before an invite")
                .isFalse();
        assertThat(first.path("account").path(Config.MACHINE_SECRET).asText())
                .as("machine secret printed on first setup")
                .isNotEmpty();
        assertThat(first.path("account").path(Config.ADMIN_TOKEN).asText())
                .as("machine token printed on first setup")
                .isNotEmpty();
        assertThat(first.path("account").path(Config.MACHINE).asText())
                .as("machine name printed in account on first setup")
                .isEqualTo(MACHINE_ONE);

        final Map<String, String> withAccount = with(baseEnv(), first.path("account"));
        final Transport.Result second =
                attempt(withAccount, "setup", "--project", PROJECT, "--machine", MACHINE_ONE);

        assertThat(second.exit())
                .as("second setup against public room refused: %s", second.err())
                .isEqualTo(Exit.CONFIG);

        setJoinRule(person, room, "invite");
        invite(person, room, "@porelo." + MACHINE_ONE + ":" + Tuwunel.SERVER_NAME);

        final JsonNode third =
                run(withAccount, "setup", "--project", PROJECT, "--machine", MACHINE_ONE);

        assertThat(third.path("admitted").asBoolean())
                .as("admitted once room is invite-only and machine is invited")
                .isTrue();
        assertThat(third.path("conversation").asText()).as("conversation room").isEqualTo(room);
    }

    /**
     * Harm guarded: tasks on two machines in one project must share one conversation in the central
     * room so a task on one machine sends a message that the other machine's relay polls and the
     * other machine's task reads.
     */
    @Test
    @DisplayName(
            "enroll registers @T.M and a task of each machine sends to and is read by the other's")
    void enrolledTasksAcrossTwoMachinesTalk() throws Exception {
        final JsonNode preOne =
                run(baseEnv(), "setup", "--project", PROJECT, "--machine", MACHINE_ONE);
        final JsonNode preTwo =
                run(baseEnv(), "setup", "--project", PROJECT, "--machine", MACHINE_TWO);
        invite(person, room, "@porelo." + MACHINE_ONE + ":" + Tuwunel.SERVER_NAME);
        invite(person, room, "@porelo." + MACHINE_TWO + ":" + Tuwunel.SERVER_NAME);
        final JsonNode setupOne =
                run(
                        with(baseEnv(), preOne.path("account")),
                        "setup",
                        "--project",
                        PROJECT,
                        "--machine",
                        MACHINE_ONE);
        final JsonNode setupTwo =
                run(
                        with(baseEnv(), preTwo.path("account")),
                        "setup",
                        "--project",
                        PROJECT,
                        "--machine",
                        MACHINE_TWO);

        final Map<String, String> lifecycleOne =
                with(baseEnv(), setupOne.path("account"), setupOne.path("secrets"));
        final Map<String, String> lifecycleTwo =
                with(baseEnv(), setupTwo.path("account"), setupTwo.path("secrets"));

        final JsonNode enrolledAlice =
                run(
                        lifecycleOne,
                        "enroll",
                        "--project",
                        PROJECT,
                        "--task",
                        "porelo-demo-alice",
                        "--machine",
                        MACHINE_ONE);
        final JsonNode enrolledBob =
                run(
                        lifecycleTwo,
                        "enroll",
                        "--project",
                        PROJECT,
                        "--task",
                        "porelo-demo-bob",
                        "--machine",
                        MACHINE_TWO);

        assertThat(enrolledAlice.path("address").asText())
                .as("alice's address on machine %s", MACHINE_ONE)
                .isEqualTo("@porelo-demo-alice." + MACHINE_ONE + ":" + Tuwunel.SERVER_NAME);
        assertThat(enrolledBob.path("address").asText())
                .as("bob's address on machine %s", MACHINE_TWO)
                .isEqualTo("@porelo-demo-bob." + MACHINE_TWO + ":" + Tuwunel.SERVER_NAME);

        final Map<String, String> alice = with(Map.of(), enrolledAlice.path("secrets"));
        final Path stateTwo = dir.resolve("state-two");
        final Map<String, String> bob =
                with(Map.of("XDG_STATE_HOME", stateTwo.toString()), enrolledBob.path("secrets"));

        final Path message =
                Files.writeString(
                        dir.resolve("m.json"), "{\"to\":\"bob\"}", StandardCharsets.UTF_8);
        final Path sig = Files.write(dir.resolve("m.json.sig"), new byte[] {1, 2, 3});
        final String reference =
                run(alice, "send", message.toString(), sig.toString(), "--to", room)
                        .path("reference")
                        .asText();

        final Path inbound = Files.createDirectories(dir.resolve("in"));
        final Map<String, String> relayTwo =
                with(Map.of("XDG_STATE_HOME", stateTwo.toString()), setupTwo.path("secrets"));
        assertThat(Transport.run(relayTwo, "poll", "--into", inbound.toString()).exit())
                .as("relay on machine %s polls the message", MACHINE_TWO)
                .isEqualTo(Exit.OK);
        final String delivered;
        try (Stream<Path> files = Files.list(inbound)) {
            delivered =
                    files.map(f -> f.getFileName().toString())
                            .filter(n -> n.endsWith(".json"))
                            .findFirst()
                            .orElseThrow();
        }
        assertThat(Files.readString(inbound.resolve(delivered), StandardCharsets.UTF_8))
                .as("message body delivered to the second machine")
                .isEqualTo("{\"to\":\"bob\"}");

        final String by = enrolledBob.path("address").asText();
        assertThat(run(alice, "receipt", reference, "--by", by).path("state").asText())
                .as("receipt before bob reads")
                .isEqualTo("delivered");
        assertThat(
                        Transport.run(
                                        bob,
                                        "read",
                                        delivered.substring(
                                                0, delivered.length() - ".json".length()))
                                .exit())
                .as("bob marks the message read")
                .isEqualTo(Exit.OK);
        assertThat(run(alice, "receipt", reference, "--by", by).path("state").asText())
                .as("receipt after bob reads")
                .isEqualTo("read");
    }

    /**
     * Harm guarded: enrolling a task again must invalidate every earlier session token of that
     * task's account so a lost or stale token can no longer act as the task.
     */
    @Test
    @DisplayName("Enrolling the same task again gets a new token and the old token answers 401")
    void enrollAgainReplacesTokenAndInvalidatesTheOldOne() throws Exception {
        final JsonNode pre =
                run(baseEnv(), "setup", "--project", PROJECT, "--machine", MACHINE_ONE);
        invite(person, room, "@porelo." + MACHINE_ONE + ":" + Tuwunel.SERVER_NAME);
        final JsonNode setup =
                run(
                        with(baseEnv(), pre.path("account")),
                        "setup",
                        "--project",
                        PROJECT,
                        "--machine",
                        MACHINE_ONE);
        final Map<String, String> lifecycle =
                with(baseEnv(), setup.path("account"), setup.path("secrets"));

        final String first =
                run(
                                lifecycle,
                                "enroll",
                                "--project",
                                PROJECT,
                                "--task",
                                "porelo-demo-alice",
                                "--machine",
                                MACHINE_ONE)
                        .path("secrets")
                        .path(Config.ACCESS_TOKEN)
                        .asText();
        assertThat(status(first)).as("first token valid before re-enroll").isEqualTo(200);

        final JsonNode again =
                run(
                        lifecycle,
                        "enroll",
                        "--project",
                        PROJECT,
                        "--task",
                        "porelo-demo-alice",
                        "--machine",
                        MACHINE_ONE);
        final String second = again.path("secrets").path(Config.ACCESS_TOKEN).asText();

        assertThat(again.path("address").asText())
                .as("task address stays the same")
                .isEqualTo("@porelo-demo-alice." + MACHINE_ONE + ":" + Tuwunel.SERVER_NAME);
        assertThat(second).as("new token issued").isNotEqualTo(first);
        assertThat(status(second)).as("new token accepted by homeserver").isEqualTo(200);
        assertThat(status(first)).as("old token logged out and refused with 401").isEqualTo(401);
    }

    /**
     * Harm guarded: two projects whose names run together can produce the same task container name
     * on one machine, and enrolling it in the second project would log out the first project's task
     * and put it into the second room.
     */
    @Test
    @DisplayName(
            "enroll of a task that is in another project's room on the same machine is refused with 78")
    void enrollOfATaskInAnotherProjectsRoomIsRefused() throws Exception {
        final String roomA = room;
        final String roomAb = createCentralRoom(person, true);
        settings = settingsJson(roomA);
        final JsonNode pre = run(baseEnv(), "setup", "--project", "a", "--machine", MACHINE_ONE);
        final String machineUserId = "@porelo." + MACHINE_ONE + ":" + Tuwunel.SERVER_NAME;
        invite(person, roomA, machineUserId);
        invite(person, roomAb, machineUserId);

        final Map<String, String> accountEnv = with(baseEnv(), pre.path("account"));
        final JsonNode setupA =
                run(accountEnv, "setup", "--project", "a", "--machine", MACHINE_ONE);
        settings = settingsJson(roomAb);
        final JsonNode setupAb =
                run(accountEnv, "setup", "--project", "a-b", "--machine", MACHINE_ONE);

        settings = settingsJson(roomA);
        final String firstToken =
                run(
                                with(accountEnv, setupA.path("secrets")),
                                "enroll",
                                "--project",
                                "a",
                                "--task",
                                "porelo-a-b-c",
                                "--machine",
                                MACHINE_ONE)
                        .path("secrets")
                        .path(Config.ACCESS_TOKEN)
                        .asText();

        settings = settingsJson(roomAb);
        final Transport.Result collision =
                attempt(
                        with(accountEnv, setupAb.path("secrets")),
                        "enroll",
                        "--project",
                        "a-b",
                        "--task",
                        "porelo-a-b-c",
                        "--machine",
                        MACHINE_ONE);

        assertThat(collision.exit())
                .as("enrolling a task already in another project's room: %s", collision.err())
                .isEqualTo(Exit.CONFIG);
        assertThat(status(firstToken))
                .as("the first project's task keeps its session")
                .isEqualTo(200);
    }

    /**
     * Harm guarded: on a central homeserver people are administered at the homeserver itself, so
     * join must refuse with 77 rather than registering or resetting a person's account.
     */
    @Test
    @DisplayName("join on a central homeserver exits with 77")
    void joinOnACentralHomeserverIsNotPermitted() {
        final Transport.Result result =
                attempt(
                        baseEnv(),
                        "join",
                        "--project",
                        PROJECT,
                        "--person",
                        "dave",
                        "--machine",
                        MACHINE_ONE);

        assertThat(result.exit())
                .as("central join: %s", result.err())
                .isEqualTo(Exit.NOT_PERMITTED);
    }

    /** Harm guarded: part must not remove or lock a person managed by a central homeserver. */
    @Test
    @DisplayName("part on a central homeserver exits with 77 and leaves the person alone")
    void partOnACentralHomeserverIsNotPermitted() throws Exception {
        final Transport.Result result =
                attempt(baseEnv(), "part", "--project", PROJECT, "--person", "carol");

        assertThat(result.exit())
                .as("central part: %s", result.err())
                .isEqualTo(Exit.NOT_PERMITTED);
        assertThat(status(person.accessToken()))
                .as("the person's session stays valid")
                .isEqualTo(200);
        assertThat(tuwunel.membership(person, room, person.userId()))
                .as("the person remains in the central room")
                .isEqualTo("join");
    }

    /**
     * Harm guarded: without --machine on a central homeserver, setup would have no machine suffix
     * to namespace its accounts and must refuse with 78.
     */
    @Test
    @DisplayName("setup without --machine on a central homeserver exits with 78")
    void setupWithoutMachineIsRefused() throws Exception {
        // Fresh homeserver so non-central setup would otherwise register @porelo as admin and exit
        // 0.
        tuwunel.reset();
        settings = settingsJson("#porelo-" + PROJECT + ":" + Tuwunel.SERVER_NAME);

        final Transport.Result result = attempt(baseEnv(), "setup", "--project", PROJECT);

        assertThat(result.exit())
                .as("central setup without --machine: %s", result.err())
                .isEqualTo(Exit.CONFIG);
    }

    /**
     * Harm guarded: the secret and token in the environment belong to one machine; if setup is
     * called with a different --machine than PORELO_MATRIX_MACHINE, registering or logging in under
     * the new name with the first machine's secret would bind two machine names to one secret.
     */
    @Test
    @DisplayName(
            "setup refuses with 78 when --machine differs from PORELO_MATRIX_MACHINE in the environment")
    void setupWithDifferentMachineThanEnvironmentIsRefused() throws Exception {
        final JsonNode first =
                run(baseEnv(), "setup", "--project", PROJECT, "--machine", MACHINE_ONE);

        final Transport.Result mismatch =
                attempt(
                        with(baseEnv(), first.path("account")),
                        "setup",
                        "--project",
                        PROJECT,
                        "--machine",
                        MACHINE_TWO);

        assertThat(mismatch.exit())
                .as(
                        "setup with --machine %s while env has %s: %s",
                        MACHINE_TWO, MACHINE_ONE, mismatch.err())
                .isEqualTo(Exit.CONFIG);
        final HttpResponse<Void> available =
                http.send(
                        HttpRequest.newBuilder(
                                        URI.create(
                                                tuwunel.url()
                                                        + "/_matrix/client/v3/register/available?username=porelo."
                                                        + MACHINE_TWO))
                                .build(),
                        HttpResponse.BodyHandlers.discarding());
        assertThat(available.statusCode())
                .as("no provisioning account for the other machine was registered")
                .isEqualTo(200);
    }

    /**
     * Harm guarded: the secret and token in the environment belong to one machine; if enroll is
     * called with a different --machine than PORELO_MATRIX_MACHINE, deriving accounts for that
     * other machine from this machine's secret would forge accounts under the wrong secret.
     */
    @Test
    @DisplayName(
            "enroll refuses with 78 when --machine differs from PORELO_MATRIX_MACHINE in the environment")
    void enrollWithDifferentMachineThanEnvironmentIsRefused() throws Exception {
        final JsonNode pre =
                run(baseEnv(), "setup", "--project", PROJECT, "--machine", MACHINE_ONE);
        invite(person, room, "@porelo." + MACHINE_ONE + ":" + Tuwunel.SERVER_NAME);
        final JsonNode setup =
                run(
                        with(baseEnv(), pre.path("account")),
                        "setup",
                        "--project",
                        PROJECT,
                        "--machine",
                        MACHINE_ONE);
        final Map<String, String> lifecycle =
                with(baseEnv(), setup.path("account"), setup.path("secrets"));

        final Transport.Result mismatch =
                attempt(
                        lifecycle,
                        "enroll",
                        "--project",
                        PROJECT,
                        "--task",
                        "porelo-demo-alice",
                        "--machine",
                        MACHINE_TWO);

        assertThat(mismatch.exit())
                .as(
                        "enroll with --machine %s while env has %s: %s",
                        MACHINE_TWO, MACHINE_ONE, mismatch.err())
                .isEqualTo(Exit.CONFIG);
        final HttpResponse<Void> available =
                http.send(
                        HttpRequest.newBuilder(
                                        URI.create(
                                                tuwunel.url()
                                                        + "/_matrix/client/v3/register/available?username=porelo-demo-alice."
                                                        + MACHINE_TWO))
                                .build(),
                        HttpResponse.BodyHandlers.discarding());
        assertThat(available.statusCode())
                .as("no account for the other machine was registered on the homeserver")
                .isEqualTo(200);
    }

    /**
     * Harm guarded: running central setup again with the account and secrets it already printed
     * must reuse the valid machine and relay tokens instead of creating new sessions on every run.
     */
    @Test
    @DisplayName(
            "setup run again with what it printed keeps the machine account, the room and the relay")
    void setupAgainKeepsEverything() throws Exception {
        final JsonNode pre =
                run(baseEnv(), "setup", "--project", PROJECT, "--machine", MACHINE_ONE);
        invite(person, room, "@porelo." + MACHINE_ONE + ":" + Tuwunel.SERVER_NAME);
        final JsonNode first =
                run(
                        with(baseEnv(), pre.path("account")),
                        "setup",
                        "--project",
                        PROJECT,
                        "--machine",
                        MACHINE_ONE);

        final JsonNode second =
                run(
                        with(baseEnv(), first.path("account"), first.path("secrets")),
                        "setup",
                        "--project",
                        PROJECT,
                        "--machine",
                        MACHINE_ONE);

        assertThat(second).as("second setup with kept account and secrets").isEqualTo(first);
    }

    /**
     * Harm guarded: if a relay token is lost or dropped from the environment, central setup must
     * take the relay account back with Accounts.alone so any earlier token that leaked is logged
     * out and answers 401.
     */
    @Test
    @DisplayName(
            "setup with the account kept but the relay token dropped gets a new relay token and invalidates the old one")
    void setupWithoutKeptRelayTokenReplacesItAndInvalidatesTheOldOne() throws Exception {
        final JsonNode pre =
                run(baseEnv(), "setup", "--project", PROJECT, "--machine", MACHINE_ONE);
        invite(person, room, "@porelo." + MACHINE_ONE + ":" + Tuwunel.SERVER_NAME);
        final JsonNode first =
                run(
                        with(baseEnv(), pre.path("account")),
                        "setup",
                        "--project",
                        PROJECT,
                        "--machine",
                        MACHINE_ONE);
        final String firstRelay = first.path("secrets").path(Config.ACCESS_TOKEN).asText();
        assertThat(status(firstRelay))
                .as("first relay token valid before second setup")
                .isEqualTo(200);

        final JsonNode second =
                run(
                        with(baseEnv(), first.path("account")),
                        "setup",
                        "--project",
                        PROJECT,
                        "--machine",
                        MACHINE_ONE);
        final String secondRelay = second.path("secrets").path(Config.ACCESS_TOKEN).asText();

        assertThat(secondRelay).as("new relay token issued").isNotEqualTo(firstRelay);
        assertThat(status(secondRelay)).as("new relay token accepted by homeserver").isEqualTo(200);
        assertThat(status(firstRelay))
                .as("earlier relay token logged out and refused with 401")
                .isEqualTo(401);
    }

    /**
     * Harm guarded: a retired task's token must be logged out and the task must leave the room,
     * retiring it twice must succeed harmlessly, and because no administrator can bring a
     * deactivated name back on a central homeserver, enrolling the same task name again must
     * succeed with a new token.
     */
    @Test
    @DisplayName(
            "retire of a task logs out its token and leaves the room, retire twice exits 0, and enroll of the same task name again succeeds with a new token")
    void retireLogsTheTaskOutAndASecondRetireSucceeds() throws Exception {
        final JsonNode pre =
                run(baseEnv(), "setup", "--project", PROJECT, "--machine", MACHINE_ONE);
        invite(person, room, "@porelo." + MACHINE_ONE + ":" + Tuwunel.SERVER_NAME);
        final JsonNode setup =
                run(
                        with(baseEnv(), pre.path("account")),
                        "setup",
                        "--project",
                        PROJECT,
                        "--machine",
                        MACHINE_ONE);
        final Map<String, String> lifecycle =
                with(baseEnv(), setup.path("account"), setup.path("secrets"));
        final String taskName = "porelo-demo-alice";
        final String taskUserId = "@" + taskName + "." + MACHINE_ONE + ":" + Tuwunel.SERVER_NAME;

        final String token =
                run(
                                lifecycle,
                                "enroll",
                                "--project",
                                PROJECT,
                                "--task",
                                taskName,
                                "--machine",
                                MACHINE_ONE)
                        .path("secrets")
                        .path(Config.ACCESS_TOKEN)
                        .asText();
        assertThat(status(token)).as("task token valid before retire").isEqualTo(200);

        run(
                lifecycle,
                "retire",
                "--project",
                PROJECT,
                "--task",
                taskName,
                "--machine",
                MACHINE_ONE);

        assertThat(status(token)).as("retired task token refused with 401").isEqualTo(401);
        assertThat(tuwunel.membership(person, room, taskUserId))
                .as("retired task left the project's room")
                .isEqualTo("leave");

        run(
                lifecycle,
                "retire",
                "--project",
                PROJECT,
                "--task",
                taskName,
                "--machine",
                MACHINE_ONE);

        final String newToken =
                run(
                                lifecycle,
                                "enroll",
                                "--project",
                                PROJECT,
                                "--task",
                                taskName,
                                "--machine",
                                MACHINE_ONE)
                        .path("secrets")
                        .path(Config.ACCESS_TOKEN)
                        .asText();

        assertThat(newToken).as("new token issued after re-enroll").isNotEqualTo(token);
        assertThat(status(newToken)).as("new token accepted by homeserver").isEqualTo(200);
        assertThat(status(token)).as("earlier token still refused with 401").isEqualTo(401);
        assertThat(tuwunel.membership(person, room, taskUserId))
                .as("re-enrolled task joined the project's room again")
                .isEqualTo("join");
    }

    /**
     * Harm guarded: if PORELO_MATRIX_ADMIN_TOKEN belongs to another machine than the machine named
     * in the environment or --machine, enroll must refuse with 78 before touching any task account
     * of the named machine.
     */
    @Test
    @DisplayName(
            "enroll with another machine's token for this machine is refused with 78 and leaves this machine's task untouched")
    void enrollWithAnotherMachinesTokenIsRefused() throws Exception {
        final JsonNode preOne =
                run(baseEnv(), "setup", "--project", PROJECT, "--machine", MACHINE_ONE);
        final JsonNode preTwo =
                run(baseEnv(), "setup", "--project", PROJECT, "--machine", MACHINE_TWO);
        invite(person, room, "@porelo." + MACHINE_ONE + ":" + Tuwunel.SERVER_NAME);
        invite(person, room, "@porelo." + MACHINE_TWO + ":" + Tuwunel.SERVER_NAME);
        final JsonNode setupOne =
                run(
                        with(baseEnv(), preOne.path("account")),
                        "setup",
                        "--project",
                        PROJECT,
                        "--machine",
                        MACHINE_ONE);
        final JsonNode setupTwo =
                run(
                        with(baseEnv(), preTwo.path("account")),
                        "setup",
                        "--project",
                        PROJECT,
                        "--machine",
                        MACHINE_TWO);
        final Map<String, String> lifecycleOne =
                with(baseEnv(), setupOne.path("account"), setupOne.path("secrets"));

        final String aliceToken =
                run(
                                lifecycleOne,
                                "enroll",
                                "--project",
                                PROJECT,
                                "--task",
                                "porelo-demo-alice",
                                "--machine",
                                MACHINE_ONE)
                        .path("secrets")
                        .path(Config.ACCESS_TOKEN)
                        .asText();

        final Map<String, String> betaTokenWithAlphaMachine = new HashMap<>(lifecycleOne);
        betaTokenWithAlphaMachine.put(
                Config.ADMIN_TOKEN, setupTwo.path("account").path(Config.ADMIN_TOKEN).asText());
        final Transport.Result wrongToken =
                attempt(
                        betaTokenWithAlphaMachine,
                        "enroll",
                        "--project",
                        PROJECT,
                        "--task",
                        "porelo-demo-alice",
                        "--machine",
                        MACHINE_ONE);
        assertThat(wrongToken.exit())
                .as("enroll with beta's token for machine alpha: %s", wrongToken.err())
                .isEqualTo(Exit.CONFIG);
        assertThat(status(aliceToken))
                .as("alpha's task token stays untouched and answers 200")
                .isEqualTo(200);
    }

    /**
     * Harm guarded: --task porelo or --task porelo-relay-... on a central homeserver would derive
     * the localpart of the machine's own provisioning account or relay and log out its token, so
     * both must be refused with 78.
     */
    @Test
    @DisplayName(
            "enroll with --task porelo or --task porelo-relay-... on a central homeserver is refused with 78")
    void enrollReservedTaskNameIsRefused() throws Exception {
        final JsonNode pre =
                run(baseEnv(), "setup", "--project", PROJECT, "--machine", MACHINE_ONE);
        invite(person, room, "@porelo." + MACHINE_ONE + ":" + Tuwunel.SERVER_NAME);
        final JsonNode setup =
                run(
                        with(baseEnv(), pre.path("account")),
                        "setup",
                        "--project",
                        PROJECT,
                        "--machine",
                        MACHINE_ONE);
        final Map<String, String> lifecycle =
                with(baseEnv(), setup.path("account"), setup.path("secrets"));

        final Transport.Result reservedPorelo =
                attempt(
                        lifecycle,
                        "enroll",
                        "--project",
                        PROJECT,
                        "--task",
                        "porelo",
                        "--machine",
                        MACHINE_ONE);
        assertThat(reservedPorelo.exit())
                .as("enroll --task porelo: %s", reservedPorelo.err())
                .isEqualTo(Exit.CONFIG);
        assertThat(status(setup.path("account").path(Config.ADMIN_TOKEN).asText()))
                .as("machine provisioning token stays valid")
                .isEqualTo(200);

        final Transport.Result reservedRelay =
                attempt(
                        lifecycle,
                        "enroll",
                        "--project",
                        PROJECT,
                        "--task",
                        "porelo-relay-" + PROJECT,
                        "--machine",
                        MACHINE_ONE);
        assertThat(reservedRelay.exit())
                .as("enroll --task porelo-relay-...: %s", reservedRelay.err())
                .isEqualTo(Exit.CONFIG);
        assertThat(status(setup.path("secrets").path(Config.ACCESS_TOKEN).asText()))
                .as("relay token stays valid")
                .isEqualTo(200);
    }

    /**
     * Harm guarded: one machine must never be able to retire another machine's task account on the
     * shared homeserver, nor retire a task account that belongs to another project's room on the
     * same machine.
     */
    @Test
    @DisplayName(
            "retire through another machine's account or another project's room is refused with 78 and leaves the task active")
    void retireThroughAnotherMachinesAccountIsRefused() throws Exception {
        final JsonNode preOne =
                run(baseEnv(), "setup", "--project", PROJECT, "--machine", MACHINE_ONE);
        final JsonNode preTwo =
                run(baseEnv(), "setup", "--project", PROJECT, "--machine", MACHINE_TWO);
        invite(person, room, "@porelo." + MACHINE_ONE + ":" + Tuwunel.SERVER_NAME);
        invite(person, room, "@porelo." + MACHINE_TWO + ":" + Tuwunel.SERVER_NAME);
        final JsonNode setupOne =
                run(
                        with(baseEnv(), preOne.path("account")),
                        "setup",
                        "--project",
                        PROJECT,
                        "--machine",
                        MACHINE_ONE);
        final JsonNode setupTwo =
                run(
                        with(baseEnv(), preTwo.path("account")),
                        "setup",
                        "--project",
                        PROJECT,
                        "--machine",
                        MACHINE_TWO);
        final Map<String, String> lifecycleOne =
                with(baseEnv(), setupOne.path("account"), setupOne.path("secrets"));
        final Map<String, String> lifecycleTwo =
                with(baseEnv(), setupTwo.path("account"), setupTwo.path("secrets"));

        final String aliceToken =
                run(
                                lifecycleOne,
                                "enroll",
                                "--project",
                                PROJECT,
                                "--task",
                                "porelo-demo-alice",
                                "--machine",
                                MACHINE_ONE)
                        .path("secrets")
                        .path(Config.ACCESS_TOKEN)
                        .asText();
        assertThat(status(aliceToken)).as("alpha's task token valid before retire").isEqualTo(200);

        final Transport.Result betaOwnMachine =
                attempt(
                        lifecycleTwo,
                        "retire",
                        "--project",
                        PROJECT,
                        "--task",
                        "porelo-demo-alice",
                        "--machine",
                        MACHINE_TWO);
        assertThat(betaOwnMachine.exit())
                .as("beta retiring alpha's task name under beta: %s", betaOwnMachine.err())
                .isEqualTo(Exit.CONFIG);

        final Transport.Result betaMismatchMachine =
                attempt(
                        lifecycleTwo,
                        "retire",
                        "--project",
                        PROJECT,
                        "--task",
                        "porelo-demo-alice",
                        "--machine",
                        MACHINE_ONE);
        assertThat(betaMismatchMachine.exit())
                .as(
                        "beta retiring with --machine %s against beta's env: %s",
                        MACHINE_ONE, betaMismatchMachine.err())
                .isEqualTo(Exit.CONFIG);

        final Map<String, String> betaForgingAlpha = new HashMap<>(lifecycleTwo);
        betaForgingAlpha.put(Config.MACHINE, MACHINE_ONE);
        final Transport.Result betaWrongSecret =
                attempt(
                        betaForgingAlpha,
                        "retire",
                        "--project",
                        PROJECT,
                        "--task",
                        "porelo-demo-alice",
                        "--machine",
                        MACHINE_ONE);
        assertThat(betaWrongSecret.exit())
                .as(
                        "beta attempting to log in as alpha's task with beta's secret: %s",
                        betaWrongSecret.err())
                .isEqualTo(Exit.CONFIG);
        assertThat(status(aliceToken))
                .as("alpha's task keeps a token answering 200 after beta's attempts")
                .isEqualTo(200);

        final String otherRoom = createCentralRoom(person, true);
        invite(person, otherRoom, "@porelo." + MACHINE_ONE + ":" + Tuwunel.SERVER_NAME);
        settings = settingsJson(otherRoom);
        final JsonNode setupOther =
                run(
                        with(baseEnv(), setupOne.path("account")),
                        "setup",
                        "--project",
                        "other",
                        "--machine",
                        MACHINE_ONE);
        final Transport.Result otherProject =
                attempt(
                        with(baseEnv(), setupOne.path("account"), setupOther.path("secrets")),
                        "retire",
                        "--project",
                        "other",
                        "--task",
                        "porelo-demo-alice",
                        "--machine",
                        MACHINE_ONE);
        assertThat(otherProject.exit())
                .as(
                        "retire from another project's room on the same machine: %s",
                        otherProject.err())
                .isEqualTo(Exit.CONFIG);
        assertThat(status(aliceToken))
                .as("alpha's task keeps a token answering 200 after other project's attempt")
                .isEqualTo(200);

        settings = settingsJson(room);
        run(
                lifecycleOne,
                "retire",
                "--project",
                PROJECT,
                "--task",
                "porelo-demo-alice",
                "--machine",
                MACHINE_ONE);
        assertThat(status(aliceToken)).as("alpha's own retire logs its task out").isEqualTo(401);
    }

    /**
     * Harm guarded: clearing a project on one machine must log out that machine's relay and tasks
     * (including any task Porelo forgot to retire) and forget its relay's poll position, while
     * leaving this machine's provisioning account, other machines' accounts, the person and the
     * shared room untouched.
     */
    @Test
    @DisplayName(
            "clear --project logs out this machine's relay and tasks, including a forgotten one, and leaves the other machine's tasks, the person and the room; clear twice exits 0")
    void clearLogsThisMachinesAccountsOutAndLeavesOthersAndTheRoom() throws Exception {
        final JsonNode preOne =
                run(baseEnv(), "setup", "--project", PROJECT, "--machine", MACHINE_ONE);
        final JsonNode preTwo =
                run(baseEnv(), "setup", "--project", PROJECT, "--machine", MACHINE_TWO);
        invite(person, room, "@porelo." + MACHINE_ONE + ":" + Tuwunel.SERVER_NAME);
        invite(person, room, "@porelo." + MACHINE_TWO + ":" + Tuwunel.SERVER_NAME);
        final JsonNode setupOne =
                run(
                        with(baseEnv(), preOne.path("account")),
                        "setup",
                        "--project",
                        PROJECT,
                        "--machine",
                        MACHINE_ONE);
        final JsonNode setupTwo =
                run(
                        with(baseEnv(), preTwo.path("account")),
                        "setup",
                        "--project",
                        PROJECT,
                        "--machine",
                        MACHINE_TWO);
        final Map<String, String> lifecycleOne =
                with(baseEnv(), setupOne.path("account"), setupOne.path("secrets"));
        final Map<String, String> lifecycleTwo =
                with(baseEnv(), setupTwo.path("account"), setupTwo.path("secrets"));

        final String aliceToken =
                run(
                                lifecycleOne,
                                "enroll",
                                "--project",
                                PROJECT,
                                "--task",
                                "porelo-demo-alice",
                                "--machine",
                                MACHINE_ONE)
                        .path("secrets")
                        .path(Config.ACCESS_TOKEN)
                        .asText();
        // Enrolled and never retired: Porelo hands clear only setup's maps, not any task's.
        final String forgottenToken =
                run(
                                lifecycleOne,
                                "enroll",
                                "--project",
                                PROJECT,
                                "--task",
                                "porelo-demo-forgotten",
                                "--machine",
                                MACHINE_ONE)
                        .path("secrets")
                        .path(Config.ACCESS_TOKEN)
                        .asText();
        final String bobToken =
                run(
                                lifecycleTwo,
                                "enroll",
                                "--project",
                                PROJECT,
                                "--task",
                                "porelo-demo-bob",
                                "--machine",
                                MACHINE_TWO)
                        .path("secrets")
                        .path(Config.ACCESS_TOKEN)
                        .asText();

        final Path inbound = Files.createDirectories(dir.resolve("inbound"));
        assertThat(Transport.run(lifecycleOne, "poll", "--into", inbound.toString()).exit())
                .as("relay poll before clear writes a position")
                .isEqualTo(Exit.OK);

        final JsonNode cleared =
                run(lifecycleOne, "clear", "--project", PROJECT, "--machine", MACHINE_ONE);

        final String relayOneId =
                "@porelo-relay-" + PROJECT + "." + MACHINE_ONE + ":" + Tuwunel.SERVER_NAME;
        final String aliceId = "@porelo-demo-alice." + MACHINE_ONE + ":" + Tuwunel.SERVER_NAME;
        final String forgottenId =
                "@porelo-demo-forgotten." + MACHINE_ONE + ":" + Tuwunel.SERVER_NAME;
        final String bobId = "@porelo-demo-bob." + MACHINE_TWO + ":" + Tuwunel.SERVER_NAME;

        assertThat(cleared.path("removed"))
                .as("accounts and poll position removed by clear on machine %s", MACHINE_ONE)
                .extracting(JsonNode::asText)
                .containsExactlyInAnyOrder(
                        "the account " + relayOneId,
                        "the account " + aliceId,
                        "the account " + forgottenId,
                        "the poll position of " + relayOneId);
        assertThat(cleared.path("kept")).as("nothing kept on first clear").isEmpty();

        assertThat(status(setupOne.path("secrets").path(Config.ACCESS_TOKEN).asText()))
                .as("this machine's relay token answers 401 after clear")
                .isEqualTo(401);
        assertThat(status(aliceToken))
                .as("this machine's task token answers 401 after clear")
                .isEqualTo(401);
        assertThat(status(forgottenToken))
                .as("the forgotten task's token answers 401 after clear")
                .isEqualTo(401);
        assertThat(status(setupOne.path("account").path(Config.ADMIN_TOKEN).asText()))
                .as("this machine's provisioning account @porelo.M stays active")
                .isEqualTo(200);
        assertThat(status(setupTwo.path("account").path(Config.ADMIN_TOKEN).asText()))
                .as("the other machine's provisioning account stays active")
                .isEqualTo(200);
        assertThat(status(setupTwo.path("secrets").path(Config.ACCESS_TOKEN).asText()))
                .as("the other machine's relay token still answers 200")
                .isEqualTo(200);
        assertThat(status(bobToken))
                .as("the other machine's task token still answers 200")
                .isEqualTo(200);
        assertThat(status(person.accessToken()))
                .as("the person's token still answers 200")
                .isEqualTo(200);
        assertThat(tuwunel.membership(person, room, person.userId()))
                .as("the room is still there with the person in it")
                .isEqualTo("join");
        assertThat(tuwunel.membership(person, room, bobId))
                .as("the other machine's task is still in the room")
                .isEqualTo("join");

        final JsonNode again =
                run(lifecycleOne, "clear", "--project", PROJECT, "--machine", MACHINE_ONE);
        assertThat(again.path("removed")).as("second clear removes nothing").isEmpty();
        assertThat(again.path("kept")).as("second clear keeps nothing").isEmpty();

        final String oldRelayToken = setupOne.path("secrets").path(Config.ACCESS_TOKEN).asText();
        final JsonNode setupAgain =
                run(
                        with(baseEnv(), setupOne.path("account")),
                        "setup",
                        "--project",
                        PROJECT,
                        "--machine",
                        MACHINE_ONE);
        final String newRelayToken = setupAgain.path("secrets").path(Config.ACCESS_TOKEN).asText();
        assertThat(setupAgain.path("admitted").asBoolean())
                .as("setup again on the same machine after clear is admitted")
                .isTrue();
        assertThat(newRelayToken)
                .as("new relay token issued after clear")
                .isNotEqualTo(oldRelayToken);
        assertThat(status(newRelayToken))
                .as("new relay token accepted by homeserver")
                .isEqualTo(200);
        assertThat(status(oldRelayToken))
                .as("earlier relay token still answers 401")
                .isEqualTo(401);
    }

    /**
     * Harm guarded: a person on the central homeserver whose username happens to end in .M (such as
     *
     * @carol.alpha) is not a transport or task account of machine M and must neither be touched nor
     *     reported in kept when machine M clears the project.
     */
    @Test
    @DisplayName(
            "clear --project ignores a person @carol.alpha in the room: neither removed nor in kept")
    void clearIgnoresPersonWhoseLocalpartEndsInMachine() throws Exception {
        final Tuwunel.Account dottedPerson = tuwunel.register("carol." + MACHINE_ONE);
        invite(person, room, dottedPerson.userId());
        final HttpResponse<String> joined =
                http.send(
                        HttpRequest.newBuilder(
                                        URI.create(
                                                tuwunel.url()
                                                        + "/_matrix/client/v3/join/"
                                                        + MatrixClient.segment(room)))
                                .header("Authorization", "Bearer " + dottedPerson.accessToken())
                                .header("Content-Type", "application/json")
                                .POST(HttpRequest.BodyPublishers.ofString("{}"))
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
        assertThat(joined.statusCode()).as("carol.alpha joins room").isEqualTo(200);

        final JsonNode pre =
                run(baseEnv(), "setup", "--project", PROJECT, "--machine", MACHINE_ONE);
        invite(person, room, "@porelo." + MACHINE_ONE + ":" + Tuwunel.SERVER_NAME);
        final JsonNode setup =
                run(
                        with(baseEnv(), pre.path("account")),
                        "setup",
                        "--project",
                        PROJECT,
                        "--machine",
                        MACHINE_ONE);
        final Map<String, String> lifecycle =
                with(baseEnv(), setup.path("account"), setup.path("secrets"));

        final JsonNode cleared =
                run(lifecycle, "clear", "--project", PROJECT, "--machine", MACHINE_ONE);

        assertThat(cleared.path("removed"))
                .as("removed does not contain @carol.alpha")
                .extracting(JsonNode::asText)
                .doesNotContain("the account " + dottedPerson.userId());
        assertThat(cleared.path("kept")).as("kept is empty: @carol.alpha was not tried").isEmpty();
        assertThat(status(dottedPerson.accessToken()))
                .as("@carol.alpha's token stays valid")
                .isEqualTo(200);
        assertThat(tuwunel.membership(person, room, dottedPerson.userId()))
                .as("@carol.alpha stays joined in the room")
                .isEqualTo("join");
    }

    /**
     * Harm guarded: an account of this machine that is also in another room this machine's account
     * is in must be kept by clear rather than logged out from under the other project.
     */
    @Test
    @DisplayName(
            "clear --project keeps an account of this machine that is in another room of this machine, naming that room")
    void clearKeepsAnAccountInAnotherRoomOfThisMachine() throws Exception {
        final String roomA = room;
        final String roomOther = createCentralRoom(person, true);
        final JsonNode pre = run(baseEnv(), "setup", "--project", "a", "--machine", MACHINE_ONE);
        final String machineUserId = "@porelo." + MACHINE_ONE + ":" + Tuwunel.SERVER_NAME;
        invite(person, roomA, machineUserId);
        invite(person, roomOther, machineUserId);

        final Map<String, String> accountEnv = with(baseEnv(), pre.path("account"));
        settings = settingsJson(roomA);
        final JsonNode setupA =
                run(accountEnv, "setup", "--project", "a", "--machine", MACHINE_ONE);
        settings = settingsJson(roomOther);
        run(accountEnv, "setup", "--project", "other", "--machine", MACHINE_ONE);

        settings = settingsJson(roomA);
        final Map<String, String> lifecycleA = with(accountEnv, setupA.path("secrets"));
        final JsonNode sharedTask =
                run(
                        lifecycleA,
                        "enroll",
                        "--project",
                        "a",
                        "--task",
                        "porelo-a-shared",
                        "--machine",
                        MACHINE_ONE);
        final String sharedUserId = sharedTask.path("address").asText();
        final String sharedToken = sharedTask.path("secrets").path(Config.ACCESS_TOKEN).asText();
        invite(person, roomOther, sharedUserId);

        final JsonNode cleared =
                run(lifecycleA, "clear", "--project", "a", "--machine", MACHINE_ONE);

        assertThat(cleared.path("removed"))
                .as("only the relay not in the other room is removed")
                .extracting(JsonNode::asText)
                .contains("the account @porelo-relay-a." + MACHINE_ONE + ":" + Tuwunel.SERVER_NAME)
                .doesNotContain("the account " + sharedUserId);
        assertThat(cleared.path("kept"))
                .as("shared task is kept with the other room")
                .anySatisfy(
                        entry -> {
                            assertThat(entry.path("what").asText())
                                    .as("kept account id")
                                    .isEqualTo("the account " + sharedUserId);
                            assertThat(entry.path("why").asText())
                                    .as("kept reason names the other room")
                                    .contains(roomOther);
                        });
        assertThat(status(sharedToken)).as("kept task's token still answers 200").isEqualTo(200);
    }
}
