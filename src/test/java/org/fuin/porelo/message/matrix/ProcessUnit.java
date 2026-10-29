package org.fuin.porelo.message.matrix;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;

/**
 * The account's homeserver as the unit runs it - its port and server name from {@code
 * homeserver.conf}, its registration token and backup directory made as the unit's start makes
 * them, backups on {@code SIGUSR2} - but as a process of the test: Tuwunel's own binary, so neither
 * systemd nor a container start is needed. Its database is a directory of the test's.
 */
final class ProcessUnit implements Unit, AutoCloseable {

    private final Map<String, String> env;

    private final Path binary;

    private final Path data;

    private final Path log;

    private @Nullable Process process;

    /** How many times a run of its own was asked for, and with which arguments last. */
    private int runs;

    ProcessUnit(final Map<String, String> env, final Path binary, final Path dir) {
        this.env = env;
        this.binary = binary;
        this.data = dir.resolve("data");
        this.log = dir.resolve("log");
    }

    /**
     * Tuwunel's binary: the one {@link Tuwunel#BINARY} names, else copied out of the pinned image
     * into the directory given, so the test runs it the same way in a task and on a runner.
     */
    static Path binary(final Path dir) throws IOException, InterruptedException {
        final String named = System.getenv(Tuwunel.BINARY);
        if (named != null && !named.isBlank()) {
            return Path.of(named);
        }
        final Path target = dir.resolve("tuwunel");
        if (Files.isExecutable(target)) {
            return target;
        }
        copyOut(target, "podman");
        return target;
    }

    /** Copies the binary out of a container of the pinned image, made and removed for it. */
    static void copyOut(final Path target, final String podman)
            throws IOException, InterruptedException {
        final String id = podman(podman, "create", Tuwunel.image()).strip();
        try {
            podman(podman, "cp", id + ":/usr/bin/tuwunel", target.toString());
        } finally {
            podman(podman, "rm", id);
        }
    }

    int runs() {
        return runs;
    }

    @Override
    public boolean installed() {
        return true;
    }

    @Override
    public boolean active() {
        final Process running = process;
        return running != null && running.isAlive();
    }

    @Override
    public String state() {
        return active() ? "active" : "inactive";
    }

    @Override
    public List<String> recentLog() {
        try {
            final List<String> lines = Files.readAllLines(log, StandardCharsets.UTF_8);
            return lines.subList(Math.max(0, lines.size() - 200), lines.size());
        } catch (final IOException ex) {
            return List.of();
        }
    }

    @Override
    public void start() throws Failure {
        if (active()) {
            return;
        }
        HomeserverToken.run(List.of(), env, new PrintStream(OutputStream.nullOutputStream()));
        try {
            Files.createDirectories(data);
            final ProcessBuilder builder = builder(List.of(), Map.of(), true);
            builder.redirectOutput(ProcessBuilder.Redirect.appendTo(log.toFile()));
            process = builder.start();
        } catch (final IOException ex) {
            throw new Failure(Exit.CONFIG, "cannot start " + binary + ": " + ex.getMessage(), ex);
        }
    }

    @Override
    public void stop() throws Failure {
        final Process running = process;
        if (running == null) {
            return;
        }
        try {
            running.destroy();
            if (!running.waitFor(20, TimeUnit.SECONDS)) {
                running.destroyForcibly().waitFor();
            }
        } catch (final InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new Failure(Exit.TEMPORARY, "interrupted while stopping the homeserver", ex);
        } finally {
            process = null;
        }
    }

    @Override
    public boolean hasDatabase() {
        try (Stream<Path> files = Files.exists(data) ? Files.list(data) : Stream.empty()) {
            return files.findAny().isPresent();
        } catch (final IOException ex) {
            throw new IllegalStateException(ex);
        }
    }

    @Override
    public void removeDatabase() {
        delete(data);
    }

    @Override
    public void signalBackup() throws Failure {
        final Process running = process;
        if (running == null || !running.isAlive()) {
            throw new Failure(Exit.TEMPORARY, "the homeserver is not running");
        }
        try {
            final Process kill =
                    new ProcessBuilder("kill", "-USR2", Long.toString(running.pid()))
                            .redirectErrorStream(true)
                            .start();
            if (kill.waitFor() != 0) {
                throw new Failure(Exit.TEMPORARY, "kill -USR2 failed");
            }
        } catch (final IOException ex) {
            throw new Failure(Exit.TEMPORARY, ex.getMessage(), ex);
        } catch (final InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new Failure(Exit.TEMPORARY, "interrupted", ex);
        }
    }

