package org.fuin.porelo.message.matrix;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GateLoginTokensTest {
    @TempDir private Path dir;

    /** Harm guarded: a stolen or replayed login token produces a second session. */
    @Test
    void tokensAreSingleUseAndBoundToTheLogin() throws Exception {
        final GateLoginTokens tokens =
                new GateLoginTokens(dir, Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));
        final String token = tokens.issue("alice");
        assertThat(tokens.consume(token, "bob")).as("another login cannot consume it").isFalse();
        try (var pool = Executors.newFixedThreadPool(2)) {
            final var first = pool.submit(() -> tokens.consume(token, "alice"));
            final var second =
                    pool.submit(
                            () ->
                                    new GateLoginTokens(
                                                    dir, Clock.fixed(Instant.EPOCH, ZoneOffset.UTC))
                                            .consume(token, "alice"));
            assertThat((first.get() ? 1 : 0) + (second.get() ? 1 : 0))
                    .as("one concurrent consumer wins")
                    .isEqualTo(1);
        }
        assertThat(tokens.consume(token, "alice")).as("no replay").isFalse();
    }

    /** Harm guarded: token files disclose bearer credentials or survive their expiry. */
    @Test
    void keepsOnlyPrivateHashesAndRemovesExpiredTokens() throws Exception {
        final var start = Clock.fixed(Instant.EPOCH, ZoneOffset.UTC);
        final var tokens = new GateLoginTokens(dir, start);
        final String token = tokens.issue("alice");
        try (var files = Files.list(dir)) {
            for (final Path file : files.toList()) {
                assertThat(
                                file.getFileName().toString().contains(token)
                                        || Files.readString(file).contains(token))
                        .as("the credential is never stored")
                        .isFalse();
                assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(file)))
                        .as("private token record")
                        .isEqualTo("rw-------");
            }
        }
        final var expired =
                new GateLoginTokens(dir, Clock.offset(start, java.time.Duration.ofSeconds(120)));
        assertThat(expired.consume(token, "alice")).as("two-minute expiry").isFalse();
        final String old = tokens.issue("alice");
        expired.issue("bob");
        assertThat(tokens.consume(old, "alice")).as("issuing removes expired records").isFalse();
    }

    /**
     * Harm guarded: concurrent SSO starts lose records or crash instead of creating independent
     * tokens.
     */
    @Test
    void concurrentIssuersKeepBothTokens() throws Exception {
        final Clock clock = Clock.fixed(Instant.EPOCH, ZoneOffset.UTC);
        try (var pool = Executors.newFixedThreadPool(2)) {
            final var first = pool.submit(() -> new GateLoginTokens(dir, clock).issue("alice"));
            final var second = pool.submit(() -> new GateLoginTokens(dir, clock).issue("bob"));
            final String one = first.get();
            final String two = second.get();
            assertThat(one.equals(two)).as("independent credentials").isFalse();
            assertThat(new GateLoginTokens(dir, clock).consume(one, "alice"))
                    .as("first record survives")
                    .isTrue();
            assertThat(new GateLoginTokens(dir, clock).consume(two, "bob"))
                    .as("second record survives")
                    .isTrue();
        }
    }

    /** Harm guarded: two independently activated gate processes redeem the same token. */
    @Test
    void separateProcessesConsumeATokenOnlyOnce() throws Exception {
        final var tokens = new GateLoginTokens(dir, Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));
        final java.util.List<String> credentials = new java.util.ArrayList<>();
        for (int i = 0; i < 16; i++) {
            credentials.add(tokens.issue("alice"));
        }
        final Process one = worker();
        final Process two = worker();
        final var exchange =
                new java.util.concurrent.FutureTask<Void>(
                        () -> {
                            final var first =
                                    new java.io.BufferedReader(
                                            new java.io.InputStreamReader(
                                                    one.getInputStream(),
                                                    java.nio.charset.StandardCharsets.UTF_8));
                            final var second =
                                    new java.io.BufferedReader(
                                            new java.io.InputStreamReader(
                                                    two.getInputStream(),
                                                    java.nio.charset.StandardCharsets.UTF_8));
                            assertThat(first.readLine())
                                    .as("first process is ready")
                                    .isEqualTo("ready");
                            assertThat(second.readLine())
                                    .as("second process is ready")
                                    .isEqualTo("ready");
                            for (final String token : credentials) {
                                final byte[] input =
                                        (token + "\n")
                                                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
                                one.getOutputStream().write(input);
                                two.getOutputStream().write(input);
                                one.getOutputStream().flush();
                                two.getOutputStream().flush();
                                final String left = first.readLine();
                                final String right = second.readLine();
                                assertThat(left)
                                        .as("first worker returns a boolean")
                                        .isIn("true", "false");
                                assertThat(right)
                                        .as("second worker returns a boolean")
                                        .isIn("true", "false");
                                assertThat(java.util.List.of(left, right))
                                        .as("exactly one process redeems each credential")
                                        .containsExactlyInAnyOrder("true", "false");
                            }
                            one.getOutputStream().close();
                            two.getOutputStream().close();
                            assertThat(one.waitFor(20, java.util.concurrent.TimeUnit.SECONDS))
                                    .as("first worker exits")
                                    .isTrue();
                            assertThat(two.waitFor(20, java.util.concurrent.TimeUnit.SECONDS))
                                    .as("second worker exits")
                                    .isTrue();
                            assertThat(one.exitValue()).as("first worker did not crash").isZero();
                            assertThat(two.exitValue()).as("second worker did not crash").isZero();
                            return null;
                        });
        Thread.ofPlatform().daemon().start(exchange);
        try {
            exchange.get(20, java.util.concurrent.TimeUnit.SECONDS);
        } finally {
            one.destroyForcibly().waitFor();
            two.destroyForcibly().waitFor();
            exchange.cancel(true);
        }
    }

    private Process worker() throws java.io.IOException {
        final ProcessBuilder builder =
                new ProcessBuilder(
                                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                                "-cp",
                                System.getProperty(
                                        "surefire.test.class.path",
                                        System.getProperty("java.class.path")),
                                TokenConsumer.class.getName(),
                                dir.toString())
                        .redirectError(ProcessBuilder.Redirect.DISCARD);
        builder.environment().clear();
        return builder.start();
    }

    /** A separate gate process; credentials arrive only on stdin and only booleans leave. */
    public static final class TokenConsumer {
        private TokenConsumer() {}

        /** Consumes the credentials supplied on stdin with a deterministic clock. */
        public static void main(final String[] args) throws Exception {
            final var tokens =
                    new GateLoginTokens(
                            Path.of(args[0]), Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));
            final var in =
                    new java.io.BufferedReader(
                            new java.io.InputStreamReader(
                                    System.in, java.nio.charset.StandardCharsets.UTF_8));
            System.out.println("ready");
            System.out.flush();
            String token;
            while ((token = in.readLine()) != null) {
                System.out.println(tokens.consume(token, "alice"));
                System.out.flush();
            }
        }
    }

    /** Harm guarded: one unreadable record stops every person's sign-in on the host. */
    @Test
    void anUnreadableRecordIsRemovedNotFatal() throws Exception {
        final GateLoginTokens tokens =
                new GateLoginTokens(dir, Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));
        tokens.issue("alice");
        final Path poison = dir.resolve("0".repeat(64));
        Files.writeString(poison, "");
        Files.setPosixFilePermissions(poison, PosixFilePermissions.fromString("rw-------"));

        assertThat(tokens.issue("bob")).as("issued beside it").isNotEmpty();
        assertThat(poison).as("removed").doesNotExist();
    }

    /** Harm guarded: a loop fills the directory every issue and consume reads under one lock. */
    @Test
    void aLoginHoldsAtMostSoManyOpenTokens() throws Exception {
        final GateLoginTokens tokens =
                new GateLoginTokens(dir, Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));
        for (int i = 0; i < GateLoginTokens.OPEN_PER_LOGIN; i++) {
            tokens.issue("alice");
        }
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> tokens.issue("alice"))
                .isInstanceOf(java.io.IOException.class);
        assertThat(tokens.issue("bob")).as("another login is not held up").isNotEmpty();
    }
}
