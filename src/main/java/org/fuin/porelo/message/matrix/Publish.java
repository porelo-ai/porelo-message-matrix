package org.fuin.porelo.message.matrix;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.PrintStream;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * {@code publish --address <address>:<port>} and {@code publish --off}: the account's homeserver
 * served for another machine on an address of this one, a VPN's, over TLS with the account's own
 * certificate. Not a verb of Porelo's transport contract - whoever brings this machine onto the VPN
 * runs it. The socket {@code porelo-matrix-homeserver-vpn} listens on the address and forwards to
 * the homeserver's loopback port; the address lives in a drop-in of the account's. The unit reads
 * the TLS settings from {@code tls.env}, which every start writes anew: the server certificate's
 * settings while {@code PORELO_MATRIX_PUBLISH} is set, nothing otherwise. Tuwunel's TLS covers
 * every listener and takes plain {@code http} beside it, so this machine's transport keeps its
 * plain loopback port.
 */
final class Publish {

    /** The file the unit hands podman as {@code --env-file}, beside the configuration. */
    static final String TLS_ENV = "tls.env";

    /** Where the unit mounts the {@code tls} directory in the container. */
    static final String MOUNT = "/tls";

    static final String SOCKET = "porelo-matrix-homeserver-vpn.socket";

    /** The service the socket starts, which forwards each connection to the loopback port. */
    static final String FORWARDER = "porelo-matrix-homeserver-vpn.service";

    /** Below it a port takes privileges an account's socket has not. */
    private static final int FIRST_UNPRIVILEGED_PORT = 1024;

    /** What {@code publish} asks of the account's service manager. */
    interface Control {

        /** Has the service manager read the units and drop-ins again. */
        void reload() throws Failure;

        /** Restarts the homeserver if it runs, so it serves the certificate just made. */
        void restartHomeserver() throws Failure;

        /** The socket enabled and listening on the address its drop-in names now. */
        void listen() throws Failure;

        /** The socket stopped and disabled. */
        void close() throws Failure;
    }

    private static final Pattern ADDRESS =
            Pattern.compile("(?:\\[([0-9a-fA-F:.]+)]|([0-9.]+)):(\\d{1,5})");

    record Address(InetAddress ip, int port) {

        /** As the socket and a URL name it: an IPv6 address in brackets. */
        String text() {
            final String host = ip.getHostAddress();
            return (host.contains(":") ? "[" + host + "]" : host) + ":" + port;
        }
    }

    private Publish() {}

    static void run(
            final List<String> args,
            final Map<String, String> env,
            final PrintStream out,
            final PrintStream err,
            final Control control)
            throws Failure {
        final ObjectNode answer = MatrixClient.JSON.createObjectNode();
        if (args.equals(List.of("--off"))) {
            off(env, control);
            answer.putNull("published");
        } else if ((args.size() == 2 || args.size() == 4 && "--name".equals(args.get(2)))
                && "--address".equals(args.getFirst())) {
            final Address address = address(args.get(1));
            final @Nullable String name = args.size() == 4 ? hostName(args.get(3)) : null;
            if (address.ip().isLoopbackAddress()) {
                throw new Failure(
                        Exit.CONFIG,
                        "the homeserver is on loopback already; publish it on an address another"
                                + " machine reaches");
            }
            if (address.port() < FIRST_UNPRIVILEGED_PORT) {
                throw new Failure(
                        Exit.CONFIG,
                        "an account's socket cannot listen on port "
                                + address.port()
                                + "; take one from "
                                + FIRST_UNPRIVILEGED_PORT
                                + " on");
            }
            final @Nullable String notice = on(env, address, name, control);
            if (notice != null) {
                err.println(Main.NAME + " publish: " + notice);
            }
            answer.put("published", address.text());
            if (name != null) {
                answer.put("name", name);
            }
            answer.put(
                    "ca_file",
                    LocalHomeserver.directory(env).resolve(Certificate.CA_CERTIFICATE).toString());
        } else {
            throw new Failure(
                    Exit.USAGE,
                    "usage: publish --address <address>:<port> [--name <host name>] | publish --off");
        }
        out.println(answer);
    }

