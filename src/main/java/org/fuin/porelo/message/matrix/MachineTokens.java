package org.fuin.porelo.message.matrix;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * A registration token of its own for each machine admitted to the account's homeserver, so
 * dropping one machine revokes its token alone. The homeserver reads every token from one file,
 * separated by whitespace; the local token stays first, where this machine's transport reads it,
 * and each machine's token is kept beside it under the machine's name, readable by this account
 * alone. The homeserver reads the file at every registration, so a change holds at once.
 */
final class MachineTokens {

    private static final String DIRECTORY = "machine-tokens";

    /** The machines expelled, each an empty file named after it. */
    static final String EXPELLED = "expelled-machines";

    /**
     * Every machine ever issued a token here, each an empty file named after it, kept when its
     * token is revoked: the host's own word on which names are a machine's, where the homeserver's
     * list holds whatever names a token's holder registered.
     */
    static final String KNOWN = "known-machines";

    private static final Set<PosixFilePermission> OWNER_ONLY =
            PosixFilePermissions.fromString("rw-------");

    private static final Set<PosixFilePermission> OWNER_ONLY_DIR =
            PosixFilePermissions.fromString("rwx------");

    private static final int BYTES = 32;

    private static final SecureRandom RANDOM = new SecureRandom();

    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    /** As Porelo names a machine: a file name of its own, never a path. */
    private static final Pattern MACHINE = Pattern.compile("[a-z0-9][a-z0-9._=-]{0,120}");

    private MachineTokens() {}

    /**
     * The machine's token, made the first time and the same on every later call, in the file the
     * homeserver reads by the time this returns.
     */
    static String issue(final Map<String, String> env, final String machine) throws Failure {
        return issue(env, machine, "", List.of(), false);
    }

    /**
     * As {@link #issue(Map, String)}, refused too for a machine whose token was revoked, for one
     * never issued a token whose accounts the homeserver lists already, and for one whose accounts'
     * names would end as those of a machine the homeserver lists by its {@code @porelo.<machine>}.
     * {@code ids} are the accounts listed on {@code server}; they may be older than this call, and
     * what the files tell is decided under the lock alone.
     */
    static String issue(
            final Map<String, String> env,
            final String machine,
            final String server,
            final List<String> ids)
            throws Failure {
        return issue(env, machine, server, ids, true);
    }

    private static String issue(
            final Map<String, String> env,
            final String machine,
            final String server,
            final List<String> ids,
            final boolean revokedIsFinal)
            throws Failure {
        final Path dir = LocalHomeserver.directory(env).resolve(DIRECTORY);
        final Path file = dir.resolve(name(machine));
        final Path tokens = HomeserverToken.file(env);
        try {
            if (!Files.isRegularFile(tokens)
                    || local(Files.readString(tokens, StandardCharsets.US_ASCII)).isEmpty()) {
                throw new Failure(
                        Exit.CONFIG,
                        "the homeserver has made no registration token of its own yet; start it"
                                + " first");
            }
            if (!Files.isDirectory(dir)) {
                Files.createDirectories(dir, PosixFilePermissions.asFileAttribute(OWNER_ONLY_DIR));
            }
            try (FileChannel channel = lock(env);
                    FileLock held = channel.lock()) {
                refuseExpelled(env, machine);
                refuseAlike(env, machine, machinesListed(ids, server));
                final Path issued = LocalHomeserver.directory(env).resolve(KNOWN);
                if (!Files.exists(file)) {
                    if (revokedIsFinal) {
                        refuseRevoked(env, machine);
                    }
                    refuseTaken(machine, ids, server);
                    final byte[] random = new byte[BYTES];
                    RANDOM.nextBytes(random);
                    write(
                            file,
                            Base64.getUrlEncoder().withoutPadding().encodeToString(random),
                            false);
                }
                mark(issued, machine);
                combine(env);
                return read(file);
            }
        } catch (final IOException ex) {
            throw new Failure(
                    Exit.CONFIG,
                    "cannot make the registration token of " + machine + ": " + ex.getMessage(),
                    ex);
        }
    }

    /**
     * The machine's token removed, from the file the homeserver reads too; answers whether it had
     * one. Refused at once once this returns.
     */
    static boolean revoke(final Map<String, String> env, final String machine) throws Failure {
        final Path file = LocalHomeserver.directory(env).resolve(DIRECTORY).resolve(name(machine));
        if (!Files.isDirectory(LocalHomeserver.directory(env))) {
            // Nothing made yet, so no token to take away and nowhere to lock.
            return false;
        }
        final boolean had;
        try (FileChannel channel = lock(env);
                FileLock held = channel.lock()) {
            known(env, file, machine);
            had = Files.deleteIfExists(file);
            // Rewritten even with no file left, so a revoke run again finishes one cut off; with
            // the homeserver's own file gone, the next start writes it from the machines' files.
            if (Files.isRegularFile(HomeserverToken.file(env))) {
                combine(env);
            }
        } catch (final IOException ex) {
            throw new Failure(
                    Exit.CONFIG,
                    "cannot remove the registration token of " + machine + ": " + ex.getMessage(),
                    ex);
        }
        return had;
    }

