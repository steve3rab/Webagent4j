package io.webagent4j.browser.playwright;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.TimeoutError;
import io.webagent4j.dom.ElementState;
import io.webagent4j.locator.ILocatorBackend;
import io.webagent4j.locator.ILocatorEngine;
import io.webagent4j.locator.LocatorBackendQuery;
import io.webagent4j.locator.LocatorBackendSearchResult;
import io.webagent4j.locator.LocatorConfig;
import io.webagent4j.locator.LocatorScope;
import io.webagent4j.locator.LocatorStrategyType;
import io.webagent4j.locator.api.ElementRole;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Proves {@link PlaywrightLocatorBackend#find} distinguishes a candidate that genuinely vanished
 * while counting or between {@link Locator#count()} and its identity-evaluation call - Playwright's
 * typed {@link TimeoutError} for "did not resolve within the bounded inspection timeout" is the
 * only signal absorbed as "this candidate is gone" - from a real backend or runtime failure (a
 * disconnected browser, a closed context, or any other opaque failure), which must always propagate
 * unchanged rather than being silently turned into an absent candidate.
 */
class PlaywrightLocatorBackendTest {

    @Test
    void aFrameRootThatVanishesWhileReadingElementStateIsReportedAsDetached() {
        Locator locator = mock(Locator.class);
        when(locator.count()).thenThrow(new TimeoutError("Frame root disappeared while counting"));

        ElementState state =
                new PlaywrightElement(
                                locator,
                                ElementRole.BUTTON,
                                null,
                                LocatorScope.page(),
                                LocatorConfig.defaults())
                        .state();

        assertThat(state.present()).isFalse();
        assertThat(state.visible()).isFalse();
    }

    @Test
    void aFrameRootThatVanishesWhileReadingAttributesProducesEmptyMetadata() {
        Locator locator = mock(Locator.class);
        when(locator.evaluate(any(), any(), any(Locator.EvaluateOptions.class)))
                .thenThrow(new TimeoutError("Frame root disappeared while reading attributes"));

        Map<String, String> attributes =
                new PlaywrightElement(
                                locator,
                                ElementRole.BUTTON,
                                null,
                                LocatorScope.page(),
                                LocatorConfig.defaults())
                        .attributes();

        assertThat(attributes).isEmpty();
    }

    @Test
    void aFrameRootThatVanishesWithATimeoutErrorDuringCountingProducesAnEmptyPoll() {
        Locator matches = mock(Locator.class);
        Locator documentRoot = rootLocatingAll(matches);
        when(matches.count()).thenThrow(new TimeoutError("Frame root disappeared while counting"));

        LocatorBackendSearchResult result =
                backend(documentRoot)
                        .find(
                                roleQuery(),
                                LocatorScope.page(),
                                LocatorConfig.defaults(),
                                Duration.ofSeconds(1),
                                20);

        assertThat(result.candidates()).isEmpty();
        assertThat(result.discoveredCount()).isZero();
        assertThat(result.truncated()).isFalse();
    }

    @Test
    void aGenuineBackendFailureDuringCountingPropagatesUnchanged() {
        Locator matches = mock(Locator.class);
        Locator documentRoot = rootLocatingAll(matches);
        RuntimeException backendFailure = new IllegalStateException("browser disconnected");
        when(matches.count()).thenThrow(backendFailure);

        assertThatThrownBy(
                        () ->
                                backend(documentRoot)
                                        .find(
                                                roleQuery(),
                                                LocatorScope.page(),
                                                LocatorConfig.defaults(),
                                                Duration.ofSeconds(1),
                                                20))
                .isSameAs(backendFailure);
    }

    @Test
    void aCandidateThatVanishesWithATimeoutErrorDuringIdentityEvaluationIsExcludedFromThisPoll() {
        Locator matches = mock(Locator.class);
        Locator item = mock(Locator.class);
        Locator documentRoot = rootLocatingAll(matches);
        when(matches.count()).thenReturn(1);
        when(matches.nth(0)).thenReturn(item);
        when(item.evaluate(any(), any(), any(Locator.EvaluateOptions.class)))
                .thenThrow(
                        new TimeoutError("Timeout exceeded while evaluating candidate identity"));

        LocatorBackendSearchResult result =
                backend(documentRoot)
                        .find(
                                roleQuery(),
                                LocatorScope.page(),
                                LocatorConfig.defaults(),
                                Duration.ofSeconds(1),
                                20);

        // The candidate is dropped from this poll's results - never surfaced as a backend
        // failure - so the caller's WaitEngine retries and picks it up as a normal "not
        // currently present" outcome instead of aborting the whole resolution.
        assertThat(result.candidates()).isEmpty();
        assertThat(result.discoveredCount()).isEqualTo(1);
    }

    @Test
    void
            aGenuineBackendFailureDuringIdentityEvaluationPropagatesUnchangedInsteadOfBecomingAnAbsentCandidate() {
        Locator matches = mock(Locator.class);
        Locator item = mock(Locator.class);
        Locator documentRoot = rootLocatingAll(matches);
        when(matches.count()).thenReturn(1);
        when(matches.nth(0)).thenReturn(item);
        RuntimeException backendFailure = new IllegalStateException("browser disconnected");
        when(item.evaluate(any(), any(), any(Locator.EvaluateOptions.class)))
                .thenThrow(backendFailure);

        assertThatThrownBy(
                        () ->
                                backend(documentRoot)
                                        .find(
                                                roleQuery(),
                                                LocatorScope.page(),
                                                LocatorConfig.defaults(),
                                                Duration.ofSeconds(1),
                                                20))
                // Must be the exact same instance - not wrapped, not reinterpreted as
                // LocatorNotFoundException, not swallowed into an empty result.
                .isSameAs(backendFailure);
    }

    private static Locator rootLocatingAll(Locator matches) {
        Locator documentRoot = mock(Locator.class);
        when(documentRoot.locator("*")).thenReturn(matches);
        return documentRoot;
    }

    private static ILocatorBackend backend(Locator documentRoot) {
        return new PlaywrightLocatorBackend(
                documentRoot,
                mock(ILocatorEngine.class),
                LocatorConfig.defaults(),
                LocatorScope.page());
    }

    private static LocatorBackendQuery roleQuery() {
        return new LocatorBackendQuery(
                LocatorStrategyType.ROLE,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty());
    }
}
