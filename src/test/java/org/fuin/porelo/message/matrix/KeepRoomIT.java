package org.fuin.porelo.message.matrix;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/** A project's room kept by the host's helper, against the real homeserver behind its gate. */
class KeepRoomIT {

    private static final String SERVER = "localhost";

    @TempDir private Path dir;

    private final Map<String, String> env = new HashMap<>();

    private String login;

    private ProcessUnit homeserver;

    private Process gate;

    private Path listener;

    private String adminToken;

    private MatrixClient nobody;

    @BeforeEach
    void startTheHostsHomeserver() throws Exception {
        login = System.getProperty("user.name");
        env.put("HOME", dir.resolve("home").toString());
        env.put("XDG_CONFIG_HOME", dir.resolve("config").toString());
        env.put("XDG_STATE_HOME", dir.resolve("state").toString());
        env.put("XDG_RUNTIME_DIR", dir.resolve("runtime").toString());
        env.put(
                "PORELO_MATRIX_GATE_GROUP_FILE",
                Files.writeString(dir.resolve("group"), "porelo-people:x:101:" + login + "\n")
                        .toString());
        final Path conf = LocalHomeserver.directory(env).resolve("homeserver.conf");
        Files.createDirectories(conf.getParent());
        try (var port =
                new java.net.ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())) {
            Files.writeString(conf, "PORELO_MATRIX_PORT=" + port.getLocalPort() + "\n");
        }
        assertThat(Transport.run(env, "gate", "--prepare").exit()).as("gate mode").isZero();
        homeserver =
                new ProcessUnit(
                        env,
                        ProcessUnit.binary(Files.createDirectories(dir.resolve("bin"))),
                        dir.resolve("unit"));
        homeserver.start();
        GateIT.awaitHomeserver(env, homeserver);
        assertThat(Transport.run(env, "gate", "--prepare").exit()).as("administrator").isZero();
        adminToken = GateFiles.readToken(GateFiles.directory(env).resolve("admin-token"));
        nobody = new GateHomeserver(env).client("");
        listener = dir.resolve("client.sock");
        gate = GateIT.startGate(env, listener);
        GateIT.awaitSocket(listener, gate);
    }

    @AfterEach
    void stop() throws Exception {
        if (gate != null) {
            gate.destroy();
            if (!gate.waitFor(5, TimeUnit.SECONDS)) {
                gate.destroyForcibly().waitFor();
            }
        }
        if (homeserver != null) {
            homeserver.close();
        }
    }

    private String secrets(final String token) {
        final ObjectNode input = MatrixClient.JSON.createObjectNode();
        input.putObject("secrets").put(Config.ADMIN_TOKEN, token);
        return input.toString();
    }

    private Transport.Result host(final String... args) {
        return Transport.runWithInput(env, secrets(adminToken), args);
    }

    private JsonNode answered(final String... args) throws Exception {
        final Transport.Result result = host(args);
        assertThat(result.exit()).as("%s: %s", String.join(" ", args), result.err()).isZero();
        assertThat(result.out() + result.err()).as("never the token").doesNotContain(adminToken);
        return MatrixClient.JSON.readTree(result.out());
    }

    private JsonNode keep(final String project, final String persons) throws Exception {
        return answered("keep-room", "--project", project, "--persons", persons);
    }

    /** Who the homeserver says is in the project's room, by membership; empty without a room. */
    private Map<String, String> room(final String project) throws Exception {
        final MatrixClient admin = nobody.as(adminToken);
        final JsonNode found =
                admin.find(
                        "/_matrix/client/v3/directory/room/"
                                + MatrixClient.segment(Lifecycle.alias(project, SERVER)));
        return found == null
                ? Map.of()
                : KeepRoom.members(
                        admin,
                        "/_matrix/client/v3/rooms/"
                                + MatrixClient.segment(found.path("room_id").asText()));
    }

    private static List<String> texts(final JsonNode array) {
        final List<String> texts = new java.util.ArrayList<>();
        array.forEach(node -> texts.add(node.asText()));
        return texts;
    }

    private String id(final String localpart) {
        return "@" + localpart + ":" + SERVER;
    }

    private String settings(final String project) {
        return "{\"homeserver\":\"unix:"
                + listener.toUri().getRawPath()
                + "\",\"room\":\"#porelo-"
                + project
                + ":"
                + SERVER
                + "\"}";
    }

    private Map<String, String> porelo() {
        return new HashMap<>(Map.of("XDG_STATE_HOME", dir.resolve("porelo").toString()));
    }

    /** The person's Porelo: first setup of the project with whatever account it holds. */
    private JsonNode setup(final Map<String, String> held, final String project) throws Exception {
        final Transport.Result setup =
                Transport.runWithInput(
                        held, settings(project), "setup", "--project", project, "--login", login);
        assertThat(setup.exit()).as("setup: %s", setup.err()).isZero();
        final JsonNode answer = MatrixClient.JSON.readTree(setup.out());
        answer.path("account")
                .properties()
                .forEach(e -> held.put(e.getKey(), e.getValue().asText()));
        return answer;
    }

    /**
     * Harm guarded: a person named is not let in without anybody's Porelo running, a person dropped
     * stays in with their tasks, the same person leaves another project's room with them, a kick
     * becomes a ban, a repeat changes the room, or a dropped room's conversation stays readable.
     */
    @Test
    @Timeout(300)
    void theHostKeepsARoomToThePersonsItNamesAndDropsIt() throws Exception {
        final JsonNode made = keep("demo", login + ",bob");
        assertThat(made.path("created_room").asBoolean()).as("made on the first run").isTrue();
        assertThat(made.path("room").asText()).as("the alias").isEqualTo("#porelo-demo:" + SERVER);
        final String conversation = made.path("conversation").asText();
        assertThat(texts(made.path("invited")))
                .as("neither has ever signed in")
                .containsExactly(id("bob"), id("porelo.bob"), id(login), id("porelo." + login));
        assertThat(texts(made.path("removed"))).as("nobody out").isEmpty();

        final JsonNode again = keep("demo", login + ",bob");
        assertThat(again.path("created_room").asBoolean()).as("taken on a repeat").isFalse();
        assertThat(again.path("conversation").asText()).as("same room").isEqualTo(conversation);
        assertThat(texts(again.path("invited"))).as("a repeat invites nobody").isEmpty();
        assertThat(texts(again.path("removed"))).as("a repeat removes nobody").isEmpty();

        final Map<String, String> invited = room("demo");
        assertThat(invited)
                .as("bob's accounts wait")
                .containsEntry(id("bob"), "invite")
                .containsEntry(id("porelo.bob"), "invite")
                .containsEntry(id("porelo"), "join");

        final Map<String, String> held = porelo();
        final JsonNode admitted = setup(held, "demo");
        assertThat(admitted.path("admitted").asBoolean())
                .as("a login invited before it existed joins with no further step")
                .isTrue();
        assertThat(admitted.path("conversation").asText())
                .as("the host's room")
                .isEqualTo(conversation);
        keep("other", login);
        assertThat(setup(held, "other").path("admitted").asBoolean()).as("second room").isTrue();
        final Map<String, String> lifecycle = new HashMap<>(held);
        admitted.path("secrets")
                .properties()
                .forEach(e -> lifecycle.put(e.getKey(), e.getValue().asText()));
        final Transport.Result enroll =
                Transport.runWithInput(
                        lifecycle,
                        settings("demo"),
                        "enroll",
                        "--project",
                        "demo",
                        "--task",
                        "worker",
                        "--login",
                        login);
        assertThat(enroll.exit()).as("enroll: %s", enroll.err()).isZero();
        assertThat(room("demo"))
                .as("the login's accounts by name")
                .containsEntry(id("porelo." + login), "join")
                .containsEntry(id("worker." + login), "join");

        final MatrixClient asPorelo = nobody.as(held.get(Config.ADMIN_TOKEN));
        assertThat(
                        asPorelo.exchange(
                                        "POST",
                                        "/_matrix/client/v3/rooms/"
                                                + MatrixClient.segment(conversation)
                                                + "/kick",
                                        MatrixClient.JSON
                                                .createObjectNode()
                                                .put("user_id", id("porelo")))
                                .status())
                .as("no member can kick the administrator")
                .isEqualTo(403);

        final JsonNode dropped = keep("demo", "bob");
        assertThat(texts(dropped.path("removed")))
                .as("the dropped login's every account, its provisioning one first")
                .startsWith(id("porelo." + login))
                .contains(id("worker." + login), id(login));
        assertThat(room("demo").entrySet())
                .as("nothing of the login is left in the room")
                .noneMatch(
                        member ->
                                login.equals(Login.owner(member.getKey(), SERVER))
                                        && KeepRoom.in(member.getValue()));
        assertThat(room("other"))
                .as("still in the other project's room")
                .containsEntry(id("porelo." + login), "join");
        assertThat(texts(keep("demo", login + ",bob").path("invited")))
                .as("a kick is no ban: named again, invited again")
                .contains(id("porelo." + login));

        final JsonNode gone = answered("drop-room", "--project", "demo");
        assertThat(gone.path("deleted").asBoolean()).as("the room is deleted").isTrue();
        assertThat(texts(gone.path("removed")))
                .as("every member out, the administrator not named")
                .contains(id("porelo.bob"))
                .doesNotContain(id("porelo"));
        assertThat(room("demo")).as("no room, no alias").isEmpty();
        assertThat(
                        nobody.as(adminToken)
                                .exchange(
                                        "GET",
                                        "/_matrix/client/v3/rooms/"
                                                + MatrixClient.segment(conversation)
                                                + "/messages?dir=b&limit=5",
                                        null)
                                .status())
                .as("the conversation cannot be read, even by the administrator")
                .isIn(403, 404);
        assertThat(asPorelo.exchange("GET", "/_matrix/client/v3/account/whoami", null).status())
                .as("the person's accounts still sign in")
                .isEqualTo(200);
        assertThat(room("other"))
                .as("another project's room is untouched")
                .containsEntry(id("porelo." + login), "join");
        final JsonNode second = answered("drop-room", "--project", "demo");
        assertThat(second.path("deleted").asBoolean()).as("nothing to do").isFalse();

        final JsonNode anew = keep("demo", login);
        assertThat(anew.path("created_room").asBoolean()).as("a new room").isTrue();
        assertThat(anew.path("conversation").asText())
                .as("not the old one")
                .isNotEqualTo(conversation);
        assertThat(setup(held, "demo").path("conversation").asText())
                .as("the same person joins it")
                .isEqualTo(anew.path("conversation").asText());
    }

    /**
     * Harm guarded: a room somebody else made under the alias is taken and its members changed, a
     * client makes or takes the alias, or a person's token acts as the administrator.
     */
    @Test
    @Timeout(180)
    void aRoomTheAdministratorDidNotMakeIsLeftAlone() throws Exception {
        final Accounts.Session bob =
                Accounts.register(
                        nobody, "bob", Accounts.secret(), LocalHomeserver.registrationToken(env));
        final MatrixClient asBob = nobody.as(bob.accessToken());
        final ObjectNode create =
                MatrixClient.JSON
                        .createObjectNode()
                        .put("preset", "private_chat")
                        .put("room_alias_name", "porelo-foreign");
        final String foreign =
                asBob.post("/_matrix/client/v3/createRoom", create).path("room_id").asText();

        for (final String[] verb :
                List.of(
                        new String[] {"keep-room", "--project", "foreign", "--persons", login},
                        new String[] {"drop-room", "--project", "foreign"})) {
            final Transport.Result refused = host(verb);
            assertThat(refused.exit()).as("%s: %s", verb[0], refused.err()).isEqualTo(Exit.CONFIG);
            assertThat(refused.err()).as("never the token").doesNotContain(adminToken);
        }
        final JsonNode joined =
                asBob.exchange(
                                "GET",
                                "/_matrix/client/v3/rooms/"
                                        + MatrixClient.segment(foreign)
                                        + "/joined_members",
                                null)
                        .body()
                        .path("joined");
        assertThat(joined.size()).as("the foreign room is as it was").isEqualTo(1);
        assertThat(joined.has(bob.userId())).as("with its maker alone").isTrue();

        final Transport.Result asAPerson =
                Transport.runWithInput(
                        env,
                        secrets(bob.accessToken()),
                        "keep-room",
                        "--project",
                        "mine",
                        "--persons",
                        login);
        assertThat(asAPerson.exit()).as("a person's token: %s", asAPerson.err()).isEqualTo(78);
        assertThat(room("mine")).as("no room was made").isEmpty();

        final Map<String, String> held = porelo();
        final JsonNode admin = setup(held, "client");
        assertThat(admin.path("admitted").asBoolean()).as("no room yet").isFalse();
        final MatrixClient throughGate =
                new MatrixClient(
                        new Config(
                                URI.create("unix:" + listener.toUri().getRawPath()),
                                held.get(Config.ADMIN_TOKEN),
                                null,
                                true));
        assertThat(
                        throughGate
                                .exchange(
                                        "POST",
                                        "/_matrix/client/v3/createRoom",
                                        MatrixClient.JSON
                                                .createObjectNode()
                                                .put("room_alias_name", "porelo-client"))
                                .status())
                .as("a person's provisioning account makes no project's room through the gate")
                .isEqualTo(403);
    }

    /**
     * Harm guarded: a person shows in the room as their whole Matrix id though the host passed a
     * name, an account the host names before its first sign-in exists unnamed, or a name the person
     * then chose is overwritten on the next run.
     */
    @Test
    @Timeout(180)
    void aPersonShowsUnderTheNameTheHostPassesUntilTheyChooseTheirOwn() throws Exception {
        final String input =
                "{\"settings\":{\"names\":{\""
                        + login
                        + "\":\"Ada\",\"bob\":\"Bob\"}},\"secrets\":{\""
                        + Config.ADMIN_TOKEN
                        + "\":\""
                        + adminToken
                        + "\"}}";
        final String persons = login + ",bob";
        final JsonNode first = named(input, persons);
        final List<String> fields = new java.util.ArrayList<>();
        first.fieldNames().forEachRemaining(fields::add);
        assertThat(fields)
                .as("the names change nothing in the answer")
                .containsExactly("room", "conversation", "created_room", "invited", "removed");
        final String conversation = first.path("conversation").asText();
        final MatrixClient admin = nobody.as(adminToken);
        assertThat(
                        admin.get(
                                        "/_matrix/client/v3/profile/"
                                                + MatrixClient.segment(id(login))
                                                + "/displayname")
                                .path("displayname")
                                .asText())
                .as("an account made before its first sign-in is made named")
                .isEqualTo("Ada");
        assertThat(shown(admin, conversation, id("bob")))
                .as("the invitation shows the name")
                .isEqualTo("Bob");

        final MatrixClient person = signedIn();
        person.post(
                "/_matrix/client/v3/join/" + MatrixClient.segment(conversation),
                MatrixClient.JSON.createObjectNode());
        assertThat(shown(admin, conversation, id(login)))
                .as("the person, signing in for the first time, shows the name passed")
                .isEqualTo("Ada");

        final String profile =
                "/_matrix/client/v3/profile/" + MatrixClient.segment(id(login)) + "/displayname";
        person.put(profile, MatrixClient.JSON.createObjectNode().put("displayname", ""));
        final com.fasterxml.jackson.databind.node.ObjectNode same = first.deepCopy();
        same.put("created_room", false).putArray("invited");
        assertThat(named(input, persons)).as("the answer of a run without names").isEqualTo(same);
        assertThat(shown(admin, conversation, id(login)))
                .as("a name emptied is given again")
                .isEqualTo("Ada");

        person.put(profile, MatrixClient.JSON.createObjectNode().put("displayname", "Ada L."));
        named(input, persons);
        assertThat(shown(admin, conversation, id(login)))
                .as("the name the person chose stays")
                .isEqualTo("Ada L.");
    }

    /** keep-room's answer for the project "names", the names passed on {@code input}. */
    private JsonNode named(final String input, final String persons) throws Exception {
        final Transport.Result result =
                Transport.runWithInput(
                        env, input, "keep-room", "--project", "names", "--persons", persons);
        assertThat(result.exit()).as("named: %s", result.err()).isZero();
        assertThat(result.out() + result.err()).as("never the token").doesNotContain(adminToken);
        return MatrixClient.JSON.readTree(result.out());
    }

    /** The person signed in through the gate's single sign-on, as a person's client does. */
    private MatrixClient signedIn() throws Exception {
        final MatrixClient client =
                new MatrixClient(
                        new Config(
                                URI.create("unix:" + listener.toUri().getRawPath()),
                                "",
                                null,
                                true));
        final Wire.Reply redirect =
                client.rawExchange(
                        "GET",
                        "/_matrix/client/v3/login/sso/redirect/porelo?redirectUrl="
                                + MatrixClient.segment("element://vector/webapp/"),
                        null);
        assertThat(redirect.statusCode()).as("SSO redirect").isEqualTo(302);
        final String raw = new String(redirect.raw(), java.nio.charset.StandardCharsets.ISO_8859_1);
        final String location =
                raw.lines()
                        .filter(l -> l.regionMatches(true, 0, "Location:", 0, 9))
                        .findFirst()
                        .orElseThrow()
                        .substring(9)
                        .strip();
        final MatrixClient.Answer signedIn =
                client.exchange(
                        "POST",
                        "/_matrix/client/v3/login",
                        MatrixClient.JSON
                                .createObjectNode()
                                .put("type", "m.login.token")
                                .put(
                                        "token",
                                        GateRequest.query(URI.create(location).getRawQuery())
                                                .get("loginToken")));
        assertThat(signedIn.status()).as("signed in").isEqualTo(200);
        return client.as(signedIn.body().path("access_token").asText());
    }

    /** The name the room shows for a member, from its member event. */
    private static String shown(final MatrixClient admin, final String room, final String id)
            throws Failure {
        return admin.get(
                        "/_matrix/client/v3/rooms/"
                                + MatrixClient.segment(room)
                                + "/state/m.room.member/"
                                + MatrixClient.segment(id))
                .path("displayname")
                .asText();
    }

    private Map<String, String> relayOf(final Map<String, String> held, final JsonNode setup) {
        final Map<String, String> relay = new HashMap<>(held);
        setup.path("secrets")
                .properties()
                .forEach(e -> relay.put(e.getKey(), e.getValue().asText()));
        return relay;
    }

    /** The task's message sent, then the relay's poll read: what it handed over this time. */
    private List<String> sentThenPolled(
            final Map<String, String> relay, final String conversation, final String text)
            throws Exception {
        final Transport.Result enroll =
                Transport.runWithInput(
                        relay,
                        settings("demo"),
                        "enroll",
                        "--project",
                        "demo",
                        "--task",
                        "worker",
                        "--login",
                        login);
        assertThat(enroll.exit()).as("enroll: %s", enroll.err()).isZero();
        final Map<String, String> task = new HashMap<>();
        MatrixClient.JSON
                .readTree(enroll.out())
                .path("secrets")
                .properties()
                .forEach(e -> task.put(e.getKey(), e.getValue().asText()));
        final Path message = Files.writeString(dir.resolve("m.json"), text);
        final Path sig = Files.write(dir.resolve("m.json.sig"), new byte[] {1, 2, 3});
        final Transport.Result sent =
                Transport.run(
                        task, "send", message.toString(), sig.toString(), "--to", conversation);
        assertThat(sent.exit()).as("send: %s", sent.err()).isZero();
        return polled(relay);
    }

    private List<String> polled(final Map<String, String> relay) throws Exception {
        final Path inbound = Files.createTempDirectory(dir, "in");
        final Transport.Result poll = Transport.run(relay, "poll", "--into", inbound.toString());
        assertThat(poll.exit()).as("poll: %s", poll.err()).isZero();
        final List<String> handed = new java.util.ArrayList<>();
        try (var files = Files.list(inbound)) {
            for (final Path file : files.filter(f -> f.toString().endsWith(".json")).toList()) {
                handed.add(Files.readString(file));
            }
        }
        return handed;
    }

    private void sayAsTheAdministrator(final String room, final String text) throws Exception {
        nobody.as(adminToken)
                .put(
                        "/_matrix/client/v3/rooms/"
                                + MatrixClient.segment(room)
                                + "/send/m.room.message/"
                                + text,
                        MatrixClient.JSON
                                .createObjectNode()
                                .put("msgtype", "m.text")
                                .put("body", text));
    }

    /** What the account of this token reads of the room, or what stopped it. */
    private String readBy(final String token, final String room) {
        try {
            return nobody.as(token)
                    .get(
                            "/_matrix/client/v3/rooms/"
                                    + MatrixClient.segment(room)
                                    + "/messages?dir=b&limit=50")
                    .toString();
        } catch (final Failure refused) {
            return "refused: " + refused.getMessage();
        }
    }

    /** Harm guarded: a person taken out of a project goes on reading what is said in its room. */
    @Test
    @Timeout(240)
    void aMemberTakenOutReadsNothingSaidAfter() throws Exception {
        final Map<String, String> held = porelo();
        final String room = keep("demo", login).path("conversation").asText();
        final String token = relayOf(held, setup(held, "demo")).get(Config.ACCESS_TOKEN);
        sayAsTheAdministrator(room, "said-while-in");
        assertThat(readBy(token, room)).as("a member reads").contains("said-while-in");

        final JsonNode out = keep("demo", "");
        assertThat(texts(out.path("removed")))
                .as("the answer names who went")
                .contains(id("porelo." + login));
        sayAsTheAdministrator(room, "said-after-out");
        final String after = readBy(token, room);
        assertThat(after).as("what it reads now").doesNotContain("said-after-out");
    }

    /**
     * Harm guarded: a relay's position taken in a room since deleted stops its poll for good, so a
     * project put back on the host hears nothing until a person removes a file; or a setup in the
     * same room forgets the position and hands the conversation over twice.
     */
    @Test
    @Timeout(240)
    void aRelayGoesOnInTheRoomItIsAdmittedToWithNoHand() throws Exception {
        final Map<String, String> held = porelo();
        final String first = keep("demo", login).path("conversation").asText();
        final Map<String, String> relay = relayOf(held, setup(held, "demo"));
        assertThat(sentThenPolled(relay, first, "{\"to\":\"one\"}"))
                .as("the first room's message")
                .containsExactly("{\"to\":\"one\"}");

        keep("demo", "");
        keep("demo", login);
        final Map<String, String> back = relayOf(held, setup(held, "demo"));
        assertThat(polled(back)).as("taken out and named again: nothing twice").isEmpty();

        answered("drop-room", "--project", "demo");
        final String second = keep("demo", login).path("conversation").asText();
        final Map<String, String> anew = relayOf(held, setup(held, "demo"));
        assertThat(sentThenPolled(anew, second, "{\"to\":\"two\"}"))
                .as("the new room's message, with no file removed by hand")
                .containsExactly("{\"to\":\"two\"}");
    }
}
