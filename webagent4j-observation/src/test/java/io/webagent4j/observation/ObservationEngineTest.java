package io.webagent4j.observation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.webagent4j.browser.IPage;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ObservationEngineTest {

    private static final Instant NOW = Instant.parse("2026-08-13T10:15:30Z");

    @Test
    void buildsTheCompleteSemanticModelFromOneBatchSnapshot() {
        IPage page = mock(IPage.class);
        when(page.captureObservation(any())).thenReturn(ObservationSnapshotFixtures.richSnapshot());
        List<IObservationEvent> events = new ArrayList<>();
        ObservationEngine engine = engine(events);
        ObservationOptions options =
                ObservationOptions.builder()
                        .includeInputValues(true)
                        .maxElements(100)
                        .maxDepth(2)
                        .build();

        Observation observation = engine.observe(page, options);

        assertThat(observation.id().value()).isEqualTo("test-observation");
        assertThat(observation.metadata().capturedAt()).isEqualTo(NOW);
        assertThat(observation.headings())
                .extracting(HeadingObservation::level)
                .containsExactly(1, 3);
        assertThat(observation.landmarks()).hasSize(3);
        assertThat(observation.navigations())
                .singleElement()
                .satisfies(
                        navigation -> {
                            assertThat(navigation.links())
                                    .extracting(SemanticElement::name)
                                    .containsExactly("Help");
                            assertThat(navigation.currentItem()).isPresent();
                            assertThat(navigation.orientation())
                                    .isEqualTo(NavigationOrientation.HORIZONTAL);
                        });
        assertThat(observation.forms())
                .singleElement()
                .satisfies(
                        form -> {
                            assertThat(form.method()).isEqualTo("POST");
                            assertThat(form.fields()).hasSize(3);
                            assertThat(form.actions())
                                    .extracting(SemanticElement::name)
                                    .containsExactly("Continue");
                            assertThat(form.fields())
                                    .filteredOn(field -> field.type() == InputFieldType.EMAIL)
                                    .extracting(field -> field.value().value().orElseThrow())
                                    .containsExactly("user@example.test");
                            assertThat(form.fields())
                                    .filteredOn(FormFieldObservation::sensitive)
                                    .allSatisfy(
                                            field -> assertThat(field.value().redacted()).isTrue());
                        });
        assertThat(observation.tables())
                .singleElement()
                .satisfies(
                        table -> {
                            assertThat(table.rowsTruncated()).isTrue();
                            assertThat(table.columnsTruncated()).isTrue();
                        });
        assertThat(observation.lists())
                .singleElement()
                .satisfies(list -> assertThat(list.truncated()).isTrue());
        assertThat(observation.images())
                .singleElement()
                .satisfies(
                        image ->
                                assertThat(image.source())
                                        .contains("https://example.test/logo.png"));
        assertThat(observation.dialogs())
                .singleElement()
                .satisfies(
                        dialog -> {
                            assertThat(dialog.modal()).isTrue();
                            assertThat(dialog.interactiveElements()).isNotEmpty();
                        });
        assertThat(observation.alerts()).hasSize(1);
        assertThat(observation.tabLists())
                .singleElement()
                .satisfies(tabs -> assertThat(tabs.panelRelationships()).hasSize(1));
        assertThat(observation.menus())
                .singleElement()
                .satisfies(menu -> assertThat(menu.items()).hasSize(1));
        assertThat(observation.content().textBlocks())
                .containsExactly("Bounded visible page content");
        assertThat(observation.warnings())
                .extracting(ObservationWarning::type)
                .contains(
                        ObservationWarningType.HEADING_LEVEL_JUMP,
                        ObservationWarningType.BUTTON_WITHOUT_NAME,
                        ObservationWarningType.CAPTURE_MUTATED,
                        ObservationWarningType.BACKEND_WARNING);
        assertThat(observation.warnings())
                .extracting(ObservationWarning::message)
                .doesNotContain("password=must-never-escape");
        assertThat(observation.statistics().truncations())
                .extracting(ObservationTruncation::type)
                .contains(
                        ObservationTruncationType.TEXT,
                        ObservationTruncationType.SELECT_OPTIONS,
                        ObservationTruncationType.TABLE_ROWS,
                        ObservationTruncationType.TABLE_COLUMNS,
                        ObservationTruncationType.LIST_ITEMS,
                        ObservationTruncationType.TREE_DEPTH,
                        ObservationTruncationType.ELEMENTS);
        assertThat(observation.toString()).doesNotContain("must-never-escape");
        assertThat(observation.toJson()).doesNotContain("must-never-escape");
        assertThat(events)
                .extracting(event -> event.getClass().getSimpleName())
                .containsExactly(
                        "ObservationStarted", "ObservationTruncated", "ObservationCompleted");
    }

    @Test
    void defaultOptionsOmitOrdinaryValuesAndElementBudgetIsExplicit() {
        IPage page = mock(IPage.class);
        when(page.captureObservation(any())).thenReturn(ObservationSnapshotFixtures.richSnapshot());

        Observation observation =
                new ObservationEngine(Clock.fixed(NOW, ZoneOffset.UTC))
                        .observe(page, ObservationOptions.builder().maxElements(6).build());

        assertThat(observation.elements()).hasSize(6);
        assertThat(observation.statistics().truncations())
                .extracting(ObservationTruncation::type)
                .contains(ObservationTruncationType.ELEMENTS);
        assertThat(observation.elements().stream().map(SemanticElement::value))
                .noneMatch(value -> value.value().isPresent());
    }

    @Test
    void wrapsBackendFailuresAndEmitsOnlySafeFailureKinds() {
        IPage page = mock(IPage.class);
        when(page.captureObservation(any()))
                .thenThrow(new IllegalStateException("secret backend details"));
        List<IObservationEvent> events = new ArrayList<>();

        assertThatThrownBy(() -> engine(events).observe(page))
                .isInstanceOf(ObservationBackendException.class)
                .hasMessage("Observation backend capture failed");
        assertThat(events.getLast()).isInstanceOf(IObservationEvent.ObservationFailed.class);
        assertThat(events.getLast().toString()).doesNotContain("secret backend details");
    }

    @Test
    void enforcesTheGlobalDeadlineAndThreadInterruption() {
        IPage page = mock(IPage.class);
        when(page.captureObservation(any()))
                .thenAnswer(
                        ignored -> {
                            Thread.sleep(2);
                            return ObservationSnapshotFixtures.richSnapshot();
                        });
        ObservationOptions tinyDeadline =
                ObservationOptions.builder().timeout(Duration.ofNanos(1)).build();

        assertThatThrownBy(() -> engine(new ArrayList<>()).observe(page, tinyDeadline))
                .isInstanceOf(ObservationTimeoutException.class);

        Thread.currentThread().interrupt();
        try {
            assertThatThrownBy(() -> engine(new ArrayList<>()).observe(page))
                    .isInstanceOf(ObservationException.class)
                    .hasMessage("Observation interrupted");
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void observationEventsRejectNegativeDurations() {
        assertThatThrownBy(
                        () ->
                                new IObservationEvent.ObservationCompleted(
                                        NOW,
                                        new ObservationId("observation"),
                                        Duration.ofNanos(-1),
                                        0,
                                        0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static ObservationEngine engine(List<IObservationEvent> events) {
        return new ObservationEngine(
                Clock.fixed(NOW, ZoneOffset.UTC),
                () -> new ObservationId("test-observation"),
                new SemanticObservationFilter(),
                new SecureObservationRedactionPolicy(),
                new SemanticLocatorDefinitionFactory(),
                new ElementCapabilityResolver(),
                events::add);
    }
}
