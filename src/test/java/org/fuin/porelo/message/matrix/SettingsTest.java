package org.fuin.porelo.message.matrix;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The environment the lifecycle prints for Porelo to keep. Porelo lays it over the environment it
 * runs a verb in, so whatever it leaves out, the caller's own environment decides.
 */
class SettingsTest {

    private static final URI URL = URI.create("https://matrix.example.org");

    private static Settings settings(final String json) throws Failure {
        return Settings.read(new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    @DisplayName(
            "The defaults are named too, so an inherited TLS setting never outweighs the project's")
    void defaultsAreNamed() throws Failure {
        final Map<String, String> env =
                new HashMap<>(Map.of(Config.TLS_VERIFY, "off", Config.CA_FILE, "/tmp/evil.pem"));

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
                .containsEntry(Config.CA_FILE, "/etc/ssl/intranet.pem")
                .containsEntry(Config.TLS_VERIFY, "on");
        assertThat(settings("{\"tls_verify\":false}").environment(URL, "t"))
                .containsEntry(Config.CA_FILE, "")
                .containsEntry(Config.TLS_VERIFY, "off");
    }

    @org.junit.jupiter.params.ParameterizedTest(name = "{0}")
    @org.junit.jupiter.params.provider.ValueSource(
            strings = {
                "http://matrix.example.org",
                "http://192.168.1.10:8008",
                "http://localhost.example.org"
            })
    @DisplayName(
            "Plain http to another host is refused with 78: tokens and passwords would travel readable")
    void plainHttpElsewhereRefused(final String url) {
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> settings("{\"homeserver\":\"" + url + "\"}"))
                .isInstanceOf(Failure.class)
                .hasMessageContaining("plain http on another host")
                .satisfies(e -> assertThat(((Failure) e).exitCode()).isEqualTo(Exit.CONFIG));
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> Config.from(Map.of(Config.HOMESERVER, url, Config.ACCESS_TOKEN, "t")))
                .isInstanceOf(Failure.class)
                .hasMessageContaining("plain http on another host");
    }

    @org.junit.jupiter.params.ParameterizedTest(name = "{0}")
    @org.junit.jupiter.params.provider.ValueSource(
            strings = {
                "http://127.0.0.1:8008",
                "http://localhost:8008",
                "http://[::1]:8008",
                "https://matrix.example.org"
            })
    @DisplayName("http on this machine's loopback, and https anywhere, are taken")
    void loopbackHttpAndHttpsTaken(final String url) throws Failure {
        assertThat(
                        settings("{\"homeserver\":\"" + url + "\",\"room\":\"#p:example.org\"}")
                                .homeserver())
                .as(url)
                .hasToString(url);
        assertThat(
                        Config.from(Map.of(Config.HOMESERVER, url, Config.ACCESS_TOKEN, "t"))
                                .homeserver())
                .as(url)
                .hasToString(url);
    }

    /**
     * Harm guarded: on a central homeserver a person makes the project's room and invites each
     * machine into it; without its alias or id in the settings, or with one on the account's own
     * homeserver where setup makes the room itself, setup cannot know which room is the project's.
     */
    @Test
    @DisplayName(
            "A central homeserver and its room (#local:server or !opaque:server) go together, else 78")
    void roomForCentralHomeserver() throws Failure {
        assertThat(settings("{}").room()).as("no room on the own homeserver").isNull();
        for (final String valid :
                List.of(
                        "#project:matrix.example.org",
                        "!abc123:matrix.example.org",
                        "!8SMTIDnU1F0VdJ9SQHbXixFEhujitnU5ctcH1nt_mrI")) {
            assertThat(
                            settings(
                                            "{\"homeserver\":\"https://matrix.example.org\",\"room\":\""
                                                    + valid
                                                    + "\"}")
                                    .room())
                    .as(valid)
                    .isEqualTo(valid);
        }
        assertThatThrownBy(() -> settings("{\"homeserver\":\"https://matrix.example.org\"}"))
                .as("homeserver without room")
                .isInstanceOf(Failure.class)
                .satisfies(
                        f ->
                                assertThat(((Failure) f).exitCode())
                                        .as("exit code")
                                        .isEqualTo(Exit.CONFIG));
        for (final String bad :
                List.of(
                        "{\"room\":\"#project:matrix.example.org\"}",
                        "{\"homeserver\":\"https://matrix.example.org\",\"room\":\"project\"}",
                        "{\"homeserver\":\"https://matrix.example.org\",\"room\":\"#no-server\"}",
                        "{\"homeserver\":\"https://matrix.example.org\",\"room\":\"!\"}",
                        "{\"homeserver\":\"https://matrix.example.org\",\"room\":\"!has space\"}",
                        "{\"homeserver\":\"https://matrix.example.org\",\"room\":\"#empty:\"}",
                        "{\"homeserver\":\"https://matrix.example.org\",\"room\":\"#:empty\"}",
                        "{\"homeserver\":\"https://matrix.example.org\",\"room\":\"@user:matrix.example.org\"}",
                        "{\"homeserver\":\"https://matrix.example.org\",\"room\":\"#has space:matrix.example.org\"}",
                        "{\"homeserver\":\"https://matrix.example.org\",\"room\":\"\"}")) {
            assertThatThrownBy(() -> settings(bad))
                    .as(bad)
                    .isInstanceOf(Failure.class)
                    .hasMessageContaining("room")
                    .satisfies(
                            f ->
                                    assertThat(((Failure) f).exitCode())
                                            .as("exit code")
                                            .isEqualTo(Exit.CONFIG));
        }
    }

    /**
     * Harm guarded: a ca_file no path can name - a NUL in it - ended the verb with 1 and a stack
     * trace, an exit Porelo has no meaning for, instead of the refusal it reads.
     */
    @Test
    @DisplayName("A ca_file that is no path is refused with 78")
    void caFileThatIsNoPathRefused() {
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> settings("{\"ca_file\":\"/etc/ssl/a\\u0000b.pem\"}"))
                .as("the refusal")
                .isInstanceOf(Failure.class)
                .satisfies(f -> assertThat(((Failure) f).exitCode()).isEqualTo(Exit.CONFIG));
    }

    /**
     * Harm guarded: a homeserver elsewhere could not be set up through Porelo at all, since nothing
     * hands the transport its registration token; a relative path would be read against a directory
     * that is Porelo's, and the setting on the account's own homeserver would silently do nothing.
     */
    @Test
    @DisplayName(
            "registration_token_file is taken as an absolute path for a homeserver elsewhere, else 78")
    void registrationTokenFile() throws Failure {
        assertThat(
                        settings(
                                        "{\"homeserver\":\"https://matrix.example.org\","
                                                + "\"room\":\"#p:example.org\","
                                                + "\"registration_token_file\":\"/home/a/token\"}")
                                .registrationTokenFile())
                .as("registration_token_file")
                .hasToString("/home/a/token");
        for (final String json :
                List.of(
                        "{\"homeserver\":\"https://matrix.example.org\","
                                + "\"room\":\"#p:example.org\","
                                + "\"registration_token_file\":\"token\"}",
                        "{\"registration_token_file\":\"/home/a/token\"}")) {
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> settings(json))
                    .as(json)
                    .isInstanceOf(Failure.class)
                    .satisfies(f -> assertThat(((Failure) f).exitCode()).isEqualTo(Exit.CONFIG));
        }
    }
}
