package org.fuin.porelo.message.matrix;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * NullAway checks only what is {@code @NullMarked}, and skips an unmarked package in silence - so
 * the marking itself needs a check that fails loudly.
 */
class NullMarkedPackagesTest {

    private static final Path MAIN_SOURCES = Path.of("src", "main", "java");

    private static final Pattern NULL_MARKED =
            Pattern.compile("(?m)^\\s*@(org\\.jspecify\\.annotations\\.)?NullMarked\\b");

    @Test
    @DisplayName("Every package holding main code is @NullMarked")
    void everyMainPackageIsNullMarked() throws IOException {
        final List<Path> packages = packagesWithJavaSources();

        assertThat(packages).as("packages under %s", MAIN_SOURCES).isNotEmpty();
        assertThat(packages)
                .allSatisfy(
                        dir ->
                                assertThat(isNullMarked(dir))
                                        .as("%s has a package-info.java carrying @NullMarked", dir)
                                        .isTrue());
    }

    private static List<Path> packagesWithJavaSources() throws IOException {
        try (Stream<Path> files = Files.walk(MAIN_SOURCES)) {
            return files.filter(f -> f.toString().endsWith(".java"))
                    .map(Path::getParent)
                    .distinct()
                    .sorted()
                    .toList();
        }
    }

    private static boolean isNullMarked(final Path dir) {
        final Path packageInfo = dir.resolve("package-info.java");
        if (!Files.isRegularFile(packageInfo)) {
            return false;
        }
        try {
            return NULL_MARKED
                    .matcher(Files.readString(packageInfo, StandardCharsets.UTF_8))
                    .find();
        } catch (final IOException ex) {
            throw new IllegalStateException("Cannot read " + packageInfo, ex);
        }
    }
}
