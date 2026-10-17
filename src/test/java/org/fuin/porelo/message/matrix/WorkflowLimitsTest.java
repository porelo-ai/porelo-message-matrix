package org.fuin.porelo.message.matrix;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * GitHub stops a job after six hours unless it says otherwise, and every minute on a slow runner or a hung
 * step is paid. A job added without a limit would inherit that default, and nothing notices until a run
 * does not end.
 */
class WorkflowLimitsTest {

    private static final Path WORKFLOWS = Path.of(".github", "workflows");

    /** A job's name: two spaces in, under {@code jobs:}. */
    private static final Pattern JOB = Pattern.compile("^  ([A-Za-z0-9_-]+):\\s*$");

    /** The job's own limit, four spaces in; a step's limit is deeper and does not count. */
    private static final Pattern LIMIT = Pattern.compile("^    timeout-minutes: *[0-9]+\\s*$");

    @Test
    @DisplayName("Every job of every workflow has a time limit")
    void everyJobHasALimit() throws IOException {
        final List<Path> files;
        try (Stream<Path> list = Files.list(WORKFLOWS)) {
            files = list.filter(p -> p.toString().endsWith(".yml") || p.toString().endsWith(".yaml")).sorted().toList();
        }
        assertThat(files).as("the workflows").isNotEmpty();

        final List<String> jobs = new ArrayList<>();
        final List<String> without = new ArrayList<>();
        for (final Path file : files) {
            boolean inJobs = false;
            String job = null;
            boolean limited = false;
            for (final String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (!line.isBlank() && !line.startsWith(" ") && !line.startsWith("#")) {
                    inJobs = line.startsWith("jobs:");
                    continue;
                }
                if (!inJobs) {
                    continue;
                }
                final Matcher m = JOB.matcher(line);
                if (m.matches()) {
                    if (job != null && !limited) {
                        without.add(file.getFileName() + ": " + job);
                    }
                    job = m.group(1);
                    jobs.add(job);
                    limited = false;
                } else if (LIMIT.matcher(line).matches()) {
                    limited = true;
                }
            }
            if (job != null && !limited) {
                without.add(file.getFileName() + ": " + job);
            }
        }
        assertThat(jobs).as("the jobs found").isNotEmpty();
        assertThat(without).as("jobs without timeout-minutes").isEmpty();
    }

}
