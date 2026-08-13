package io.webagent4j.observation;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class ObservationFingerprintTest {

    @Test
    void ignoresObservationIdentityTimestampAndDuration() {
        Observation observation = ObservationTestFixtures.completeObservation();
        PageMetadata later =
                new PageMetadata(
                        observation.url(),
                        observation.title(),
                        observation.metadata().language(),
                        observation.metadata().charset(),
                        observation.metadata().readyState(),
                        Instant.parse("2030-01-01T00:00:00Z"),
                        observation.metadata().viewport(),
                        observation.metadata().canonicalUrl(),
                        observation.metadata().description());

        assertThat(
                        ObservationFingerprint.compute(
                                later, observation.elements(), observation.relationships()))
                .isEqualTo(observation.fingerprint());
        assertThat(observation.fingerprint().value()).hasSize(64).matches("[0-9a-f]+");
    }

    @Test
    void excludesBackendDerivedSemanticIdsFromRelationshipFingerprinting() {
        PageMetadata metadata = ObservationTestFixtures.metadata("https://example.test", "Example");
        SemanticElement firstSource =
                ObservationTestFixtures.element(
                        1, io.webagent4j.locator.api.ElementRole.FORM, "Form", "");
        SemanticElement firstTarget =
                ObservationTestFixtures.element(
                        2, io.webagent4j.locator.api.ElementRole.BUTTON, "Submit", "Submit");
        SemanticElement secondSource = withId(firstSource, "different-source");
        SemanticElement secondTarget = withId(firstTarget, "different-target");

        ObservationFingerprint first =
                ObservationFingerprint.compute(
                        metadata,
                        List.of(firstSource, firstTarget),
                        List.of(
                                new SemanticRelationship(
                                        firstSource.id(),
                                        firstTarget.id(),
                                        SemanticRelationshipType.SUBMITS)));
        ObservationFingerprint second =
                ObservationFingerprint.compute(
                        metadata,
                        List.of(secondSource, secondTarget),
                        List.of(
                                new SemanticRelationship(
                                        secondSource.id(),
                                        secondTarget.id(),
                                        SemanticRelationshipType.SUBMITS)));

        assertThat(second).isEqualTo(first);
    }

    private static SemanticElement withId(SemanticElement source, String id) {
        return new SemanticElement(
                source.index(),
                new SemanticElementId(id),
                source.stableKey(),
                source.role(),
                source.accessibleName(),
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
                source.value());
    }
}
