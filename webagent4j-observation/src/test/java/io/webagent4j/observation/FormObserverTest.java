package io.webagent4j.observation;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class FormObserverTest {

    @Test
    void retainsFieldAndSubmitOwnershipWithSafeValues() {
        Observation observation = ObserverTestSupport.observeRich();

        assertThat(observation.forms())
                .singleElement()
                .satisfies(
                        form -> {
                            assertThat(form.fields()).hasSize(3);
                            assertThat(form.actions())
                                    .extracting(SemanticElement::name)
                                    .contains("Continue");
                            assertThat(form.fields())
                                    .filteredOn(FormFieldObservation::sensitive)
                                    .allSatisfy(
                                            field -> assertThat(field.value().redacted()).isTrue());
                        });
        assertThat(observation.relationships())
                .extracting(SemanticRelationship::type)
                .contains(SemanticRelationshipType.BELONGS_TO, SemanticRelationshipType.SUBMITS);
    }
}
