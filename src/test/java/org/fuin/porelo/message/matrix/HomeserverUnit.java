package org.fuin.porelo.message.matrix;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Writes the homeserver's unit for the package, with the image taken from the FROM line of {@code
 * homeserver/Dockerfile}: the digest has one place, the one Dependabot moves, and the unit can
 * never run another. Run by the build before packaging; it is not part of the executable.
 */
public final class HomeserverUnit {

    static final String PLACEHOLDER = "@IMAGE@";

    private static final Pattern FROM =
            Pattern.compile("(?m)^FROM\\s+(\\S+@sha256:[0-9a-f]{64})\\s*$");

    private HomeserverUnit() {}

    /** {@code <template> <Dockerfile> <unit to write>} */
    public static void main(final String[] args) throws IOException {
        if (args.length != 3) {
            throw new IllegalArgumentException(
                    "usage: HomeserverUnit <template> <Dockerfile> <unit>");
        }
        final Path unit = Path.of(args[2]);
        final Path dir = unit.toAbsolutePath().getParent();
        if (dir != null) {
            Files.createDirectories(dir);
        }
        Files.writeString(
                unit,
                render(
                        Files.readString(Path.of(args[0]), StandardCharsets.UTF_8),
                        Files.readString(Path.of(args[1]), StandardCharsets.UTF_8)),
                StandardCharsets.UTF_8);
    }

    static String render(final String template, final String dockerfile) {
        final Matcher m = FROM.matcher(dockerfile);
        if (!m.find()) {
            throw new IllegalStateException(
                    "The Dockerfile must pin its image by digest in one FROM line");
        }
        if (!template.contains(PLACEHOLDER)) {
            throw new IllegalStateException(
                    "The unit's template has no " + PLACEHOLDER + " to fill");
        }
        return template.replace(PLACEHOLDER, m.group(1));
    }
}
