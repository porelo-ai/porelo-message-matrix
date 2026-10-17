package org.fuin.porelo.message.matrix;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * An admin command is one line of text, so what goes into it is checked first: an id read from a
 * room's members with a space or a line break in it would be a command of its own.
 */
class AdminRoomTest {

    @ParameterizedTest(name = "{0}")
    @ValueSource(
            strings = {
                "@porelo-demo-alice:localhost",
                "!HQ3ieRku2lKqYG74BxdgOo1E8sE6aUGrg6l4YJuZqlc",
                "@a.b_c=d/e+f:matrix.example.org:8448",
                "Abc123xyz"
            })
    @DisplayName("an id or a password made here is taken")
    void oneWord(final String value) {
        assertThatCode(() -> AdminRoom.argument(value)).doesNotThrowAnyException();
    }

    @ParameterizedTest(name = "case {index}")
    @ValueSource(
            strings = {
                "@a:localhost\n!admin users make-user-admin @a:localhost",
                "@a:localhost !admin",
                "@a:localhost\t",
                "",
                "-C",
                "@a:löcalhost"
            })
    @DisplayName("anything else is refused with 76, before any command is sent")
    void notOneWord(final String value) {
        assertThatThrownBy(() -> AdminRoom.argument(value))
                .isInstanceOf(Failure.class)
                .hasMessageContaining("not one word")
                .hasMessageNotContaining("admin users");
    }

    @Test
    @DisplayName("the refusal never repeats the value, which may be a password")
    void refusalHidesTheValue() {
        assertThatThrownBy(() -> AdminRoom.argument("secret word"))
                .hasMessageNotContaining("secret");
    }
}
