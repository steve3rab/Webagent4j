package io.webagent4j.observation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.webagent4j.observation.internal.ObservationBuilder;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ObservationBuilderTest {

    @Test
    void requiresDiagnosticsAndComputesTheFingerprint() {
        PageMetadata metadata =
                new PageMetadata(
                        "https://example.test",
                        "Example",
                        Optional.empty(),
                        Optional.empty(),
                        "complete",
                        Instant.EPOCH,
                        new ViewportSize(800, 600),
                        Optional.empty(),
                        Optional.empty());
        ObservationBuilder builder =
                new ObservationBuilder(new ObservationId("builder-test"), metadata);

        assertThatThrownBy(builder::build).isInstanceOf(IllegalStateException.class);

        Observation observation =
                builder.diagnostics(
                                new ObservationStatistics(
                                        0, 0, 0, 0, 0, 0, 0, 0, Duration.ZERO, List.of()),
                                List.of())
                        .build();
        assertThat(observation.elements()).isEmpty();
        assertThat(observation.fingerprint().value()).hasSize(64);
    }
}
