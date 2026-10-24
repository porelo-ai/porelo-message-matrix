package org.fuin.porelo.message.matrix;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@code send} against the real homeserver the transport is built for, on loopback in podman. */
class SendIT {

    private static final String MESSAGE = "Grüße aus dem Raum ✓\n\tline two\r\nline three\n";

    private static final byte[] SIGNATURE = {0, 1, 2, (byte) 0xfe, (byte) 0xff, '\n', '-', '-'};

    private static Tuwunel tuwunel;

    private static Tuwunel.Account alice;

    private static Tuwunel.Account bob;

    private static String room;

    @TempDir private Path dir;

    private final ByteArrayOutputStream errBytes = new ByteArrayOutputStream();

    @BeforeAll
    static void startHomeserver() throws IOException, InterruptedException {
        tuwunel = Tuwunel.start();
        alice = tuwunel.register("alice");
        bob = tuwunel.register("bob");
        room = tuwunel.createRoom(alice, bob);
    }

    @AfterAll
    static void stopHomeserver() throws IOException, InterruptedException {
        if (tuwunel != null) {
            tuwunel.close();
        }
    }

    private int send(final Tuwunel.Account from, final String to, final String message)
            throws IOException {
        final Path file =
                Files.writeString(dir.resolve("message"), message, StandardCharsets.UTF_8);
        final Path sig = Files.write(dir.resolve("message.sig"), SIGNATURE);
        return Transport.run(
                new String[] {"send", file.toString(), sig.toString(), "--to", to},
                Map.of(Config.HOMESERVER, tuwunel.url(), Config.ACCESS_TOKEN, from.accessToken()),
                new PrintStream(OutputStream.nullOutputStream()),
                new PrintStream(errBytes, true, StandardCharsets.UTF_8));
    }

    /** Harm guarded: a reply from Porelo must use the relay's identity, never a task's. */
    @Test
    void sendWithTheSetupTokenWritesAsTheRelay() throws Exception {
        final Map<String, String> env = new HashMap<>();
        env.put("HOME", dir.resolve("home").toString());
        env.put("XDG_CONFIG_HOME", dir.resolve("config").toString());
        env.put("XDG_STATE_HOME", dir.resolve("state").toString());
        final ByteArrayOutputStream setupOutput = new ByteArrayOutputStream();
        try (ProcessUnit unit =
                new ProcessUnit(env, ProcessUnit.binary(dir), dir.resolve("unit"))) {
            Lifecycle.run(
                    "setup",
                    List.of("--project", "delivery"),
                    env,
                    new ByteArrayInputStream("{}".getBytes(StandardCharsets.UTF_8)),
                    new PrintStream(setupOutput, true, StandardCharsets.UTF_8),
                    new PrintStream(errBytes, true, StandardCharsets.UTF_8),
                    unit);
            final JsonNode setup = MatrixClient.JSON.readTree(setupOutput.toByteArray());
            setup.path("secrets")
                    .properties()
                    .forEach(entry -> env.put(entry.getKey(), entry.getValue().asText()));
            final String conversation = setup.path("conversation").asText();
            final Path file = Files.writeString(dir.resolve("relay-message"), MESSAGE);
            final Path sig = Files.write(dir.resolve("relay-message.sig"), SIGNATURE);

            final Transport.Result sent =
                    Transport.run(
                            env, "send", file.toString(), sig.toString(), "--to", conversation);

            assertThat(sent.exit())
                    .as("the relay sends successfully: %s", sent.err())
                    .isEqualTo(Exit.OK);
            final String reference =
                    MatrixClient.JSON.readTree(sent.out()).path("reference").asText();
            final MatrixClient observer =
                    new MatrixClient(Config.from(env))
                            .as(setup.path("account").path(Config.ADMIN_TOKEN).asText());
            final JsonNode event =
                    observer.get(
                            "/_matrix/client/v3/rooms/"
                                    + MatrixClient.segment(conversation)
                                    + "/event/"
                                    + MatrixClient.segment(reference));
            assertThat(event.path("sender").asText())
                    .as("the homeserver attributes the reply to the relay")
                    .isEqualTo(
                            "@porelo-relay-delivery:"
                                    + AdminRoom.server(
                                            observer.get("/_matrix/client/v3/account/whoami")
                                                    .path("user_id")
                                                    .asText()));
            assertThat(event.path("content").path(Send.MESSAGE_FIELD).asText())
                    .as("the relay's message reaches the room whole")
                    .isEqualTo(MESSAGE);
        }
    }

