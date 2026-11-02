package org.fuin.porelo.message.matrix;

import java.util.regex.Pattern;

/** The local homeserver's registration-token file, as the homeserver itself reads it. */
final class RegistrationTokenFile {

    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private RegistrationTokenFile() {}

    /** The first token on the first line of a registration-token file. */
    static String firstToken(final String content) {
        final String first = content.lines().findFirst().orElse("").strip();
        return WHITESPACE.split(first, 2)[0];
    }
}
