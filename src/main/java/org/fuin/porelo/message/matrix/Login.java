package org.fuin.porelo.message.matrix;

import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/** The person's login, which owns its bare account and every account with that final suffix. */
final class Login {

    private static final Pattern NAME = Pattern.compile("[a-z][a-z0-9-]{0,31}");

    private Login() {}

    static String name(final String value) throws Failure {
        if (!valid(value)) {
            throw new Failure(
                    Exit.CONFIG,
                    "a login must begin with a lower-case letter, contain only lower-case letters,"
                            + " digits and '-' (no dot), be at most 32 characters, and be neither"
                            + " 'porelo' nor a name starting with 'porelo-'");
        }
        return value;
    }

    private static boolean valid(final String value) {
        return NAME.matcher(value).matches()
                && !"porelo".equals(value)
                && !value.startsWith("porelo-");
    }

    /** The last dot separates a login from any task or relay name, which may itself hold dots. */
    static @Nullable String owner(final String id, final String server) {
        final String suffix = ":" + server;
        if (!id.startsWith("@") || !id.endsWith(suffix)) {
            return null;
        }
        final String localpart = id.substring(1, id.length() - suffix.length());
        final int dot = localpart.lastIndexOf('.');
        if (dot == 0) {
            return null;
        }
        final String login = localpart.substring(dot + 1);
        return valid(login) ? login : null;
    }

    static boolean owns(final String login, final String id, final String server) {
        return login.equals(owner(id, server));
    }
}
