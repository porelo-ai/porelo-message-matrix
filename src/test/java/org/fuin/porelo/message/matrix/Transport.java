package org.fuin.porelo.message.matrix;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Runs one verb the way the integration tests need: in this JVM through {@link Main#run}, or - when
 * the build names the native executable in {@value #EXECUTABLE} - as its own process with only the
 * environment given, the way Porelo starts it. A native image leaves out what it cannot see being
 * reached, so only the second proves the program Porelo runs.
 */
final class Transport {

    static final String EXECUTABLE = "transport.executable";

    private static final long TIMEOUT_SECONDS = 120;

    record Result(int exit, String out, String err) {}

    private Transport() {}

    static boolean isNative() {
        final String executable = System.getProperty(EXECUTABLE);
        return executable != null && !executable.isBlank();
    }

    /** In the shape of {@link Main#run}, so a test reads the same either way. */
    static int run(
            final String[] args,
            final Map<String, String> env,
            final PrintStream out,
            final PrintStream err) {
        final Result result = run(env, args);
        out.print(result.out());
        out.flush();
        err.print(result.err());
        err.flush();
        return result.exit();
    }

    static Result run(final Map<String, String> env, final String... args) {
        return runWithInput(env, "", args);
    }

    /**
     * With {@code stdin} given to the verb, as Porelo gives the lifecycle verbs the project's
     * settings.
     */
    static Result runWithInput(
            final Map<String, String> env, final String stdin, final String... args) {
        return isNative()
                ? process(Path.of(System.getProperty(EXECUTABLE)), env, stdin, args)
                : inJvm(env, stdin, args);
    }

    private static Result inJvm(
            final Map<String, String> env, final String stdin, final String... args) {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        final ByteArrayOutputStream err = new ByteArrayOutputStream();
        final int exit =
                Main.run(
                        args,
                        env,
                        new java.io.ByteArrayInputStream(stdin.getBytes(StandardCharsets.UTF_8)),
                        new PrintStream(out, true, StandardCharsets.UTF_8),
                        new PrintStream(err, true, StandardCharsets.UTF_8));
        return new Result(
                exit, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    private static Result process(
            final Path executable,
            final Map<String, String> env,
            final String stdin,
            final String... args) {
        if (!Files.isExecutable(executable)) {
            throw new IllegalStateException(
                    EXECUTABLE + " names " + executable + ", which is not an executable");
        }
        try {
            final Path out = Files.createTempFile("transport-", ".out");
            final Path err = Files.createTempFile("transport-", ".err");
            final Path in =
                    Files.writeString(
                            Files.createTempFile("transport-", ".in"),
                            stdin,
                            StandardCharsets.UTF_8);
            try {
                final List<String> command = new ArrayList<>(List.of(executable.toString()));
                command.addAll(List.of(args));
                final ProcessBuilder builder =
                        new ProcessBuilder(command)
                                .redirectInput(in.toFile())
                                .redirectOutput(out.toFile())
                                .redirectError(err.toFile());
                // Only what the test gives, as Porelo gives a transport only what it needs.
                builder.environment().clear();
                builder.environment().putAll(env);
                final Process process = builder.start();
                if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                    throw new IllegalStateException(
                            executable + " did not finish within " + TIMEOUT_SECONDS + " s");
                }
                return new Result(
                        process.exitValue(),
                        Files.readString(out, StandardCharsets.UTF_8),
                        Files.readString(err, StandardCharsets.UTF_8));
            } finally {
                Files.deleteIfExists(out);
                Files.deleteIfExists(err);
                Files.deleteIfExists(in);
            }
        } catch (final IOException ex) {
            throw new UncheckedIOException(ex);
        } catch (final InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while waiting for " + executable, ex);
        }
    }
}
