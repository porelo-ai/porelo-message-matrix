package org.fuin.porelo.message.matrix;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class MainTest {

    private final ByteArrayOutputStream errBytes = new ByteArrayOutputStream();

    private final PrintStream err = new PrintStream(errBytes, true, StandardCharsets.UTF_8);

    @Test
    @DisplayName("Without a verb it prints its usage and exits with 64")
    void noVerb() {
        final int exit = Main.run(new String[0], Map.of(), err, err);

        assertThat(exit).isEqualTo(Exit.USAGE);
        assertThat(stderr()).startsWith("usage: porelo-message-transport-matrix");
    }

    @Test
    @DisplayName("An unknown verb is refused with 64, naming it")
    void unknownVerb() {
        final int exit = Main.run(new String[] {"deliver-anywhere"}, Map.of(), err, err);

        assertThat(exit).isEqualTo(Exit.USAGE);
        assertThat(stderr()).contains("unknown verb 'deliver-anywhere'");
    }

    private String stderr() {
        return errBytes.toString(StandardCharsets.UTF_8);
    }
}
