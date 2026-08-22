package io.webagent4j.observation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.webagent4j.locator.api.ElementRole;
import io.webagent4j.observation.spi.PageSnapshot;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ObservationModelTest {

    @Test
    void exposesImmutableDomainViewsAndPortableReferences() {
        Observation observation = ObservationTestFixtures.completeObservation();

        assertThat(observation.url()).isEqualTo("https://example.test/account");
        assertThat(observation.title()).isEqualTo("Account");
        assertThat(observation.headings())
                .extracting(HeadingObservation::text)
                .containsExactly("Account");
        assertThat(observation.links()).extracting(SemanticElement::name).containsExactly("Help");
        assertThat(observation.buttons())
                .extracting(SemanticElement::name)
                .containsExactly("Continue");
        assertThat(observation.byRole(ElementRole.TEXTBOX)).hasSize(1);
        assertThat(observation.interactiveElements()).hasSize(5);
        assertThat(observation.element(1).role()).isEqualTo(ElementRole.MAIN);
        assertThat(observation.element(5).visible()).isTrue();
        assertThat(observation.element(5).enabled()).isTrue();
        assertThat(observation.element(5).reference().definition().role())
                .contains(ElementRole.TEXTBOX);
        assertThat(observation.metadata().language()).contains("en");
        assertThat(observation.statistics().truncated()).isTrue();
        assertThat(observation.tree().depthTruncated()).isTrue();

        assertThatThrownBy(() -> observation.elements().add(observation.element(1)))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> observation.element(0))
                .isInstanceOf(IndexOutOfBoundsException.class);
        assertThatThrownBy(() -> observation.element(100))
                .isInstanceOf(IndexOutOfBoundsException.class);
    }

    @Test
    void rendersCompleteDeterministicAndSecretSafeRepresentations() {
        Observation observation = ObservationTestFixtures.completeObservation();

        String compact = observation.toCompactText();
        String json = observation.toJson();

        assertThat(compact)
                .startsWith("PAGE \"Account\"")
                .contains("[1] MAIN \"Main\"")
                .contains("depth-truncated")
                .endsWith("TRUNCATED");
        assertThat(json)
                .startsWith("{")
                .endsWith("}")
                .contains("\"tabLists\"")
                .contains("\"menus\"")
                .contains("user@example.test")
                .contains("Welcome\\nback")
                .doesNotContain("LocatorDefinition")
                .doesNotContain("backendId");
        assertThat(new CompactTextObservationRenderer().render(observation)).isEqualTo(compact);
        assertThat(new JsonObservationRenderer().render(observation)).isEqualTo(json);
    }

    @Test
    void optionsBuilderAppliesEveryBoundAndSecureDefaults() {
        ObservationOptions defaults = ObservationOptions.defaults();
        ObservationOptions options =
                ObservationOptions.builder()
                        .mode(ObservationMode.DETAILED)
                        .includeHidden(true)
                        .includeInputValues(true)
                        .timeout(Duration.ofSeconds(2))
                        .maxElements(20)
                        .maxDepth(4)
                        .maxTextLength(100)
                        .maxTableRows(5)
                        .maxTableColumns(6)
                        .maxListItems(7)
                        .maxSelectOptions(8)
                        .allowedDataAttributes(List.of("DATA-TRACK", "data-testid"))
                        .build();

        assertThat(defaults.includeInputValues()).isFalse();
        assertThat(defaults.allowedDataAttributes()).containsExactly("data-testid");
        assertThat(options.mode()).isEqualTo(ObservationMode.DETAILED);
        assertThat(options.includeHidden()).isTrue();
        assertThat(options.includeInputValues()).isTrue();
        assertThat(options.budget())
                .extracting(
                        ObservationBudget::timeout,
                        ObservationBudget::maxElements,
                        ObservationBudget::maxDepth,
                        ObservationBudget::maxTextLength,
                        ObservationBudget::maxTableRows,
                        ObservationBudget::maxTableColumns,
                        ObservationBudget::maxListItems,
                        ObservationBudget::maxSelectOptions)
                .containsExactly(Duration.ofSeconds(2), 20, 4, 100, 5, 6, 7, 8);
        assertThat(options.allowedDataAttributes())
                .containsExactlyInAnyOrder("data-track", "data-testid");

        ObservationBudget replacement =
                new ObservationBudget(Duration.ofSeconds(1), 1, 1, 1, 1, 1, 1, 1);
        assertThat(ObservationOptions.builder().budget(replacement).build().budget())
                .isSameAs(replacement);
        assertThatThrownBy(
                        () ->
                                ObservationOptions.builder()
                                        .allowedDataAttributes(Set.of("class"))
                                        .build())
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ObservationBudget(Duration.ZERO, 1, 1, 1, 1, 1, 1, 1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void valueAndModelInvariantsPreventSecretOrMutableStateLeaks() {
        assertThat(ObservedValue.empty().valuePresent()).isFalse();
        assertThat(ObservedValue.omitted(true).disposition()).isEqualTo(ValueDisposition.OMITTED);
        assertThat(ObservedValue.plain("")).isEqualTo(ObservedValue.empty());
        assertThat(ObservedValue.redacted(true).redacted()).isTrue();
        assertThat(ObservedValue.redacted(true).toString()).doesNotContain("secret-value");
        assertThatThrownBy(
                        () ->
                                new ObservedValue(
                                        ValueDisposition.REDACTED,
                                        Optional.of("secret-value"),
                                        true))
                .isInstanceOf(IllegalArgumentException.class);

        SemanticElement regular =
                ObservationTestFixtures.element(1, ElementRole.TEXTBOX, "Password", "");
        assertThatThrownBy(
                        () ->
                                new SemanticElement(
                                        regular.index(),
                                        regular.id(),
                                        regular.stableKey(),
                                        regular.role(),
                                        regular.name(),
                                        regular.text(),
                                        regular.tagName(),
                                        regular.state(),
                                        regular.reference(),
                                        regular.attributes(),
                                        regular.capabilities(),
                                        regular.parentId(),
                                        regular.formId(),
                                        regular.headingLevel(),
                                        regular.fieldType(),
                                        true,
                                        ObservedValue.plain("secret-value")))
                .isInstanceOf(IllegalArgumentException.class);

        ArrayList<String> mutableItems = new ArrayList<>(List.of("one"));
        ListObservation list =
                new ListObservation(new SemanticElementId("list"), false, 1, mutableItems, false);
        mutableItems.add("two");
        assertThat(list.items()).containsExactly("one");
    }

    @Test
    void rejectsNegativeObservationDurations() {
        assertThatThrownBy(
                        () ->
                                new ObservationStatistics(
                                        0, 0, 0, 0, 0, 0, 0, 0, Duration.ofNanos(-1), List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                new PageSnapshot(
                                        "https://example.test",
                                        "Example",
                                        Optional.empty(),
                                        Optional.empty(),
                                        "complete",
                                        new ViewportSize(1280, 720),
                                        Optional.empty(),
                                        Optional.empty(),
                                        List.of(),
                                        0,
                                        0,
                                        Duration.ofNanos(-1),
                                        false,
                                        List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
