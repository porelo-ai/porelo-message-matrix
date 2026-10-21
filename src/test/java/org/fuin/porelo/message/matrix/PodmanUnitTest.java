package org.fuin.porelo.message.matrix;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The run the unit's homeserver gets of its own, as podman is asked for it. */
class PodmanUnitTest {

    private static final String IMAGE = "ghcr.io/example/tuwunel@sha256:" + "0".repeat(64);

    @Test
    @DisplayName(
            "a secret for the run is named on podman's command line, never given there, and the run's output stays out of the journal")
    void aSecretIsNeverOnTheCommandLine() {
        final List<String> command =
                PodmanUnit.command(
                        "localhost",
                        Path.of("/state/porelo/matrix/backups"),
                        IMAGE,
                        List.of(),
                        Set.of("TUWUNEL_ADMIN_EXECUTE"));

        assertThat(command)
                .as("the secret's name alone, after -e")
                .containsSubsequence("-e", "TUWUNEL_ADMIN_EXECUTE")
                .noneMatch(arg -> arg.startsWith("TUWUNEL_ADMIN_EXECUTE="));
        assertThat(command)
                .as("the run's output may repeat the secret")
                .contains("--log-driver=none");
        assertThat(command)
                .as("no port published, no registration")
                .doesNotContain("-p")
                .contains("TUWUNEL_ALLOW_REGISTRATION=false");
    }

    @Test
    @DisplayName(
            "the run's own arguments come after the image, so podman never reads them as its own")
    void argumentsFollowTheImage() {
        final List<String> command =
                PodmanUnit.command(
                        "localhost",
                        Path.of("/b"),
                        IMAGE,
                        List.of("--restore-backup", "--execute", "server shutdown"),
                        Set.of());

        assertThat(command.subList(command.indexOf(IMAGE) + 1, command.size()))
                .as("after the image")
                .containsExactly("--restore-backup", "--execute", "server shutdown");
    }
}
