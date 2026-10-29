package org.fuin.porelo.message.matrix;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.net.InetAddress;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.cert.CertPathValidator;
import java.security.cert.CertificateFactory;
import java.security.cert.PKIXParameters;
import java.security.cert.TrustAnchor;
import java.security.cert.X509Certificate;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * The account's own certificates for its homeserver's listener on another address: a CA made once,
 * whose certificate another machine names as its {@code ca_file}, and a server certificate for the
 * one address the listener is published on. The CA's key stays beside the configuration and is
 * never mounted into the container; only the {@code tls} directory with the server's certificate
 * and key is.
 */
final class Certificate {

    /** How long a server certificate holds. */
    static final Duration LIFETIME = Duration.ofDays(365);

    /** A server certificate this close to its end is made anew. */
    static final Duration RENEW_WITHIN = Duration.ofDays(30);

    /** The CA outlives any server certificate it signs, so a renewal never needs a new CA file. */
    private static final Duration CA_LIFETIME = Duration.ofDays(3650);

    /** Clocks of two machines a little apart still agree that a fresh certificate holds. */
    private static final Duration BACKDATE = Duration.ofMinutes(5);

    static final String CA_CERTIFICATE = "ca.crt";

    static final String CA_KEY = "ca.key";

    static final String TLS = "tls";

    static final String SERVER_CERTIFICATE = "server.crt";

    static final String SERVER_KEY = "server.key";

    private static final Set<PosixFilePermission> OWNER_ONLY =
            PosixFilePermissions.fromString("rw-------");

    private static final Set<PosixFilePermission> OWNER_ONLY_DIR =
            PosixFilePermissions.fromString("rwx------");

    private static final String SIGNATURE = "SHA256withECDSA";

    private static final byte[] ECDSA_WITH_SHA256 = oid(1, 2, 840, 10045, 4, 3, 2);

    private static final byte[] COMMON_NAME = oid(2, 5, 4, 3);

    private static final byte[] BASIC_CONSTRAINTS = oid(2, 5, 29, 19);

    private static final byte[] KEY_USAGE = oid(2, 5, 29, 15);

    private static final byte[] EXTENDED_KEY_USAGE = oid(2, 5, 29, 37);

    private static final String SERVER_AUTH_OID = "1.3.6.1.5.5.7.3.1";

    private static final byte[] SERVER_AUTH = oid(1, 3, 6, 1, 5, 5, 7, 3, 1);

    private static final byte[] SUBJECT_ALT_NAME = oid(2, 5, 29, 17);

    private static final byte[] SUBJECT_KEY_ID = oid(2, 5, 29, 14);

    private static final byte[] AUTHORITY_KEY_ID = oid(2, 5, 29, 35);

    private static final SecureRandom RANDOM = new SecureRandom();

    private Certificate() {}

    /**
     * The CA, made once, and a server certificate for {@code address}, made anew when it is
     * missing, names another address, is not the CA's, does not fit its key or ends within {@link
     * #RENEW_WITHIN}. Answers whether the server certificate was made anew, so a running homeserver
     * can be restarted to serve it.
     */
    static boolean ensure(final Path dir, final InetAddress address, final Instant now)
            throws Failure {
        return ensure(dir, address, null, now);
    }

    /** As {@link #ensure(Path, InetAddress, Instant)}, naming {@code name} beside the address. */
    static boolean ensure(
            final Path dir,
            final InetAddress address,
            final @Nullable String name,
            final Instant now)
            throws Failure {
        try {
            directory(dir);
            directory(dir.resolve(TLS));
            try (FileChannel channel = lock(dir);
                    FileLock lock = channel.lock()) {
                if (placed(dir)) {
                    checkPlaced(dir, address, name, now);
                    return false;
                }
                final Authority ca = authority(dir, now);
                if (current(dir.resolve(TLS), ca, address, name, now)) {
                    return false;
                }
                server(dir.resolve(TLS), ca, address, name, now);
                return true;
            }
        } catch (final IOException | GeneralSecurityException ex) {
            throw new Failure(
                    Exit.CONFIG,
                    "cannot make the homeserver's certificate in " + dir + ": " + ex.getMessage(),
                    ex);
        }
    }

