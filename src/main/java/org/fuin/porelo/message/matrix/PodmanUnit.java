package org.fuin.porelo.message.matrix;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * The homeserver as the package installs it: the user unit {@code porelo-matrix-homeserver}, asked
 * through {@code systemctl --user}, and its container, database and image, asked through podman.
 */
final class PodmanUnit implements Unit {

    /** The image as the unit names it: by digest, so a run of its own is the same version. */
    private static final Pattern IMAGE = Pattern.compile("\\S+@sha256:[0-9a-f]{64}");

    /** A restore of a database of some weeks, with the image pulled already, takes seconds. */
    private static final Duration RUN_TIMEOUT = Duration.ofSeconds(300);

    private static final Duration DIAGNOSTIC_TIMEOUT = Duration.ofSeconds(3);

    private static final Duration CLEANUP_RESERVE = Duration.ofMillis(100);

    private static final int OUTPUT_LIMIT = 1024 * 1024;

    static final int MESSAGE_LIMIT = 4096;

    private final Map<String, String> env;

    private final RootControl rootControl;

    PodmanUnit(final Map<String, String> env) {
        this(env, PodmanUnit::requireRootControl);
    }

    PodmanUnit(final Map<String, String> env, final RootControl rootControl) {
        this.env = env;
        this.rootControl = rootControl;
    }

    /** Verifies the ownership and permissions of a diagnostic session launcher's path. */
    @FunctionalInterface
    interface RootControl {
        /** Refuses a path whose file or directories can be replaced by an untrusted account. */
        void require(Path path) throws IOException;
    }

    @Override
    public boolean installed() throws Failure {
        return LocalHomeserver.systemctl("show", LocalHomeserver.UNIT, "-p", "LoadState", "--value")
                .filter(state -> state.strip().equals("loaded"))
                .isPresent();
    }

    @Override
    public boolean active() throws Failure {
        return LocalHomeserver.systemctl("is-active", "-q", LocalHomeserver.UNIT).isPresent();
    }

    @Override
    public String state() {
        return diagnostic(
                        List.of(
                                "systemctl",
                                "--user",
                                "show",
                                LocalHomeserver.UNIT,
                                "-p",
                                "ActiveState",
                                "--value"),
                        String::strip)
                .filter(
                        state ->
                                Set.of(
                                                "active",
                                                "failed",
                                                "inactive",
                                                "activating",
                                                "deactivating",
                                                "reloading",
                                                "maintenance",
                                                "refreshing")
                                        .contains(state))
                .orElse("unknown");
    }

    @Override
    public List<String> recentLog() {
        // The detached container is removed on exit; its journal survives it.
        return diagnostic(
                        List.of(
                                "journalctl",
                                "--user",
                                "-u",
                                LocalHomeserver.UNIT + ".service",
                                "-n",
                                "200",
                                "-o",
                                "json"),
                        PodmanUnit::journalMessages)
                .orElse(List.of());
    }

    private static List<String> journalMessages(final String output) {
        final List<String> messages = new ArrayList<>();
        try {
            for (final String record : output.lines().toList()) {
                if (Thread.currentThread().isInterrupted()) {
                    return List.of();
                }
                final JsonNode entry =
                        MatrixClient.JSON
                                .reader()
                                .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                                .readTree(record);
                final JsonNode message = entry.path("MESSAGE");
                if (message.isTextual()) {
                    if (message.textValue().length() <= MESSAGE_LIMIT) {
                        messages.add(message.textValue());
                    }
                } else if (message.isArray()) {
                    if (message.size() > MESSAGE_LIMIT * 4) {
                        continue;
                    }
                    final byte[] bytes = new byte[message.size()];
                    for (int index = 0; index < bytes.length; index++) {
                        final JsonNode value = message.path(index);
                        if (!value.isIntegralNumber()
                                || !value.canConvertToInt()
                                || value.intValue() < 0
                                || value.intValue() > 255) {
                            return List.of();
                        }
                        bytes[index] = (byte) value.intValue();
                    }
                    final String decoded = new String(bytes, StandardCharsets.UTF_8);
                    if (decoded.length() <= MESSAGE_LIMIT) {
                        messages.add(decoded);
                    }
                } else {
                    return List.of();
                }
            }
            return List.copyOf(messages);
        } catch (final IOException ex) {
            return List.of();
        }
    }

