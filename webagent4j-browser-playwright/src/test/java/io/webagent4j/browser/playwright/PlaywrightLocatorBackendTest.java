package io.webagent4j.browser.playwright;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.microsoft.playwright.ElementHandle;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.PlaywrightException;
import com.microsoft.playwright.TimeoutError;
import io.webagent4j.common.LocatorException;
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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Regression coverage for Playwright locator discovery and structured-scope guards.
 *
 * <p>Candidate discovery performs one immediate current-DOM count and current-handle identity
 * inspection. Element metadata/state inspection also resolves current handles and never starts a
 * nested locator wait or uses {@link Locator#evaluateAll(String)}. Structured scopes preserve
 * strict semantic 0/1/N cardinality and never let a physical binding hide ambiguity.
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
    void currentCandidateInspectionsDoNotReResolveThroughEvaluateAllOrStartNestedWaits() {
        Locator matches = mock(Locator.class);
        Locator item = mock(Locator.class);
        ElementHandle identityHandle = mock(ElementHandle.class);
        ElementHandle attributeHandle = mock(ElementHandle.class);
        Locator documentRoot = rootLocatingAll(matches);
        when(matches.count()).thenReturn(1);
        when(matches.nth(0)).thenReturn(item);
        when(item.elementHandles()).thenReturn(List.of(identityHandle), List.of(attributeHandle));
        when(identityHandle.evaluate(anyString(), any()))
                .thenReturn(Map.of("identity", "webagent4j-test-candidate", "domOrder", 0));
        when(attributeHandle.evaluate(anyString(), isNull())).thenReturn(Map.of("id", "confirm"));

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

        verify(item, times(2)).elementHandles();
        verify(item, never()).evaluateAll(anyString());
        verify(item, never()).evaluate(anyString(), any(), any(Locator.EvaluateOptions.class));
        verify(identityHandle).dispose();
        verify(attributeHandle).dispose();
    }

    @Test
    void aMissingCandidateIdentityBridgeFailsClosedInsteadOfFabricatingAbsence() {
        Locator matches = mock(Locator.class);
        Locator item = mock(Locator.class);
        ElementHandle identityHandle = mock(ElementHandle.class);
        Locator documentRoot = rootLocatingAll(matches);
        when(matches.count()).thenReturn(1);
        when(matches.nth(0)).thenReturn(item);
        // A fresh recheck confirms the candidate is still present, so a missing bridge on it is a
        // genuine, persistent condition - not the transient candidate-just-disappeared race the
        // recheck also has to rule out - and must still fail closed rather than being fabricated
        // into absence.
        when(item.count()).thenReturn(1);
        when(item.elementHandles()).thenReturn(List.of(identityHandle));
        when(identityHandle.evaluate(anyString(), any())).thenReturn(Map.of("bridgeMissing", true));

        assertThatThrownBy(
                        () ->
                                backend(documentRoot)
                                        .find(
                                                roleQuery(),
                                                LocatorScope.page(),
                                                LocatorConfig.defaults(),
                                                Duration.ofSeconds(1),
                                                20))
                .isInstanceOf(LocatorException.class)
                .hasMessageContaining("identity bridge");

        verify(identityHandle).dispose();
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
    void aCandidateAbsentAtImmediateHandleResolutionIsExcludedFromThisPoll() {
        Locator matches = mock(Locator.class);
        Locator item = mock(Locator.class);
        Locator documentRoot = rootLocatingAll(matches);
        when(matches.count()).thenReturn(1);
        when(matches.nth(0)).thenReturn(item);
        when(item.elementHandles()).thenReturn(List.of());

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
    void anUnexpectedIdentityHandleTimeoutPropagatesUnchanged() {
        Locator matches = mock(Locator.class);
        Locator item = mock(Locator.class);
        Locator documentRoot = rootLocatingAll(matches);
        TimeoutError timeout = new TimeoutError("Identity handle query exceeded its bound");
        when(matches.count()).thenReturn(1);
        when(matches.nth(0)).thenReturn(item);
        when(item.elementHandles()).thenThrow(timeout);

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
    void aDifferentDocumentAdoptionRaceDuringIdentityIsExcludedOnlyWhenARecheckProvesAbsence() {
        Locator matches = mock(Locator.class);
        Locator item = mock(Locator.class);
        Locator documentRoot = rootLocatingAll(matches);
        PlaywrightException adoption = differentDocumentAdoptionFailure();
        when(matches.count()).thenReturn(1);
        when(matches.nth(0)).thenReturn(item);
        when(item.elementHandles()).thenThrow(adoption);
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
    void
            aDifferentDocumentAdoptionRaceDuringIdentityForAStillPresentCandidateIsRetryableNotFatal() {
        Locator matches = mock(Locator.class);
        Locator item = mock(Locator.class);
        Locator documentRoot = rootLocatingAll(matches);
        PlaywrightException adoption = differentDocumentAdoptionFailure();
        when(matches.count()).thenReturn(1);
        when(matches.nth(0)).thenReturn(item);
        when(item.elementHandles()).thenThrow(adoption);
        when(item.count()).thenReturn(1);

        // A still-present candidate racing a document-adoption transition is never accepted as a
        // match, but it is also never a fatal, non-retryable failure any more: resolving through a
        // frame/document transition is exactly the transient condition the caller's own bounded
        // wait loop exists to absorb. LocatorNotFoundException is what
        // io.webagent4j.common.LocatorFailureClassifier#isNotFound recognizes as retryable.
        assertThatThrownBy(
                        () ->
                                backend(documentRoot)
                                        .find(
                                                roleQuery(),
                                                LocatorScope.page(),
                                                LocatorConfig.defaults(),
                                                Duration.ofSeconds(1),
                                                20))
                .isInstanceOf(LocatorNotFoundException.class);
    }

    @Test
    void aMissingExecutionContextRaceDuringIdentityIsExcludedOnlyWhenARecheckProvesAbsence() {
        Locator matches = mock(Locator.class);
        Locator item = mock(Locator.class);
        Locator documentRoot = rootLocatingAll(matches);
        PlaywrightException contextLoss = describeNodeContextMissingFailure();
        when(matches.count()).thenReturn(1);
        when(matches.nth(0)).thenReturn(item);
        when(item.elementHandles()).thenThrow(contextLoss);
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
    void aMissingExecutionContextRaceDuringIdentityForAStillPresentCandidateIsRetryableNotFatal() {
        Locator matches = mock(Locator.class);
        Locator item = mock(Locator.class);
        Locator documentRoot = rootLocatingAll(matches);
        PlaywrightException contextLoss = describeNodeContextMissingFailure();
        when(matches.count()).thenReturn(1);
        when(matches.nth(0)).thenReturn(item);
        when(item.elementHandles()).thenThrow(contextLoss);
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
                .isInstanceOf(LocatorNotFoundException.class);
    }

    @Test
    void
            aFirefoxExecutionContextDestroyedRaceDuringIdentityIsExcludedOnlyWhenARecheckProvesAbsence() {
        Locator matches = mock(Locator.class);
        Locator item = mock(Locator.class);
        Locator documentRoot = rootLocatingAll(matches);
        PlaywrightException contextDestroyed = executionContextDestroyedFailure();
        when(matches.count()).thenReturn(1);
        when(matches.nth(0)).thenReturn(item);
        when(item.elementHandles()).thenThrow(contextDestroyed);
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
    void
            aFirefoxExecutionContextDestroyedRaceDuringIdentityForAStillPresentCandidateIsRetryableNotFatal() {
        Locator matches = mock(Locator.class);
        Locator item = mock(Locator.class);
        Locator documentRoot = rootLocatingAll(matches);
        PlaywrightException contextDestroyed = executionContextDestroyedFailure();
        when(matches.count()).thenReturn(1);
        when(matches.nth(0)).thenReturn(item);
        when(item.elementHandles()).thenThrow(contextDestroyed);
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
                .isInstanceOf(LocatorNotFoundException.class);
    }

    @Test
    void aProtocolEnvelopeFormOfExecutionContextDestroyedAlsoQualifies() {
        Locator matches = mock(Locator.class);
        Locator item = mock(Locator.class);
        Locator documentRoot = rootLocatingAll(matches);
        PlaywrightException contextDestroyed =
                new PlaywrightException(
                        "Error {\n"
                                + "  message='Execution context was destroyed, most likely because"
                                + " of a navigation\n"
                                + "  name='Error\n"
                                + "}");
        when(matches.count()).thenReturn(1);
        when(matches.nth(0)).thenReturn(item);
        when(item.elementHandles()).thenThrow(contextDestroyed);
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
    }

    @Test
    void aJsDetectedDocumentMismatchIsExcludedOnlyWhenARecheckProvesAbsence() {
        Locator matches = mock(Locator.class);
        Locator item = mock(Locator.class);
        ElementHandle identityHandle = mock(ElementHandle.class);
        Locator documentRoot = rootLocatingAll(matches);
        when(matches.count()).thenReturn(1);
        when(matches.nth(0)).thenReturn(item);
        when(item.elementHandles()).thenReturn(List.of(identityHandle));
        when(identityHandle.evaluate(anyString(), any()))
                .thenReturn(Map.of("documentMismatch", true));
        // A fresh recheck proves the candidate is genuinely gone - the mismatch the browser-side
        // bridge observed a moment earlier reflected a transient document transition, not a real,
        // standing wrong-document condition, so this candidate is safely excluded rather than
        // fatally failing the whole search.
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
    void aJsDetectedDocumentMismatchForAStillPresentCandidateFailsClosedButIsRetryable() {
        Locator matches = mock(Locator.class);
        Locator item = mock(Locator.class);
        ElementHandle identityHandle = mock(ElementHandle.class);
        Locator documentRoot = rootLocatingAll(matches);
        when(matches.count()).thenReturn(1);
        when(matches.nth(0)).thenReturn(item);
        when(item.elementHandles()).thenReturn(List.of(identityHandle));
        when(identityHandle.evaluate(anyString(), any()))
                .thenReturn(Map.of("documentMismatch", true));
        // A fresh recheck proves the candidate is still there: this candidate is never accepted as
        // a match (it is still failing closed, never silently treated as the same element), but a
        // persistent-this-poll document mismatch during frame/document descent is also never a
        // fatal, non-retryable error - it is reported as a typed, retryable "not found this poll"
        // so the caller's own bounded wait loop can absorb the settling window on the same shared
        // deadline, exactly like an ordinary not-yet-resolved candidate.
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
                .isInstanceOf(LocatorNotFoundException.class);
    }

    @Test
    void aMissingCandidateIdentityBridgeIsExcludedOnlyWhenARecheckProvesAbsence() {
        Locator matches = mock(Locator.class);
        Locator item = mock(Locator.class);
        ElementHandle identityHandle = mock(ElementHandle.class);
        Locator documentRoot = rootLocatingAll(matches);
        when(matches.count()).thenReturn(1);
        when(matches.nth(0)).thenReturn(item);
        when(item.elementHandles()).thenReturn(List.of(identityHandle));
        when(identityHandle.evaluate(anyString(), any())).thenReturn(Map.of("bridgeMissing", true));
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
    void anOpaqueFailureOnlyMentioningMissingContextDoesNotTriggerAnAbsenceRecheck() {
        Locator matches = mock(Locator.class);
        Locator item = mock(Locator.class);
        Locator documentRoot = rootLocatingAll(matches);
        PlaywrightException failure =
                new PlaywrightException(
                        "browser disconnected after Protocol error (DOM.describeNode): "
                                + "Cannot find context with specified id");
        when(matches.count()).thenReturn(1);
        when(matches.nth(0)).thenReturn(item);
        when(item.elementHandles()).thenThrow(failure);

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

        verify(item, never()).count();
    }

    @Test
    void aGenuineBackendFailureDuringIdentityResolutionPropagatesUnchanged() {
        Locator matches = mock(Locator.class);
        Locator item = mock(Locator.class);
        Locator documentRoot = rootLocatingAll(matches);
        RuntimeException backendFailure = new IllegalStateException("browser disconnected");
        when(matches.count()).thenReturn(1);
        when(matches.nth(0)).thenReturn(item);
        when(item.elementHandles()).thenThrow(backendFailure);

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
    void aDetachedPhysicalHandleIsExcludedFromThisPoll() {
        Locator matches = mock(Locator.class);
        Locator item = mock(Locator.class);
        ElementHandle handle = mock(ElementHandle.class);
        Locator documentRoot = rootLocatingAll(matches);
        when(matches.count()).thenReturn(1);
        when(matches.nth(0)).thenReturn(item);
        when(item.elementHandles()).thenReturn(List.of(handle));
        when(handle.evaluate(anyString(), any())).thenReturn(Map.of("absent", true));

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
        verify(handle).dispose();
    }

    @Test
    void aCanonicalMissingFrameFailureDuringCurrentElementInspectionProducesDetachedState() {
        Locator item = mock(Locator.class);
        when(item.elementHandles()).thenThrow(frameMissingForSelectorFailure());

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
        when(item.elementHandles()).thenThrow(failure);

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
        StructuredScopeLocators locators = structuredScopeLocators();
        Locator documentRoot = structuredScopeRoot(locators);
        when(locators.binding().count()).thenReturn(0);

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
        StructuredScopeLocators locators = structuredScopeLocators();
        Locator documentRoot = structuredScopeRoot(locators);
        when(locators.binding().count()).thenReturn(2);

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
        StructuredScopeLocators locators = structuredScopeLocators();
        ElementHandle identityHandle = successfulStructuredScopeHandshake(locators);
        when(locators.guarded().count()).thenReturn(1, 2);

        PlaywrightLocatorBackend backend = playwrightBackend(structuredScopeRoot(locators));
        IElement scope =
                backend.resolveUniqueContainer(
                        backend.context(),
                        "Shipping",
                        LocatorStrategyType.ACCESSIBLE_NAME,
                        Duration.ofSeconds(1));

        assertThatThrownBy(scope::state).isInstanceOf(AmbiguousLocatorException.class);
        verify(identityHandle).dispose();
    }

    @Test
    void aStructuredScopeRejectsPhysicalIdentityReplacementDuringPromotion() {
        StructuredScopeLocators locators = structuredScopeLocators();
        ElementHandle identityHandle = mock(ElementHandle.class);
        when(locators.binding().count()).thenReturn(1);
        when(locators.leased().elementHandles()).thenReturn(List.of(identityHandle));
        when(identityHandle.evaluate(anyString(), any()))
                .thenReturn(Map.of("identity", "webagent4j-scope-identity", "domOrder", 0));
        when(locators.promotion().count()).thenReturn(0);

        PlaywrightLocatorBackend backend = playwrightBackend(structuredScopeRoot(locators));

        assertThatThrownBy(
                        () ->
                                backend.resolveUniqueContainer(
                                        backend.context(),
                                        "Shipping",
                                        LocatorStrategyType.ACCESSIBLE_NAME,
                                        Duration.ofSeconds(1)))
                .isInstanceOf(LocatorNotFoundException.class);

        verify(identityHandle).dispose();
    }

    @Test
    void aUniqueStableStructuredScopeRemainsUsableAsALiveElement() {
        StructuredScopeLocators locators = structuredScopeLocators();
        ElementHandle identityHandle = successfulStructuredScopeHandshake(locators);
        ElementHandle stateHandle = mock(ElementHandle.class);
        when(locators.guarded().count()).thenReturn(1);
        when(locators.guarded().elementHandles()).thenReturn(List.of(stateHandle));
        when(stateHandle.evaluate(anyString(), isNull())).thenReturn(presentStateValue());

        PlaywrightLocatorBackend backend = playwrightBackend(structuredScopeRoot(locators));
        IElement scope =
                backend.resolveUniqueContainer(
                        backend.context(),
                        "Shipping",
                        LocatorStrategyType.ACCESSIBLE_NAME,
                        Duration.ofSeconds(1));

        assertThat(scope.state().present()).isTrue();
        verify(identityHandle).dispose();
        verify(stateHandle).dispose();
    }

    @Test
    void structuredScopeUsesAtomicLeasePromotionAndStableGuardInsteadOfSavedIndexes() {
        StructuredScopeLocators locators = structuredScopeLocators();
        ElementHandle identityHandle = successfulStructuredScopeHandshake(locators);
        when(locators.guarded().count()).thenReturn(1);

        Locator documentRoot = structuredScopeRoot(locators);
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
                                selector -> selector.startsWith("webagent4j_scope=a.l.")));
        verify(documentRoot)
                .locator(
                        org.mockito.ArgumentMatchers.<String>argThat(
                                selector -> selector.startsWith("webagent4j_scope=a.p.")));
        verify(documentRoot)
                .locator(
                        org.mockito.ArgumentMatchers.<String>argThat(
                                selector -> selector.startsWith("webagent4j_scope=a.g.")));

        verify(documentRoot, never())
                .locator(
                        org.mockito.ArgumentMatchers.<String>argThat(
                                selector -> selector.contains("nth(")));
        verify(identityHandle).dispose();
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

    private static StructuredScopeLocators structuredScopeLocators() {
        return new StructuredScopeLocators(
                mock(Locator.class), mock(Locator.class), mock(Locator.class), mock(Locator.class));
    }

    private static Locator structuredScopeRoot(StructuredScopeLocators locators) {
        Locator documentRoot = mock(Locator.class);
        when(documentRoot.locator(anyString()))
                .thenAnswer(
                        invocation -> {
                            String selector = invocation.getArgument(0);
                            if (selector.startsWith("webagent4j_scope=a.b.")) {
                                return locators.binding();
                            }
                            if (selector.startsWith("webagent4j_scope=a.l.")) {
                                return locators.leased();
                            }
                            if (selector.startsWith("webagent4j_scope=a.p.")) {
                                return locators.promotion();
                            }
                            if (selector.startsWith("webagent4j_scope=a.g.")) {
                                return locators.guarded();
                            }
                            throw new AssertionError("Unexpected selector: " + selector);
                        });
        return documentRoot;
    }

    private static ElementHandle successfulStructuredScopeHandshake(
            StructuredScopeLocators locators) {
        ElementHandle identityHandle = mock(ElementHandle.class);
        when(locators.binding().count()).thenReturn(1);
        when(locators.leased().elementHandles()).thenReturn(List.of(identityHandle));
        when(identityHandle.evaluate(anyString(), any()))
                .thenReturn(Map.of("identity", "webagent4j-scope-identity", "domOrder", 0));
        when(locators.promotion().count()).thenReturn(1);
        return identityHandle;
    }

    private record StructuredScopeLocators(
            Locator binding, Locator leased, Locator promotion, Locator guarded) {}

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

    private static PlaywrightException differentDocumentAdoptionFailure() {
        return new PlaywrightException(
                "Error {\n"
                        + "  message='Unable to adopt element handle from a different document\n"
                        + "  name='Error\n"
                        + "}");
    }

    private static PlaywrightException describeNodeContextMissingFailure() {
        return new PlaywrightException(
                "Error {\n"
                        + "  message='Protocol error (DOM.describeNode): "
                        + "Cannot find context with specified id\n"
                        + "  name='Error\n"
                        + "}");
    }

    /**
     * The operation-prefixed bare form Firefox's own driver surfaces this message in (no protocol
     * envelope), e.g. from {@code ElementHandle#evaluate}.
     */
    private static PlaywrightException executionContextDestroyedFailure() {
        return new PlaywrightException(
                "elementHandle.evaluate: Execution context was destroyed, most likely because of a"
                        + " navigation");
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
