package org.fuin.porelo.message.matrix;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * No redirect is followed. Harm guarded: every request carries the access token, and a redirect
 * followed would carry it to whatever host the answer names.
 */
class RedirectTest {

    @ParameterizedTest(name = "{0}")
    @ValueSource(ints = {301, 302, 307, 308})
    @DisplayName("A redirect ends the call with 76, and the host it names hears nothing")
    void redirectIsNotFollowed(final int status) throws IOException {
        try (FakeHomeserver homeserver = new FakeHomeserver();
                FakeHomeserver elsewhere = new FakeHomeserver()) {
            homeserver.redirect(status, elsewhere.url() + "/_matrix/client/v3/joined_rooms");
            elsewhere.answer(200, "{\"joined_rooms\":[]}");
            final MatrixClient client =
                    new MatrixClient(
                            Config.from(
                                    Map.of(
                                            Config.HOMESERVER,
                                            homeserver.url(),
                                            Config.ACCESS_TOKEN,
                                            "secret-token")));

            assertThatThrownBy(() -> client.get("/_matrix/client/v3/joined_rooms"))
                    .as("the call")
                    .isInstanceOf(Failure.class)
                    .satisfies(f -> assertThat(((Failure) f).exitCode()).isEqualTo(Exit.PROTOCOL));
            assertThat(elsewhere.requests()).as("what the other host heard").isEmpty();
        } catch (final Failure ex) {
            throw new AssertionError(ex);
        }
    }
}
