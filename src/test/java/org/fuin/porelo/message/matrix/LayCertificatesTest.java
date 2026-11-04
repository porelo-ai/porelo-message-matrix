package org.fuin.porelo.message.matrix;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * The script that lays a person's certificates on a host, with the real {@code ssh-keygen} and
 * stand-ins for {@code ssh} and {@code porelo}: the first runs what it is sent in this account, the
 * second finds a record as Porelo does, by the key's fingerprint in a file name's alphabet.
 */
@Timeout(120)
class LayCertificatesTest {

    private static final String SCRIPT = Path.of("build/lay-certificates.sh").toString();

    private static final String PRINCIPAL = "task:demo:porelo-agent-core";

    private static final String SSH =
            """
            #!/bin/sh
            echo called >>"$CALLS"
            exec sh -s
            """;

    private static final String PORELO =
            """
            #!/bin/sh
            [ -z "${PORELO_FINDS_NOTHING:-}" ] || exit 66
            [ -z "${PORELO_READS_ITS_INPUT:-}" ] || cat >/dev/null
            name=$(printf '%s' "${3#SHA256:}" | tr '+/' '-_')
            cat "${XDG_STATE_HOME:-$HOME/.local/state}/porelo/task-certificates/$name.json"
            [ "$3" != "${PORELO_REFUSES:-}" ] || exit 70
            """;

    @TempDir private Path dir;

    private Path home;

    private Path calls;

    private Path person;

    private record Run(int exit, String output) {}

    @BeforeEach
    void standIns() throws Exception {
        home = dir.resolve("home");
        Files.createDirectories(home);
        calls = dir.resolve("calls");
        final Path bin = Files.createDirectories(dir.resolve("bin"));
        executable(bin.resolve("ssh"), SSH);
        executable(bin.resolve("porelo"), PORELO);
        person = dir.resolve("person");
        keygen("-q", "-t", "ed25519", "-N", "", "-C", "", "-f", person.toString());
    }

    /** Harm guarded: the record lies where the receiver never looks, and every message is held. */
    @Test
    void aRecordLiesWhereTheReceiverLooksForItsKey() throws Exception {
        final Certified certified = certified("-V", "-1h:+1d", "-n", PRINCIPAL);

        final Run run = lay("alice", "host.example.org", "demo", certified.file());

        assertThat(run.exit()).as(run.output()).isZero();
        assertThat(Files.readString(record(certified.fingerprint())))
                .as("the certificate alone, without what its file carried beside it")
                .isEqualTo("{\"certificate\":\"" + certified.line() + "\"}\n");
        assertThat(run.output())
                .as("what was laid, for whoever signed it to check")
                .contains(certified.fingerprint(), PRINCIPAL, "signed by");
        assertThat(certificates())
                .as("no temporary file stays behind")
                .containsExactly(record(certified.fingerprint()));
    }

    /** Harm guarded: a certificate someone changed is laid on a host in the person's name. */
    @Test
    void aCertificateWhoseSignatureFailsIsRefused() throws Exception {
        final Certified certified = certified("-V", "-1h:+1d", "-n", PRINCIPAL);
        final byte[] blob = Base64.getDecoder().decode(certified.line().split(" ")[1]);
        blob[blob.length - 1] ^= 1;
        final Path changed = dir.resolve("changed-cert.pub");
        Files.writeString(
                changed,
                "ssh-ed25519-cert-v01@openssh.com "
                        + Base64.getEncoder().encodeToString(blob)
                        + "\n");

        assertRefusedBeforeAnythingLeaves(lay("alice", "host", "demo", changed), "signature");
    }

    /** Harm guarded: a private key handed by mistake leaves the machine, or shows in an answer. */
    @Test
    void aPrivateKeyIsRefusedAndNotShown() throws Exception {
        final Run run = lay("alice", "host", "demo", person);

        assertRefusedBeforeAnythingLeaves(run, "one line");
        assertThat(run.output()).as("not a word of the key").doesNotContain("PRIVATE KEY");
    }

    /** Harm guarded: a plain public key reads as vouched for, with nobody having signed it. */
    @Test
    void aPlainPublicKeyIsRefused() throws Exception {
        assertRefusedBeforeAnythingLeaves(
                lay("alice", "host", "demo", dir.resolve("person.pub")), "no Ed25519 certificate");
    }

