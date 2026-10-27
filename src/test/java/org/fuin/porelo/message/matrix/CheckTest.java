package org.fuin.porelo.message.matrix;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class CheckTest {

    private static final String TOKEN = "syt_test_token_that_must_never_be_printed";

    private FakeHomeserver homeserver;

    private final ByteArrayOutputStream errBytes = new ByteArrayOutputStream();

    @BeforeEach
    void setUp() throws IOException {
        homeserver = new FakeHomeserver();
    }

    @AfterEach
    void tearDown() {
        homeserver.close();
    }

    private int check(final Map<String, String> env, final String... args) {
        final String[] all = new String[args.length + 1];
        all[0] = "check";
        System.arraycopy(args, 0, all, 1, args.length);
        return Main.run(
                all,
                env,
                new PrintStream(OutputStream.nullOutputStream()),
                new PrintStream(errBytes, true, StandardCharsets.UTF_8));
    }

    private int check() {
        return check(Map.of(Config.HOMESERVER, homeserver.url(), Config.ACCESS_TOKEN, TOKEN));
    }

    private int checkWithInput(final Map<String, String> env, final String input) {
        return Main.run(
                new String[] {"check"},
                env,
                new java.io.ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)),
                new PrintStream(OutputStream.nullOutputStream()),
                new PrintStream(errBytes, true, StandardCharsets.UTF_8));
    }

    /** Harm guarded: a token only found where another process of the account reads it. */
    @Test
    @DisplayName("With the token on its input alone, the homeserver is asked with it")
    void takesItsTokenOnItsInput() {
        homeserver.answer(200, "{\"user_id\":\"@bob:matrix.test\"}");

        assertThat(
                        checkWithInput(
                                Map.of(),
                                "{\"secrets\":{\""
                                        + Config.HOMESERVER
                                        + "\":\""
                                        + homeserver.url()
                                        + "\",\""
                                        + Config.ACCESS_TOKEN
                                        + "\":\""
                                        + TOKEN
                                        + "\"}}"))
                .as(stderr())
                .isEqualTo(Exit.OK);
        assertThat(homeserver.requests())
                .singleElement()
                .satisfies(
                        request ->
                                assertThat(request.authorization()).isEqualTo("Bearer " + TOKEN));
    }

    /** Harm guarded: a stale token in the environment is sent beside the input's secrets. */
    @Test
    @DisplayName(
            "With secrets on its input, a token in its environment is not used: 2, nothing asked")
    void readsNoTokenFromTheEnvironmentBesideTheInput() {
        assertThat(
                        checkWithInput(
                                Map.of(Config.ACCESS_TOKEN, TOKEN),
                                "{\"secrets\":{\""
                                        + Config.HOMESERVER
                                        + "\":\""
                                        + homeserver.url()
                                        + "\"}}"))
                .isEqualTo(Check.NOT_USABLE);
        assertThat(stderr()).contains(Config.ACCESS_TOKEN + " is not set");
        assertThat(homeserver.requests()).isEmpty();
    }

    /** Harm guarded: Porelo reads an answer the contract does not know for check. */
    @Test
    @DisplayName("Input it cannot take is 2, as every reason check is not usable")
    void refusedInputIsNotUsable() {
        assertThat(checkWithInput(Map.of(), "{\"secrets\":{\"PATH\":\"/tmp\"}}"))
                .isEqualTo(Check.NOT_USABLE);
        assertThat(homeserver.requests()).isEmpty();
    }

    /** Harm guarded: development's switch, given the new way, is used without its warning. */
    @Test
    @DisplayName("TLS_VERIFY=off on the input warns as it does from the environment")
    void warnsOfVerifyOffOnTheInput() {
        checkWithInput(Map.of(), "{\"secrets\":{\"" + Config.TLS_VERIFY + "\":\"off\"}}");

        assertThat(stderr()).contains(Config.VERIFY_OFF_WARNING);
    }

    private String stderr() {
        return errBytes.toString(StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName(
            "A homeserver that knows the token is usable: 0, having only asked whose token it is")
    void usable() {
        homeserver.answer(200, "{\"user_id\":\"@bob:matrix.test\"}");

        assertThat(check()).as(stderr()).isEqualTo(Exit.OK);
        assertThat(homeserver.requests())
                .singleElement()
                .satisfies(
                        request -> {
                            assertThat(request.method()).isEqualTo("GET");
                            assertThat(request.rawPath())
                                    .isEqualTo("/_matrix/client/v3/account/whoami");
                        });
    }

    @ParameterizedTest(name = "status {0}")
    @CsvSource({
        "401, M_UNKNOWN_TOKEN",
        "403, M_FORBIDDEN",
        "500, M_UNKNOWN",
        "429, M_LIMIT_EXCEEDED"
    })
    @DisplayName("Whatever the homeserver refuses, it is 2, with the reason and never the token")
    void refused(final int status, final String errcode) {
        homeserver.answer(status, "{\"errcode\":\"" + errcode + "\",\"error\":\"no\"}");

        assertThat(check()).isEqualTo(Check.NOT_USABLE);
        assertThat(stderr()).contains(errcode).doesNotContain(TOKEN);
    }

    @Test
    @DisplayName("An unreachable homeserver is 2")
    void unreachable() throws IOException {
        final int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }

        assertThat(
                        check(
                                Map.of(
                                        Config.HOMESERVER,
                                        "http://127.0.0.1:" + closedPort,
                                        Config.ACCESS_TOKEN,
                                        TOKEN)))
                .isEqualTo(Check.NOT_USABLE);
        assertThat(stderr()).contains("not reachable");
    }

    @Test
    @DisplayName("Missing configuration is 2, naming what is missing")
    void missingConfiguration() {
        assertThat(check(Map.of(Config.HOMESERVER, homeserver.url()))).isEqualTo(Check.NOT_USABLE);
        assertThat(stderr()).contains(Config.ACCESS_TOKEN);
        assertThat(homeserver.requests()).isEmpty();
    }

    @Test
    @DisplayName("An answer that names nobody is 2")
    void answerWithoutUser() {
        homeserver.answer(200, "{}");

        assertThat(check()).isEqualTo(Check.NOT_USABLE);
    }
}
