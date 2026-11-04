package org.fuin.porelo.message.matrix;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AccountsTest {

    /** Harm guarded: a gate's completed registration is refused because it needs no token stage. */
    @Test
    void registrationThroughTheGateNeedsNoToken() throws Exception {
        try (FakeHomeserver homeserver = new FakeHomeserver()) {
            homeserver.answer(
                    200,
                    "{\"user_id\":\"@porelo.bob:example.org\",\"access_token\":\"new-session\"}");
            final MatrixClient client =
                    new MatrixClient(new Config(URI.create(homeserver.url()), "", null, true));
            final Accounts.Session account =
                    Accounts.register(client, "porelo.bob", "derived-password", "");
            assertThat(account.userId())
                    .as("the gate's account is accepted")
                    .isEqualTo("@porelo.bob:example.org");
            assertThat(homeserver.requests())
                    .as("the gate handled registration in one request")
                    .hasSize(1);
            assertThat(
                            MatrixClient.JSON
                                    .readTree(homeserver.requests().getFirst().body())
                                    .has("auth"))
                    .as("the client sends no registration token to the gate")
                    .isFalse();
        }
    }

    /** Harm guarded: the gate's dummy stage is answered with an empty token it refuses. */
    @Test
    void registrationThroughTheGateAnswersItsDummyStage() throws Exception {
        try (FakeHomeserver homeserver = new FakeHomeserver()) {
            homeserver.answer(
                    401,
                    "{\"flows\":[{\"stages\":[\"m.login.dummy\"]}],\"session\":\"porelo-gate\"}");
            homeserver.answer(
                    200,
                    "{\"user_id\":\"@porelo.bob:example.org\",\"access_token\":\"new-session\"}");
            final MatrixClient client =
                    new MatrixClient(new Config(URI.create(homeserver.url()), "", null, true));
            Accounts.register(client, "porelo.bob", "derived-password", "");
            final var auth =
                    MatrixClient.JSON.readTree(homeserver.requests().getLast().body()).path("auth");
            assertThat(auth.path("type").asText()).as("the dummy stage").isEqualTo("m.login.dummy");
            assertThat(auth.has("token")).as("no token is sent to the gate").isFalse();
        }
    }

    /**
     * Harm guarded: a homeserver that registers only with a token, reached over a socket, is taken
     * for a gate, and the person learns nothing of what is missing.
     */
    @Test
    void aSocketsHomeserverAskingATokenNoneWasLentForStopsNamingIt() throws Exception {
        try (FakeHomeserver homeserver = new FakeHomeserver()) {
            homeserver.answer(
                    401,
                    "{\"flows\":[{\"stages\":[\"m.login.registration_token\"]}],"
                            + "\"session\":\"challenge\"}");
            final MatrixClient client =
                    new MatrixClient(new Config(URI.create(homeserver.url()), "", null, true));
            assertThatThrownBy(() -> Accounts.register(client, "porelo.bob", "password", ""))
                    .isInstanceOfSatisfying(
                            Failure.class,
                            failure ->
                                    assertThat(failure.exitCode())
                                            .as("a setting, not a refusal to retry")
                                            .isEqualTo(Exit.CONFIG))
                    .hasMessageContaining(Config.REGISTRATION_TOKEN);
            assertThat(homeserver.requests())
                    .as("no second request guesses a stage it was not offered")
                    .hasSize(1);
        }
    }

    /** Harm guarded: a token lent for another homeserver goes to a gate that never asked for it. */
    @Test
    void aTokenStaysHomeWhereTheHomeserverOffersTheDummyStage() throws Exception {
        try (FakeHomeserver homeserver = new FakeHomeserver()) {
            homeserver.answer(
                    401,
                    "{\"flows\":[{\"stages\":[\"m.login.dummy\"]}],\"session\":\"porelo-gate\"}");
            homeserver.answer(
                    200,
                    "{\"user_id\":\"@porelo.bob:example.org\",\"access_token\":\"new-session\"}");
            final MatrixClient client =
                    new MatrixClient(new Config(URI.create(homeserver.url()), "", null, true));
            Accounts.register(client, "porelo.bob", "derived-password", "a-lent-token");
            assertThat(homeserver.requests().getLast().body())
                    .as("the token is not sent")
                    .doesNotContain("a-lent-token");
        }
    }

    /**
     * Harm guarded: a stage that must come after the token reads as an offer of the dummy stage
     * alone, so a homeserver that needs the token is answered without it.
     */
    @Test
    void aDummyStageAfterTheTokenDoesNotReplaceTheToken() throws Exception {
        try (FakeHomeserver homeserver = new FakeHomeserver()) {
            homeserver.answer(
                    401,
                    "{\"flows\":[{\"stages\":[\"m.login.registration_token\",\"m.login.dummy\"]}],"
                            + "\"session\":\"s\"}");
            homeserver.answer(
                    200,
                    "{\"user_id\":\"@porelo.bob:example.org\",\"access_token\":\"new-session\"}");
            final MatrixClient client =
                    new MatrixClient(new Config(URI.create(homeserver.url()), "", null, true));
            Accounts.register(client, "porelo.bob", "derived-password", "a-lent-token");
            assertThat(homeserver.requests().getLast().body())
                    .as("the token answers the first stage")
                    .contains("a-lent-token");
        }
    }

    /** Harm guarded: a token goes to a homeserver whose challenge never asked for one. */
    @Test
    void aTokenStaysHomeWhereTheChallengeDoesNotAskForIt() throws Exception {
        try (FakeHomeserver homeserver = new FakeHomeserver()) {
            homeserver.answer(
                    401, "{\"flows\":[{\"stages\":[\"m.login.sso\"]}],\"session\":\"s\"}");
            final MatrixClient client =
                    new MatrixClient(new Config(URI.create(homeserver.url()), "", null, true));
            assertThatThrownBy(
                            () ->
                                    Accounts.register(
                                            client, "porelo.bob", "password", "a-lent-token"))
                    .isInstanceOf(Failure.class);
            assertThat(homeserver.requests())
                    .as("the token never left")
                    .hasSize(1)
                    .noneMatch(request -> request.body().contains("a-lent-token"));
        }
    }

    /**
     * Harm guarded: the central server's token challenge is bypassed after registration changes.
     */
    @Test
    void registrationElsewhereStillCompletesTheTokenStage() throws Exception {
        try (FakeHomeserver homeserver = new FakeHomeserver()) {
            homeserver.answer(
                    401,
                    "{\"flows\":[{\"stages\":[\"m.login.registration_token\"]}],"
                            + "\"session\":\"challenge\"}");
            homeserver.answer(
                    200,
                    "{\"user_id\":\"@porelo.bob:example.org\",\"access_token\":\"new-session\"}");
            final MatrixClient client =
                    new MatrixClient(new Config(URI.create(homeserver.url()), "", null, true));
            Accounts.register(client, "porelo.bob", "derived-password", "registration-token");
            assertThat(homeserver.requests())
                    .as("the server's token challenge is answered")
                    .hasSize(2);
            final var auth =
                    MatrixClient.JSON.readTree(homeserver.requests().getLast().body()).path("auth");
            assertThat(auth.path("type").asText())
                    .as("the token stage is preserved")
                    .isEqualTo("m.login.registration_token");
            assertThat(auth.path("token").asText())
                    .as("the configured token is used")
                    .isEqualTo("registration-token");
        }
    }

    @Test
    @DisplayName(
            "A password is letters and digits only, so the admin room's command never reads one as an option")
    void passwordIsLettersAndDigits() {
        final Set<String> seen = new HashSet<>();
        for (int i = 0; i < 10_000; i++) {
            final String password = Accounts.password();
            assertThat(password).hasSize(32).matches("[A-Za-z0-9]+");
            seen.add(password);
        }
        assertThat(seen).as("no password twice").hasSize(10_000);
    }

    /**
     * Harm guarded: a machine on a central homeserver derives its accounts' passwords from its
     * secret and the localpart; a change to the derivation would lock the machine out of every
     * account it already registered.
     */
    @Test
    @DisplayName(
            "A central account's password is the hex HMAC-SHA256 of the machine's secret over the localpart")
    void derivesPasswordFromMachineSecretAndLocalpart() {
        assertThat(Accounts.password("Jefe", "what do ya want for nothing?"))
                .as("RFC 4231 test case 2")
                .isEqualTo("5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843");
        assertThat(Accounts.password("machine-secret-alpha", "porelo.alpha"))
                .as("a second machine's secret gives a different password for the same localpart")
                .isNotEqualTo(Accounts.password("machine-secret-beta", "porelo.alpha"));
        assertThat(Accounts.password("machine-secret-alpha", "porelo.alpha"))
                .as("each account on the machine gets its own password")
                .isNotEqualTo(Accounts.password("machine-secret-alpha", "porelo-relay-demo.alpha"));
    }
}
