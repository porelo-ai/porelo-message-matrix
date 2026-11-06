package org.fuin.porelo.message.matrix;

import static org.assertj.core.api.Assertions.assertThat;
import static org.fuin.porelo.message.matrix.WorkflowSteps.steps;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.fuin.porelo.message.matrix.WorkflowSteps.Step;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Maven on a forge's runner holds only a token that reads the registry, since a test on an
 * unreviewed branch can read whatever the step holds; and a forge that names no snapshot
 * repository, or no token to read it with, stops the job rather than resolving snapshots from
 * Sonatype alone. Tagged, since a change to the workflows alone starts no Build.
 */
@Tag("documents")
class WorkflowTokensTest {

    private static final Path ROOT = Path.of("");
    private static final Path WORKFLOWS = ROOT.resolve(".github").resolve("workflows");
    private static final Path SETTINGS = ROOT.resolve("settings.xml");
    private static final String WRITING_TOKEN = "PORELO_PUBLISHER_TOKEN";
    // Maven by the wrapper or by the runner's own, however the path to it is spelt or the shell
    // nests it.
    private static final Pattern MAVEN = Pattern.compile("(^|[^\\w.-])mvnw?($|[^\\w.-])");
    // A value given to the variable, as a key of an environment or as a shell assignment.
    private static final Pattern SNAPSHOTS_SET =
            Pattern.compile("MAVEN_SNAPSHOTS_URL[\"']?\\s*[:=]");
    private static final String READ_TOKEN = "PORELO_PACKAGES_READ_TOKEN";
    private static final String GUARD_STEP = "A forge names its registry and its token";
    private static final String SNAPSHOTS_BINDING =
            "    MAVEN_SNAPSHOTS_URL: ${{ vars.MAVEN_SNAPSHOTS_URL }}";
    private static final String GITHUB = "https://github.com";
    // Any forge but GitHub, as the host's forge names itself to its runner.
    private static final String FORGE = "https://forge.example:3000";
    private static final String FORGE_REGISTRY = "https://forge.example:3000/api/packages/o/maven";
    private static final String A_TOKEN = "stand-in";
    private static final List<String> MAVEN_STEPS =
            List.of(
                    "build.yml: Resolve the build's tools",
                    "build.yml: Refuse a snapshot among what a release is built with",
                    "build.yml: Build, test against the native executable, and package",
                    "shared-rules.yml: Resolve the build's tools",
                    "shared-rules.yml: The tests that read a document",
                    "workflows.yml: Every step pinned to a commit");
    // Every value settings.xml takes from the environment: a misspelt or added one is seen here.
    private static final Pattern ENV_REFERENCE = Pattern.compile("\\$\\{env\\.([A-Za-z0-9_]+)}");

    @TempDir Path directory;

    // Prevents a token that can overwrite a snapshot from reaching a step an unreviewed branch
    // runs.
    @Test
    void noWorkflowAndNotTheSettingsNameTheWritingToken() throws IOException {
        final List<Path> files = new ArrayList<>(workflows().values());
        files.add(SETTINGS);
        final List<String> naming = new ArrayList<>();
        for (final Path file : files) {
            final List<String> lines = Files.readAllLines(file);
            for (int i = 0; i < lines.size(); i++) {
                if (lines.get(i).contains(WRITING_TOKEN)) {
                    naming.add(file.getFileName() + ":" + (i + 1) + ": " + lines.get(i).strip());
                }
            }
        }

        assertThat(naming).as("lines naming %s", WRITING_TOKEN).isEmpty();
    }

    // Prevents a misspelt value from sending a literal password, and a writing token from coming
    // in.
    @Test
    void theSettingsTakeExactlyTheirValuesFromTheEnvironment() throws IOException {
        final List<String> names = new ArrayList<>();
        final java.util.regex.Matcher m = ENV_REFERENCE.matcher(Files.readString(SETTINGS));
        while (m.find()) {
            names.add(m.group(1));
        }

        assertThat(names)
                .as("what settings.xml reads from the environment")
                .containsExactly(
                        "OSS_SONATYPE_USERNAME",
                        "OSS_SONATYPE_TOKEN",
                        READ_TOKEN,
                        "MAVEN_SNAPSHOTS_URL",
                        "MAVEN_SNAPSHOTS_URL");
    }

