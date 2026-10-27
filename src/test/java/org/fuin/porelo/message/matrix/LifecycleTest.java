package org.fuin.porelo.message.matrix;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.InputStream;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Guards the token join's command shape before any credentials or network are needed. */
class LifecycleTest {

    @Test
    void tokenAndDisplayReachConfigurationValidation() {
        assertThatThrownBy(
                        () ->
                                Lifecycle.run(
                                        "join",
                                        List.of(
                                                "--project",
                                                "demo",
                                                "--person",
                                                "core",
                                                "--token",
                                                "--display",
                                                "Agent Core"),
                                        Map.of(),
                                        InputStream.nullInputStream(),
                                        System.out,
                                        System.err,
                                        new PodmanUnit(Map.of())))
                .as(
                        "valid token join options reach configuration, rather than unknown-option refusal")
                .isInstanceOfSatisfying(
                        Failure.class,
                        failure ->
                                org.assertj.core.api.Assertions.assertThat(failure.exitCode())
                                        .as("configuration is missing")
                                        .isEqualTo(Exit.CONFIG));
    }

    @Test
    void joinDisplayHasTheSameValidationAsEnroll() {
        assertThatThrownBy(
                        () ->
                                Lifecycle.run(
                                        "join",
                                        List.of(
                                                "--project",
                                                "demo",
                                                "--person",
                                                "core",
                                                "--token",
                                                "--display",
                                                "Agent: Core"),
                                        Map.of(),
                                        InputStream.nullInputStream(),
                                        System.out,
                                        System.err,
                                        new PodmanUnit(Map.of())))
                .as("display validation runs before any state changes")
                .isInstanceOf(Failure.class)
                .hasMessageContaining("--display must be");
    }
}
