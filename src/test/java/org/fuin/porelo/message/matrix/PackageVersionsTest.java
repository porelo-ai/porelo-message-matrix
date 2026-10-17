package org.fuin.porelo.message.matrix;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathFactory;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.NodeList;

/**
 * The version is written by hand in more than one place of the pom: the project's own, the prefix of the
 * {@code .deb}s' version and the version of each {@code .rpm}. One left behind when the version moves would
 * ship a package whose version is not the release's, with the build green.
 */
class PackageVersionsTest {

    private static final Path POM = Path.of("pom.xml");

    @Test
    @DisplayName("Every package carries the project's version")
    void everyPackageCarriesTheProjectsVersion() throws Exception {
        final Document pom = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(POM.toFile());
        final XPath xpath = XPathFactory.newInstance().newXPath();
        final String version = xpath.evaluate("/project/version", pom).replaceFirst("-SNAPSHOT$", "");
        final String deb = xpath.evaluate("/project/properties/deb.version", pom);
        final NodeList rpms = (NodeList) xpath.evaluate(
                "//plugin[artifactId='rpm-maven-plugin']/executions/execution/configuration/version", pom,
                XPathConstants.NODESET);
        final List<String> rpmVersions = new ArrayList<>();
        for (int i = 0; i < rpms.getLength(); i++) {
            rpmVersions.add(rpms.item(i).getTextContent().trim());
        }

        assertThat(version).as("the project's version").matches("[0-9]+\\.[0-9]+\\.[0-9]+");
        // A snapshot's packages are version~snapshot.<run><suffix>; on a release tag the build writes the
        // version itself into deb.version, and the packages carry nothing else.
        assertThat(deb).as("the .debs' version").matches(java.util.regex.Pattern.quote(version) + "(~snapshot\\..*)?");
        // The transport's and the homeserver's: a guard that found none would pass forever.
        assertThat(rpmVersions).as("the .rpms' versions").hasSize(2).containsOnly(version);
    }

}