    /** Harm guarded: what the receiver would refuse on arrival looks laid until a task waits. */
    @Test
    void whatTheReceiverWouldRefuseIsRefusedHere() throws Exception {
        assertRefusedBeforeAnythingLeaves(
                lay("alice", "host", "demo", certified("-V", "-2d:-1d", "-n", PRINCIPAL).file()),
                "expired");
        assertRefusedBeforeAnythingLeaves(
                lay(
                        "alice",
                        "host",
                        "demo",
                        certified("-V", "-1h:+1d", "-n", PRINCIPAL + ",x").file()),
                "principal");
        assertRefusedBeforeAnythingLeaves(
                lay("alice", "host", "other", certified("-V", "-1h:+1d", "-n", PRINCIPAL).file()),
                "principal");
        assertRefusedBeforeAnythingLeaves(
                lay(
                        "alice",
                        "host",
                        "demo",
                        certified("-V", "-1h:+1d", "-h", "-n", PRINCIPAL).file()),
                "no user certificate");
        assertRefusedBeforeAnythingLeaves(
                lay(
                        "alice",
                        "host",
                        "demo",
                        certified("-V", "-1h:+1d", "-O", "force-command=/bin/true", "-n", PRINCIPAL)
                                .file()),
                "critical option");
    }

    /** Harm guarded: one bad file leaves some agents laid and others not, and nobody sees which. */
    @Test
    void oneBadFileLaysNone() throws Exception {
        final Certified good = certified("-V", "-1h:+1d", "-n", PRINCIPAL);

        assertRefusedBeforeAnythingLeaves(
                lay("alice", "host", "demo", good.file(), dir.resolve("person.pub")),
                "no Ed25519 certificate");
        assertThat(certificates()).as("the good one stayed home too").isEmpty();
    }

    /** Harm guarded: a task's own record, kept after it ended, is lost to a laid one. */
    @Test
    void poreloSOwnRecordIsNotReplaced() throws Exception {
        final Certified certified = certified("-V", "-1h:+1d", "-n", PRINCIPAL);
        final Path own = record(certified.fingerprint());
        Files.createDirectories(own.getParent());
        final String kept = "{\"task\":\"a-task\",\"certificate\":\"kept\"}\n";
        Files.writeString(own, kept);

        final Run run = lay("alice", "host", "demo", certified.file());

        assertThat(run.exit()).as(run.output()).isNotZero();
        assertThat(run.output()).as("it names the record").contains(own.toString());
        assertThat(Files.readString(own)).as("the record is unchanged").isEqualTo(kept);
    }

    /** Harm guarded: a renewal cannot replace the week-old certificate of the same key. */
    @Test
    void layingAgainRenews() throws Exception {
        final Certified first = certified("-V", "-1h:+1d", "-n", PRINCIPAL);
        assertThat(lay("alice", "host", "demo", first.file()).exit()).isZero();
        final Certified second = signed(first.key(), "-V", "-1h:+2d", "-n", PRINCIPAL);
        assertThat(second.fingerprint()).as("the same key").isEqualTo(first.fingerprint());
        assertThat(second.line()).as("another certificate").isNotEqualTo(first.line());

        final Run run = lay("alice", "host", "demo", second.file());

        assertThat(run.exit()).as(run.output()).isZero();
        assertThat(Files.readString(record(second.fingerprint())))
                .as("the new one holds")
                .contains(second.line());
    }

    /** Harm guarded: a principal that reads as a heading hides the rest from the checks. */
    @Test
    void aPrincipalReadingAsAHeadingHidesNothing() throws Exception {
        assertRefusedBeforeAnythingLeaves(
                lay(
                        "alice",
                        "host",
                        "demo",
                        certified(
                                        "-V",
                                        "-1h:+1d",
                                        "-O",
                                        "force-command=/bin/sh",
                                        "-n",
                                        PRINCIPAL + ",Critical Options: (none),task:other:evil")
                                .file()),
                "principal");
    }

    /** Harm guarded: one line is checked and another laid, under a good key's name. */
    @Test
    void aSecondLineWithoutItsNewlineIsRefused() throws Exception {
        final Certified good = certified("-V", "-1h:+1d", "-n", PRINCIPAL);
        final Path file = dir.resolve("two-lines-cert.pub");
        Files.writeString(file, "ssh-ed25519-cert-v01@openssh.com AAAA\n" + good.line());

        assertRefusedBeforeAnythingLeaves(lay("alice", "host", "demo", file), "one line");
    }

