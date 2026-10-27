package org.fuin.porelo.message.matrix;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class InputTest {

    private static final String TOKEN = "syt_the_token_on_the_input";

    private static InputStream in(final String text) {
        return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
    }

    private static String settings(final Input input) throws IOException {
        return new String(input.settings().readAllBytes(), StandardCharsets.UTF_8);
    }

    /** Harm guarded: a Porelo of the old way stops working the day the transport is updated. */
    @Test
    @DisplayName("The bare settings and the secrets in the environment pass as they came")
    void theOldWayPassesUnchanged() throws Exception {
        final Map<String, String> env = Map.of(Config.ACCESS_TOKEN, TOKEN, "HOME", "/home/a");

        final Input input = Input.resolve(in("{\"room\":\"#a:b\"}"), env);

        assertThat(input.env()).as("the environment as it came").isEqualTo(env);
        assertThat(settings(input)).as("the bare settings").isEqualTo("{\"room\":\"#a:b\"}");
    }

    /** Harm guarded: a token the transport needs is only found where another process reads it. */
    @Test
    @DisplayName(
            "With the secrets on the input, the verb gets them as its environment and the settings"
                    + " beside them as its settings")
    void theNewWayHandsSecretsAndSettings() throws Exception {
        final Input input =
                Input.resolve(
                        in(
                                "{\"settings\":{\"room\":\"#a:b\"},\"secrets\":{\""
                                        + Config.ACCESS_TOKEN
                                        + "\":\""
                                        + TOKEN
                                        + "\"}}"),
                        Map.of("HOME", "/home/a"));

        assertThat(input.env())
                .containsExactlyInAnyOrderEntriesOf(
                        Map.of(Config.ACCESS_TOKEN, TOKEN, "HOME", "/home/a"));
        assertThat(MatrixClient.JSON.readTree(settings(input)))
                .isEqualTo(MatrixClient.JSON.readTree("{\"room\":\"#a:b\"}"));
    }

    /** Harm guarded: a stale token in the environment is used beside the input's, by accident. */
    @Test
    @DisplayName(
            "With secrets on the input, no PORELO_MATRIX_ key of the environment is read, not even"
                    + " one the input lacks")
    void theNewWayReadsNoSecretFromTheEnvironment() throws Exception {
        final Input input =
                Input.resolve(
                        in("{\"secrets\":{\"" + Config.HOMESERVER + "\":\"http://127.0.0.1:1\"}}"),
                        Map.of(
                                Config.ACCESS_TOKEN,
                                "syt_stale",
                                Config.ADMIN_TOKEN,
                                "syt_admin",
                                "HOME",
                                "/home/a"));

        assertThat(input.env())
                .containsExactlyInAnyOrderEntriesOf(
                        Map.of(Config.HOMESERVER, "http://127.0.0.1:1", "HOME", "/home/a"));
    }

    /** Harm guarded: a lifecycle verb run with no settings from a new Porelo fails or guesses. */
    @Test
    @DisplayName("The new way without settings gives the verb empty settings")
    void theNewWayWithoutSettingsGivesEmptySettings() throws Exception {
        final Input input =
                Input.resolve(
                        in("{\"secrets\":{\"" + Config.ACCESS_TOKEN + "\":\"" + TOKEN + "\"}}"),
                        Map.of());

        assertThat(settings(input)).isEqualTo("{}");
    }

    /** Harm guarded: a verb that takes no settings reads them as something else. */
    @Test
    @DisplayName("Nothing on the input leaves the environment as it is")
    void anEmptyInputIsTheOldWay() throws Exception {
        final Map<String, String> env = Map.of(Config.ACCESS_TOKEN, TOKEN);

        final Input input = Input.resolve(in(""), env);

        assertThat(input.env()).isEqualTo(env);
        assertThat(settings(input)).isEmpty();
    }

    /** Harm guarded: the input sets what decides which programs and files the transport uses. */
    @ParameterizedTest
    @ValueSource(strings = {"PATH", "HOME", "XDG_STATE_HOME", "LD_PRELOAD", "porelo_matrix_x"})
    @DisplayName("A secret whose key is not a PORELO_MATRIX_ one is refused with 78")
    void refusesAKeyThatIsNoTransportsSecret(final String key) {
        assertThatThrownBy(
                        () ->
                                Input.resolve(
                                        in("{\"secrets\":{\"" + key + "\":\"/tmp/x\"}}"), Map.of()))
                .isInstanceOf(Failure.class)
                .extracting(failure -> ((Failure) failure).exitCode())
                .isEqualTo(Exit.CONFIG);
    }

    /** Harm guarded: a value of another shape turns into a token the homeserver never gave. */
    @ParameterizedTest
    @ValueSource(
            strings = {
                "{\"secrets\":{\"PORELO_MATRIX_ACCESS_TOKEN\":1}}",
                "{\"secrets\":{\"PORELO_MATRIX_ACCESS_TOKEN\":null}}",
                "{\"secrets\":{\"PORELO_MATRIX_ACCESS_TOKEN\":[\"a\"]}}",
                "{\"secrets\":[]}",
                "{\"secrets\":\"a\"}",
                "{\"settings\":[]}",
                "{\"secrets\":{\"PORELO_MATRIX_ACCESS_TOKEN\":\"a\","
                        + "\"PORELO_MATRIX_ACCESS_TOKEN\":\"b\"}}",
                "{\"secrets\":{},\"secrets\":{}}",
                "{\"settings\":{},\"secrets\":{},\"extra\":1}",
                "{\"settings\":{},\"secret\":{}}",
                "{\"settings\":{},\"settings\":{}}",
                "{\"settings\":{}} trailing",
                "{\"settings\":{\"tls_verify\":\"on\",\"tls_verify\":\"off\"}}"
            })
    @DisplayName("Secrets or settings of another shape, or a key twice, are refused with 78")
    void refusesAnotherShape(final String text) {
        assertThatThrownBy(() -> Input.resolve(in(text), Map.of()))
                .isInstanceOf(Failure.class)
                .extracting(failure -> ((Failure) failure).exitCode())
                .isEqualTo(Exit.CONFIG);
    }

    /** Harm guarded: a Porelo of the old way that hands the settings to every verb is refused. */
    @ParameterizedTest
    @ValueSource(strings = {"{\"room\":\"#a:b\"}", "not json", "[]", "{\"room\":1,\"room\":2}"})
    @DisplayName("Input that is not the new way's object passes as it came, the environment too")
    void anythingElseIsTheOldWay(final String text) throws Exception {
        final Map<String, String> env = Map.of(Config.ACCESS_TOKEN, TOKEN);

        final Input input = Input.resolve(in(text), env);

        assertThat(input.env()).isEqualTo(env);
        assertThat(settings(input)).isEqualTo(text);
    }

    /** Harm guarded: a stream that never ends fills the transport's memory. */
    @Test
    @DisplayName("Input beyond 1 MiB is refused with 78")
    void refusesInputBeyondItsBound() {
        final byte[] big = new byte[Input.LIMIT + 1];
        java.util.Arrays.fill(big, (byte) ' ');

        assertThatThrownBy(() -> Input.resolve(new ByteArrayInputStream(big), Map.of()))
                .isInstanceOf(Failure.class)
                .extracting(failure -> ((Failure) failure).exitCode())
                .isEqualTo(Exit.CONFIG);
    }

    /** Harm guarded: the parser's message quotes a secret into Porelo's log. */
    @ParameterizedTest
    @ValueSource(
            strings = {
                "{\"settings\":{},\"secrets\":{\"PORELO_MATRIX_ACCESS_TOKEN\":" + TOKEN + "}}",
                "{\"secrets\":{\"PORELO_MATRIX_ACCESS_TOKEN\":\"" + TOKEN + "\"",
                "{\"secrets\":{\"PORELO_MATRIX_ACCESS_TOKEN\":\"" + TOKEN + "\"}} trailing"
            })
    @DisplayName("Broken JSON that names the secrets is refused with where it broke, never what")
    void brokenSecretsAreRefusedWithoutTheirText(final String text) {
        assertThatThrownBy(() -> Input.resolve(in(text), Map.of()))
                .isInstanceOf(Failure.class)
                .hasMessageNotContaining(TOKEN)
                .hasMessageContaining("line 1")
                .extracting(failure -> ((Failure) failure).exitCode())
                .isEqualTo(Exit.CONFIG);
    }

    /** Harm guarded: a secret in a refusal ends up in Porelo's log. */
    @Test
    @DisplayName("A refusal names the key, never the value")
    void aRefusalNamesNoValue() {
        assertThatThrownBy(
                        () ->
                                Input.resolve(
                                        in("{\"secrets\":{\"PATH\":\"" + TOKEN + "\"}}"), Map.of()))
                .hasMessageContaining("PATH")
                .hasMessageNotContaining(TOKEN);
    }
}
