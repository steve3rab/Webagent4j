package io.webagent4j.observation;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class CompactTextObservationRendererTest {

    @Test
    void rendersAStableReadableBoundedTree() {
        Observation observation = ObservationTestFixtures.completeObservation();

        assertThat(new CompactTextObservationRenderer().render(observation))
                .isEqualTo(observation.toCompactText())
                .contains("PAGE \"Account\"")
                .contains("[2] HEADING \"Account\" level=1")
                .contains("[4] FORM \"Sign in\" depth-truncated")
                .doesNotContain("user@example.test", "LocatorDefinition");
    }
}
