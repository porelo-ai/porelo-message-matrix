package org.fuin.porelo.message.matrix;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

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

    /**
     * 32 random bytes, 256 bits, as base64url without padding: the secret a person keeps on a
     * central homeserver to derive its accounts' passwords.
     */
    static String secret() {
        final byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /**
     * Derives the password of {@code localpart} from the person's {@code secret}, so every
     * lifecycle verb for that login can act as its own accounts without keeping each password.
     */
    static String password(final String secret, final String localpart) {
        try {
            final Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of()
                    .formatHex(mac.doFinal(localpart.getBytes(StandardCharsets.UTF_8)));
        } catch (final NoSuchAlgorithmException | InvalidKeyException ex) {
            throw new IllegalStateException("HmacSHA256 could not be initialized", ex);
        }
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
     * Registers through the gate without a token, or completes an external homeserver's token
     * challenge.
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
        if (registrationToken.isEmpty() && first.status() == 200) {
            return session(first);
        }
        if (first.status() != 401 || !first.body().path("session").isTextual()) {
            throw refused("registering " + localpart, first);
        }
        // A host's gate asks only for the dummy stage: who registers is the caller's login.
        final ObjectNode auth =
                request.putObject("auth").put("session", first.body().path("session").asText());
        if (registrationToken.isEmpty()) {
            auth.put("type", "m.login.dummy");
        } else {
            auth.put("type", "m.login.registration_token").put("token", registrationToken);
        }
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

    /**
     * Logged in with its password as its one session: every other one logged out first, so a token
     * lost to Porelo, or restored with a copy, is valid no more.
     */
    static Session alone(final MatrixClient anybody, final String localpart, final String password)
            throws Failure {
        logoutAll(anybody, localpart, password);
        return login(anybody, localpart, password);
    }

    /** Logs in as the account and ends every session, including the one just made. */
    static void logoutAll(final MatrixClient anybody, final String localpart, final String password)
            throws Failure {
        final Session session = login(anybody, localpart, password);
        logoutAll(anybody.as(session.accessToken()));
    }

    private static void logoutAll(final MatrixClient account) throws Failure {
        account.post("/_matrix/client/v3/logout/all", MatrixClient.JSON.createObjectNode());
    }

    /**
     * Logs in as {@code localpart} with {@code password}, leaves {@code room}, and logs out every
     * session so earlier tokens are refused while the name can be used again.
     */
    static void retire(
            final MatrixClient anybody,
            final String localpart,
            final String password,
            final String room)
            throws Failure {
        final Session session = login(anybody, localpart, password);
        final MatrixClient asAccount = anybody.as(session.accessToken());
        final MatrixClient.Answer leave =
                asAccount.exchange(
                        "POST",
                        "/_matrix/client/v3/rooms/" + MatrixClient.segment(room) + "/leave",
                        MatrixClient.JSON.createObjectNode());
        // Already out of the room on a second retire is done, not a failure.
        if (leave.status() != 200 && leave.status() != 403 && leave.status() != 404) {
            throw refused("leaving " + room + " as " + localpart, leave);
        }
        logoutAll(asAccount);
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