    /**
     * What {@code issue} refuses, checked before anything is made for the machine: a name that is
     * no machine's, a machine expelled or revoked, and one whose accounts' names would end as
     * another's.
     */
    static void admissible(final Map<String, String> env, final String machine) throws Failure {
        name(machine);
        refuseExpelled(env, machine);
        refuseAlike(env, machine, List.of());
        // The record before the token: an issue running beside makes the token first.
        if (Files.exists(LocalHomeserver.directory(env).resolve(KNOWN).resolve(machine))
                && !admitted(env, machine)) {
            refuseRevoked(env, machine);
        }
    }

    /** What {@code expel} found. */
    enum Expel {
        /** Nothing known of the machine: no token, no record, no account; nothing was done. */
        UNKNOWN,
        /** Recorded as expelled, and its token revoked. */
        REVOKED,
        /** Recorded as expelled; it held no token. */
        NO_TOKEN
    }

    /**
     * The machine recorded as expelled and its token revoked, both at once, under the tokens' lock
     * and from its files alone, so nothing the homeserver does holds the lock. The record lies
     * beside the tokens and outside their directory, so it outlives the token: every account of the
     * machine is deactivated next, and its names stay taken. A machine the files know nothing of is
     * left as it is unless {@code listed}, its accounts being listed on the homeserver, so a name
     * mistyped takes no name for good. Refused, before all else, for a machine whose name ends as
     * another's the host issued a token, whose accounts' names would end as its own; a name only
     * expelled never held one, so it refuses no machine's expel.
     */
    static Expel expel(final Map<String, String> env, final String machine, final boolean listed)
            throws Failure {
        final Path file = LocalHomeserver.directory(env).resolve(DIRECTORY).resolve(name(machine));
        final Path dir = LocalHomeserver.directory(env).resolve(EXPELLED);
        final Path record = dir.resolve(machine);
        final Path known = LocalHomeserver.directory(env).resolve(KNOWN);
        try (FileChannel channel = lock(env);
                FileLock held = channel.lock()) {
            for (final String other : held(env)) {
                if (machine.endsWith("." + other)) {
                    throw new Failure(
                            Exit.CONFIG,
                            "the machine "
                                    + other
                                    + " was issued a token here, and "
                                    + machine
                                    + "'s accounts' names end as its own; expel "
                                    + other
                                    + " instead");
                }
            }
            if (!listed
                    && !Files.exists(file)
                    && !Files.exists(record)
                    && !Files.exists(known.resolve(machine))) {
                return Expel.UNKNOWN;
            }
            mark(dir, machine);
            known(env, file, machine);
            final boolean had = Files.deleteIfExists(file);
            combine(env);
            return had ? Expel.REVOKED : Expel.NO_TOKEN;
        } catch (final IOException ex) {
            throw new Failure(
                    Exit.CONFIG,
                    "cannot record " + machine + " as expelled in " + dir + ": " + ex.getMessage(),
                    ex);
        }
    }

    /**
     * The machine's accounts among those listed, {@code @porelo.<machine>} first. An account whose
     * name ends as a longer machine's the host issued a token is that machine's, never this one's,
     * whatever the homeserver lists besides.
     */
    static List<String> accounts(
            final Map<String, String> env,
            final String machine,
            final String server,
            final List<String> ids)
            throws Failure {
        final String ending = ending(machine, server);
        final List<String> longer =
                held(env).stream()
                        .filter(other -> other.endsWith("." + machine))
                        .map(other -> ending(other, server))
                        .toList();
        final List<String> found = new ArrayList<>();
        for (final String id : ids) {
            if (!id.endsWith(ending) || longer.stream().anyMatch(id::endsWith)) {
                continue;
            }
            if (id.equals("@" + Lifecycle.ADMIN + ending)) {
                found.addFirst(id);
            } else {
                found.add(id);
            }
        }
        return found;
    }

    /**
     * A machine holding its token recorded as known before the token goes, so a machine given it
     * before the record was kept is never, for a moment, neither.
     */
    private static void known(final Map<String, String> env, final Path file, final String machine)
            throws IOException, Failure {
        if (Files.exists(file)) {
            mark(LocalHomeserver.directory(env).resolve(KNOWN), machine);
        }
    }

