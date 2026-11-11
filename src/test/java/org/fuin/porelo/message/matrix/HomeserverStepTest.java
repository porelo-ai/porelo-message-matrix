package org.fuin.porelo.message.matrix;

import static org.assertj.core.api.Assertions.assertThat;
import static org.fuin.porelo.message.matrix.WorkflowSteps.step;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The Build keeps the homeserver's pinned image as fetched, in a cache keyed by its digest, and on
 * a runner without podman takes the binary out of it; every integration test then runs what this
 * step left. The step's own script runs here against a stand-in for {@code ghcr.io}: a {@code curl}
 * on the path serving files by the last part of the URL. Tagged, since a change to the workflows
 * alone starts no Build.
 */
@Tag("documents")
class HomeserverStepTest {

    private static final Path BUILD = Path.of(".github", "workflows", "build.yml");
    private static final String PIN = "The homeserver's pinned image";
    private static final String STEP = "The homeserver's image, from its cache or ghcr.io";
    private static final String GZIP = "application/vnd.oci.image.layer.v1.tar+gzip";
    private static final String OLD = "#!/bin/sh\necho tuwunel old\n";
    private static final String NEW = "#!/bin/sh\necho tuwunel new\n";
    private static final String GOOD_TOKEN = "djE6c3RhbmQtaW4=";

    @TempDir Path directory;

    private Path served;
    private Path work;
    private Path bin;
    private Path temp;
    private Path githubEnv;
    private Path requests;
    private Path configs;
    private Path home;
    private String ref;

    @BeforeEach
    void setUp() throws IOException {
        served = Files.createDirectories(directory.resolve("served"));
        work = Files.createDirectories(directory.resolve("work"));
        bin = Files.createDirectories(directory.resolve("bin"));
        temp = Files.createDirectories(directory.resolve("runner-temp"));
        githubEnv = directory.resolve("github-env");
        requests = directory.resolve("requests");
        configs = directory.resolve("configs");
        home = Files.createDirectories(directory.resolve("home"));
        Files.createDirectories(work.resolve("homeserver"));
        // Only the tools the step names, so a podman installed here is not found.
        for (final String tool :
                List.of(
                        "bash",
                        "sed",
                        "jq",
                        "tar",
                        "gzip",
                        "sha256sum",
                        "grep",
                        "wc",
                        "mkdir",
                        "rm",
                        "mv",
                        "stat",
                        "find",
                        "xargs",
                        "cat",
                        "env")) {
            Files.createSymbolicLink(bin.resolve(tool), Path.of("/usr/bin", tool));
        }
        final Path curl = bin.resolve("curl");
        Files.writeString(
                curl,
                """
                #!/bin/bash
                out=; url=
                while [ $# -gt 0 ]; do
                    case "$1" in
                        -o) out=$2; shift ;;
                        -K) cat >> "$CONFIGS"; echo '--' >> "$CONFIGS"; shift ;;
                        -H) shift ;;
                        -*) ;;
                        *) url=$1 ;;
                    esac
                    shift
                done
                echo "$url" >> "$REQUESTS"
                case "$url" in
                    https://ghcr.io/token*) printf '{"token":%s}\\n' "$(cat "$SERVED/token")"; exit 0 ;;
                esac
                file="$SERVED/${url##*/}"
                [ -f "$file" ] || exit 22
                cat "$file" > "$out"
                """);
        Files.setPosixFilePermissions(curl, PosixFilePermissions.fromString("rwx------"));
        Files.writeString(served.resolve("token"), "\"" + GOOD_TOKEN + "\"");
    }

