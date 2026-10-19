package org.fuin.porelo.message.matrix;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Inside a Porelo task the integration tests run Tuwunel's binary, copied into the task's image by
 * {@code porelo.yml}, where everywhere else they start the image {@code homeserver/Dockerfile}
 * pins. The homeserver is moved by hand in that one file; a {@code porelo.yml} left behind would
 * have the task test another homeserver than the one that ships, and every test there would still
 * be green.
 */
class TaskImageTest {

    private static final Pattern COPY =
            Pattern.compile("(?m)^\\s*COPY --from=(\\S+) /usr/bin/tuwunel (\\S+)\\s*$");

    @Test
    @DisplayName("The task's image takes Tuwunel from the very image the package runs")
    void theTaskTestsTheHomeserverThatShips() throws IOException {
        final String image = Files.readString(Path.of("porelo.yml"), StandardCharsets.UTF_8);
        final Matcher copy = COPY.matcher(image);

        assertThat(copy.find()).as("porelo.yml copies /usr/bin/tuwunel from an image").isTrue();
        assertThat(copy.group(1)).isEqualTo(Tuwunel.image());
        assertThat(image)
                .as("porelo.yml names the binary it copies to the tests")
                .contains(Tuwunel.BINARY + ": " + copy.group(2));
    }
}
