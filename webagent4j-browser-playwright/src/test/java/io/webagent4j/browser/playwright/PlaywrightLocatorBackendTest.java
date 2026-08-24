package io.webagent4j.browser.playwright;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.PlaywrightException;
import com.microsoft.playwright.TimeoutError;
import io.webagent4j.dom.ElementState;
import io.webagent4j.dom.IElement;
import io.webagent4j.locator.AmbiguousLocatorException;
import io.webagent4j.locator.ILocatorEngine;
import io.webagent4j.locator.LocatorBackendQuery;
import io.webagent4j.locator.LocatorBackendSearchResult;
import io.webagent4j.locator.LocatorConfig;
import io.webagent4j.locator.LocatorNotFoundException;
import io.webagent4j.locator.LocatorScope;
import io.webagent4j.locator.LocatorStrategyType;
import io.webagent4j.locator.api.ElementRole;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Regression coverage for Playwright locator discovery and structured-scope guards.
 *
 * <p>Candidate discovery performs one immediate current-DOM count and identity inspection. Element
 * metadata/state inspection uses {@link Locator#evaluateAll(String)} and never starts a nested
 * {@link Locator#evaluate(String, Object, Locator.EvaluateOptions)} wait. Structured scopes
 * preserve strict semantic 0/1/N cardinality and never let a physical binding hide ambiguity.
 */
class PlaywrightLocatorBackendTest {

    @Test
    void aZeroRemainingTimeoutStillPerformsOneImmediateCountInsteadOfSkippingIt() {
        Locator matches = mock(Locator.class);
        Locator documentRoot = rootLocatingAll(matches);
        when(matches.count()).thenReturn(0);

        LocatorBackendSearchResult result =
                backend(documentRoot)
                        .find(
                                roleQuery(),
                                LocatorScope.page(),
                                LocatorConfig.defaults(),
                                Duration.ZERO,
                                20);

        verify(matches).count();
        assertThat(result.candidates()).isEmpty();
        assertThat(result.discoveredCount()).isZero();
    }

    @Test
    void currentCandidateInspectionsDoNotStartNestedPlaywrightWaits() {
        Locator matches = mock(Locator.class);
        Locator item = mock(Locator.class);
        Locator documentRoot = rootLocatingAll(matches);
        when(matches.count()).thenReturn(1);
        when(matches.nth(0)).thenReturn(item);
        when(item.evaluateAll(anyString()))
                .thenReturn(
                        Map.of("identity", "candidate", "domOrder", 0),
                        Map.of("count", 1, "value", Map.of("id", "confirm")));

        LocatorBackendSearchResult result =
                backend(documentRoot)
                        .find(
                                roleQuery(),
                                LocatorScope.page(),
                                LocatorConfig.defaults(),
                                Duration.ofNanos(1),
                                20);

        assertThat(result.candidates().getFirst().element().attributes())
                .containsEntry("id", "confirm");

        verify(item, times(2)).evaluateAll(anyString());
        verify(item, never()).evaluate(anyString(), any(), any(Locator.EvaluateOptions.class));
    }

    @ParameterizedTest
    @MethodSource("positiveTimeoutBudgets")
    void internalTimeoutHelpersNeverExceedTheRemainingCallerBudget(Duration budget) {
        double budgetMillis = toMillis(budget);

        for (int operationCount : new int[] {1, 4, 1_000}) {
            assertThat(PlaywrightLocatorBackend.operationTimeoutMillis(budget, operationCount))
                    .isPositive()
                    .isLessThanOrEqualTo(budgetMillis);
            assertThat(PlaywrightLocatorBackend.inspectionTimeoutMillis(budget, operationCount))
                    .isPositive()
                    .isLessThanOrEqualTo(budgetMillis);
            assertThat(PlaywrightLocatorBackend.identityTimeoutMillis(budget, operationCount))
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
    void aCandidateThatVanishesDuringIdentityEvaluationIsExcludedFromThisPoll() {
        Locator matches = mock(Locator.class);
        Locator item = mock(Locator.class);
        Locator documentRoot = rootLocatingAll(matches);
        when(matches.count()).thenReturn(1);
        when(matches.nth(0)).thenReturn(item);
        when(item.evaluateAll(anyString()))
                .thenThrow(new TimeoutError("identity evaluation timed out"));
        when(item.count()).thenReturn(0);

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
    void aGenuineBackendFailureDuringIdentityEvaluationPropagatesUnchanged() {
        Locator matches = mock(Locator.class);
        Locator item = mock(Locator.class);
        Locator documentRoot = rootLocatingAll(matches);
        RuntimeException backendFailure = new IllegalStateException("browser disconnected");
        when(matches.count()).thenReturn(1);
        when(matches.nth(0)).thenReturn(item);
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
                .isSameAs(backendFailure);
    }

    @Test
    void aCanonicalMissingFrameFailureDuringCurrentElementInspectionProducesDetachedState() {
        Locator item = mock(Locator.class);
        when(item.evaluateAll(anyString())).thenThrow(frameMissingForSelectorFailure());

        ElementState state =
                new PlaywrightElement(
                                item,
                                ElementRole.UNKNOWN,
                                null,
                                LocatorScope.page(),
                                LocatorConfig.defaults(),
                                1_000.0)
                        .state();

        assertThat(state.detached()).isTrue();
    }

    @Test
    void anOpaqueElementFailureThatOnlyMentionsAMissingFramePropagatesUnchanged() {
        Locator item = mock(Locator.class);
        PlaywrightException failure =
                new PlaywrightException(
                        "browser disconnected after Failed to find frame for selector x");
        when(item.evaluateAll(anyString())).thenThrow(failure);

        assertThatThrownBy(
                        () ->
                                new PlaywrightElement(
                                                item,
                                                ElementRole.UNKNOWN,
                                                null,
                                                LocatorScope.page(),
                                                LocatorConfig.defaults(),
                                                1_000.0)
                                        .state())
                .isSameAs(failure);
    }

    @Test
    void structuredScopeInitialZeroIsNotFound() {
        Locator bindingLocator = mock(Locator.class);
        Locator guardedLocator = mock(Locator.class);
        Locator documentRoot = structuredScopeRoot(bindingLocator, guardedLocator);
        when(bindingLocator.count()).thenReturn(0);

        PlaywrightLocatorBackend backend = playwrightBackend(documentRoot);

        assertThatThrownBy(
                        () ->
                                backend.resolveUniqueContainer(
                                        backend.context(),
                                        "Shipping",
                                        LocatorStrategyType.ACCESSIBLE_NAME,
                                        Duration.ofSeconds(1)))
                .isInstanceOf(LocatorNotFoundException.class);
    }

    @Test
    void structuredScopeInitialMultipleMatchesAreAmbiguous() {
        Locator bindingLocator = mock(Locator.class);
        Locator guardedLocator = mock(Locator.class);
        Locator documentRoot = structuredScopeRoot(bindingLocator, guardedLocator);
        when(bindingLocator.count()).thenReturn(2);

        PlaywrightLocatorBackend backend = playwrightBackend(documentRoot);

        assertThatThrownBy(
                        () ->
                                backend.resolveUniqueContainer(
                                        backend.context(),
                                        "Shipping",
                                        LocatorStrategyType.ACCESSIBLE_NAME,
                                        Duration.ofSeconds(1)))
                .isInstanceOf(AmbiguousLocatorException.class);
    }

    @Test
    void aGuardedStructuredScopeNeverHidesNewAmbiguity() {
        Locator bindingLocator = mock(Locator.class);
        Locator guardedLocator = mock(Locator.class);
        Locator documentRoot = structuredScopeRoot(bindingLocator, guardedLocator);
        when(bindingLocator.count()).thenReturn(1);
        when(guardedLocator.count()).thenReturn(2);

        PlaywrightLocatorBackend backend = playwrightBackend(documentRoot);
        IElement scope =
                backend.resolveUniqueContainer(
                        backend.context(),
                        "Shipping",
                        LocatorStrategyType.ACCESSIBLE_NAME,
                        Duration.ofSeconds(1));

        assertThatThrownBy(scope::state).isInstanceOf(AmbiguousLocatorException.class);
    }

    @Test
    void aGuardedStructuredScopeRejectsPhysicalIdentityReplacementInsideTheSameSeam() {
        Locator bindingLocator = mock(Locator.class);
        Locator guardedLocator = mock(Locator.class);
        Locator documentRoot = structuredScopeRoot(bindingLocator, guardedLocator);
        when(bindingLocator.count()).thenReturn(1);
        when(guardedLocator.count()).thenReturn(0);

        PlaywrightLocatorBackend backend = playwrightBackend(documentRoot);
        IElement scope =
                backend.resolveUniqueContainer(
                        backend.context(),
                        "Shipping",
                        LocatorStrategyType.ACCESSIBLE_NAME,
                        Duration.ofSeconds(1));

        assertThatThrownBy(scope::state).isInstanceOf(LocatorNotFoundException.class);
    }

    @Test
    void aUniqueGuardedStructuredScopeRemainsUsableAsALiveElement() {
        Locator bindingLocator = mock(Locator.class);
        Locator guardedLocator = mock(Locator.class);
        Locator documentRoot = structuredScopeRoot(bindingLocator, guardedLocator);
        when(bindingLocator.count()).thenReturn(1);
        when(guardedLocator.count()).thenReturn(1);
        when(guardedLocator.evaluateAll(anyString()))
                .thenReturn(Map.of("count", 1, "value", presentStateValue()));

        PlaywrightLocatorBackend backend = playwrightBackend(documentRoot);
        IElement scope =
                backend.resolveUniqueContainer(
                        backend.context(),
                        "Shipping",
                        LocatorStrategyType.ACCESSIBLE_NAME,
                        Duration.ofSeconds(1));

        assertThat(scope.state().present()).isTrue();
    }

    @Test
    void structuredScopeUsesBindAndGuardedSelectorModesInsteadOfSavedIndexes() {
        Locator bindingLocator = mock(Locator.class);
        Locator guardedLocator = mock(Locator.class);
        Locator documentRoot = structuredScopeRoot(bindingLocator, guardedLocator);
        when(bindingLocator.count()).thenReturn(1);

        PlaywrightLocatorBackend backend = playwrightBackend(documentRoot);
        backend.resolveUniqueContainer(
                backend.context(),
                "Shipping",
                LocatorStrategyType.ACCESSIBLE_NAME,
                Duration.ofSeconds(1));

        verify(documentRoot)
                .locator(
                        org.mockito.ArgumentMatchers.<String>argThat(
                                selector -> selector.startsWith("webagent4j_scope=a.b.")));

        verify(documentRoot)
                .locator(
                        org.mockito.ArgumentMatchers.<String>argThat(
                                selector -> selector.startsWith("webagent4j_scope=a.g.")));

        verify(documentRoot, never())
                .locator(
                        org.mockito.ArgumentMatchers.<String>argThat(
                                selector -> selector.contains("nth(")));
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

    private static Locator rootLocatingAll(Locator matches) {
        Locator documentRoot = mock(Locator.class);
        when(documentRoot.locator("*")).thenReturn(matches);
        return documentRoot;
    }

    private static Locator structuredScopeRoot(Locator bindingLocator, Locator guardedLocator) {
        Locator documentRoot = mock(Locator.class);
        when(documentRoot.locator(anyString()))
                .thenAnswer(
                        invocation -> {
                            String selector = invocation.getArgument(0);
                            if (selector.startsWith("webagent4j_scope=a.b.")) {
                                return bindingLocator;
                            }
                            if (selector.startsWith("webagent4j_scope=a.g.")) {
                                return guardedLocator;
                            }
                            throw new AssertionError("Unexpected selector: " + selector);
                        });
        return documentRoot;
    }

    private static PlaywrightLocatorBackend playwrightBackend(Locator documentRoot) {
        return new PlaywrightLocatorBackend(
                documentRoot,
                mock(ILocatorEngine.class),
                LocatorConfig.defaults(),
                LocatorScope.page());
    }

    private static PlaywrightLocatorBackend backend(Locator documentRoot) {
        return playwrightBackend(documentRoot);
    }

    private static LocatorBackendQuery roleQuery() {
        return new LocatorBackendQuery(
                LocatorStrategyType.ROLE,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty());
    }

    private static Map<String, Object> presentStateValue() {
        return Map.ofEntries(
                Map.entry("present", true),
                Map.entry("visible", true),
                Map.entry("enabled", true),
                Map.entry("editable", false),
                Map.entry("readOnly", false),
                Map.entry("checked", false),
                Map.entry("selected", false),
                Map.entry("focused", false),
                Map.entry("inViewport", true),
                Map.entry("clickable", true),
                Map.entry("covered", false));
    }

    private static PlaywrightException frameDetachedFailure() {
        return new PlaywrightException(
                "Error {\n" + "  message='Frame was detached\n" + "  name='Error\n" + "}");
    }

    private static PlaywrightException frameMissingForSelectorFailure() {
        return new PlaywrightException(
                "Error {\n"
                        + "  message='Failed to find frame for selector \"html >> iframe\"\n"
                        + "  name='Error\n"
                        + "}");
    }
}
