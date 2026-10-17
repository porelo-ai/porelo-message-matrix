package org.fuin.porelo.message.matrix;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The native executable starts on any x86-64 CPU, as every Porelo executable does. A native image
 * checks the CPU when it starts, against a list it carries as text; that list is read here, since a
 * build that drifts back to the tool's default would start on the machine that built it and refuse
 * an older one.
 */
class CpuBaselineIT {

    /** What x86-64 itself guarantees: nothing beyond it may be asked for. */
    private static final String BASELINE = "CX8, CMOV, FXSR, MMX, SSE, SSE2";

    private static final Pattern REQUIRED =
            Pattern.compile("required by the image: \\[([A-Z0-9_, ]+)\\]");

    @Test
    @DisplayName("The executable asks for no CPU feature beyond the x86-64 baseline")
    void startsOnAnyX8664Cpu() throws IOException {
        assumeTrue(Transport.isNative(), "only a native build has an executable to read");
        final String image =
                new String(
                        Files.readAllBytes(Path.of(System.getProperty(Transport.EXECUTABLE))),
                        StandardCharsets.ISO_8859_1);

        final Matcher required = REQUIRED.matcher(image);

        assertThat(required.find()).as("the image's own start-up check").isTrue();
        assertThat(required.group(1)).isEqualTo(BASELINE);
    }
}
