package org.fuin.porelo.message.matrix;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;

/**
 * A homeserver over TLS with a certificate no public authority signed - an intranet's, or a self-signed
 * one - made by each test with the JDK's keytool. Which of them the transport trusts, and with what
 * configuration, decides whether the token is sent at all.
 */
class TlsTest {

    private static final String TOKEN = "syt_never_sent_to_a_stranger";

    @TempDir
    private Path dir;

    private HttpsServer server;

    private final AtomicInteger requests = new AtomicInteger();

    private final ByteArrayOutputStream errBytes = new ByteArrayOutputStream();

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    /** Starts a TLS homeserver whose self-signed certificate names {@code san}; answers its PEM certificate. */
    private Path serve(final String name, final String san) throws IOException, InterruptedException, GeneralSecurityException {
        final Path keystore = dir.resolve(name + ".p12");
        final String password = "test-only";
        keytool("-genkeypair", "-alias", "server", "-keyalg", "EC", "-dname", "CN=" + name, "-ext", "SAN=" + san,
                "-validity", "1", "-keystore", keystore.toString(), "-storetype", "PKCS12", "-storepass", password);
        final Path pem = dir.resolve(name + ".pem");
        keytool("-exportcert", "-rfc", "-alias", "server", "-keystore", keystore.toString(), "-storepass", password,
                "-file", pem.toString());

        final KeyStore store = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(keystore)) {
            store.load(in, password.toCharArray());
        }
        final KeyManagerFactory keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keys.init(store, password.toCharArray());
        final SSLContext tls = SSLContext.getInstance("TLS");
        tls.init(keys.getKeyManagers(), null, null);

