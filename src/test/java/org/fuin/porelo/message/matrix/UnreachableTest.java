package org.fuin.porelo.message.matrix;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.ConnectException;
import java.net.ServerSocket;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.nio.channels.UnresolvedAddressException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** An unreachable homeserver is temporary - and stderr says why, so a stopped one and a wrong name differ. */
class UnreachableTest {

    private final ByteArrayOutputStream errBytes = new ByteArrayOutputStream();

    private int check(final String homeserver) {
        final PrintStream err = new PrintStream(errBytes, true, StandardCharsets.UTF_8);
        return Main.run(new String[] {"poll", "--into", System.getProperty("java.io.tmpdir")},
                Map.of(Config.HOMESERVER, homeserver, Config.ACCESS_TOKEN, "t", "XDG_STATE_HOME", System.getProperty("java.io.tmpdir")),
                new PrintStream(OutputStream.nullOutputStream()), err);
    }

    @Test
    @DisplayName("Nothing listening on the port: 75, saying the connection was not accepted")
    void refused() throws IOException {
        final int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }

        assertThat(check("http://127.0.0.1:" + closedPort)).isEqualTo(Exit.TEMPORARY);
        assertThat(errBytes.toString(StandardCharsets.UTF_8)).contains("not reachable: the connection was not accepted (refused, or no route)");
    }

    @Test
    @DisplayName("A name that does not resolve: 75, saying there is no such host")
    void noSuchHost() {
        assertThat(check("https://no-such-host.invalid:8008")).isEqualTo(Exit.TEMPORARY);
        assertThat(errBytes.toString(StandardCharsets.UTF_8)).contains("not reachable: no such host");
    }

    @Test
    @DisplayName("Timeouts and messageless failures are named too, never as null")
    void otherCauses() {
        assertThat(MatrixClient.cause(new HttpConnectTimeoutException("x"))).isEqualTo("timed out connecting");
        assertThat(MatrixClient.cause(new HttpTimeoutException("x"))).isEqualTo("no answer within 60 s");
        assertThat(MatrixClient.cause(new ConnectException(null))).isEqualTo("the connection was not accepted (refused, or no route)");
        assertThat(MatrixClient.cause(new IOException())).isEqualTo("java.io.IOException");
        final IOException wrapped = new IOException(null, new UnresolvedAddressException());
        assertThat(MatrixClient.cause(wrapped)).isEqualTo("no such host");
        assertThat(MatrixClient.cause(new IOException("broken pipe", new IOException()))).isEqualTo("broken pipe");
    }

}
