package io.webagent4j.observation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.webagent4j.locator.api.ElementRole;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ObservationDiffTest {

    @Test
    void findsAddedRemovedChangedMetadataAndDialogChanges() {
        Observation before = ObservationTestFixtures.completeObservation();
        List<SemanticElement> changedElements = new ArrayList<>(before.elements());
        SemanticElement oldField = changedElements.get(4);
        SemanticElement changedField =
                new SemanticElement(
                        oldField.index(),
                        oldField.id(),
                        oldField.stableKey(),
                        oldField.role(),
                        "Work email",
                        "Updated",
                        oldField.tagName(),
                        new ObservedElementState(
                                oldField.state().interaction(), false, Optional.of(true)),
                        oldField.reference(),
                        oldField.attributes(),
                        java.util.Set.of(ElementCapability.FOCUS),
                        oldField.parentId(),
                        Optional.empty(),
                        oldField.headingLevel(),
                        oldField.fieldType(),
                        false,
                        ObservedValue.omitted(true));
        changedElements.set(4, changedField);
        changedElements.remove(2);
        changedElements.add(
                ObservationTestFixtures.element(17, ElementRole.BUTTON, "New action", "New"));
        Observation after =
                copy(
                        before,
                        "https://example.test/account?updated=true",
                        "Updated account",
                        changedElements,
                        List.of());

        ObservationDiff diff = before.diff(after);

        assertThat(diff.empty()).isFalse();
        assertThat(diff.urlChanged()).isTrue();
        assertThat(diff.titleChanged()).isTrue();
        assertThat(diff.elementsAdded()).extracting(SemanticElement::name).contains("New action");
        assertThat(diff.elementsRemoved()).extracting(SemanticElement::name).contains("Help");
        assertThat(diff.elementsChanged()).hasSize(1);
        assertThat(diff.elementsChanged().getFirst().changedProperties())
                .contains(
                        ChangedProperty.ACCESSIBLE_NAME,
                        ChangedProperty.TEXT,
                        ChangedProperty.EXPANDED,
                        ChangedProperty.VALUE,
                        ChangedProperty.CAPABILITIES,
                        ChangedProperty.FORM_RELATIONSHIP);
        assertThat(diff.dialogsClosed()).hasSize(1);
        assertThat(diff.dialogsOpened()).isEmpty();
    }

    @Test
    void fingerprintsIgnoreVolatileCaptureFieldsButTrackSemanticChanges() {
        Observation first = ObservationTestFixtures.completeObservation();
        PageMetadata laterMetadata =
                new PageMetadata(
                        first.url(),
                        first.title(),
                        first.metadata().language(),
                        first.metadata().charset(),
                        first.metadata().readyState(),
                        first.metadata().capturedAt().plusSeconds(90),
                        first.metadata().viewport(),
                        first.metadata().canonicalUrl(),
                        first.metadata().description());
        ObservationFingerprint later =
                ObservationFingerprint.compute(
                        laterMetadata, first.elements(), first.relationships());
        List<SemanticElement> changed = new ArrayList<>(first.elements());
        changed.set(
                1,
                ObservationTestFixtures.element(
                        2, ElementRole.HEADING, "Different heading", "Different heading"));

        assertThat(later).isEqualTo(first.fingerprint());
        assertThat(ObservationFingerprint.compute(laterMetadata, changed, first.relationships()))
                .isNotEqualTo(first.fingerprint());
        assertThat(first.diff(first).empty()).isTrue();
        assertThatThrownBy(() -> new ObservationFingerprint("not-a-hash"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                new ChangedSemanticElement(
                                        first.element(1),
                                        first.element(1),
                                        EnumSet.noneOf(ChangedProperty.class)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static Observation copy(
            Observation source,
            String url,
            String title,
            List<SemanticElement> elements,
            List<DialogObservation> dialogs) {
        PageMetadata metadata = ObservationTestFixtures.metadata(url, title);
        return new Observation(
                new ObservationId("observation-2"),
                metadata,
                elements,
                source.landmarks(),
                source.forms(),
                source.navigations(),
                source.tables(),
                source.lists(),
                source.images(),
                dialogs,
                source.alerts(),
                source.tabLists(),
                source.menus(),
                source.relationships(),
                source.tree(),
                source.content(),
                source.statistics(),
                source.warnings(),
                ObservationFingerprint.compute(metadata, elements, source.relationships()));
    }
}
