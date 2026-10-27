package org.fuin.porelo.message.matrix;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PublishTest {

    @TempDir private Path dir;

    private final List<String> calls = new ArrayList<>();

    private final Publish.Control control =
            new Publish.Control() {
                @Override
                public void reload() {
                    calls.add("reload");
                }

                @Override
                public void restartHomeserver() {
                    calls.add("restart homeserver");
                }

                @Override
                public void listen() {
                    calls.add("listen");
                }

                @Override
                public void close() {
                    calls.add("close");
                }
            };

    /** As a project's setup leaves the account: the homeserver's port in its settings. */
    @org.junit.jupiter.api.BeforeEach
    void setUp() throws Exception {
        Files.createDirectories(matrix());
        Files.writeString(matrix().resolve("homeserver.conf"), "PORELO_MATRIX_PORT=8011\n");
    }

    private Map<String, String> env() {
        return Map.of("HOME", dir.toString(), "XDG_CONFIG_HOME", dir.resolve("config").toString());
    }

    private JsonNode publish(final String... args) throws Exception {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        Publish.run(
                List.of(args), env(), new PrintStream(out, true, StandardCharsets.UTF_8), control);
        return MatrixClient.JSON.readTree(out.toByteArray());
    }

    private Path matrix() {
        return dir.resolve("config/porelo/matrix");
    }

    private Path dropIn() {
        return dir.resolve(
                "config/systemd/user/porelo-matrix-homeserver-vpn.socket.d/publish.conf");
    }

    /** Harm guarded: the homeserver listens on every address, or on one a name resolves to. */
    @ParameterizedTest
    @ValueSource(
            strings = {
                "0.0.0.0:8448",
                "[::]:8448",
                "host.example.org:8448",
                "127.0.0.1:8448",
                "224.0.0.1:8448",
                "192.0.2.2:80",
                "192.0.2.2"
            })
    @DisplayName(
            "A wildcard, a name, loopback, multicast or a privileged port is refused before anything changes")
    void refusesWhatIsNotOneAddressOfThisMachine(final String address) throws Exception {
        assertThatThrownBy(() -> publish("--address", address))
                .as("%s is refused", address)
                .isInstanceOf(Failure.class)
                .extracting(failure -> ((Failure) failure).exitCode())
                .isEqualTo(Exit.CONFIG);

        assertThat(dropIn()).as("no socket is given an address").doesNotExist();
        assertThat(Files.readString(matrix().resolve("homeserver.conf")))
                .as("nothing is written")
                .isEqualTo("PORELO_MATRIX_PORT=8011\n");
        assertThat(calls).as("the service manager is not asked").isEmpty();
    }

    /** Harm guarded: the socket listens before the homeserver serves the new certificate. */
    @Test
    @DisplayName(
            "Publishing names the address for the socket and the homeserver, then restarts and listens")
    void publishesOnTheAddress() throws Exception {
        final JsonNode answer = publish("--address", "192.0.2.2:8448");

        assertThat(Files.readString(dropIn()))
                .as("the drop-in replaces any address the socket had")
                .isEqualTo("[Socket]\nListenStream=\nListenStream=192.0.2.2:8448\n");
        assertThat(Files.readString(matrix().resolve("homeserver.conf")))
                .as("the homeserver keeps its port and knows its published address")
                .isEqualTo("PORELO_MATRIX_PORT=8011\nPORELO_MATRIX_PUBLISH=192.0.2.2:8448\n");
        assertThat(Files.readString(matrix().resolve(Publish.TLS_ENV)))
                .as("the next start serves TLS beside plain http")
                .contains("TUWUNEL_TLS__DUAL_PROTOCOL=true");
        assertThat(calls)
                .as("read, restarted with the certificate, then listening")
                .containsExactly("reload", "restart homeserver", "listen");
        assertThat(answer.path("published").asText())
                .as("the answer names the address")
                .isEqualTo("192.0.2.2:8448");
        assertThat(Path.of(answer.path("ca_file").asText()))
                .as("the CA file another machine names")
                .isEqualTo(matrix().resolve(Certificate.CA_CERTIFICATE))
                .exists();
    }

    /** Harm guarded: the socket is handed an address it cannot parse and listens nowhere. */
    @Test
    @DisplayName("An IPv6 address is named in brackets for the socket")
    void publishesOnAnIpv6Address() throws Exception {
        publish("--address", "[2001:db8::2]:8448");

        assertThat(Files.readString(dropIn()))
                .as("systemd takes an IPv6 address with its port only in brackets")
                .contains("ListenStream=[2001:db8:0:0:0:0:0:2]:8448");
    }

    /** Harm guarded: the homeserver stays reachable from the VPN after the host is dropped. */
    @Test
    @DisplayName(
            "Off, the socket is closed, its address, the server's key and the TLS settings removed, the CA kept")
    void publishesNoMore() throws Exception {
        publish("--address", "192.0.2.2:8448");
        final String ca = Files.readString(matrix().resolve(Certificate.CA_CERTIFICATE));
        calls.clear();

        final JsonNode answer = publish("--off");

        assertThat(calls)
                .as("closed first, then read again and restarted without TLS")
                .containsExactly("close", "reload", "restart homeserver");
        assertThat(dropIn()).as("the socket has no address").doesNotExist();
        assertThat(Files.readString(matrix().resolve("homeserver.conf")))
                .as("nothing is published")
                .doesNotContain("PORELO_MATRIX_PUBLISH");
        assertThat(matrix().resolve(Certificate.TLS).resolve(Certificate.SERVER_KEY))
                .as("the server's key is gone")
                .doesNotExist();
        assertThat(Files.readString(matrix().resolve(Publish.TLS_ENV)))
                .as("the next start serves no TLS")
                .isEmpty();
        assertThat(Files.readString(matrix().resolve(Certificate.CA_CERTIFICATE)))
                .as("the CA other machines trust stays")
                .isEqualTo(ca);
        assertThat(answer.path("published").isNull()).as("the answer names none").isTrue();
    }

    /** Harm guarded: the CA's key or the server's key outlives the account's homeserver. */
    @Test
    @DisplayName("Clearing removes everything publishing made, and a second clear finds nothing")
    void clearingForgetsWhatPublishingMade() throws Exception {
        publish("--address", "192.0.2.2:8448");
        HomeserverToken.run(List.of(), env());
        MachineTokens.issue(env(), "hosta");

        final Lifecycle.Report report = new Lifecycle.Report();
        LocalHomeserver.forgetPublished(env(), report, true);

        try (var left = Files.walk(dir)) {
            assertThat(left.filter(Files::isRegularFile).map(dir::relativize).map(Path::toString))
                    .as(
                            "only the port's settings and this machine's token are left, which clear"
                                    + " removes with the rest; no other machine's token")
                    .containsExactlyInAnyOrder(
                            "config/porelo/matrix/homeserver.conf",
                            "config/porelo/matrix/registration-token");
        }
        final Lifecycle.Report again = new Lifecycle.Report();
        LocalHomeserver.forgetPublished(env(), again, true);
        assertThat(again.answer().path("removed")).as("nothing is left to remove").isEmpty();
    }

    /**
     * Harm guarded: a socket that failed to close is forgotten, and a second clear leaves it on.
     */
    @Test
    @DisplayName("When the socket could not be closed, its address stays for the next clear")
    void keepsTheAddressOfASocketNotClosed() throws Exception {
        publish("--address", "192.0.2.2:8448");

        LocalHomeserver.forgetPublished(env(), new Lifecycle.Report(), false);

        assertThat(dropIn())
                .as("the next clear still sees the socket was published and closes it")
                .exists();
    }

    /**
     * Harm guarded: publishing moves a running homeserver to another port, and this machine's
     * transport loses it.
     */
    @Test
    @DisplayName(
            "A homeserver no project set up has no port to publish, and is refused before anything changes")
    void refusesAHomeserverWithoutItsPort() throws Exception {
        Files.delete(matrix().resolve("homeserver.conf"));

        assertThatThrownBy(() -> publish("--address", "192.0.2.2:8448"))
                .as("no port is chosen for a homeserver that may run on another")
                .isInstanceOf(Failure.class)
                .extracting(failure -> ((Failure) failure).exitCode())
                .isEqualTo(Exit.CONFIG);
        assertThat(matrix().resolve("homeserver.conf")).as("no port written").doesNotExist();
        assertThat(calls).as("the service manager is not asked").isEmpty();
    }

    /** Harm guarded: a mistyped option is taken for something else and changes the machine. */
    @Test
    @DisplayName("Anything but --address or --off is a usage error")
    void refusesOtherArguments() {
        assertThatThrownBy(() -> publish("--adress", "192.0.2.2:8448"))
                .as("nothing is guessed from a mistyped option")
                .isInstanceOf(Failure.class)
                .extracting(failure -> ((Failure) failure).exitCode())
                .isEqualTo(Exit.USAGE);
    }
}
