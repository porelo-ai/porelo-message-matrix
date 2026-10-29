package org.fuin.porelo.message.matrix;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.KeyFactory;
import java.security.KeyStore;
import java.security.cert.CertPathValidator;
import java.security.cert.CertificateFactory;
import java.security.cert.PKIXParameters;
import java.security.cert.TrustAnchor;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManagerFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CertificateTest {

    private static final InetAddress VPN = InetAddress.ofLiteral("192.0.2.2");

    private static final InetAddress OTHER = InetAddress.ofLiteral("192.0.2.3");

    private static final Instant NOW = Instant.parse("2026-10-27T12:00:00Z");

    @TempDir private Path dir;

    /** Harm guarded: whoever reads the container's mount could sign certificates as this CA. */
    @Test
    @DisplayName("The CA's key stays outside the directory the container mounts")
    void keepsTheCaKeyOutOfTheMount() throws Exception {
        Certificate.ensure(dir, VPN, NOW);

        try (Stream<Path> mounted = Files.list(dir.resolve(Certificate.TLS))) {
            assertThat(mounted.map(path -> path.getFileName().toString()))
                    .as("only the server's certificate and key are in the mounted directory")
                    .containsExactlyInAnyOrder(
                            Certificate.SERVER_CERTIFICATE, Certificate.SERVER_KEY);
        }
        assertThat(dir.resolve("ca.key")).as("the CA's key beside the configuration").exists();
    }

    /** Harm guarded: another account of the machine reads a private key. */
    @Test
    @DisplayName("Both private keys and their directories are readable by their owner alone")
    void keepsTheKeysToTheirOwner() throws Exception {
        Certificate.ensure(dir, VPN, NOW);

        for (final Path key : List.of(dir.resolve("ca.key"), serverKey())) {
            assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(key)))
                    .as("%s is the owner's alone", key)
                    .isEqualTo("rw-------");
        }
        for (final Path directory : List.of(dir, dir.resolve(Certificate.TLS))) {
            assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(directory)))
                    .as("%s can be entered by its owner alone", directory)
                    .isEqualTo("rwx------");
        }
    }

    /** Harm guarded: a key another account could read is used instead of refused. */
    @Test
    @DisplayName("A CA key readable by others is refused, not used")
    void refusesACaKeyOthersCanRead() throws Exception {
        Certificate.ensure(dir, VPN, NOW);
        Files.setPosixFilePermissions(
                dir.resolve("ca.key"), PosixFilePermissions.fromString("rw-r--r--"));

        assertThatThrownBy(() -> Certificate.ensure(dir, OTHER, NOW))
                .as("a CA key others can read signs nothing")
                .isInstanceOf(Failure.class)
                .hasMessageContaining("readable by more than its owner")
                .extracting(failure -> ((Failure) failure).exitCode())
                .isEqualTo(Exit.CONFIG);
    }

    /** Harm guarded: a new CA on every start locks out every machine that trusts the old one. */
    @Test
    @DisplayName("The CA is made once and kept through later runs and renewals")
    void keepsTheCa() throws Exception {
        Certificate.ensure(dir, VPN, NOW);
        final String first = Certificate.caCertificate(dir);

        Certificate.ensure(dir, OTHER, NOW.plus(Certificate.LIFETIME));

        assertThat(Certificate.caCertificate(dir))
                .as("the CA the other machines name is the same")
                .isEqualTo(first);
    }

    /**
     * Harm guarded: a CA certificate without its key leaves nothing to sign renewals with, and
     * replacing it would silently lock out every machine that trusts it.
     */
    @Test
    @DisplayName(
            "A CA certificate whose key is gone is never replaced: its server certificate holds until it ends, then is refused")
    void refusesACaWithoutItsKey() throws Exception {
        Certificate.ensure(dir, VPN, NOW);
        final String ca = Files.readString(dir.resolve("ca.crt"));
        Files.delete(dir.resolve("ca.key"));

        assertThat(Certificate.ensure(dir, VPN, NOW))
                .as("the server certificate is kept while it holds")
                .isFalse();
        assertThatThrownBy(() -> Certificate.ensure(dir, VPN, NOW.plus(Certificate.LIFETIME)))
                .as("once it ends nothing here can sign another")
                .isInstanceOf(Failure.class)
                .hasMessageContaining("ended");
        assertThat(Files.readString(dir.resolve("ca.crt")))
                .as("the CA certificate the other machines name is unchanged")
                .isEqualTo(ca);
        Files.delete(dir.resolve(Certificate.TLS).resolve(Certificate.SERVER_CERTIFICATE));
        assertThatThrownBy(() -> Certificate.ensure(dir, VPN, NOW))
                .as("without a server certificate the CA's key is needed, and refused missing")
                .isInstanceOf(Failure.class)
                .hasMessageContaining("is missing");
    }

    /** Harm guarded: server certificates are signed with a key no machine's CA file verifies. */
    @Test
    @DisplayName("A CA key that does not fit the CA certificate is refused, not signed with")
    void refusesACaKeyThatDoesNotFit() throws Exception {
        Certificate.ensure(dir, VPN, NOW);
        final Path other = Files.createDirectory(dir.resolve("other"));
        Certificate.ensure(other, VPN, NOW);
        Files.copy(
                other.resolve("ca.key"),
                dir.resolve("ca.key"),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING);

        assertThatThrownBy(() -> Certificate.ensure(dir, OTHER, NOW))
                .as("a server certificate signed so would be refused by every machine")
                .isInstanceOf(Failure.class)
                .hasMessageContaining("does not fit")
                .extracting(failure -> ((Failure) failure).exitCode())
                .isEqualTo(Exit.CONFIG);
    }

    /** Harm guarded: the other machine refuses the listener once the certificate has run out. */
    @Test
    @DisplayName("A server certificate is made anew within its last 30 days, not before")
    void renewsWithinTheLastThirtyDays() throws Exception {
        assertThat(Certificate.ensure(dir, VPN, NOW)).as("the first run makes it").isTrue();
        final X509Certificate first = server();
        final Instant end = first.getNotAfter().toInstant();

        assertThat(
                        Certificate.ensure(
                                dir, VPN, end.minus(Certificate.RENEW_WITHIN).minusSeconds(60)))
                .as("31 days before its end it is kept")
                .isFalse();
        assertThat(
                        Certificate.ensure(
                                dir, VPN, end.minus(Certificate.RENEW_WITHIN).plusSeconds(60)))
                .as("within its last 30 days it is made anew")
                .isTrue();
        assertThat(server().getNotAfter().toInstant())
                .as("the new one holds a year from then")
                .isAfter(end);
    }

    /** Harm guarded: the other machine refuses a certificate naming the old address. */
    @Test
    @DisplayName("A server certificate for another address is made anew for the one published")
    void followsTheAddress() throws Exception {
        Certificate.ensure(dir, VPN, NOW);

        assertThat(Certificate.ensure(dir, OTHER, NOW)).as("a new address remakes it").isTrue();

        assertThat(server().getSubjectAlternativeNames())
                .as("it names the new address alone")
                .containsExactly(List.of(7, "192.0.2.3"));
    }

    /** Harm guarded: a server key that fits no certificate leaves the listener refusing all. */
    @Test
    @DisplayName("A server key that does not fit its certificate is made anew with it")
    void remakesAKeyThatDoesNotFit() throws Exception {
        Certificate.ensure(dir, VPN, NOW);
        final Path other = Files.createDirectory(dir.resolve("other"));
        Certificate.ensure(other, VPN, NOW);
        Files.copy(
                other.resolve(Certificate.TLS).resolve(Certificate.SERVER_KEY),
                serverKey(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING);

        assertThat(Certificate.ensure(dir, VPN, NOW)).as("a stray key is replaced").isTrue();

        assertThat(fits()).as("the key and certificate served belong together").isTrue();
    }

    /**
     * Harm guarded: the other machine's transport, which checks the chain and the address, refuses
     * every connection.
     */
    @Test
    @DisplayName("The server certificate validates against the CA file alone and names the address")
    void validatesAgainstTheCaFile() throws Exception {
        Certificate.ensure(dir, VPN, NOW);
        final X509Certificate ca = certificate(dir.resolve(Certificate.CA_CERTIFICATE));
        final X509Certificate server = server();

        final PKIXParameters parameters = new PKIXParameters(Set.of(new TrustAnchor(ca, null)));
        parameters.setRevocationEnabled(false);
        parameters.setDate(Date.from(NOW.plusSeconds(60)));
        CertPathValidator.getInstance("PKIX")
                .validate(
                        CertificateFactory.getInstance("X.509").generateCertPath(List.of(server)),
                        parameters);

        assertThat(ca.getBasicConstraints()).as("the CA signs servers only").isZero();
        assertThat(server.getBasicConstraints()).as("the server is no CA").isEqualTo(-1);
        assertThat(server.getExtendedKeyUsage())
                .as("for a TLS server")
                .containsExactly("1.3.6.1.5.5.7.3.1");
        assertThat(server.getSubjectAlternativeNames())
                .as("the published address alone")
                .containsExactly(List.of(7, "192.0.2.2"));
        assertThat(server.getCriticalExtensionOIDs())
                .as("the names are not critical beside a subject, as a strict client demands")
                .doesNotContain("2.5.29.17");
    }

    /** Harm guarded: a client without the CA would hand its token to whoever answers. */
    @Test
    @DisplayName("A TLS client completes the handshake with the CA file and is refused without it")
    void servesTlsOnlyToAClientWithTheCa() throws Exception {
        final InetAddress loopback = InetAddress.ofLiteral("127.0.0.1");
        Certificate.ensure(dir, loopback, Instant.now());
        final SSLContext server = SSLContext.getInstance("TLSv1.3");
        final KeyStore keys = KeyStore.getInstance("PKCS12");
        keys.load(null, null);
        keys.setKeyEntry(
                "server",
                KeyFactory.getInstance("EC")
                        .generatePrivate(
                                new PKCS8EncodedKeySpec(pemBody(Files.readString(serverKey())))),
                new char[0],
                new java.security.cert.Certificate[] {server()});
        final KeyManagerFactory managers = KeyManagerFactory.getInstance("PKIX");
        managers.init(keys, new char[0]);
        server.init(managers.getKeyManagers(), null, null);

        try (SSLServerSocket socket =
                (SSLServerSocket) server.getServerSocketFactory().createServerSocket()) {
            socket.bind(new InetSocketAddress(loopback, 0));
            final CompletableFuture<Void> accepting =
                    CompletableFuture.runAsync(
                            () -> {
                                for (int round = 0; round < 2; round++) {
                                    try (SSLSocket accepted = (SSLSocket) socket.accept()) {
                                        accepted.startHandshake();
                                        accepted.getOutputStream().write('!');
                                    } catch (final IOException ex) {
                                        // The client without the CA ends its handshake.
                                    }
                                }
                            });

            assertThat(
                            handshake(
                                    socket.getLocalPort(),
                                    trusting(certificate(dir.resolve("ca.crt")))))
                    .as("the client with the CA file reads what the server wrote")
                    .isEqualTo('!');
            assertThatThrownBy(() -> handshake(socket.getLocalPort(), SSLContext.getDefault()))
                    .as("the client without it refuses the server")
                    .isInstanceOf(SSLHandshakeException.class);
            accepting.get(10, TimeUnit.SECONDS);
        }
    }

    private static int handshake(final int port, final SSLContext client) throws IOException {
        try (SSLSocket socket =
                (SSLSocket) client.getSocketFactory().createSocket("127.0.0.1", port)) {
            final var parameters = socket.getSSLParameters();
            // As the transport's HTTP client does for an address: the IP SAN is what is checked.
            parameters.setEndpointIdentificationAlgorithm("HTTPS");
            parameters.setServerNames(List.<javax.net.ssl.SNIServerName>of());
            socket.setSSLParameters(parameters);
            socket.setSoTimeout(5000);
            socket.startHandshake();
            final InputStream in = socket.getInputStream();
            return in.read();
        }
    }

    private static SSLContext trusting(final X509Certificate ca) throws Exception {
        final KeyStore trust = KeyStore.getInstance("PKCS12");
        trust.load(null, null);
        trust.setCertificateEntry("ca", ca);
        final TrustManagerFactory managers = TrustManagerFactory.getInstance("PKIX");
        managers.init(trust);
        final SSLContext context = SSLContext.getInstance("TLSv1.3");
        context.init(null, managers.getTrustManagers(), null);
        return context;
    }

    private boolean fits() throws Exception {
        final var key =
                KeyFactory.getInstance("EC")
                        .generatePrivate(
                                new PKCS8EncodedKeySpec(pemBody(Files.readString(serverKey()))));
        final var signer = java.security.Signature.getInstance("SHA256withECDSA");
        signer.initSign(key);
        signer.update(new byte[] {1, 2, 3});
        final byte[] signed = signer.sign();
        final var verifier = java.security.Signature.getInstance("SHA256withECDSA");
        verifier.initVerify(server().getPublicKey());
        verifier.update(new byte[] {1, 2, 3});
        return verifier.verify(signed);
    }

    private Path serverKey() {
        return dir.resolve(Certificate.TLS).resolve(Certificate.SERVER_KEY);
    }

    private X509Certificate server() throws Exception {
        return certificate(dir.resolve(Certificate.TLS).resolve(Certificate.SERVER_CERTIFICATE));
    }

    private static X509Certificate certificate(final Path file) throws Exception {
        return (X509Certificate)
                CertificateFactory.getInstance("X.509")
                        .generateCertificate(new ByteArrayInputStream(Files.readAllBytes(file)));
    }

    private static byte[] pemBody(final String pem) {
        return Base64.getDecoder()
                .decode(
                        pem.replace("-----BEGIN PRIVATE KEY-----", "")
                                .replace("-----END PRIVATE KEY-----", "")
                                .replaceAll("\\s", "")
                                .getBytes(StandardCharsets.US_ASCII));
    }

    /**
     * Harm guarded: a machine that reaches the homeserver by its name refuses the certificate, or
     * one for an older name is served on.
     */
    @Test
    @DisplayName(
            "The server certificate names the published name beside the address, and is made anew when it changes")
    void namesThePublishedName() throws Exception {
        Certificate.ensure(dir, VPN, "matrix.porelo.internal", NOW);

        assertThat(server().getSubjectAlternativeNames())
                .as("the address and the name")
                .containsExactlyInAnyOrder(
                        List.of(7, "192.0.2.2"), List.of(2, "matrix.porelo.internal"));
        assertThat(Certificate.ensure(dir, VPN, "matrix.porelo.internal", NOW))
                .as("the same name keeps it")
                .isFalse();
        assertThat(Certificate.ensure(dir, VPN, null, NOW))
                .as("without the name it is made anew")
                .isTrue();
        assertThat(Certificate.ensure(dir, VPN, "matrix.porelo.internal", NOW))
                .as("a name published later makes it anew with the name")
                .isTrue();
        assertThat(Certificate.ensure(dir, VPN, "matrix.example.org", NOW))
                .as("another name makes it anew")
                .isTrue();
        assertThat(server().getSubjectAlternativeNames())
                .as("the new name alone")
                .containsExactlyInAnyOrder(
                        List.of(7, "192.0.2.2"), List.of(2, "matrix.example.org"));
    }
}