    private static @Nullable String on(
            final Map<String, String> env,
            final Address address,
            final @Nullable String name,
            final Control control)
            throws Failure {
        final Path conf = LocalHomeserver.directory(env).resolve("homeserver.conf");
        // The port a project's setup chose and the homeserver runs on; a new one would move it
        // away from this machine's transport.
        try {
            if (!Files.exists(conf)
                    || LocalHomeserver.setting(conf, LocalHomeserver.PORT) == null) {
                throw new Failure(
                        Exit.CONFIG,
                        conf
                                + " names no port for the homeserver: set a project up first, which"
                                + " gives it its port");
            }
        } catch (final IOException ex) {
            throw new Failure(Exit.CONFIG, "cannot read " + conf + ": " + ex.getMessage(), ex);
        }
        if (name != null) {
            try {
                if (!Integer.toString(address.port())
                        .equals(LocalHomeserver.setting(conf, LocalHomeserver.PORT))) {
                    throw new Failure(
                            Exit.CONFIG,
                            "published by a name, one URL reaches the homeserver from every machine,"
                                    + " so the published port must be its loopback port "
                                    + LocalHomeserver.setting(conf, LocalHomeserver.PORT));
                }
            } catch (final IOException ex) {
                throw new Failure(Exit.CONFIG, "cannot read " + conf + ": " + ex.getMessage(), ex);
            }
        }
        setting(conf, LocalHomeserver.PUBLISH, address.text());
        setting(conf, LocalHomeserver.PUBLISH_NAME, name);
        final @Nullable String notice = prepare(env);
        write(dropIn(env), "[Socket]\nListenStream=\nListenStream=" + address.text() + "\n");
        control.reload();
        control.restartHomeserver();
        control.listen();
        return notice;
    }

    private static void off(final Map<String, String> env, final Control control) throws Failure {
        control.close();
        try {
            Files.deleteIfExists(dropIn(env));
        } catch (final IOException ex) {
            throw new Failure(
                    Exit.CONFIG, "cannot remove " + dropIn(env) + ": " + ex.getMessage(), ex);
        }
        control.reload();
        final Path dir = LocalHomeserver.directory(env);
        if (Files.exists(dir.resolve("homeserver.conf"))) {
            setting(dir.resolve("homeserver.conf"), LocalHomeserver.PUBLISH, null);
            setting(dir.resolve("homeserver.conf"), LocalHomeserver.PUBLISH_NAME, null);
        }
        Certificate.removeServer(dir);
        prepare(env);
        control.restartHomeserver();
    }

    /**
     * The drop-in that gives the socket its address: {@code
     * $XDG_CONFIG_HOME/systemd/user/porelo-matrix-homeserver-vpn.socket.d/publish.conf}.
     */
    static Path dropIn(final Map<String, String> env) throws Failure {
        return LocalHomeserver.configHome(env)
                .resolve("systemd")
                .resolve("user")
                .resolve(SOCKET + ".d")
                .resolve("publish.conf");
    }

    /** {@code homeserver.conf} with its published address set, or removed for null. */
    private static void setting(final Path conf, final String key, final @Nullable String value)
            throws Failure {
        try {
            final List<String> lines = new ArrayList<>();
            for (final String line : Files.readAllLines(conf, StandardCharsets.UTF_8)) {
                if (!line.strip().startsWith(key + "=")) {
                    lines.add(line);
                }
            }
            if (value != null) {
                lines.add(key + "=" + value);
            }
            write(conf, String.join("\n", lines) + "\n");
        } catch (final IOException ex) {
            throw new Failure(Exit.CONFIG, "cannot read " + conf + ": " + ex.getMessage(), ex);
        }
    }

    /** Written whole beside it, then moved over it, so no reader sees half a file. */
    private static void write(final Path file, final String content) throws Failure {
        try {
            final Path dir = file.getParent();
            Files.createDirectories(dir);
            final Path draft =
                    Files.createTempFile(
                            dir,
                            "." + file.getFileName() + "-",
                            ".draft",
                            PosixFilePermissions.asFileAttribute(
                                    PosixFilePermissions.fromString("rw-------")));
            try {
                Files.writeString(draft, content, StandardCharsets.UTF_8, StandardOpenOption.WRITE);
                Files.move(draft, file, StandardCopyOption.ATOMIC_MOVE);
            } finally {
                Files.deleteIfExists(draft);
            }
        } catch (final IOException ex) {
            throw new Failure(Exit.CONFIG, "cannot write " + file + ": " + ex.getMessage(), ex);
        }
    }

    /** The account's service manager, through {@code systemctl --user}. */
    static final class Systemd implements Control {

        @Override
        public void reload() throws Failure {
            call("daemon-reload");
        }

        @Override
        public void restartHomeserver() throws Failure {
            call("try-restart", LocalHomeserver.UNIT);
        }

        @Override
        public void listen() throws Failure {
            call("enable", SOCKET);
            // A forwarder that runs holds the old listening socket; it starts anew on the next
            // connection with the new one.
            call("stop", FORWARDER);
            // A restart, not a start: a socket listening already takes its new address only so.
            call("restart", SOCKET);
        }

        @Override
        public void close() throws Failure {
            if (!closeSocket()) {
                throw new Failure(
                        Exit.CONFIG, "systemctl --user disable --now " + SOCKET + " failed");
            }
        }

        /**
         * The socket closed and disabled, and its forwarder stopped with it: a forwarder that runs
         * holds the listening socket, and would keep the address open. Answers whether both held.
         */
        static boolean closeSocket() throws Failure {
            return LocalHomeserver.systemctl("disable", "--now", SOCKET).isPresent()
                    && LocalHomeserver.systemctl("stop", FORWARDER).isPresent();
        }

