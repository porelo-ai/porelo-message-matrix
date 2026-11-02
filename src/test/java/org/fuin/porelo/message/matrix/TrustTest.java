package org.fuin.porelo.message.matrix;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.X509ExtendedTrustManager;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Each way the platform's TLS asks whom to trust. The HTTP client takes only the engine's, which
 * {@link TlsTest} holds against a real server; the others are asked only by code that may come.
 */
class TrustTest {

    private static final X509Certificate[] CHAIN = new X509Certificate[0];

    /** One way of asking, and what it should reach in the manager behind it. */
    private enum Asked {
        SOCKET {
            @Override
            void of(final X509ExtendedTrustManager manager, final X509Certificate[] chain)
                    throws CertificateException {
                manager.checkServerTrusted(chain, "RSA", new Socket());
            }
        },
        ENGINE {
            @Override
            void of(final X509ExtendedTrustManager manager, final X509Certificate[] chain)
                    throws Exception {
                manager.checkServerTrusted(chain, "RSA", SSLContext.getDefault().createSSLEngine());
            }
        },
        PLAIN {
            @Override
            void of(final X509ExtendedTrustManager manager, final X509Certificate[] chain)
                    throws CertificateException {
                manager.checkServerTrusted(chain, "RSA");
            }
        };

        abstract void of(X509ExtendedTrustManager manager, X509Certificate[] chain)
                throws Exception;
    }

    /** A source of trust that says what it was told to, and records how it was asked. */
    private static final class Source extends X509ExtendedTrustManager {

        private final boolean trusts;

        private final List<Asked> asked = new ArrayList<>();

        Source(final boolean trusts) {
            this.trusts = trusts;
        }

        private void answer(final Asked how) throws CertificateException {
            asked.add(how);
            if (!trusts) {
                throw new CertificateException("unknown to this source");
            }
        }

        @Override
        public void checkServerTrusted(
                final X509Certificate[] chain, final String authType, final Socket socket)
                throws CertificateException {
            answer(Asked.SOCKET);
        }

        @Override
        public void checkServerTrusted(
                final X509Certificate[] chain, final String authType, final SSLEngine engine)
                throws CertificateException {
            answer(Asked.ENGINE);
        }

        @Override
        public void checkServerTrusted(final X509Certificate[] chain, final String authType)
                throws CertificateException {
            answer(Asked.PLAIN);
        }

        @Override
        public void checkClientTrusted(
                final X509Certificate[] chain, final String authType, final Socket socket) {
            throw new AssertionError("a client was asked about");
        }

        @Override
        public void checkClientTrusted(
                final X509Certificate[] chain, final String authType, final SSLEngine engine) {
            throw new AssertionError("a client was asked about");
        }

        @Override
        public void checkClientTrusted(final X509Certificate[] chain, final String authType) {
            throw new AssertionError("a client was asked about");
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return CHAIN;
        }
    }

    @TempDir static Path dir;

    private static X509Certificate first;

    private static X509Certificate second;

    private static X509Certificate stranger;

    private static Path bothAuthorities;

    @BeforeAll
    static void makeCertificates() throws Exception {
        first = certificate("first");
        second = certificate("second");
        stranger = certificate("stranger");
        bothAuthorities = dir.resolve("both.pem");
        Files.write(bothAuthorities, Files.readAllBytes(dir.resolve("first.pem")));
        Files.write(
                bothAuthorities,
                Files.readAllBytes(dir.resolve("second.pem")),
                StandardOpenOption.APPEND);
    }

    private static X509Certificate certificate(final String name) throws Exception {
        final Path store = dir.resolve(name + ".p12");
        final Path pem = dir.resolve(name + ".pem");
        keytool(
                "-genkeypair",
                "-alias",
                name,
                "-keyalg",
                "RSA",
                "-keysize",
                "2048",
                "-dname",
                "CN=" + name,
                "-ext",
                "BC:critical=ca:true",
                "-validity",
                "2",
                "-storetype",
                "PKCS12",
                "-keystore",
                store.toString(),
                "-storepass",
                "fixture");
        keytool(
                "-exportcert",
                "-rfc",
                "-alias",
                name,
                "-keystore",
                store.toString(),
                "-storepass",
                "fixture",
                "-file",
                pem.toString());
        try (var in = Files.newInputStream(pem)) {
            return (X509Certificate)
                    CertificateFactory.getInstance("X.509").generateCertificate(in);
        }
    }

