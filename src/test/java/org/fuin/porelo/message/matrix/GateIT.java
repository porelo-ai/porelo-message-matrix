package org.fuin.porelo.message.matrix;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/** The real homeserver and inherited Unix socket, on the JVM and with the native executable. */
class GateIT {
    @TempDir private Path dir;

    /**
     * Harm guarded: identity and revocation checks disappear in the executable or across real
     * sockets.
     */
    @Test
    @Timeout(180)
    void aHostLoginRegistersSignsInSyncsAndSendsThroughItsGate() throws Exception {
        final String login = System.getProperty("user.name");
        assertThat(GateRules.login(login))
                .as("the integration account must be a valid host login")
                .isTrue();
        final Map<String, String> env = new HashMap<>();
        env.put("HOME", dir.resolve("home").toString());
        env.put("XDG_CONFIG_HOME", dir.resolve("config").toString());
        env.put("XDG_STATE_HOME", dir.resolve("state").toString());
        env.put("XDG_RUNTIME_DIR", dir.resolve("runtime").toString());
        final Path group =
                Files.writeString(dir.resolve("group"), "porelo-people:x:101:" + login + "\n");
        env.put("PORELO_MATRIX_GATE_GROUP_FILE", group.toString());
        final Path conf = LocalHomeserver.directory(env).resolve("homeserver.conf");
        Files.createDirectories(conf.getParent());
        try (var port =
                new java.net.ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())) {
            Files.writeString(conf, "PORELO_MATRIX_PORT=" + port.getLocalPort() + "\n");
        }
        assertThat(Transport.run(env, "gate", "--prepare").exit())
                .as("gate mode before first start")
                .isZero();
        final Path binary = ProcessUnit.binary(Files.createDirectories(dir.resolve("bin")));
        try (ProcessUnit homeserver = new ProcessUnit(env, binary, dir.resolve("unit"))) {
            homeserver.start();
            awaitHomeserver(env, homeserver);
            final Transport.Result prepared = Transport.run(env, "gate", "--prepare");
            assertThat(prepared.exit()).as("administrator registered").isZero();
            assertThat(MatrixClient.JSON.readTree(prepared.out()).path("administrator").asBoolean())
                    .as("administrator is ready")
                    .isTrue();
            final Path admin = GateFiles.directory(env).resolve("admin-token");
            final var modified = Files.getLastModifiedTime(admin);
            assertThat(Transport.run(env, "gate", "--prepare").exit())
                    .as("repeat preparation")
                    .isZero();
            assertThat(Files.getLastModifiedTime(admin))
                    .as("existing token is retained")
                    .isEqualTo(modified);
            final Path listener = dir.resolve("client.sock");
            final Process gate = startGate(env, listener);
            try {
                awaitSocket(listener, gate);
                final MatrixClient client =
                        new MatrixClient(
                                new Config(
                                        URI.create("unix:" + listener.toUri().getRawPath()),
                                        "",
                                        null,
                                        true));
                final String password = Accounts.secret();
                final ObjectNode registration =
                        MatrixClient.JSON.createObjectNode().put("username", login);
                assertThat(
                                client.exchange("POST", "/_matrix/client/v3/register", registration)
                                        .status())
                        .as("dummy UIA challenge")
                        .isEqualTo(401);
                registration
                        .put("password", password)
                        .putObject("auth")
                        .put("type", "m.login.dummy");
                final MatrixClient.Answer registered =
                        client.exchange("POST", "/_matrix/client/v3/register", registration);
                assertThat(registered.status()).as("own account registered").isEqualTo(200);
                assertThat(registered.body().path("user_id").asText())
                        .as("registration identity")
                        .startsWith("@" + login + ":");
                assertThat(
                                client.exchange(
                                                "POST",
                                                "/_matrix/client/v3/register",
                                                MatrixClient.JSON
                                                        .createObjectNode()
                                                        .put("username", "x.another-login"))
                                        .status())
                        .as("foreign registration")
                        .isEqualTo(400);
                concurrentRegistrationKeepsTheWinningPassword(client, login);
                final String task = "task." + login;
                registration.put("username", task);
                assertThat(
                                client.exchange("POST", "/_matrix/client/v3/register", registration)
                                        .status())
                        .as("own task registered")
                        .isEqualTo(200);
                final String target =
                        "/_matrix/client/v3/login/sso/redirect/porelo?redirectUrl="
                                + MatrixClient.segment("element://client/callback?x=1#keep");
                final Wire.Reply redirect = client.rawExchange("GET", target, null);
                assertThat(redirect.statusCode()).as("SSO redirect").isEqualTo(302);
                final String location = header(redirect.raw(), "Location");
                final String token =
                        GateRequest.query(URI.create(location).getRawQuery()).get("loginToken");
                final ObjectNode credentials =
                        MatrixClient.JSON
                                .createObjectNode()
                                .put("type", "m.login.token")
                                .put("token", token);
                final MatrixClient.Answer signedIn =
                        client.exchange("POST", "/_matrix/client/v3/login", credentials);
                assertThat(signedIn.status())
                        .as("gate token signs the host login in")
                        .isEqualTo(200);
                assertThat(signedIn.body().path("user_id").asText())
                        .as("SSO identity")
                        .isEqualTo(registered.body().path("user_id").asText());
                assertThat(
                                client.exchange("POST", "/_matrix/client/v3/login", credentials)
                                        .status())
                        .as("single use")
                        .isEqualTo(403);
                final MatrixClient person =
                        client.as(signedIn.body().path("access_token").asText());
                final String room =
                        person.post(
                                        "/_matrix/client/v3/createRoom",
                                        MatrixClient.JSON
                                                .createObjectNode()
                                                .put("preset", "private_chat"))
                                .path("room_id")
                                .asText();
                person.put(
                        "/_matrix/client/v3/rooms/"
                                + MatrixClient.segment(room)
                                + "/send/m.room.message/one",
                        MatrixClient.JSON
                                .createObjectNode()
                                .put("msgtype", "m.text")
                                .put("body", "through the gate"));
                final JsonNode sync = person.get("/_matrix/client/v3/sync?timeout=0");
                final long start = System.nanoTime();
                assertThat(
                                person.get(
                                                "/_matrix/client/v3/sync?timeout=1500&since="
                                                        + MatrixClient.segment(
                                                                sync.path("next_batch").asText()))
                                        .has("next_batch"))
                        .as("long poll completes")
                        .isTrue();
                assertThat(Duration.ofNanos(System.nanoTime() - start).toMillis())
                        .as("idle sync was held open")
                        .isGreaterThanOrEqualTo(1000);
                final JsonNode messages =
                        person.get(
                                "/_matrix/client/v3/rooms/"
                                        + MatrixClient.segment(room)
                                        + "/messages?dir=b");
                assertThat(messages.path("chunk").findValuesAsText("body"))
                        .as("sent message is visible")
                        .contains("through the gate");
                try (SocketChannel connection =
                        SocketChannel.open(java.net.StandardProtocolFamily.UNIX)) {
                    connection.connect(UnixDomainSocketAddress.of(listener));
                    final var out = Channels.newOutputStream(connection);
                    final var in = new BufferedInputStream(Channels.newInputStream(connection));
                    final byte[] request =
                            "GET /_matrix/client/v3/login HTTP/1.1\r\nHost: localhost\r\n\r\n"
                                    .getBytes(StandardCharsets.US_ASCII);
                    out.write(request);
                    out.flush();
                    assertThat(SocketWire.read(in, "GET").statusCode())
                            .as("first request is admitted")
                            .isEqualTo(200);
                    Files.writeString(group, "porelo-people:x:101:\n");
                    out.write(request);
                    out.flush();
                    assertThat(SocketWire.read(in, "GET").statusCode())
                            .as("the next request on the same connection is refused")
                            .isEqualTo(403);
                }
            } finally {
                gate.destroy();
                if (!gate.waitFor(5, TimeUnit.SECONDS)) {
                    gate.destroyForcibly().waitFor();
                }
            }
            Files.delete(admin);
            assertThat(Transport.run(env, "gate", "--prepare").exit())
                    .as("existing administrator is never taken over")
                    .isEqualTo(78);
        }
    }

    /**
     * Harm guarded: the transport's own verbs need a registration token or a TCP port that a host's
     * homeserver behind its gate no longer offers, so a person's Porelo cannot work there at all.
     */
    @Test
    @Timeout(240)
    void thePersonsPoreloMakesItsRoomAndCarriesAMessageThroughTheGate() throws Exception {
        final String login = System.getProperty("user.name");
        final Map<String, String> env = new HashMap<>();
        env.put("HOME", dir.resolve("home").toString());
        env.put("XDG_CONFIG_HOME", dir.resolve("config").toString());
        env.put("XDG_STATE_HOME", dir.resolve("state").toString());
        env.put("XDG_RUNTIME_DIR", dir.resolve("runtime").toString());
        env.put(
                "PORELO_MATRIX_GATE_GROUP_FILE",
                Files.writeString(dir.resolve("group"), "porelo-people:x:101:" + login + "\n")
                        .toString());
        final Path admins = Files.createDirectories(dir.resolve("admins"));
        Files.writeString(admins.resolve("demo.admins"), login + "\n");
        env.put("PORELO_MATRIX_GATE_ADMINS_DIR", admins.toString());
        final Path conf = LocalHomeserver.directory(env).resolve("homeserver.conf");
        Files.createDirectories(conf.getParent());
        try (var port =
                new java.net.ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())) {
            Files.writeString(conf, "PORELO_MATRIX_PORT=" + port.getLocalPort() + "\n");
        }
        assertThat(Transport.run(env, "gate", "--prepare").exit()).as("gate mode").isZero();
        final Path binary = ProcessUnit.binary(Files.createDirectories(dir.resolve("bin")));
        try (ProcessUnit homeserver = new ProcessUnit(env, binary, dir.resolve("unit"))) {
            homeserver.start();
            awaitHomeserver(env, homeserver);
            assertThat(Transport.run(env, "gate", "--prepare").exit())
                    .as("administrator registered")
                    .isZero();
            final Path listener = dir.resolve("client.sock");
            final Process gate = startGate(env, listener);
            try {
                awaitSocket(listener, gate);
                // The person's Porelo: its own state, and nothing of the homeserver's account.
                final Map<String, String> porelo =
                        Map.of("XDG_STATE_HOME", dir.resolve("porelo").toString());
                final String settings =
                        "{\"homeserver\":\"unix:"
                                + listener.toUri().getRawPath()
                                + "\",\"room\":\"#porelo-demo:localhost\"}";
                final Transport.Result setup =
                        Transport.runWithInput(
                                porelo,
                                settings,
                                "setup",
                                "--project",
                                "demo",
                                "--login",
                                login,
                                "--create-room");
                assertThat(setup.exit()).as("setup: %s", setup.err()).isZero();
                final JsonNode made = MatrixClient.JSON.readTree(setup.out());
                assertThat(made.path("secrets").path(Config.LOGIN).asText())
                        .as("the relay's secrets mark a central account")
                        .isEqualTo(login);
                final Map<String, String> lifecycle = new HashMap<>(porelo);
                made.path("account")
                        .properties()
                        .forEach(e -> lifecycle.put(e.getKey(), e.getValue().asText()));
                made.path("secrets")
                        .properties()
                        .forEach(e -> lifecycle.put(e.getKey(), e.getValue().asText()));
                final Transport.Result enroll =
                        Transport.runWithInput(
                                lifecycle,
                                settings,
                                "enroll",
                                "--project",
                                "demo",
                                "--task",
                                "worker",
                                "--login",
                                login);
                assertThat(enroll.exit()).as("enroll: %s", enroll.err()).isZero();
                final JsonNode enrolled = MatrixClient.JSON.readTree(enroll.out());
                assertThat(enrolled.path("address").asText())
                        .as("the task's account under the person's login")
                        .isEqualTo("@worker." + login + ":localhost");
                assertThat(enrolled.path("secrets").path(Config.LOGIN).asText())
                        .as("the task's secrets mark a central account")
                        .isEqualTo(login);
                final Map<String, String> task = new HashMap<>();
                enrolled.path("secrets")
                        .properties()
                        .forEach(e -> task.put(e.getKey(), e.getValue().asText()));
                final Path message = Files.writeString(dir.resolve("m.json"), "{\"to\":\"relay\"}");
                final Path sig = Files.write(dir.resolve("m.json.sig"), new byte[] {1, 2, 3});
                final Transport.Result sent =
                        Transport.run(
                                task,
                                "send",
                                message.toString(),
                                sig.toString(),
                                "--to",
                                made.path("conversation").asText());
                assertThat(sent.exit()).as("send: %s", sent.err()).isZero();
                final Map<String, String> relay = new HashMap<>(porelo);
                made.path("secrets")
                        .properties()
                        .forEach(e -> relay.put(e.getKey(), e.getValue().asText()));
                final Path inbound = Files.createDirectories(dir.resolve("in"));
                final Transport.Result polled =
                        Transport.run(relay, "poll", "--into", inbound.toString());
                assertThat(polled.exit()).as("poll: %s", polled.err()).isZero();
                try (var files = Files.list(inbound)) {
                    assertThat(
                                    files.filter(f -> f.toString().endsWith(".json"))
                                            .map(
                                                    f -> {
                                                        try {
                                                            return Files.readString(f);
                                                        } catch (final IOException ex) {
                                                            throw new IllegalStateException(ex);
                                                        }
                                                    })
                                            .toList())
                            .as("the task's message reaches the relay through the gate")
                            .containsExactly("{\"to\":\"relay\"}");
                }
            } finally {
                gate.destroy();
                if (!gate.waitFor(5, TimeUnit.SECONDS)) {
                    gate.destroyForcibly().waitFor();
                }
            }
        }
    }

    /**
     * Harm guarded: concurrent registration uses the admin PUT as a reset of an existing password.
     */
    private static void concurrentRegistrationKeepsTheWinningPassword(
            final MatrixClient client, final String login) throws Exception {
        final String localpart = "race." + login;
        final String firstPassword = Accounts.secret();
        final String secondPassword = Accounts.secret();
        final ObjectNode first = registration(localpart, firstPassword);
        final ObjectNode second = registration(localpart, secondPassword);
        final var barrier = new java.util.concurrent.CyclicBarrier(2);
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            final var one =
                    pool.submit(
                            () -> {
                                barrier.await();
                                return client.exchange(
                                        "POST", "/_matrix/client/v3/register", first);
                            });
            final var two =
                    pool.submit(
                            () -> {
                                barrier.await();
                                return client.exchange(
                                        "POST", "/_matrix/client/v3/register", second);
                            });
            final var left = one.get();
            final var right = two.get();
            assertThat(java.util.List.of(left.status(), right.status()))
                    .as("one account is created and one registration is refused")
                    .containsExactlyInAnyOrder(200, 400);
            final String winningPassword = left.status() == 200 ? firstPassword : secondPassword;
            final String losingPassword = left.status() == 200 ? secondPassword : firstPassword;
            assertThat(Accounts.login(client, localpart, winningPassword).userId())
                    .as("the winning password still signs in")
                    .startsWith("@" + localpart + ":");
            final ObjectNode wrong =
                    MatrixClient.JSON
                            .createObjectNode()
                            .put("type", "m.login.password")
                            .put("user", localpart)
                            .put("password", losingPassword);
            assertThat(client.exchange("POST", "/_matrix/client/v3/login", wrong).status())
                    .as("the losing request did not replace the password")
                    .isEqualTo(403);
        }
    }

    private static ObjectNode registration(final String localpart, final String password) {
        final ObjectNode request =
                MatrixClient.JSON
                        .createObjectNode()
                        .put("username", localpart)
                        .put("password", password);
        request.putObject("auth").put("type", "m.login.dummy");
        return request;
    }

    private Process startGate(final Map<String, String> env, final Path socket) throws IOException {
        final List<String> command =
                new ArrayList<>(
                        List.of(
                                "/usr/bin/systemd-socket-activate",
                                "--inetd",
                                "-a",
                                "-l",
                                socket.toString()));
        // systemd-socket-activate hands its child only the variables it is told to.
        env.keySet().stream().sorted().forEach(name -> command.add("--setenv=" + name));
        if (Transport.isNative()) {
            command.add(System.getProperty(Transport.EXECUTABLE));
        } else {
            command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
            command.add("--enable-native-access=ALL-UNNAMED");
            command.add("-cp");
            command.add(
                    System.getProperty(
                            "surefire.test.class.path", System.getProperty("java.class.path")));
            command.add(Main.class.getName());
        }
        command.add("gate");
        final ProcessBuilder builder = new ProcessBuilder(command);
        builder.environment().clear();
        builder.environment().putAll(env);
        builder.redirectError(
                ProcessBuilder.Redirect.appendTo(new java.io.File("target/gate-it.err")));
        builder.redirectOutput(ProcessBuilder.Redirect.DISCARD);
        return builder.start();
    }

    private static void awaitSocket(final Path socket, final Process process) throws Exception {
        final long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (!Files.exists(socket) && process.isAlive() && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertThat(Files.exists(socket) && process.isAlive())
                .as("systemd owns the listening socket")
                .isTrue();
    }

    private static void awaitHomeserver(final Map<String, String> env, final ProcessUnit unit)
            throws Exception {
        final long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        String previous = "";
        while (System.nanoTime() < deadline && unit.active()) {
            try {
                if (Files.exists(GateFiles.runtime(env).resolve("homeserver.sock"))
                        && new GateHomeserver(env)
                                        .client("")
                                        .exchange("GET", "/_matrix/client/versions", null)
                                        .status()
                                == 200) {
                    return;
                }
            } catch (final Failure failure) {
                final String current = Integer.toString(failure.exitCode());
                if (current.equals(previous) && !unit.active()) {
                    throw new IOException("the homeserver repeatedly failed during startup");
                }
                previous = current;
            }
            Thread.sleep(100);
        }
        throw new IOException("the private homeserver did not start");
    }

    private static String header(final byte[] raw, final String name) {
        for (final String line : new String(raw, StandardCharsets.ISO_8859_1).split("\r\n")) {
            if (line.regionMatches(true, 0, name + ":", 0, name.length() + 1)) {
                return line.substring(name.length() + 1).strip();
            }
        }
        throw new IllegalStateException("redirect answered without its location");
    }
}
