package org.fuin.porelo.message.matrix;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Where the homeserver is and the account's token, from the environment - never from an argument,
 * which every process on the machine can read.
 */
record Config(URI homeserver, String accessToken, @Nullable Path caFile, boolean verifyTls) {

    static final String HOMESERVER = "PORELO_MATRIX_HOMESERVER";

    static final String ACCESS_TOKEN = "PORELO_MATRIX_ACCESS_TOKEN";

    /** The provisioning account's token: handed only to the lifecycle verbs, never to poll. */
    static final String ADMIN_TOKEN = "PORELO_MATRIX_ADMIN_TOKEN";

    /**
     * The machine's secret on a central homeserver, from which every account's password there is
     * derived.
     */
    static final String MACHINE_SECRET = "PORELO_MATRIX_MACHINE_SECRET";

    /** The machine's name on a central homeserver, kept by setup for the verbs that follow. */
    static final String MACHINE = "PORELO_MATRIX_MACHINE";

    /** A homeserver's registration token, for one that is not the account's own. */
    static final String REGISTRATION_TOKEN = "PORELO_MATRIX_REGISTRATION_TOKEN";

    /**
     * Certificates trusted besides the built-in authorities: an intranet's CA, or a self-signed
     * server's own.
     */
    static final String CA_FILE = "PORELO_MATRIX_CA_FILE";

    /** {@code off} switches certificate checking off - for development alone. */
    static final String TLS_VERIFY = "PORELO_MATRIX_TLS_VERIFY";

    static final String VERIFY_OFF_WARNING =
            "WARNING: "
                    + TLS_VERIFY
                    + "=off - the homeserver's certificate is not"
                    + " checked, and whoever answers in its place receives the access token. For development only.";

    static Config from(final Map<String, String> env) throws Failure {
        final String homeserver = env.get(HOMESERVER);
        final String token = env.get(ACCESS_TOKEN);
        if (homeserver == null || homeserver.isBlank()) {
            throw new Failure(Exit.CONFIG, HOMESERVER + " is not set");
        }
        if (token == null || token.isBlank()) {
            throw new Failure(Exit.CONFIG, ACCESS_TOKEN + " is not set");
        }
        final URI uri = homeserver(homeserver);
        final String verify = env.getOrDefault(TLS_VERIFY, "on");
        if (!"on".equals(verify) && !"off".equals(verify)) {
            throw new Failure(
                    Exit.CONFIG, TLS_VERIFY + " must be 'on' or 'off', not '" + verify + "'");
        }
        final String caFile = env.get(CA_FILE);
        if (caFile != null && !caFile.isBlank() && "off".equals(verify)) {
            // Which of the two was meant cannot be told, so neither is guessed.
            throw new Failure(
                    Exit.CONFIG,
                    CA_FILE + " and " + TLS_VERIFY + "=off contradict each other; set one");
        }
        return new Config(
                uri,
                token,
                caFile == null || caFile.isBlank() ? null : Path.of(caFile),
                "on".equals(verify));
    }

    /**
     * The homeserver's URL as Porelo passes it: {@code https}, or {@code http} only on this
     * machine's loopback, since a token travels in every request to it.
     */
    static URI homeserver(final String homeserver) throws Failure {
        final URI uri;
        try {
            uri =
                    new URI(
                            homeserver.endsWith("/")
                                    ? homeserver.substring(0, homeserver.length() - 1)
                                    : homeserver);
        } catch (final URISyntaxException ex) {
            throw new Failure(Exit.CONFIG, HOMESERVER + " is not a URL: " + ex.getMessage(), ex);
        }
        final String scheme = uri.getScheme();
        if (!("http".equals(scheme) || "https".equals(scheme)) || uri.getHost() == null) {
            throw new Failure(
                    Exit.CONFIG,
                    HOMESERVER
                            + " must be an http or https URL with a host, not '"
                            + homeserver
                            + "'");
        }
        // The token travels in every request: in the clear only where it never leaves this machine.
        if ("http".equals(scheme) && !Lifecycle.loopback(uri)) {
            throw new Failure(
                    Exit.CONFIG,
                    HOMESERVER
                            + " "
                            + homeserver
                            + " is plain http on another host, where the token"
                            + " would travel readable; use https, or http only on this machine's loopback");
        }
        return uri;
    }

    @Override
    public String toString() {
        // A record prints every component by default, and this one holds a credential.
        return "Config[homeserver="
                + homeserver
                + ", caFile="
                + caFile
                + ", verifyTls="
                + verifyTls
                + "]";
    }
}
