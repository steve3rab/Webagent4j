package io.webagent4j.observation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.webagent4j.locator.api.ElementRole;
import java.util.Set;
import org.junit.jupiter.api.Test;

class SemanticElementTest {

    @Test
    void keepsAnImmutablePhaseTwoReferenceAndCapabilityCopy() {
        SemanticElement source =
                ObservationTestFixtures.element(1, ElementRole.BUTTON, "Save", "Save");
        SemanticElement copy =
                source.withCapabilities(Set.of(ElementCapability.CLICK, ElementCapability.FOCUS));

        assertThat(copy.name()).isEqualTo("Save");
        assertThat(copy.visible()).isTrue();
        assertThat(copy.enabled()).isTrue();
        assertThat(copy.reference().definition().role()).contains(ElementRole.BUTTON);
        assertThat(copy.capabilities())
                .containsExactlyInAnyOrder(ElementCapability.CLICK, ElementCapability.FOCUS);
        assertThat(source.capabilities()).containsExactly(ElementCapability.CLICK);
    }

    @Test
    void rejectsInvalidLocalIndicesAndHeadingLevels() {
        SemanticElement source =
                ObservationTestFixtures.element(1, ElementRole.HEADING, "Title", "Title");

        assertThatThrownBy(
                        () ->
                                new SemanticElement(
                                        0,
                                        source.id(),
                                        source.stableKey(),
                                        source.role(),
                                        source.name(),
                                        source.text(),
                                        source.tagName(),
                                        source.state(),
                                        source.reference(),
                                        source.attributes(),
                                        source.capabilities(),
                                        source.parentId(),
                                        source.formId(),
                                        source.headingLevel(),
                                        source.fieldType(),
                                        source.sensitive(),
                                        source.value()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
