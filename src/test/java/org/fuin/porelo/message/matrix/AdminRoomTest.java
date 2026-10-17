package org.fuin.porelo.message.matrix;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.util.Map;
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

    /**
     * Harm guarded: two lifecycle verbs at once each command the admin room; a reply taken for the
     * wrong command leaves the reply holding a reset password unredacted, readable to anybody in
     * the room later.
     */
    @Test
    @DisplayName("the reply is the one that answers this command, not a newer one to another")
    void replyAnswersThisCommand() throws IOException, Failure {
        try (FakeHomeserver homeserver = new FakeHomeserver()) {
            homeserver
                    .answer(200, "{\"joined_rooms\":[\"!admins:x\"]}")
                    .answer(200, "{\"alias\":\"#admins:x\"}")
                    .answer(200, "{\"event_id\":\"$mine\"}")
                    .answer(
                            200,
                            """
                            {"chunk":[
                             {"type":"m.room.message","event_id":"$theirs-reply","sender":"@conduit:x",
                              "content":{"msgtype":"m.notice","body":"Successfully reset the password of @b:x",
                               "m.relates_to":{"m.in_reply_to":{"event_id":"$theirs"}}}},
                             {"type":"m.room.message","event_id":"$theirs","sender":"@porelo:x",
                              "content":{"msgtype":"m.text","body":"!admin users reset-password @b:x pw"}},
                             {"type":"m.room.message","event_id":"$notice","sender":"@conduit:x",
                              "content":{"msgtype":"m.notice","body":"New user registered"}},
                             {"type":"m.room.message","event_id":"$mine-reply","sender":"@conduit:x",
                              "content":{"msgtype":"m.notice","body":"Found 2 local user account(s):",
                               "m.relates_to":{"m.in_reply_to":{"event_id":"$mine"}}}},
                             {"type":"m.room.message","event_id":"$mine","sender":"@porelo:x",
                              "content":{"msgtype":"m.text","body":"!admin users list-users"}}]}
                            """);
            final MatrixClient admin =
                    new MatrixClient(
                            Config.from(
                                    Map.of(
                                            Config.HOMESERVER,
                                            homeserver.url(),
                                            Config.ACCESS_TOKEN,
                                            "t")));

            final String reply =
                    AdminRoom.of(admin, "@porelo:x").command("!admin users list-users");

            assertThat(reply).as("the reply taken").isEqualTo("Found 2 local user account(s):");
        }
    }
}