    // Prevents a job from resolving snapshots before it has checked where they come from.
    @Test
    void everyJobThatRunsMavenChecksItsSnapshotRepositoryFirst() throws IOException {
        final Map<String, String> unguarded = new TreeMap<>();
        for (final Map.Entry<String, Path> workflow : mavenWorkflows().entrySet()) {
            final List<String> lines = Files.readAllLines(workflow.getValue());
            final List<Step> steps = steps(workflow.getValue());
            assertThat(steps.stream().mapToLong(s -> mavenLines(s.run().lines())).sum())
                    .as("%s: every Maven call is in a step this reading sees", workflow.getKey())
                    .isEqualTo(mavenLines(lines.stream()));
            assertThat(lines)
                    .as("%s: the snapshot repository is the forge's variable", workflow.getKey())
                    .filteredOn(l -> SNAPSHOTS_SET.matcher(l).find())
                    .containsExactly(SNAPSHOTS_BINDING);
            final int maven = firstIndex(steps, s -> mavenLines(s.run().lines()) > 0);
            final int guard = firstIndex(steps, s -> s.name().equals(GUARD_STEP));
            if (guard < 0 || guard >= maven) {
                unguarded.put(workflow.getKey(), steps.get(maven).name());
            }
        }

        assertThat(unguarded)
                .as("workflows whose first Maven step runs before '%s'", GUARD_STEP)
                .isEmpty();
    }

    // Prevents a forge without a snapshot repository from resolving Sonatype's snapshots unnoticed.
    @Test
    void aForgeWithoutASnapshotRepositoryStopsTheJob() throws IOException, InterruptedException {
        for (final Map.Entry<String, Step> guard : guards().entrySet()) {
            final Run run = execute(guard.getValue(), FORGE, "", A_TOKEN);
            assertThat(run.exit())
                    .as("%s: a forge without a repository: %s", guard.getKey(), run.log())
                    .isNotZero();
            assertThat(run.log())
                    .as("%s: the refusal is a workflow error naming the variable", guard.getKey())
                    .contains("::error::")
                    .contains("vars.MAVEN_SNAPSHOTS_URL");
        }
    }

    // Prevents a forge's answer of 401, only a warning to Maven, from building without what the
    // host built.
    @Test
    void aForgeWithoutAReadTokenStopsTheJob() throws IOException, InterruptedException {
        for (final Map.Entry<String, Step> guard : guards().entrySet()) {
            final Run run = execute(guard.getValue(), FORGE, FORGE_REGISTRY, "");
            assertThat(run.exit())
                    .as("%s: a forge without a token: %s", guard.getKey(), run.log())
                    .isNotZero();
            assertThat(run.log())
                    .as("%s: the refusal is a workflow error naming the secret", guard.getKey())
                    .contains("::error::")
                    .contains("secrets." + READ_TOKEN);
        }
    }

    // Prevents a Maven step without the token from building past the forge's 401, which Maven only
    // warns about, without what the host built.
    @Test
    void everyMavenStepHoldsTheReadToken() throws IOException {
        final List<String> without = new ArrayList<>();
        final List<String> maven = new ArrayList<>();
        int read = 0;
        for (final Map.Entry<String, Path> workflow : mavenWorkflows().entrySet()) {
            for (final Step step : steps(workflow.getValue())) {
                if (mavenLines(step.run().lines()) > 0) {
                    read++;
                    maven.add(workflow.getKey() + ": " + step.name());
                    if (!("${{ secrets." + READ_TOKEN + " }}").equals(step.env().get(READ_TOKEN))) {
                        without.add(workflow.getKey() + ": " + step.name());
                    }
                }
            }
        }

        // A Maven call this reading does not see as one would leave its step out unnoticed.
        assertThat(maven).as("the Maven steps read").containsExactlyElementsOf(MAVEN_STEPS);
        assertThat(read).as("the Maven steps read").isEqualTo(MAVEN_STEPS.size());
        assertThat(without).as("Maven steps without %s", READ_TOKEN).isEmpty();
    }

    // Prevents the guard from stopping the builds that do name where their snapshots come from.
    @Test
    void githubAndAForgeThatNamesItsRepositoryPass() throws IOException, InterruptedException {
        for (final Map.Entry<String, Step> guard : guards().entrySet()) {
            final Run github = execute(guard.getValue(), GITHUB, "", "");
            assertThat(github.exit()).as("%s: GitHub: %s", guard.getKey(), github.log()).isZero();
            final Run forge = execute(guard.getValue(), FORGE, FORGE_REGISTRY, A_TOKEN);
            assertThat(forge.exit())
                    .as("%s: a forge naming its own registry: %s", guard.getKey(), forge.log())
                    .isZero();
        }
    }

