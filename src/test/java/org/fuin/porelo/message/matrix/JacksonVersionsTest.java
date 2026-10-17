package org.fuin.porelo.message.matrix;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Jackson's parts are released together and work only as one set. A BOM the parent imports can move
 * the parts this repository does not name - core and annotations - away from the databind it does,
 * and the build stays green while the executable carries a mixed set.
 */
class JacksonVersionsTest {

    @Test
    @DisplayName(
            "Jackson's core and annotations are the release of the databind the transport names")
    void jacksonIsOneSet() {
        final String databind =
                com.fasterxml.jackson.databind.cfg.PackageVersion.VERSION.toString();
        final String core = com.fasterxml.jackson.core.json.PackageVersion.VERSION.toString();
        final com.fasterxml.jackson.core.Version databindVersion =
                com.fasterxml.jackson.databind.cfg.PackageVersion.VERSION;
        final String release =
                databindVersion.getMajorVersion() + "." + databindVersion.getMinorVersion();

        assertThat(core)
                .as("jackson-core beside jackson-databind %s", databind)
                .isEqualTo(databind);
        assertThat(annotationsVersion())
                .as("jackson-annotations beside jackson-databind %s", databind)
                .startsWith(release);
    }

    /** jackson-annotations carries no version class; its jar's manifest names its release. */
    private static String annotationsVersion() {
        final String version =
                com.fasterxml.jackson.annotation.JsonProperty.class
                        .getPackage()
                        .getImplementationVersion();
        assertThat(version).as("the version in jackson-annotations' manifest").isNotNull();
        return version;
    }
}
