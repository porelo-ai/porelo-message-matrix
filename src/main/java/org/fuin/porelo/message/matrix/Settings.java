package org.fuin.porelo.message.matrix;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * A project's settings for this transport - what its project file says under {@code
 * mail.transports.matrix} - handed to a lifecycle verb as JSON on stdin. A key nobody knows stops
 * the verb rather than being ignored, since it would otherwise be a setting that silently does
 * nothing.
 */
record Settings(
        @Nullable URI homeserver,
        @Nullable Path caFile,
        boolean verifyTls,
        @Nullable Path registrationTokenFile) {

    /** Said on stderr whenever the project's settings switch the certificate check off. */
    static final String VERIFY_OFF_WARNING =
            "WARNING: tls_verify is off in the project's settings - the homeserver's certificate is"
                    + " not checked, and whoever answers in its place receives the admin token. For"
                    + " development only.";

    static final Set<String> KEYS =
            Set.of("homeserver", "ca_file", "tls_verify", "registration_token_file");

    static final String CONTRADICTION =
            "the project's ca_file and tls_verify off contradict each other; set one";

    static Settings read(final InputStream in) throws Failure {
        final JsonNode node;
        try {
            final byte[] bytes = in.readAllBytes();
            node =
                    bytes.length == 0
                            ? MatrixClient.JSON.createObjectNode()
                            : MatrixClient.JSON.readTree(bytes);
        } catch (final IOException ex) {
            throw new Failure(
                    Exit.CONFIG,
                    "the project's settings on stdin are not JSON: " + ex.getMessage(),
                    ex);
        }
        if (node == null || node.isNull()) {
            return new Settings(null, null, true, null);
        }
        if (!node.isObject()) {
            throw new Failure(Exit.CONFIG, "the project's settings on stdin are not a JSON object");
        }
        for (final Map.Entry<String, JsonNode> entry : node.properties()) {
            final String key = entry.getKey();
            if (!KEYS.contains(key)) {
                throw new Failure(
                        Exit.CONFIG,
                        "the project's settings name '"
                                + key
                                + "', which this transport does not know");
            }
        }
        final Settings settings =
                new Settings(
                        homeserver(node.path("homeserver")),
                        caFile(node.path("ca_file")),
                        verify(node.path("tls_verify")),
                        absolutePath(
                                node.path("registration_token_file"), "registration_token_file"));
        // Refused here, not first by the verb that reads the printed secrets: which was meant
        // cannot be told.
        if (settings.caFile() != null && !settings.verifyTls()) {
            throw new Failure(Exit.CONFIG, CONTRADICTION);
        }
        // The account's own homeserver has its token in the transport's own file; a second one
        // here would silently do nothing.
        if (settings.registrationTokenFile() != null && settings.homeserver() == null) {
            throw new Failure(
                    Exit.CONFIG,
                    "the project's registration_token_file is for a homeserver elsewhere; this"
                            + " account's own needs none");
        }
        return settings;
    }

    static @Nullable URI homeserver(final JsonNode value) throws Failure {
        if (value.isMissingNode() || value.isNull()) {
            return null;
        }
        final String text = value.asText("");
        try {
            final URI uri =
                    new URI(text.endsWith("/") ? text.substring(0, text.length() - 1) : text);
            if (!("http".equals(uri.getScheme()) || "https".equals(uri.getScheme()))
                    || uri.getHost() == null) {
                throw new Failure(
                        Exit.CONFIG,
                        "the project's homeserver must be an http or https URL, not '"
                                + text
                                + "'");
            }
            // Accounts' tokens and passwords travel to it: in the clear only where they never leave
            // this machine.
            if ("http".equals(uri.getScheme()) && !Lifecycle.loopback(uri)) {
                throw new Failure(
                        Exit.CONFIG,
                        "the project's homeserver "
                                + text
                                + " is plain http on another host, where"
                                + " tokens and passwords would travel readable; use https, or http only on this machine's loopback");
            }
            return uri;
        } catch (final URISyntaxException ex) {
            throw new Failure(Exit.CONFIG, "the project's homeserver is not a URL: " + text, ex);
        }
    }

    static @Nullable Path caFile(final JsonNode value) throws Failure {
        return absolutePath(value, "ca_file");
    }

    private static @Nullable Path absolutePath(final JsonNode value, final String key)
            throws Failure {
        if (value.isMissingNode() || value.isNull()) {
            return null;
        }
        final Path path;
        try {
            path = Path.of(value.asText(""));
        } catch (final InvalidPathException ex) {
            throw new Failure(
                    Exit.CONFIG, "the project's " + key + " is no path: " + ex.getReason(), ex);
        }
        // Relative to what is not the transport's to know: the project's checkout is Porelo's.
        if (!path.isAbsolute()) {
            throw new Failure(
                    Exit.CONFIG,
                    "the project's " + key + " must be an absolute path, not '" + path + "'");
        }
        return path;
    }

    static boolean verify(final JsonNode value) throws Failure {
        if (value.isMissingNode() || value.isNull()) {
            return true;
        }
        // A project file's `tls_verify: off` reaches here as the boolean false, since YAML reads it
        // so.
        if (value.isBoolean()) {
            return value.asBoolean();
        }
        final String text = value.asText("");
        if ("on".equals(text) || "off".equals(text)) {
            return "on".equals(text);
        }
        throw new Failure(
                Exit.CONFIG, "the project's tls_verify must be on or off, not '" + text + "'");
    }

    /**
     * The environment a verb acting as {@code token} on {@code homeserver} reads - complete, as
     * Porelo hands it back.
     */
    Map<String, String> environment(final URI homeserverUrl, final String token) {
        final Map<String, String> env = new LinkedHashMap<>();
        env.put(Config.HOMESERVER, homeserverUrl.toString());
        env.put(Config.ACCESS_TOKEN, token);
        // Both named always, the defaults too: laid over an environment that already has one, what
        // the project
        // configured wins - an inherited "off" must not switch the check off unasked.
        env.put(Config.CA_FILE, caFile == null ? "" : caFile.toString());
        env.put(Config.TLS_VERIFY, verifyTls ? "on" : "off");
        return env;
    }
}
