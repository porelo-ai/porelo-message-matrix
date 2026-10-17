package org.fuin.porelo.message.matrix;

import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509ExtendedTrustManager;

import org.jspecify.annotations.Nullable;

/**
 * Which homeservers the transport believes to be who they say. By default the authorities built into the
 * executable - a public certificate. With a CA file, the certificates in it as well - an intranet's own
 * authority, or a server's self-signed certificate. Checking switched off trusts every server, which is
 * for development alone: whoever answers gets the token.
 */
final class Trust {

    private Trust() {
    }

    /** The TLS context for the configuration, or null for the platform's default. */
    static @Nullable SSLContext context(final Config config) throws Failure {
        try {
            if (!config.verifyTls()) {
                final SSLContext context = SSLContext.getInstance("TLS");
                context.init(null, new TrustManager[] {new TrustingEveryone()}, null);
                return context;
            }
            final Path caFile = config.caFile();
            if (caFile == null) {
                return null;
            }
            final SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, new TrustManager[] {new Either(platform(), fromFile(caFile))}, null);
            return context;
        } catch (final GeneralSecurityException ex) {
            throw new Failure(Exit.CONFIG, "cannot set up TLS: " + ex.getMessage(), ex);
        }
    }

    private static X509ExtendedTrustManager platform() throws GeneralSecurityException {
        final TrustManagerFactory factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        factory.init((KeyStore) null);
        return extended(factory);
    }

    private static X509ExtendedTrustManager fromFile(final Path file) throws Failure, GeneralSecurityException {
        final Collection<? extends Certificate> certificates;
        try (InputStream in = Files.newInputStream(file)) {
            certificates = CertificateFactory.getInstance("X.509").generateCertificates(in);
        } catch (final IOException ex) {
            throw new Failure(Exit.CONFIG, "cannot read " + Config.CA_FILE + " " + file + ": " + ex.getMessage(), ex);
        } catch (final CertificateException ex) {
            throw new Failure(Exit.CONFIG, Config.CA_FILE + " " + file + " holds no readable certificate: " + ex.getMessage(), ex);
        }
        if (certificates.isEmpty()) {
            throw new Failure(Exit.CONFIG, Config.CA_FILE + " " + file + " holds no certificate");
        }
        final KeyStore store = KeyStore.getInstance(KeyStore.getDefaultType());
        try {
            store.load(null, null);
        } catch (final IOException ex) {
            throw new IllegalStateException("An empty key store could not be made", ex);
        }
        int i = 0;
        for (final Certificate certificate : certificates) {
            store.setCertificateEntry("ca-" + i++, certificate);
        }
        final TrustManagerFactory factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        factory.init(store);
        return extended(factory);
    }

    private static X509ExtendedTrustManager extended(final TrustManagerFactory factory) {
        for (final TrustManager manager : factory.getTrustManagers()) {
            if (manager instanceof X509ExtendedTrustManager x509) {
                return x509;
            }
        }
        throw new IllegalStateException("The platform offers no X.509 trust manager");
    }

    /**
     * Trusts a server either of two managers trusts. Both are the platform's own, which check the server's
     * name against its certificate as well - so a CA file widens whom to trust, never how carefully.
     */
    private static final class Either extends X509ExtendedTrustManager {

        private final X509ExtendedTrustManager first;

        private final X509ExtendedTrustManager second;

        Either(final X509ExtendedTrustManager first, final X509ExtendedTrustManager second) {
            this.first = first;
            this.second = second;
        }

        private interface Check {
            void check(X509ExtendedTrustManager manager) throws CertificateException;
        }

        private void either(final Check check) throws CertificateException {
            try {
                check.check(first);
            } catch (final CertificateException ex) {
                try {
                    check.check(second);
                } catch (final CertificateException second) {
                    second.addSuppressed(ex);
                    throw second;
                }
            }
        }

        @Override
        public void checkServerTrusted(final X509Certificate[] chain, final String authType, final Socket socket)
                throws CertificateException {
            either(m -> m.checkServerTrusted(chain, authType, socket));
        }

        @Override
        public void checkServerTrusted(final X509Certificate[] chain, final String authType, final SSLEngine engine)
                throws CertificateException {
            either(m -> m.checkServerTrusted(chain, authType, engine));
        }

        @Override
        public void checkServerTrusted(final X509Certificate[] chain, final String authType) throws CertificateException {
            either(m -> m.checkServerTrusted(chain, authType));
        }

        @Override
        public void checkClientTrusted(final X509Certificate[] chain, final String authType, final Socket socket)
                throws CertificateException {
            throw new CertificateException("The transport is never a server");
        }

        @Override
        public void checkClientTrusted(final X509Certificate[] chain, final String authType, final SSLEngine engine)
                throws CertificateException {
            throw new CertificateException("The transport is never a server");
        }

        @Override
        public void checkClientTrusted(final X509Certificate[] chain, final String authType) throws CertificateException {
            throw new CertificateException("The transport is never a server");
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            final List<X509Certificate> issuers = new ArrayList<>(List.of(first.getAcceptedIssuers()));
            issuers.addAll(List.of(second.getAcceptedIssuers()));
            return issuers.toArray(X509Certificate[]::new);
        }

    }

    /** Checking switched off: every certificate, for every name. For development alone. */
    private static final class TrustingEveryone extends X509ExtendedTrustManager {

        @Override
        public void checkServerTrusted(final X509Certificate[] chain, final String authType, final Socket socket) {
            // Deliberately nothing: the operator switched checking off.
        }

        @Override
        public void checkServerTrusted(final X509Certificate[] chain, final String authType, final SSLEngine engine) {
            // Deliberately nothing: the operator switched checking off.
        }

        @Override
        public void checkServerTrusted(final X509Certificate[] chain, final String authType) {
            // Deliberately nothing: the operator switched checking off.
        }

        @Override
        public void checkClientTrusted(final X509Certificate[] chain, final String authType, final Socket socket)
                throws CertificateException {
            throw new CertificateException("The transport is never a server");
        }

        @Override
        public void checkClientTrusted(final X509Certificate[] chain, final String authType, final SSLEngine engine)
                throws CertificateException {
            throw new CertificateException("The transport is never a server");
        }

        @Override
        public void checkClientTrusted(final X509Certificate[] chain, final String authType) throws CertificateException {
            throw new CertificateException("The transport is never a server");
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return new X509Certificate[0];
        }

    }

}
