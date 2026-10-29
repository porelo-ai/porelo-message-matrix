package org.fuin.porelo.message.matrix;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.GeneralSecurityException;
import java.security.Key;
import java.security.KeyStore;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * A CA of the test's own and the server certificates it signs, made with the JDK's keytool as a
 * host's administrator makes them elsewhere: nothing of {@link Certificate}'s own code makes them.
 */
final class PlacedCertificate {

    private static final String STORE_PASSWORD = "test-only";

    private static final DateTimeFormatter START =
            DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss").withZone(ZoneOffset.UTC);

    private final Path work;

    /** A new CA in {@code work}, valid from a year before {@code now} for ten years. */
    PlacedCertificate(final Path work, final Instant now) throws IOException, InterruptedException {
        this.work = Files.createDirectories(work);
        keytool(
                "-genkeypair",
                "-alias",
                "ca",
                "-keyalg",
                "EC",
                "-groupname",
                "secp256r1",
                "-dname",
                "CN=Host admin CA",
                "-ext",
                "bc:c",
                "-startdate",
                START.format(now.minusSeconds(365L * 24 * 3600)),
                "-validity",
                "3650",
                "-keystore",
                store("ca").toString());
        keytool(
                "-exportcert",
                "-rfc",
                "-alias",
                "ca",
                "-keystore",
                store("ca").toString(),
                "-file",
                work.resolve("ca.crt").toString());
    }

    /** The CA's certificate as PEM. */
    Path caCertificate() {
        return work.resolve("ca.crt");
    }

    /**
     * Places the CA's certificate beside {@code homeserver.conf} in {@code dir}, and a server
     * certificate for {@code san} (keytool's form, such as {@code dns:*.h.example,ip:192.0.2.2})
     * with its key in {@code dir/tls}, valid from {@code start} for {@code days}; no CA key.
     */
    void place(final Path dir, final String san, final Instant start, final int days)
            throws IOException, InterruptedException, GeneralSecurityException {
        place(dir, san, start, days, "serverAuth");
    }

    /** As {@link #place(Path, String, Instant, int)}, for the extended key usage named. */
    void place(
            final Path dir,
            final String san,
            final Instant start,
            final int days,
            final String usage)
            throws IOException, InterruptedException, GeneralSecurityException {
        final Path server = store("server-" + System.nanoTime());
        keytool(
                "-genkeypair",
                "-alias",
                "server",
                "-keyalg",
                "EC",
                "-groupname",
                "secp256r1",
                "-dname",
                "CN=matrix",
                "-keystore",
                server.toString());
        final Path request = work.resolve("server.csr");
        keytool(
                "-certreq",
                "-alias",
                "server",
                "-keystore",
                server.toString(),
                "-file",
                request.toString());
        final Path tls = dir.resolve(Certificate.TLS);
        Files.createDirectories(
                tls,
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        keytool(
                "-gencert",
                "-rfc",
                "-alias",
                "ca",
                "-keystore",
                store("ca").toString(),
                "-infile",
                request.toString(),
                "-outfile",
                tls.resolve(Certificate.SERVER_CERTIFICATE).toString(),
                "-ext",
                "san=" + san,
                "-ext",
                "eku=" + usage,
                "-startdate",
                START.format(start),
                "-validity",
                Integer.toString(days));
        final KeyStore keys = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(server)) {
            keys.load(in, STORE_PASSWORD.toCharArray());
        }
        final Key key = keys.getKey("server", STORE_PASSWORD.toCharArray());
        final Path keyFile = tls.resolve(Certificate.SERVER_KEY);
        Files.deleteIfExists(keyFile);
        Files.createFile(
                keyFile,
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        Files.writeString(
                keyFile,
                "-----BEGIN PRIVATE KEY-----\n"
                        + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII))
                                .encodeToString(key.getEncoded())
                        + "\n-----END PRIVATE KEY-----\n",
                StandardCharsets.US_ASCII);
        Files.copy(
                caCertificate(),
                dir.resolve(Certificate.CA_CERTIFICATE),
                StandardCopyOption.REPLACE_EXISTING);
        Files.setPosixFilePermissions(
                dir.resolve(Certificate.CA_CERTIFICATE),
                PosixFilePermissions.fromString("rw-r--r--"));
    }

    private Path store(final String name) {
        return work.resolve(name + ".p12");
    }

    private static void keytool(final String... args) throws IOException, InterruptedException {
        final List<String> command = new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "keytool").toString());
        command.addAll(List.of(args));
        command.addAll(
                List.of(
                        "-storetype",
                        "PKCS12",
                        "-storepass",
                        STORE_PASSWORD,
                        "-keypass",
                        STORE_PASSWORD,
                        "-noprompt"));
        final Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        final String output =
                new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (process.waitFor() != 0) {
            throw new IOException("keytool " + args[0] + " failed: " + output);
        }
    }
}