    /** Harm guarded: Porelo's answer with a failure reads as having found the record. */
    @Test
    void anAnswerWithAFailureIsNoFinding() throws Exception {
        final Certified certified = certified("-V", "-1h:+1d", "-n", PRINCIPAL);

        final Run run =
                run(
                        List.of("alice", "host", "demo", certified.file().toString()),
                        Map.of("PORELO_REFUSES", certified.fingerprint()));

        assertThat(run.exit()).as(run.output()).isNotZero();
        assertThat(run.output())
                .as("what it left")
                .contains("left: " + record(certified.fingerprint()));
    }

    /** Harm guarded: a program reading its input eats the rest of the script, laying less. */
    @Test
    void whatPoreloReadsIsNotTheRestOfTheScript() throws Exception {
        // More than the shell reads ahead at once, or nothing is left on its input to take.
        final List<Certified> many = new ArrayList<>();
        final List<String> arguments = new ArrayList<>(List.of("alice", "host", "demo"));
        for (int i = 0; i < 12; i++) {
            final Certified one = certified("-V", "-1h:+1d", "-n", PRINCIPAL);
            many.add(one);
            arguments.add(one.file().toString());
        }

        final Run run = run(arguments, Map.of("PORELO_READS_ITS_INPUT", "1"));

        assertThat(run.exit()).as(run.output()).isZero();
        assertThat(record(many.getLast().fingerprint())).as("the last one was laid too").exists();
    }

    /** Harm guarded: a failure halfway leaves records on the host that nobody knows of. */
    @Test
    void aFailureHalfwayNamesEveryRecordItLeft() throws Exception {
        final Certified first = certified("-V", "-1h:+1d", "-n", PRINCIPAL);
        final Certified second = certified("-V", "-1h:+1d", "-n", PRINCIPAL);

        final Run run =
                run(
                        List.of(
                                "alice",
                                "host",
                                "demo",
                                first.file().toString(),
                                second.file().toString()),
                        Map.of("PORELO_REFUSES", second.fingerprint()));

        assertThat(run.exit()).as(run.output()).isNotZero();
        assertThat(run.output())
                .as("both records it left")
                .contains(
                        "left: " + record(first.fingerprint()),
                        "left: " + record(second.fingerprint()));
    }

    /** Harm guarded: a record it cannot read may be Porelo's own, and is lost to a laid one. */
    @Test
    void aRecordItCannotReadIsNotReplaced() throws Exception {
        final Certified certified = certified("-V", "-1h:+1d", "-n", PRINCIPAL);
        final Path own = record(certified.fingerprint());
        Files.createDirectories(own.getParent());
        final String kept = "{\"task\":\"a-task\",\"certificate\":\"kept\"}\n";
        Files.writeString(own, kept);
        Files.setPosixFilePermissions(own, PosixFilePermissions.fromString("---------"));
        assumeThat(Files.isReadable(own)).as("nobody here reads past the mode").isFalse();

        final Run run = lay("alice", "host", "demo", certified.file());

        Files.setPosixFilePermissions(own, PosixFilePermissions.fromString("rw-------"));
        assertThat(run.exit()).as(run.output()).isNotZero();
        assertThat(Files.readString(own)).as("the record is unchanged").isEqualTo(kept);
    }

    /** Harm guarded: a machine left half-done by a dead run is written over unseen. */
    @Test
    void whatADeadRunLeftStopsTheNext() throws Exception {
        final Certified certified = certified("-V", "-1h:+1d", "-n", PRINCIPAL);
        final Path left = record(certified.fingerprint()).resolveSibling(".laying-abc");
        Files.createDirectories(left.getParent());
        Files.writeString(left, "");

        final Run run = lay("alice", "host", "demo", certified.file());

        assertThat(run.exit()).as(run.output()).isNotZero();
        assertThat(run.output()).as("it names what it found").contains(left.toString());
        assertThat(record(certified.fingerprint())).as("nothing was laid").doesNotExist();
    }

    /** Harm guarded: a record Porelo does not read counts as laid, and messages are held later. */
    @Test
    void aRecordPoreloDoesNotFindFailsNamingIt() throws Exception {
        final Certified certified = certified("-V", "-1h:+1d", "-n", PRINCIPAL);

        final Run run =
                run(
                        List.of("alice", "host", "demo", certified.file().toString()),
                        Map.of("PORELO_FINDS_NOTHING", "1"));

        assertThat(run.exit()).as(run.output()).isNotZero();
        assertThat(run.output())
                .as("what failed, and what it left")
                .contains("does not find", "left: " + record(certified.fingerprint()));
    }

