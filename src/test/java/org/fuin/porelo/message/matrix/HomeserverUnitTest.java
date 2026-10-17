package org.fuin.porelo.message.matrix;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class HomeserverUnitTest {

    private static final Path TEMPLATE = Path.of("homeserver", "porelo-matrix-homeserver.service");

    private static final Path DOCKERFILE = Path.of("homeserver", "Dockerfile");

    @Test
    @DisplayName("The unit runs exactly the image the Dockerfile pins, and names no other")
    void runsThePinnedImage() throws IOException {
        final String unit =
                HomeserverUnit.render(
                        Files.readString(TEMPLATE, StandardCharsets.UTF_8),
                        Files.readString(DOCKERFILE, StandardCharsets.UTF_8));

        assertThat(unit).contains(Tuwunel.image()).doesNotContain(HomeserverUnit.PLACEHOLDER);
        assertThat(unit.lines().filter(line -> line.contains("@sha256:"))).hasSize(1);
    }

    @Test
    @DisplayName("The container is named outside porelo-, which Porelo reads as its tasks' names")
    void containerOutsidePorelosNames() throws IOException {
        final String template = Files.readString(TEMPLATE, StandardCharsets.UTF_8);

        assertThat(template)
                .contains("--name matrix-homeserver")
                .doesNotContain("--name porelo-")
                .doesNotContainPattern("podman rm [^\\n]*porelo-");
    }

    @Test
    @DisplayName(
            "The container is acted on by the id podman gave the unit, never by its name, and never replaced")
    void containerByItsId() throws IOException {
        final String template = Files.readString(TEMPLATE, StandardCharsets.UTF_8);

        assertThat(template)
                .doesNotContain("--replace")
                .contains("--cidfile=%t/%N.cid --name matrix-homeserver");
        assertThat(template.lines().filter(line -> line.contains("podman rm")))
                .hasSize(3)
                .allSatisfy(
                        line ->
                                assertThat(line)
                                        .endsWith("--cidfile=%t/%N.cid")
                                        .doesNotContain("matrix-homeserver"));
    }

    @Test
    @DisplayName("The template holds no digest of its own, so it cannot drift from the Dockerfile")
    void templateHasNoDigest() throws IOException {
        assertThat(Files.readString(TEMPLATE, StandardCharsets.UTF_8)).doesNotContain("sha256:");
    }

    @Test
    @DisplayName("A Dockerfile that does not pin by digest is refused, not written into the unit")
    void refusesATag() {
        assertThatThrownBy(
                        () ->
                                HomeserverUnit.render(
                                        "x " + HomeserverUnit.PLACEHOLDER,
                                        "FROM ghcr.io/matrix-construct/tuwunel:latest\n"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("by digest");
    }

    @Test
    @DisplayName(
            "The unit makes its token with the transport's own verb, and publishes on loopback only")
    void tokenAndLoopback() throws IOException {
        final String template = Files.readString(TEMPLATE, StandardCharsets.UTF_8);

        assertThat(template)
                .contains(
                        "ExecStartPre=/usr/libexec/porelo/transports/porelo-message-transport-matrix homeserver-token")
                .contains("-p 127.0.0.1:${PORELO_MATRIX_PORT}:8008")
                .contains("TUWUNEL_ALLOW_FEDERATION=false");
    }

    /**
     * Harm guarded: the transport reads and writes the port and the registration token under {@code
     * $XDG_CONFIG_HOME} when it is set, the unit's own {@code homeserver-token} too; a unit that
     * names {@code ~/.config} then mounts another token than the one setup registers with, or runs
     * on another port than the one setup waits on.
     */
    @Test
    @DisplayName(
            "The unit finds its port and token where the transport puts them, under XDG_CONFIG_HOME")
    void sameDirectoryAsTheTransport() throws IOException {
        final String template = Files.readString(TEMPLATE, StandardCharsets.UTF_8);

        assertThat(template)
                .as("the unit")
                .contains("EnvironmentFile=-%E/porelo/matrix/homeserver.conf")
                .contains("-v %E/porelo/matrix/registration-token:/run/secrets/registration-token")
                .doesNotContain("%h/.config");
    }
}
