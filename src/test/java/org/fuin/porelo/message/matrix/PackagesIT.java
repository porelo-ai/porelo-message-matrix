package org.fuin.porelo.message.matrix;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What the packages declare, read from the packages the build made. A machine's setup offers a package by
 * what it provides, so a package that provides the wrong thing is one a new person cannot choose.
 */
class PackagesIT {

    private static final Path TARGET = Path.of("target");

    /** The newest package whose file name starts so - the build may leave older ones beside it. */
    private static Path newest(final Path dir, final String prefix, final String suffix) throws IOException {
        try (Stream<Path> files = Files.walk(dir)) {
            return files.filter(f -> f.getFileName().toString().startsWith(prefix) && f.toString().endsWith(suffix))
                    .max(Comparator.comparing(f -> f.toFile().lastModified())).orElseThrow(
                            () -> new IllegalStateException("no " + prefix + "*" + suffix + " under " + dir));
        }
    }

    private static String run(final String... command) throws IOException, InterruptedException {
        final Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        final String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(process.waitFor()).as("%s: %s", List.of(command), output).isZero();
        return output;
    }

    @Test
    @DisplayName("The .debs provide what setup offers them by, and the transport brings the filter, never the homeserver")
    void debsProvide() throws Exception {
        assumeTrue(Transport.isNative(), "only a native build makes the packages");
        final Path transport = newest(TARGET, "porelo-message-transport-matrix_", ".deb");
        final Path homeserver = newest(TARGET, "porelo-matrix-homeserver_", ".deb");

        assertThat(run("dpkg-deb", "-f", transport.toString(), "Provides").strip()).isEqualTo("porelo-transport");
        assertThat(run("dpkg-deb", "-f", homeserver.toString(), "Provides").strip()).isEqualTo("porelo-homeserver");
        // Chosen, never pulled in: a machine on a central homeserver must not get a local one.
        assertThat(run("dpkg-deb", "-f", transport.toString(), "Depends", "Recommends"))
                .doesNotContain("porelo-matrix-homeserver");
        // Porelo sends nothing without the filter: whoever installs a transport gets it, however they install.
        assertThat(run("dpkg-deb", "-f", transport.toString(), "Depends")).contains("porelo-message-sluice-filter");
    }

    @Test
    @DisplayName("The .rpms provide the same")
    void rpmsProvide() throws Exception {
        assumeTrue(Transport.isNative(), "only a native build makes the packages");
        final Path rpms = TARGET.resolve("rpm");
        final Path transport = newest(rpms, "porelo-message-transport-matrix-", ".rpm");
        final Path homeserver = newest(rpms, "porelo-matrix-homeserver-", ".rpm");

        assertThat(run("rpm", "-qp", "--provides", transport.toString())).contains("porelo-transport");
        assertThat(run("rpm", "-qp", "--provides", homeserver.toString())).contains("porelo-homeserver");
        assertThat(run("rpm", "-qp", "--requires", transport.toString())).doesNotContain("porelo-matrix-homeserver")
                .contains("porelo-message-sluice-filter");
    }

    @Test
    @DisplayName("Every package built carries the project's version as the packaging maps it, read from the package")
    void packagesCarryTheProjectsVersion() throws Exception {
        assumeTrue(Transport.isNative(), "only a native build makes the packages");
        final String project = javax.xml.xpath.XPathFactory.newInstance().newXPath().evaluate("/project/version",
                javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(
                        Path.of("pom.xml").toFile()));
        final boolean snapshot = project.endsWith("-SNAPSHOT");
        final String version = project.replaceFirst("-SNAPSHOT$", "");
        // Worked out here from the raw parts, never read back from deb.version or rpm.release, so a mistake in
        // the pom's mapping is not the answer it is held to.
        final String run = System.getProperty("porelo.snapshot.run");
        final String suffix = System.getProperty("porelo.snapshot.suffix", "");
        assertThat(run).as("the run number the build passes to its integration tests").isNotNull();
        final String deb = snapshot ? version + "~snapshot." + run + suffix : version;
        final String rpm = version + " " + (snapshot ? "snapshot." + run : "1");
        final Path rpms = TARGET.resolve("rpm");

        // A plugin may take its version from a property the parent sets for something else; only the built
        // package shows which one it took.
        for (final String name : List.of("porelo-message-transport-matrix_", "porelo-matrix-homeserver_")) {
            assertThat(run("dpkg-deb", "-f", newest(TARGET, name, ".deb").toString(), "Version").strip())
                    .as("the version of %s*.deb", name).isEqualTo(deb);
        }
        for (final String name : List.of("porelo-message-transport-matrix-", "porelo-matrix-homeserver-")) {
            // rpm may warn on its own lines first, about a database a query of a package file does not need.
            final String said = run("rpm", "-qp", "--queryformat", "\n%{VERSION} %{RELEASE}",
                    newest(rpms, name, ".rpm").toString());
            assertThat(said.substring(said.lastIndexOf('\n') + 1).strip()).as("the version and release of %s*.rpm", name)
                    .isEqualTo(rpm);
        }
    }

    @Test
    @DisplayName("Both packages name the newest glibc and zlib the executable needs, measured from it with objdump -T")
    void floorsMatchTheExecutable() throws Exception {
        assumeTrue(Transport.isNative(), "only a native build makes the packages");
        final String symbols = run("objdump", "-T", TARGET.resolve("porelo-message-transport-matrix").toString());
        final String glibc = newestVersion(symbols, "GLIBC_");
        final String zlib = newestVersion(symbols, "ZLIB_");
        final Path deb = newest(TARGET, "porelo-message-transport-matrix_", ".deb");
        final Path rpm = newest(TARGET.resolve("rpm"), "porelo-message-transport-matrix-", ".rpm");

        assertThat(run("dpkg-deb", "-f", deb.toString(), "Depends"))
                .contains("libc6 (>= " + glibc + ")", "zlib1g (>= 1:" + zlib + ")");
        assertThat(run("rpm", "-qp", "--requires", rpm.toString()))
                .contains("libc.so.6(GLIBC_" + glibc + ")(64bit)", "libz.so.1(ZLIB_" + zlib + ")(64bit)");
    }

    /** The highest version of a symbol prefix in objdump's output, compared number by number. */
    private static String newestVersion(final String symbols, final String prefix) {
        final java.util.regex.Matcher m = java.util.regex.Pattern.compile(prefix + "([0-9]+(?:\\.[0-9]+)*)").matcher(symbols);
        String newest = null;
        while (m.find()) {
            if (newest == null || compare(m.group(1), newest) > 0) {
                newest = m.group(1);
            }
        }
        assertThat(newest).as("the executable needs some %s symbol", prefix).isNotNull();
        return newest;
    }

    private static int compare(final String a, final String b) {
        final String[] x = a.split("\\.");
        final String[] y = b.split("\\.");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            final int d = Integer.compare(i < x.length ? Integer.parseInt(x[i]) : 0, i < y.length ? Integer.parseInt(y[i]) : 0);
            if (d != 0) {
                return d;
            }
        }
        return 0;
    }

}
