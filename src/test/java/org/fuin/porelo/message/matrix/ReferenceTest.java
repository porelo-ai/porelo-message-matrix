package org.fuin.porelo.message.matrix;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ReferenceTest {

    private static final String EVENT = "$t5KmEIilJ9dkUnAcY-w2b2vn89q-dxpvTwPUkiW8scg";

    @Test
    @DisplayName(
            "A message's name is its time and a UUID from its id - the shape the filter takes, the event id nowhere in it")
    void nameTheFilterTakes() {
        final String name = Reference.of(EVENT, 1_790_695_981_512L);

        assertThat(name)
                .matches(
                        "[0-9]{8}T[0-9]{9}--matrix-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
                .doesNotContain(EVENT.substring(1));
        assertThat(name + ".json").hasSizeLessThanOrEqualTo(128);
    }

    @Test
    @DisplayName("The same event is given the same name, so a second delivery replaces the first")
    void stable() {
        assertThat(Reference.of(EVENT, 1L)).isEqualTo(Reference.of(EVENT, 1L));
        assertThat(Reference.of(EVENT, 1L)).isNotEqualTo(Reference.of(EVENT + "x", 1L));
    }
}
