package org.fuin.porelo.message.matrix;

import static org.assertj.core.api.Assertions.assertThat;
import static org.fuin.porelo.message.matrix.WorkflowSteps.step;
import static org.fuin.porelo.message.matrix.WorkflowSteps.steps;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.fuin.porelo.message.matrix.WorkflowSteps.Step;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The same workflows run on GitHub and on the host's forge, and only GitHub publishes: a forge
 * holds placeholder credentials, and a step that used them would send the packages, or a request to
 * GitHub's API, from a machine that must not. Tagged, since a change to the workflows alone starts
 * no Build.
 */
@Tag("documents")
class WorkflowPublishTest {

    private static final Path WORKFLOWS = Path.of(".github", "workflows");
    private static final Path BUILD = WORKFLOWS.resolve("build.yml");
    private static final Path DELETE_RUNS = WORKFLOWS.resolve("delete-runs.yml");
    private static final String PUBLISHES = "vars.PORELO_PUBLISH == 'true'";
    private static final String GUARD_STEP = "Whether this forge publishes";
    private static final String JOB_IF = "        if: " + PUBLISHES;
    // The publication's values and its script, however they are reached.
    private static final Pattern PUBLICATION = Pattern.compile("JF_|artifactory\\.sh");
    private static final Pattern GITHUB_API = Pattern.compile("\\bgh +api\\b|GH_TOKEN");

    /** A job's name: four spaces in, under {@code jobs:}. */
    private static final Pattern JOB = Pattern.compile("^    [A-Za-z0-9_-]+:\\s*$");

    @TempDir Path directory;

    // Prevents a forge from uploading the packages with its placeholder credentials.
    @Test
    @DisplayName(
            "Every step holding the publication's credentials runs only where PORELO_PUBLISH is true")
    void everyPublishingStepRunsOnlyWherePublishIsTrue() throws IOException {
        final List<Step> publishing =
                steps(BUILD).stream()
                        .filter(s -> s.env().values().stream().anyMatch(v -> v.contains("JF_")))
                        .toList();

        assertThat(publishing)
                .as("the steps that publish")
                .extracting(Step::name)
                .containsExactly(
                        "Publish the packages", "The packages are indexed, not merely stored");
        for (final Step step : publishing) {
            final String condition = step.keys().get("if");
            assertThat(condition)
                    .as("%s: its condition starts with the forge's switch", step.name())
                    .startsWith(PUBLISHES + " && ");
            assertThat(hasTopLevelOr(condition.substring(PUBLISHES.length())))
                    .as(
                            "%s: no || outside parentheses lets the rest run without the switch",
                            step.name())
                    .isFalse();
        }
        long inSteps = 0;
        for (final Step step : publishing) {
            inSteps +=
                    step.env().entrySet().stream()
                            .filter(
                                    e ->
                                            PUBLICATION
                                                    .matcher(e.getKey() + ": " + e.getValue())
                                                    .find())
                            .count();
            inSteps += step.run().lines().filter(l -> PUBLICATION.matcher(l).find()).count();
        }
        assertThat(publicationLines())
                .as("every line of the workflows naming the publication is in those two steps")
                .isEqualTo(inSteps);
    }

    // Prevents a forge from calling GitHub's API with whatever it holds as a token.
    @Test
    @DisplayName("Deleting old runs runs only where PORELO_PUBLISH is true")
    void deletingRunsRunsOnlyWherePublishIsTrue() throws IOException {
        final List<String> lines = Files.readAllLines(DELETE_RUNS);
        final List<String> jobs =
                lines.subList(lines.indexOf("jobs:") + 1, lines.size()).stream()
                        .filter(l -> JOB.matcher(l).matches())
                        .toList();

        assertThat(jobs).as("delete-runs.yml's jobs").containsExactly("    delete:");
        assertThat(lines).as("delete-runs.yml's job condition").contains(JOB_IF);
        for (final Path workflow : workflows()) {
            if (!workflow.equals(DELETE_RUNS)) {
                assertThat(Files.readAllLines(workflow))
                        .as("%s: GitHub's API is called only by delete-runs.yml", workflow)
                        .noneMatch(l -> GITHUB_API.matcher(l).find());
            }
        }
    }

