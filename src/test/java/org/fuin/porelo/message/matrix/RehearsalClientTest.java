package org.fuin.porelo.message.matrix;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The rehearsal's person signs in only by the gate's SSO and lets no session token out. */
class RehearsalClientTest {

    private static final String SESSION = "syt_rehearsal_session_secret";
    private static final String LOGIN_TOKEN = "gate-login-token-secret";

    @TempDir private Path dir;

    private record Run(int exit, String out, String err) {
        String all() {
            return out + err;
        }
    }

    /** Answers each request on a Unix socket by its first line and records what came. */
    private static final class ScriptedGate implements AutoCloseable {

        final List<String> requests = new CopyOnWriteArrayList<>();
        private final ServerSocketChannel server;

        ScriptedGate(final Path path, final Function<String, String> answer) throws IOException {
            server = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
            server.bind(UnixDomainSocketAddress.of(path));
            Thread.ofVirtual()
                    .start(
                            () -> {
                                while (server.isOpen()) {
                                    try (SocketChannel socket = server.accept()) {
                                        final String request =
                                                read(
                                                        new BufferedInputStream(
                                                                Channels.newInputStream(socket)));
                                        requests.add(request);
                                        Channels.newOutputStream(socket)
                                                .write(
                                                        answer.apply(request)
                                                                .getBytes(StandardCharsets.UTF_8));
                                    } catch (final IOException ex) {
                                        // A client that closed its end, as an interrupted one
                                        // does, ends only its own request, not the gate.
                                        if (!server.isOpen()) {
                                            return;
                                        }
                                    }
                                }
                            });
        }

        private static String read(final InputStream in) throws IOException {
            final ByteArrayOutputStream head = new ByteArrayOutputStream();
            while (!head.toString(StandardCharsets.US_ASCII).endsWith("\r\n\r\n")) {
                final int b = in.read();
                if (b < 0) {
                    throw new IOException("request ended before its headers");
                }
                head.write(b);
            }
            int length = 0;
            for (final String line : head.toString(StandardCharsets.US_ASCII).split("\r\n")) {
                if (line.toLowerCase(java.util.Locale.ROOT).startsWith("content-length: ")) {
                    length = Integer.parseInt(line.substring("content-length: ".length()));
                }
            }
            return head.toString(StandardCharsets.US_ASCII)
                    + new String(in.readNBytes(length), StandardCharsets.UTF_8);
        }

        @Override
        public void close() throws IOException {
            server.close();
        }
    }

    private static String json(final int status, final String body) {
        return "HTTP/1.1 "
                + status
                + " X\r\nContent-Type: application/json\r\nContent-Length: "
                + body.getBytes(StandardCharsets.UTF_8).length
                + "\r\nConnection: close\r\n\r\n"
                + body;
    }

    private static String redirect(final String location) {
        return "HTTP/1.1 302 Found\r\nLocation: "
                + location
                + "\r\nContent-Length: 0\r\nConnection: close\r\n\r\n";
    }

    /** A gate that signs the person in, holds the session until it is logged out. */
    private static Function<String, String> signingGate(final String loginAnswer) {
        final boolean[] loggedOut = {false};
        return request -> {
            if (request.startsWith("GET /_matrix/client/v3/login/sso/redirect/porelo")) {
                return redirect("http://localhost/?loginToken=" + LOGIN_TOKEN);
            }
            if (request.startsWith("POST /_matrix/client/v3/login ")) {
                return request.contains(LOGIN_TOKEN)
                        ? json(200, loginAnswer)
                        : json(403, "{\"errcode\":\"M_FORBIDDEN\"}");
            }
            if (request.startsWith("POST /_matrix/client/v3/logout")) {
                loggedOut[0] = true;
                return json(200, "{}");
            }
            if (request.startsWith("GET /_matrix/client/v3/account/whoami")) {
                return loggedOut[0]
                        ? json(401, "{\"errcode\":\"M_UNKNOWN_TOKEN\"}")
                        : json(200, "{\"user_id\":\"@bob:x\"}");
            }
            return json(404, "{\"errcode\":\"M_UNRECOGNIZED\"}");
        };
    }

    private static final String SIGNED_IN =
            "{\"access_token\":\"" + SESSION + "\",\"user_id\":\"@bob:x\",\"device_id\":\"D\"}";

