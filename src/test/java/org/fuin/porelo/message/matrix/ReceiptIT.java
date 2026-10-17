package org.fuin.porelo.message.matrix;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code receipt} against the real homeserver: the reference {@code send} prints, handed back as
 * Porelo does, and the read receipts a person's client or a task's account leaves in the room.
 */
class ReceiptIT {

    @TempDir private Path dir;

    private Tuwunel tuwunel;

    private Tuwunel.Account alice;

    private Tuwunel.Account bob;

    private String room;

    private final ByteArrayOutputStream errBytes = new ByteArrayOutputStream();

    @BeforeEach
    void setUp() throws IOException, InterruptedException {
        tuwunel = Tuwunel.start();
        alice = tuwunel.register("alice");
        bob = tuwunel.register("bob");
        room = tuwunel.createRoom(alice, bob);
    }

    @AfterEach
    void tearDown() throws IOException, InterruptedException {
        tuwunel.close();
    }

    private Map<String, String> env(final Tuwunel.Account account) {
        return Map.of(Config.HOMESERVER, tuwunel.url(), Config.ACCESS_TOKEN, account.accessToken());
    }

    /** Sends as alice and answers the reference send printed, as Porelo keeps it. */
    private String send(final String message) throws IOException {
        final Path file = Files.writeString(dir.resolve("m.json"), message, StandardCharsets.UTF_8);
        final Path sig = Files.write(dir.resolve("m.json.sig"), new byte[] {1, 2, 3});
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        assertThat(
                        Transport.run(
                                new String[] {
                                    "send", file.toString(), sig.toString(), "--to", room
                                },
                                env(alice),
                                new PrintStream(out, true, StandardCharsets.UTF_8),
                                new PrintStream(errBytes, true, StandardCharsets.UTF_8)))
                .as(() -> errBytes.toString(StandardCharsets.UTF_8))
                .isEqualTo(Exit.OK);
        return MatrixClient.JSON.readTree(out.toByteArray()).path("reference").asText();
    }

    private JsonNode receipt(final String reference, final String by) throws IOException {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        assertThat(
                        Transport.run(
                                new String[] {"receipt", reference, "--by", by},
                                env(alice),
                                new PrintStream(out, true, StandardCharsets.UTF_8),
                                new PrintStream(errBytes, true, StandardCharsets.UTF_8)))
                .as(() -> errBytes.toString(StandardCharsets.UTF_8))
                .isEqualTo(Exit.OK);
        return MatrixClient.JSON.readTree(out.toByteArray());
    }

    @Test
    @DisplayName(
            "A message the addressee has not read is delivered, and read once it has, with when")
    void deliveredThenRead() throws IOException, InterruptedException {
        final String reference = send("{\"n\":1}");
        assertThat(reference).startsWith("$");

        assertThat(receipt(reference, bob.userId()).path("state").asText())
                .as(() -> errBytes.toString(StandardCharsets.UTF_8))
                .isEqualTo("delivered");
        assertThat(receipt(reference, bob.userId()).has("at")).isFalse();

        final Instant before = Instant.now().minusSeconds(5);
        tuwunel.read(bob, room, reference);

        final JsonNode answer = receipt(reference, bob.userId());
        assertThat(answer.path("state").asText())
                .as(() -> errBytes.toString(StandardCharsets.UTF_8))
                .isEqualTo("read");
        assertThat(Instant.parse(answer.path("at").asText()))
                .isAfter(before)
                .isBefore(Instant.now().plusSeconds(5));
    }

    @Test
    @DisplayName(
            "The read verb, run with the addressee's token, makes the sender's receipt answer read")
    void readVerbMarksRead() throws IOException {
        final String reference = send("{\"n\":1}");
        assertThat(receipt(reference, bob.userId()).path("state").asText()).isEqualTo("delivered");

        final PrintStream err = new PrintStream(errBytes, true, StandardCharsets.UTF_8);
        assertThat(Transport.run(new String[] {"read", reference}, env(bob), err, err))
                .as(() -> errBytes.toString(StandardCharsets.UTF_8))
                .isEqualTo(Exit.OK);

        assertThat(receipt(reference, bob.userId()).path("state").asText())
                .as(() -> errBytes.toString(StandardCharsets.UTF_8))
                .isEqualTo("read");
    }

    @Test
    @DisplayName(
            "Reading a later message counts as reading the earlier ones, and not the other way round")
    void aReceiptReadsUpTo() throws IOException, InterruptedException {
        final String first = send("{\"n\":1}");
        final String second = send("{\"n\":2}");
        final String third = send("{\"n\":3}");

        tuwunel.read(bob, room, second);

        assertThat(receipt(first, bob.userId()).path("state").asText())
                .as(() -> errBytes.toString(StandardCharsets.UTF_8))
                .isEqualTo("read");
        assertThat(receipt(second, bob.userId()).path("state").asText())
                .as(() -> errBytes.toString(StandardCharsets.UTF_8))
                .isEqualTo("read");
        assertThat(receipt(third, bob.userId()).path("state").asText())
                .as(() -> errBytes.toString(StandardCharsets.UTF_8))
                .isEqualTo("delivered");
    }

    @Test
    @DisplayName(
            "An event the account cannot find, or an account not in the room, is unknown - not a failure")
    void cannotSay() throws IOException {
        final String reference = send("{\"n\":1}");

        assertThat(receipt("$doesNotExist", bob.userId()).path("state").asText())
                .as(() -> errBytes.toString(StandardCharsets.UTF_8))
                .isEqualTo("unknown");
        assertThat(receipt(reference, "@carol:" + Tuwunel.SERVER_NAME).path("state").asText())
                .as(() -> errBytes.toString(StandardCharsets.UTF_8))
                .isEqualTo("unknown");
    }

    @Test
    @DisplayName("Arguments not in the contract's shape are 64")
    void wrongArguments() {
        final PrintStream err = new PrintStream(errBytes, true, StandardCharsets.UTF_8);
        assertThat(Transport.run(new String[] {"receipt", "$e"}, env(alice), err, err))
                .isEqualTo(Exit.USAGE);
        assertThat(
                        Transport.run(
                                new String[] {"receipt", "e f", "--by", bob.userId()},
                                env(alice),
                                err,
                                err))
                .isEqualTo(Exit.USAGE);
        assertThat(
                        Transport.run(
                                new String[] {"receipt", "$e", "--by", "bob"},
                                env(alice),
                                err,
                                err))
                .isEqualTo(Exit.USAGE);
    }
}