    @Test
    @DisplayName(
            "The other account in the room receives the message byte for byte, with its signature, once - even when sent twice")
    void arrivesByteForByteOnce() throws IOException, InterruptedException {
        final String message = MESSAGE + "once\n";

        assertThat(send(alice, room, message))
                .as(errBytes.toString(StandardCharsets.UTF_8))
                .isEqualTo(Exit.OK);
        assertThat(send(alice, room, message))
                .as(errBytes.toString(StandardCharsets.UTF_8))
                .isEqualTo(Exit.OK);

        assertThat(tuwunel.messages(bob, room))
                .filteredOn(
                        event ->
                                event.path("content")
                                        .path(Send.MESSAGE_FIELD)
                                        .asText()
                                        .equals(message))
                .singleElement()
                .satisfies(
                        event -> {
                            final JsonNode content = event.path("content");
                            assertThat(
                                            content.path(Send.MESSAGE_FIELD)
                                                    .asText()
                                                    .getBytes(StandardCharsets.UTF_8))
                                    .isEqualTo(message.getBytes(StandardCharsets.UTF_8));
                            assertThat(
                                            Base64.getDecoder()
                                                    .decode(
                                                            content.path(Send.SIGNATURE_FIELD)
                                                                    .asText()))
                                    .isEqualTo(SIGNATURE);
                            assertThat(event.path("sender").asText()).isEqualTo(alice.userId());
                        });
    }

    @Test
    @DisplayName(
            "The largest message escaped at its worst, a 2 KiB signature and a long line: the homeserver takes it, the message whole and the line cut")
    void largestEventFits() throws IOException, InterruptedException {
        final String message = "\u0001".repeat(Describe.MAX_BYTES);
        final Path file =
                Files.writeString(dir.resolve("largest"), message, StandardCharsets.UTF_8);
        final Path sig = Files.write(dir.resolve("largest.sig"), new byte[2048]);
        final Path line =
                Files.writeString(dir.resolve("line"), "\"".repeat(40_000), StandardCharsets.UTF_8);

        assertThat(
                        Transport.run(
                                new String[] {
                                    "send",
                                    file.toString(),
                                    sig.toString(),
                                    "--to",
                                    room,
                                    "--shown",
                                    line.toString()
                                },
                                Map.of(
                                        Config.HOMESERVER,
                                        tuwunel.url(),
                                        Config.ACCESS_TOKEN,
                                        alice.accessToken()),
                                new PrintStream(OutputStream.nullOutputStream()),
                                new PrintStream(errBytes, true, StandardCharsets.UTF_8)))
                .as(errBytes.toString(StandardCharsets.UTF_8))
                .isEqualTo(Exit.OK);

        assertThat(tuwunel.messages(bob, room))
                .filteredOn(
                        event ->
                                event.path("content")
                                        .path(Send.MESSAGE_FIELD)
                                        .asText()
                                        .equals(message))
                .singleElement()
                .satisfies(
                        event ->
                                assertThat(event.path("content").path("body").asText())
                                        .startsWith("\"")
                                        .endsWith("… (cut; 40000 characters in all)"));
    }

    @Test
    @DisplayName("A room the account is not in is refused with 77, and nothing reaches it")
    void roomNotJoinedIsRefused() throws IOException, InterruptedException {
        final String alicesOwnRoom = tuwunel.createRoom(alice);

        assertThat(send(bob, alicesOwnRoom, MESSAGE)).isEqualTo(Exit.NOT_PERMITTED);
        assertThat(tuwunel.messages(alice, alicesOwnRoom)).isEmpty();
    }

    @Test
    @DisplayName("check answers 0 for a token the homeserver knows, and 2 for one it does not")
    void check() {
        final ByteArrayOutputStream err = new ByteArrayOutputStream();
        final PrintStream errStream = new PrintStream(err, true, StandardCharsets.UTF_8);

        assertThat(
                        Transport.run(
                                new String[] {"check"},
                                Map.of(
                                        Config.HOMESERVER,
                                        tuwunel.url(),
                                        Config.ACCESS_TOKEN,
                                        bob.accessToken()),
                                errStream,
                                errStream))
                .as(err.toString(StandardCharsets.UTF_8))
                .isEqualTo(Exit.OK);
        assertThat(
                        Transport.run(
                                new String[] {"check"},
                                Map.of(
                                        Config.HOMESERVER,
                                        tuwunel.url(),
                                        Config.ACCESS_TOKEN,
                                        "syt_not_a_token"),
                                errStream,
                                errStream))
                .isEqualTo(Check.NOT_USABLE);
        assertThat(err.toString(StandardCharsets.UTF_8)).contains("M_UNKNOWN_TOKEN");
    }

    @Test
    @DisplayName("A token the homeserver does not know is refused with 77")
    void unknownTokenIsRefused() throws IOException {
        assertThat(send(new Tuwunel.Account(bob.userId(), "syt_not_a_token"), room, MESSAGE))
                .isEqualTo(Exit.NOT_PERMITTED);
        assertThat(errBytes.toString(StandardCharsets.UTF_8))
                .contains("M_UNKNOWN_TOKEN")
                .doesNotContain("syt_not_a_token");
    }
}