    // Prevents a missing or misspelt switch from leaving GitHub's packages unpublished unnoticed.
    @Test
    @DisplayName(
            "The switch is read before the first publishing step, always, from its variable alone")
    void theSwitchIsReadBeforePublishing() throws IOException {
        final List<Step> steps = steps(BUILD);
        final Step guard = step(BUILD, GUARD_STEP);

        assertThat(guard.keys().keySet())
                .as("the switch's check has no condition and no way to fail softly")
                .containsExactlyInAnyOrder("name", "run");
        assertThat(guard.env())
                .as("the switch's check reads the variable alone")
                .containsOnly(Map.entry("PORELO_PUBLISH", "${{ vars.PORELO_PUBLISH }}"));
        assertThat(steps.indexOf(guard))
                .as("the switch is checked before the first step that publishes")
                .isLessThan(steps.indexOf(step(BUILD, "Publish the packages")));
    }

    // Prevents a switch that is neither value from building on as if it were one of them.
    @ParameterizedTest
    @ValueSource(strings = {"", "TRUE", "yes", "false "})
    @DisplayName("A switch that is neither true nor false stops the Build, naming the variable")
    void aSwitchNeitherTrueNorFalseStopsTheBuild(final String value)
            throws IOException, InterruptedException {
        final Run run = execute(step(BUILD, GUARD_STEP), value);

        assertThat(run.exit()).as("the check given '%s': %s", value, run.log()).isNotZero();
        assertThat(run.log())
                .as("the refusal is a workflow error naming the variable")
                .contains("::error::")
                .contains("vars.PORELO_PUBLISH");
    }

    // Prevents the check from stopping the forges that do say whether they publish.
    @ParameterizedTest
    @ValueSource(strings = {"true", "false"})
    @DisplayName("A switch of true or false passes")
    void trueOrFalsePasses(final String value) throws IOException, InterruptedException {
        final Run run = execute(step(BUILD, GUARD_STEP), value);

        assertThat(run.exit()).as("the check given '%s': %s", value, run.log()).isZero();
    }

    /** Whether an {@code ||} stands outside every parenthesis of an expression. */
    private static boolean hasTopLevelOr(final String expression) {
        int depth = 0;
        for (int i = 0; i < expression.length(); i++) {
            final char c = expression.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
            } else if (depth == 0 && expression.startsWith("||", i)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The lines of every workflow, comments aside, that name the publication's values or script.
     */
    private static long publicationLines() throws IOException {
        long count = 0;
        for (final Path workflow : workflows()) {
            count +=
                    Files.readAllLines(workflow).stream()
                            .filter(l -> !l.stripLeading().startsWith("#"))
                            .filter(l -> PUBLICATION.matcher(l).find())
                            .count();
        }
        return count;
    }

    /** Every workflow a forge loads. */
    private static List<Path> workflows() throws IOException {
        try (Stream<Path> files = Files.list(WORKFLOWS)) {
            final List<Path> workflows =
                    files.filter(f -> f.getFileName().toString().matches(".*\\.ya?ml"))
                            .sorted()
                            .toList();
            // A wrong root reads nothing and would pass: all four workflows must be read.
            assertThat(workflows)
                    .as("workflows read")
                    .extracting(p -> p.getFileName().toString())
                    .containsExactly(
                            "build.yml", "delete-runs.yml", "shared-rules.yml", "workflows.yml");
            return workflows;
        }
    }

    private record Run(int exit, String log) {}

    /** Runs the check's own script with only the switch set. */
    private Run execute(final Step guard, final String value)
            throws IOException, InterruptedException {
        final Path output = directory.resolve("output");
        final ProcessBuilder command =
                new ProcessBuilder(
                                "bash",
                                "--noprofile",
                                "--norc",
                                "-e",
                                "-o",
                                "pipefail",
                                "-c",
                                guard.run())
                        .directory(directory.toFile())
                        .redirectErrorStream(true)
                        .redirectOutput(output.toFile());
        command.environment().clear();
        command.environment().put("PATH", "/usr/bin:/bin");
        command.environment().put("PORELO_PUBLISH", value);
        final Process process = command.start();
        try {
            assertThat(process.waitFor(10, TimeUnit.SECONDS)).as("the check ends").isTrue();
            return new Run(process.exitValue(), Files.readString(output));
        } finally {
            process.destroyForcibly();
        }
    }
}
