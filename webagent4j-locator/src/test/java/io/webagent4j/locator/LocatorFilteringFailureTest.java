package io.webagent4j.locator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.webagent4j.dom.ElementState;
import io.webagent4j.dom.IElement;
import io.webagent4j.locator.api.ElementRole;
import io.webagent4j.locator.api.LocatorDefinition;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Fail-closed coverage for runtime failures while hard locator constraints are inspected. */
class LocatorFilteringFailureTest {

    private final LocatorEngine engine = new LocatorEngine();

    @Test
    void aRuntimeFailureReadingStatePropagatesInsteadOfBecomingOutsideScope() {
        RuntimeException failure = new IllegalStateException("state backend failed");
        IElement candidate = candidate();
        when(candidate.state()).thenThrow(failure);

        assertThatThrownBy(() -> locateSingle(List.of(candidate), visibleConfirm()))
                .isSameAs(failure);
    }

    @Test
    void aRuntimeFailureReadingAttributesPropagatesInsteadOfBecomingOutsideScope() {
        RuntimeException failure = new IllegalStateException("attribute backend failed");
        IElement candidate = candidate();
        when(candidate.state()).thenReturn(presentState());
        when(candidate.attributes()).thenThrow(failure);

        assertThatThrownBy(
                        () ->
                                locateSingle(
                                        List.of(candidate),
                                        visibleConfirm().withAttribute("data-kind", "primary")))
                .isSameAs(failure);
    }

    @Test
    void aFailingFirstCandidateCannotBeDiscardedToMakeASecondCandidateTheWinner() {
        RuntimeException failure =
                new IllegalStateException("candidate inspection exhausted its budget");
        IElement failing = candidate();
        when(failing.state()).thenThrow(failure);
        IElement otherwiseValid = LocatorTestFixtures.element(ElementRole.BUTTON, "Confirm");

        assertThatThrownBy(() -> locateSingle(List.of(failing, otherwiseValid), visibleConfirm()))
                .isSameAs(failure);
        verify(failing).state();
    }

    @Test
    void aProvenDetachedCandidateRemainsDistinctFromAnInspectionFailure() {
        IElement detached = candidate();
        when(detached.state()).thenReturn(detachedState());
        IElement present = LocatorTestFixtures.element(ElementRole.BUTTON, "Confirm");

        assertThat(locateSingle(List.of(detached, present), visibleConfirm()).element())
                .isSameAs(present);
        verify(detached, never()).accessibleName();
    }

    private LocatorResult locateSingle(List<IElement> elements, LocatorDefinition definition) {
        ILocatorBackend backend =
                (query, scope, config, timeout, candidateLimit) -> {
                    List<LocatorBackendCandidate> candidates =
                            java.util.stream.IntStream.range(0, elements.size())
                                    .mapToObj(
                                            index ->
                                                    new LocatorBackendCandidate(
                                                            "candidate-" + index,
                                                            elements.get(index),
                                                            index))
                                    .toList();
                    return new LocatorBackendSearchResult(candidates, candidates.size(), false);
                };
        LocatorConfig config =
                new LocatorConfig(
                        0.80,
                        20,
                        Duration.ofMillis(20),
                        true,
                        true,
                        0.02,
                        LocatorScoringConfig.defaults());
        return engine.locateSingle(LocatorContext.page(backend, config), definition);
    }

    private static LocatorDefinition visibleConfirm() {
        return LocatorDefinition.forRole(ElementRole.BUTTON).named("Confirm").visibleOnly();
    }

    private static IElement candidate() {
        IElement candidate = mock(IElement.class);
        when(candidate.role()).thenReturn(ElementRole.BUTTON);
        when(candidate.accessibleName()).thenReturn("Confirm");
        when(candidate.text()).thenReturn("Confirm");
        when(candidate.tagName()).thenReturn("button");
        when(candidate.attributes()).thenReturn(Map.of());
        return candidate;
    }

    private static ElementState presentState() {
        return ElementState.basic(true, true, true);
    }

    private static ElementState detachedState() {
        return new ElementState(
                false, false, false, false, false, false, false, false, false, false, false, true);
    }
}
