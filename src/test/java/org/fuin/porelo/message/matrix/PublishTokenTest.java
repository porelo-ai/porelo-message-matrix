package org.fuin.porelo.message.matrix;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Runs {@code artifactory.sh}'s login, upload and REST call against a stand-in for {@code curl}, as
 * a Publish step does, traced. The token must reach curl, and nothing else: no file, no argument,
 * no line of the trace. Self-contained, so the same test can stand beside every copy of the helper.
 */
class PublishTokenTest {

    private static final String TOKEN = "tok-3f9c1e7a-never-on-disk";

    /** Records its arguments, and whether the config it was handed carries the token. */
    private static final String CURL =
            """
            #!/bin/bash
            printf '%s\\n' "$*" >> "$HOME/argv"
            echo called >> "$HOME/calls"
            config=""
            prev=""
            for a in "$@"; do
                [ "$prev" = "-K" ] && config="$a"
                prev="$a"
            done
            if [ "$config" = "-" ]; then
                if grep -qF "Authorization: Bearer $JF_ACCESS_TOKEN" -; then
                    echo stdin-token >> "$HOME/auth"
                else
                    echo stdin-without-token >> "$HOME/auth"
                fi
            elif [ -n "$config" ]; then
                echo "file:$config" >> "$HOME/auth"
            else
                echo none >> "$HOME/auth"
            fi
            """;

    @Test
    void theTokenReachesCurlOnStandardInputAndTouchesNoFile(@TempDir final Path dir)
            throws Exception {

        final Path runnerTemp = Files.createDirectories(dir.resolve("runner-temp"));
        Files.writeString(dir.resolve("package.deb"), "bytes");

        final Result run =
                run(
                        dir,
                        """
                        set -x
                        . .github/scripts/artifactory.sh
                        art_login
                        art_put package.deb porelo-dist-deb/pool/ "deb.distribution=snapshots"
                        art_api GET /api/storage/porelo-dist-deb
                        """,
                        Map.of(
                                "RUNNER_TEMP", runnerTemp.toString(),
                                "TMPDIR", runnerTemp.toString(),
                                "JF_ACCESS_TOKEN", TOKEN));

        assertThat(run.exit()).as("exit of the helper's calls:%n%s", run.output()).isZero();
        assertThat(Files.readAllLines(dir.resolve("auth")))
                .as("how each curl call got its credentials: on standard input, with the token")
                .containsExactly("stdin-token", "stdin-token");
        assertThat(Files.readString(dir.resolve("argv")))
                .as("curl's arguments, which /proc/<pid>/cmdline shows")
                .doesNotContain(TOKEN);
        assertThat(run.output()).as("the traced output of the step").doesNotContain(TOKEN);
        assertThat(filesHolding(dir))
                .as("files under the step's directory, RUNNER_TEMP included, that hold the token")
                .isEmpty();
    }

    @Test
    void aMissingTokenIsRefusedBeforeAnyCall(@TempDir final Path dir) throws Exception {

        final Result run =
                run(
                        dir,
                        """
                        . .github/scripts/artifactory.sh
                        art_login
                        art_api GET /api/storage/porelo-dist-deb
                        """,
                        Map.of());

        assertThat(run.exit()).as("exit without a token:%n%s", run.output()).isNotZero();
        assertThat(run.output()).as("what the step says").contains("JF_ACCESS_TOKEN is not set");
        assertThat(dir.resolve("calls")).as("curl calls without a token").doesNotExist();
    }

    private record Result(int exit, String output) {}

    /** Runs the script with bash -e in a directory holding the helper and the stand-in curl. */
    private static Result run(final Path dir, final String script, final Map<String, String> env)
            throws IOException, InterruptedException {
        final Path bin = Files.createDirectories(dir.resolve("bin"));
        final Path curl = bin.resolve("curl");
        Files.writeString(curl, CURL);
        Files.setPosixFilePermissions(curl, PosixFilePermissions.fromString("rwx------"));
        final Path scripts = Files.createDirectories(dir.resolve(".github/scripts"));
        Files.copy(Path.of(".github/scripts/artifactory.sh"), scripts.resolve("artifactory.sh"));
        final Path step = dir.resolve("step.sh");
        Files.writeString(step, script);
        final Path out = dir.resolve("step.out");
        final ProcessBuilder builder =
                new ProcessBuilder("bash", "--noprofile", "--norc", "-e", step.toString())
                        .directory(dir.toFile())
                        .redirectErrorStream(true)
                        .redirectOutput(out.toFile());
        builder.environment().clear();
        builder.environment()
                .put("PATH", bin + ":" + System.getenv().getOrDefault("PATH", "/usr/bin:/bin"));
        builder.environment().put("HOME", dir.toString());
        builder.environment().put("LC_ALL", "C");
        builder.environment().put("JF_URL", "https://artifactory.invalid");
        builder.environment().putAll(env);
        final Process process = builder.start();
        assertThat(process.waitFor(30, TimeUnit.SECONDS)).as("the step ended within 30 s").isTrue();
        final String output = Files.readString(out);
        Files.delete(out);
        Files.delete(step);
        return new Result(process.exitValue(), output);
    }

    private static List<Path> filesHolding(final Path dir) throws IOException {
        try (Stream<Path> files = Files.walk(dir)) {
            return files.filter(Files::isRegularFile)
                    .filter(
                            f -> {
                                try {
                                    return new String(Files.readAllBytes(f), StandardCharsets.UTF_8)
                                            .contains(TOKEN);
                                } catch (IOException ex) {
                                    throw new IllegalStateException(ex);
                                }
                            })
                    .toList();
        }
    }
}