    // Prevents the guard from being skipped, from being let fail, or from reading a server or a
    // repository other than the workflow's own.
    @Test
    void theGuardAlwaysRunsAndReadsOnlyItsEnvironment() throws IOException {
        for (final Map.Entry<String, Step> guard : guards().entrySet()) {
            assertThat(guard.getValue().keys().keySet())
                    .as("%s: the guard has no condition and no way to fail softly", guard.getKey())
                    .containsExactlyInAnyOrder("name", "run");
            assertThat(guard.getValue().env())
                    .as("%s: the server the workflow runs on, the token it holds", guard.getKey())
                    .containsOnly(
                            Map.entry("SERVER_URL", "${{ github.server_url }}"),
                            Map.entry(READ_TOKEN, "${{ secrets." + READ_TOKEN + " }}"));
            assertThat(guard.getValue().run())
                    .as("%s: the script reads its environment alone", guard.getKey())
                    .doesNotContain("${{");
        }
    }

    private record Run(int exit, String log) {}

    /** Runs a guard's own script with only the server, the repository and the token set. */
    private Run execute(
            final Step guard, final String server, final String snapshots, final String token)
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
        command.environment().put("SERVER_URL", server);
        command.environment().put("MAVEN_SNAPSHOTS_URL", snapshots);
        command.environment().put(READ_TOKEN, token);
        final Process process = command.start();
        try {
            assertThat(process.waitFor(10, TimeUnit.SECONDS))
                    .as("the guard ends for %s", server)
                    .isTrue();
            return new Run(process.exitValue(), Files.readString(output));
        } finally {
            process.destroyForcibly();
        }
    }

    /** The one guard step of every workflow that runs Maven, by the workflow's file name. */
    private static Map<String, Step> guards() throws IOException {
        final Map<String, Step> guards = new TreeMap<>();
        for (final Map.Entry<String, Path> workflow : mavenWorkflows().entrySet()) {
            final List<Step> named =
                    steps(workflow.getValue()).stream()
                            .filter(s -> s.name().equals(GUARD_STEP))
                            .toList();
            assertThat(named).as("%s: guard steps", workflow.getKey()).hasSize(1);
            guards.put(workflow.getKey(), named.get(0));
        }
        return guards;
    }

    /** The workflows that call Maven anywhere, by their file name. */
    private static Map<String, Path> mavenWorkflows() throws IOException {
        final Map<String, Path> withMaven = new TreeMap<>();
        for (final Map.Entry<String, Path> workflow : workflows().entrySet()) {
            if (mavenLines(Files.readAllLines(workflow.getValue()).stream()) > 0) {
                withMaven.put(workflow.getKey(), workflow.getValue());
            }
        }
        // Only Delete old runs calls no Maven; a reading that sees no Maven call would pass.
        assertThat(withMaven.keySet())
                .as("the workflows that run Maven")
                .containsExactly("build.yml", "shared-rules.yml", "workflows.yml");
        return withMaven;
    }

    /** Every workflow a forge loads, by its file name. */
    private static Map<String, Path> workflows() throws IOException {
        assertThat(WORKFLOWS).as("the workflows' directory").isDirectory();
        final Map<String, Path> workflows;
        try (Stream<Path> files = Files.list(WORKFLOWS)) {
            workflows =
                    files.filter(f -> f.getFileName().toString().matches(".*\\.ya?ml"))
                            .collect(
                                    Collectors.toMap(
                                            f -> f.getFileName().toString(),
                                            f -> f,
                                            (a, b) -> a,
                                            TreeMap::new));
        }
        // A wrong root reads nothing and would pass: all four workflows must be read.
        assertThat(workflows.keySet())
                .as("workflows read")
                .containsExactly(
                        "build.yml", "delete-runs.yml", "shared-rules.yml", "workflows.yml");
        return workflows;
    }

    private static long mavenLines(final Stream<String> lines) {
        return lines.filter(l -> !l.stripLeading().startsWith("#") && MAVEN.matcher(l).find())
                .count();
    }

    private static int firstIndex(final List<Step> steps, final Predicate<Step> matches) {
        for (int i = 0; i < steps.size(); i++) {
            if (matches.test(steps.get(i))) {
                return i;
            }
        }
        return -1;
    }
}
