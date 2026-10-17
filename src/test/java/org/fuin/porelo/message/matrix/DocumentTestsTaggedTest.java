package org.fuin.porelo.message.matrix;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * A change to documents or issues runs only the tests tagged {@code documents}; a test that reads a document
 * without the tag is left out of that run, and the run stays green while the document it checks is broken. No
 * test here reads one today - the build's {@code check-citations} and {@code check-doc-site} check them - so
 * this guard is what makes the next one carry the tag.
 */
@Tag("documents")
class DocumentTestsTaggedTest {

    private static final Path TESTS = Path.of("src", "test", "java");

    /**
     * How a test reaches a document, each as a string literal of its own: a Markdown file, the site's
     * navigation, or the documentation chapter or the issues as a directory.
     */
    private static final Pattern READS_DOCUMENTS =
            Pattern.compile("\"[^\"\\s]*\\.md\"|\"[^\"\\s]*mkdocs\\.yml\"|\"(doc|issues)/?\"");

    private static final String TAG = "@Tag(\"documents\")";

    @Test
    @DisplayName("Every test that reads a document is tagged documents")
    void everyTestThatReadsADocumentIsTagged() throws IOException {
        final List<Path> tests = tests();

        // A guard that reads nothing passes forever: assert that it reaches the test sources, then what it checks.
        assertThat(tests).extracting(path -> path.getFileName().toString())
                .as("the test sources the guard reads")
                .contains("MainTest.java", "SendTest.java", "DocumentTestsTaggedTest.java");
        assertThat(tests).filteredOn(path -> READS_DOCUMENTS.matcher(read(path)).find())
                .filteredOn(path -> !read(path).contains(TAG))
                .as("tests that read a document without %s", TAG)
                .isEmpty();
    }

    @Test
    @DisplayName("A path to a Markdown file, the navigation, doc or issues is seen, and other strings are not")
    void seesTheWaysToADocument() {
        assertThat(List.of("Path.of(\"README.md\")", "Path.of(\"doc\", \"transport.md\")", "Path.of(\"mkdocs.yml\")",
                "Path.of(\"doc\")", "Path.of(\"issues\")", "root.resolve(\"doc/\")"))
                .allMatch(code -> READS_DOCUMENTS.matcher(code).find());
        assertThat(List.of("Path.of(\"homeserver\", \"Dockerfile\")", "\"document\"", "\"a .md file\"",
                "Path.of(\".github\", \"workflows\")"))
                .noneMatch(code -> READS_DOCUMENTS.matcher(code).find());
    }

    private static List<Path> tests() throws IOException {
        try (Stream<Path> sources = Files.walk(TESTS)) {
            return sources.filter(path -> path.getFileName().toString().endsWith("Test.java")).sorted().toList();
        }
    }

    private static String read(final Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (final IOException ex) {
            throw new UncheckedIOException("Cannot read " + path, ex);
        }
    }

}