    /**
     * Whether the server certificate was made elsewhere and placed here: a CA certificate without
     * its key beside a server certificate. The transport never makes, renews or removes one.
     */
    static boolean placed(final Path dir) {
        // Any one of them is enough: the host's tooling may be placing the others still.
        return !Files.exists(dir.resolve(CA_KEY))
                && (Files.exists(dir.resolve(CA_CERTIFICATE))
                        || Files.exists(dir.resolve(TLS).resolve(SERVER_CERTIFICATE))
                        || Files.exists(dir.resolve(TLS).resolve(SERVER_KEY)));
    }

    /**
     * What to say of a placed certificate within its last {@link #RENEW_WITHIN}, which nobody here
     * renews; null for any other.
     */
    static @Nullable String notice(final Path dir, final Instant now) {
        if (!placed(dir)) {
            return null;
        }
        final Path file = dir.resolve(TLS).resolve(SERVER_CERTIFICATE);
        try {
            final Instant end = read(file).getNotAfter().toInstant();
            return end.isBefore(now.plus(RENEW_WITHIN))
                    ? file + ", made elsewhere, ends at " + end + "; place a new one before then"
                    : null;
        } catch (final IOException | GeneralSecurityException ex) {
            return null;
        }
    }

    /**
     * A placed certificate checked, never changed: signed by the placed CA, valid now, fitting its
     * key, and covering the published address and name.
     */
    private static void checkPlaced(
            final Path dir,
            final InetAddress address,
            final @Nullable String name,
            final Instant now)
            throws Failure {
        final Path caFile = dir.resolve(CA_CERTIFICATE);
        final Path file = dir.resolve(TLS).resolve(SERVER_CERTIFICATE);
        final Path key = dir.resolve(TLS).resolve(SERVER_KEY);
        for (final Path needed : List.of(caFile, file, key)) {
            if (!Files.exists(needed)) {
                throw new Failure(
                        Exit.CONFIG,
                        needed
                                + " is missing: a certificate made elsewhere needs ca.crt,"
                                + " tls/server.crt and tls/server.key, and one of the account's own"
                                + " its ca.key");
            }
        }
        final X509Certificate ca = single(caFile);
        final X509Certificate server = single(file);
        try {
            server.verify(ca.getPublicKey());
        } catch (final GeneralSecurityException ex) {
            throw new Failure(Exit.CONFIG, file + " is not signed by " + caFile);
        }
        if (server.getNotBefore().toInstant().isAfter(now)) {
            throw new Failure(
                    Exit.CONFIG, file + " holds only from " + server.getNotBefore().toInstant());
        }
        if (!server.getNotAfter().toInstant().isAfter(now)) {
            throw new Failure(
                    Exit.CONFIG,
                    file
                            + " ended at "
                            + server.getNotAfter().toInstant()
                            + "; place a new one, the transport makes none");
        }
        validate(ca, server, now, file);
        if (!coversAddress(server, address)) {
            throw new Failure(Exit.CONFIG, file + " does not cover " + address.getHostAddress());
        }
        if (name != null && !coversName(server, name)) {
            throw new Failure(Exit.CONFIG, file + " does not cover " + name);
        }
        final boolean fits;
        try {
            fits = fits(privateKey(key), server.getPublicKey());
        } catch (final IOException | GeneralSecurityException | IllegalArgumentException ex) {
            throw new Failure(
                    Exit.CONFIG,
                    "cannot read "
                            + key
                            + ": it must be an unencrypted EC key in PKCS#8 (BEGIN PRIVATE KEY)");
        }
        if (!fits) {
            throw new Failure(Exit.CONFIG, key + " does not fit " + file);
        }
    }

