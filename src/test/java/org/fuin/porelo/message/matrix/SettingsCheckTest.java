package org.fuin.porelo.message.matrix;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * {@code settings}, which Porelo asks while a person edits a project file: every finding at once, what the
 * transport would refuse anywhere apart from what only this machine lacks.
 */
class SettingsCheckTest {

    @TempDir
    private Path dir;

    private int exit;

    private JsonNode check(final String settings, final Map<String, String> env) throws IOException {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        exit = Main.run(new String[] {"settings"}, env, new ByteArrayInputStream(settings.getBytes(StandardCharsets.UTF_8)),
                new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(OutputStream.nullOutputStream()));
        return MatrixClient.JSON.readTree(out.toByteArray());
    }

    private JsonNode check(final String settings) throws IOException {
        return check(settings, Map.of());
    }

    private static List<String> texts(final JsonNode array) {
        return array.valueStream().map(JsonNode::asText).toList();
    }

    @Test
    @DisplayName("Settings setup takes answer nothing refused and nothing to warn of")
    void clean() throws IOException {
        final Path pem = Files.writeString(dir.resolve("ca.pem"), "-----BEGIN CERTIFICATE-----\n", StandardCharsets.UTF_8);

        for (final String settings : List.of("", "{}", "{\"homeserver\":\"https://matrix.example.org\",\"ca_file\":\"" + pem
                + "\",\"tls_verify\":\"on\"}", "{\"tls_verify\":true}")) {
            final JsonNode answer = check(settings);
            assertThat(exit).as(settings).isEqualTo(Exit.OK);
            assertThat(texts(answer.path("refused"))).as(settings).isEmpty();
            assertThat(texts(answer.path("warnings"))).as(settings).isEmpty();
        }
    }

    @Test
    @DisplayName("Everything setup would refuse is named, all of it at once")
    void everythingRefusedAtOnce() throws IOException {
        final JsonNode answer = check("{\"homeserver\":\"ftp://x\",\"ca_file\":\"certs/ca.pem\",\"tls_verify\":\"maybe\","
                + "\"federation\":true,\"homesever\":\"x\"}");

        assertThat(exit).isEqualTo(Exit.OK);
        assertThat(texts(answer.path("refused"))).hasSize(5).anySatisfy(t -> assertThat(t).contains("'federation'"))
                .anySatisfy(t -> assertThat(t).contains("'homesever'"))
                .anySatisfy(t -> assertThat(t).contains("http or https URL"))
                .anySatisfy(t -> assertThat(t).contains("absolute path"))
                .anySatisfy(t -> assertThat(t).contains("on or off"));
    }

    @Test
    @DisplayName("What is allowed but not advised, or missing only here, is a warning, not a refusal")
    void warnings() throws IOException {
        final JsonNode off = check("{\"tls_verify\":false}");
        assertThat(texts(off.path("refused"))).isEmpty();
        assertThat(texts(off.path("warnings"))).singleElement().asString().contains("development only");

        final JsonNode absent = check("{\"ca_file\":\"" + dir.resolve("missing.pem") + "\"}");
        assertThat(texts(absent.path("refused"))).isEmpty();
        assertThat(texts(absent.path("warnings"))).singleElement().asString().contains("not there");
    }

    @Test
    @DisplayName("A CA file with the check switched off is refused, as setup refuses it")
    void contradiction() throws IOException {
        final Path pem = Files.writeString(dir.resolve("ca.pem"), "x", StandardCharsets.UTF_8);
        final String settings = "{\"ca_file\":\"" + pem + "\",\"tls_verify\":\"off\"}";

        assertThat(texts(check(settings).path("refused"))).singleElement().asString().contains("contradict");
        final ByteArrayOutputStream err = new ByteArrayOutputStream();
        assertThat(Main.run(new String[] {"setup", "--project", "p"}, Map.of(),
                new ByteArrayInputStream(settings.getBytes(StandardCharsets.UTF_8)),
                new PrintStream(OutputStream.nullOutputStream()), new PrintStream(err, true, StandardCharsets.UTF_8)))
                .isEqualTo(Exit.CONFIG);
        assertThat(err.toString(StandardCharsets.UTF_8)).as("setup refuses it before anything else").contains("contradict");
    }

    @Test
    @DisplayName("Not a JSON object is refused as such; an argument is 64")
    void shape() throws IOException {
        assertThat(texts(check("[1]").path("refused"))).singleElement().asString().contains("not a JSON object");
        assertThat(texts(check("{").path("refused"))).singleElement().asString().contains("not JSON");
        assertThat(Main.run(new String[] {"settings", "extra"}, Map.of(), new ByteArrayInputStream(new byte[0]),
                new PrintStream(OutputStream.nullOutputStream()), new PrintStream(OutputStream.nullOutputStream())))
                .isEqualTo(Exit.USAGE);
    }

    @Test
    @DisplayName("It reads no environment: an inherited setting changes nothing it answers")
    void noEnvironment() throws IOException {
        final JsonNode answer = check("{}", Map.of(Config.TLS_VERIFY, "off", Config.CA_FILE, "relative.pem"));

        assertThat(texts(answer.path("refused"))).isEmpty();
        assertThat(texts(answer.path("warnings"))).isEmpty();
    }

}
