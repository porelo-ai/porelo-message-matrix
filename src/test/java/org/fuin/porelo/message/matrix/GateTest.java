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
    private static final String ADMIN_DETAIL = "database at 192.0.2.7 refused";
    private static final String INTERNAL_ADDRESS = "https://internal.example.org";

    private int createStatus = 200;
    private int adminLoginStatus = 200;
    private int loginStatus = 200;
    private int availabilityStatus = 200;
    private boolean answeredNotAvailable;
    private boolean takenAtSecondAsk;
    private int asked;
    private int forwardStatus = 200;
    private String lastAnswer = "";

    /** As a real administrator's API words a failure: what the caller must not read. */
    private static GateResponse adminFailure(final int status) {
        return new GateResponse(
                status,
                MatrixClient.JSON
                        .createObjectNode()
                        .put("errcode", "M_UNKNOWN")
                        .put("error", ADMIN_DETAIL));
    }

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
                                if (path.contains("username_available")) {
                                    asked++;
                                    if (taken || takenAtSecondAsk && asked > 1) {
                                        return GateResponse.error(400, "M_USER_IN_USE");
                                    }
                                    if (availabilityStatus != 200) {
                                        return adminFailure(availabilityStatus);
                                    }
                                    return new GateResponse(
                                            200,
                                            MatrixClient.JSON
                                                    .createObjectNode()
                                                    .put("available", !answeredNotAvailable));
                                }
                                if (path.startsWith("/_synapse/admin/v2/users/")) {
                                    return createStatus == 200 || createStatus == 201
                                            ? new GateResponse(
                                                    createStatus,
                                                    MatrixClient.JSON.createObjectNode())
                                            : adminFailure(createStatus);
                                }
                                if (path.startsWith("/_synapse/")
                                        && path.endsWith("/login")
                                        && adminLoginStatus != 200) {
                                    return GateResponse.error(adminLoginStatus, "M_FORBIDDEN");
                                }
                                if (path.endsWith("/v3/login") && loginStatus != 200) {
                                    return GateResponse.error(loginStatus, "M_FORBIDDEN");
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
                                                    .put("device_id", "device")
                                                    .put("well_known", INTERNAL_ADDRESS));
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
                                new GateResponse(
                                                forwardStatus, MatrixClient.JSON.createObjectNode())
                                        .write(out, false);
                                return forwardStatus;
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
     * Harm guarded: a decoded space in a refused path makes the log line read as another status.
     */
    @Test
    void aDecodedSpaceInARefusedPathCannotShiftTheLogsColumns() throws Exception {
        assertThat(request("GET", "/_matrix/client/v3/sync%20200", "", ""))
                .as("past the parser, and refused for having no token")
                .isEqualTo(401);
        assertThat(log.toString(StandardCharsets.UTF_8))
                .as("the decoded space is masked, so 401 stays the only status")
                .isEqualTo("alice GET /_matrix/client/v3/sync?200 401\n");
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

    /**
     * Harm guarded: a page on another local port or name, or one that fetches instead of
     * navigating, receives or reads the person's login token.
     */
    @Test
    void ssoAnswersOnlyANavigationBackToTheForwardItCameIn() throws Exception {
        final String forward = "walk.localhost:8448";
        assertThat(sso("http://walk.localhost:8448/callback", forward, ""))
                .as("a page at the forward the request came in on")
                .isEqualTo(302);
        for (final String elsewhere :
                List.of(
                        "http://localhost/callback",
                        "http://walk.localhost:9999/callback",
                        "https://walk.localhost:8448/callback")) {
            assertThat(sso(elsewhere, forward, ""))
                    .as("a client elsewhere %s", elsewhere)
                    .isEqualTo(400);
        }
        for (final String origin :
                List.of("http://walk.localhost:9999", "http://walk.localhost:8448")) {
            assertThat(
                            sso(
                                    "http://walk.localhost:8448/callback",
                                    forward,
                                    "Origin: " + origin + "\r\n"))
                    .as("a fetch from %s, which could read where it was sent", origin)
                    .isEqualTo(400);
        }
        assertThat(lastAnswer).as("no token in a refusal").doesNotContain("loginToken");
    }

    private int sso(final String redirect, final String host, final String headers)
            throws Exception {
        return request(
                "GET",
                "/_matrix/client/v3/login/sso/redirect/porelo?redirectUrl="
                        + MatrixClient.segment(redirect),
                "",
                "",
                host,
                headers);
    }

    /** Harm guarded: password or token sign-in bypasses ownership when the homeserver disagrees. */
    @Test
    void ownSsoTokenIsConsumedAndReturnsOnlyThePersonsSession() throws Exception {
        assertThat(
                        request(
                                "GET",
                                "/_matrix/client/v3/login/sso/redirect/porelo?redirectUrl="
                                        + MatrixClient.segment("element://vector/webapp/?x=1#keep"),
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
                                "/_matrix/client/r0/createRoom",
                                projectToken,
                                "{\"room_alias_name\":\"porelo-demo\"}"))
                .as("a project admin's provisioning account makes no project's room")
                .isEqualTo(403);
        assertThat(
                        request(
                                "PUT",
                                "/_matrix/client/v3/directory/room/%23porelo-demo%3Aexample.org",
                                projectToken,
                                "{}"))
                .as("nor takes its alias")
                .isEqualTo(403);
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

    private static final String ALIAS_PATH = "/_matrix/client/v3/directory/room/";

    private void assertNothingOfTheAliasIsForwarded() {
        assertThat(calls)
                .as("nothing forwarded")
                .noneMatch(path -> path.contains("directory/room"));
    }

    /** Harm guarded: a person takes a project's reserved alias, and with it the project's room. */
    @Test
    void aPutOfAReservedAliasIsRefusedToEveryClient() throws Exception {
        final String path = ALIAS_PATH + "%23porelo-demo%3Aexample.org";
        assertThat(request("PUT", path, Accounts.secret(), "{}")).as("person").isEqualTo(403);
        assertNothingOfTheAliasIsForwarded();
        identity = "@porelo.alice:example.org";
        assertThat(request("PUT", path, Accounts.secret(), "{}"))
                .as("a project admin's provisioning account")
                .isEqualTo(403);
        assertNothingOfTheAliasIsForwarded();
    }

    /** Harm guarded: another form of the same path passes the alias check by. */
    @Test
    void anAliasNotInTheFormOfOneIsRefusedAndOnlyAPutIsChecked() throws Exception {
        final String token = Accounts.secret();
        for (final String alias : List.of("porelo-demo%3Aexample.org", "%23%3Aexample.org")) {
            assertThat(request("PUT", ALIAS_PATH + alias, token, "{}")).as(alias).isEqualTo(403);
        }
        assertNothingOfTheAliasIsForwarded();
        assertThat(request("PUT", ALIAS_PATH + "%23a%3Aexample.org", token, "{}"))
                .as("the shortest alias of nobody's")
                .isEqualTo(200);
        assertThat(request("GET", ALIAS_PATH + "%23porelo-demo%3Aexample.org", token, ""))
                .as("reading a reserved alias changes nothing")
                .isEqualTo(200);
    }

    /** Harm guarded: a reserved alias under a name no project could have lets a client by. */
    @Test
    void aReservedAliasIsRefusedWhateverNameFollowsIt() throws Exception {
        identity = "@porelo.alice:example.org";
        final String create = "/_matrix/client/v3/createRoom";
        assertThat(
                        request(
                                "POST",
                                create,
                                Accounts.secret(),
                                "{\"room_alias_name\":\"porelo-A B\"}"))
                .as("no project's name")
                .isEqualTo(403);
        assertThat(calls).as("nothing forwarded").noneMatch(path -> path.equals(create));
    }

    /** Harm guarded: a second name in a login, which the homeserver may take for the account. */
    @Test
    void aLoginNamingAnyoneElseOrAnotherIdentifierIsRefused() throws Exception {
        final String login = "/_matrix/client/v3/login";
        assertThat(
                        request(
                                "POST",
                                login,
                                "",
                                "{\"type\":\"m.login.password\",\"user\":\"bob\",\"password\":\"x\","
                                        + "\"identifier\":{\"type\":\"m.id.user\",\"user\":\"alice\"}}"))
                .as("the caller in the identifier, another as the user")
                .isEqualTo(403);
        assertThat(
                        request(
                                "POST",
                                login,
                                "",
                                "{\"type\":\"m.login.password\",\"password\":\"x\","
                                        + "\"identifier\":{\"type\":\"m.id.phone\",\"user\":\"alice\"}}"))
                .as("an identifier of another type")
                .isEqualTo(403);
        assertThat(request("POST", login, "", "no json")).as("no JSON").isEqualTo(400);
        assertThat(calls).as("none reaches the homeserver").isEmpty();
        assertThat(
                        request(
                                "POST",
                                login,
                                "",
                                "{\"type\":\"m.login.password\",\"user\":\"alice\",\"password\":\"x\","
                                        + "\"identifier\":{\"type\":\"m.id.user\",\"user\":\"alice\"}}"))
                .as("the caller, named twice")
                .isEqualTo(200);
    }

    /** Harm guarded: a refused sign-in answered as if the gate had a session to give. */
    @Test
    void aHomeserversRefusedLoginIsAnsweredWithNoSession() throws Exception {
        loginStatus = 403;
        assertThat(
                        request(
                                "POST",
                                "/_matrix/client/v3/login",
                                "",
                                "{\"type\":\"m.login.password\",\"user\":\"alice\",\"password\":\"x\"}"))
                .as("wrong password")
                .isEqualTo(403);
        assertThat(lastAnswer).doesNotContain("access_token");
        assertThat(calls).as("nothing to log out").containsExactly("/_matrix/client/v3/login");
        final String token =
                new GateLoginTokens(
                                GateFiles.directory(env).resolve("login-tokens"),
                                java.time.Clock.systemUTC())
                        .issue("alice");
        adminLoginStatus = 403;
        assertThat(
                        request(
                                "POST",
                                "/_matrix/client/v3/login",
                                "",
                                "{\"type\":\"m.login.token\",\"token\":\"" + token + "\"}"))
                .as("the administrator's login refused")
                .isEqualTo(403);
        assertThat(lastAnswer).doesNotContain("access_token");
    }

    /** Harm guarded: a group file nobody can read admits whoever asks. */
    @Test
    void aGroupFileThatCannotBeReadAdmitsNobody() throws Exception {
        Files.delete(group);
        assertThat(request("GET", "/_matrix/client/versions", "", "")).isEqualTo(403);
        assertThat(calls).as("nothing passed").isEmpty();
    }

    private String registration(final String username) {
        return MatrixClient.JSON
                .createObjectNode()
                .put("username", username)
                .put("password", "fixture")
                .set("auth", MatrixClient.JSON.createObjectNode().put("type", "m.login.dummy"))
                .toString();
    }

    private int register(final String body) throws Exception {
        return request("POST", "/_matrix/client/v3/register", "", body);
    }

    private void assertNoAccountWasMade() {
        assertThat(calls)
                .as("no account made")
                .noneMatch(path -> path.startsWith("/_synapse/admin/v2/users/"));
    }

    /** Harm guarded: a registration on another server's name, or in a form the gate cannot read. */
    @Test
    void aRegistrationInAnotherNameOrFormIsRefused() throws Exception {
        assertThat(register(registration("@alice:other.example.org")))
                .as("the caller's name on another server")
                .isEqualTo(400);
        assertThat(lastAnswer).contains("M_INVALID_USERNAME");
        assertThat(calls)
                .as("not even asked about")
                .noneMatch(p -> p.contains("username_available"));
        assertThat(register("no json")).as("no JSON").isEqualTo(400);
        assertThat(lastAnswer).contains("M_BAD_JSON");
        assertThat(
                        register(
                                "{\"username\":\"alice\",\"password\":\"x\","
                                        + "\"auth\":{\"type\":\"m.login.other\"}}"))
                .as("another stage than the one offered")
                .isEqualTo(400);
        assertThat(lastAnswer).contains("M_BAD_JSON");
        assertThat(register("{\"username\":\"alice\",\"password\":1}"))
                .as("a password that is no text")
                .isEqualTo(400);
        assertThat(lastAnswer).contains("M_BAD_JSON");
        assertNoAccountWasMade();
    }

    /** Harm guarded: a name someone holds is reset instead of refused, also between two asks. */
    @Test
    void aTakenNameIsRefusedHoweverTheHomeserverSaysSo() throws Exception {
        answeredNotAvailable = true;
        assertThat(register(registration("alice"))).as("answered not available").isEqualTo(400);
        assertThat(lastAnswer).contains("M_USER_IN_USE");
        assertNoAccountWasMade();
        answeredNotAvailable = false;
        takenAtSecondAsk = true;
        asked = 0;
        assertThat(register(registration("alice"))).as("taken since the first ask").isEqualTo(400);
        assertThat(lastAnswer).contains("M_USER_IN_USE");
        assertNoAccountWasMade();
    }

    /** Harm guarded: what the administrator's API answers reaches the caller. */
    @Test
    void aFailedCreationIsAnsweredOnlyAsAFailureAndNoLoginFollows() throws Exception {
        createStatus = 500;
        assertThat(register(registration("alice"))).as("creation failed").isEqualTo(502);
        assertThat(lastAnswer).as("what creation said").doesNotContain(ADMIN_DETAIL);
        assertThat(calls).as("no login").noneMatch(path -> path.endsWith("/v3/login"));
        availabilityStatus = 500;
        calls.clear();
        assertThat(register(registration("alice"))).as("asking failed").isEqualTo(502);
        assertThat(lastAnswer).as("what asking said").doesNotContain(ADMIN_DETAIL);
        assertNoAccountWasMade();
    }

    /** Harm guarded: the homeserver's own answer to a login reaches the caller unfiltered. */
    @Test
    void aRegistrationAnswersOnlyTheSessionTheGateBuilt() throws Exception {
        for (final int created : List.of(200, 201)) {
            createStatus = created;
            assertThat(register(registration("@alice:example.org")))
                    .as("created with %d, the full name of this server", created)
                    .isEqualTo(200);
            assertThat(lastAnswer).contains("access_token").doesNotContain(INTERNAL_ADDRESS);
        }
    }

    /** Harm guarded: a sign-in made for an account the homeserver did not confirm. */
    @Test
    void aRegistrationWhoseLoginIsRefusedHasNoSession() throws Exception {
        loginStatus = 403;
        assertThat(register(registration("alice"))).isEqualTo(403);
        assertThat(lastAnswer).doesNotContain("access_token");
    }

    /** Harm guarded: a sign-in overwrites an existing account, or goes on after a failure. */
    @Test
    void aSignInCreatesOnlyAnAbsentAccountAndStopsAtAFailure() throws Exception {
        final String redirect = "element://vector/login";
        taken = true;
        assertThat(sso(redirect, "localhost", "")).as("existing account").isEqualTo(302);
        assertThat(lastAnswer).contains("loginToken=");
        assertNoAccountWasMade();
        taken = false;
        availabilityStatus = 500;
        assertThat(sso(redirect, "localhost", "")).as("asking failed").isEqualTo(502);
        assertThat(lastAnswer).doesNotContain("loginToken");
        assertNoAccountWasMade();
        availabilityStatus = 200;
        createStatus = 500;
        assertThat(sso(redirect, "localhost", "")).as("creation failed").isEqualTo(502);
        assertThat(lastAnswer).doesNotContain("loginToken");
        createStatus = 201;
        assertThat(sso(redirect, "localhost", "")).as("created").isEqualTo(302);
        assertThat(lastAnswer).contains("loginToken=");
    }

    /** Harm guarded: a request after a refusal reads as part of the one before it. */
    @Test
    void aBadRequestsAnswerEndsTheConnection() throws Exception {
        final String body = "{\"username\":\"bob\"}";
        final String refused =
                "POST /_matrix/client/v3/register HTTP/1.1\r\nHost: localhost\r\n"
                        + "Content-Length: "
                        + body.length()
                        + "\r\n\r\n"
                        + body;
        final String next = "GET /_matrix/client/versions HTTP/1.1\r\nHost: localhost\r\n\r\n";
        final var out = new ByteArrayOutputStream();
        gate.serve(
                new ByteArrayInputStream((refused + next).getBytes(StandardCharsets.UTF_8)), out);
        assertThat(out.toString(StandardCharsets.UTF_8))
                .as("the answer says the connection ends")
                .contains("Connection: close");
        assertThat(calls).as("the next request is not read").isEmpty();
    }

    /** Harm guarded: a failure nobody sees, as the log is the only trace of a refusal. */
    @Test
    void everyRefusalAndFailureIsLoggedAndNothingElse() throws Exception {
        assertThat(request("GET", "/_matrix/client/versions", "", "")).isEqualTo(200);
        assertThat(log.toString(StandardCharsets.UTF_8)).as("a success is not logged").isEmpty();
        forwardStatus = 400;
        assertThat(request("GET", "/_matrix/client/versions", "", "")).isEqualTo(400);
        assertThat(log.toString(StandardCharsets.UTF_8))
                .as("a homeserver's refusal")
                .isEqualTo("alice GET /_matrix/client/versions 400\n");
        log.reset();
        assertThat(register("{\"username\":\"bob\"}")).isEqualTo(400);
        assertThat(log.toString(StandardCharsets.UTF_8))
                .as("the gate's own refusal")
                .isEqualTo("alice POST /_matrix/client/v3/register 400\n");
        log.reset();
        final var out = new ByteArrayOutputStream();
        gate.serve(
                new ByteArrayInputStream("no request\r\n\r\n".getBytes(StandardCharsets.UTF_8)),
                out);
        assertThat(log.toString(StandardCharsets.UTF_8))
                .as("a request that cannot be read")
                .isEqualTo("alice - - 400\n");
        log.reset();
        GateFiles.write(GateFiles.directory(env).resolve("admin-token"), Accounts.secret(), true);
        assertThat(register(registration("alice"))).as("a failure inside the gate").isEqualTo(502);
        assertThat(log.toString(StandardCharsets.UTF_8))
                .isEqualTo("alice POST /_matrix/client/v3/register 502\n");
    }

    /** Harm guarded: a connection ends unasked, or stays open against what the client asked. */
    @Test
    void anAnswerByTheGateEndsTheConnectionOnlyWhenAsked() throws Exception {
        final String body = "{\"username\":\"alice\"}";
        final String request =
                "POST /_matrix/client/v3/register HTTP/1.1\r\nHost: localhost\r\n%s"
                        + "Content-Length: "
                        + body.length()
                        + "\r\n\r\n"
                        + body;
        final var kept = new ByteArrayOutputStream();
        gate.serve(
                new ByteArrayInputStream(
                        (request.formatted("") + request.formatted(""))
                                .getBytes(StandardCharsets.UTF_8)),
                kept);
        assertThat(kept.toString(StandardCharsets.UTF_8))
                .as("both are answered, on a connection left open")
                .doesNotContain("Connection: close")
                .containsSubsequence("HTTP/1.1 401", "HTTP/1.1 401");
        final var ended = new ByteArrayOutputStream();
        final String closing = request.formatted("Connection: close\r\n");
        gate.serve(
                new ByteArrayInputStream(
                        (closing + request.formatted("")).getBytes(StandardCharsets.UTF_8)),
                ended);
        assertThat(ended.toString(StandardCharsets.UTF_8))
                .as("only the first is answered")
                .contains("Connection: close")
                .containsOnlyOnce("HTTP/1.1 401");
    }
}
