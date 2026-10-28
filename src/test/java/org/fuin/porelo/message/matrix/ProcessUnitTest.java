package org.fuin.porelo.message.matrix;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Tuwunel's binary copied out of the pinned image, as the homeserver's tests get it. */
class ProcessUnitTest {

    @TempDir private Path dir;

    /**
     * Harm guarded: on a machine without the image cached, every test against the real homeserver
     * crashes in its setup, since podman's pull progress was taken for the container's id.
     */
    @Test
    @DisplayName(
            "An image podman pulls first still yields the binary, and the container made for it is removed by its id")
    void anImagePulledFirstYieldsTheBinary() throws Exception {
        final Path calls = dir.resolve("calls");
        final Path podman = dir.resolve("podman");
        Files.writeString(
                podman,
                "#!/bin/sh\n"
                        + "echo \"$*\" >> \""
                        + calls
                        + "\"\n"
                        + "case \"$1\" in\n"
                        + "create) echo 'Trying to pull ghcr.io/example/tuwunel...' >&2\n"
                        + "        echo 'Writing manifest to image destination' >&2\n"
                        + "        echo 4f2c9e ;;\n"
                        + "cp) [ \"$2\" = 4f2c9e:/usr/bin/tuwunel ] || exit 1\n"
                        + "    printf '#!/bin/sh\\n' > \"$3\" && chmod 700 \"$3\" ;;\n"
                        + "rm) [ \"$2\" = 4f2c9e ] || exit 1 ;;\n"
                        + "esac\n");
        Files.setPosixFilePermissions(podman, PosixFilePermissions.fromString("rwx------"));
        final Path target = dir.resolve("tuwunel");

        ProcessUnit.copyOut(target, podman.toString());

        assertThat(Files.isExecutable(target))
                .as("the binary was copied out of the container")
                .isTrue();
        assertThat(Files.readAllLines(calls, StandardCharsets.UTF_8))
                .as("the container is removed by the id podman printed, not by its progress")
                .last()
                .isEqualTo("rm 4f2c9e");
    }
}
