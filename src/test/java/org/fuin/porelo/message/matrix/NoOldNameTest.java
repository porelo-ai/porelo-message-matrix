package org.fuin.porelo.message.matrix;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.MalformedInputException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * No tracked file of this repository says the product's old name, but where it says what the old name was. The
 * repository was renamed by a script; a word it left behind, or one written later out of habit, names a path, a
 * package or a setting that is no longer there. The old name is spelled here in two pieces, so that this file does
 * not say it.
 */
@Tag("documents")
class NoOldNameTest {

    private static final String OLD = "so" + "kar";

    /** The rename's own issue, by its title: it says what the old name was, name and text. */
    private static final String RENAME_ISSUE = "[A-Z]+\\d+-The-Transport-Is-Renamed-Porelo\\.md";

    private static final Pattern KEPT_PATHS = Pattern.compile("issues/" + RENAME_ISSUE);

    /** The rename's own row in the issue index, the one that links its issue. */
    private static final Pattern KEPT_LINES = Pattern.compile(".*\\]\\(" + RENAME_ISSUE + "\\).*");

    /** The changelog's first released section: from it on, every entry stays as it was released. */
    private static final Pattern RELEASED = Pattern.compile("## \\[\\d.*");

    @Test
    @DisplayName("No tracked file says the old name, in its path or in a line, but the rename's own record")
    void noTrackedFileSaysTheOldName() throws IOException, InterruptedException {
        final List<String> files = tracked();
        final List<String> found = new ArrayList<>();
        for (final String file : files) {
            if (KEPT_PATHS.matcher(file).matches()) {
                continue;
            }
            if (says(file)) {
                found.add(file + ": its path");
            }
            final List<String> lines = lines(Path.of(file));
            for (int i = 0; i < lines.size(); i++) {
                final String line = lines.get(i);
                if (file.equals("CHANGELOG.md") && RELEASED.matcher(line).matches()) {
                    break;
                }
                if (!KEPT_LINES.matcher(line).matches() && says(line)) {
                    found.add(file + ":" + (i + 1) + ": " + line.strip());
                }
            }
        }

        // A guard that reads nothing passes forever: assert what it read, then what it checks.
        assertThat(files).as("the tracked files the guard reads")
                .contains("pom.xml", "AGENTS.md", "CHANGELOG.md", "issues/README.md");
        assertThat(found).as("tracked files that say the old name").isEmpty();
    }

    @Test
    @DisplayName("The old name is seen in any case, and the changelog's released sections are where it stops")
    void seesTheOldNameInAnyCase() {
        assertThat(List.of(OLD, "So" + "kar", "SO" + "KAR_MATRIX_PORT", "org.fuin." + OLD + ".message"))
                .as("spellings of the old name").allMatch(NoOldNameTest::says);
        assertThat(List.of("porelo", "Porelo", "PORELO_MATRIX_PORT", "soka", "kar"))
                .as("words that are not the old name").noneMatch(NoOldNameTest::says);
        assertThat(List.of("## [0.4.1] - 2026-10-08", "## [0.4.0] - 2026-10-05"))
                .as("released headings").allMatch(line -> RELEASED.matcher(line).matches());
        assertThat(RELEASED.matcher("## [Unreleased]").matches()).as("the unreleased heading is read").isFalse();
    }

    private static boolean says(final String text) {
        return text.toLowerCase(Locale.ROOT).contains(OLD);
    }

    private static List<String> tracked() throws IOException, InterruptedException {
        final ProcessBuilder builder = new ProcessBuilder("git", "ls-files", "-z").redirectErrorStream(true);
        // The machine's own git configuration is not part of the test.
        builder.environment().put("GIT_CONFIG_GLOBAL", "/dev/null");
        final Process git = builder.start();
        final String out = new String(git.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(git.waitFor()).as("git ls-files: %s", out).isZero();
        return List.of(out.split("\0"));
    }

    private static List<String> lines(final Path path) {
        try {
            return Files.readAllLines(path, StandardCharsets.UTF_8);
        } catch (final MalformedInputException ex) {
            // Not text: nothing in it is read as a word.
            return List.of();
        } catch (final IOException ex) {
            throw new UncheckedIOException("Cannot read " + path, ex);
        }
    }

}