    /** Harm guarded: a value of the command line runs on the host as part of its script. */
    @Test
    void anArgumentOfAnotherShapeIsRefused() throws Exception {
        final Path file = certified("-V", "-1h:+1d", "-n", PRINCIPAL).file();
        for (final List<String> arguments :
                List.of(
                        List.of("-oProxyCommand=x", "host", "demo"),
                        List.of("alice", "-oProxyCommand=x", "demo"),
                        List.of("alice\ntouch x", "host", "demo"),
                        List.of("alice", "host", "demo;touch x"))) {
            final List<String> all = new ArrayList<>(arguments);
            all.add(file.toString());
            final Run run = run(all, Map.of());
            assertThat(run.exit()).as(run.output()).isEqualTo(64);
            assertThat(calls).as("ssh was not started").doesNotExist();
        }
    }

    private void assertRefusedBeforeAnythingLeaves(final Run run, final String why) {
        assertThat(run.exit()).as(run.output()).isEqualTo(65);
        assertThat(run.output()).as("it says why").contains(why);
        assertThat(calls).as("ssh was not started").doesNotExist();
    }

    private record Certified(Path key, Path file, String line, String fingerprint) {}

    private int serial;

    private Certified certified(final String... options) throws Exception {
        serial++;
        final Path key = dir.resolve("agent" + serial);
        keygen("-q", "-t", "ed25519", "-N", "", "-C", "", "-f", key.toString());
        return signed(key, options);
    }

    private Certified signed(final Path key, final String... options) throws Exception {
        final List<String> sign =
                new ArrayList<>(List.of("-q", "-s", person.toString(), "-I", "id"));
        sign.addAll(List.of(options));
        sign.add(key + ".pub");
        keygen(sign.toArray(String[]::new));
        final Path file = Path.of(key + "-cert.pub");
        final String[] fields = Files.readString(file).trim().split(" ");
        final String shown = keygen("-lf", key + ".pub");
        return new Certified(key, file, fields[0] + " " + fields[1], shown.split(" ")[1]);
    }

    private Path record(final String fingerprint) {
        return home.resolve(".local/state/porelo/task-certificates")
                .resolve(
                        fingerprint
                                        .substring("SHA256:".length())
                                        .replace('+', '-')
                                        .replace('/', '_')
                                + ".json");
    }

    private List<Path> certificates() throws IOException {
        final Path directory = home.resolve(".local/state/porelo/task-certificates");
        if (!Files.isDirectory(directory)) {
            return List.of();
        }
        try (Stream<Path> found = Files.list(directory)) {
            return found.toList();
        }
    }

    private Run lay(
            final String login, final String host, final String project, final Path... files)
            throws Exception {
        final List<String> arguments = new ArrayList<>(List.of(login, host, project));
        Stream.of(files).map(Path::toString).forEach(arguments::add);
        return run(arguments, Map.of());
    }

    private Run run(final List<String> arguments, final Map<String, String> flags)
            throws Exception {
        final List<String> command = new ArrayList<>(List.of("sh", SCRIPT));
        command.addAll(arguments);
        final ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
        builder.environment().clear();
        builder.environment().put("PATH", dir.resolve("bin") + ":/usr/bin:/bin");
        builder.environment().put("HOME", home.toString());
        builder.environment().put("CALLS", calls.toString());
        builder.environment().put("SENT", dir.resolve("sent").toString());
        builder.environment().putAll(flags);
        final Process process = builder.start();
        final String output =
                new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(process.waitFor(60, TimeUnit.SECONDS)).as("the script ended").isTrue();
        return new Run(process.exitValue(), output);
    }

    private static String keygen(final String... arguments) throws Exception {
        final List<String> command = new ArrayList<>(List.of("ssh-keygen"));
        command.addAll(List.of(arguments));
        final Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        final String output =
                new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(process.waitFor(30, TimeUnit.SECONDS)).as("ssh-keygen ended").isTrue();
        assertThat(process.exitValue()).as(output).isZero();
        return output;
    }

    private static void executable(final Path file, final String text) throws IOException {
        Files.writeString(file, text);
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rwx------"));
    }
}