    // Prevents the integration tests from running a binary that is not the pinned image's.
    @Test
    @DisplayName("A pinned image's binary is taken and named to the tests")
    void thePinnedImagesBinaryIsTaken() throws Exception {
        pin(image(layer(entry("usr/bin/tuwunel", NEW))));

        final Run run = run();

        assertThat(run.exit()).as("the step: %s", run.log()).isZero();
        assertThat(Files.readString(temp.resolve("tuwunel/usr/bin/tuwunel")))
                .as("the binary the tests are given")
                .isEqualTo(NEW);
        assertThat(Files.readString(githubEnv))
                .as("what the step hands the next steps")
                .isEqualTo("MATRIX_TEST_TUWUNEL=" + temp.resolve("tuwunel/usr/bin/tuwunel") + "\n");
    }

    // Prevents an earlier layer's binary from standing in for the one the image really holds.
    @Test
    @DisplayName("A later layer's binary replaces an earlier one's, however long its listing")
    void aLaterLayersBinaryReplacesAnEarlierOnes() throws Exception {
        final List<byte[]> later = new ArrayList<>();
        later.add(entry("usr/bin/tuwunel", NEW));
        // Far more than a pipe holds after the binary's name, so a reader stopping early is seen.
        for (int i = 0; i < 3000; i++) {
            later.add(entry("usr/share/filler/" + "f".repeat(60) + i, ""));
        }
        pin(image(layer(entry("usr/bin/tuwunel", OLD)), layer(later.toArray(byte[][]::new))));

        final Run run = run();

        assertThat(run.exit()).as("the step: %s", run.log()).isZero();
        assertThat(Files.readString(temp.resolve("tuwunel/usr/bin/tuwunel")))
                .as("the binary the image's last layer holds")
                .isEqualTo(NEW);
    }

    // Prevents a layer that cannot be read from being taken as one that holds no binary.
    @Test
    @DisplayName("A layer that cannot be listed stops the step")
    void aLayerThatCannotBeListedStopsTheStep() throws Exception {
        pin(image(layer(entry("usr/bin/tuwunel", OLD)), serve("not a gzip".getBytes())));

        final Run run = run();

        assertThat(run.exit()).as("the step: %s", run.log()).isNotZero();
        assertThat(githubEnv).as("no binary named to the tests").doesNotExist();
    }

    // Prevents bytes other than the pinned ones from reaching the tests.
    @Test
    @DisplayName("A layer whose bytes are not its digest stops the step")
    void aLayerNotItsDigestStopsTheStep() throws Exception {
        final String layer = layer(entry("usr/bin/tuwunel", NEW));
        pin(image(layer));
        Files.writeString(served.resolve(layer), "other bytes");

        final Run run = run();

        assertThat(run.exit()).as("the step: %s", run.log()).isNotZero();
        assertThat(run.log()).as("the refusal names the blob").contains("::error::blobs " + layer);
        assertThat(githubEnv).as("no binary named to the tests").doesNotExist();
    }

    // Prevents an answer of the registry's from adding lines to curl's configuration.
    @Test
    @DisplayName("A token that is not a plain token stops the step before it is sent")
    void aTokenThatIsNotPlainStopsTheStep() throws Exception {
        pin(image(layer(entry("usr/bin/tuwunel", NEW))));
        Files.writeString(served.resolve("token"), "\"x\\\"\\nlocation-trusted\\n\"");

        final Run run = run();

        assertThat(run.exit()).as("the step: %s", run.log()).isNotZero();
        assertThat(configs).as("no configuration handed to curl").doesNotExist();
    }

    // Prevents a name from the registry's answer from steering a request off the image.
    @Test
    @DisplayName("A digest in the registry's answer that is not a sha256 stops the step")
    void aDigestNotASha256StopsTheStep() throws Exception {
        final String index =
                serve(
                        ("{\"manifests\":[{\"digest\":\"../../other\",\"platform\":"
                                        + "{\"os\":\"linux\",\"architecture\":\"amd64\"}}]}")
                                .getBytes(StandardCharsets.UTF_8));
        pin(index);

        final Run run = run();

        assertThat(run.exit()).as("the step: %s", run.log()).isNotZero();
        assertThat(Files.readAllLines(requests))
                .as("the requests made")
                .noneMatch(r -> r.contains("other"));
    }

