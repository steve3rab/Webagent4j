package io.webagent4j.locator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.webagent4j.dom.BoundingBox;
import io.webagent4j.dom.ElementState;
import io.webagent4j.dom.IElement;
import io.webagent4j.locator.api.ElementRole;
import io.webagent4j.locator.internal.DefaultInteractabilityChecker;
import io.webagent4j.locator.internal.LocatorCandidateOrder;
import io.webagent4j.locator.internal.LocatorResolutionWaiter;
import java.time.Duration;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class LocatorAdvancedComponentsTest {

    @Test
    void buildsImmutablePolicySpecificConfiguration() {
        LocatorConfig strict =
                LocatorConfig.builder()
                        .resolutionPolicy(LocatorResolutionPolicy.STRICT)
                        .locale(Locale.FRENCH)
                        .testIdAttribute("data-qa")
                        .ambiguityMargin(0.03)
                        .earlyStopConfidence(0.97)
                        .pollingInterval(Duration.ofMillis(10))
                        .diagnosticsLevel(LocatorDiagnosticsLevel.OFF)
                        .resolutionBudget(
                                new LocatorResolutionBudget(Duration.ofSeconds(1), 20, 4, 3))
                        .scoring(LocatorScoringConfig.defaults())
                        .build();
        LocatorConfig permissive =
                LocatorConfig.builder()
                        .resolutionPolicy(LocatorResolutionPolicy.PERMISSIVE)
                        .diagnosticsLevel(LocatorDiagnosticsLevel.OFF)
                        .build();

        assertThat(strict.allowFuzzyMatching()).isFalse();
        assertThat(strict.fuzzyThreshold()).isEqualTo(1.0);
        assertThat(strict.locale()).isEqualTo(Locale.FRENCH);
        assertThat(strict.testIdAttribute()).isEqualTo("data-qa");
        assertThat(strict.maximumCandidates()).isEqualTo(20);
        assertThat(strict.defaultTimeout()).isEqualTo(Duration.ofSeconds(1));
        assertThat(strict.diagnosticsEnabled()).isFalse();
        assertThat(permissive.fuzzyThreshold()).isEqualTo(0.70);
        assertThat(permissive.diagnosticsLevel()).isEqualTo(LocatorDiagnosticsLevel.DETAILED);
    }

    @Test
    void validatesConfigurationBudgetAndScoringValues() {
        assertThatThrownBy(() -> LocatorConfig.builder().fuzzyThreshold(-0.1).build())
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> LocatorConfig.builder().ambiguityMargin(2.0).build())
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> LocatorConfig.builder().earlyStopConfidence(2.0).build())
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> LocatorConfig.builder().testIdAttribute(" "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> LocatorConfig.builder().pollingInterval(Duration.ZERO).build())
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LocatorResolutionBudget(Duration.ZERO, 1, 1, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LocatorResolutionBudget(Duration.ofSeconds(1), 0, 1, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LocatorScoringConfig(0, 0, 0, 0, 0, 0, 0, 0, 2))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void reportsCompleteInteractabilityReasonsAndSuccess() {
        DefaultInteractabilityChecker checker = new DefaultInteractabilityChecker();
        ElementState blockedState =
                new ElementState(
                        false, false, false, false, true, false, false, false, false, false, true,
                        false);
        ElementState interactiveState =
                new ElementState(
                        true, true, true, true, false, false, false, false, true, true, false,
                        true);

        assertThat(checker.check(new StateElement(blockedState)).reasons())
                .contains(
                        InteractabilityFailureReason.DETACHED,
                        InteractabilityFailureReason.NOT_VISIBLE,
                        InteractabilityFailureReason.DISABLED,
                        InteractabilityFailureReason.OUTSIDE_VIEWPORT,
                        InteractabilityFailureReason.COVERED,
                        InteractabilityFailureReason.READ_ONLY,
                        InteractabilityFailureReason.UNKNOWN);
        assertThat(checker.check(new StateElement(interactiveState)).interactable()).isTrue();
        assertThatThrownBy(() -> InteractabilityResult.failed(List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                new InteractabilityResult(
                                        true, List.of(InteractabilityFailureReason.UNKNOWN)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void exposesBackendCapabilitiesAndStableCandidateOrdering() {
        LocatorBackendCapabilities capabilities =
                new LocatorBackendCapabilities(
                        EnumSet.of(LocatorStrategyType.ROLE),
                        EnumSet.of(LocatorBackendCapability.SCOPED_SEARCH));
        assertThat(capabilities.supports(LocatorStrategyType.ROLE)).isTrue();
        assertThat(capabilities.supports(LocatorStrategyType.FUZZY_TEXT)).isFalse();
        assertThat(capabilities.supports(LocatorBackendCapability.SCOPED_SEARCH)).isTrue();
        assertThat(LocatorBackendCapabilities.standardStrategies().supports(LocatorStrategyType.ID))
                .isTrue();

        LocatorEvidence role =
                new LocatorEvidence(
                        LocatorStrategyType.ROLE, LocatorMatchType.EXACT, "BUTTON", "BUTTON", 0.3);
        LocatorCandidate first = candidate("a", 1, role);
        LocatorCandidate second = candidate("b", 2, role);
        assertThat(List.of(second, first).stream().sorted(LocatorCandidateOrder.comparator()))
                .containsExactly(first, second);
        assertThat(LocatorCandidateOrder.sameSemanticTier(first, second)).isTrue();
    }

    @Test
    void validatesStateEvidenceAndBoundedBackendResults() {
        assertThat(ElementState.basic(true, true, true).interactabilityKnown()).isFalse();
        assertThat(ElementState.basic(false, false, false).hidden()).isTrue();
        assertThat(ElementState.basic(true, false, true).disabled()).isTrue();
        assertThat(
                        new ElementState(
                                        false, false, false, false, false, false, false, false,
                                        false, false, false, false)
                                .detached())
                .isTrue();
        assertThatThrownBy(
                        () ->
                                new ElementState(
                                        true, false, true, false, false, false, false, false, true,
                                        true, false, true))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                new LocatorEvidence(
                                        LocatorStrategyType.ROLE,
                                        LocatorMatchType.EXACT,
                                        "x",
                                        "x",
                                        2))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LocatorBackendSearchResult(List.of(), 0, true))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void usesAnInterruptibleMonotonicWaiter() {
        long started = System.nanoTime();
        new LocatorResolutionWaiter().awaitNextPoll(Duration.ofMillis(2), Duration.ofMillis(5));
        assertThat(Duration.ofNanos(System.nanoTime() - started))
                .isGreaterThanOrEqualTo(Duration.ofMillis(1));
    }

    private static LocatorCandidate candidate(
            String identity, int order, LocatorEvidence evidence) {
        return new LocatorCandidate(
                identity,
                new StateElement(ElementState.basic(true, true, true)),
                LocatorStrategyType.ROLE,
                0.5,
                0.5,
                order,
                List.of(evidence),
                true,
                true,
                false);
    }

    private record StateElement(ElementState state) implements IElement {

        @Override
        public ElementRole role() {
            return ElementRole.BUTTON;
        }

        @Override
        public String accessibleName() {
            return "Target";
        }

        @Override
        public String text() {
            return "Target";
        }

        @Override
        public String tagName() {
            return "button";
        }

        @Override
        public Map<String, String> attributes() {
            return Map.of();
        }

        @Override
        public boolean visible() {
            return state.visible();
        }

        @Override
        public boolean enabled() {
            return state.enabled();
        }

        @Override
        public Optional<BoundingBox> boundingBox() {
            return Optional.empty();
        }

        @Override
        public void click() {}
    }
}
