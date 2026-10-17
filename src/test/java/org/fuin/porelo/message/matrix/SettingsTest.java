package org.fuin.porelo.message.matrix;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The environment the lifecycle prints for Porelo to keep. Porelo lays it over the environment it runs a verb
 * in, so whatever it leaves out, the caller's own environment decides.
 */
class SettingsTest {

    private static final URI URL = URI.create("https://matrix.example.org");

    private static Settings settings(final String json) throws Failure {
        return Settings.read(new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    @DisplayName("The defaults are named too, so an inherited TLS setting never outweighs the project's")
    void defaultsAreNamed() throws Failure {
        final Map<String, String> env = new HashMap<>(Map.of(Config.TLS_VERIFY, "off", Config.CA_FILE, "/tmp/evil.pem"));

        env.putAll(settings("{}").environment(URL, "t"));

        assertThat(env).containsEntry(Config.TLS_VERIFY, "on").containsEntry(Config.CA_FILE, "");
        final Config config = Config.from(env);
        assertThat(config.verifyTls()).isTrue();
        assertThat(config.caFile()).isNull();
    }

    @Test
    @DisplayName("What the project configured is what is printed")
    void configuredIsPrinted() throws Failure {
        assertThat(settings("{\"ca_file\":\"/etc/ssl/intranet.pem\"}").environment(URL, "t"))
                .containsEntry(Config.CA_FILE, "/etc/ssl/intranet.pem").containsEntry(Config.TLS_VERIFY, "on");
        assertThat(settings("{\"tls_verify\":false}").environment(URL, "t"))
                .containsEntry(Config.CA_FILE, "").containsEntry(Config.TLS_VERIFY, "off");
    }

    @org.junit.jupiter.params.ParameterizedTest(name = "{0}")
    @org.junit.jupiter.params.provider.ValueSource(strings = {"http://matrix.example.org", "http://192.168.1.10:8008",
            "http://localhost.example.org"})
    @DisplayName("Plain http to another host is refused with 78: tokens and passwords would travel readable")
    void plainHttpElsewhereRefused(final String url) {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> settings("{\"homeserver\":\"" + url + "\"}"))
                .isInstanceOf(Failure.class).hasMessageContaining("plain http on another host")
                .satisfies(e -> assertThat(((Failure) e).exitCode()).isEqualTo(Exit.CONFIG));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> Config.from(Map.of(Config.HOMESERVER, url,
                Config.ACCESS_TOKEN, "t"))).isInstanceOf(Failure.class).hasMessageContaining("plain http on another host");
    }

    @org.junit.jupiter.params.ParameterizedTest(name = "{0}")
    @org.junit.jupiter.params.provider.ValueSource(strings = {"http://127.0.0.1:8008", "http://localhost:8008", "http://[::1]:8008",
            "https://matrix.example.org"})
    @DisplayName("http on this machine's loopback, and https anywhere, are taken")
    void loopbackHttpAndHttpsTaken(final String url) throws Failure {
        assertThat(settings("{\"homeserver\":\"" + url + "\"}").homeserver()).hasToString(url);
        assertThat(Config.from(Map.of(Config.HOMESERVER, url, Config.ACCESS_TOKEN, "t")).homeserver()).hasToString(url);
    }

}
