package org.fuin.porelo.message.matrix;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The registration token of a homeserver elsewhere, read from the file the project's settings name.
 * Porelo hands a transport no variable of the person's choosing, so this file is how the token
 * reaches it. The token lets anyone register accounts on that homeserver, so a file the group or
 * others can read is refused rather than read.
 */
final class RegistrationTokenFile {

    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private RegistrationTokenFile() {}

    /** The first token on the first line of a registration-token file. */
    static String firstToken(final String content) {
        final String first = content.lines().findFirst().orElse("").strip();
        return WHITESPACE.split(first, 2)[0];
    }

    static String read(final Path file) throws Failure {
        try {
            final Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(file);
            if (permissions.contains(PosixFilePermission.GROUP_READ)
                    || permissions.contains(PosixFilePermission.OTHERS_READ)) {
                throw new Failure(
                        Exit.CONFIG,
                        "the project's registration_token_file "
                                + file
                                + " is readable by other accounts; make it readable by this"
                                + " account alone (chmod 600)");
            }
            final String token = firstToken(Files.readString(file, StandardCharsets.UTF_8));
            if (token.isEmpty()) {
                throw new Failure(
                        Exit.CONFIG, "the project's registration_token_file " + file + " is empty");
            }
            return token;
        } catch (final NoSuchFileException ex) {
            throw new Failure(
                    Exit.CONFIG,
                    "the project's registration_token_file " + file + " is missing",
                    ex);
        } catch (final IOException ex) {
            throw new Failure(
                    Exit.CONFIG,
                    "the project's registration_token_file "
                            + file
                            + " cannot be read: "
                            + ex.getMessage(),
                    ex);
        }
    }
}