    // Prevents a registry that is down from failing a Build whose pinned image was fetched before.
    @Test
    @DisplayName(
            "A second run takes the image from the cache, asks no registry, and keeps only its files")
    void aSecondRunAsksNoRegistry() throws Exception {
        pin(image(layer(entry("usr/bin/tuwunel", NEW))));
        assertThat(run().exit()).as("the first run").isZero();
        final Path blobs = home.resolve(".cache/porelo-pinned/tuwunel/blobs/sha256");
        Files.writeString(blobs.resolve("stray"), "an earlier pin's file");
        Files.delete(requests);
        Files.delete(githubEnv);

        final Run run = run();

        assertThat(run.exit()).as("the step: %s", run.log()).isZero();
        assertThat(requests).as("no request to the registry").doesNotExist();
        assertThat(Files.readString(temp.resolve("tuwunel/usr/bin/tuwunel")))
                .as("the binary the tests are given")
                .isEqualTo(NEW);
        assertThat(blobs.resolve("stray")).as("a file the pin does not name").doesNotExist();
        assertThat(home.resolve(".cache/porelo-pinned/tuwunel/index.json"))
                .as("the layout's index")
                .content()
                .contains(ref.substring(ref.indexOf('@') + 1));
    }

    // Prevents a cached file that is not its digest from reaching the tests.
    @Test
    @DisplayName("A cached file whose bytes are not its digest is fetched again")
    void aDamagedCachedFileIsFetchedAgain() throws Exception {
        final String layer = layer(entry("usr/bin/tuwunel", NEW));
        pin(image(layer));
        assertThat(run().exit()).as("the first run").isZero();
        Files.writeString(
                home.resolve(".cache/porelo-pinned/tuwunel/blobs/sha256")
                        .resolve(layer.substring("sha256:".length())),
                "other bytes");
        Files.delete(requests);

        final Run run = run();

        assertThat(run.exit()).as("the step: %s", run.log()).isZero();
        assertThat(Files.readAllLines(requests))
                .as("the requests made")
                .anyMatch(r -> r.endsWith("/blobs/" + layer));
        assertThat(Files.readString(temp.resolve("tuwunel/usr/bin/tuwunel")))
                .as("the binary the tests are given")
                .isEqualTo(NEW);
    }

    // Prevents a cache keyed by anything but the pinned digest, or an image pinned by no digest.
    @Test
    @DisplayName(
            "The pin names the Dockerfile's reference and its digest, and a FROM with no digest stops it")
    void thePinNamesTheReferenceAndItsDigest() throws Exception {
        final String index = image(layer(entry("usr/bin/tuwunel", NEW)));
        pin(index);
        final Path output = directory.resolve("github-output");

        assertThat(run(PIN, output).exit()).as("the pin").isZero();
        assertThat(Files.readAllLines(output))
                .as("what the pin hands the cache and the step")
                .containsExactly("ref=" + ref, "sha256=" + index.substring("sha256:".length()));

        Files.writeString(
                work.resolve("homeserver").resolve("Dockerfile"),
                "FROM ghcr.io/example/tuwunel:latest\n");
        final Run unpinned = run(PIN, output);
        assertThat(unpinned.exit()).as("the pin of an image with no digest").isNotZero();
        assertThat(unpinned.log()).contains("pins no ghcr.io image by digest");
    }

    private record Run(int exit, String log) {}

    private Run run() throws IOException, InterruptedException {
        return run(STEP, directory.resolve("github-output"));
    }

