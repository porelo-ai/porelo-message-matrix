package org.fuin.porelo.message.matrix;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GateTest {
    @TempDir private Path dir;
    private final Map<String, String> env = new HashMap<>();
    private final List<String> calls = new ArrayList<>();
    private final ByteArrayOutputStream log = new ByteArrayOutputStream();
    private Path group;
    private String identity = "@alice:example.org";
    private Gate gate;
    private final String adminToken = Accounts.secret();
    private final String sessionToken = Accounts.secret();
    private boolean taken;
    private int identityStatus = 200;
    private String lastAnswer = "";

    @BeforeEach
    void prepare() throws Exception {
        env.put("XDG_CONFIG_HOME", dir.resolve("config").toString());
        group = Files.writeString(dir.resolve("group"), "porelo-people:x:101:alice\n");
        GateFiles.privateDirectory(GateFiles.directory(env));
        GateFiles.write(GateFiles.directory(env).resolve("admin-token"), adminToken, false);
        gate =
                new Gate(
                        "alice",
                        env,
                        group,
                        dir.resolve("projects"),
                        new Gate.Backend() {
                            @Override
                            public GateResponse exchange(
                                    final String token,
                                    final String method,
                                    final String path,
                                    final @Nullable JsonNode body) {
                                calls.add(path);
                                if (path.endsWith("whoami")) {
                                    return new GateResponse(
                                            token.equals(adminToken) ? 200 : identityStatus,
                                            MatrixClient.JSON
                                                    .createObjectNode()
                                                    .put(
                                                            "user_id",
                                                            token.equals(adminToken)
                                                                    ? "@porelo:example.org"
                                                                    : identity)
                                                    .put("device_id", "device"));
                                }
                                if (path.contains("username_available") && taken) {
                                    return GateResponse.error(400, "M_USER_IN_USE");
                                }
                                if (path.startsWith("/_synapse/") && path.endsWith("/login")) {
                                    return new GateResponse(
                                            200,
                                            MatrixClient.JSON
                                                    .createObjectNode()
                                                    .put("access_token", sessionToken));
                                }
                                if (path.endsWith("/login")) {
                                    return new GateResponse(
                                            200,
                                            MatrixClient.JSON
                                                    .createObjectNode()
                                                    .put("user_id", identity)
                                                    .put("access_token", Accounts.secret())
                                                    .put("device_id", "device"));
                                }
                                return new GateResponse(
                                        200,
                                        MatrixClient.JSON
                                                .createObjectNode()
                                                .put("available", true));
                            }

                            @Override
                            public int forward(final GateRequest request, final OutputStream out)
                                    throws java.io.IOException {
                                calls.add(request.path());
                                new GateResponse(200, MatrixClient.JSON.createObjectNode())
                                        .write(out, false);
                                return 200;
                            }
                        },
                        new PrintStream(log, true, StandardCharsets.UTF_8));
    }

    private int request(
            final String method, final String path, final String token, final String body)
            throws Exception {
        return request(method, path, token, body, "localhost");
    }

    private int request(
            final String method,
            final String path,
            final String token,
            final String body,
            final String host)
            throws Exception {
        return request(method, path, token, body, host, "");
    }

    private int request(
            final String method,
            final String path,
            final String token,
            final String body,
            final String host,
            final String headers)
            throws Exception {
        final String wire =
                method
                        + " "
                        + path
                        + " HTTP/1.1\r\nHost: "
                        + host
                        + "\r\n"
                        + headers
                        + (token.isEmpty() ? "" : "Authorization: Bearer " + token + "\r\n")
                        + "Content-Length: "
                        + body.getBytes(StandardCharsets.UTF_8).length
                        + "\r\n\r\n"
                        + body;
        final var out = new ByteArrayOutputStream();
        gate.serve(new ByteArrayInputStream(wire.getBytes(StandardCharsets.UTF_8)), out);
        lastAnswer = out.toString(StandardCharsets.UTF_8);
        return Integer.parseInt(lastAnswer.substring(9, 12));
    }

    /** Harm guarded: a foreign account signs in by password, token, or registration. */
    @Test
    void foreignAccountsNeverReachLoginOrRegistration() throws Exception {
        for (final String user :
                List.of(
                        "bob",
                        "@x.bob:example.org",
                        "@.bob:example.org",
                        "porelo",
                        "Alice",
                        "a b",
                        "x..alice")) {
            final String body =
                    MatrixClient.JSON
                            .createObjectNode()
                            .put("type", "m.login.password")
                            .put("user", user)
                            .put("password", Accounts.secret())
                            .toString();
            assertThat(request("POST", "/_matrix/client/v3/login", "", body))
                    .as("foreign password login")
                    .isEqualTo(403);
            assertThat(
                            request(
                                    "POST",
                                    "/_matrix/client/v3/register",
                                    "",
                                    MatrixClient.JSON
                                            .createObjectNode()
                                            .put("username", user)
                                            .toString()))
                    .as("foreign registration")
                    .isEqualTo(400);
            assertThat(
                            request(
                                    "GET",
                                    "/_matrix/client/v3/register/available?username="
                                            + MatrixClient.segment(user),
                                    "",
                                    ""))
                    .as("asking about a foreign name")
                    .isEqualTo(400);
        }
        assertThat(
                        request(
                                "POST",
                                "/_matrix/client/v3/login",
                                "",
                                "{\"type\":\"m.login.token\",\"token\":\"unissued\"}"))
                .as("an upstream login token is not a gate token")
                .isEqualTo(403);
        assertThat(calls).as("nothing was sent upstream").isEmpty();
    }

    /** Harm guarded: an unbound bearer or query token reaches a protected endpoint. */
    @Test
    void everyTokenIsBoundAndItsCacheIsBounded() throws Exception {
        identity = "@x.bob:example.org";
        assertThat(request("GET", "/_matrix/client/v3/sync", Accounts.secret(), ""))
                .as("foreign bearer")
                .isEqualTo(403);
        assertThat(
                        request(
                                "GET",
                                "/_matrix/client/v3/sync?access_token=" + Accounts.secret(),
                                "",
                                ""))
                .as("foreign query token")
                .isEqualTo(403);
        assertThat(calls).as("only identity calls").allMatch(path -> path.endsWith("whoami"));
        identity = "@alice:example.org";
        calls.clear();
        final String first = Accounts.secret();
        assertThat(request("GET", "/_matrix/client/v3/sync", first, ""))
                .as("own bearer")
                .isEqualTo(200);
        assertThat(request("GET", "/_matrix/client/v3/sync", first, ""))
                .as("cached identity")
                .isEqualTo(200);
        for (int i = 0; i < 64; i++) {
            request("GET", "/_matrix/client/v3/sync", Accounts.secret(), "");
        }
        request("GET", "/_matrix/client/v3/sync", first, "");
        assertThat(calls.stream().filter(path -> path.endsWith("whoami")).count())
                .as("old identity is evicted after 64 different tokens")
                .isEqualTo(66);
    }

    /** Harm guarded: locking or expulsion leaves an open connection authorized. */
    @Test
    void groupAndExpulsionAreReadAtEveryRequest() throws Exception {
        assertThat(request("GET", "/_matrix/client/versions", "", ""))
                .as("admitted")
                .isEqualTo(200);
        calls.clear();
        Files.writeString(group, "porelo-people:x:101:\n");
        assertThat(request("GET", "/_matrix/client/versions", "", "")).as("locked").isEqualTo(403);
        Files.writeString(group, "porelo-people:x:101:alice\n");
        final Path expelled =
                Files.createDirectories(LocalHomeserver.directory(env).resolve(Expel.DIRECTORY));
        Files.createFile(expelled.resolve("alice"));
        assertThat(request("GET", "/_matrix/client/versions", "", ""))
                .as("expelled")
                .isEqualTo(403);
        assertThat(calls).as("refused uid reaches no upstream").isEmpty();
    }

    /** Harm guarded: a homeserver's unexpected login answer releases a foreign session. */
    @Test
    void anUnexpectedLoginAccountIsLoggedOutAndNeverAnswered() throws Exception {
        identity = "@bob:example.org";
        assertThat(
                        request(
                                "POST",
                                "/_matrix/client/v3/login",
                                "",
                                "{\"type\":\"m.login.password\",\"user\":\"alice\",\"password\":\"fixture\"}"))
                .as("unexpected user is refused")
                .isEqualTo(403);
        assertThat(calls)
                .as("new device was logged out")
                .containsExactly("/_matrix/client/v3/login", "/_matrix/client/v3/logout");
    }

    /**
     * Harm guarded: callers become administrators before host preparation or bypass the API
     * allowlist.
     */
    @Test
    void noRegistrationBeforePreparationAndNoAdministrativeApi() throws Exception {
        Files.delete(GateFiles.directory(env).resolve("admin-token"));
        assertThat(request("POST", "/_matrix/client/v3/register", "", "{\"username\":\"alice\"}"))
                .as("administrator must already exist")
                .isEqualTo(403);
        assertThat(request("GET", "/_synapse/admin/v1/users", "", ""))
                .as("admin API is hidden")
                .isEqualTo(404);
        assertThat(request("GET", "/_matrix/client/v3/sync", "", ""))
                .as("missing bearer")
                .isEqualTo(401);
        assertThat(calls).as("no upstream activity").isEmpty();
    }

    /** Harm guarded: refusal logs disclose passwords or bearer tokens. */
    @Test
    void refusalLogsContainOnlyTheCallerMethodPathAndStatus() throws Exception {
        final String secret = Accounts.secret();
        identity = "@bob:example.org";
        request("GET", "/_matrix/client/v3/sync?access_token=" + secret, "", "");
        assertThat(log.toString(StandardCharsets.UTF_8))
                .as("one redacted refusal")
                .isEqualTo("alice GET /_matrix/client/v3/sync 403\n");
    }

    /**
     * Harm guarded: registration leaks an administrator token or skips the client's password stage.
     */
    @Test
    void ownRegistrationRequiresDummyAndNeverReplacesATakenName() throws Exception {
        assertThat(request("POST", "/_matrix/client/v3/register", "", "{\"username\":\"alice\"}"))
                .as("UIA challenge")
                .isEqualTo(401);
        final JsonNode challenge =
                MatrixClient.JSON.readTree(
                        lastAnswer.substring(lastAnswer.indexOf("\r\n\r\n") + 4));
        assertThat(challenge.path("flows").get(0).path("stages").get(0).asText())
                .as("only dummy is offered")
                .isEqualTo("m.login.dummy");
        calls.clear();
        assertThat(
                        request(
                                "POST",
                                "/_matrix/client/v3/register",
                                "",
                                "{\"username\":\"task.alice\",\"password\":\"fixture\",\"auth\":{\"type\":\"m.login.dummy\"}}"))
                .as("own task registration")
                .isEqualTo(200);
        assertThat(lastAnswer.contains(adminToken))
                .as("administrator credential is private")
                .isFalse();
        assertThat(calls)
                .as("password reaches only the admin creation and the client login")
                .anyMatch(path -> path.startsWith("/_synapse/admin/v2/users/"));
        taken = true;
        calls.clear();
        assertThat(
                        request(
                                "POST",
                                "/_matrix/client/v3/register",
                                "",
                                "{\"username\":\"alice\",\"password\":\"fixture\",\"auth\":{\"type\":\"m.login.dummy\"}}"))
                .as("taken user")
                .isEqualTo(400);
        assertThat(calls)
                .as("no password reset")
                .noneMatch(path -> path.startsWith("/_synapse/admin/v2/users/"));
    }

    /** Harm guarded: password or token sign-in bypasses ownership when the homeserver disagrees. */
    @Test
    void ownSsoTokenIsConsumedAndReturnsOnlyThePersonsSession() throws Exception {
        assertThat(
                        request(
                                "GET",
                                "/_matrix/client/v3/login/sso/redirect/porelo?redirectUrl="
                                        + MatrixClient.segment(
                                                "element://client/callback?x=1#keep"),
                                "",
                                ""))
                .as("SSO redirect")
                .isEqualTo(302);
        final String location =
                lastAnswer
                        .lines()
                        .filter(line -> line.startsWith("Location: "))
                        .findFirst()
                        .orElseThrow()
                        .substring(10);
        assertThat(location.endsWith("#keep")).as("fragment survives").isTrue();
        final String token =
                GateRequest.query(java.net.URI.create(location).getRawQuery()).get("loginToken");
        final String credentials =
                MatrixClient.JSON
                        .createObjectNode()
                        .put("type", "m.login.token")
                        .put("token", token)
                        .toString();
        assertThat(request("POST", "/_matrix/client/v3/login", "", credentials))
                .as("own single-use gate token")
                .isEqualTo(200);
        final JsonNode session =
                MatrixClient.JSON.readTree(
                        lastAnswer.substring(lastAnswer.indexOf("\r\n\r\n") + 4));
        assertThat(session.path("user_id").asText())
                .as("person's identity")
                .isEqualTo("@alice:example.org");
        assertThat(session.path("device_id").asText())
                .as("device obtained from whoami")
                .isEqualTo("device");
        assertThat(session.path("home_server").asText())
                .as("homeserver name")
                .isEqualTo("example.org");
        assertThat(lastAnswer.contains(adminToken))
                .as("administrator credential never leaves")
                .isFalse();
        calls.clear();
        assertThat(request("POST", "/_matrix/client/v3/login", "", credentials))
                .as("no replay")
                .isEqualTo(403);
        assertThat(calls).as("replay makes no upstream call").isEmpty();
    }

    /** Harm guarded: token validation failure falls through to a protected homeserver request. */
    @Test
    void failedWhoamiIsReturnedWithoutPassingTheRequest() throws Exception {
        identityStatus = 401;
        assertThat(request("GET", "/_matrix/client/v3/sync", Accounts.secret(), ""))
                .as("upstream refusal")
                .isEqualTo(401);
        assertThat(calls)
                .as("only whoami was attempted")
                .containsExactly("/_matrix/client/v3/account/whoami");
    }

    /** Harm guarded: a media upload or other version of a client route bypasses the token check. */
    @Test
    void missingTokensAndUnknownLoginTypesAreRefused() throws Exception {
        for (final String path :
                List.of(
                        "/_matrix/media/v3/upload",
                        "/_matrix/client/v3/rooms",
                        "/_matrix/client/v1/profile/alice")) {
            assertThat(request("GET", path, "", ""))
                    .as("only pre-login routes are public")
                    .isEqualTo(401);
        }
        assertThat(
                        request(
                                "POST",
                                "/_matrix/client/r0/login",
                                "",
                                "{\"type\":\"m.login.application_service\"}"))
                .as("unknown login type")
                .isEqualTo(400);
        assertThat(
                        request(
                                "GET",
                                "/_matrix/client/v3/login/sso/redirect?redirectUrl="
                                        + MatrixClient.segment("javascript:alert(1)"),
                                "",
                                ""))
                .as("unsafe redirect")
                .isEqualTo(400);
        assertThat(calls).as("no upstream requests").isEmpty();
    }

    /**
     * Harm guarded: a reserved alias is claimed through either creation endpoint or an encoded
     * path.
     */
    @Test
    void reservedAliasesAreCheckedOnBothCreationRoutes() throws Exception {
        final String token = Accounts.secret();
        assertThat(
                        request(
                                "POST",
                                "/_matrix/client/v3/createRoom",
                                token,
                                "{\"room_alias_name\":\"porelo-demo\"}"))
                .as("person is no project admin")
                .isEqualTo(403);
        assertThat(
                        request(
                                "PUT",
                                "/_matrix/client/v3/directory/room/%23%70orelo-demo%3Aexample.org",
                                token,
                                "{}"))
                .as("an encoded letter is refused before any check")
                .isEqualTo(400);
        identity = "@porelo.alice:example.org";
        final String projectToken = Accounts.secret();
        assertThat(
                        request(
                                "POST",
                                "/_matrix/client/v3/createRoom",
                                projectToken,
                                "{\"room_alias_name\":\"porelo-demo\"}"))
                .as("missing host file")
                .isEqualTo(403);
        Files.createDirectories(dir.resolve("projects"));
        Files.writeString(dir.resolve("projects/demo.admins"), "");
        assertThat(
                        request(
                                "POST",
                                "/_matrix/client/v3/createRoom",
                                projectToken,
                                "{\"room_alias_name\":\"porelo-demo\"}"))
                .as("empty host file")
                .isEqualTo(403);
        Files.writeString(dir.resolve("projects/demo.admins"), "alice\n");
        assertThat(
                        request(
                                "POST",
                                "/_matrix/client/r0/createRoom",
                                projectToken,
                                "{\"room_alias_name\":\"porelo-demo\"}"))
                .as("authorized project admin")
                .isEqualTo(200);
        assertThat(
                        request(
                                "PUT",
                                "/_matrix/client/v3/directory/room/%23porelo-demo%3Aexample.org",
                                projectToken,
                                "{}"))
                .as("authorized directory entry")
                .isEqualTo(200);
    }

    /** Harm guarded: a failed admin-issued login releases an account of another login. */
    @Test
    void tokenLoginChecksTheIssuedSessionsIdentity() throws Exception {
        final String token =
                new GateLoginTokens(
                                GateFiles.directory(env).resolve("login-tokens"),
                                java.time.Clock.systemUTC())
                        .issue("alice");
        identity = "@bob:example.org";
        final String credentials =
                MatrixClient.JSON
                        .createObjectNode()
                        .put("type", "m.login.token")
                        .put("token", token)
                        .toString();
        assertThat(request("POST", "/_matrix/client/v3/login", "", credentials))
                .as("foreign admin-issued session")
                .isEqualTo(403);
        assertThat(calls).as("new session was logged out").contains("/_matrix/client/v3/logout");
        assertThat(lastAnswer.contains(sessionToken)).as("session never leaves the gate").isFalse();
    }

    /**
     * Harm guarded: a malformed body skips alias inspection or leaves a pipelined request active.
     */
    @Test
    void malformedAliasBodiesAreBadRequestsAndClose() throws Exception {
        assertThat(request("POST", "/_matrix/client/v3/createRoom", Accounts.secret(), "{invalid"))
                .as("malformed JSON")
                .isEqualTo(400);
        assertThat(lastAnswer)
                .as("bad requests close the connection")
                .contains("Connection: close\r\n");
        assertThat(calls)
                .as("no room request was forwarded")
                .containsExactly("/_matrix/client/v3/account/whoami");
    }

    /**
     * Harm guarded: a normal client's password-first UIA request can never register its own
     * account.
     */
    @Test
    void passwordFirstRegistrationStillOffersTheDummyStage() throws Exception {
        assertThat(
                        request(
                                "POST",
                                "/_matrix/client/v3/register",
                                "",
                                "{\"username\":\"alice\",\"password\":\"fixture\"}"))
                .as("initial password-first UIA request")
                .isEqualTo(401);
        final JsonNode challenge =
                MatrixClient.JSON.readTree(
                        lastAnswer.substring(lastAnswer.indexOf("\r\n\r\n") + 4));
        assertThat(challenge.path("flows").get(0).path("stages").get(0).asText())
                .as("dummy stage is advertised")
                .isEqualTo("m.login.dummy");
        assertThat(calls)
                .as("challenge creates no account")
                .noneMatch(path -> path.startsWith("/_synapse/admin/v2/users/"));
    }

    /** Harm guarded: a trailing slash, routed by the homeserver as the bare path, skips a check. */
    @Test
    void aTrailingSlashIsTheSameEndpoint() throws Exception {
        final String token = Accounts.secret();
        assertThat(
                        request(
                                "POST",
                                "/_matrix/client/v3/createRoom/",
                                token,
                                "{\"room_alias_name\":\"porelo-demo\"}"))
                .as("reserved alias behind a slash")
                .isEqualTo(403);
        assertThat(
                        request(
                                "POST",
                                "/_matrix/client/v3/login/",
                                token,
                                "{\"type\":\"m.login.password\",\"user\":\"bob\",\"password\":\"x\"}"))
                .as("foreign login behind a slash")
                .isEqualTo(403);
    }

    /**
     * Harm guarded: a browser page rebound to loopback acts as the person through their forward.
     */
    @Test
    void aForeignHostIsRefused() throws Exception {
        assertThat(request("GET", "/_matrix/client/versions", "", "", "evil.example.org"))
                .isEqualTo(400);
        assertThat(calls).as("nothing passed").isEmpty();
    }

    /** Harm guarded: an answer with a body to a HEAD request desynchronizes the connection. */
    @Test
    void headIsRefused() throws Exception {
        assertThat(request("HEAD", "/_matrix/client/versions", "", "")).isEqualTo(400);
    }

    /**
     * Harm guarded: a second request on the connection is read into the homeserver's answer, copied
     * as it came for a connection that ends with it.
     */
    @Test
    void aPassedRequestEndsTheConnection() throws Exception {
        final String one = "GET /_matrix/client/versions HTTP/1.1\r\nHost: localhost\r\n\r\n";
        final var out = new ByteArrayOutputStream();
        gate.serve(new ByteArrayInputStream((one + one).getBytes(StandardCharsets.UTF_8)), out);
        assertThat(calls).as("only the first is passed").hasSize(1);
    }

    /** Harm guarded: an account with an empty password, which anybody signs in to. */
    @Test
    void anEmptyPasswordIsRefused() throws Exception {
        assertThat(
                        request(
                                "POST",
                                "/_matrix/client/v3/register",
                                "",
                                "{\"username\":\"alice\",\"password\":\"\",\"auth\":{\"type\":\"m.login.dummy\"}}"))
                .isEqualTo(400);
    }

    /** Harm guarded: Element, a browser client, cannot reach the homeserver it signs in to. */
    @Test
    void elementsPreflightIsAnsweredWithoutAToken() throws Exception {
        assertThat(
                        request(
                                "OPTIONS",
                                "/_matrix/client/v3/sync",
                                "",
                                "",
                                "walk.localhost:8448",
                                "Origin: vector://vector\r\n"
                                        + "Access-Control-Request-Method: GET\r\n"
                                        + "Access-Control-Request-Headers: authorization\r\n"))
                .isEqualTo(204);
        assertThat(lastAnswer)
                .contains("Access-Control-Allow-Origin: vector://vector\r\n")
                .contains("Access-Control-Allow-Headers: ")
                .containsIgnoringCase("authorization")
                .contains("Access-Control-Allow-Methods: ");
        assertThat(calls).as("nothing was sent upstream").isEmpty();
    }

    /** Harm guarded: a browser client refused the gate's own answers for want of CORS headers. */
    @Test
    void theGatesOwnAnswersAllowItsClientsOrigin() throws Exception {
        for (final String origin :
                List.of("vector://vector", "http://walk.localhost:8448", "http://127.0.0.1:8448")) {
            for (final String path :
                    List.of("/.well-known/matrix/client", "/_matrix/client/v3/login")) {
                assertThat(
                                request(
                                        "GET",
                                        path,
                                        "",
                                        "",
                                        "walk.localhost:8448",
                                        "Origin: " + origin + "\r\n"))
                        .as(path + " from " + origin)
                        .isEqualTo(200);
                assertThat(lastAnswer)
                        .as(path + " from " + origin)
                        .contains("Access-Control-Allow-Origin: " + origin + "\r\n");
            }
        }
        assertThat(
                        request(
                                "POST",
                                "/_matrix/client/v3/login",
                                "",
                                "{}",
                                "localhost",
                                "Origin: vector://vector\r\n"))
                .as("a refusal")
                .isEqualTo(400);
        assertThat(lastAnswer).contains("Access-Control-Allow-Origin: vector://vector\r\n");
        request("GET", "/.well-known/matrix/client", "", "");
        assertThat(lastAnswer).as("no origin, no CORS").doesNotContain("Access-Control-");
    }

    /** Harm guarded: a web page in the person's browser reads the gate's login answer. */
    @Test
    void aForeignOriginIsRefused() throws Exception {
        for (final String origin :
                List.of(
                        "https://evil.example",
                        "null",
                        "http://localhost.evil.example",
                        "vector://vector.evil.example",
                        "https://app.element.io")) {
            for (final String method : List.of("OPTIONS", "GET")) {
                assertThat(
                                request(
                                        method,
                                        "/_matrix/client/v3/login",
                                        "",
                                        "",
                                        "localhost",
                                        "Origin: " + origin + "\r\n"))
                        .as(method + " from " + origin)
                        .isEqualTo(403);
                assertThat(lastAnswer).as(origin).doesNotContain("Access-Control-");
            }
        }
        assertThat(calls).as("nothing was sent upstream").isEmpty();
    }

    /** Harm guarded: Element asks a person for a password nobody knows. */
    @Test
    void theLoginListOffersOnlyTheHostsLogin() throws Exception {
        assertThat(request("GET", "/_matrix/client/v3/login", "", "")).isEqualTo(200);
        final JsonNode flows =
                MatrixClient.JSON
                        .readTree(lastAnswer.substring(lastAnswer.indexOf("\r\n\r\n") + 4))
                        .path("flows");
        assertThat(flows.findValuesAsText("type")).containsExactly("m.login.sso", "m.login.token");
    }

    /** Harm guarded: Element refuses a homeserver whose sign-in discovery answers 401. */
    @Test
    void elementsSignInDiscoveryReachesTheHomeserver() throws Exception {
        for (final String path :
                List.of(
                        "/_matrix/client/v1/auth_metadata",
                        "/_matrix/client/unstable/org.matrix.msc2965/auth_issuer")) {
            assertThat(request("GET", path, "", "")).as(path).isEqualTo(200);
        }
        assertThat(calls).hasSize(2);
    }
}
