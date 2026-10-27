package org.fuin.porelo.message.matrix;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The account's own homeserver published for another machine, against the real homeserver run as
 * the account's own: TLS with the account's CA beside the plain loopback listener this machine's
 * transport keeps using. The forwarding from the VPN's address is systemd's, measured on the VM.
 */
class LocalHomeserverIT {

    @TempDir private Path dir;

    private ProcessUnit unit;

    private Map<String, String> env;

    @BeforeEach
    void setUp() throws IOException, InterruptedException {
        final Path binary = ProcessUnit.binary(Files.createDirectories(dir.resolve("bin")));
        env = new HashMap<>();
        env.put("HOME", dir.resolve("home").toString());
        env.put("XDG_CONFIG_HOME", dir.resolve("config").toString());
        env.put("XDG_STATE_HOME", dir.resolve("state").toString());
        unit = new ProcessUnit(env, binary, dir.resolve("unit"));
    }

    @AfterEach
    void tearDown() throws Failure {
        unit.close();
    }

    /**
     * Harm guarded: a client on the other machine would hand its tokens to whoever answers, or the
     * listener refuses the client that holds the CA.
     */
    @Test
    @DisplayName(
            "Published, the homeserver serves TLS to a client with the account's CA, refuses one without it, and keeps its plain loopback listener")
    void servesTlsWithTheAccountsCa() throws Exception {
        final Path conf = Files.createDirectories(LocalHomeserver.directory(env));
        Files.writeString(
                conf.resolve("homeserver.conf"),
                LocalHomeserver.PUBLISH + "=127.0.0.1:8448\n",
                StandardCharsets.UTF_8);

        final JsonNode setup = lifecycle("setup", "--project", "demo");
        assertThat(setup.path("conversation").asText())
                .as("setup over the plain loopback listener, as before")
                .startsWith("!");

        final int port = LocalHomeserver.port(conf.resolve("homeserver.conf"));
        final URI https = URI.create("https://127.0.0.1:" + port);
        final Path ca = conf.resolve(Certificate.CA_CERTIFICATE);
        assertThat(
                        new MatrixClient(new Config(https, "", ca, true))
                                .exchange("GET", "/_matrix/client/versions", null)
                                .status())
                .as("a client with the CA file completes the handshake and is answered")
                .isEqualTo(200);
        assertThatThrownBy(
                        () ->
                                new MatrixClient(new Config(https, "", null, true))
                                        .exchange("GET", "/_matrix/client/versions", null))
                .as("a client without it refuses the certificate")
                .isInstanceOf(Failure.class)
                .extracting(failure -> ((Failure) failure).exitCode())
                .isEqualTo(Exit.CONFIG);
    }

    /**
     * Harm guarded: the program the unit runs before each start lacks what it needs to make the
     * certificate, and the published homeserver never starts on a machine.
     */
    @Test
    @DisplayName(
            "The transport's own program makes the CA and a server certificate for the published address before a start")
    void theProgramMakesTheCertificate() throws Exception {
        final Path conf = Files.createDirectories(LocalHomeserver.directory(env));
        Files.writeString(
                conf.resolve("homeserver.conf"),
                LocalHomeserver.PUBLISH + "=192.0.2.2:8448\n",
                StandardCharsets.UTF_8);

        final Transport.Result result = Transport.run(env, "homeserver-token");

        assertThat(result.exit()).as("homeserver-token: %s", result.err()).isEqualTo(Exit.OK);
        final java.security.cert.CertificateFactory x509 =
                java.security.cert.CertificateFactory.getInstance("X.509");
        final java.security.cert.X509Certificate ca =
                (java.security.cert.X509Certificate)
                        x509.generateCertificate(
                                Files.newInputStream(conf.resolve(Certificate.CA_CERTIFICATE)));
        final java.security.cert.X509Certificate server =
                (java.security.cert.X509Certificate)
                        x509.generateCertificate(
                                Files.newInputStream(
                                        conf.resolve(Certificate.TLS)
                                                .resolve(Certificate.SERVER_CERTIFICATE)));
        server.verify(ca.getPublicKey());
        assertThat(server.getSubjectAlternativeNames())
                .as("the server certificate the program made names the published address")
                .containsExactly(List.of(7, "192.0.2.2"));
        assertThat(Files.readString(conf.resolve(Publish.TLS_ENV)))
                .as("the program wrote the TLS settings the unit hands podman")
                .contains("TUWUNEL_TLS__DUAL_PROTOCOL=true");
    }

