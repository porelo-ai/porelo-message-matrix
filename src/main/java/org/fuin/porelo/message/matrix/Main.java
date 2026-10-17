package org.fuin.porelo.message.matrix;

import java.io.InputStream;
import java.io.PrintStream;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/** The transport's entry point, called by Porelo with a verb and its arguments. */
public final class Main {

    static final String NAME = "porelo-message-transport-matrix";

    private Main() {}

    public static void main(final String[] args) {
        System.exit(run(args, System.getenv(), System.in, System.out, System.err));
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

    /** With stdin, which the lifecycle verbs read the project's settings from. */
    static int run(
            final String[] args,
            final Map<String, String> env,
            final InputStream in,
            final PrintStream out,
            final PrintStream err) {
        // Fail closed: an unknown verb stops with an exit code rather than being guessed at.
        if (args.length == 0) {
            err.println("usage: " + NAME + " <verb> [arguments]");
            return Exit.USAGE;
        }
        final List<String> rest = Arrays.asList(args).subList(1, args.length);
        // Said on every start, so a switched-off check never goes unnoticed in a log.
        if ("off".equals(env.get(Config.TLS_VERIFY))) {
            err.println(NAME + ": " + Config.VERIFY_OFF_WARNING);
        }
        try {
            switch (args[0]) {
                case "describe" -> Describe.run(rest, out);
                case "check" -> Check.run(rest, env);
                case "send" -> Send.run(rest, Config.from(env), out);
                case "poll" -> Poll.run(rest, Config.from(env), env, err);
                case "receipt" -> Receipt.run(rest, Config.from(env), env, out, err);
                case "read" -> Read.run(rest, Config.from(env), env);
                case "rename" -> Rename.run(rest, Config.from(env));
                case "homeserver-token" -> HomeserverToken.run(rest, env);
                case "settings" -> SettingsCheck.run(rest, in, out);
                case "setup", "enroll", "retire", "join", "clear" ->
                        Lifecycle.run(args[0], rest, env, in, out, err);
                default -> {
                    err.println(NAME + ": unknown verb '" + args[0] + "'");
                    return Exit.USAGE;
                }
            }
            return Exit.OK;
        } catch (final Failure failure) {
            err.println(NAME + " " + args[0] + ": " + failure.getMessage());
            return failure.exitCode();
        }
    }
}