    private static void keytool(final String... args) throws IOException, InterruptedException {
        final List<String> command = new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "keytool").toString());
        command.addAll(List.of(args));
        final Process process =
                new ProcessBuilder(command)
                        .redirectErrorStream(true)
                        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                        .start();
        assertThat(process.waitFor()).as("keytool %s", args[0]).isZero();
    }

    private static X509Certificate[] chain(final X509Certificate certificate) {
        return new X509Certificate[] {certificate};
    }

    /** Harm guarded: a way of asking that passes a server neither source knows gets the token. */
    @ParameterizedTest
    @EnumSource(Asked.class)
    void neitherSourceKnowingTheServerIsARefusal(final Asked how) {
        final Source platform = new Source(false);
        final Source file = new Source(false);

        assertThatThrownBy(() -> how.of(new Trust.Either(platform, file), CHAIN))
                .isInstanceOf(CertificateException.class)
                .hasSuppressedException(new CertificateException("unknown to this source"));

        assertThat(platform.asked).as("the platform, the same way").containsExactly(how);
        assertThat(file.asked).as("the file, the same way").containsExactly(how);
    }

    /** Harm guarded: an intranet's server is refused though its authority is in the file. */
    @ParameterizedTest
    @EnumSource(Asked.class)
    void theSecondSourceKnowingTheServerIsEnough(final Asked how) {
        final Source file = new Source(true);

        assertThatCode(() -> how.of(new Trust.Either(new Source(false), file), CHAIN))
                .doesNotThrowAnyException();

        assertThat(file.asked).as("the file, the same way").containsExactly(how);
    }

    /** Harm guarded: a public certificate is refused because a CA file is set as well. */
    @ParameterizedTest
    @EnumSource(Asked.class)
    void theFirstSourceKnowingTheServerIsEnough(final Asked how) {
        final Source platform = new Source(true);
        final Source file = new Source(false);

        assertThatCode(() -> how.of(new Trust.Either(platform, file), CHAIN))
                .doesNotThrowAnyException();

        assertThat(platform.asked).as("the platform, the same way").containsExactly(how);
        assertThat(file.asked).as("not asked once one has said yes").isEmpty();
    }

    /** Harm guarded: a file of several authorities trusts only one, and a server there is lost. */
    @Test
    void aFileOfTwoAuthoritiesTrustsEach() throws Exception {
        final X509ExtendedTrustManager manager = Trust.fromFile(bothAuthorities);

        assertThatCode(() -> manager.checkServerTrusted(chain(first), "RSA"))
                .as("the first in the file")
                .doesNotThrowAnyException();
        assertThatCode(() -> manager.checkServerTrusted(chain(second), "RSA"))
                .as("the second in the file")
                .doesNotThrowAnyException();
    }

    /** Harm guarded: the real sources together pass a certificate neither has heard of. */
    @Test
    void theRealSourcesRefuseAStrangerAndKnowTheFilesServer() throws Exception {
        final X509ExtendedTrustManager manager =
                new Trust.Either(Trust.platform(), Trust.fromFile(bothAuthorities));

        assertThatThrownBy(() -> manager.checkServerTrusted(chain(stranger), "RSA"))
                .as("plain")
                .isInstanceOf(CertificateException.class);
        assertThatThrownBy(() -> manager.checkServerTrusted(chain(stranger), "RSA", new Socket()))
                .as("by socket")
                .isInstanceOf(CertificateException.class);
        assertThatCode(() -> manager.checkServerTrusted(chain(second), "RSA", new Socket()))
                .as("by socket, the file's")
                .doesNotThrowAnyException();
    }

    /** Harm guarded: a TLS client told of no issuer, or of only one source's, picks no key. */
    @Test
    void theIssuersAreBothSources() throws Exception {
        final X509Certificate[] issuers =
                new Trust.Either(Trust.fromFile(bothAuthorities), new Source(false))
                        .getAcceptedIssuers();

        assertThat(issuers).as("the file's").contains(first, second);
        assertThat(
                        new Trust.Either(new Source(false), Trust.fromFile(bothAuthorities))
                                .getAcceptedIssuers())
                .as("whichever comes second")
                .contains(first, second);
    }
}
