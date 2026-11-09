package org.fuin.porelo.message.matrix;

import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The names a host passes for its persons, as {@code keep-room} reads them from its input and gives
 * them to the persons' accounts: an account showing no name gets the one given, an account showing
 * a name of its own keeps it.
 */
final class Names {

    private static final String KEY = "names";

    private Names() {}

    /**
     * The names on the input's settings, {@code {"names":{"<login>":"<name>"}}}, each checked: a
     * key that is no login of the list, or settings of another shape, is 78; a name breaking the
     * display rule is 64, saying whose, never repeating the name.
     */
    static Map<String, String> read(final InputStream settings, final List<String> logins)
            throws Failure {
        final byte[] bytes;
        try {
            bytes = settings.readNBytes(Input.LIMIT + 1);
        } catch (final IOException ex) {
            throw new Failure(Exit.CONFIG, "cannot read the settings on stdin", ex);
        }
        if (bytes.length > Input.LIMIT) {
            throw new Failure(Exit.CONFIG, "stdin holds more than " + Input.LIMIT + " bytes");
        }
        final JsonNode node;
        try {
            node =
                    MatrixClient.JSON
                            .reader()
                            .with(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                            .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                            .readTree(bytes);
        } catch (final IOException ex) {
            // Where, never what: the parser's message quotes the input.
            throw new Failure(Exit.CONFIG, "the settings on stdin are not one JSON value");
        }
        if (node == null || node.isMissingNode()) {
            return Map.of();
        }
        if (!node.isObject()) {
            throw new Failure(Exit.CONFIG, "the settings on stdin are not a JSON object");
        }
        if (node.isEmpty()) {
            return Map.of();
        }
        for (final var keys = node.fieldNames(); keys.hasNext(); ) {
            final String key = keys.next();
            if (!KEY.equals(key)) {
                throw new Failure(
                        Exit.CONFIG,
                        "keep-room takes only names in its settings, not '" + key + "'");
            }
        }
        final JsonNode given = node.path(KEY);
        if (!given.isObject()) {
            throw new Failure(Exit.CONFIG, "the names are not a JSON object of login to name");
        }
        final Map<String, String> names = new TreeMap<>();
        for (final Map.Entry<String, JsonNode> entry : given.properties()) {
            final String login = entry.getKey();
            if (!logins.contains(login)) {
                throw new Failure(
                        Exit.CONFIG,
                        "a name is given for "
                                + (Login.valid(login) ? login : "a login")
                                + ", which --persons does not name");
            }
            if (!entry.getValue().isTextual()) {
                throw new Failure(Exit.CONFIG, "the name for " + login + " is not text");
            }
            final String name = entry.getValue().asText().strip();
            if (!Lifecycle.DISPLAY.matcher(name).matches()) {
                throw new Failure(
                        Exit.USAGE,
                        "the name for "
                                + login
                                + " must be 1 to 64 characters, with no control or format"
                                + " characters, '@' or ':'");
            }
            names.put(login, name);
        }
        return names;
    }

    /**
     * Gives each login's account its name where it shows none, making a missing account with it.
     * {@code home} is the homeserver's directory, where the gate keeps its lock and the expelled. A
     * login the gate expelled, also while this waited for the gate's lock, is neither made nor
     * named; a key that is no login is 78 before anything is asked.
     */
    static void give(
            final MatrixClient admin,
            final String server,
            final Map<String, String> names,
            final Path home)
            throws Failure {
        for (final String login : names.keySet()) {
            Login.name(login);
        }
        for (final Map.Entry<String, String> entry : new TreeMap<>(names).entrySet()) {
            final String login = entry.getKey();
            final String id = "@" + login + ":" + server;
            final String users = "/_synapse/admin/v2/users/" + MatrixClient.segment(id);
            // The gate makes an account under the same lock, so neither resets the other's.
            synchronized (Gate.class) {
                try (var channel = GateFiles.lock(GateFiles.usersLock(home.resolve("gate")));
                        var held = channel.lock()) {
                    if (!Files.notExists(
                            home.resolve(Expel.DIRECTORY).resolve(login),
                            LinkOption.NOFOLLOW_LINKS)) {
                        continue;
                    }
                    if (available(admin, login)) {
                        final ObjectNode made =
                                MatrixClient.JSON
                                        .createObjectNode()
                                        .put("displayname", entry.getValue())
                                        // Nobody keeps it: a person signs in by the gate's token.
                                        .put("password", Accounts.secret());
                        admin.put(users, made);
                    } else if (showsNone(admin, id)) {
                        admin.put(
                                users,
                                MatrixClient.JSON
                                        .createObjectNode()
                                        .put("displayname", entry.getValue()));
                    }
                } catch (final IOException ex) {
                    throw new Failure(
                            Exit.CONFIG,
                            "cannot take the gate's lock to name " + login + ": " + ex.getMessage(),
                            ex);
                }
            }
        }
    }

    private static boolean available(final MatrixClient admin, final String login) throws Failure {
        final MatrixClient.Answer answer =
                admin.exchange(
                        "GET",
                        "/_synapse/admin/v1/username_available?username="
                                + MatrixClient.segment(login),
                        null);
        if (answer.status() == 400 && "M_USER_IN_USE".equals(answer.errcode())) {
            return false;
        }
        if (answer.status() == 401 || answer.status() == 403) {
            throw new Failure(
                    Exit.NOT_PERMITTED,
                    "the homeserver refused asking whether " + login + " is taken");
        }
        if (answer.status() != 200 || !answer.body().path("available").isBoolean()) {
            throw new Failure(
                    answer.status() == 429 || answer.status() >= 500
                            ? Exit.TEMPORARY
                            : Exit.PROTOCOL,
                    "the homeserver answered "
                            + answer.status()
                            + " asking whether "
                            + login
                            + " is taken");
        }
        return answer.body().path("available").booleanValue();
    }

    /** Whether the account shows no name, so its Matrix id would show in the room. */
    private static boolean showsNone(final MatrixClient admin, final String id) throws Failure {
        final JsonNode profile =
                admin.find(
                        "/_matrix/client/v3/profile/" + MatrixClient.segment(id) + "/displayname");
        return profile == null || profile.path("displayname").asText("").isBlank();
    }
}