    private Run run(final String name, final Path githubOutput)
            throws IOException, InterruptedException {
        final Path output = directory.resolve("output");
        final ProcessBuilder command =
                new ProcessBuilder(
                                bin.resolve("bash").toString(),
                                "--noprofile",
                                "--norc",
                                "-e",
                                "-o",
                                "pipefail",
                                "-c",
                                step(BUILD, name).run())
                        .directory(work.toFile())
                        .redirectErrorStream(true)
                        .redirectOutput(output.toFile());
        command.environment().clear();
        command.environment().put("PATH", bin.toString());
        command.environment().put("RUNNER_TEMP", temp.toString());
        command.environment().put("GITHUB_ENV", githubEnv.toString());
        command.environment().put("GITHUB_OUTPUT", githubOutput.toString());
        command.environment().put("HOME", home.toString());
        command.environment().put("REF", ref);
        command.environment().put("SERVED", served.toString());
        command.environment().put("REQUESTS", requests.toString());
        command.environment().put("CONFIGS", configs.toString());
        final Process process = command.start();
        try {
            assertThat(process.waitFor(60, TimeUnit.SECONDS)).as("the step ends").isTrue();
            return new Run(process.exitValue(), Files.readString(output));
        } finally {
            process.destroyForcibly();
        }
    }

    /** Pins the index by its digest, as homeserver/Dockerfile does. */
    private void pin(final String index) throws IOException {
        ref = "ghcr.io/example/tuwunel:latest@" + index;
        Files.writeString(work.resolve("homeserver").resolve("Dockerfile"), "FROM " + ref + "\n");
    }

    /** Serves an index naming one linux/amd64 image of these layers; returns the index's digest. */
    private String image(final String... layers) throws Exception {
        final StringBuilder list = new StringBuilder();
        for (final String layer : layers) {
            list.append(list.isEmpty() ? "" : ",")
                    .append("{\"mediaType\":\"")
                    .append(GZIP)
                    .append("\",\"digest\":\"")
                    .append(layer)
                    .append("\"}");
        }
        final String config = serve("{}".getBytes(StandardCharsets.UTF_8));
        final String manifest =
                serve(
                        ("{\"config\":{\"digest\":\"" + config + "\"},\"layers\":[" + list + "]}")
                                .getBytes(StandardCharsets.UTF_8));
        return serve(
                ("{\"manifests\":[{\"digest\":\"arm\",\"platform\":{\"os\":\"linux\","
                                + "\"architecture\":\"arm64\"}},{\"digest\":\""
                                + manifest
                                + "\",\"platform\":{\"os\":\"linux\",\"architecture\":\"amd64\"}}]}")
                        .getBytes(StandardCharsets.UTF_8));
    }

    /** Serves a gzipped tar of these entries; returns its digest. */
    private String layer(final byte[]... entries) throws Exception {
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(bytes)) {
            for (final byte[] entry : entries) {
                gzip.write(entry);
            }
            gzip.write(new byte[1024]);
        }
        return serve(bytes.toByteArray());
    }

    private String serve(final byte[] content) throws IOException, NoSuchAlgorithmException {
        final String digest =
                "sha256:"
                        + HexFormat.of()
                                .formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        Files.write(served.resolve(digest), content);
        return digest;
    }

    /** One ustar entry of an executable file. */
    private static byte[] entry(final String name, final String content) {
        final byte[] data = content.getBytes(StandardCharsets.UTF_8);
        final byte[] header = new byte[512];
        put(header, 0, name);
        put(header, 100, "0000755");
        put(header, 108, "0000000");
        put(header, 116, "0000000");
        put(header, 124, String.format("%011o", data.length));
        put(header, 136, "00000000000");
        java.util.Arrays.fill(header, 148, 156, (byte) ' ');
        header[156] = '0';
        put(header, 257, "ustar");
        put(header, 263, "00");
        int sum = 0;
        for (final byte b : header) {
            sum += b & 0xff;
        }
        put(header, 148, String.format("%06o", sum));
        header[155] = ' ';
        final int padded = (data.length + 511) / 512 * 512;
        final byte[] entry = new byte[512 + padded];
        System.arraycopy(header, 0, entry, 0, 512);
        System.arraycopy(data, 0, entry, 512, data.length);
        return entry;
    }

    private static void put(final byte[] header, final int offset, final String value) {
        final byte[] bytes = value.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(bytes, 0, header, offset, bytes.length);
    }
}
