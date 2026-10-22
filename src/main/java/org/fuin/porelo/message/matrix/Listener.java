package org.fuin.porelo.message.matrix;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Whose a listening loopback port is, as the kernel's socket tables say: plain {@code http} on
 * loopback carries a token to whatever listens there, and while the account's own homeserver is
 * down another account of the machine can take its port.
 */
final class Listener {

    /** The kernel's tables of TCP sockets; the second is missing where IPv6 is off. */
    static final List<Path> TABLES = List.of(Path.of("/proc/net/tcp"), Path.of("/proc/net/tcp6"));

    private static final String LISTEN = "0A";

    /** 127.0.0.1 and 0.0.0.0, which takes its connections too, as the IPv4 table writes them. */
    private static final Set<String> IPV4 = Set.of("0100007F", "00000000");

    /** ::, which takes connections to either, as the IPv6 table writes it. */
    private static final String IPV6_ANY = "00000000000000000000000000000000";

    /** ::1, as the IPv6 table writes it. */
    private static final String IPV6_LOOPBACK = "00000000000000000000000001000000";

    /** 127.0.0.1 mapped into IPv6, as the IPv6 table writes it. */
    private static final String IPV6_MAPPED = "0000000000000000FFFF00000100007F";

    private Listener() {}

    /**
     * Refuses (78) a plain-{@code http} loopback homeserver whose port another uid listens on,
     * asked before each request. Nothing listening at that moment is left to the connection, which
     * then fails as unreachable.
     */
    static void ensureOwn(final URI homeserver, final List<Path> tables) throws Failure {
        if (!"http".equals(homeserver.getScheme()) || !Lifecycle.loopback(homeserver)) {
            return;
        }
        ensureOwn(homeserver, tables, ownUid(Path.of("/proc/self/status")));
    }

    static void ensureOwn(final URI homeserver, final List<Path> tables, final long uid)
            throws Failure {
        final int port = homeserver.getPort() >= 0 ? homeserver.getPort() : 80;
        final String hexPort = String.format(Locale.ROOT, "%04X", port);
        for (final Path table : tables) {
            final List<String> lines;
            try {
                lines = Files.readAllLines(table, StandardCharsets.US_ASCII);
            } catch (final NoSuchFileException ex) {
                if (table.equals(tables.getFirst())) {
                    throw unreadable(table, ex);
                }
                continue;
            } catch (final IOException ex) {
                throw unreadable(table, ex);
            }
            for (final String line : lines.subList(Math.min(1, lines.size()), lines.size())) {
                final String[] fields = line.trim().split("\\s+");
                if (fields.length < 8 || !LISTEN.equals(fields[3])) {
                    continue;
                }
                final int colon = fields[1].indexOf(':');
                if (colon < 0 || !hexPort.equals(fields[1].substring(colon + 1))) {
                    continue;
                }
                if (!reaches(homeserver.getHost(), fields[1].substring(0, colon))) {
                    continue;
                }
                final long owner;
                try {
                    owner = Long.parseLong(fields[7]);
                } catch (final NumberFormatException ex) {
                    throw new Failure(Exit.CONFIG, table + " names no uid in: " + line, ex);
                }
                if (owner != uid) {
                    throw new Failure(
                            Exit.CONFIG,
                            homeserver.getHost()
                                    + ":"
                                    + port
                                    + " is listened on by uid "
                                    + owner
                                    + ", not by this account (uid "
                                    + uid
                                    + "): no token goes to it. Its own homeserver is down, or"
                                    + " another account holds its port");
                }
            }
        }
    }

    /**
     * Whether a connection to {@code host} can land on a listener bound to {@code address}: a
     * listener on ::1 alone never answers 127.0.0.1, nor one on 127.0.0.1 ::1.
     */
    private static boolean reaches(final String host, final String address) {
        final boolean four = !host.contains(":");
        final boolean six = host.contains(":") || "localhost".equals(host);
        if (address.length() == 8) {
            return four && IPV4.contains(address);
        }
        return IPV6_ANY.equals(address)
                || (six && IPV6_LOOPBACK.equals(address))
                || (four && IPV6_MAPPED.equals(address));
    }

    /** The process's real uid, from its status in {@code /proc}. */
    static long ownUid(final Path status) throws Failure {
        try {
            for (final String line : Files.readAllLines(status, StandardCharsets.US_ASCII)) {
                if (line.startsWith("Uid:")) {
                    return Long.parseLong(line.substring(4).trim().split("\\s+")[0]);
                }
            }
        } catch (final IOException | NumberFormatException ex) {
            throw new Failure(Exit.CONFIG, "cannot read this process's uid from " + status, ex);
        }
        throw new Failure(Exit.CONFIG, status + " names no uid");
    }

    private static Failure unreadable(final Path table, final IOException ex) {
        return new Failure(
                Exit.CONFIG,
                "cannot read "
                        + table
                        + " to see who listens on the homeserver's port: "
                        + ex.getMessage(),
                ex);
    }
}