    private <T> Optional<T> diagnostic(
            final List<String> command, final Function<String, T> parse) {
        final Process process;
        final List<String> invocation;
        try {
            invocation = diagnosticCommand(command, Path.of("/usr/bin/setsid"), rootControl);
            process =
                    new ProcessBuilder(invocation)
                            .redirectError(ProcessBuilder.Redirect.DISCARD)
                            .start();
        } catch (final IOException ex) {
            return Optional.empty();
        }
        final long deadline = System.nanoTime() + DIAGNOSTIC_TIMEOUT.toNanos();
        final long workDeadline = deadline - CLEANUP_RESERVE.toNanos();
        final Set<ProcessHandle> descendants = new LinkedHashSet<>();
        final FutureTask<T> output =
                new FutureTask<>(
                        () -> {
                            try (var input = process.getInputStream()) {
                                // One overflow byte distinguishes an exact-limit read from excess.
                                final byte[] bytes = input.readNBytes(OUTPUT_LIMIT + 1);
                                if (bytes.length > OUTPUT_LIMIT) {
                                    throw new IOException("diagnostic output exceeds 1 MiB");
                                }
                                return parse.apply(new String(bytes, StandardCharsets.UTF_8));
                            }
                        });
        // A child can inherit stdout, so the caller must be free to abandon this reader.
        Thread.ofPlatform().daemon().name("homeserver-diagnostic").start(output);
        try {
            process.getOutputStream().close();
            while (process.isAlive()) {
                descendants.addAll(process.descendants().toList());
                final long remaining = workDeadline - System.nanoTime();
                if (remaining <= 0) {
                    return Optional.empty();
                }
                process.waitFor(
                        Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(50)),
                        TimeUnit.NANOSECONDS);
            }
            if (process.exitValue() != 0) {
                return Optional.empty();
            }
            return Optional.of(
                    output.get(
                            Math.max(0, workDeadline - System.nanoTime()), TimeUnit.NANOSECONDS));
        } catch (final InterruptedException ex) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } catch (final IOException | ExecutionException | TimeoutException ex) {
            return Optional.empty();
        } finally {
            if (invocation.size() > command.size()) {
                final List<ProcessHandle> members = groupMembers(process.pid());
                descendants.addAll(members);
                members.forEach(ProcessHandle::destroyForcibly);
            }
            descendants.addAll(process.descendants().toList());
            List.copyOf(descendants).reversed().forEach(ProcessHandle::destroyForcibly);
            // Process.destroyForcibly also closes streams and can wait for the blocked reader.
            process.toHandle().destroyForcibly();
            output.cancel(true);
            descendants.add(process.toHandle());
            for (final ProcessHandle child : descendants) {
                try {
                    child.onExit()
                            .get(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
                } catch (final InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (final ExecutionException | TimeoutException ex) {
                    break;
                }
            }
        }
    }

    static List<String> diagnosticCommand(final List<String> command, final Path launcher)
            throws IOException {
        return diagnosticCommand(command, launcher, PodmanUnit::requireRootControl);
    }

    private static List<String> diagnosticCommand(
            final List<String> command, final Path launcher, final RootControl rootControl)
            throws IOException {
        if (Files.notExists(launcher)) {
            return command;
        }
        rootControl.require(launcher);
        rootControl.require(launcher.toRealPath());
        if (!Files.isRegularFile(launcher) || !Files.isExecutable(launcher)) {
            throw new IOException("the diagnostic session launcher is not executable: " + launcher);
        }
        final List<String> invocation = new ArrayList<>(command);
        invocation.addFirst(launcher.toString());
        return invocation;
    }

    private static void requireRootControl(final Path path) throws IOException {
        for (@Nullable Path part = path; part != null; part = part.getParent()) {
            final int owner = (int) Files.getAttribute(part, "unix:uid");
            final int mode = (int) Files.getAttribute(part, "unix:mode");
            if (owner != 0 || (mode & 0022) != 0) {
                throw new IOException(
                        "the diagnostic session launcher must be root-owned and protected: "
                                + part);
            }
        }
    }

    private static List<ProcessHandle> groupMembers(final long group) {
        try (var processes = ProcessHandle.allProcesses()) {
            return processes.filter(process -> inGroup(process.pid(), group)).toList();
        }
    }

    private static boolean inGroup(final long pid, final long group) {
        try {
            final String stat = Files.readString(Path.of("/proc", Long.toString(pid), "stat"));
            final String[] fields = stat.substring(stat.lastIndexOf(')') + 2).split(" ");
            return fields.length >= 3 && Long.parseLong(fields[2]) == group;
        } catch (final IOException | NumberFormatException ex) {
            // A process may exit between enumeration and reading its group.
            return false;
        }
    }

    @Override
    public void start() throws Failure {
        // Enabled, not only started: it then comes back after a boot without anybody logging in.
        LocalHomeserver.systemctl("enable", "--now", LocalHomeserver.UNIT)
                .orElseThrow(
                        () ->
                                new Failure(
                                        Exit.CONFIG,
                                        "systemctl --user enable --now "
                                                + LocalHomeserver.UNIT
                                                + " failed"));
    }

    @Override
    public void stop() throws Failure {
        LocalHomeserver.systemctl("stop", LocalHomeserver.UNIT)
                .orElseThrow(
                        () ->
                                new Failure(
                                        Exit.CONFIG,
                                        "systemctl --user stop "
                                                + LocalHomeserver.UNIT
                                                + " failed"));
    }

    @Override
    public boolean hasDatabase() throws Failure {
        return podman(List.of("volume", "exists", LocalHomeserver.VOLUME), Map.of()) == 0;
    }

    @Override
    public void removeDatabase() throws Failure {
        if (podman(List.of("volume", "rm", "-f", LocalHomeserver.VOLUME), Map.of()) != 0) {
            throw new Failure(
                    Exit.CONFIG,
                    "the podman volume " + LocalHomeserver.VOLUME + " could not be removed");
        }
    }

    @Override
    public void signalBackup() throws Failure {
        // The unit's own container, by the id podman gave it, never by its name.
        final @Nullable Path cid = LocalHomeserver.cidFile(env);
        if (cid == null || !Files.isRegularFile(cid)) {
            throw new Failure(
                    Exit.TEMPORARY, "the homeserver " + LocalHomeserver.UNIT + " is not running");
        }
        if (podman(List.of("kill", "--signal", "USR2", "--cidfile=" + cid), Map.of()) != 0) {
            throw new Failure(
                    Exit.TEMPORARY,
                    "the homeserver " + LocalHomeserver.UNIT + " could not be asked for a backup");
        }
    }

    @Override
    public int runOnce(final List<String> args, final Map<String, String> secrets) throws Failure {
        return podman(
                command(
                        LocalHomeserver.serverName(
                                LocalHomeserver.directory(env).resolve("homeserver.conf")),
                        Keep.backups(env),
                        image(),
                        args,
                        secrets.keySet()),
                secrets);
    }

    /**
     * The run's podman arguments. A secret is named, never given: podman takes its value from its
     * own environment, so it is on no command line.
     */
    static List<String> command(
            final String serverName,
            final Path backups,
            final String image,
            final List<String> args,
            final Set<String> secretNames) {
        final List<String> command =
                new ArrayList<>(
                        List.of(
                                "run",
                                "--rm",
                                // Its output may repeat a secret: kept out of the journal too.
                                "--log-driver=none",
                                "-e",
                                "TUWUNEL_SERVER_NAME=" + serverName,
                                "-e",
                                "TUWUNEL_DATABASE_PATH=/data",
                                "-e",
                                "TUWUNEL_DATABASE_BACKUP_PATH=/backup",
                                "-e",
                                "TUWUNEL_ALLOW_FEDERATION=false",
                                "-e",
                                "TUWUNEL_ALLOW_REGISTRATION=false"));
        command.addAll(HomeserverBounds.PODMAN_FLAGS);
        HomeserverBounds.SETTINGS.forEach(
                (key, value) -> {
                    command.add("-e");
                    command.add(key + "=" + value);
                });
        for (final String name : new TreeSet<>(secretNames)) {
            command.add("-e");
            command.add(name);
        }
        command.addAll(
                List.of(
                        "-v",
                        LocalHomeserver.VOLUME + ":/data",
                        "-v",
                        backups + ":/backup:Z",
                        image));
        command.addAll(args);
        return command;
    }

    /** The image the installed unit runs, read from the unit itself. */
    private String image() throws Failure {
        final String unit =
                LocalHomeserver.systemctl("cat", LocalHomeserver.UNIT)
                        .orElseThrow(
                                () ->
                                        new Failure(
                                                Exit.CONFIG,
                                                "systemctl --user cat "
                                                        + LocalHomeserver.UNIT
                                                        + " failed"));
        final Set<String> images = new TreeSet<>();
        final Matcher m = IMAGE.matcher(unit);
        while (m.find()) {
            images.add(m.group());
        }
        if (images.size() != 1) {
            throw new Failure(
                    Exit.CONFIG,
                    "the unit "
                            + LocalHomeserver.UNIT
                            + " names "
                            + images.size()
                            + " images by digest, not one: "
                            + images);
        }
        return images.iterator().next();
    }

    /**
     * Runs podman with what it is given in its environment beside the account's, output dropped.
     */
    private static int podman(final List<String> args, final Map<String, String> secrets)
            throws Failure {
        final List<String> command = new ArrayList<>(List.of("podman"));
        command.addAll(args);
        final ProcessBuilder builder =
                new ProcessBuilder(command)
                        .redirectErrorStream(true)
                        .redirectOutput(ProcessBuilder.Redirect.DISCARD);
        builder.environment().putAll(secrets);
        try {
            final Process process = builder.start();
            if (!process.waitFor(RUN_TIMEOUT.toSeconds(), TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new Failure(
                        Exit.TEMPORARY,
                        "podman "
                                + args.getFirst()
                                + " did not end within "
                                + RUN_TIMEOUT.toSeconds()
                                + " s");
            }
            return process.exitValue();
        } catch (final IOException ex) {
            throw new Failure(Exit.CONFIG, "cannot run podman: " + ex.getMessage(), ex);
        } catch (final InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new Failure(Exit.TEMPORARY, "interrupted while running podman", ex);
        }
    }
}
