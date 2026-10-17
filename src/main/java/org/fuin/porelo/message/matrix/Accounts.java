package org.fuin.porelo.message.matrix;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.security.SecureRandom;

/**
 * Making and finding accounts on a homeserver. A password is made here, used once to log in or
 * handed to a person once, and never kept: what Porelo keeps is the account's token.
 */
final class Accounts {

    private static final SecureRandom RANDOM = new SecureRandom();

    /** An account's id and its token. */
    record Session(String userId, String accessToken) {

        @Override
        public String toString() {
            return "Session[" + userId + "]";
        }
    }

    private Accounts() {}

    private static final String ALPHABET =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";

    /**
     * 32 letters and digits, about 190 bits. Nothing else: a password goes into an admin-room
     * command, whose parser takes one starting with {@code -} for an option of its own.
     */
    static String password() {
        final StringBuilder password = new StringBuilder(32);
        for (int i = 0; i < 32; i++) {
            password.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        }
        return password.toString();
    }

    /** Whether nobody has this name yet. */
    static boolean available(final MatrixClient anybody, final String localpart) throws Failure {
        final MatrixClient.Answer answer =
                anybody.exchange(
                        "GET",
                        "/_matrix/client/v3/register/available?username="
                                + MatrixClient.segment(localpart),
                        null);
        if (answer.status() == 200) {
            return answer.body().path("available").asBoolean(false);
        }
        if ("M_USER_IN_USE".equals(answer.errcode())) {
            return false;
        }
        throw new Failure(
                answer.status() == 429 || answer.status() >= 500 ? Exit.TEMPORARY : Exit.PROTOCOL,
                "asking whether '"
                        + localpart
                        + "' is free answered "
                        + answer.status()
                        + " "
                        + answer.errcode());
    }

    /**
     * Registers with the homeserver's registration token, in the two steps its user-interactive
     * auth takes.
     */
    static Session register(
            final MatrixClient anybody,
            final String localpart,
            final String password,
            final String registrationToken)
            throws Failure {
        final ObjectNode request =
                MatrixClient.JSON
                        .createObjectNode()
                        .put("username", localpart)
                        .put("password", password)
                        .put("inhibit_login", false);
        final MatrixClient.Answer first =
                anybody.exchange("POST", "/_matrix/client/v3/register", request);
        if (first.status() != 401 || !first.body().path("session").isTextual()) {
            throw refused("registering " + localpart, first);
        }
        request.putObject("auth")
                .put("type", "m.login.registration_token")
                .put("token", registrationToken)
                .put("session", first.body().path("session").asText());
        final MatrixClient.Answer second =
                anybody.exchange("POST", "/_matrix/client/v3/register", request);
        if (second.status() != 200) {
            throw refused("registering " + localpart, second);
        }
        return session(second);
    }

    static Session login(final MatrixClient anybody, final String localpart, final String password)
            throws Failure {
        final ObjectNode request =
                MatrixClient.JSON
                        .createObjectNode()
                        .put("type", "m.login.password")
                        .put("password", password);
        request.putObject("identifier").put("type", "m.id.user").put("user", localpart);
        final MatrixClient.Answer answer =
                anybody.exchange("POST", "/_matrix/client/v3/login", request);
        if (answer.status() != 200) {
            throw refused("logging in as " + localpart, answer);
        }
        return session(answer);
    }

    /** The account's own id, or null when the homeserver does not know the token. */
    static String whoami(final MatrixClient account) throws Failure {
        return account.get("/_matrix/client/v3/account/whoami").path("user_id").asText("");
    }

    private static Session session(final MatrixClient.Answer answer) throws Failure {
        final String userId = answer.body().path("user_id").asText("");
        final String token = answer.body().path("access_token").asText("");
        if (userId.isEmpty() || token.isEmpty()) {
            throw new Failure(
                    Exit.PROTOCOL, "the homeserver made an account but named no id or token");
        }
        return new Session(userId, token);
    }

    private static Failure refused(final String what, final MatrixClient.Answer answer) {
        final int status = answer.status();
        final int exit =
                status == 429 || status >= 500
                        ? Exit.TEMPORARY
                        : status == 401 || status == 403 ? Exit.NOT_PERMITTED : Exit.PROTOCOL;
        return new Failure(
                exit,
                what
                        + " answered "
                        + status
                        + " "
                        + answer.errcode()
                        + ": "
                        + answer.body().path("error").asText(""));
    }
}
