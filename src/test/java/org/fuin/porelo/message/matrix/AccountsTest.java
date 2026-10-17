package org.fuin.porelo.message.matrix;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AccountsTest {

    @Test
    @DisplayName(
            "A password is letters and digits only, so the admin room's command never reads one as an option")
    void passwordIsLettersAndDigits() {
        final Set<String> seen = new HashSet<>();
        for (int i = 0; i < 10_000; i++) {
            final String password = Accounts.password();
            assertThat(password).hasSize(32).matches("[A-Za-z0-9]+");
            seen.add(password);
        }
        assertThat(seen).as("no password twice").hasSize(10_000);
    }
}
