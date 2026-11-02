package org.fuin.porelo.message.matrix;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class GateRulesTest {

    /** Harm guarded: a valid forwarded client at the highest TCP port cannot receive its login. */
    @Test
    void allowsTheHighestTcpPortForTheForwardedClient() {
        assertThat(
                        GateRules.redirectAllowed(
                                "http://walk.localhost:65535/callback", "walk.localhost:65535"))
                .as("65535 is a usable TCP port on the same forwarded authority")
                .isTrue();
    }

    /** Harm guarded: malformed Matrix identities are mistaken for accounts owned by a login. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("localparts")
    void extractsOnlyWellFormedLocalparts(String account, String expected) {
        assertThat(GateRules.localpart(account)).as("localpart of %s", account).isEqualTo(expected);
    }

    private static Stream<Arguments> localparts() {
        return Stream.of(
                Arguments.of("@a:s", "a"),
                Arguments.of("@:s", ""),
                Arguments.of("@a:", ""),
                Arguments.of("@a:s t", ""),
                Arguments.of("@a:s@t", ""));
    }

    /** Harm guarded: malformed or overlong accounts gain another login's authority. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("ownershipEdges")
    void ownershipEnforcesLoginAndLengthBoundaries(
            String reason, String account, String login, boolean expected) {
        assertThat(GateRules.belongs(account, login)).as(reason).isEqualTo(expected);
    }

    private static Stream<Arguments> ownershipEdges() {
        return Stream.of(
                Arguments.of(
                        "invalid login cannot own an exact matching account",
                        "Alice",
                        "Alice",
                        false),
                Arguments.of(
                        "160 character localpart is usable",
                        "x".repeat(154) + ".alice",
                        "alice",
                        true),
                Arguments.of(
                        "161 character localpart is refused",
                        "x".repeat(155) + ".alice",
                        "alice",
                        false));
    }

    /** Harm guarded: unsafe redirect parsing hands a login token to an unintended destination. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("redirectRefusals")
    void refusesRedirectsAtTheRemainingSafetyEdges(String reason, String url, String host) {
        assertThat(GateRules.redirectAllowed(url, host)).as(reason).isFalse();
    }

    private static Stream<Arguments> redirectRefusals() {
        final String host = "walk.localhost:8448";
        final String base = "http://" + host + "/";
        return Stream.of(
                Arguments.of("invalid URI", "http://[", host),
                Arguments.of("raw non-ASCII character", base + "é", host),
                Arguments.of("raw DEL character", base + "\u007f", host),
                Arguments.of("encoded carriage return", base + "%0d", host),
                Arguments.of("encoded line feed", base + "%0A", host),
                Arguments.of("existing login token on HTTP client", base + "?loginToken=old", host),
                Arguments.of("encoded login token key", base + "?%6coginToken=old", host),
                Arguments.of("ambiguous query", base + "?x=1&x=2", host),
                Arguments.of(
                        "port exceeds TCP range",
                        "http://walk.localhost:65536/",
                        "walk.localhost:65536"),
                Arguments.of("non-loopback HTTP authority", "http://example.org/", "example.org"));
    }

    /** Harm guarded: a person uses a different login's accounts or the administrator. */
    @Test
    void accountsBelongOnlyToTheirExactLogin() {
        for (final String account :
                List.of(
                        "alice",
                        "@alice:example.org",
                        "x.alice",
                        "porelo.alice",
                        "a_b-1.alice",
                        "porelo-relay-my.project.alice")) {
            assertThat(GateRules.belongs(account, "alice"))
                    .as("own localpart %s", account)
                    .isTrue();
        }
        for (final String account :
                List.of(
                        "bob",
                        "@x.bob:example.org",
                        ".alice",
                        "@.bob:example.org",
                        "porelo",
                        "@porelo:example.org",
                        "x..alice",
                        "X.alice",
                        "a b.alice",
                        "a\nalice",
                        "alice:",
                        "@alice",
                        "@alice:")) {
            assertThat(GateRules.belongs(account, "alice"))
                    .as("foreign or malformed account %s", account.replace('\n', ' '))
                    .isFalse();
        }
        assertThat(GateRules.belongs("bob", "x-bob"))
                .as("a suffix without a dot is another login")
                .isFalse();
    }

    /** Harm guarded: a locked, expelled, reserved or unrepresentable uid reaches the homeserver. */
    @Test
    void admissionRequiresAValidGroupMemberAndNoExpulsion() {
        final String group = "other:x:100:alice\nporelo-people:x:101:alice,bob\n";
        assertThat(GateRules.admitted("alice", group, false)).as("explicit membership").isTrue();
        assertThat(GateRules.admitted("alice", group, true)).as("expulsion wins").isFalse();
        assertThat(GateRules.admitted("alice", "other:x:100:alice\n", false))
                .as("a different group is no admission")
                .isFalse();
        for (final String login :
                List.of(
                        "mallory",
                        "porelo",
                        "porelo-alice",
                        "Alice",
                        "a_b",
                        "a.b",
                        "1bob",
                        "a".repeat(33),
                        "../alice")) {
            assertThat(GateRules.admitted(login, "porelo-people:x:101:" + login + "\n", false))
                    .as("invalid login or uid")
                    .isEqualTo(login.equals("mallory"));
        }
        assertThat(
                        GateRules.admitted(
                                "alice",
                                "porelo-people:x:101:alice\nporelo-people:x:102:bob\n",
                                false))
                .as("ambiguous group is refused")
                .isFalse();
    }

    /** Harm guarded: a client takes a project's reserved room alias. */
    @Test
    void noClientTakesAProjectsAlias() {
        assertThat(GateRules.aliasAllowed("ordinary")).as("ordinary aliases").isTrue();
        assertThat(GateRules.aliasAllowed("porelo")).as("no project's alias").isTrue();
        for (final String alias :
                List.of(
                        "porelo-demo",
                        "porelo-",
                        "porelo-../escape",
                        "porelo-Demo",
                        "porelo-a/b")) {
            assertThat(GateRules.aliasAllowed(alias)).as("reserved: %s", alias).isFalse();
        }
    }

    /**
     * Harm guarded: a web page the person opens sends their browser to the gate and has the
     * person's login token handed to a site of its own.
     */
    @Test
    void aLoginTokenGoesOnlyToElementDesktopOrTheForwardTheRequestCameIn() {
        final String forward = "walk.localhost:8448";
        for (final String url :
                List.of(
                        "http://walk.localhost:8448/path?x=y#fragment",
                        "http://walk.localhost:8448",
                        "element://vector/webapp/?element-desktop-ssoid=abc#/home")) {
            assertThat(GateRules.redirectAllowed(url, forward))
                    .as("the homeserver's own client %s", url)
                    .isTrue();
        }
        for (final String url :
                List.of(
                        "https://example.org/",
                        "https://example.org",
                        "element://evil.example/webapp/",
                        "element:/vector/webapp/",
                        "io.element.desktop://vector/webapp/",
                        "http://walk.localhost:9999/",
                        "http://other.localhost:8448/",
                        "http://127.0.0.1:8448/",
                        "http://walk.localhost:8448.example.org/",
                        "https://walk.localhost:8448/",
                        "http://walk.localhost:8448//evil.example/",
                        "element://vector",
                        "element://vector//evil.example/")) {
            assertThat(GateRules.redirectAllowed(url, forward))
                    .as("a client elsewhere %s", url)
                    .isFalse();
        }
        assertThat(GateRules.redirectAllowed("https://example.org/", "example.org"))
                .as("a Host the gate does not serve names no client of its own")
                .isFalse();
        assertThat(GateRules.redirectAllowed("http://.localhost/", ".localhost"))
                .as("an authority that names no host")
                .isFalse();
    }

    /** Harm guarded: a login token is sent to executable content or an ambiguous redirect. */
    @Test
    void redirectsRequireAnAbsoluteSafeUrl() {
        for (final String url :
                List.of(
                        "custom:callback?loginToken=injected",
                        "/relative",
                        "//walk.localhost:8448",
                        "javascript:alert(1)",
                        "JaVaScRiPt:alert(1)",
                        "data:text/plain,hello",
                        "http:/no-host",
                        "https://user@walk.localhost:8448",
                        "file:///tmp/client",
                        "https://walk.localhost:8448/%0d%0aX:y",
                        "https://walk.localhost:8448/?loginToken=old",
                        "element://vector/webapp/?loginToken=old",
                        "element://user@vector/webapp/")) {
            assertThat(GateRules.redirectAllowed(url, "walk.localhost:8448"))
                    .as("unsafe redirect %s", url)
                    .isFalse();
        }
    }

    /** Harm guarded: a caller reaches administrative or federation APIs through a path disguise. */
    @Test
    void onlyClientAndMediaApisAreForwarded() {
        assertThat(GateRules.clientPath("/_matrix/client/v3/sync")).as("client API").isTrue();
        assertThat(GateRules.clientPath("/_matrix/media/v3/upload")).as("media API").isTrue();
        for (final String path :
                List.of(
                        "/_synapse/admin/v1/users",
                        "/_tuwunel/admin",
                        "/_matrix/federation/v1/send",
                        "/_matrix/key/v2/server",
                        "/other",
                        "/_matrix/client/../../_synapse/admin")) {
            assertThat(GateRules.clientPath(path)).as("outside the client surface").isFalse();
        }
    }

    /** Harm guarded: a login token authorizes a different login or survives its deadline. */
    @Test
    void loginTokensAreBoundAndExpireAtTheirDeadline() {
        final Instant now = Instant.parse("2000-01-01T00:00:00Z");
        assertThat(GateRules.loginTokenValid("alice", "alice", now.plusSeconds(1), now))
                .as("live token")
                .isTrue();
        assertThat(GateRules.loginTokenValid("alice", "bob", now.plusSeconds(1), now))
                .as("different login")
                .isFalse();
        assertThat(GateRules.loginTokenValid("alice", "alice", now, now))
                .as("expiry is exclusive")
                .isFalse();
    }

    /** Harm guarded: a page elsewhere whose name now points at loopback uses a forwarded socket. */
    @Test
    void onlyALoopbackHostIsAClients() {
        for (final String host :
                List.of(
                        "localhost",
                        "localhost:8008",
                        "127.0.0.1:8008",
                        "[::1]:8008",
                        "h.localhost:8448")) {
            assertThat(GateRules.hostAllowed(host)).as("a client's: %s", host).isTrue();
        }
        for (final String host :
                List.of(
                        "",
                        "evil.example.org",
                        "evil.example.org:8008",
                        "localhost.example.org",
                        "localhost:8008:1",
                        "a b.localhost")) {
            assertThat(GateRules.hostAllowed(host)).as("no client's: %s", host).isFalse();
        }
    }
}
