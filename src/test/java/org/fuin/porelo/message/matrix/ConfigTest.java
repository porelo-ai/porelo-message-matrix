package org.fuin.porelo.message.matrix;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Harm guarded: a token sent outside loopback, or to a socket resolved in the wrong directory. */
class ConfigTest {

    @Test
    void absoluteSocketIsAccepted() throws Failure {
        assertThat(
                        Config.from(
                                        Map.of(
                                                Config.HOMESERVER,
                                                "unix:/run/matrix.sock",
                                                Config.ACCESS_TOKEN,
                                                "t"))
                                .homeserver())
                .as("an absolute socket in the environment")
                .isEqualTo(URI.create("unix:/run/matrix.sock"));
    }

    @Test
    void relativeSocketIsRefused() {
        assertThatThrownBy(() -> Config.homeserver("unix:relative/path"))
                .as("a socket must not depend on the working directory")
                .isInstanceOfSatisfying(
                        Failure.class,
                        failure -> {
                            assertThat(failure.exitCode())
                                    .as("invalid configuration")
                                    .isEqualTo(Exit.CONFIG);
                            assertThat(failure)
                                    .as("the path constraint is actionable")
                                    .hasMessageContaining("absolute");
                        });
    }

    @Test
    void localhostSuffixIsLoopback() throws Failure {
        final URI url = URI.create("http://a.localhost:8008");
        assertThat(Lifecycle.loopback(url)).as("the DNS suffix denotes loopback").isTrue();
        assertThat(Config.homeserver(url.toString()))
                .as("plain HTTP stays on loopback")
                .isEqualTo(url);
    }

    @Test
    void embeddedLocalhostIsNotLoopback() {
        final URI url = URI.create("http://foo.localhost.example.org:8008");
        assertThat(Lifecycle.loopback(url))
                .as("localhost inside another domain is remote")
                .isFalse();
        assertThatThrownBy(() -> Config.homeserver(url.toString()))
                .as("no token may travel in plain HTTP to a remote host")
                .isInstanceOf(Failure.class)
                .hasMessageContaining("plain http on another host");
    }

    @Test
    void aSocketPathTheKernelCannotHoldIsRefused() {
        // Harm guarded: a path cut by the kernel reaches another socket than the one named.
        final String path = "/" + "s".repeat(107);
        assertThatThrownBy(() -> Config.socket(URI.create("unix:" + path), "test"))
                .as("a socket path of more than 107 bytes is refused")
                .isInstanceOf(Failure.class);
    }
}