    /** Harm guarded: a machine that never publishes serves a listener nobody asked for. */
    @Test
    @DisplayName("Unpublished, the homeserver serves no TLS and its loopback listener is plain")
    void servesNoTlsUnpublished() throws Exception {
        lifecycle("setup", "--project", "demo");
        final Path conf = LocalHomeserver.directory(env);
        final int port = LocalHomeserver.port(conf.resolve("homeserver.conf"));

        assertThat(conf.resolve(Certificate.TLS).resolve(Certificate.SERVER_KEY))
                .as("no server key is made for a homeserver nobody else reaches")
                .doesNotExist();
        assertThatThrownBy(
                        () ->
                                new MatrixClient(
                                                new Config(
                                                        URI.create("https://127.0.0.1:" + port),
                                                        "",
                                                        null,
                                                        false))
                                        .exchange("GET", "/_matrix/client/versions", null))
                .as("nothing answers TLS on the loopback port")
                .isInstanceOf(Failure.class);
    }

    /**
     * Harm guarded: a dropped machine's token still registers accounts for whoever holds it, or
     * this machine's own setup breaks once other machines have tokens.
     */
    @Test
    @DisplayName(
            "A machine's token registers until it is revoked, while the local token and setup keep working")
    void aMachinesTokenRegistersUntilItIsRevoked() throws Exception {
        // As Porelo keeps it: the administrator's token from the first setup, for the next.
        lifecycle("setup", "--project", "demo")
                .path("account")
                .properties()
                .forEach(entry -> env.put(entry.getKey(), entry.getValue().asText()));
        final String local = LocalHomeserver.registrationToken(env);
        final int port =
                LocalHomeserver.port(LocalHomeserver.directory(env).resolve("homeserver.conf"));
        final MatrixClient nobody =
                new MatrixClient(
                        new Config(URI.create("http://127.0.0.1:" + port), "", null, true));

        final String token = MachineTokens.issue(env, "hosta");
        assertThat(MachineTokens.issue(env, "hosta"))
                .as("issued again, the machine keeps its token")
                .isEqualTo(token);
        assertThat(token).as("a token of its own").isNotEqualTo(local);
        assertThat(LocalHomeserver.registrationToken(env))
                .as("this machine's own token is still the one it reads")
                .isEqualTo(local);
        assertThat(Accounts.register(nobody, "porelo.hosta", Accounts.password(), token).userId())
                .as("the machine registers its account with its token")
                .startsWith("@porelo.hosta:");
        assertThat(lifecycle("setup", "--project", "demo").path("conversation").asText())
                .as("setup goes on with the local token beside the machine's")
                .startsWith("!");

        assertThat(MachineTokens.revoke(env, "hosta")).as("it had a token").isTrue();

        assertThatThrownBy(() -> Accounts.register(nobody, "intruder", Accounts.password(), token))
                .as("the revoked token registers nobody")
                .isInstanceOf(Failure.class);
        assertThat(Accounts.register(nobody, "worker", Accounts.password(), local).userId())
                .as("the local token still registers")
                .startsWith("@worker:");
    }

    private JsonNode lifecycle(final String... args) throws Failure, IOException {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        Lifecycle.run(
                args[0],
                List.of(args).subList(1, args.length),
                env,
                new ByteArrayInputStream(new byte[0]),
                new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(OutputStream.nullOutputStream()),
                unit);
        return MatrixClient.JSON.readTree(out.toByteArray());
    }
}
