package io.webagent4j.observation;

import static org.assertj.core.api.Assertions.assertThat;

import io.webagent4j.locator.api.ElementRole;
import org.junit.jupiter.api.Test;

class ObservationFilterTest {

    private final SemanticObservationFilter filter = new SemanticObservationFilter();

    @Test
    void includesOnlyVisibleMeaningfulContentByDefault() {
        assertThat(
                        filter.include(
                                ObservationSnapshotFixtures.element(
                                        "button", null, 0, ElementRole.BUTTON, "button", "Save"),
                                ObservationOptions.defaults()))
                .isTrue();
        assertThat(
                        filter.include(
                                ObservationSnapshotFixtures.builder(
                                                "hidden",
                                                null,
                                                1,
                                                ElementRole.BUTTON,
                                                "button",
                                                "Hidden")
                                        .hidden()
                                        .build(),
                                ObservationOptions.defaults()))
                .isFalse();
        assertThat(
                        filter.include(
                                ObservationSnapshotFixtures.builder(
                                                "hidden",
                                                null,
                                                1,
                                                ElementRole.BUTTON,
                                                "button",
                                                "Hidden")
                                        .hidden()
                                        .build(),
                                ObservationOptions.builder().includeHidden(true).build()))
                .isTrue();
        assertThat(
                        filter.include(
                                ObservationSnapshotFixtures.element(
                                        "decorative", null, 2, ElementRole.IMAGE, "img", ""),
                                ObservationOptions.defaults()))
                .isFalse();
        assertThat(
                        filter.include(
                                ObservationSnapshotFixtures.builder(
                                                "paragraph", null, 3, ElementRole.UNKNOWN, "p", "")
                                        .text("Useful text")
                                        .build(),
                                ObservationOptions.defaults()))
                .isTrue();
        assertThat(
                        filter.include(
                                ObservationSnapshotFixtures.builder(
                                                "div", null, 4, ElementRole.UNKNOWN, "div", "")
                                        .text("Incidental text")
                                        .build(),
                                ObservationOptions.defaults()))
                .isFalse();
    }
}
