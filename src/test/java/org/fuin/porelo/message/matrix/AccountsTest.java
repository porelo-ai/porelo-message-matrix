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

    /**
     * Harm guarded: a machine on a central homeserver derives its accounts' passwords from its
     * secret and the localpart; a change to the derivation would lock the machine out of every
     * account it already registered.
     */
    @Test
    @DisplayName(
            "A central account's password is the hex HMAC-SHA256 of the machine's secret over the localpart")
    void derivesPasswordFromMachineSecretAndLocalpart() {
        assertThat(Accounts.password("Jefe", "what do ya want for nothing?"))
                .as("RFC 4231 test case 2")
                .isEqualTo("5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843");
        assertThat(Accounts.password("machine-secret-alpha", "porelo.alpha"))
                .as("a second machine's secret gives a different password for the same localpart")
                .isNotEqualTo(Accounts.password("machine-secret-beta", "porelo.alpha"));
        assertThat(Accounts.password("machine-secret-alpha", "porelo.alpha"))
                .as("each account on the machine gets its own password")
                .isNotEqualTo(Accounts.password("machine-secret-alpha", "porelo-relay-demo.alpha"));
    }
}
