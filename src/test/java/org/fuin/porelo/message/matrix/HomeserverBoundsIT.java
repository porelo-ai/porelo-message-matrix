package org.fuin.porelo.message.matrix;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Harm guarded: Tuwunel sizes its database pool from the cores it sees, so on a host with many
 * cores it needs more threads than its container may start, and the room's homeserver never comes
 * up. Here the server sees four cores and may start far fewer processes than the unit allows: with
 * the pool capped it needs about 72 threads, uncapped about 138 (both measured), so a cap that
 * Tuwunel no longer reads fails this test on any build machine.
 */
class HomeserverBoundsIT {

    private static final int CORES = 4;

    private static final int PIDS = 128;

    private static final Duration START_TIMEOUT = Duration.ofSeconds(60);

    private @Nullable String container;

    @AfterEach
    void tearDown() throws IOException, InterruptedException {
        if (container != null) {
            podman("rm", "-f", "-i", "-t", "0", container);
        }
    }

    @Test
    @DisplayName("The homeserver answers under a process limit its uncapped pool would break")
    void answersUnderAProcessLimitItsUncappedPoolWouldBreak() throws Exception {
        final String binary = System.getenv(Tuwunel.BINARY);
        assumeTrue(
                binary == null || binary.isBlank(),
                "no container starts where the homeserver runs as a binary of the test");
        final List<Integer> allowed = allowedCpus();
        assumeTrue(
                allowed.size() >= CORES,
                "with fewer than four cores an uncapped pool fits under the limit too");
        final String cores =
                allowed.subList(0, CORES).stream()
                        .map(String::valueOf)
                        .collect(Collectors.joining(","));
        final String name = "tuwunel-bounds-it-" + HexFormat.of().formatHex(randomBytes());
        container = name;
        final List<String> command = command(name, cores);
        assertThat(command)
                .as("the unit's process limit is the one forced down")
                .contains("--pids-limit=" + PIDS)
                .doesNotContainAnyElementsOf(
                        HomeserverBounds.PODMAN_FLAGS.stream()
                                .filter(flag -> flag.startsWith("--pids-limit"))
                                .toList());
        run(command);

        final String outcome = awaitAnswer(name);

        assertThat(outcome)
                .as("the homeserver under --pids-limit=%d on cores %s", PIDS, cores)
                .isEqualTo("answered");
    }

    private static byte[] randomBytes() {
        final byte[] bytes = new byte[6];
        new SecureRandom().nextBytes(bytes);
        return bytes;
    }

    /** The cores this process may run on, as the kernel lists them, in order. */
    private static List<Integer> allowedCpus() throws IOException {
        final String list =
                Files.readAllLines(Path.of("/proc/self/status"), StandardCharsets.UTF_8).stream()
                        .filter(line -> line.startsWith("Cpus_allowed_list:"))
                        .findFirst()
                        .orElseThrow(() -> new IllegalStateException("no Cpus_allowed_list"))
                        .substring("Cpus_allowed_list:".length())
                        .strip();
        final List<Integer> cpus = new ArrayList<>();
        for (final String range : list.split(",")) {
            final String[] ends = range.split("-");
            final int last = Integer.parseInt(ends[ends.length - 1]);
            for (int cpu = Integer.parseInt(ends[0]); cpu <= last; cpu++) {
                cpus.add(cpu);
            }
        }
        return cpus;
    }

    /**
     * The unit's own bounds and settings, with the process limit forced down. Tuwunel counts the
     * cores it may run on, which a container's CPU limit does not change, so {@code taskset} sets
     * them.
     */
    private static List<String> command(final String name, final String cores) throws IOException {
        final List<String> command =
                new ArrayList<>(
                        List.of("taskset", "-c", cores, "podman", "run", "-d", "--name", name));
        for (final String flag : HomeserverBounds.PODMAN_FLAGS) {
            command.add(flag.startsWith("--pids-limit=") ? "--pids-limit=" + PIDS : flag);
        }
        final List<String> settings =
                new ArrayList<>(
                        List.of(
                                "TUWUNEL_SERVER_NAME=" + Tuwunel.SERVER_NAME,
                                "TUWUNEL_ADDRESS=0.0.0.0",
                                "TUWUNEL_PORT=8008",
                                "TUWUNEL_DATABASE_PATH=/data",
                                "TUWUNEL_ALLOW_FEDERATION=false"));
        HomeserverBounds.SETTINGS.forEach((key, value) -> settings.add(key + "=" + value));
        for (final String setting : settings) {
            command.add("-e");
            command.add(setting);
        }
        // Podman picks the port and holds it, so no other process takes it before the start.
        command.addAll(List.of("-p", "127.0.0.1::8008", Tuwunel.image()));
        return command;
    }

    /** {@code answered}, or why not: the container ended, or it stayed silent to the limit. */
    private static String awaitAnswer(final String name) throws IOException, InterruptedException {
        final long deadline = System.nanoTime() + START_TIMEOUT.toNanos();
        @Nullable HttpRequest versions = null;
        try (HttpClient http = HttpClient.newHttpClient()) {
            while (System.nanoTime() - deadline < 0) {
                final String state = podman("inspect", "-f", "{{.State.Status}}", name).strip();
                if (!"running".equals(state)) {
                    return "ended (" + state + "): " + podman("logs", name);
                }
                if (versions == null) {
                    final String port =
                            podman("port", name, "8008/tcp").strip().replaceAll(".*:", "");
                    versions =
                            HttpRequest.newBuilder(
                                            URI.create(
                                                    "http://127.0.0.1:"
                                                            + port
                                                            + "/_matrix/client/versions"))
                                    .timeout(Duration.ofSeconds(2))
                                    .build();
                }
                try {
                    if (http.send(versions, HttpResponse.BodyHandlers.discarding()).statusCode()
                            == 200) {
                        return "answered";
                    }
                } catch (final IOException notYet) {
                    // Not listening yet; the container's state above says whether it still runs.
                }
                Thread.sleep(250);
            }
        }
        return "no answer within " + START_TIMEOUT + ": " + podman("logs", name);
    }

    private static String podman(final String... args) throws IOException, InterruptedException {
        final List<String> command = new ArrayList<>(List.of("podman"));
        command.addAll(List.of(args));
        return run(command);
    }

    private static String run(final List<String> command) throws IOException, InterruptedException {
        final Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        final String output =
                new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (process.waitFor() != 0) {
            throw new IllegalStateException(String.join(" ", command) + " failed: " + output);
        }
        return output;
    }
}
