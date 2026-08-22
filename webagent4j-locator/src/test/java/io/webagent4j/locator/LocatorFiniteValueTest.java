package io.webagent4j.locator;

import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import io.webagent4j.dom.IElement;
import io.webagent4j.locator.api.ElementRole;
import io.webagent4j.locator.api.LocatorDefinition;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class LocatorFiniteValueTest {

    @Test
    void rejectsNonFiniteConfigurationValues() {
        LocatorScoringConfig scoring = LocatorScoringConfig.defaults();

        assertThatIllegalArgumentException()
                .isThrownBy(
                        () ->
                                new LocatorConfig(
                                        Double.NaN,
                                        1,
                                        Duration.ofSeconds(1),
                                        true,
                                        true,
                                        0.0,
                                        scoring));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new LocatorScoringConfig(Double.NaN, 0.0, 0.0, 0.0, 0.0, 0.0));
    }

    @Test
    void rejectsNonFiniteScoresAcrossPublicLocatorResultsAndDiagnostics() {
        IElement element = LocatorTestFixtures.element(ElementRole.BUTTON, "Confirm");

        assertThatIllegalArgumentException()
                .isThrownBy(
                        () ->
                                new LocatorCandidate(
                                        "candidate",
                                        element,
                                        LocatorStrategyType.ROLE,
                                        Double.NaN,
                                        1.0,
                                        0,
                                        List.of(),
                                        true,
                                        true,
                                        true));
        assertThatIllegalArgumentException()
                .isThrownBy(
                        () ->
                                new LocatorResult(
                                        LocatorDefinition.element(),
                                        element,
                                        LocatorStrategyType.ROLE,
                                        Double.NaN,
                                        1.0,
                                        true,
                                        List.of(),
                                        diagnostics()));
        assertThatIllegalArgumentException()
                .isThrownBy(
                        () ->
                                new LocatorDiagnostics.Ambiguity(
                                        "first", Double.NaN, "second", 0.5, 0.1));
    }

    @Test
    void rejectsNonFiniteEvidenceAndEventValues() {
        assertThatIllegalArgumentException()
                .isThrownBy(
                        () ->
                                new LocatorEvidence(
                                        LocatorStrategyType.ROLE,
                                        LocatorMatchType.EXACT,
                                        "button",
                                        "button",
                                        Double.NaN));
        assertThatIllegalArgumentException()
                .isThrownBy(
                        () -> new MatchExplanation("role", "button", "button", true, Double.NaN));
        assertThatIllegalArgumentException()
                .isThrownBy(
                        () ->
                                new ILocatorEvent.CandidateFound(
                                        Instant.EPOCH,
                                        "candidate",
                                        LocatorStrategyType.ROLE,
                                        Double.NaN));
        assertThatIllegalArgumentException()
                .isThrownBy(
                        () ->
                                new ILocatorEvent.ResolutionCompleted(
                                        Instant.EPOCH, "candidate", Double.NaN, Duration.ZERO));
    }

    private static LocatorDiagnostics diagnostics() {
        return new LocatorDiagnostics(
                LocatorDefinition.element(),
                LocatorResolutionPolicy.BALANCED,
                LocatorDiagnosticsLevel.OFF,
                List.of(),
                List.of(),
                List.of(),
                0,
                0,
                0,
                List.of(),
                0,
                0,
                Optional.empty(),
                Duration.ZERO,
                false,
                Set.of(),
                Optional.empty(),
                List.of());
    }
}
