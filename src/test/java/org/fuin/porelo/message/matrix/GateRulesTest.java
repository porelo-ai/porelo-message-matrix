package org.fuin.porelo.message.matrix;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class GateRulesTest {

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

    /** Harm guarded: another account takes a project's reserved room alias. */
    @Test
    void projectAliasesRequireTheExactAccountAndHostAuthorization() {
        assertThat(GateRules.aliasAllowed("ordinary", "@alice:example.org", "alice", List.of()))
                .as("ordinary aliases")
                .isTrue();
        assertThat(
                        GateRules.aliasAllowed(
                                "porelo-demo",
                                "@porelo.alice:example.org",
                                "alice",
                                List.of("alice")))
                .as("the project's named administrator")
                .isTrue();
        for (final String id :
                List.of(
                        "@alice:example.org",
                        "@task.alice:example.org",
                        "@porelo.bob:example.org",
                        "@porelo:example.org")) {
            assertThat(GateRules.aliasAllowed("porelo-demo", id, "alice", List.of("alice")))
                    .as("reserved alias owner")
                    .isFalse();
        }
        assertThat(
                        GateRules.aliasAllowed(
                                "porelo-demo", "@porelo.alice:example.org", "alice", List.of()))
                .as("missing or empty admin file grants nothing")
                .isFalse();
        for (final String alias :
                List.of("porelo-", "porelo-../escape", "porelo-Demo", "porelo-a/b")) {
            assertThat(
                            GateRules.aliasAllowed(
                                    alias, "@porelo.alice:example.org", "alice", List.of("alice")))
                    .as("project path cannot escape")
                    .isFalse();
        }
    }

    /** Harm guarded: a login token is sent to executable content or an ambiguous redirect. */
    @Test
    void redirectsRequireAnAbsoluteSafeUrl() {
        for (final String url :
                List.of(
                        "http://localhost:8080/path?x=y#fragment",
                        "https://example.org",
                        "element://vector/webapp/")) {
            assertThat(GateRules.redirectAllowed(url)).as("supported redirect").isTrue();
        }
        for (final String url :
                List.of(
                        "custom:callback?loginToken=injected",
                        "/relative",
                        "//example.org",
                        "javascript:alert(1)",
                        "JaVaScRiPt:alert(1)",
                        "data:text/plain,hello",
                        "http:/no-host",
                        "https://user@example.org",
                        "file:///tmp/client",
                        "https://example.org/%0d%0aX:y",
                        "https://example.org/?loginToken=old")) {
            assertThat(GateRules.redirectAllowed(url)).as("unsafe redirect").isFalse();
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
