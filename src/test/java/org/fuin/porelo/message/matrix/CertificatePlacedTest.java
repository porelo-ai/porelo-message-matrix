package org.fuin.porelo.message.matrix;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A server certificate the host's administrator made elsewhere and placed beside the configuration.
 */
class CertificatePlacedTest {

    private static final InetAddress VPN = InetAddress.ofLiteral("192.0.2.2");

    private static final String NAME = "matrix.h.example";

    private static final String COVERING = "dns:*.h.example,ip:192.0.2.2";

    private static final Instant NOW = Instant.parse("2026-10-27T12:00:00Z");

    @TempDir private Path work;

    private Path dir;

    private PlacedCertificate ca;

    @BeforeEach
    void setUp() throws Exception {
        dir = Files.createDirectories(work.resolve("conf"));
        ca = new PlacedCertificate(work.resolve("ca"), NOW);
    }

    /**
     * Harm guarded: the transport replaces the host's certificate with one of its own CA, whose key
     * then lies on the host, and every machine that trusts the host's CA refuses the homeserver.
     */
    @Test
    @DisplayName(
            "A placed certificate that covers the name by a wildcard is kept as it is, and no CA key is made")
    void keepsAPlacedCertificate() throws Exception {
        ca.place(dir, COVERING, NOW.minus(Duration.ofDays(1)), 90);
        final Map<String, String> before = files();

        assertThat(Certificate.ensure(dir, VPN, NAME, NOW)).as("nothing is made anew").isFalse();

        assertThat(files())
                .as("the placed CA certificate, server certificate and key are unchanged")
                .containsExactlyInAnyOrderEntriesOf(before);
        assertThat(dir.resolve("ca.key")).as("no CA of the transport's own").doesNotExist();
    }

    /**
     * Harm guarded: an ended certificate is served on, or silently replaced by one of the
     * transport's own.
     */
    @Test
    @DisplayName("A placed certificate that has ended is refused in words, never replaced")
    void refusesAnEndedCertificate() throws Exception {
        ca.place(dir, COVERING, NOW.minus(Duration.ofDays(100)), 90);
        final Map<String, String> before = files();

        assertThatThrownBy(() -> Certificate.ensure(dir, VPN, NAME, NOW))
                .as("an ended certificate stops the start")
                .isInstanceOf(Failure.class)
                .hasMessageContaining("ended");

        assertThat(files()).as("nothing replaced").containsExactlyInAnyOrderEntriesOf(before);
        assertThat(dir.resolve("ca.key")).as("no CA of the transport's own").doesNotExist();
    }

    /**
     * Harm guarded: a certificate another CA signed is served, which no machine trusting ca.crt
     * accepts.
     */
    @Test
    @DisplayName("A placed certificate that the placed CA did not sign is refused, never replaced")
    void refusesACertificateOfAnotherCa() throws Exception {
        ca.place(dir, COVERING, NOW.minus(Duration.ofDays(1)), 90);
        Files.copy(
                new PlacedCertificate(work.resolve("other"), NOW).caCertificate(),
                dir.resolve(Certificate.CA_CERTIFICATE),
                StandardCopyOption.REPLACE_EXISTING);
        final Map<String, String> before = files();

        assertThatThrownBy(() -> Certificate.ensure(dir, VPN, NAME, NOW))
                .as("signed by another CA")
                .isInstanceOf(Failure.class)
                .hasMessageContaining("not signed by");

        assertThat(files()).as("nothing replaced").containsExactlyInAnyOrderEntriesOf(before);
    }

    /**
     * Harm guarded: a certificate for another host is served, and clients refuse the homeserver.
     */
    @Test
    @DisplayName(
            "A placed certificate that covers another name or another address is refused, never replaced")
    void refusesACertificateForAnotherHost() throws Exception {
        ca.place(dir, "dns:*.other.example,ip:192.0.2.2", NOW.minus(Duration.ofDays(1)), 90);

        assertThatThrownBy(() -> Certificate.ensure(dir, VPN, NAME, NOW))
                .as("another name")
                .isInstanceOf(Failure.class)
                .hasMessageContaining("does not cover " + NAME);

        ca.place(dir, "dns:*.h.example,ip:192.0.2.9", NOW.minus(Duration.ofDays(1)), 90);

        assertThatThrownBy(() -> Certificate.ensure(dir, VPN, NAME, NOW))
                .as("another address")
                .isInstanceOf(Failure.class)
                .hasMessageContaining("does not cover 192.0.2.2");
        assertThat(dir.resolve("ca.key")).as("no CA of the transport's own").doesNotExist();
    }

    /**
     * Harm guarded: a wildcard taken to cover more than one label serves a name it does not hold.
     */
    @Test
    @DisplayName("A wildcard covers one label only")
    void aWildcardCoversOneLabel() throws Exception {
        ca.place(dir, COVERING, NOW.minus(Duration.ofDays(1)), 90);

        assertThatThrownBy(() -> Certificate.ensure(dir, VPN, "a.matrix.h.example", NOW))
                .as("two labels under the wildcard")
                .isInstanceOf(Failure.class)
                .hasMessageContaining("does not cover a.matrix.h.example");
    }

    /**
     * Harm guarded: the host's certificate ends unnoticed, and every machine loses the homeserver.
     */
    @Test
    @DisplayName("A placed certificate in its last 30 days is kept and said to end")
    void saysWhenAPlacedCertificateEnds() throws Exception {
        ca.place(dir, COVERING, NOW.minus(Duration.ofDays(80)), 90);

        assertThat(Certificate.ensure(dir, VPN, NAME, NOW)).as("kept").isFalse();
        assertThat(Certificate.notice(dir, NOW))
                .as("the notice names the end")
                .contains("2026-11-06");
        ca.place(dir, COVERING, NOW.minus(Duration.ofDays(1)), 90);
        assertThat(Certificate.notice(dir, NOW)).as("no notice far from the end").isNull();
    }

    /** Harm guarded: publish --off deletes the host administrator's certificate and key. */
    @Test
    @DisplayName("Removing the server certificate leaves a placed one alone")
    void removingLeavesAPlacedCertificate() throws Exception {
        ca.place(dir, COVERING, NOW.minus(Duration.ofDays(1)), 90);
        final Map<String, String> before = files();

        Certificate.removeServer(dir);

        assertThat(files()).as("placed files kept").containsExactlyInAnyOrderEntriesOf(before);
    }

    private Map<String, String> files() throws Exception {
        return Map.of(
                "ca.crt", Files.readString(dir.resolve(Certificate.CA_CERTIFICATE)),
                "server.crt",
                        Files.readString(
                                dir.resolve(Certificate.TLS)
                                        .resolve(Certificate.SERVER_CERTIFICATE)),
                "server.key",
                        Files.readString(
                                dir.resolve(Certificate.TLS).resolve(Certificate.SERVER_KEY)));
    }
}