    /** Every machine the host issued a token: admitted now, revoked or expelled. */
    private static Set<String> held(final Map<String, String> env) throws Failure {
        final Path home = LocalHomeserver.directory(env);
        final Set<String> held = new TreeSet<>(names(home.resolve(DIRECTORY)));
        held.addAll(names(home.resolve(KNOWN)));
        return held;
    }

    /** Every machine whose accounts' names the host keeps: issued a token, or expelled. */
    private static Set<String> issued(final Map<String, String> env) throws Failure {
        final Set<String> issued = held(env);
        issued.addAll(names(LocalHomeserver.directory(env).resolve(EXPELLED)));
        return issued;
    }

    /** An empty file made in the directory given, both readable by their owner alone. */
    private static void mark(final Path dir, final String machine) throws IOException {
        if (!Files.isDirectory(dir)) {
            Files.createDirectories(dir, PosixFilePermissions.asFileAttribute(OWNER_ONLY_DIR));
        }
        final Path file = dir.resolve(machine);
        if (!Files.exists(file)) {
            Files.createFile(file, PosixFilePermissions.asFileAttribute(OWNER_ONLY));
        }
    }

    /** What every account of the machine's name ends with on the server. */
    static String ending(final String machine, final String server) {
        return "." + machine + ":" + server;
    }

    /** The machines the homeserver lists by their {@code @porelo.<machine>}. */
    static List<String> machinesListed(final List<String> ids, final String server) {
        final String prefix = "@" + Lifecycle.ADMIN + ".";
        final String suffix = ":" + server;
        return ids.stream()
                .filter(id -> id.startsWith(prefix) && id.endsWith(suffix))
                .map(id -> id.substring(prefix.length(), id.length() - suffix.length()))
                .filter(name -> MACHINE.matcher(name).matches())
                .toList();
    }

    private static void refuseTaken(
            final String machine, final List<String> ids, final String server) throws Failure {
        final String ending = ending(machine, server);
        final List<String> taken = ids.stream().filter(id -> id.endsWith(ending)).toList();
        if (!server.isEmpty() && !taken.isEmpty()) {
            throw new Failure(
                    Exit.CONFIG,
                    "the homeserver has accounts of "
                            + machine
                            + " already ("
                            + String.join(", ", taken)
                            + "), whose names a new Porelo cannot take; expel "
                            + machine
                            + ", or add the person under another machine name");
        }
    }

    private static void refuseExpelled(final Map<String, String> env, final String machine)
            throws Failure {
        if (expelled(env, machine)) {
            throw new Failure(
                    Exit.CONFIG,
                    machine
                            + " was expelled from this homeserver, and its accounts' names stay"
                            + " taken; add the person under another user name");
        }
    }

    private static void refuseRevoked(final Map<String, String> env, final String machine)
            throws Failure {
        if (Files.exists(LocalHomeserver.directory(env).resolve(KNOWN).resolve(name(machine)))) {
            throw new Failure(
                    Exit.CONFIG,
                    machine
                            + "'s token was revoked, and its accounts stay; expel "
                            + machine
                            + " and add the person under another machine name");
        }
    }

    /** Whether the machine holds a token now. */
    static boolean admitted(final Map<String, String> env, final String machine) throws Failure {
        return Files.exists(
                LocalHomeserver.directory(env).resolve(DIRECTORY).resolve(name(machine)));
    }

    /** Whether the machine was expelled. */
    static boolean expelled(final Map<String, String> env, final String machine) throws Failure {
        return Files.exists(
                LocalHomeserver.directory(env).resolve(EXPELLED).resolve(name(machine)));
    }

    /**
     * As {@code admit} refuses it: two machines whose accounts' names would end alike. Every
     * machine known is weighed: issued a token here, expelled, or listed on the homeserver by its
     * {@code @porelo}. A machine holding its token was weighed when it was issued.
     */
    private static void refuseAlike(
            final Map<String, String> env, final String machine, final List<String> listed)
            throws Failure {
        if (admitted(env, machine)) {
            return;
        }
        final Set<String> known = issued(env);
        known.addAll(listed);
        for (final String other : known) {
            if (!other.equals(machine)
                    && (other.endsWith("." + machine) || machine.endsWith("." + other))) {
                throw new Failure(
                        Exit.CONFIG,
                        "the machine "
                                + other
                                + " is known to this homeserver already, and its accounts' names and "
                                + machine
                                + "'s would end alike; admit a machine of another name");
            }
        }
    }

    static String name(final String machine) throws Failure {
        if (!MACHINE.matcher(machine).matches()) {
            throw new Failure(Exit.USAGE, "not a machine's name: '" + machine + "'");
        }
        return machine;
    }

