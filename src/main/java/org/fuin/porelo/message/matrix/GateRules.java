package org.fuin.porelo.message.matrix;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** Pure admission and identity decisions for the host's gate. */
final class GateRules {
    private static final Pattern LOGIN = Pattern.compile("[a-z][a-z0-9-]{0,31}");
    private static final Pattern TASK = Pattern.compile("[a-z0-9]([a-z0-9._=-]*[a-z0-9_=-])?");
    private static final Pattern PROJECT = Pattern.compile("[a-z0-9][a-z0-9._=-]{0,120}");

    private GateRules() {}

    static boolean login(final String login) {
        return LOGIN.matcher(login).matches()
                && !login.equals("porelo")
                && !login.startsWith("porelo-");
    }

    static String localpart(final String account) {
        if (account.startsWith("@")) {
            final int colon = account.indexOf(':');
            if (colon < 2
                    || colon == account.length() - 1
                    || account.substring(colon + 1).chars().anyMatch(c -> c <= 32 || c == '@')) {
                return "";
            }
            return account.substring(1, colon);
        }
        return account;
    }

    static boolean belongs(final String account, final String login) {
        if (!login(login)) {
            return false;
        }
        final String local = localpart(account);
        // A name the transport takes for a task, with the login: far within a user id's 255 bytes.
        if (local.length() > 160) {
            return false;
        }
        if (local.equals(login)) {
            return true;
        }
        final int dot = local.lastIndexOf('.');
        return dot > 0
                && local.substring(dot + 1).equals(login)
                && TASK.matcher(local.substring(0, dot)).matches();
    }

    static boolean admitted(final String login, final String group, final boolean expelled) {
        if (!login(login) || expelled) {
            return false;
        }
        final List<String[]> entries =
                group.lines()
                        .map(line -> line.split(":", -1))
                        .filter(fields -> fields[0].equals("porelo-people"))
                        .toList();
        if (entries.size() != 1) {
            return false;
        }
        final String[] fields = entries.getFirst();
        return fields.length == 4
                && fields[2].matches("[0-9]+")
                && Arrays.asList(fields[3].split(",", -1)).contains(login);
    }

    static boolean project(final String project) {
        return PROJECT.matcher(project).matches();
    }

    static boolean aliasAllowed(
            final String alias, final String id, final String login, final List<String> admins) {
        return !alias.startsWith("porelo-")
                || project(alias.substring("porelo-".length()))
                        && id.startsWith("@")
                        && localpart(id).equals("porelo." + login)
                        && belongs(id, login)
                        && admins.contains(login);
    }

    static boolean redirectAllowed(final String url) {
        try {
            final URI uri = new URI(url);
            // An opaque URI's query is no query to Java, so a login token in it would go unseen.
            if (!uri.isAbsolute()
                    || uri.isOpaque()
                    || url.chars().anyMatch(c -> c <= 32 || c >= 127)
                    || url.toLowerCase(Locale.ROOT).matches(".*%0[ad].*")) {
                return false;
            }
            final String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
            if (List.of("javascript", "data", "file", "vbscript", "about", "blob")
                    .contains(scheme)) {
                return false;
            }
            if ((scheme.equals("http") || scheme.equals("https"))
                    && (uri.getHost() == null || uri.getUserInfo() != null)) {
                return false;
            }
            final String query = uri.getRawQuery();
            return query == null || !GateRequest.query(query).containsKey("loginToken");
        } catch (final URISyntaxException ex) {
            return false;
        } catch (final java.io.IOException ex) {
            return false;
        }
    }

    /**
     * An {@code Origin} whose page may read the gate's answers: none (a client that is no browser),
     * Element Desktop's, or a page served from this machine's loopback. A page elsewhere could
     * otherwise read a login answer the person's browser was made to ask for.
     */
    static boolean originAllowed(final String origin) {
        if (origin.isEmpty() || origin.equals("vector://vector")) {
            return true;
        }
        final String host = origin.replaceFirst("^https?://", "");
        return !host.equals(origin) && !host.contains("/") && hostAllowed(host);
    }

    static boolean clientPath(final String path) {
        return (path.startsWith("/_matrix/client/") || path.startsWith("/_matrix/media/"))
                && Arrays.stream(path.split("/", -1))
                        .noneMatch(part -> part.equals(".") || part.equals(".."));
    }

    static boolean loginTokenValid(
            final String owner, final String caller, final Instant expiry, final Instant now) {
        return login(caller) && owner.equals(caller) && now.isBefore(expiry);
    }

    /**
     * A {@code Host} a client of the forwarded socket sends: a loopback address or a {@code
     * *.localhost} name, with or without a port. Any other is a page elsewhere whose name now
     * points at this machine.
     */
    static boolean hostAllowed(final String host) {
        final String name =
                host.toLowerCase(Locale.ROOT)
                        .replaceFirst("^(\\[[^\\]]*\\]|[^:]*)(:[0-9]{1,5})?$", "$1");
        return name.equals("localhost")
                || name.equals("127.0.0.1")
                || name.equals("[::1]")
                || name.endsWith(".localhost") && name.matches("[a-z0-9.-]+");
    }
}
