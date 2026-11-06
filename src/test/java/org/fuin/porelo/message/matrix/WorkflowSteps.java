package org.fuin.porelo.message.matrix;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The workflows read as lines, the way they are written: a step is a twelve-space list item, its
 * keys fourteen spaces in, its environment and script eighteen, and the lines of a {@code with:}
 * value written as {@code |} deeper still.
 */
final class WorkflowSteps {

    /** The name a checkout step is read under. */
    static final String CHECKOUT = "checkout";

    private WorkflowSteps() {}

    /**
     * One step as written: its name ({@value #CHECKOUT} for the checkout), its own keys, its {@code
     * with:} and {@code env:} entries, and its script.
     */
    record Step(
            String name,
            Map<String, String> keys,
            Map<String, String> with,
            Map<String, String> env,
            String run) {}

    static Step step(final Path workflow, final String name) throws IOException {
        for (final Step step : steps(workflow)) {
            if (step.name().equals(name)) {
                return step;
            }
        }
        throw new AssertionError("no step '" + name + "' in " + workflow.getFileName());
    }

    /**
     * The steps of the workflow's first job, in order. Every line under {@code steps:} is one this
     * reading knows, or it fails: a line it skipped could hide a script or a binding from the
     * assertions.
     */
    static List<Step> steps(final Path workflow) throws IOException {
        final List<Step> steps = new ArrayList<>();
        StepLines step = null;
        boolean inSteps = false;
        int number = 0;
        for (final String line : Files.readAllLines(workflow)) {
            number++;
            final String where = workflow.getFileName() + ":" + number;
            if (!inSteps) {
                inSteps = line.equals("        steps:");
                continue;
            }
            final int indent = line.length() - line.stripLeading().length();
            if (step != null && step.inScript() && (line.isBlank() || indent >= 18)) {
                step.script(line.isBlank() ? "" : line.substring(18));
                continue;
            }
            if (step != null) {
                step.endScript();
            }
            if (line.isBlank() || line.stripLeading().startsWith("#")) {
                continue;
            }
            if (indent < 12) {
                break;
            }
            if (indent == 12 && line.startsWith("            - ")) {
                if (step != null) {
                    steps.add(step.finish());
                }
                step = new StepLines();
                step.key(line.substring(14), where);
            } else if (step != null && indent == 14) {
                step.key(line.substring(14), where);
            } else if (step != null && indent == 18 && step.inBlock()) {
                step.entry(line.substring(18), where);
            } else if (step != null && indent > 18 && step.inEntryLines()) {
                step.entryLine(line.strip());
            } else {
                throw new AssertionError(where + ": a line this reading does not know: " + line);
            }
        }
        if (step != null) {
            steps.add(step.finish());
        }
        // A wrong root or a changed layout finds no step and would pass: the checkout must be read.
        assertThat(steps.stream().map(Step::name))
                .as("steps read from %s", workflow.getFileName())
                .contains(CHECKOUT);
        return steps;
    }

    /** One step while its lines are read. */
    private static final class StepLines {

        private final Map<String, String> keys = new LinkedHashMap<>();
        private final Map<String, String> with = new LinkedHashMap<>();
        private final Map<String, String> env = new LinkedHashMap<>();
        private final java.util.Set<String> blocks = new java.util.HashSet<>();
        private Map<String, String> block;
        private String linesKey;
        private StringBuilder script;
        private String run = "";

        void key(final String entry, final String where) {
            block = null;
            linesKey = null;
            switch (entry) {
                case "with:", "env:" -> {
                    // YAML keeps only the last of two, so a reading that merged them would see a
                    // binding the runner drops.
                    if (!blocks.add(entry)) {
                        throw new AssertionError(where + ": '" + entry + "' twice");
                    }
                    block = entry.equals("with:") ? with : env;
                }
                case "run: |" -> {
                    put(keys, entry, where);
                    script = new StringBuilder();
                }
                default -> {
                    if (entry.startsWith("run: |") || entry.startsWith("run: >")) {
                        throw new AssertionError(
                                where + ": a script written another way: " + entry);
                    }
                    put(keys, entry, where);
                    if (entry.startsWith("run: ")) {
                        run = keys.get("run");
                    }
                }
            }
        }

        void entry(final String entry, final String where) {
            put(block, entry, where);
            linesKey = entry.endsWith(": |") ? entry.substring(0, entry.length() - 3) : null;
        }

        boolean inEntryLines() {
            return linesKey != null;
        }

        void entryLine(final String line) {
            block.merge(linesKey, line, (a, b) -> a.equals("|") ? b : a + "\n" + b);
        }

        boolean inBlock() {
            return block != null;
        }

        boolean inScript() {
            return script != null;
        }

        void script(final String line) {
            script.append(line).append('\n');
        }

        void endScript() {
            if (script != null) {
                run = script.toString();
                script = null;
            }
        }

        Step finish() {
            endScript();
            final String uses = keys.getOrDefault("uses", "");
            final String name =
                    uses.startsWith("actions/checkout@") ? CHECKOUT : keys.getOrDefault("name", "");
            return new Step(name, keys, with, env, run);
        }
    }

    private static void put(final Map<String, String> map, final String entry, final String where) {
        final int colon = entry.indexOf(": ");
        if (colon <= 0) {
            throw new AssertionError(where + ": not a key and its value: " + entry);
        }
        final String key = entry.substring(0, colon);
        if (map.put(key, entry.substring(colon + 2).trim()) != null) {
            throw new AssertionError(where + ": '" + key + "' twice");
        }
    }
}