    /**
     * The machines' tokens put back after a local token just made, so a token file made anew locks
     * out no machine admitted before.
     */
    static void include(final Map<String, String> env) throws Failure {
        final Path dir = LocalHomeserver.directory(env).resolve(DIRECTORY);
        if (!Files.isDirectory(dir)) {
            return;
        }
        try (FileChannel channel = lock(env);
                FileLock held = channel.lock()) {
            combine(env);
        } catch (final IOException ex) {
            throw new Failure(
                    Exit.CONFIG,
                    "cannot put the machines' registration tokens back: " + ex.getMessage(),
                    ex);
        }
    }

    /**
     * Every other machine's token and every record of an expelled one removed, once the account's
     * homeserver goes, and its accounts' names with it.
     */
    static void forget(final Map<String, String> env, final Lifecycle.Report report)
            throws Failure {
        forget(LocalHomeserver.directory(env).resolve(EXPELLED), report, "the record of ");
        forget(
                LocalHomeserver.directory(env)
                        .resolve(org.fuin.porelo.message.matrix.Expel.DIRECTORY),
                report,
                "the record of ");
        forget(LocalHomeserver.directory(env).resolve(KNOWN), report, "the record of ");
        forget(
                LocalHomeserver.directory(env).resolve(DIRECTORY),
                report,
                "the registration token of ");
    }

    private static void forget(final Path dir, final Lifecycle.Report report, final String what) {
        if (!Files.isDirectory(dir)) {
            return;
        }
        try (Stream<Path> files = Files.list(dir)) {
            for (final Path file : files.toList()) {
                LocalHomeserver.delete(file, report, what + file.getFileName());
            }
        } catch (final IOException ex) {
            report.kept(dir.toString(), ex.getMessage());
        }
        try {
            Files.deleteIfExists(dir);
        } catch (final IOException ex) {
            report.kept(dir.toString(), ex.getMessage());
        }
    }

    /** The machines admitted now, by name. */
    static List<String> admitted(final Map<String, String> env) throws Failure {
        return names(LocalHomeserver.directory(env).resolve(DIRECTORY));
    }

    /** The machines a directory holds a file for, by name. */
    private static List<String> names(final Path dir) throws Failure {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(dir)) {
            return files.map(file -> file.getFileName().toString())
                    .filter(name -> MACHINE.matcher(name).matches())
                    .sorted()
                    .toList();
        } catch (final IOException ex) {
            throw new Failure(
                    Exit.CONFIG, "cannot read the machines in " + dir + ": " + ex.getMessage(), ex);
        }
    }

    /** The token the local token file holds first, which is this machine's own. */
    static String local(final String content) {
        // The first line, even an empty one: a token after it is another machine's.
        final String first = content.lines().findFirst().orElse("").strip();
        return WHITESPACE.split(first, 2)[0];
    }

    /** The local token first, then every machine's, written whole over the homeserver's file. */
    private static void combine(final Map<String, String> env) throws IOException, Failure {
        final Path tokens = HomeserverToken.file(env);
        final List<String> lines = new ArrayList<>();
        lines.add(local(Files.readString(tokens, StandardCharsets.US_ASCII)));
        final Path dir = LocalHomeserver.directory(env).resolve(DIRECTORY);
        // A machine's name only: a draft an issue killed left beside them is no token.
        for (final String machine : names(dir)) {
            lines.add(read(dir.resolve(machine)));
        }
        write(tokens, String.join("\n", lines) + "\n", true);
    }

    private static String read(final Path file) throws IOException {
        return Files.readString(file, StandardCharsets.US_ASCII).strip();
    }

    /** Beside the token file, so every writer of it waits for the others. */
    private static FileChannel lock(final Map<String, String> env) throws IOException, Failure {
        return FileChannel.open(
                LocalHomeserver.directory(env).resolve(".tokens.lock"),
                Set.of(StandardOpenOption.CREATE, StandardOpenOption.WRITE),
                PosixFilePermissions.asFileAttribute(OWNER_ONLY));
    }

    /** Written whole beside it, readable by its owner alone, then moved or linked in. */
    private static void write(final Path file, final String content, final boolean replace)
            throws IOException {
        final Path draft =
                Files.createTempFile(
                        file.getParent(),
                        "." + file.getFileName() + "-",
                        ".draft",
                        PosixFilePermissions.asFileAttribute(OWNER_ONLY));
        try {
            Files.writeString(draft, content, StandardCharsets.US_ASCII);
            if (replace) {
                Files.move(draft, file, StandardCopyOption.ATOMIC_MOVE);
            } else {
                Files.createLink(file, draft);
            }
        } catch (final FileAlreadyExistsException ex) {
            // Unreachable under the lock; a machine's token is never replaced.
        } finally {
            Files.deleteIfExists(draft);
        }
    }
}
