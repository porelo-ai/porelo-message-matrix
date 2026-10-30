package org.fuin.porelo.message.matrix;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
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
        @Nullable Path registrationTokenFile,
        @Nullable String room,
        @Nullable String caCertificate) {

    /** Said on stderr whenever the project's settings switch the certificate check off. */
    static final String VERIFY_OFF_WARNING =
            "WARNING: tls_verify is off in the project's settings - the homeserver's certificate is"
                    + " not checked, and whoever answers in its place receives the admin token. For"
                    + " development only.";

    static final Set<String> KEYS =
            Set.of(
                    "homeserver",
                    "ca_file",
                    "ca_certificate",
                    "tls_verify",
                    "registration_token_file",
                    "room");

    /** A host's gate supplies the registration token itself. */
    boolean hosted() {
        return homeserver != null
                && ("unix".equals(homeserver.getScheme())
                        || (homeserver.getHost() != null
                                && homeserver
                                        .getHost()
                                        .toLowerCase(Locale.ROOT)
                                        .endsWith(".localhost")));
    }

    static final String TOKEN_FILE_ON_A_HOST =
            "the project's registration_token_file is for a homeserver elsewhere; a host's"
                    + " homeserver behind its gate takes no registration token";

    static final String TEXT_CONTRADICTION =
            "the project's ca_certificate and tls_verify off contradict each other; set one";

    static final String TWO_CAS =
            "the project's ca_file and ca_certificate both name a CA; give one";

    static final String TOKEN_FILE_WITHOUT_HOMESERVER =
            "the project's registration_token_file is for a homeserver elsewhere; this account's"
                    + " own needs none";

    static final String HOMESERVER_WITHOUT_ROOM =
            "a homeserver in the settings is a central one and needs the project's room as `room`";

    static final String ROOM_WITHOUT_HOMESERVER =
            "the project's room is for a central homeserver; this account's own makes its room"
                    + " itself";

    static final String CONTRADICTION =
            "the project's ca_file and tls_verify off contradict each other; set one";

    /**
     * A room alias ({@code #local:server}) or a room id ({@code !opaque}, which from room version
     * 12 on carries no server name).
     */
    private static final Pattern ROOM =
            Pattern.compile(
                    "(#[^:\\s\\p{Cntrl}]+:([A-Za-z0-9.-]+|\\[[0-9a-fA-F:]+\\])(:[0-9]{1,5})?|![^\\s\\p{Cntrl}]+)");

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
            return new Settings(null, null, true, null, null, null);
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
                                node.path("registration_token_file"), "registration_token_file"),
                        room(node.path("room")),
                        caCertificate(node.path("ca_certificate")));
        if (settings.caFile() != null && settings.caCertificate() != null) {
            throw new Failure(Exit.CONFIG, TWO_CAS);
        }
        if (settings.caCertificate() != null && !settings.verifyTls()) {
            throw new Failure(Exit.CONFIG, TEXT_CONTRADICTION);
        }
        // Refused here, not first by the verb that reads the printed secrets: which was meant
        // cannot be told.
        if (settings.caFile() != null && !settings.verifyTls()) {
            throw new Failure(Exit.CONFIG, CONTRADICTION);
        }
        // The account's own homeserver has its token in the transport's own file; a second one
        // here would silently do nothing.
        if (settings.registrationTokenFile() != null && settings.homeserver() == null) {
            throw new Failure(Exit.CONFIG, TOKEN_FILE_WITHOUT_HOMESERVER);
        }
        // A central homeserver's room is made by a person there; without it, setup has none to
        // join.
        // A host's gate registers without a token; one named here would silently do nothing.
        if (settings.registrationTokenFile() != null && settings.hosted()) {
            throw new Failure(Exit.CONFIG, TOKEN_FILE_ON_A_HOST);
        }
        if (settings.homeserver() != null && settings.room() == null) {
            throw new Failure(Exit.CONFIG, HOMESERVER_WITHOUT_ROOM);
        }
        if (settings.room() != null && settings.homeserver() == null) {
            throw new Failure(Exit.CONFIG, ROOM_WITHOUT_HOMESERVER);
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
            if ("unix".equals(uri.getScheme())) {
                return Config.socket(uri, "the project's homeserver");
            }
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

    /**
     * The text of a CA's certificate, as another machine's {@code admit} answers it: refused unless
     * it holds one, so a fault shows here rather than as every connection refused.
     */
    static @Nullable String caCertificate(final JsonNode value) throws Failure {
        if (value.isMissingNode() || value.isNull()) {
            return null;
        }
        final String text = value.isTextual() ? value.asText() : "";
        try {
            if (text.isBlank()
                    || CertificateFactory.getInstance("X.509")
                            .generateCertificates(
                                    new ByteArrayInputStream(
                                            text.getBytes(StandardCharsets.US_ASCII)))
                            .isEmpty()) {
                throw new Failure(Exit.CONFIG, "the project's ca_certificate holds no certificate");
            }
        } catch (final CertificateException ex) {
            throw new Failure(
                    Exit.CONFIG,
                    "the project's ca_certificate is not a certificate: " + ex.getMessage(),
                    ex);
        }
        return text;
    }

    /**
     * These settings with a {@code ca_certificate} written to a file of the transport's own, named
     * after its content, which the verbs after {@code setup} are handed as their CA file.
     */
    Settings materialized(final Map<String, String> env) throws Failure {
        final @Nullable Path tokenFile = inHome(registrationTokenFile, env);
        final @Nullable Path ca = inHome(caFile, env);
        if (caCertificate == null) {
            return new Settings(homeserver, ca, verifyTls, tokenFile, room, null);
        }
        try {
            final MessageDigest sha = MessageDigest.getInstance("SHA-256");
            final String name =
                    HexFormat.of()
                                    .formatHex(
                                            sha.digest(
                                                    caCertificate
                                                            .strip()
                                                            .getBytes(
                                                                    java.nio.charset
                                                                            .StandardCharsets
                                                                            .US_ASCII)))
                                    .substring(0, 16)
                            + ".crt";
            final Path dir = Poll.stateDirectory(env).resolve("ca");
            Files.createDirectories(dir);
            final Path file = dir.resolve(name);
            if (!Files.exists(file)) {
                final Path draft = Files.createTempFile(dir, ".ca-", ".draft");
                try {
                    Files.writeString(draft, caCertificate.strip(), StandardCharsets.US_ASCII);
                    Files.move(draft, file, StandardCopyOption.ATOMIC_MOVE);
                } finally {
                    Files.deleteIfExists(draft);
                }
            }
            return new Settings(homeserver, file, verifyTls, tokenFile, room, null);
        } catch (final IOException | NoSuchAlgorithmException ex) {
            throw new Failure(
                    Exit.CONFIG,
                    "cannot keep the project's ca_certificate: " + ex.getMessage(),
                    ex);
        }
    }

    /** The first name of a path in the running account's home: {@code ~}. */
    private static final Path HOME_PREFIX = Path.of("~");

    /** A path that begins with {@code ~/} made absolute in this account's home. */
    private static @Nullable Path inHome(final @Nullable Path path, final Map<String, String> env)
            throws Failure {
        if (path == null || !path.startsWith(HOME_PREFIX)) {
            return path;
        }
        final String home = env.get("HOME");
        if (home == null || !Path.of(home).isAbsolute()) {
            throw new Failure(
                    Exit.CONFIG,
                    "the project's settings name " + path + ", and HOME names no home");
        }
        return Path.of(home).resolve(path.subpath(1, path.getNameCount()));
    }

    static @Nullable Path caFile(final JsonNode value) throws Failure {
        return absolutePath(value, "ca_file");
    }

    static @Nullable Path registrationTokenFile(final JsonNode value) throws Failure {
        return absolutePath(value, "registration_token_file");
    }

    static @Nullable String room(final JsonNode value) throws Failure {
        if (value.isMissingNode() || value.isNull()) {
            return null;
        }
        final String text = value.isTextual() ? value.asText() : value.toString();
        if (!value.isTextual() || !ROOM.matcher(text).matches()) {
            throw new Failure(
                    Exit.CONFIG,
                    "the project's room must be a room alias (#local:server) or a room id"
                            + " (!opaque:server), not '"
                            + text
                            + "'");
        }
        return text;
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
        // In the running account's home, which the signed settings can name for every machine;
        // materialized makes it absolute.
        if (path.startsWith(HOME_PREFIX) && path.getNameCount() > 1) {
            for (final Path element : path) {
                if ("..".equals(element.toString())) {
                    throw new Failure(
                            Exit.CONFIG,
                            "the project's " + key + " must stay in the home, not '" + path + "'");
                }
            }
            return path;
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
