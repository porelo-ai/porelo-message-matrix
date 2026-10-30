package org.fuin.porelo.message.matrix;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** The transport's entry point, called by Porelo with a verb and its arguments. */
public final class Main {

    static final String NAME = "porelo-message-transport-matrix";

    /** The verbs Porelo hands its secrets or a project's settings, on stdin. */
    private static final Set<String> TAKES_INPUT =
            Set.of(
                    "check",
                    "send",
                    "poll",
                    "receipt",
                    "read",
                    "rename",
                    "settings",
                    "setup",
                    "enroll",
                    "retire",
                    "join",
                    "part",
                    "admit",
                    "dismiss",
                    "members",
                    "clear",
                    "expel",
                    "machine-token");

    private Main() {}

    public static void main(final String[] args) {
        // First, before any token is read: no other process of the account reads them from the
        // running transport.
        try {
            Hardening.notDumpable();
        } catch (final IllegalStateException ex) {
            System.err.println(NAME + ": " + ex.getMessage());
            System.exit(Exit.CONFIG);
        }
        System.exit(
                run(
                        args,
                        System.getenv(),
                        isTerminal(Path.of("/proc/self/fd/0"))
                                ? InputStream.nullInputStream()
                                : System.in,
                        System.out,
                        System.err));
    }

    /**
     * Runs one invocation and answers its exit code, so a test can call it without ending the JVM.
     */
    static int run(
            final String[] args,
            final Map<String, String> env,
            final PrintStream out,
            final PrintStream err) {
        return run(args, env, InputStream.nullInputStream(), out, err);
    }

    /**
     * With stdin, on which Porelo hands the secrets it kept and a project's settings; the
     * environment is read for them only when it hands none there.
     */
    static int run(
            final String[] args,
            final Map<String, String> given,
            final InputStream stdin,
            final PrintStream out,
            final PrintStream err) {
        // Fail closed: an unknown verb stops with an exit code rather than being guessed at.
        if (args.length == 0) {
            err.println("usage: " + NAME + " <verb> [arguments]");
            return Exit.USAGE;
        }
        final List<String> rest = Arrays.asList(args).subList(1, args.length);
        try {
            final Map<String, String> env;
            final InputStream in;
            if (TAKES_INPUT.contains(args[0])) {
                final Input input = input(args[0], stdin, given);
                env = input.env();
                in = input.settings();
            } else {
                env = given;
                in = InputStream.nullInputStream();
            }
            // Said on every start, so a switched-off check never goes unnoticed in a log.
            if ("off".equals(env.get(Config.TLS_VERIFY))) {
                err.println(NAME + ": " + Config.VERIFY_OFF_WARNING);
            }
            switch (args[0]) {
                case "describe" -> Describe.run(rest, out);
                case "check" -> Check.run(rest, env);
                case "send" -> Send.run(rest, Config.from(env), out);
                case "poll" -> Poll.run(rest, Config.from(env), env, err);
                case "receipt" -> Receipt.run(rest, Config.from(env), env, out, err);
                case "read" -> Read.run(rest, Config.from(env), env);
                case "rename" -> Rename.run(rest, Config.from(env));
                case "room" -> Room.start(rest, env, out, err);
                case "homeserver-token" -> HomeserverToken.run(rest, env, err);
                case "gate" -> Gate.run(rest, env, out, err);
                case "publish" -> Publish.run(rest, env, out, err, new Publish.Systemd());
                case "settings" -> SettingsCheck.run(rest, in, out);
                case "setup",
                        "enroll",
                        "retire",
                        "join",
                        "part",
                        "admit",
                        "dismiss",
                        "members",
                        "clear" ->
                        Lifecycle.run(args[0], rest, env, in, out, err, new PodmanUnit(env));
                case "machine-token" -> MachineTokenVerb.run(rest, env, out, new PodmanUnit(env));
                case "expel" -> Expel.run(rest, env, out, new PodmanUnit(env));
                case "backup", "restore" -> Keep.run(args[0], rest, env, new PodmanUnit(env), out);
                default -> {
                    err.println(NAME + ": unknown verb '" + args[0] + "'");
                    return Exit.USAGE;
                }
            }
            return Exit.OK;
        } catch (final Failure failure) {
            return reportFailure(args[0], failure, err);
        }
    }

    private static Input input(
            final String verb, final InputStream stdin, final Map<String, String> given)
            throws Failure {
        try {
            return Input.resolve(stdin, given);
        } catch (final Failure failure) {
            // check knows two answers, usable or not.
            if ("check".equals(verb)) {
                throw new Failure(
                        Check.NOT_USABLE,
                        failure.getMessage() == null ? "not usable" : failure.getMessage(),
                        failure);
            }
            throw failure;
        }
    }

    /**
     * Whether stdin is a terminal, where a person runs a verb by hand with the environment of the
     * old way: read there, the verb would wait for an end of input that never comes. Porelo hands a
     * pipe or a file, never a terminal.
     */
    static boolean isTerminal(final Path stdin) {
        try {
            final String target = Files.readSymbolicLink(stdin).toString();
            return target.startsWith("/dev/pts/") || target.startsWith("/dev/tty");
        } catch (final IOException | UnsupportedOperationException ex) {
            return false;
        }
    }

    /** The failure on stderr as a run ends with it, so a test reads the very lines Porelo reads. */
    static int reportFailure(final String verb, final Failure failure, final PrintStream err) {
        err.println(NAME + " " + verb + ": " + failure.getMessage());
        return failure.exitCode();
    }
}
