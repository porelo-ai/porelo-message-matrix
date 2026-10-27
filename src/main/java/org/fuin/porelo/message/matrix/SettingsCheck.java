package org.fuin.porelo.message.matrix;

import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.jspecify.annotations.Nullable;

/**
 * {@code settings}: a project's settings for this transport, checked as a draft before anybody
 * commits them - what {@code setup} would refuse anywhere, and what only this machine lacks. It
 * asks nothing of the network and reads no environment, so an editor can call it on every change.
 */
final class SettingsCheck {

    private SettingsCheck() {}

    static void run(final List<String> args, final InputStream in, final PrintStream out)
            throws Failure {
        final boolean loopbackOnly = args.equals(List.of("--loopback-only"));
        if (!args.isEmpty() && !loopbackOnly) {
            throw new Failure(
                    Exit.USAGE,
                    "usage: settings [--loopback-only] < <the project's settings as JSON>");
        }
        final List<String> refused = new ArrayList<>();
        final List<String> warnings = new ArrayList<>();
        final JsonNode node = read(in, refused);
        check(node, refused, warnings);
        if (loopbackOnly) {
            offline(node, refused);
        }
        final ObjectNode answer = MatrixClient.JSON.createObjectNode();
        refused.forEach(answer.putArray("refused")::add);
        warnings.forEach(answer.putArray("warnings")::add);
        try {
            out.println(MatrixClient.JSON.writeValueAsString(answer));
        } catch (final JacksonException ex) {
            throw new IllegalStateException("A JSON tree could not be written", ex);
        }
    }

    private static JsonNode read(final InputStream in, final List<String> refused) {
        try {
            final byte[] bytes = in.readAllBytes();
            final JsonNode node = bytes.length == 0 ? null : MatrixClient.JSON.readTree(bytes);
            if (node == null || node.isNull()) {
                return MatrixClient.JSON.createObjectNode();
            }
            if (!node.isObject()) {
                refused.add("the settings are not a JSON object");
                return MatrixClient.JSON.createObjectNode();
            }
            return node;
        } catch (final IOException ex) {
            refused.add("the settings are not JSON: " + ex.getMessage());
            return MatrixClient.JSON.createObjectNode();
        }
    }

    /** Whether tls_verify is off; a value that is neither is said once, by its own check. */
    private static boolean off(final JsonNode node) {
        try {
            return !Settings.verify(node.path("tls_verify"));
        } catch (final Failure failure) {
            return false;
        }
    }

    /** An offline project reaches this machine's loopback alone, as setup holds it to. */
    private static void offline(final JsonNode node, final List<String> refused) {
        try {
            final @Nullable URI homeserver = Settings.homeserver(node.path("homeserver"));
            if (homeserver != null && !Lifecycle.loopback(homeserver)) {
                refused.add(
                        "the project may reach only this machine's loopback, and its homeserver is "
                                + homeserver);
            }
        } catch (final Failure failure) {
            // Said already by the general check.
        }
    }

    /** What only this machine can say of the token's file: whether it is there, and whose. */
    private static void tokenFileOnThisMachine(final Path file, final List<String> warnings) {
        if (!Files.isReadable(file)) {
            warnings.add(
                    "the registration_token_file "
                            + file
                            + " is not there, or not readable, on this"
                            + " machine");
            return;
        }
        try {
            final Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(file);
            if (permissions.contains(PosixFilePermission.GROUP_READ)
                    || permissions.contains(PosixFilePermission.OTHERS_READ)) {
                warnings.add(
                        "the registration_token_file "
                                + file
                                + " is readable by other accounts, so setup refuses it; chmod 600");
            }
        } catch (final IOException | UnsupportedOperationException ex) {
            warnings.add("the registration_token_file " + file + " cannot be read: " + ex);
        }
    }

    /**
     * Every finding at once, in the order of the keys - an editor shows them all, not the first.
     */
    private static void check(
            final JsonNode node, final List<String> refused, final List<String> warnings) {
        final Map<String, JsonNode> keys = new TreeMap<>();
        node.properties().forEach(entry -> keys.put(entry.getKey(), entry.getValue()));
        for (final String key : keys.keySet()) {
            if (!Settings.KEYS.contains(key)) {
                refused.add("'" + key + "' is not a setting this transport knows");
            }
        }
        try {
            if (Settings.homeserver(node.path("homeserver")) != null
                    && (node.path("room").isMissingNode() || node.path("room").isNull())) {
                refused.add(Settings.HOMESERVER_WITHOUT_ROOM);
            }
        } catch (final Failure failure) {
            refused.add(failure.getMessage());
        }
        try {
            if (Settings.room(node.path("room")) != null
                    && (node.path("homeserver").isMissingNode()
                            || node.path("homeserver").isNull())) {
                refused.add(Settings.ROOM_WITHOUT_HOMESERVER);
            }
        } catch (final Failure failure) {
            refused.add(failure.getMessage());
        }
        try {
            final Path caFile = Settings.caFile(node.path("ca_file"));
            if (caFile != null && caFile.isAbsolute() && !Files.isReadable(caFile)) {
                warnings.add(
                        "the ca_file "
                                + caFile
                                + " is not there, or not readable, on this machine");
            }
        } catch (final Failure failure) {
            refused.add(failure.getMessage());
        }
        try {
            final Path tokenFile =
                    Settings.registrationTokenFile(node.path("registration_token_file"));
            if (tokenFile != null) {
                if (node.path("homeserver").isMissingNode() || node.path("homeserver").isNull()) {
                    refused.add(Settings.TOKEN_FILE_WITHOUT_HOMESERVER);
                } else if (tokenFile.isAbsolute()) {
                    // A path in the home is each account's own: this check reads no environment.
                    tokenFileOnThisMachine(tokenFile, warnings);
                }
            }
        } catch (final Failure failure) {
            refused.add(failure.getMessage());
        }
        try {
            if (Settings.caCertificate(node.path("ca_certificate")) != null) {
                if (!node.path("ca_file").isMissingNode() && !node.path("ca_file").isNull()) {
                    refused.add(Settings.TWO_CAS);
                }
                if (off(node)) {
                    refused.add(Settings.TEXT_CONTRADICTION);
                }
            }
        } catch (final Failure failure) {
            refused.add(failure.getMessage());
        }
        try {
            if (!Settings.verify(node.path("tls_verify"))) {
                warnings.add(
                        "tls_verify off checks no certificate: for development only, not recommended");
                if (!node.path("ca_file").isMissingNode() && !node.path("ca_file").isNull()) {
                    refused.add(Settings.CONTRADICTION);
                }
            }
        } catch (final Failure failure) {
            refused.add(failure.getMessage());
        }
    }
}