        private static void call(final String... args) throws Failure {
            if (LocalHomeserver.systemctl(args).isEmpty()) {
                throw new Failure(
                        Exit.CONFIG, "systemctl --user " + String.join(" ", args) + " failed");
            }
        }
    }

    /**
     * {@code <ipv4>:<port>} or {@code [<ipv6>]:<port>}: an address literal, never a name, which
     * could resolve elsewhere later.
     */
    static Address address(final String value) throws Failure {
        final Matcher m = ADDRESS.matcher(value.strip());
        if (!m.matches()) {
            throw new Failure(
                    Exit.CONFIG,
                    LocalHomeserver.PUBLISH
                            + " must be <address>:<port> or [<address>]:<port>, not '"
                            + value
                            + "'");
        }
        final InetAddress ip;
        try {
            ip = InetAddress.ofLiteral(m.group(1) != null ? m.group(1) : m.group(2));
        } catch (final IllegalArgumentException ex) {
            throw new Failure(
                    Exit.CONFIG,
                    LocalHomeserver.PUBLISH + " names no address: '" + value + "'",
                    ex);
        }
        final int port = Integer.parseInt(m.group(3));
        if (port < 1 || port > 65535) {
            throw new Failure(
                    Exit.CONFIG, LocalHomeserver.PUBLISH + " names no port: '" + value + "'");
        }
        if (ip.isAnyLocalAddress() || ip.isMulticastAddress()) {
            throw new Failure(
                    Exit.CONFIG,
                    LocalHomeserver.PUBLISH
                            + " must name one address of this machine, not "
                            + ip.getHostAddress());
        }
        return new Address(ip, port);
    }

    /** A host name in lower case, two labels at least, as a certificate names it. */
    private static final Pattern HOST_NAME =
            Pattern.compile(
                    "[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?)+");

    static String hostName(final String value) throws Failure {
        if (!HOST_NAME.matcher(value).matches() || value.length() > 253) {
            throw new Failure(
                    Exit.CONFIG,
                    "--name must be a host name in lower case, such as matrix.example.org, not '"
                            + value
                            + "'");
        }
        return value;
    }

    /** The name the homeserver is published under in {@code homeserver.conf}, or null. */
    static @Nullable String name(final Path conf) throws Failure {
        try {
            final @Nullable String value =
                    Files.exists(conf)
                            ? LocalHomeserver.setting(conf, LocalHomeserver.PUBLISH_NAME)
                            : null;
            return value == null || value.isEmpty() ? null : hostName(value);
        } catch (final IOException ex) {
            throw new Failure(Exit.CONFIG, "cannot read " + conf + ": " + ex.getMessage(), ex);
        }
    }

    /** The published address in {@code homeserver.conf}, or null when it names none. */
    static @Nullable Address published(final Path conf) throws Failure {
        try {
            final @Nullable String value =
                    Files.exists(conf)
                            ? LocalHomeserver.setting(conf, LocalHomeserver.PUBLISH)
                            : null;
            return value == null || value.isEmpty() ? null : address(value);
        } catch (final IOException ex) {
            throw new Failure(Exit.CONFIG, "cannot read " + conf + ": " + ex.getMessage(), ex);
        }
    }

    /**
     * Before every start: the certificate made or renewed for the published address, and the TLS
     * settings written for the unit, or none when nothing is published. The {@code tls} directory
     * always exists, since the unit mounts it. Answers what to say of a placed certificate near its
     * end, or null.
     */
    static @Nullable String prepare(final Map<String, String> env) throws Failure {
        final Path dir = LocalHomeserver.directory(env);
        final @Nullable Address address = published(dir.resolve("homeserver.conf"));
        final String settings;
        @Nullable String notice = null;
        if (address == null) {
            settings = "";
        } else {
            Certificate.ensure(
                    dir, address.ip(), name(dir.resolve("homeserver.conf")), Instant.now());
            notice = Certificate.notice(dir, Instant.now());
            settings =
                    "TUWUNEL_TLS__CERTS="
                            + MOUNT
                            + "/"
                            + Certificate.SERVER_CERTIFICATE
                            + "\nTUWUNEL_TLS__KEY="
                            + MOUNT
                            + "/"
                            + Certificate.SERVER_KEY
                            // The loopback listener stays plain http for this machine's transport.
                            + "\nTUWUNEL_TLS__DUAL_PROTOCOL=true\n";
        }
        try {
            final Path tls = dir.resolve(Certificate.TLS);
            if (!Files.isDirectory(tls)) {
                Files.createDirectories(
                        tls,
                        PosixFilePermissions.asFileAttribute(
                                PosixFilePermissions.fromString("rwx------")));
            }
        } catch (final IOException ex) {
            throw new Failure(
                    Exit.CONFIG,
                    "cannot make the homeserver's TLS directory in " + dir + ": " + ex.getMessage(),
                    ex);
        }
        write(dir.resolve(TLS_ENV), settings);
        return notice;
    }
}