        server = HttpsServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.setHttpsConfigurator(new HttpsConfigurator(tls));
        server.createContext("/", exchange -> {
            requests.incrementAndGet();
            final byte[] body = "{\"user_id\":\"@bob:matrix.test\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
        return pem;
    }

    private static void keytool(final String... args) throws IOException, InterruptedException {
        final String[] command = new String[args.length + 1];
        command[0] = Path.of(System.getProperty("java.home"), "bin", "keytool").toString();
        System.arraycopy(args, 0, command, 1, args.length);
        final Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        final String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(process.waitFor()).as("keytool: %s", output).isZero();
    }

    private Map<String, String> env(final String... more) {
        final Map<String, String> env = new HashMap<>();
        env.put(Config.HOMESERVER, "https://127.0.0.1:" + server.getAddress().getPort());
        env.put(Config.ACCESS_TOKEN, TOKEN);
        env.put("XDG_STATE_HOME", dir.resolve("state").toString());
        for (int i = 0; i < more.length; i += 2) {
            env.put(more[i], more[i + 1]);
        }
        return env;
    }

    private int run(final Map<String, String> env, final String... args) {
        final PrintStream err = new PrintStream(errBytes, true, StandardCharsets.UTF_8);
        return Main.run(args, env, new PrintStream(OutputStream.nullOutputStream()), err);
    }

    private String stderr() {
        return errBytes.toString(StandardCharsets.UTF_8);
    }

    @Nested
    @DisplayName("Without configuration: the built-in authorities only")
    class BuiltIn {

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"send", "poll", "receipt"})
        @DisplayName("A certificate no built-in authority signed is refused with 78, and no request reaches the server")
        void refused(final String verb) throws Exception {
            serve("intranet", "IP:127.0.0.1");
            final Path message = Files.writeString(dir.resolve("m.json"), "{}", StandardCharsets.UTF_8);
            final Path inbound = Files.createDirectories(dir.resolve("in"));
            final String[] args = switch (verb) {
                case "send" -> new String[] {"send", message.toString(), message.toString(), "--to", "!room"};
                case "poll" -> new String[] {"poll", "--into", inbound.toString()};
                default -> new String[] {"receipt", "$event", "--by", "@bob:matrix.test"};
            };

            assertThat(run(env(), args)).as(stderr()).isEqualTo(Exit.CONFIG);
            assertThat(stderr()).contains("is not trusted").doesNotContain(TOKEN);
            assertThat(requests).as("requests that reached the server").hasValue(0);
        }


        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"send", "poll", "receipt"})
        @DisplayName("An inherited " + Config.TLS_VERIFY + "=off, with the secrets setup printed laid over it as Porelo"
                + " does, still checks the certificate")
        void inheritedOffIsOutweighed(final String verb) throws Exception {
            serve("intranet", "IP:127.0.0.1");
            final Path message = Files.writeString(dir.resolve("m.json"), "{}", StandardCharsets.UTF_8);
            final Path inbound = Files.createDirectories(dir.resolve("in"));
            final String[] args = switch (verb) {
                case "send" -> new String[] {"send", message.toString(), message.toString(), "--to", "!room"};
                case "poll" -> new String[] {"poll", "--into", inbound.toString()};
                default -> new String[] {"receipt", "$event", "--by", "@bob:matrix.test"};
            };
            final Map<String, String> caller = env(Config.TLS_VERIFY, "off");
            caller.putAll(Settings.read(new ByteArrayInputStream("{}".getBytes(StandardCharsets.UTF_8)))
                    .environment(URI.create(caller.get(Config.HOMESERVER)), TOKEN));

            assertThat(run(caller, args)).as(stderr()).isEqualTo(Exit.CONFIG);
            assertThat(stderr()).contains("is not trusted").doesNotContain(Config.VERIFY_OFF_WARNING);
            assertThat(requests).as("requests that reached the server").hasValue(0);
        }

    }

    @Nested
    @DisplayName("With " + Config.CA_FILE)
    class CaFile {

        @Test
        @DisplayName("The server whose certificate is in the file is trusted")
        void trustsTheFilesServer() throws Exception {
            final Path pem = serve("intranet", "IP:127.0.0.1");

            assertThat(run(env(Config.CA_FILE, pem.toString()), "check")).as(stderr()).isEqualTo(Exit.OK);
            assertThat(requests).hasValue(1);
        }

        @Test
        @DisplayName("Another server is not trusted because a file is given")
        void onlyThatServer() throws Exception {
            final Path other = serve("other", "IP:127.0.0.1");
            server.stop(0);
            serve("intranet", "IP:127.0.0.1");

            assertThat(run(env(Config.CA_FILE, other.toString()), "check")).isEqualTo(Check.NOT_USABLE);
            assertThat(requests).hasValue(0);
        }

        @Test
        @DisplayName("A certificate in the file that names another host is still refused: the name is checked as before")
        void nameStillChecked() throws Exception {
            final Path pem = serve("intranet", "IP:127.0.0.2");

            assertThat(run(env(Config.CA_FILE, pem.toString()), "check")).isEqualTo(Check.NOT_USABLE);
            assertThat(stderr()).contains("is not trusted");
            assertThat(requests).hasValue(0);
        }

        @Test
        @DisplayName("A file that holds no certificate is refused with 78, naming the file")
        void notACertificate() throws Exception {
            serve("intranet", "IP:127.0.0.1");
            final Path junk = Files.writeString(dir.resolve("junk.pem"), "not a certificate", StandardCharsets.UTF_8);
            final Path message = Files.writeString(dir.resolve("m.json"), "{}", StandardCharsets.UTF_8);

            assertThat(run(env(Config.CA_FILE, junk.toString()), "send", message.toString(), message.toString(), "--to", "!r"))
                    .isEqualTo(Exit.CONFIG);
            assertThat(stderr()).contains(Config.CA_FILE, "junk.pem");
        }

    }

    @Nested
    @DisplayName("With " + Config.TLS_VERIFY + "=off")
    class VerifyOff {

        @Test
        @DisplayName("Every certificate is accepted, and every start says so on stderr")
        void acceptedAndWarned() throws Exception {
            serve("intranet", "IP:127.0.0.2");

            assertThat(run(env(Config.TLS_VERIFY, "off"), "check")).as(stderr()).isEqualTo(Exit.OK);
            assertThat(run(env(Config.TLS_VERIFY, "off"), "check")).isEqualTo(Exit.OK);

            assertThat(stderr().lines().filter(line -> line.contains(Config.VERIFY_OFF_WARNING))).hasSize(2);
        }

        @Test
        @DisplayName("It is said even for a verb that never connects")
        void warnedEvenWithoutConnecting() {
            final PrintStream err = new PrintStream(errBytes, true, StandardCharsets.UTF_8);
            Main.run(new String[] {"describe"}, Map.of(Config.TLS_VERIFY, "off"), new PrintStream(OutputStream.nullOutputStream()), err);

            assertThat(stderr()).contains(Config.VERIFY_OFF_WARNING);
        }

        @Test
        @DisplayName("Without the switch nothing is said")
        void silentOtherwise() throws Exception {
            final Path pem = serve("intranet", "IP:127.0.0.1");

            run(env(Config.CA_FILE, pem.toString()), "check");

            assertThat(stderr()).doesNotContain("WARNING");
        }

        @Test
        @DisplayName("A value other than on or off is refused with 78")
        void unknownValue() throws Exception {
            serve("intranet", "IP:127.0.0.1");

            assertThat(run(env(Config.TLS_VERIFY, "no"), "poll", "--into", dir.toString())).isEqualTo(Exit.CONFIG);
            assertThat(requests).hasValue(0);
        }

        @Test
        @DisplayName("A CA file together with the switch off is refused with 78: which was meant cannot be told")
        void contradiction() throws Exception {
            final Path pem = serve("intranet", "IP:127.0.0.1");

            assertThat(run(env(Config.CA_FILE, pem.toString(), Config.TLS_VERIFY, "off"), "poll", "--into", dir.toString()))
                    .isEqualTo(Exit.CONFIG);
            assertThat(stderr()).contains("contradict");
            assertThat(requests).hasValue(0);
        }

    }

}