    /**
     * As a client checks it: a path from the CA at {@code now}, the CA a CA, and the certificate
     * one for a server.
     */
    private static void validate(
            final X509Certificate ca,
            final X509Certificate server,
            final Instant now,
            final Path file)
            throws Failure {
        try {
            final PKIXParameters parameters = new PKIXParameters(Set.of(new TrustAnchor(ca, null)));
            parameters.setRevocationEnabled(false);
            parameters.setDate(Date.from(now));
            CertPathValidator.getInstance("PKIX")
                    .validate(
                            CertificateFactory.getInstance("X.509")
                                    .generateCertPath(List.of(server)),
                            parameters);
            if (ca.getBasicConstraints() < 0) {
                throw new Failure(Exit.CONFIG, file + "'s CA certificate is not a CA's");
            }
            final @Nullable List<String> usages = server.getExtendedKeyUsage();
            if (usages != null && !usages.contains(SERVER_AUTH_OID)) {
                throw new Failure(Exit.CONFIG, file + " is not a server's: no serverAuth usage");
            }
            final boolean @Nullable [] keyUsage = server.getKeyUsage();
            if (keyUsage != null && !keyUsage[0]) {
                throw new Failure(
                        Exit.CONFIG, file + " is not a server's: no digitalSignature usage");
            }
        } catch (final GeneralSecurityException ex) {
            throw new Failure(
                    Exit.CONFIG,
                    file + " is refused as a server's certificate: " + ex.getMessage());
        }
    }

    /** The one certificate a placed file holds, never a key beside it nor a second one. */
    private static X509Certificate single(final Path file) throws Failure {
        try {
            final String pem = Files.readString(file, StandardCharsets.US_ASCII);
            if (pem.contains("PRIVATE KEY")) {
                throw new Failure(
                        Exit.CONFIG,
                        file
                                + " holds a private key: place the certificate alone, and the key away");
            }
            final Collection<? extends java.security.cert.Certificate> found =
                    CertificateFactory.getInstance("X.509")
                            .generateCertificates(
                                    new ByteArrayInputStream(
                                            pem.getBytes(StandardCharsets.US_ASCII)));
            if (found.size() != 1 || !(found.iterator().next() instanceof X509Certificate one)) {
                throw new Failure(
                        Exit.CONFIG,
                        file + " holds " + found.size() + " certificates: place exactly one");
            }
            return one;
        } catch (final IOException | GeneralSecurityException ex) {
            throw new Failure(Exit.CONFIG, "cannot read " + file + ": " + ex.getMessage());
        }
    }

    private static boolean coversAddress(final X509Certificate server, final InetAddress address) {
        for (final List<?> entry : alternativeNames(server)) {
            // 7 is iPAddress.
            if (Integer.valueOf(7).equals(entry.get(0))
                    && entry.get(1) instanceof String value
                    && address.equals(literal(value))) {
                return true;
            }
        }
        return false;
    }

    private static @Nullable InetAddress literal(final String value) {
        try {
            return InetAddress.ofLiteral(value);
        } catch (final IllegalArgumentException ex) {
            return null;
        }
    }