    @Override
    public int runOnce(final List<String> args, final Map<String, String> secrets) throws Failure {
        runs++;
        if (active()) {
            throw new IllegalStateException("a run of its own while the homeserver runs");
        }
        try {
            Files.createDirectories(data);
            final ProcessBuilder builder = builder(args, secrets, false);
            builder.redirectOutput(ProcessBuilder.Redirect.DISCARD);
            final Process run = builder.start();
            if (!run.waitFor(120, TimeUnit.SECONDS)) {
                run.destroyForcibly().waitFor();
                throw new Failure(Exit.TEMPORARY, "the run did not end");
            }
            return run.exitValue();
        } catch (final IOException ex) {
            throw new Failure(Exit.CONFIG, "cannot run " + binary + ": " + ex.getMessage(), ex);
        } catch (final InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new Failure(Exit.TEMPORARY, "interrupted", ex);
        }
    }

    /** As the unit starts the container, the settings as environment; a run has no registration. */
    private ProcessBuilder builder(
            final List<String> args, final Map<String, String> secrets, final boolean serving)
            throws Failure {
        final Path conf = LocalHomeserver.directory(env).resolve("homeserver.conf");
        final int port = LocalHomeserver.port(conf);
        final List<String> command = new java.util.ArrayList<>(List.of(binary.toString()));
        command.addAll(args);
        final ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
        final Map<String, String> environment = builder.environment();
        environment.clear();
        final Map<String, String> settings = new HashMap<>();
        settings.putAll(HomeserverBounds.SETTINGS);
        settings.put("TUWUNEL_SERVER_NAME", LocalHomeserver.serverName(conf));
        settings.put("TUWUNEL_ADDRESS", "127.0.0.1");
        settings.put("TUWUNEL_PORT", Integer.toString(port));
        settings.put("TUWUNEL_DATABASE_PATH", data.toString());
        settings.put("TUWUNEL_DATABASE_BACKUP_PATH", Keep.backups(env).toString());
        settings.put("TUWUNEL_DATABASE_BACKUPS_TO_KEEP", "48");
        settings.put("TUWUNEL_ALLOW_FEDERATION", "false");
        if (serving) {
            settings.put("TUWUNEL_ALLOW_REGISTRATION", "true");
            settings.put("TUWUNEL_REGISTRATION_TOKEN_FILE", HomeserverToken.file(env).toString());
            settings.put("TUWUNEL_ADMIN_SIGNAL_EXECUTE", "[\"server backup-database\"]");
            settings.putAll(tls());
        } else {
            settings.put("TUWUNEL_ALLOW_REGISTRATION", "false");
        }
        environment.putAll(settings);
        environment.putAll(secrets);
        return builder;
    }

    /** As podman's {@code --env-file} gives them, with the container's mount as this directory. */
    private Map<String, String> tls() throws Failure {
        final Path dir = LocalHomeserver.directory(env);
        final Map<String, String> settings = new HashMap<>();
        try {
            for (final String line :
                    Files.readAllLines(dir.resolve(Publish.TLS_ENV), StandardCharsets.US_ASCII)) {
                if (!line.isBlank()) {
                    final int equals = line.indexOf('=');
                    settings.put(
                            line.substring(0, equals),
                            line.substring(equals + 1)
                                    .replace(
                                            Publish.MOUNT + "/",
                                            dir.resolve(Certificate.TLS) + "/"));
                }
            }
        } catch (final IOException ex) {
            throw new Failure(Exit.CONFIG, "cannot read " + Publish.TLS_ENV, ex);
        }
        return settings;
    }

    @Override
    public void close() throws Failure {
        stop();
    }

    private static void delete(final Path tree) {
        if (!Files.exists(tree)) {
            return;
        }
        try (Stream<Path> files = Files.walk(tree)) {
            for (final Path file : files.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.delete(file);
            }
        } catch (final IOException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static String podman(final String podman, final String... args)
            throws IOException, InterruptedException {
        final List<String> command = new java.util.ArrayList<>(List.of(podman));
        command.addAll(List.of(args));
        // A pull's progress goes to stderr, apart from the id the caller reads from stdout.
        final Path errors = Files.createTempFile("podman", ".err");
        try {
            final Process process =
                    new ProcessBuilder(command).redirectError(errors.toFile()).start();
            final String output =
                    new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (process.waitFor() != 0) {
                throw new IllegalStateException(
                        String.join(" ", command)
                                + " failed: "
                                + output
                                + Files.readString(errors, StandardCharsets.UTF_8));
            }
            return output;
        } finally {
            Files.delete(errors);
        }
    }
}
