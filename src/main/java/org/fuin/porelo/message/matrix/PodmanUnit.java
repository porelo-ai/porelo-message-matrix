package org.fuin.porelo.message.matrix;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
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

    private final Map<String, String> env;

    PodmanUnit(final Map<String, String> env) {
        this.env = env;
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
