package org.fuin.porelo.message.matrix;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * A person's own account on a central homeserver. Harm guarded: another person's task or relay, or
 * the administrator, speaks in the project's room or a task's direct chat as a person.
 */
class LoginTest {

    @ParameterizedTest
    @ValueSource(
            strings = {
                "@alice:x",
                "@alice_smith:x",
                "@1alice:x",
                "@a-very-long-name-of-more-than-32-chars:x"
            })
    void aBareAccountIsAPerson(final String id) {
        assertThat(Login.person(id, "x")).as("a person's own account: %s", id).isTrue();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "@t.alice:x",
                "@porelo.alice:x",
                "@porelo-relay-demo.alice:x",
                "@porelo:x",
                "@porelo-x:x",
                "@alice:y",
                "@alice:x.y",
                "alice:x",
                "@:x"
            })
    void anyOtherAccountIsNoPerson(final String id) {
        assertThat(Login.person(id, "x")).as("no person's own account: %s", id).isFalse();
    }

    @Test
    void noServerNamesNoPerson() {
        assertThat(Login.person("@alice:", "")).as("an account without a server").isFalse();
    }

    @Test
    void onlyANamedLoginMarksACentralAccount() {
        assertThat(Login.central(Map.of(Config.LOGIN, "alice"))).as("a login named").isTrue();
        assertThat(Login.central(Map.of(Config.LOGIN, " "))).as("a blank login").isFalse();
        assertThat(Login.central(Map.of())).as("the account's own homeserver").isFalse();
    }
}
