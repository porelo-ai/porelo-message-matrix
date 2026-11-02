package org.fuin.porelo.message.matrix;

/**
 * Harm guarded: the registration token lets anyone register accounts on the homeserver, so a file
 * another account can read hands that to it; an empty or missing file would register with nothing
 * and fail far from its cause.
 */
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class RegistrationTokenFileTest {

    /**
     * Harm guarded: a token file the homeserver reads holds several tokens, and the transport sends
     * them all as one, which registers nothing.
     */
    @Test
    void readsTheFirstTokenOfSeveral() {
        assertThat(RegistrationTokenFile.firstToken("first\nsecond third\n"))
                .as("the first token, as the homeserver's own file holds this machine's first")
                .isEqualTo("first");
    }

    /** Harm guarded: a file whose first line is empty hands on another machine's token. */
    @Test
    void takesNothingAfterAnEmptyFirstLine() {
        assertThat(RegistrationTokenFile.firstToken("\nanother-machines-token\n"))
                .as("no token is taken from a line after an empty first one")
                .isEmpty();
    }
}