    /**
     * The name itself, or a wildcard in place of its first label alone, under at least two labels,
     * compared in ASCII alone.
     */
    private static boolean coversName(final X509Certificate server, final String name) {
        final String lower = name.toLowerCase(Locale.ROOT);
        final int dot = lower.indexOf('.');
        final @Nullable String wildcard =
                dot > 0 && lower.indexOf('.', dot + 1) > dot + 1
                        ? "*" + lower.substring(dot)
                        : null;
        for (final List<?> entry : alternativeNames(server)) {
            // 2 is dNSName; a name has no case, and a name outside ASCII is none of ours.
            if (Integer.valueOf(2).equals(entry.get(0))
                    && entry.get(1) instanceof String value
                    && value.chars().allMatch(c -> c < 0x80)) {
                final String candidate = value.toLowerCase(Locale.ROOT);
                if (candidate.equals(lower) || candidate.equals(wildcard)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** None when they cannot be read: such a certificate covers nothing. */
    private static Collection<List<?>> alternativeNames(final X509Certificate server) {
        try {
            final Collection<List<?>> names = server.getSubjectAlternativeNames();
            return names == null ? List.of() : names;
        } catch (final GeneralSecurityException ex) {
            return List.of();
        }
    }

    /**
     * The server certificate and its key removed; the CA stays, so other machines keep it. A placed
     * certificate stays too.
     */
    static void removeServer(final Path dir) throws Failure {
        if (!Files.isDirectory(dir)) {
            return;
        }
        // Under the lock a start making the pair holds, so neither file is left without the other.
        try (FileChannel channel = lock(dir);
                FileLock lock = channel.lock()) {
            // The host's administrator placed it, and is the one to remove it.
            if (placed(dir)) {
                return;
            }
            Files.deleteIfExists(dir.resolve(TLS).resolve(SERVER_CERTIFICATE));
            Files.deleteIfExists(dir.resolve(TLS).resolve(SERVER_KEY));
        } catch (final IOException ex) {
            throw new Failure(
                    Exit.CONFIG,
                    "cannot remove the homeserver's server certificate in "
                            + dir
                            + ": "
                            + ex.getMessage(),
                    ex);
        }
    }

    private static FileChannel lock(final Path dir) throws IOException {
        return FileChannel.open(
                dir.resolve(".certificate.lock"),
                Set.of(StandardOpenOption.CREATE, StandardOpenOption.WRITE),
                PosixFilePermissions.asFileAttribute(OWNER_ONLY));
    }

    /** The CA's certificate as PEM, which another machine names as its {@code ca_file}. */
    static String caCertificate(final Path dir) throws Failure {
        try {
            final String pem =
                    Files.readString(dir.resolve(CA_CERTIFICATE), StandardCharsets.US_ASCII);
            if (pem.contains("PRIVATE KEY")) {
                // Handed to every machine admitted: never a key with it.
                throw new Failure(
                        Exit.CONFIG,
                        dir.resolve(CA_CERTIFICATE)
                                + " holds a private key: place the certificate alone");
            }
            return pem;
        } catch (final IOException ex) {
            throw new Failure(
                    Exit.CONFIG,
                    "cannot read the homeserver's CA certificate "
                            + dir.resolve(CA_CERTIFICATE)
                            + ": "
                            + ex.getMessage(),
                    ex);
        }
    }

    private record Authority(X509Certificate certificate, PrivateKey key) {}

    private static void directory(final Path dir) throws IOException {
        if (!Files.isDirectory(dir)) {
            Files.createDirectories(dir, PosixFilePermissions.asFileAttribute(OWNER_ONLY_DIR));
        }
        Files.setPosixFilePermissions(dir, OWNER_ONLY_DIR);
    }

    /**
     * The CA in {@code dir}, made when it has none. A certificate without its key cannot sign and
     * is refused; a key without its certificate was handed to nobody and is made anew.
     */
    private static Authority authority(final Path dir, final Instant now)
            throws IOException, GeneralSecurityException, Failure {
        final Path certificate = dir.resolve(CA_CERTIFICATE);
        final Path key = dir.resolve(CA_KEY);
        if (Files.exists(certificate)) {
            if (!Files.exists(key)) {
                throw new Failure(
                        Exit.CONFIG,
                        certificate
                                + " has no key beside it: remove it, and give every machine that"
                                + " trusts it the new one");
            }
            final Authority found = new Authority(read(certificate), privateKey(key));
            if (!fits(found.key(), found.certificate().getPublicKey())) {
                throw new Failure(
                        Exit.CONFIG,
                        key
                                + " does not fit "
                                + certificate
                                + ": nothing it signs would hold for a machine that trusts the CA");
            }
            return found;
        }
        final KeyPair pair = keyPair();
        final byte[] name = name("Porelo homeserver CA");
        final byte[] keyId = keyId(pair.getPublic());
        final byte[] der =
                certificate(
                        name,
                        name,
                        pair.getPublic(),
                        pair.getPrivate(),
                        now.minus(BACKDATE),
                        now.plus(CA_LIFETIME),
                        List.of(
                                extension(
                                        BASIC_CONSTRAINTS,
                                        true,
                                        sequence(bool(true), integer(BigInteger.ZERO))),
                                // keyCertSign and cRLSign.
                                extension(KEY_USAGE, true, bitString(new byte[] {0x06}, 1)),
                                extension(SUBJECT_KEY_ID, false, octets(keyId))));
        // The key first, so a certificate on disk always has its key.
        replace(key, pem("PRIVATE KEY", pair.getPrivate().getEncoded()));
        link(certificate, pem("CERTIFICATE", der));
        return new Authority(read(certificate), privateKey(key));
    }

    private static boolean current(
            final Path tls,
            final Authority ca,
            final InetAddress address,
            final @Nullable String name,
            final Instant now) {
        final Path certificate = tls.resolve(SERVER_CERTIFICATE);
        final Path key = tls.resolve(SERVER_KEY);
        try {
            if (!Files.exists(certificate)
                    || !Files.exists(key)
                    || !Files.getPosixFilePermissions(key).equals(OWNER_ONLY)) {
                return false;
            }
            final X509Certificate server = read(certificate);
            server.verify(ca.certificate().getPublicKey());
            if (server.getNotAfter().toInstant().isBefore(now.plus(RENEW_WITHIN))
                    || server.getNotBefore().toInstant().isAfter(now)) {
                return false;
            }
            return names(server, address, name) && fits(privateKey(key), server.getPublicKey());
        } catch (final IOException | GeneralSecurityException | RuntimeException | Failure ex) {
            // A server certificate or key that cannot be read or checked is made anew.
            return false;
        }
    }

    /** Exactly the address and, where one is published, the name: no other, none missing. */
    private static boolean names(
            final X509Certificate server, final InetAddress address, final @Nullable String name)
            throws GeneralSecurityException {
        final Collection<List<?>> names = server.getSubjectAlternativeNames();
        if (names == null) {
            return false;
        }
        boolean ip = false;
        boolean dns = name == null;
        for (final List<?> entry : names) {
            // 7 is iPAddress, 2 dNSName.
            if (Integer.valueOf(7).equals(entry.get(0))
                    && entry.get(1) instanceof String value
                    && InetAddress.ofLiteral(value).equals(address)) {
                ip = true;
            } else if (Integer.valueOf(2).equals(entry.get(0)) && entry.get(1).equals(name)) {
                dns = true;
            } else {
                return false;
            }
        }
        return ip && dns;
    }

    private static boolean fits(final PrivateKey key, final PublicKey publicKey)
            throws GeneralSecurityException {
        final byte[] probe = new byte[32];
        RANDOM.nextBytes(probe);
        final Signature signer = Signature.getInstance(SIGNATURE);
        signer.initSign(key);
        signer.update(probe);
        final byte[] signed = signer.sign();
        final Signature verifier = Signature.getInstance(SIGNATURE);
        verifier.initVerify(publicKey);
        verifier.update(probe);
        return verifier.verify(signed);
    }

    private static void server(
            final Path tls,
            final Authority ca,
            final InetAddress address,
            final @Nullable String name,
            final Instant now)
            throws IOException, GeneralSecurityException {
        final KeyPair pair = keyPair();
        final byte[] der =
                certificate(
                        ca.certificate().getSubjectX500Principal().getEncoded(),
                        name("Porelo homeserver"),
                        pair.getPublic(),
                        ca.key(),
                        now.minus(BACKDATE),
                        now.plus(LIFETIME),
                        List.of(
                                extension(BASIC_CONSTRAINTS, true, sequence()),
                                // digitalSignature.
                                extension(KEY_USAGE, true, bitString(new byte[] {(byte) 0x80}, 7)),
                                extension(EXTENDED_KEY_USAGE, false, sequence(SERVER_AUTH)),
                                // Not critical: the subject is not empty, as RFC 5280 asks then.
                                extension(
                                        SUBJECT_ALT_NAME,
                                        false,
                                        name == null
                                                ? sequence(tag(0x87, address.getAddress()))
                                                : sequence(
                                                        tag(0x87, address.getAddress()),
                                                        tag(
                                                                0x82,
                                                                name.getBytes(
                                                                        StandardCharsets
                                                                                .US_ASCII)))),
                                extension(SUBJECT_KEY_ID, false, octets(keyId(pair.getPublic()))),
                                extension(
                                        AUTHORITY_KEY_ID,
                                        false,
                                        sequence(
                                                tag(
                                                        0x80,
                                                        keyId(ca.certificate().getPublicKey()))))));
        // The key first: a certificate whose key is still the old one fails the next check and is
        // made again, while the other order would serve a key that fits no certificate.
        replace(tls.resolve(SERVER_KEY), pem("PRIVATE KEY", pair.getPrivate().getEncoded()));
        replace(tls.resolve(SERVER_CERTIFICATE), pem("CERTIFICATE", der));
    }

    private static KeyPair keyPair() throws GeneralSecurityException {
        final KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"), RANDOM);
        return generator.generateKeyPair();
    }

    private static X509Certificate read(final Path file)
            throws IOException, GeneralSecurityException {
        return (X509Certificate)
                CertificateFactory.getInstance("X.509")
                        .generateCertificate(new ByteArrayInputStream(Files.readAllBytes(file)));
    }

    private static PrivateKey privateKey(final Path file)
            throws IOException, GeneralSecurityException, Failure {
        if (!Files.getPosixFilePermissions(file).equals(OWNER_ONLY)) {
            throw new Failure(
                    Exit.CONFIG, file + " is readable by more than its owner; fix it or remove it");
        }
        final String pem = Files.readString(file, StandardCharsets.US_ASCII);
        final String body =
                pem.replace("-----BEGIN PRIVATE KEY-----", "")
                        .replace("-----END PRIVATE KEY-----", "")
                        .replaceAll("\\s", "");
        return KeyFactory.getInstance("EC")
                .generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(body)));
    }

    /** Written whole beside it, readable by its owner alone, then moved over the old one. */
    private static void replace(final Path file, final String content) throws IOException {
        final Path draft = draft(file, content);
        try {
            Files.move(draft, file, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(draft);
        }
    }

    /** As {@link #replace}, but linked in: a CA another start made first is the one kept. */
    private static void link(final Path file, final String content) throws IOException {
        final Path draft = draft(file, content);
        try {
            Files.createLink(file, draft);
        } catch (final FileAlreadyExistsException ex) {
            // Unreachable under the lock; kept so a CA is never replaced.
        } finally {
            Files.deleteIfExists(draft);
        }
    }

    private static Path draft(final Path file, final String content) throws IOException {
        final Path dir = file.getParent();
        final Path draft =
                Files.createTempFile(
                        dir,
                        "." + file.getFileName() + "-",
                        ".draft",
                        PosixFilePermissions.asFileAttribute(OWNER_ONLY));
        Files.writeString(draft, content, StandardCharsets.US_ASCII);
        return draft;
    }

    private static String pem(final String type, final byte[] der) {
        return "-----BEGIN "
                + type
                + "-----\n"
                + Base64.getMimeEncoder(64, new byte[] {'\n'}).encodeToString(der)
                + "\n-----END "
                + type
                + "-----\n";
    }

    // --- DER, as RFC 5280 lays out a certificate ---------------------------------------------

    private static byte[] certificate(
            final byte[] issuer,
            final byte[] subject,
            final PublicKey publicKey,
            final PrivateKey signingKey,
            final Instant notBefore,
            final Instant notAfter,
            final List<byte[]> extensions)
            throws GeneralSecurityException {
        final byte[] algorithm = sequence(ECDSA_WITH_SHA256);
        final byte[] serial = new byte[16];
        RANDOM.nextBytes(serial);
        // Positive and of full length, as RFC 5280 asks of a serial.
        serial[0] = (byte) ((serial[0] & 0x7f) | 0x40);
        final byte[] tbs =
                sequence(
                        tag(0xa0, integer(BigInteger.TWO)),
                        integer(new BigInteger(serial)),
                        algorithm,
                        issuer,
                        sequence(time(notBefore), time(notAfter)),
                        subject,
                        publicKey.getEncoded(),
                        tag(0xa3, sequence(extensions.toArray(byte[][]::new))));
        final Signature signer = Signature.getInstance(SIGNATURE);
        signer.initSign(signingKey);
        signer.update(tbs);
        return sequence(tbs, algorithm, bitString(signer.sign(), 0));
    }

    private static byte[] name(final String commonName) {
        return sequence(
                tag(
                        0x31,
                        sequence(
                                COMMON_NAME,
                                tag(0x0c, commonName.getBytes(StandardCharsets.UTF_8)))));
    }

    private static byte[] extension(final byte[] id, final boolean critical, final byte[] value) {
        return critical ? sequence(id, bool(true), octets(value)) : sequence(id, octets(value));
    }

    /** The SHA-1 of the key's bits, as RFC 5280 suggests for a key identifier. */
    private static byte[] keyId(final PublicKey key) throws GeneralSecurityException {
        final byte[] info = key.getEncoded();
        // SubjectPublicKeyInfo: a sequence of the algorithm and the key's bit string, read
        // back from the encoding the provider made.
        int at = 1 + lengthOfLength(info, 0);
        at += 1 + lengthOfLength(info, at) + length(info, at);
        final int bitsLength = length(info, at);
        final int bitsStart = at + lengthOfLength(info, at) + 1;
        return MessageDigest.getInstance("SHA-1")
                .digest(Arrays.copyOfRange(info, bitsStart + 1, bitsStart + bitsLength));
    }

    private static int lengthOfLength(final byte[] der, final int tagAt) {
        final int first = der[tagAt + 1] & 0xff;
        return first < 0x80 ? 1 : 1 + (first & 0x7f);
    }

    private static int length(final byte[] der, final int tagAt) {
        final int first = der[tagAt + 1] & 0xff;
        if (first < 0x80) {
            return first;
        }
        int value = 0;
        for (int index = 0; index < (first & 0x7f); index++) {
            value = (value << 8) | (der[tagAt + 2 + index] & 0xff);
        }
        return value;
    }

    private static byte[] time(final Instant instant) {
        final ZonedDateTime utc = instant.atZone(ZoneOffset.UTC);
        // UTCTime up to 2049, GeneralizedTime from 2050 on, as RFC 5280 requires.
        return utc.getYear() < 2050
                ? tag(
                        0x17,
                        DateTimeFormatter.ofPattern("yyMMddHHmmss'Z'")
                                .format(utc)
                                .getBytes(StandardCharsets.US_ASCII))
                : tag(
                        0x18,
                        DateTimeFormatter.ofPattern("yyyyMMddHHmmss'Z'")
                                .format(utc)
                                .getBytes(StandardCharsets.US_ASCII));
    }

    private static byte[] sequence(final byte[]... parts) {
        return tag(0x30, concat(parts));
    }

    private static byte[] integer(final BigInteger value) {
        return tag(0x02, value.toByteArray());
    }

    private static byte[] bool(final boolean value) {
        return tag(0x01, new byte[] {value ? (byte) 0xff : 0});
    }

    private static byte[] octets(final byte[] value) {
        return tag(0x04, value);
    }

    private static byte[] bitString(final byte[] bits, final int unused) {
        final byte[] value = new byte[bits.length + 1];
        value[0] = (byte) unused;
        System.arraycopy(bits, 0, value, 1, bits.length);
        return tag(0x03, value);
    }

    private static byte[] oid(final int... arcs) {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(40 * arcs[0] + arcs[1]);
        for (int index = 2; index < arcs.length; index++) {
            final int arc = arcs[index];
            for (int shift = 28; shift > 0; shift -= 7) {
                if (arc >= 1 << shift) {
                    out.write(0x80 | ((arc >>> shift) & 0x7f));
                }
            }
            out.write(arc & 0x7f);
        }
        return tag(0x06, out.toByteArray());
    }

    private static byte[] tag(final int tag, final byte[] value) {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(tag);
        final int length = value.length;
        if (length < 0x80) {
            out.write(length);
        } else if (length < 0x100) {
            out.write(0x81);
            out.write(length);
        } else {
            out.write(0x82);
            out.write(length >>> 8);
            out.write(length & 0xff);
        }
        out.writeBytes(value);
        return out.toByteArray();
    }

    private static byte[] concat(final byte[]... parts) {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (final byte[] part : parts) {
            out.writeBytes(part);
        }
        return out.toByteArray();
    }
}
