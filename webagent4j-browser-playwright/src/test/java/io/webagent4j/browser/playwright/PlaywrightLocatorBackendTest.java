package io.webagent4j.browser.playwright;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.PlaywrightException;
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
import io.webagent4j.wait.WaitBudget;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Proves {@link PlaywrightLocatorBackend#find} distinguishes a candidate that genuinely vanished
 * while counting or between {@link Locator#count()} and its identity-evaluation call. A typed
 * {@link TimeoutError} is absorbed only after a fresh count confirms absence; Playwright's
 * canonical frame-detached protocol failure is already definitive. Every opaque backend/runtime
 * failure propagates unchanged.
 */
class PlaywrightLocatorBackendTest {

    @Test
    void currentCandidateInspectionsDoNotStartNestedPlaywrightWaits() {
        Locator matches = mock(Locator.class);
        Locator item = mock(Locator.class);
        Locator documentRoot = rootLocatingAll(matches);
        when(matches.count()).thenReturn(1);
        when(matches.nth(0)).thenReturn(item);
        when(item.evaluateAll(anyString()))
                .thenReturn(Map.of("identity", "candidate", "domOrder", 0))
                .thenReturn(Map.of());

        LocatorBackendSearchResult result =
                backend(documentRoot)
                        .find(
                                roleQuery(),
                                LocatorScope.page(),
                                LocatorConfig.defaults(),
                                Duration.ofSeconds(4),
                                20);
        result.candidates().getFirst().element().attributes();

        verify(item, times(2)).evaluateAll(anyString());
    }

    @ParameterizedTest
    @MethodSource("positiveTimeoutBudgets")
    void internalTimeoutsNeverExceedTheRemainingCallerBudget(Duration budget) {
        double budgetMillis = toMillis(budget);

        for (int candidateCount : new int[] {1, 4, 1_000}) {
            assertThat(PlaywrightLocatorBackend.operationTimeoutMillis(budget, candidateCount))
                    .isPositive()
                    .isLessThanOrEqualTo(budgetMillis);
            assertThat(PlaywrightLocatorBackend.inspectionTimeoutMillis(budget, candidateCount))
                    .isPositive()
                    .isLessThanOrEqualTo(budgetMillis);
            assertThat(PlaywrightLocatorBackend.identityTimeoutMillis(budget, candidateCount))
                    .isPositive()
                    .isLessThanOrEqualTo(budgetMillis);
        }
    }

    @Test
    void zeroBudgetNeverCreatesAnUnboundedPlaywrightTimeout() {
        assertThat(PlaywrightLocatorBackend.operationTimeoutMillis(Duration.ZERO, 1)).isZero();
        assertThat(PlaywrightLocatorBackend.inspectionTimeoutMillis(Duration.ZERO, 1)).isZero();
        assertThat(PlaywrightLocatorBackend.identityTimeoutMillis(Duration.ZERO, 1)).isZero();
    }

    @Test
    void identityAndCandidateInspectionsShareOneCallerBudget() {
        Duration budget = Duration.ofMillis(800);
        int candidateCount = 5;
        double identityTimeout =
                PlaywrightLocatorBackend.identityTimeoutMillis(budget, candidateCount);
        double inspectionTimeout =
                PlaywrightLocatorBackend.inspectionTimeoutMillis(budget, candidateCount);

        double maximumAllocated = candidateCount * (identityTimeout + 8 * inspectionTimeout);

        assertThat(maximumAllocated).isLessThanOrEqualTo(toMillis(budget));
    }

    private static Stream<Duration> positiveTimeoutBudgets() {
        return Stream.of(
                Duration.ofNanos(1),
                Duration.ofMillis(1),
                Duration.ofMillis(10),
                Duration.ofMillis(100),
                Duration.ofMillis(800),
                Duration.ofSeconds(2),
                Duration.ofSeconds(4));
    }

    private static double toMillis(Duration duration) {
        return duration.getSeconds() * 1_000.0 + duration.getNano() / 1_000_000.0;
    }

    @Test
    void aFrameRootThatVanishesWhileReadingElementStateIsReportedAsDetached() {
        Locator locator = mock(Locator.class);
        when(locator.count())
                .thenThrow(new TimeoutError("Frame root disappeared while counting"))
                .thenReturn(0);

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
    void aStateInspectionTimeoutForAStillPresentElementPropagatesUnchanged() {
        Locator locator = mock(Locator.class);
        TimeoutError timeout = new TimeoutError("State inspection exceeded its bound");
        when(locator.count()).thenReturn(1);
        when(locator.evaluate(any(), any(), any(Locator.EvaluateOptions.class))).thenThrow(timeout);

        PlaywrightElement element =
                new PlaywrightElement(
                        locator,
                        ElementRole.BUTTON,
                        null,
                        LocatorScope.page(),
                        LocatorConfig.defaults());

        assertThatThrownBy(element::state).isSameAs(timeout);
    }

    @Test
    void aGenuineBackendFailureDuringStateInspectionPropagatesUnchanged() {
        Locator locator = mock(Locator.class);
        PlaywrightException backendFailure = new PlaywrightException("browser disconnected");
        when(locator.count()).thenReturn(1);
        when(locator.evaluate(any(), any(), any(Locator.EvaluateOptions.class)))
                .thenThrow(backendFailure);

        PlaywrightElement element =
                new PlaywrightElement(
                        locator,
                        ElementRole.BUTTON,
                        null,
                        LocatorScope.page(),
                        LocatorConfig.defaults());

        assertThatThrownBy(element::state).isSameAs(backendFailure);
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
        when(matches.count())
                .thenThrow(new TimeoutError("Frame root disappeared while counting"))
                .thenReturn(0);

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
    void anExplicitFrameDetachmentDuringCountingProducesAnEmptyPoll() {
        Locator matches = mock(Locator.class);
        Locator documentRoot = rootLocatingAll(matches);
        when(matches.count()).thenThrow(frameDetachedFailure());

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
    }

    @Test
    void aCountingTimeoutForAStillPresentRootPropagatesUnchanged() {
        Locator matches = mock(Locator.class);
        Locator documentRoot = rootLocatingAll(matches);
        TimeoutError timeout = new TimeoutError("Counting exceeded its bound");
        when(matches.count()).thenThrow(timeout).thenReturn(1);

        assertThatThrownBy(
                        () ->
                                backend(documentRoot)
                                        .find(
                                                roleQuery(),
                                                LocatorScope.page(),
                                                LocatorConfig.defaults(),
                                                Duration.ofSeconds(1),
                                                20))
                .isSameAs(timeout);
    }

    @Test
    void aFailedCountingRecheckPreservesTheOriginalTimeoutAndSuppressesTheRecheck() {
        Locator matches = mock(Locator.class);
        Locator documentRoot = rootLocatingAll(matches);
        TimeoutError timeout = new TimeoutError("Counting exceeded its bound");
        RuntimeException recheckFailure = new IllegalStateException("browser disconnected");
        when(matches.count()).thenThrow(timeout).thenThrow(recheckFailure);

        assertThatThrownBy(
                        () ->
                                backend(documentRoot)
                                        .find(
                                                roleQuery(),
                                                LocatorScope.page(),
                                                LocatorConfig.defaults(),
                                                Duration.ofSeconds(1),
                                                20))
                .isSameAs(timeout)
                .satisfies(
                        failure ->
                                assertThat(failure.getSuppressed())
                                        .containsExactly(recheckFailure));
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
        when(item.evaluateAll(anyString()))
                .thenThrow(
                        new TimeoutError("Timeout exceeded while evaluating candidate identity"));
        when(item.count()).thenReturn(0);

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
    void anExplicitFrameDetachmentDuringIdentityEvaluationExcludesTheCandidate() {
        Locator matches = mock(Locator.class);
        Locator item = mock(Locator.class);
        Locator documentRoot = rootLocatingAll(matches);
        when(matches.count()).thenReturn(1);
        when(matches.nth(0)).thenReturn(item);
        when(item.evaluateAll(anyString())).thenThrow(frameDetachedFailure());

        LocatorBackendSearchResult result =
                backend(documentRoot)
                        .find(
                                roleQuery(),
                                LocatorScope.page(),
                                LocatorConfig.defaults(),
                                Duration.ofSeconds(1),
                                20);

        assertThat(result.candidates()).isEmpty();
        assertThat(result.discoveredCount()).isEqualTo(1);
    }

    @Test
    void aCanonicalMissingFrameFailureDuringCurrentElementInspectionProducesADetachedState() {
        Locator item = mock(Locator.class);
        when(item.evaluateAll(anyString())).thenThrow(frameMissingForSelectorFailure());
        when(item.count()).thenReturn(1, 0);

        ElementState state =
                new PlaywrightElement(
                                item,
                                ElementRole.UNKNOWN,
                                null,
                                LocatorScope.page(),
                                LocatorConfig.defaults(),
                                WaitBudget.start(Duration.ofNanos(1), () -> 0L))
                        .state();

        assertThat(state.detached()).isTrue();
    }

    @Test
    void anOpaqueElementFailureThatOnlyMentionsAMissingFramePropagatesUnchanged() {
        Locator item = mock(Locator.class);
        PlaywrightException failure =
                new PlaywrightException(
                        "browser disconnected after Failed to find frame for selector x");
        when(item.count()).thenReturn(1);
        when(item.evaluateAll(anyString())).thenThrow(failure);

        assertThatThrownBy(
                        () ->
                                new PlaywrightElement(
                                                item,
                                                ElementRole.UNKNOWN,
                                                null,
                                                LocatorScope.page(),
                                                LocatorConfig.defaults(),
                                                WaitBudget.start(Duration.ofNanos(1), () -> 0L))
                                        .state())
                .isSameAs(failure);
    }

    @Test
    void anOpaqueFailureThatMerelyMentionsFrameDetachmentPropagatesUnchanged() {
        Locator matches = mock(Locator.class);
        Locator documentRoot = rootLocatingAll(matches);
        PlaywrightException failure =
                new PlaywrightException("browser disconnected after a Frame was detached event");
        when(matches.count()).thenThrow(failure);

        assertThatThrownBy(
                        () ->
                                backend(documentRoot)
                                        .find(
                                                roleQuery(),
                                                LocatorScope.page(),
                                                LocatorConfig.defaults(),
                                                Duration.ofSeconds(1),
                                                20))
                .isSameAs(failure);
    }

    @Test
    void anIdentityTimeoutForAStillPresentCandidatePropagatesUnchanged() {
        Locator matches = mock(Locator.class);
        Locator item = mock(Locator.class);
        Locator documentRoot = rootLocatingAll(matches);
        TimeoutError timeout = new TimeoutError("Identity evaluation exceeded its bound");
        when(matches.count()).thenReturn(1);
        when(matches.nth(0)).thenReturn(item);
        when(item.evaluateAll(anyString())).thenThrow(timeout);
        when(item.count()).thenReturn(1);

        assertThatThrownBy(
                        () ->
                                backend(documentRoot)
                                        .find(
                                                roleQuery(),
                                                LocatorScope.page(),
                                                LocatorConfig.defaults(),
                                                Duration.ofSeconds(1),
                                                20))
                .isSameAs(timeout);
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
        when(item.evaluateAll(anyString())).thenThrow(backendFailure);

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

    private static PlaywrightException frameDetachedFailure() {
        return new PlaywrightException("Error {\n  message='Frame was detached\n  name='Error\n}");
    }

    private static PlaywrightException frameMissingForSelectorFailure() {
        return new PlaywrightException(
                "Error {\n"
                        + "  message='Failed to find frame for selector \"html >> iframe\"\n"
                        + "  name='Error\n"
                        + "}");
    }
}
