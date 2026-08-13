package io.webagent4j.locator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.webagent4j.locator.LocatorTestFixtures.TestElement;
import io.webagent4j.locator.api.ElementRole;
import io.webagent4j.locator.api.LocatorDefinition;
import io.webagent4j.locator.api.TextMatch;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class LocatorComponentsTest {

    @Test
    void filtersEditableCheckedSelectedHiddenAndDisabledStates() {
        LocatorFilter filter = new LocatorFilter();
        TestElement element =
                new TestElement(
                        ElementRole.CHECKBOX,
                        "Accept",
                        "Accept",
                        "input",
                        Map.of("checked", "", "selected", "true", "contenteditable", "true"),
                        false,
                        false);

        assertThat(
                        filter.accepts(
                                LocatorDefinition.forRole(ElementRole.CHECKBOX)
                                        .hiddenOnly()
                                        .disabledOnly()
                                        .checkedOnly()
                                        .selectedOnly(),
                                element))
                .isTrue();
        assertThat(filter.accepts(LocatorDefinition.element().editableOnly(), element)).isFalse();
        assertThat(filter.accepts(LocatorDefinition.forRole(ElementRole.BUTTON), element))
                .isFalse();
    }

    @Test
    void validatesConfigurationRegistryAndDiagnosticValueObjects() {
        LocatorScoringConfig scoring = LocatorScoringConfig.defaults();
        LocatorConfig defaults = LocatorConfig.defaults();
        assertThat(defaults.fuzzyThreshold()).isEqualTo(0.80);
        assertThat(LocatorConfig.defaults(Duration.ofSeconds(1)).defaultTimeout())
                .isEqualTo(Duration.ofSeconds(1));
        assertThat(scoring.roleWeight()).isPositive();

        LocatorStrategyRegistry registry = LocatorStrategyRegistry.defaults();
        assertThat(registry.strategies()).hasSize(LocatorStrategyType.values().length - 1);
        assertThat(registry.strategy(LocatorStrategyType.ROLE).type())
                .isEqualTo(LocatorStrategyType.ROLE);

        assertThatThrownBy(
                        () ->
                                new LocatorConfig(
                                        -1, 1, Duration.ofSeconds(1), true, true, 0.0, scoring))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                new LocatorConfig(
                                        0.8, 0, Duration.ofSeconds(1), true, true, 0.0, scoring))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LocatorConfig(0.8, 1, Duration.ZERO, true, true, 0.0, scoring))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LocatorScoringConfig(2, 0, 0, 0, 0, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LocatorPlan(List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                new LocatorBackendCandidate(
                                        "x",
                                        LocatorTestFixtures.element(ElementRole.BUTTON, "x"),
                                        -1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MatchExplanation("x", "x", "x", true, 2))
                .isInstanceOf(IllegalArgumentException.class);

        ILocatorStrategy duplicate = registry.strategy(LocatorStrategyType.ROLE);
        assertThatThrownBy(() -> new LocatorStrategyRegistry(List.of(duplicate, duplicate)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                new LocatorStrategyRegistry(List.of())
                                        .strategy(LocatorStrategyType.ROLE))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void contextUsesDefinitionTimeoutAndScopesSafely() {
        LocatorTestFixtures.FakeBackend backend = new LocatorTestFixtures.FakeBackend(List.of());
        LocatorContext context =
                LocatorContext.page(backend, LocatorConfig.defaults(Duration.ofSeconds(2)));
        LocatorDefinition definition =
                LocatorDefinition.element().withTimeout(Duration.ofMillis(25));
        TestElement scope = LocatorTestFixtures.element(ElementRole.FORM, "Payment");

        assertThat(context.timeoutFor(definition)).isEqualTo(Duration.ofMillis(25));
        assertThat(context.within(scope).scope().root()).contains(scope);
        LocatorBackendQuery query =
                new LocatorBackendQuery(
                        LocatorStrategyType.ROLE,
                        Optional.of(ElementRole.BUTTON),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty());
        assertThat(new LocatorPlanStep(query, "role").description()).isEqualTo("role");
    }

    @Test
    void explicitAccessibleNameCannotBeOverriddenByMisleadingVisibleText() {
        LocatorDefinition definition =
                LocatorDefinition.forRole(ElementRole.BUTTON).named("Save profile");
        LocatorPlanStep visibleTextStep =
                new LocatorPlanStep(
                        new LocatorBackendQuery(
                                LocatorStrategyType.VISIBLE_TEXT,
                                Optional.of(ElementRole.BUTTON),
                                Optional.of(TextMatch.exactIgnoringCase("Save profile")),
                                Optional.empty(),
                                Optional.empty()),
                        "visible text");
        TestElement misleading =
                new TestElement(
                        ElementRole.BUTTON,
                        "Archive profile",
                        "Save profile",
                        "button",
                        Map.of("aria-label", "Archive profile"),
                        true,
                        true);

        assertThat(
                        new LocatorScorer()
                                .score(
                                        definition,
                                        visibleTextStep,
                                        new LocatorBackendCandidate("misleading", misleading, 0),
                                        LocatorScoringConfig.defaults(),
                                        0.80)
                                .candidate())
                .isEmpty();
    }
}
