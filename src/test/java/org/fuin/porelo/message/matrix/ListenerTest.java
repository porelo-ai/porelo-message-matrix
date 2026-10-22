package org.fuin.porelo.message.matrix;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Harm guarded: a token handed to another account's listener on the homeserver's loopback port. The
 * tables are in the kernel's own format, lines as read from a machine's {@code /proc/net/tcp} and
 * {@code /proc/net/tcp6}; port 8008 is {@code 1F48}.
 */
class ListenerTest {

    private static final String TCP_HEAD =
            "  sl  local_address rem_address   st tx_queue rx_queue tr tm->when retrnsmt   uid  timeout inode";

    private static final String TCP6_HEAD =
            "  sl  local_address                         remote_address                        st tx_queue"
                    + " rx_queue tr tm->when retrnsmt   uid  timeout inode";

    private static final URI HOMESERVER = URI.create("http://127.0.0.1:8008");

    private static final long OWN = 1000;

    @TempDir private Path dir;

    private static String tcp(final String local, final String state, final long uid) {
        return "   0: "
                + local
                + " 00000000:0000 "
                + state
                + " 00000000:00000000 00:00000000 00000000  "
                + uid
                + "        0 29667 1 0000000000000000 100 0 0 10 0";
    }

    private static String tcp6(final String local, final String state, final long uid) {
        return "   0: "
                + local
                + " 00000000000000000000000000000000:0000 "
                + state
                + " 00000000:00000000 00:00000000 00000000  "
                + uid
                + "        0 48085 1 0000000000000000 100 0 0 10 0";
    }

    private List<Path> tables(final List<String> tcp, final List<String> tcp6) throws IOException {
        final Path four = dir.resolve("tcp");
        final Path six = dir.resolve("tcp6");
        Files.write(
                four,
                java.util.stream.Stream.concat(java.util.stream.Stream.of(TCP_HEAD), tcp.stream())
                        .toList(),
                StandardCharsets.US_ASCII);
        Files.write(
                six,
                java.util.stream.Stream.concat(java.util.stream.Stream.of(TCP6_HEAD), tcp6.stream())
                        .toList(),
                StandardCharsets.US_ASCII);
        return List.of(four, six);
    }

    @Test
    @DisplayName("The account's own listener on the port is let through")
    void ownListener() throws IOException {
        final List<Path> tables = tables(List.of(tcp("0100007F:1F48", "0A", OWN)), List.of());

        assertThatCode(() -> Listener.ensureOwn(HOMESERVER, tables, OWN))
                .as("the account's own listener")
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Another uid listening on 127.0.0.1 at the port: 78, naming that uid")
    void anotherUidOnLoopback() throws IOException {
        final List<Path> tables = tables(List.of(tcp("0100007F:1F48", "0A", 1009)), List.of());

        assertThatThrownBy(() -> Listener.ensureOwn(HOMESERVER, tables, OWN))
                .as("another uid on 127.0.0.1")
                .isInstanceOfSatisfying(
                        Failure.class,
                        failure ->
                                assertThat(failure.exitCode())
                                        .as("refused as configuration")
                                        .isEqualTo(Exit.CONFIG))
                .hasMessageContaining("127.0.0.1:8008")
                .hasMessageContaining("uid 1009");
    }

    @Test
    @DisplayName("Another uid on 0.0.0.0, which takes connections to 127.0.0.1 too: 78")
    void anotherUidOnTheWildcard() throws IOException {
        final List<Path> tables = tables(List.of(tcp("00000000:1F48", "0A", 1009)), List.of());

        assertThatThrownBy(() -> Listener.ensureOwn(HOMESERVER, tables, OWN))
                .as("another uid on 0.0.0.0")
                .isInstanceOf(Failure.class);
    }

    @Test
    @DisplayName("Another uid in the IPv6 table, on :: or 127.0.0.1 mapped into it: 78")
    void anotherUidInTheIpv6Table() throws IOException {
        for (final String local :
                List.of(
                        "00000000000000000000000000000000:1F48",
                        "0000000000000000FFFF00000100007F:1F48")) {
            final List<Path> tables = tables(List.of(), List.of(tcp6(local, "0A", 1009)));

            assertThatThrownBy(() -> Listener.ensureOwn(HOMESERVER, tables, OWN))
                    .as(local)
                    .isInstanceOf(Failure.class);
        }
    }

    @Test
    @DisplayName(
            "Another uid's socket on another port, or one only connected to the port, is no listener of it")
    void notTheListenerOfThePort() throws IOException {
        final List<Path> tables =
                tables(
                        List.of(
                                tcp("0100007F:1F49", "0A", 1009),
                                tcp("0100007F:1F48", "01", 1009),
                                tcp("0100007F:1F48", "0A", OWN)),
                        List.of());

        assertThatCode(() -> Listener.ensureOwn(HOMESERVER, tables, OWN))
                .as("another port, and a connected socket, are no listeners of the port")
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName(
            "Another uid on ::1 alone is no listener of 127.0.0.1, which it can never answer; for ::1 it is 78")
    void ipv6LoopbackIsAnotherAddress() throws IOException {
        final List<Path> tables =
                tables(
                        List.of(tcp("0100007F:1F48", "0A", OWN)),
                        List.of(tcp6("00000000000000000000000001000000:1F48", "0A", 1009)));

        assertThatCode(() -> Listener.ensureOwn(HOMESERVER, tables, OWN))
                .as("127.0.0.1 is the account's own")
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> Listener.ensureOwn(URI.create("http://[::1]:8008"), tables, OWN))
                .as("::1 is another uid's")
                .isInstanceOf(Failure.class);
    }

    @Test
    @DisplayName("No IPv6 table, where IPv6 is off, is no failure; no IPv4 table is 78")
    void missingTables() throws IOException {
        final List<Path> both = tables(List.of(tcp("0100007F:1F48", "0A", OWN)), List.of());
        Files.delete(both.get(1));
        assertThatCode(() -> Listener.ensureOwn(HOMESERVER, both, OWN))
                .as("no IPv6 table")
                .doesNotThrowAnyException();

        Files.delete(both.get(0));
        assertThatThrownBy(() -> Listener.ensureOwn(HOMESERVER, both, OWN))
                .as("no IPv4 table")
                .isInstanceOf(Failure.class)
                .hasMessageContaining("cannot read");
    }

    @Test
    @DisplayName("The process's own uid is read from its status")
    void ownUidFromStatus() throws Exception {
        final Path status =
                Files.writeString(
                        dir.resolve("status"),
                        "Name:\tjava\nUmask:\t0002\nState:\tS (sleeping)\nUid:\t1009\t1009\t1009\t1009\n");

        assertThat(Listener.ownUid(status)).as("the real uid, the first of four").isEqualTo(1009);
    }
}
