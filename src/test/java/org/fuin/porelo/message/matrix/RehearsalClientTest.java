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
                                        return;
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
            assertThat(run.err()).as("the failed step").startsWith("FAILED: post !r:x ");
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
}