    private Run run(final Path socket, final String steps) {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        final ByteArrayOutputStream err = new ByteArrayOutputStream();
        final int exit =
                RehearsalClient.run(
                        new String[] {"--socket", socket.toString()},
                        new ByteArrayInputStream(steps.getBytes(StandardCharsets.UTF_8)),
                        new PrintStream(out, true, StandardCharsets.UTF_8),
                        new PrintStream(err, true, StandardCharsets.UTF_8));
        return new Run(
                exit, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    /**
     * Harm guarded: the rehearsal signing in by a flow a real host does not offer, or its session
     * token reaching output.
     */
    @Test
    void aSignInGoesOnlyThroughTheGatesSsoAndLeavesNoTokenInItsOutput() throws Exception {
        final Path socket = dir.resolve("gate.sock");
        try (ScriptedGate gate = new ScriptedGate(socket, signingGate(SIGNED_IN))) {
            final Run run = run(socket, "sign-in\nlog-out\n");

            assertThat(run.exit()).as("the run's exit, stderr: " + run.err()).isZero();
            assertThat(run.out()).as("the signed-in person").contains("\"user\":\"@bob:x\"");
            assertThat(run.all())
                    .as("no token in what the run says")
                    .doesNotContain(SESSION)
                    .doesNotContain(LOGIN_TOKEN);
            assertThat(gate.requests)
                    .as("what reached the gate")
                    .noneMatch(r -> r.contains("m.login.password"))
                    .noneMatch(r -> r.contains("/register"));
            final String sso = gate.requests.getFirst();
            assertThat(sso)
                    .as("the SSO request, sent as a navigation back to the forward's own page")
                    .startsWith(
                            "GET /_matrix/client/v3/login/sso/redirect/porelo?redirectUrl="
                                    + MatrixClient.segment("http://localhost/")
                                    + " ")
                    .doesNotContainIgnoringCase("Origin:")
                    .doesNotContainIgnoringCase("Sec-Fetch-Site:")
                    .doesNotContainIgnoringCase("Authorization:");
            assertThat(gate.requests.get(1))
                    .as("the token login")
                    .contains("\"type\":\"m.login.token\"");
            assertThat(gate.requests.getLast())
                    .as("whoami after logout, as the session")
                    .startsWith("GET /_matrix/client/v3/account/whoami")
                    .contains("Bearer " + SESSION);
        }
    }

    /** Harm guarded: a sign-in the gate did not grant taken for one, and the run going on. */
    @Test
    void aRedirectWithoutALoginTokenStopsTheRunBeforeAnyLogin() throws Exception {
        final Path socket = dir.resolve("gate.sock");
        try (ScriptedGate gate =
                new ScriptedGate(
                        socket,
                        request ->
                                request.startsWith("GET /_matrix/client/v3/login/sso")
                                        ? redirect("http://localhost/")
                                        : json(200, SIGNED_IN))) {
            final Run run = run(socket, "sign-in\npost !r:x " + dir.resolve("none") + "\n");

            assertThat(run.exit()).as("the run's exit").isEqualTo(Exit.PROTOCOL);
            assertThat(run.err())
                    .as("what was asked and what came")
                    .contains("FAILED: sign-in")
                    .contains("302 with no loginToken");
            assertThat(gate.requests).as("only the SSO request reached the gate").hasSize(1);
        }
    }

    /** Harm guarded: a login answered without a session taken for a signed-in person. */
    @Test
    void aLoginAnsweredWithoutASessionStopsTheRun() throws Exception {
        final Path socket = dir.resolve("gate.sock");
        try (ScriptedGate gate =
                new ScriptedGate(socket, signingGate("{\"user_id\":\"@bob:x\"}"))) {
            final Run run = run(socket, "sign-in\n");

            assertThat(run.exit()).as("the run's exit").isEqualTo(Exit.PROTOCOL);
            assertThat(run.err()).as("the refusal").contains("200 and no session");
            assertThat(run.out()).as("no sign-in reported").doesNotContain("\"step\"");
        }
    }

    /** Harm guarded: a failed run leaving the person's session open on the host. */
    @Test
    void aFailedStepLogsTheSessionOutAndNamesNoToken() throws Exception {
        final Path socket = dir.resolve("gate.sock");
        try (ScriptedGate gate = new ScriptedGate(socket, signingGate(SIGNED_IN))) {
            final Run run = run(socket, "sign-in\npost !r:x " + dir.resolve("missing") + "\n");

            assertThat(run.exit()).as("the run's exit").isEqualTo(Exit.DATA);
            assertThat(run.err())
                    .as("the failed step, by its keyword")
                    .startsWith("FAILED: post: cannot read ");
            assertThat(gate.requests)
                    .as("the session logged out after the failure")
                    .anyMatch(
                            r ->
                                    r.startsWith("POST /_matrix/client/v3/logout")
                                            && r.contains("Bearer " + SESSION));
            assertThat(run.all()).as("no token in what the run says").doesNotContain(SESSION);
        }
    }

    /** Harm guarded: the run's text going out other than as the file holds it. */
    @Test
    void aPostSendsTheFilesTextAndMarksOnlyTheNamedMentions() throws Exception {
        final Path socket = dir.resolve("gate.sock");
        final Path line = Files.writeString(dir.resolve("line"), "Agent Pi: the Build is green");
        final Function<String, String> signing = signingGate(SIGNED_IN);
        try (ScriptedGate gate =
                new ScriptedGate(
                        socket,
                        request ->
                                request.startsWith("PUT /_matrix/client/v3/rooms/")
                                        ? json(200, "{\"event_id\":\"$e1\"}")
                                        : signing.apply(request))) {
            final Run run = run(socket, "sign-in\npost !r:x " + line + " @pi:x @room\nlog-out\n");

            assertThat(run.exit()).as("the run's exit, stderr: " + run.err()).isZero();
            final String put =
                    gate.requests.stream()
                            .filter(r -> r.startsWith("PUT "))
                            .findFirst()
                            .orElseThrow();
            final var content =
                    MatrixClient.JSON.readTree(put.substring(put.indexOf("\r\n\r\n") + 4));
            assertThat(content.path("body").asText())
                    .as("the line as the file holds it")
                    .isEqualTo("Agent Pi: the Build is green");
            assertThat(content.path("m.mentions").path("user_ids"))
                    .as("the one member named")
                    .extracting(com.fasterxml.jackson.databind.JsonNode::asText)
                    .containsExactly("@pi:x");
            assertThat(content.path("m.mentions").path("room").asBoolean())
                    .as("the whole room named")
                    .isTrue();
            assertThat(run.out()).as("the event posted").contains("\"event\":\"$e1\"");
        }
    }

    /**
     * Harm guarded: chats an earlier run left open on a host stay, and a task's answer goes into
     * one of them instead of the chat the rehearsal waits in.
     */
    @Test
    void theRoomsThePersonIsInAreNamedAndOneIsLeft() throws Exception {
        final Path socket = dir.resolve("gate.sock");
        final Function<String, String> signing = signingGate(SIGNED_IN);
        try (ScriptedGate gate =
                new ScriptedGate(
                        socket,
                        request -> {
                            if (request.startsWith("GET /_matrix/client/v3/joined_rooms")) {
                                return json(200, "{\"joined_rooms\":[\"!r:x\",\"!d:x\"]}");
                            }
                            if (request.startsWith(
                                    "POST /_matrix/client/v3/rooms/"
                                            + MatrixClient.segment("!d:x")
                                            + "/leave")) {
                                return json(200, "{}");
                            }
                            return signing.apply(request);
                        })) {
            final Run run = run(socket, "sign-in\njoined\nleave !d:x\nlog-out\n");

            assertThat(run.exit()).as("the run's exit, stderr: " + run.err()).isZero();
            assertThat(run.out())
                    .as("the rooms, then the one left")
                    .contains("{\"step\":\"joined\",\"rooms\":[\"!r:x\",\"!d:x\"]}")
                    .contains("{\"step\":\"leave\",\"room\":\"!d:x\"}");
            assertThat(gate.requests)
                    .as("only that room left")
                    .filteredOn(r -> r.contains("/leave"))
                    .hasSize(1);
        }
    }

    /**
     * Harm guarded: a direct chat opened but not written in stays open, and a later run's answer
     * from the task goes into it instead of the chat the rehearsal waits in.
     */
    @Test
    void aDirectChatWhoseLineFailsIsLeft() throws Exception {
        final Path socket = dir.resolve("gate.sock");
        final Path line = Files.writeString(dir.resolve("line"), "answer with x");
        final Function<String, String> signing = signingGate(SIGNED_IN);
        try (ScriptedGate gate =
                new ScriptedGate(
                        socket,
                        request -> {
                            if (request.startsWith("POST /_matrix/client/v3/createRoom")) {
                                return json(200, "{\"room_id\":\"!dm:x\"}");
                            }
                            if (request.startsWith("PUT /_matrix/client/v3/rooms/")) {
                                return json(502, "{\"errcode\":\"M_UNKNOWN\"}");
                            }
                            if (request.startsWith(
                                    "POST /_matrix/client/v3/rooms/"
                                            + MatrixClient.segment("!dm:x")
                                            + "/leave")) {
                                return json(200, "{}");
                            }
                            return signing.apply(request);
                        })) {
            final Run run = run(socket, "sign-in\ndirect @pi:x " + line + "\nlog-out\n");

            assertThat(run.exit()).as("the line failed").isNotZero();
            assertThat(gate.requests)
                    .as("the chat opened for it left")
                    .anyMatch(r -> r.contains("/leave"));
        }
    }

    /** Harm guarded: a session the gate issued with a wrong answer left open on the host. */
    @Test
    void aSessionIssuedWithoutItsUserIsStillLoggedOut() throws Exception {
        final Path socket = dir.resolve("gate.sock");
        try (ScriptedGate gate =
                new ScriptedGate(socket, signingGate("{\"access_token\":\"" + SESSION + "\"}"))) {
            final Run run = run(socket, "sign-in\n");

            assertThat(run.exit()).as("the run's exit").isEqualTo(Exit.PROTOCOL);
            assertThat(gate.requests)
                    .as("the issued session logged out")
                    .anyMatch(
                            r ->
                                    r.startsWith("POST /_matrix/client/v3/logout")
                                            && r.contains("Bearer " + SESSION));
            assertThat(run.all()).as("no token in what the run says").doesNotContain(SESSION);
        }
    }

    /** Harm guarded: a run saying it logged out while the session still works. */
    @Test
    void aLogoutTheSessionSurvivesFailsTheRun() throws Exception {
        final Path socket = dir.resolve("gate.sock");
        final Function<String, String> signing = signingGate(SIGNED_IN);
        try (ScriptedGate gate =
                new ScriptedGate(
                        socket,
                        request ->
                                request.startsWith("GET /_matrix/client/v3/account/whoami")
                                        ? json(200, "{\"user_id\":\"@bob:x\"}")
                                        : signing.apply(request))) {
            final Run run = run(socket, "sign-in\nlog-out\n");

            assertThat(run.exit()).as("the run's exit").isEqualTo(Exit.PROTOCOL);
            assertThat(run.err()).as("the refusal").contains("whoami answered 200");
            assertThat(run.out()).as("no log-out reported").doesNotContain("log-out");
        }
    }

    /** Harm guarded: a line the homeserver did not take reported as posted and waited on. */
    @Test
    void aPostAnsweredWithoutAnEventFailsTheRun() throws Exception {
        final Path socket = dir.resolve("gate.sock");
        final Path line = Files.writeString(dir.resolve("line"), "Agent Pi: hello");
        final Function<String, String> signing = signingGate(SIGNED_IN);
        try (ScriptedGate gate =
                new ScriptedGate(
                        socket,
                        request ->
                                request.startsWith("PUT /_matrix/client/v3/rooms/")
                                        ? json(200, "{}")
                                        : signing.apply(request))) {
            final Run run = run(socket, "sign-in\npost !r:x " + line + "\n");

            assertThat(run.exit()).as("the run's exit").isEqualTo(Exit.PROTOCOL);
            assertThat(run.err()).as("the refusal").contains("send answered with no event_id");
            assertThat(run.out()).as("no post reported").doesNotContain("\"step\":\"post\"");
        }
    }

    /** Harm guarded: the session token sent to a socket another account listens on. */
    @Test
    void aSocketAnotherAccountListensOnGetsNoRequest() throws Exception {
        final Path socket = dir.resolve("gate.sock");
        try (ScriptedGate gate = new ScriptedGate(socket, signingGate(SIGNED_IN));
                RehearsalClient client =
                        new RehearsalClient(
                                socket,
                                new PrintStream(
                                        new ByteArrayOutputStream(), true, StandardCharsets.UTF_8),
                                System::nanoTime,
                                RehearsalClientTest::root)) {
            final Failure refused =
                    org.assertj.core.api.Assertions.catchThrowableOfType(
                            Failure.class, () -> client.step("sign-in"));
            assertThat(refused).as("the sign-in refused").isNotNull();
            assertThat(refused.exitCode()).as("the refusal's exit").isEqualTo(Exit.CONFIG);
            assertThat(gate.requests).as("nothing reached the foreign listener").isEmpty();
        }
    }

    private static java.nio.file.attribute.UserPrincipal root() throws Failure {
        try {
            return java.nio.file.FileSystems.getDefault()
                    .getUserPrincipalLookupService()
                    .lookupPrincipalByName("root");
        } catch (final IOException ex) {
            throw new Failure(Exit.CONFIG, "no root", ex);
        }
    }

    /**
     * A gate whose room holds {@code chunk}, newest first, and takes the run's line as {@code
     * $line}.
     */
    private static Function<String, String> roomGate(final String chunk) {
        final Function<String, String> signing = signingGate(SIGNED_IN);
        return request -> {
            if (request.startsWith("PUT /_matrix/client/v3/rooms/")) {
                return json(200, "{\"event_id\":\"$line\"}");
            }
            if (request.startsWith("GET /_matrix/client/v3/rooms/")
                    && request.contains("/messages?")) {
                return json(200, "{\"chunk\":" + chunk + "}");
            }
            return signing.apply(request);
        };
    }

    private static String event(final String id, final String sender) {
        return "{\"type\":\"m.room.message\",\"event_id\":\""
                + id
                + "\",\"sender\":\""
                + sender
                + "\",\"content\":{\"msgtype\":\"m.text\",\"body\":\"from "
                + sender
                + "\"}}";
    }

    private Run waitFor(final String chunk) throws IOException {
        final Path socket = Files.createTempDirectory(dir, "gate").resolve("gate.sock");
        final Path line = Files.writeString(dir.resolve("wake"), "Agent Pi: are you there?");
        try (ScriptedGate gate = new ScriptedGate(socket, roomGate(chunk))) {
            return run(
                    socket,
                    "sign-in\npost !r:x " + line + " @pi:x\nwait-answer !r:x @pi:x 0\nlog-out\n");
        }
    }

    /**
     * Harm guarded: the move check told a wake was answered when the task said nothing after it.
     */
    @Test
    void aWakeCountsAsAnsweredOnlyByTheTasksLineAfterIt() throws Exception {
        final Run answered =
                waitFor(
                        "["
                                + event("$a2", "@pi:x")
                                + ","
                                + event("$line", "@bob:x")
                                + ","
                                + event("$a1", "@pi:x")
                                + "]");
        assertThat(answered.exit()).as("answered, stderr: " + answered.err()).isZero();
        assertThat(answered.out())
                .as("the answer named")
                .contains("\"step\":\"wait-answer\",\"room\":\"!r:x\",\"event\":\"$a2\"");

        final Run before =
                waitFor("[" + event("$line", "@bob:x") + "," + event("$a1", "@pi:x") + "]");
        assertThat(before.exit()).as("only an answer before the line").isEqualTo(Exit.TEMPORARY);
        assertThat(before.err())
                .as("the line waited on")
                .contains("no answer from @pi:x after $line within 0 s");

        final Run redacted =
                waitFor(
                        "[{\"type\":\"m.room.message\",\"event_id\":\"$r\",\"sender\":\"@pi:x\","
                                + "\"content\":{}},"
                                + event("$line", "@bob:x")
                                + "]");
        assertThat(redacted.exit()).as("only a redacted answer").isEqualTo(Exit.TEMPORARY);

        final Run other =
                waitFor("[" + event("$o", "@pi-two:x") + "," + event("$line", "@bob:x") + "]");
        assertThat(other.exit()).as("only another member answered").isEqualTo(Exit.TEMPORARY);
        assertThat(other.out()).as("no answer reported").doesNotContain("wait-answer");
    }

    /** Harm guarded: a mistyped limit holding the rehearsal for days. */
    @Test
    @org.junit.jupiter.api.Timeout(30)
    void aWaitLongerThanAnHourIsRefused() throws Exception {
        final Path socket = dir.resolve("gate.sock");
        final Path line = Files.writeString(dir.resolve("wake"), "Agent Pi: are you there?");
        try (ScriptedGate gate = new ScriptedGate(socket, roomGate("[]"))) {
            final Run run =
                    run(socket, "sign-in\npost !r:x " + line + "\nwait-answer !r:x @pi:x 3601\n");

            assertThat(run.exit()).as("the run's exit").isEqualTo(Exit.USAGE);
            assertThat(run.err()).as("the refusal").contains("seconds must be 0 to 3600");
        }
    }

    /** A page of {@code events}, newest first, and the token of the next older page, if any. */
    private static String page(final List<String> events, final String end) {
        return "{\"chunk\":["
                + String.join(",", events)
                + "]"
                + (end.isEmpty() ? "" : ",\"end\":\"" + end + "\"")
                + "}";
    }

    /**
     * Harm guarded: a line hidden behind a page of later events, so its answer is never seen, and
     * other events than messages crowding the page.
     */
    @Test
    void aReadPagesBackToTheLineAndAsksOnlyForMessages() throws Exception {
        final Path socket = dir.resolve("gate.sock");
        final Path line = Files.writeString(dir.resolve("wake"), "Agent Pi: are you there?");
        final List<String> later = new java.util.ArrayList<>();
        for (int i = 0; i < 100; i++) {
            later.add(event("$x" + i, "@ada:x"));
        }
        final Function<String, String> signing = signingGate(SIGNED_IN);
        try (ScriptedGate gate =
                new ScriptedGate(
                        socket,
                        request -> {
                            if (request.startsWith("PUT /_matrix/client/v3/rooms/")) {
                                return json(200, "{\"event_id\":\"$line\"}");
                            }
                            if (request.contains("/messages?") && request.contains("from=t2")) {
                                return json(200, page(List.of(event("$old", "@pi:x")), ""));
                            }
                            if (request.contains("/messages?") && request.contains("from=t1")) {
                                return json(
                                        200,
                                        page(
                                                List.of(
                                                        event("$a", "@pi:x"),
                                                        event("$line", "@bob:x")),
                                                "t2"));
                            }
                            if (request.contains("/messages?")) {
                                return json(200, page(later, "t1"));
                            }
                            return signing.apply(request);
                        })) {
            final Run run =
                    run(
                            socket,
                            "sign-in\npost !r:x " + line + "\nwait-answer !r:x @pi:x 0\nlog-out\n");

            assertThat(run.exit()).as("answered, stderr: " + run.err()).isZero();
            assertThat(run.out())
                    .as("the answer behind the later page")
                    .contains("\"step\":\"wait-answer\",\"room\":\"!r:x\",\"event\":\"$a\"");
            assertThat(gate.requests)
                    .as("each read asks for messages only, and stops at the line")
                    .filteredOn(r -> r.contains("/messages?"))
                    .hasSize(2)
                    .allMatch(
                            r ->
                                    r.contains(
                                            "&filter="
                                                    + MatrixClient.segment(
                                                            "{\"types\":[\"m.room.message\"]}")));
        }
    }

    /** Harm guarded: a line the room does not hold waited on as if it might still be answered. */
    @Test
    void aLineTheRoomDoesNotHoldFailsTheWait() throws Exception {
        final Run run = waitFor("[" + event("$a1", "@pi:x") + "]");

        assertThat(run.exit()).as("the line is not in the room").isEqualTo(Exit.DATA);
        assertThat(run.err()).as("what was read").contains("$line not in it");
    }

    /** Harm guarded: a room's lines given as many answers, unlike every other step's one. */
    @Test
    void aReadAnswersOneLineWithTheRoomsMessages() throws Exception {
        final Path socket = dir.resolve("gate.sock");
        final Function<String, String> room =
                roomGate("[" + event("$b", "@pi:x") + "," + event("$a", "@bob:x") + "]");
        try (ScriptedGate gate =
                new ScriptedGate(
                        socket,
                        request ->
                                request.contains("/joined_members")
                                        ? json(
                                                200,
                                                "{\"joined\":{\"@pi:x\":{\"display_name\":"
                                                        + "\"Agent Pi\"},\"@bob:x\":{}}}")
                                        : room.apply(request))) {
            final Run run = run(socket, "sign-in\nread !r:x\nlog-out\n");

            assertThat(run.exit()).as("the run's exit, stderr: " + run.err()).isZero();
            assertThat(run.out().lines().filter(l -> l.contains("\"step\":\"read\"")))
                    .as("one answer, the messages oldest first")
                    .containsExactly(
                            "{\"step\":\"read\",\"room\":\"!r:x\",\"messages\":["
                                    + "{\"event\":\"$a\",\"sender\":\"@bob:x\",\"display\":"
                                    + "\"@bob:x\",\"body\":\"from @bob:x\"},"
                                    + "{\"event\":\"$b\",\"sender\":\"@pi:x\",\"display\":"
                                    + "\"Agent Pi\",\"body\":\"from @pi:x\"}]}");
        }
    }

    /** Harm guarded: a join answered without its room taken for one. */
    @Test
    void aJoinNamesTheRoomOrFails() throws Exception {
        final Path socket = dir.resolve("gate.sock");
        final Function<String, String> signing = signingGate(SIGNED_IN);
        final String[] answer = {"{\"room_id\":\"!r:x\"}"};
        try (ScriptedGate gate =
                new ScriptedGate(
                        socket,
                        request ->
                                request.startsWith("POST /_matrix/client/v3/join/")
                                        ? json(200, answer[0])
                                        : signing.apply(request))) {
            final Run joined = run(socket, "sign-in\njoin #r:x\nlog-out\n");
            assertThat(joined.out())
                    .as("the room joined")
                    .contains("{\"step\":\"join\",\"room\":\"!r:x\"}");
            assertThat(gate.requests)
                    .as("the room asked for")
                    .anyMatch(
                            r ->
                                    r.startsWith(
                                            "POST /_matrix/client/v3/join/"
                                                    + MatrixClient.segment("#r:x")
                                                    + " "));

            answer[0] = "{}";
            final Run refused = run(socket, "sign-in\njoin #r:x\n");
            assertThat(refused.exit()).as("no room in the answer").isEqualTo(Exit.PROTOCOL);
            assertThat(refused.err()).contains("join answered with no room_id");
        }
    }

    /** Harm guarded: a direct chat opened other than as an unencrypted chat with that member. */
    @Test
    void aDirectChatIsOneUnencryptedChatWithTheMember() throws Exception {
        final Path socket = dir.resolve("gate.sock");
        final Path line = Files.writeString(dir.resolve("line"), "only for you");
        final Function<String, String> signing = signingGate(SIGNED_IN);
        try (ScriptedGate gate =
                new ScriptedGate(
                        socket,
                        request -> {
                            if (request.startsWith("POST /_matrix/client/v3/createRoom")) {
                                return json(200, "{\"room_id\":\"!dm:x\"}");
                            }
                            if (request.startsWith("PUT /_matrix/client/v3/rooms/")) {
                                return json(200, "{\"event_id\":\"$d\"}");
                            }
                            if (request.contains("/leave")) {
                                return json(200, "{}");
                            }
                            return signing.apply(request);
                        })) {
            final Run run = run(socket, "sign-in\ndirect @pi:x " + line + "\nlog-out\n");

            assertThat(run.out())
                    .as("the chat and the line")
                    .contains("{\"step\":\"direct\",\"room\":\"!dm:x\",\"event\":\"$d\"}");
            final String create =
                    gate.requests.stream()
                            .filter(r -> r.startsWith("POST /_matrix/client/v3/createRoom"))
                            .findFirst()
                            .orElseThrow();
            final var body =
                    MatrixClient.JSON.readTree(create.substring(create.indexOf("\r\n\r\n") + 4));
            assertThat(body.path("is_direct").asBoolean()).as("direct").isTrue();
            assertThat(body.path("preset").asText()).isEqualTo("trusted_private_chat");
            assertThat(body.path("invite").toString()).isEqualTo("[\"@pi:x\"]");
            assertThat(body.has("initial_state")).as("no encryption asked").isFalse();
        }
    }

    /** Harm guarded: a room's members read wrong, so a stranger passes or a member is missed. */
    @Test
    void theMembersAreTheJoinedOnes() throws Exception {
        final Path socket = dir.resolve("gate.sock");
        final Function<String, String> signing = signingGate(SIGNED_IN);
        try (ScriptedGate gate =
                        new ScriptedGate(
                                socket,
                                request -> {
                                    if (request.contains("/joined_members")) {
                                        return json(
                                                200, "{\"joined\":{\"@pi:x\":{},\"@bob:x\":{}}}");
                                    }
                                    if (request.contains("/members?membership=invite")) {
                                        return json(
                                                200,
                                                "{\"chunk\":[{\"type\":\"m.room.member\","
                                                        + "\"state_key\":\"@eve:x\"}]}");
                                    }
                                    return signing.apply(request);
                                });
                RehearsalClient client =
                        new RehearsalClient(
                                socket,
                                new PrintStream(
                                        new ByteArrayOutputStream(),
                                        true,
                                        StandardCharsets.UTF_8))) {
            client.step("sign-in");

            assertThat(client.members("!r:x"))
                    .as("the joined and the invited")
                    .containsExactlyInAnyOrder("@pi:x", "@bob:x", "@eve:x");
            assertThat(gate.requests)
                    .anyMatch(
                            r ->
                                    r.startsWith(
                                            "GET /_matrix/client/v3/rooms/"
                                                    + MatrixClient.segment("!r:x")
                                                    + "/joined_members "));
        }
    }

    /** Harm guarded: a run interrupted while it waits leaves the person's session open. */
    @Test
    void anInterruptedRunStillLogsOut() throws Exception {
        final Path socket = dir.resolve("gate.sock");
        try (ScriptedGate gate = new ScriptedGate(socket, signingGate(SIGNED_IN))) {
            final RehearsalClient client =
                    new RehearsalClient(
                            socket,
                            new PrintStream(
                                    new ByteArrayOutputStream(), true, StandardCharsets.UTF_8));
            client.step("sign-in");
            Thread.currentThread().interrupt();
            try {
                client.close();

                assertThat(Thread.currentThread().isInterrupted())
                        .as("the interrupt given back")
                        .isTrue();
            } finally {
                Thread.interrupted();
            }
            assertThat(gate.requests)
                    .as("the session logged out")
                    .anyMatch(
                            r ->
                                    r.startsWith("POST /_matrix/client/v3/logout")
                                            && r.contains("Bearer " + SESSION));
        }
    }

    /** Harm guarded: steps ending with the session open, and a logout that failed said nowhere. */
    @Test
    void stepsEndingSignedInLogOutAndFailWhenThatFails() throws Exception {
        final Path socket = dir.resolve("gate.sock");
        try (ScriptedGate gate = new ScriptedGate(socket, signingGate(SIGNED_IN))) {
            final Run run = run(socket, "sign-in\n");

            assertThat(run.exit()).as("logged out, stderr: " + run.err()).isZero();
            assertThat(run.out()).as("no step asked for, none said").doesNotContain("log-out");
            assertThat(gate.requests)
                    .as("the session logged out")
                    .anyMatch(r -> r.startsWith("POST /_matrix/client/v3/logout"));
        }
        final Path held = dir.resolve("held.sock");
        final Function<String, String> signing = signingGate(SIGNED_IN);
        try (ScriptedGate gate =
                new ScriptedGate(
                        held,
                        request ->
                                request.startsWith("GET /_matrix/client/v3/account/whoami")
                                        ? json(200, "{\"user_id\":\"@bob:x\"}")
                                        : signing.apply(request))) {
            final Run run = run(held, "sign-in\n");

            assertThat(run.exit()).as("the session survived").isEqualTo(Exit.PROTOCOL);
            assertThat(run.err())
                    .as("what failed")
                    .contains("FAILED: log-out at the end of the steps: logged out, but whoami");
        }
    }

    /**
     * A project's room behind the gate where the person's lines arrive and no task answers; it
     * joins the person only when asked.
     */
    private static Function<String, String> silentRoom(final List<String> posted) {
        final Function<String, String> signing = signingGate(SIGNED_IN);
        return request -> {
            if (request.startsWith("POST /_matrix/client/v3/join/")) {
                return json(200, "{\"room_id\":\"!r:x\"}");
            }
            if (request.startsWith("PUT /_matrix/client/v3/rooms/")) {
                posted.addFirst(event("$p" + posted.size(), "@bob:x"));
                return json(200, "{\"event_id\":\"$p" + (posted.size() - 1) + "\"}");
            }
            if (request.contains("/messages?")) {
                return json(200, page(posted, ""));
            }
            if (request.contains("/joined_members")) {
                return json(200, "{\"joined\":{\"@bob:x\":{}}}");
            }
            return signing.apply(request);
        };
    }

    private Run rehearse(final Path socket, final String... tasks) {
        return rehearseWithin(socket, "0", tasks);
    }

    private Run rehearseWithin(
            final Path socket, final String answerWithin, final String... tasks) {
        final List<String> args =
                new java.util.ArrayList<>(
                        List.of(
                                "--socket",
                                socket.toString(),
                                "--room",
                                "!r:x",
                                "--answer-within",
                                answerWithin,
                                "--quiet-for",
                                "0"));
        for (final String task : tasks) {
            args.add("--task");
            args.add(task);
        }
        args.addAll(List.of("--talk-log", "sh", "-c", "true", "sh"));
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        final ByteArrayOutputStream err = new ByteArrayOutputStream();
        final int exit =
                RoomRehearsal.run(
                        args.toArray(String[]::new),
                        new PrintStream(out, true, StandardCharsets.UTF_8),
                        new PrintStream(err, true, StandardCharsets.UTF_8));
        return new Run(
                exit, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    /**
     * Harm guarded: a failed rehearsal ending 0, a room the person was only invited to failed
     * instead of joined, or the session left open after the run.
     */
    @Test
    void aRehearsalJoinsFirstAndEndsLoggedOutWithTheExitOfItsFailure() throws Exception {
        final Path socket = dir.resolve("gate.sock");
        final List<String> posted = new CopyOnWriteArrayList<>();
        try (ScriptedGate gate = new ScriptedGate(socket, silentRoom(posted))) {
            final Run run =
                    rehearse(socket, "pi=@pi.bob:x=Agent Pi", "core=@core.bob:x=Agent Core");

            assertThat(run.exit()).as("the run's exit, stderr: " + run.err()).isEqualTo(Exit.DATA);
            assertThat(run.out())
                    .as("the failure and the stop")
                    .contains(
                            "FAIL a head names one task: asked Agent Pi in the room, got no answer")
                    .contains("STOP the same failure twice in a row")
                    .contains("\"step\":\"log-out\"");
            final List<String> asked = gate.requests.stream().map(r -> r.split(" ")[1]).toList();
            assertThat(asked.indexOf("/_matrix/client/v3/join/" + MatrixClient.segment("!r:x")))
                    .as("joined before the first line")
                    .isBetween(
                            0,
                            asked.indexOf(
                                    asked.stream()
                                            .filter(a -> a.contains("/send/"))
                                            .findFirst()
                                            .orElseThrow()));
            assertThat(run.all()).as("no token in what the run says").doesNotContain(SESSION);
        }
    }

    /** Harm guarded: a rehearsal judging a task against itself, after asking the host anything. */
    @Test
    void aRehearsalWithATaskNamedTwiceAsksNothing() throws Exception {
        final Path socket = dir.resolve("gate.sock");
        try (ScriptedGate gate =
                new ScriptedGate(socket, silentRoom(new CopyOnWriteArrayList<>()))) {
            final Run run = rehearse(socket, "pi=@pi.bob:x=Agent Pi", "pi=@pi.bob:x=Agent Pi");

            assertThat(run.exit()).as("the run's exit").isEqualTo(Exit.USAGE);
            assertThat(run.err()).as("the refusal").contains("--task names pi a second time");
            assertThat(gate.requests).as("nothing asked of the gate").isEmpty();
        }
    }

    /** Harm guarded: a homeserver that echoes the session's token getting it printed. */
    @Test
    void aFailureEchoingTheTokenPrintsNoneOfIt() throws Exception {
        final Path socket = dir.resolve("gate.sock");
        final Path line = Files.writeString(dir.resolve("line"), "hello");
        final Function<String, String> signing = signingGate(SIGNED_IN);
        try (ScriptedGate gate =
                new ScriptedGate(
                        socket,
                        request ->
                                request.startsWith("PUT /_matrix/client/v3/rooms/")
                                        ? json(
                                                400,
                                                "{\"errcode\":\"M_UNKNOWN\",\"error\":\"bad Bearer "
                                                        + SESSION
                                                        + " and "
                                                        + LOGIN_TOKEN
                                                        + "\"}")
                                        : signing.apply(request))) {
            final Run run = run(socket, "sign-in\npost !r:x " + line + "\n");

            assertThat(run.exit()).as("the run's exit").isNotZero();
            assertThat(run.err()).as("the failure").contains("<token>");
            assertThat(run.all())
                    .as("no token in what the run says")
                    .doesNotContain(SESSION)
                    .doesNotContain(LOGIN_TOKEN);
        }
    }

    /** Harm guarded: a homeserver naming the same page as the next one holding a read for ever. */
    @Test
    @org.junit.jupiter.api.Timeout(20)
    void aPageNamingItselfAsTheNextEndsTheRead() throws Exception {
        final Path socket = dir.resolve("gate.sock");
        final Path line = Files.writeString(dir.resolve("wake"), "Agent Pi: are you there?");
        final Function<String, String> signing = signingGate(SIGNED_IN);
        try (ScriptedGate gate =
                new ScriptedGate(
                        socket,
                        request -> {
                            if (request.startsWith("PUT /_matrix/client/v3/rooms/")) {
                                return json(200, "{\"event_id\":\"$line\"}");
                            }
                            if (request.contains("/messages?")) {
                                return json(200, page(List.of(event("$a", "@pi:x")), "t1"));
                            }
                            return signing.apply(request);
                        })) {
            final Run run =
                    run(socket, "sign-in\npost !r:x " + line + "\nwait-answer !r:x @pi:x 0\n");

            assertThat(run.exit()).as("the read ended").isEqualTo(Exit.DATA);
            assertThat(gate.requests)
                    .as("the page asked twice, not again")
                    .filteredOn(r -> r.contains("/messages?"))
                    .hasSize(2);
        }
    }

    /** Harm guarded: a direct chat the steps did not leave stays open after the run. */
    @Test
    void aDirectChatLeftOpenByTheStepsIsLeftAtTheLogOut() throws Exception {
        final Path socket = dir.resolve("gate.sock");
        final Path line = Files.writeString(dir.resolve("line"), "only for you");
        final Function<String, String> signing = signingGate(SIGNED_IN);
        try (ScriptedGate gate =
                new ScriptedGate(
                        socket,
                        request -> {
                            if (request.startsWith("POST /_matrix/client/v3/createRoom")) {
                                return json(200, "{\"room_id\":\"!dm:x\"}");
                            }
                            if (request.startsWith("PUT /_matrix/client/v3/rooms/")) {
                                return json(200, "{\"event_id\":\"$d\"}");
                            }
                            if (request.contains("/leave")) {
                                return json(200, "{}");
                            }
                            return signing.apply(request);
                        })) {
            final Run run = run(socket, "sign-in\ndirect @pi:x " + line + "\n");

            assertThat(run.exit()).as("the run's exit, stderr: " + run.err()).isZero();
            final List<String> paths = gate.requests.stream().map(r -> r.split(" ")[1]).toList();
            assertThat(paths)
                    .as("the chat left before the logout")
                    .containsSubsequence(
                            "/_matrix/client/v3/rooms/" + MatrixClient.segment("!dm:x") + "/leave",
                            "/_matrix/client/v3/logout");
        }
    }

    /** Harm guarded: a read showing an edit, a thread's line or a redacted line as a line. */
    @Test
    void aReadShowsOnlyNewTextLines() throws Exception {
        final Path socket = dir.resolve("gate.sock");
        final String edit =
                "{\"type\":\"m.room.message\",\"event_id\":\"$e\",\"sender\":\"@pi:x\","
                        + "\"content\":{\"msgtype\":\"m.text\",\"body\":\"* fixed\","
                        + "\"m.relates_to\":{\"rel_type\":\"m.replace\",\"event_id\":\"$a\"}}}";
        final String redacted =
                "{\"type\":\"m.room.message\",\"event_id\":\"$r\",\"sender\":\"@pi:x\","
                        + "\"content\":{}}";
        final String thread =
                "{\"type\":\"m.room.message\",\"event_id\":\"$t\",\"sender\":\"@pi:x\","
                        + "\"content\":{\"msgtype\":\"m.text\",\"body\":\"aside\","
                        + "\"m.relates_to\":{\"rel_type\":\"m.thread\",\"event_id\":\"$a\"}}}";
        final String empty =
                "{\"type\":\"m.room.message\",\"event_id\":\"$n\",\"sender\":\"@pi:x\","
                        + "\"content\":{\"msgtype\":\"m.text\"}}";
        final Function<String, String> room =
                roomGate(
                        "["
                                + edit
                                + ","
                                + redacted
                                + ","
                                + thread
                                + ","
                                + empty
                                + ","
                                + event("$a", "@bob:x")
                                + "]");
        try (ScriptedGate gate =
                new ScriptedGate(
                        socket,
                        request ->
                                request.contains("/joined_members")
                                        ? json(200, "{\"joined\":{}}")
                                        : room.apply(request))) {
            final Run run = run(socket, "sign-in\nread !r:x\nlog-out\n");

            assertThat(run.out())
                    .as("only the new line")
                    .contains("\"messages\":[{\"event\":\"$a\"")
                    .doesNotContain("$e")
                    .doesNotContain("$r")
                    .doesNotContain("$t")
                    .doesNotContain("$n");
        }
    }

    /** Harm guarded: a direct chat left open when a later step fails. */
    @Test
    void aDirectChatIsLeftWhenALaterStepFails() throws Exception {
        final Path socket = dir.resolve("gate.sock");
        final Path line = Files.writeString(dir.resolve("line"), "only for you");
        final Function<String, String> signing = signingGate(SIGNED_IN);
        try (ScriptedGate gate =
                new ScriptedGate(
                        socket,
                        request -> {
                            if (request.startsWith("POST /_matrix/client/v3/createRoom")) {
                                return json(200, "{\"room_id\":\"!dm:x\"}");
                            }
                            if (request.startsWith("PUT /_matrix/client/v3/rooms/")) {
                                return json(200, "{\"event_id\":\"$d\"}");
                            }
                            if (request.contains("/leave")) {
                                return json(200, "{}");
                            }
                            return signing.apply(request);
                        })) {
            final Run run =
                    run(
                            socket,
                            "sign-in\ndirect @pi:x "
                                    + line
                                    + "\npost !r:x "
                                    + dir.resolve("missing")
                                    + "\n");

            assertThat(run.exit()).as("the later step failed").isEqualTo(Exit.DATA);
            final List<String> paths = gate.requests.stream().map(r -> r.split(" ")[1]).toList();
            assertThat(paths)
                    .as("the chat left before the logout")
                    .containsSubsequence(
                            "/_matrix/client/v3/rooms/" + MatrixClient.segment("!dm:x") + "/leave",
                            "/_matrix/client/v3/logout");
        }
    }

    /** Harm guarded: a rehearsal printing the session's token when a step's answer echoes it. */
    @Test
    void aRehearsalsFailurePrintsNoToken() throws Exception {
        final Path socket = dir.resolve("gate.sock");
        final Function<String, String> signing = signingGate(SIGNED_IN);
        try (ScriptedGate gate =
                new ScriptedGate(
                        socket,
                        request ->
                                request.startsWith("POST /_matrix/client/v3/join/")
                                        ? json(
                                                403,
                                                "{\"errcode\":\"M_FORBIDDEN\",\"error\":\"no, "
                                                        + SESSION
                                                        + "\"}")
                                        : signing.apply(request))) {
            final Run run =
                    rehearse(socket, "pi=@pi.bob:x=Agent Pi", "core=@core.bob:x=Agent Core");

            assertThat(run.exit()).as("the run's exit").isNotZero();
            assertThat(run.err()).as("the failure").startsWith("FAILED: ").contains("<token>");
            assertThat(run.all()).as("no token").doesNotContain(SESSION);
        }
    }

    /** Harm guarded: an interrupted rehearsal failing at its log-out and leaving the session. */
    @Test
    @org.junit.jupiter.api.Timeout(30)
    void anInterruptedRehearsalStopsAndLogsOut() throws Exception {
        final Path socket = dir.resolve("gate.sock");
        final List<String> posted = new CopyOnWriteArrayList<>();
        final Thread caller = Thread.currentThread();
        final Function<String, String> room = silentRoom(posted);
        try (ScriptedGate gate =
                new ScriptedGate(
                        socket,
                        request -> {
                            if (request.contains("/messages?")) {
                                caller.interrupt();
                            }
                            return room.apply(request);
                        })) {
            final Run run;
            try {
                run =
                        rehearseWithin(
                                socket,
                                "30",
                                "pi=@pi.bob:x=Agent Pi",
                                "core=@core.bob:x=Agent Core");
            } finally {
                Thread.interrupted();
            }

            assertThat(run.exit())
                    .as("the run's exit, said: " + run.all())
                    .isEqualTo(Exit.TEMPORARY);
            assertThat(run.out()).as("the stop").contains("STOP interrupted");
            assertThat(run.err()).as("no failed log-out").isEmpty();
            assertThat(gate.requests)
                    .as("the session logged out")
                    .anyMatch(r -> r.startsWith("POST /_matrix/client/v3/logout"));
        }
    }

    /**
     * The project foo's room behind the gate, found by its alias, with two of bob's tasks in it.
     */
    private static Function<String, String> projectRoom(final List<String> posted) {
        final Function<String, String> silent = silentRoom(posted);
        return request -> {
            if (request.startsWith("GET /_matrix/client/v3/directory/room/")) {
                return request.startsWith(
                                "GET /_matrix/client/v3/directory/room/"
                                        + MatrixClient.segment("#porelo-foo:x")
                                        + " ")
                        ? json(200, "{\"room_id\":\"!r:x\",\"servers\":[\"x\"]}")
                        : json(404, "{\"errcode\":\"M_NOT_FOUND\"}");
            }
            if (request.contains("/joined_members")) {
                return json(
                        200,
                        "{\"joined\":{\"@bob:x\":{\"display_name\":\"Bob\"},"
                                + "\"@porelo-foo-pi.bob:x\":{\"display_name\":\"Agent Pi\"},"
                                + "\"@porelo-foo-core.bob:x\":{\"display_name\":\"Agent Core\"}}}");
            }
            return silent.apply(request);
        };
    }

    private Run rehearseProject(final Path socket, final String project) {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        final ByteArrayOutputStream err = new ByteArrayOutputStream();
        final int exit =
                RoomRehearsal.run(
                        new String[] {
                            "--socket",
                            socket.toString(),
                            "--project",
                            project,
                            "--answer-within",
                            "0",
                            "--quiet-for",
                            "0",
                            "--talk-log",
                            "sh",
                            "-c",
                            "true",
                            "sh"
                        },
                        new PrintStream(out, true, StandardCharsets.UTF_8),
                        new PrintStream(err, true, StandardCharsets.UTF_8));
        return new Run(
                exit, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    /**
     * Harm guarded: a run given only the project rehearsing another room than the project's, or
     * tasks it did not find in it, or not saying what it found, so its lines cannot be read back.
     */
    @Test
    void aProjectsRunFindsItsRoomAndTasksAndSaysThem() throws Exception {
        final Path socket = dir.resolve("gate.sock");
        try (ScriptedGate gate =
                new ScriptedGate(socket, projectRoom(new CopyOnWriteArrayList<>()))) {
            final Run run = rehearseProject(socket, "foo");

            assertThat(run.exit()).as("the run's exit, stderr: " + run.err()).isEqualTo(Exit.DATA);
            assertThat(run.out())
                    .as("what it found, then the scenarios")
                    .contains(
                            "ROOM !r:x (#porelo-foo:x)\n"
                                    + "TASK core @porelo-foo-core.bob:x Agent Core\n"
                                    + "TASK pi @porelo-foo-pi.bob:x Agent Pi\n")
                    .contains(
                            "FAIL a head names one task: asked Agent Core in the room, got no"
                                    + " answer");
            final List<String> asked = gate.requests.stream().map(r -> r.split(" ")[1]).toList();
            assertThat(asked.indexOf("/_matrix/client/v3/join/" + MatrixClient.segment("!r:x")))
                    .as("joined the room the alias named, before reading who is in it")
                    .isBetween(
                            0, asked.indexOf("/_matrix/client/v3/rooms/%21r%3Ax/joined_members"));
        }
    }

    /** Harm guarded: a project with no room on the host failing as anything but its data. */
    @Test
    void aProjectWithNoRoomEndsWithItsDataAndLogsOut() throws Exception {
        final Path socket = dir.resolve("gate.sock");
        try (ScriptedGate gate =
                new ScriptedGate(socket, projectRoom(new CopyOnWriteArrayList<>()))) {
            final Run run = rehearseProject(socket, "bar");

            assertThat(run.exit()).as("the run's exit, stderr: " + run.err()).isEqualTo(Exit.DATA);
            assertThat(run.err())
                    .as("which room")
                    .contains("FAILED: no room is named #porelo-bar:x");
            assertThat(gate.requests.stream().map(r -> r.split(" ")[1]))
                    .as("logged out")
                    .contains("/_matrix/client/v3/logout");
        }
    }
}
